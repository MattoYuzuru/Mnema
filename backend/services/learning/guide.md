# Learning API runtime

`services:learning` is the standalone greenfield Learning API runtime. It has no
Gradle project dependency on legacy `core`, `media`, `import` or `ai`. Epic #74
added the canonical private Deck, deck-local LearningItem, native content,
EditingDraft and CaptureNote domains. Epic #75 now also owns immutable objectives,
exercise revisions, explicit content bindings, bounded Study session snapshots,
deterministic attempts, the baseline Study state reducer, material progress,
scheduled/replay/practice selection and retention cleanup; #266/#268 unified the
exercise mechanics into the seven below. Epic #76 added the greenfield media lifecycle
(`/api/media-assets`, deck media manifests, playback, physical GC): see
[upload transport](../../../docs/engineering/media-upload-transport.md),
[playback](../../../docs/engineering/media-playback.md),
[offline manifest](../../../docs/engineering/media-offline-manifest.md),
[GC](../../../docs/engineering/media-gc.md) and the
[runtime policy index](../../../docs/engineering/runtime-policy-index.md).

## Runtime contract

- Canonical application context: `/api`; there is no `/v2` or legacy service alias.
- Health: `/api/actuator/health/liveness` and
  `/api/actuator/health/readiness`. Readiness includes PostgreSQL; liveness does
  not depend on external systems.
- Build identity: `/api/actuator/info` exposes reproducible Gradle build metadata
  plus `release.id` from `MNEMA_BUILD_ID` (`dev` only as a local default).
- Private routes authenticate only an `Authorization: Bearer` access token.
  GET/HEAD require `learning.read`; other methods require `learning.write`.
  Health/info remain public, including when Identity is unavailable.
- Database: fresh Flyway history at `classpath:db/learning/migration`, owned schema
  `app_learning`, `baseline-on-migrate=false`. It never scans a legacy migration
  directory.
- AI layer (Epic #77): run 2 delivered the notification center, the usage ledger (`app.mnema.learning.usage`), the MBM
  compiler (`generation.mbm`), the `code_block` node and the provider foundation (`app.mnema.learning.ai`, below); generation
  sessions, steps and approval (`contracts/generation`) start with AI-04 (#287) in run 3.
- MBM compiler (#283): `app.mnema.learning.generation.mbm` is a pure package (no Spring, I/O or clock; identifiers come from
  the injected `IdAllocator`) that compiles MBM v1 to a native-v1 document and renders native-v1 back to MBM.
  `MbmCompiler.compile(source, MbmOptions, IdAllocator)` returns `MbmResult.Success` (document already read by
  `NativeDocumentReader`, media `MbmSlot`s, warnings) or `MbmResult.Failure` (at most 20 errors, no document); the
  `href`/`lang` profile is the reader itself (`NativeProfile` probes it), so there is no second validator.
  `MbmRenderer` writes `[[bN]]` handles and is lossless or refuses (`MbmUnsupportedContentException`: it re-compiles every
  block and compares). `MbmAutoFixer` repairs harmless slips before compiling, `MbmRepairList` formats errors for the repair
  prompt, `MbmLint` is the hook for the copy lint. Inline scanning has a linear work budget and a nesting bound that report
  `MBM_DOCUMENT_TOO_LARGE`. A fenced block outside `::mermaid` compiles to `code_block` (`BlockParser.codeBlock`). Writes use
  `NativeDocumentReader.read`; the stored-snapshot decoder uses `readRetained`, which keeps a `code_block` that is not valid v1 as an opaque node. The executable contract is `contracts/generation/mbm-v1`.

## Usage ledger and AI budget (#281)

`app.mnema.learning.usage` implements [`contracts/usage`](../../../contracts/usage/README.md): credits, reservations, the
fair-use buckets, `GET /api/usage` and the estimate. Migration `V26__usage_ledger.sql`; policy keys are in the
[runtime policy index](../../../docs/engineering/runtime-policy-index.md).

- **Endpoints.** `GET /api/usage` (`learning.read`, `private, no-store`) is a pure read of the current period and never
  writes. `POST /api/decks/{deckId}/generation-estimates` takes exactly one of `{spec}` or `{edit}` and reserves and
  persists nothing; it is a POST, so it needs `learning.write`. A foreign or absent deck is the opaque 404 and is decided
  before the body is read.
- **Model.** `usage_allowance` (owner x period: plan, bar, Free portions, burst fraction, fair-use and cap limits, frozen
  when the period is first used, rewritten only when the entitlement changes), `usage_balance` (`unlocked`, `used`,
  `reserved`, `row_version`, `CHECK used + reserved <= unlocked`), `usage_reservation`
  (`ACTIVE` -> `SETTLED` | `RELEASED` | `EXPIRED`; `SESSION` | `TURN` | `STEP`; opaque `session_id` and `turn_id`),
  `usage_ledger_entry` (append-only by trigger; `idempotency_key` globally unique; `reference` is an opaque step or call id
  that the table restricts to a token pattern, so no prose or personal data can be stored), `usage_counter` (fair-use and
  count-cap windows) and `entitlement_inbox`. A period is a calendar month, windows and weeks are calendar windows, all in
  `learning.usage.calendar-zone`.
- **Writers join the caller's transaction** (`Propagation.MANDATORY`): `UsageLedger.reserve`, `settle`, `release`, `renew`
  and `consume` commit or roll back with the domain change they pay for. `USAGE_LIMIT_REACHED`
  (`UsageLimitReachedException`, from `reserve` and `consume`) propagates through that transaction, which rolls back: do
  not catch it inside the transaction, let it reach the problem handler (the contract's "409 before any state change").
  `EstimateExceededException` and `ReservationNotActiveException` are thrown before anything is written and are declared
  `noRollbackFor` on `settle`, `release` and `renew`: the caller can catch them, record the artifact failure and commit.
  A pathological loss of the admission race (32 re-reads) is `UsageContentionException`, answered as a retryable
  `503 USAGE_UNAVAILABLE`.
- **Lock order, a rule for callers.** Writers lock reservation, then balance, then the owner's notification cursor
  (`settle`, `release`, `renew`, the sweep); balance, then the new reservation (`reserve`); counters in window order, then
  the cursor (`consume`). A debit or consumption that crosses a threshold publishes a notification, which holds the cursor
  lock until the caller commits. So call the usage writers *before* publishing the caller's own notifications in the same
  transaction, and as late as the domain write allows.
- **Admission is one conditional update** on the balance row (`... WHERE unlocked - used - reserved >= :hold AND
  row_version = :v`). Losing the row version means another admission committed, so the loser re-reads: it gets
  `409 USAGE_LIMIT_REACHED` only when the remainder really is too small. The hold expires at
  `min(learning.usage.reservation-ttl, end of the period)`; the default `PT2H` is the AI-01 decision the contract left open
  (longer than the `PT1H` bound of a step run plus margin). `UsageLedger.renew(owner, reservationId)` keeps a live hold
  alive: `expiresAt = min(now + ttl, period end)`, never shortened, never past the period, idempotent, owner-checked and
  row-locked; the step scheduler (AI-04) calls it for a session whose steps wait on the daily burst or still run, and on an
  ended hold it throws `ReservationNotActiveException`. `UsageLedger.expireDue` (scheduled by `UsageExpiryWorker`,
  `FOR UPDATE SKIP LOCKED`, safe on every instance) returns orphaned holds; a hold that outlived its period ends as
  `SETTLED` or `RELEASED` by whether it debited, an orphan that merely timed out is `EXPIRED`.
- **Settlement by fact.** `settle` records a `DEBIT` against its hold once per idempotency key (`debit:{stepId}:{attempt}`);
  a repeat changes nothing and reports `replayed`. A debit above what the hold still has is `EstimateExceededException`
  (the artifact fails with `ESTIMATE_EXCEEDED`, nothing is written); a debit on an ended hold is
  `ReservationNotActiveException`. `release` ends the hold (`SETTLED` after at least one debit, else `RELEASED`) and returns
  the remainder. Ledger entries carry the rate-card version of their reservation, never the current one.
- **Free weekly unlock.** The bar opens in portions `learning.usage.free-weekly-portions` (13, 13, 12, 12): the first on the
  1st, the next on each following Monday 00:00 in the zone, accumulating within the month, nothing carried over; a fifth
  Monday unlocks nothing extra. Each opening is one `GRANT` ledger entry written when the account next reserves. A refusal
  names `window: WEEK`, the next unlock as `renewsAt`, and `fitsAfterRenewal` computed from what that portion will free.
- **Daily burst.** On paid plans the debits of one calendar day are limited to `learning.usage.daily-burst-fraction` of the
  bar. It never fails an admission or a `settle`; `UsageLedger.dailyDebitRoom(owner)` is the query the step scheduler (AI-04)
  uses to park steps until `resetsAt`. `GET /api/usage` reports `deferredUntil: null`: only the scheduler knows which
  steps wait.
- **Fair-use buckets and count caps** are outside the bar. `consume(owner, bucket, amount, key, reference)` counts speech to
  text (seconds), answer checks and the count caps (podcasts, quality images, high fact check, smart plan) against their
  monthly and daily windows, once per key, locking the windows in a fixed order and checking before writing. The widest
  exhausted window is the one reported. A cap of 0 is "not offered": `offered: false`, no `renewsAt`. Speech to text on a
  plan without a monthly limit (Max) has only the per-day velocity limit. Smart plans are one bucket: Plus 4 a month, Pro one
  a week, Max 8 a month (4 weekly plus 4 Pro).
- **Notifications.** A write that crosses 80, 90 or 100 percent of a bucket publishes the highest threshold it crossed as
  `USAGE_LOW` (once per bucket, period and threshold by dedupe key) and a window that runs out publishes
  `USAGE_EXHAUSTED` keyed by bucket, window kind and window start (`usage:{bucket}:{WINDOW}:{windowStart}:exhausted`), so the
  weekly Free window fires every week, the last Free window (from the fourth unlock) is its own instance and a day and a
  month that start together never share a key, all inside the transaction of the debit or consumption.
- **Estimate.** `GenerationSpecInterpreter` is the port for what a spec implies; `StandardSpecInterpreter` validates the
  shape strictly and prices MATERIALS (one artifact per NOTE source or one when merged; `AUTO` effort is priced as detailed (a hold covers the worst case the planner may choose);
  one audio clip and one image search per artifact when declared; a low fact check per artifact unless the effort is
  short) and EXERCISES (`EXACT`, `AUTO` = five per target or what the session limit allows, `BUDGET_PERCENT` = what the
  share of the remaining budget buys, at least one per target). `REVISE_*` is `422 SPEC_NOT_SUPPORTED`. Limits above
  `learning.generation.max-*` are `422 RESOURCE_LIMIT_EXCEEDED` with `limit` and `limits`, never clamped. It does not check
  that notes or items exist or belong to the owner, nor that an edit's session and artifact exist: that is the generation
  module's boundary (AI-04, which supplies its own interpreter and the personal-data warnings). p95 is the sum of rate-card
  weights, p50 is `ceil(0.6 x sum)` over unrounded weights, exercises are `ceil(8 x n / 5)`.
- **Entitlements.** `EntitlementSource` is the port; `ConfigEntitlementSource` serves `learning.usage.entitlements.default-plan`
  with per-account `learning.usage.entitlements.overrides.<accountUuid>`. A plan change mid-period applies at once to the
  limits and adds the missing credits as a `GRANT`; credits are never clawed back within a period. `EntitlementInbox` is the
  validated, idempotent insert of billing's snapshots (no consumer yet, no endpoint). The rate card and allowances are
  classpath copies of the contract files (`usage/*.json`); a test keeps them identical.
- **Retention.** Counters of windows older than 90 days are deleted by the expiry worker. Ledger rows are immutable and are
  kept.
  TODO(account-deletion task, owner: the epic that adds Learning's account purge; whether billing (#79) must keep
  ledger rows for a financial retention period is decided there): Learning has no account purge path today (only study retention exists), so nothing deletes usage rows when an
  account is deleted. The rows hold no personal data (an account id and opaque references), so the purge should delete by
  `owner_id` in this order: `usage_ledger_entry`, `usage_reservation`, `usage_balance`, `usage_allowance`,
  `usage_counter`, `entitlement_inbox`; `DELETE` stays allowed on the ledger for that reason (only `UPDATE` is blocked).
- **Tests.** `app.mnema.learning.usage`: PostgreSQL integration tests with a movable clock (`UsageTestConfiguration`) cover
  parallel reservations, idempotent settlement, expiry and rollover, the Free schedule across months, the burst, the buckets
  and the notifications; `UsageContractTest` reproduces the examples of `contracts/usage/usage.json` from database state.

## Shared platform contracts

- Entity identifiers are non-nil RFC 9562/IETF UUIDs stored as PostgreSQL `uuid`.
  New command identifiers are UUIDv4 or UUIDv7; the portable Java generator emits
  UUIDv4 without an additional dependency.
- Command identity is global by `command_id`. A retry must have the same actor,
  scope, type and canonical payload. An exact retry receives the stored JSON
  result; any mismatch returns `IDEMPOTENCY_CONFLICT` when exposed over HTTP.
  The action and receipt share one JDBC transaction, so failure leaves neither
  side effects nor an in-progress receipt.
- Payload canonicalization is a durable protocol: UTF-8 JSON with lexicographically
  sorted object fields, preserved array order, normalized finite numbers and a
  fixed escaping policy. `CanonicalJsonHasher` writes these bytes itself instead of through a
  JSON library, so stored receipt digests cannot change with Jackson defaults or upgrades.
- Mutable rows use a non-negative `row_version`. Repository SQL performs an update
  guarded by the expected version, and `CompareAndSetExecutor` accepts exactly one
  changed row or raises `VERSION_CONFLICT`.
- API failures use `application/problem+json` (RFC 9457). Stable machine codes are
  `IDEMPOTENCY_CONFLICT`, `VERSION_CONFLICT`, `PRECONDITION_REQUIRED`, `INVALID_REQUEST`,
  `RESOURCE_NOT_FOUND`, `SESSION_EXPIRED`, `PRESENTATION_EXPIRED`, `METHOD_NOT_ALLOWED`
  and `INTERNAL_ERROR`; the AI layer adds `USAGE_LIMIT_REACHED` (409) and `SPEC_NOT_SUPPORTED` (422). Public details
  never contain exception messages, SQL or stored command data. A code keeps its fixed status, title and detail; the
  extension members a contract lists for it (`bucket`, `limit`, `limits`, `capability`, `kind` ...) travel in a typed
  `ProblemExtension` (`platform.api`; the exception implements `ProblemExtension.ProblemExtensionSource` and the handler writes the
  members for every code, deck-hub `limit` included as a number): a small insertion-ordered map of strings, booleans, integers, instants, enums and
  lists or maps of those, whose names can never replace `type`, `title`, `status`, `detail`, `instance` or `code`.

PostgreSQL integration tests are fail-closed: Docker absence or container startup
  failure fails the build rather than skipping the suite.

## Identity boundary

Set `MNEMA_IDENTITY_ISSUER` to the exact Identity HTTPS issuer. Release templates
give both services the same environment-specific issuer. Learning fetches public
keys from `/oauth2/jwks` and validates RS256, `at+jwt`, issuer, `mnema-api` audience,
required timestamps, canonical non-nil UUID subject and generation shape.
Key caching/rotation never replaces the active-account check: after scope
authorization, every private request relays the same token to `/userinfo` and
requires a successful response with the same subject. No successful UserInfo
result is cached; Learning neither queries Identity tables nor imports its code.

The check is a request boundary, not a distributed transaction: already-authorized
in-flight work may finish while a concurrent logout/revocation commits. New requests
must consult Identity again. Domain ACL and actor-bound transactions remain the
owning content slice's responsibility; authentication alone does not authorize an ID.

Transport never follows redirects, shares no browser cookies, has a two-second
whole-response deadline, at most 32 simultaneous calls and bounded bodies (64 KiB
JWKS, 16 KiB UserInfo). Capacity exhaustion, timeout, malformed/mismatched UserInfo
or unexpected status fail closed with `IDENTITY_UNAVAILABLE` / 503. Identity 401/403
becomes `AUTHENTICATION_REQUIRED` / 401; insufficient Learning scope is
`ACCESS_DENIED` / 403. All use the same RFC 9457 vocabulary as MVC and `no-store`;
no token, response body or exception details are exposed. Local token/key validation
failure is 401. Missing issuer permits maintenance startup but never authentication.

`learning.identity.transport-base` can specify a trusted alternate HTTPS transport
endpoint without changing the issuer claim. The explicit test-only
`learning.identity.allow-loopback-http=true` accepts plaintext solely at literal
127.0.0.1 or [::1]; it is not enabled in release templates. The Compose issuer still
needs a trusted local HTTPS endpoint for authenticated flows, as Identity does.

Security behavior is tested through actual Learning HTTP with real PostgreSQL and
a controlled Identity protocol fixture, including a stalled body after headers,
concurrency rejection/recovery, duplicate JSON fields and per-request revocation.
This fixture is complemented by the real packaged Identity/Learning black-box
harness in `scripts/learning-security` and the HTTPS browser authoring harness in
`scripts/browser-identity`. Those local checks are not deployment or production
capacity evidence.

## Content and authoring contract

- `/api/decks` owns private Deck creation, bounded listing/detail and CAS metadata
  updates with global command receipts. `DELETE /api/decks/{deckId}` requires a
  strong `If-Match` deck version and returns 204. It tombstones the deck: current
  deck-scoped API reads return 404, while immutable revisions, study evidence and
  account-owned media remain for retention/history. Physical purge is separate.
- `/api/decks/{deckId}/items` owns deck-local logical identity, immutable revisions,
  current/historical reads and atomic publication.
- `/api/decks/{deckId}/exercises` owns owner-only bounded reads and atomic
  publication of stable objectives and immutable exercise revisions. There are
  seven mechanics (`SELF_CHECK`, `FREE_RESPONSE`, `CLOZE`, `CHOICE`, `MATCH`, `ORDER`,
  `CATEGORIZE`);
  media kind is content, never a mechanic. A revision stores independent
  `content` (typed slots of `TEXT`, `MATERIAL`, `IMAGE`, `AUDIO`, `VIDEO`,
  `YOUTUBE` blocks), a private `answerKey` and an `evaluatorPolicy`; the
  objective is a stable identity plus a human `title` and never holds an answer
  key. Every object has an exact field set, schema version 2 and UTF-16 length
  limits; slot profiles (`PROMPT`, `REFERENCE`, `COMPACT`, `SEQUENCE`) bound block kinds,
  block counts and text per block (`contracts/study/mechanics.json` is the wire
  contract). The server derives bindings (the `ASSESSED` subject and one
  `CONTEXT` row per quoted material revision), validates `MATERIAL` nodes against
  the pinned current item revision, and pins every `IMAGE`/`AUDIO`/`VIDEO` asset
  with its declared kind in `exercise_media_ref` (at most 32 media blocks).
  A foreign or stale member/revision is an opaque 404, an absent node or an
  over-long quoted text is 400. `ai-semantic` evaluation and `TEXT_OR_SPEECH`
  input are rejected with 409 `CAPABILITY_UNAVAILABLE` unless the capability is
  available (see `GET /api/capabilities`). The optional
  `memberKey` list filter remains cursor-bounded and returns each current
  exercise with its current objective summary so authoring clients can reuse a
  direction without scanning every exercise or exposing identifiers for input.
  `DELETE /api/decks/{deckId}/exercises/{exerciseId}` requires the strong deck
  version in `If-Match`, returns 204, removes the current roster entry and
  compacts ordinals. Historical revisions and completed attempts remain.
  A stale version yields 412; an unknown/removed or foreign resource yields 404.
  Text rules (`FREE_RESPONSE` and each `CLOZE` blank) set `matchingMode` to
  `STRICT` or `SOFT`. Strict uses the stored NFC/trim/case rules. Soft also
  removes canonical Unicode combining marks, punctuation, Unicode spaces and
  dashes before exact comparison; this intentionally treats some distinct
  spellings as equivalent without accepting arbitrary typos. Soft answers that
  become empty are rejected at authoring and never count as correct. A `CLOZE`
  blank is `FIXED` (5..20) or `ANSWER_LENGTH`, which requires every accepted answer
  of that blank to have the same NFC code-point length; the presentation supplies
  the length and neither mode limits input.
  `ORDER` has 2..12 items (`SEQUENCE` slot: one text-like and at most one media block,
  text up to 1000 UTF-16 units with newlines kept for code) and an explicit
  `{kind:"ORDER", sequence}` key that is an exact permutation of the item ids; order
  is never inferred. Items with identical learner-visible blocks (`OrderEquivalence`: the
  same canonical JSON, ignoring item ids and author-only audio/video titles) are
  interchangeable, nothing else is. The result is binary (`CORRECT`/`INCORRECT`,
  evidence `MEDIUM`) with `correctSequence` and per-position correctness from the same
  comparison. `CATEGORIZE` has 2..6 categories (labels up to 80 UTF-16 units, unique
  after trim and case fold; an empty category is a valid distractor) and 2..12 `COMPACT`
  items; its key assigns every item to exactly one existing category (a missing
  category at publication is 400). The result is `CORRECT`/`PARTIAL`/`INCORRECT`
  (evidence `LOW`) with per-item feedback. `MappingRules` is the one mapping validator:
  `bijection` for `MATCH` and `totalManyToOne` for `CATEGORIZE`, two distinct rule sets
  over one totality check, used for authored keys and learner responses.
- `GET /api/capabilities` (authenticated, `private, no-store`) reports
  `aiAssessment` and `speechToText` as `{available, reason}`. A capability is
  available only when `learning.features.ai-assessment.enabled` /
  `learning.features.speech-to-text.enabled` is true **and** a
  `SemanticAssessmentProvider` / `SpeechToTextProvider` bean exists; there is no
  implementation today, so both stay unavailable (`DISABLED` or
  `PROVIDER_NOT_CONFIGURED`). Candidates whose evaluator needs an unavailable
  capability are never issued and an `ai-semantic` answer is `UNAVAILABLE`, never
  exact-matched.
- `/api/decks/{deckId}/study-sessions` starts and resumes owner-only
  `SCHEDULED`, `REPLAY` and `PRACTICE` snapshots. Candidate preparation reads at
  most 500 exercise rows per poll, selection scans at most 80 candidates and a
  response contains at most 20 immutable presentations. The authenticated
  `zoneinfo` claim determines the local study date; invalid or absent values fall
  back to UTC, and clients cannot submit a timezone. Resume returns only
  presentations without a terminal attempt. A presentation carries learner
  `content` resolved once at issue time (`MATERIAL` becomes `TEXT`; `MATCH` sides, `ORDER` items and `CATEGORIZE` items
  are shuffled once at issue with a `SecureRandom` source and persisted, so reads and replays
  repeat the same order; with three or more `ORDER` items a draw that already shows the
  solved class sequence is redrawn up to 16 times and then rotated, with two items the draw
  is uniform; categories keep their authored order) and never an answer key,
  accepted strings, media titles, unrevealed transcripts or bindings.
  `POST .../presentations/{id}/transcript` and `.../hints` (CLOZE blanks with
  `firstLetterHint`) are server-recorded, idempotent reveals that evidence reads.
  A scheduled snapshot pins total and new-objective limits; the current presets are
  quick 10/2 and standard 20/5. Selection uses the same reducer and attempt path for
  both, prioritizing due, then introduced, then allowed new objectives.
- `/api/decks/{deckId}/study-sessions/replay-sources` returns at most 20 completed
  scheduled sessions from the authenticated account's current local study date.
  Scheduled selection is due-first and then introduces new objectives. Practice
  defaults to already introduced objectives, supports deterministic seeded or
  weakest-first order, and admits new objectives only when explicitly requested.
- `/api/decks/{deckId}/study-sessions/{sessionId}/attempts` terminalizes one
  server-issued presentation. All seven evaluators are deterministic;
  only `SCHEDULED` writes evidence and one versioned `mnema-baseline-v1`
  transition, for the single subject objective (`CONTEXT` materials never gain
  exposure, evidence or state). `FREE_RESPONSE` and each `CLOZE` blank normalize
  against the private answer key; a recorded first-letter hint caps a non-incorrect
  cloze at `MEDIUM`, a revealed transcript caps any result at `LOW`. `CHOICE` and
  `MATCH` accept only ids that were issued and always produce `LOW` recognition
  evidence (`ORDER` `MEDIUM` sequencing, `CATEGORIZE` `LOW` recognition; `ORDER` and
  `CATEGORIZE` need an exact permutation / a total assignment of the issued ids, anything else
  is 400 and consumes nothing); a `MATCH` completed after a wrong pair check is `PARTIAL` with
  `PAIR_RETRY`. The client sends no hint list: hint use is a server record. A pinned
  asset that is not READY (or whose verified source is not the declared kind)
  gives `NOT_ASSESSED`/`MEDIA_NOT_READY` and no transition. Exact retries
  return the durable outcome, conflicting attempt IDs
  never add transitions, and raw scheduled response JSON expires separately after
  30 days. The first terminal receipt atomically removes that presentation from
  the resumable batch; after its last presentation, the bounded session becomes
  `COMPLETE` in the same transaction.
- `POST /api/exercise-previews` evaluates one draft action for the exercise editor with
  the same `AttemptEvaluation` and first-letter rule as Study, but with no deck, session,
  attempt, receipt or media lookup: it has no repository and writes nothing. It needs the
  `learning.write` scope, answers `Cache-Control: private, no-store` and accepts at most
  64 KiB. The exercise (type, schema version 2, content, answer key, evaluator) passes the
  publication validation structurally (`MATERIAL` blocks and media ids are opaque). Actions
  are `SUBMIT` (client-reported `hintedBlankIds`, `pairMistakes`, `transcriptRevealed`),
  `PAIR_CHECK` and `HINT`; `ai-semantic` yields `UNAVAILABLE`, and `FREE_RESPONSE`
  `referenceContent` is `[]`. Every validation failure is the same opaque 400
  (`contracts/study/preview.json` is the wire contract).
- `/api/decks/{deckId}/study-restarts` starts a new learning epoch for objectives
  under explicitly selected current materials. It locks objectives in UUID order,
  keeps prior evidence/transitions and makes old presentations non-assessing.
- `/api/decks/{deckId}/study-progress` returns a cursor-bounded current-material
  projection with `NOT_STARTED`, `LEARNING`, `DUE` or `ON_TRACK`, exact objective
  coverage and relevant timestamps. It deliberately exposes no mastery percentage.
- The scheduled retention worker deletes expired raw scheduled responses in locked
  batches of 500 and clears expired replay/practice outcomes while preserving their
  global attempt-ID tombstones. `mnema.study.retention.initial-delay` and
  `mnema.study.retention.fixed-delay` default to `PT1H`.
- `/api/editing-drafts` owns bounded acknowledged server drafts; autosave never
  publishes.
- `/api/capture-notes` owns durable quick notes and idempotent conversion while
  retaining source/provenance. `GET /api/capture-notes?deckId=...&limit=...&cursor=...`
  returns an owner-checked, cursor-bounded page of unarchived, unconverted notes
  with `total` for the whole deck. `DELETE /api/capture-notes/{noteId}` requires
  the note's strong `If-Match` version and returns 204. The account-wide list
  retains its prior response shape, without `total`.
- Native document v1, immutable block/page storage and counted structural edits
  back both material and exercise membership roots. Exercise writes advance the
  Deck CAS and receipt in the same transaction.

- Deck hub (#285, [`contracts/decks/hub.json`](../../../contracts/decks/hub.json)):
  `GET /api/decks/{id}/insights` (`DeckInsightsService`) is one snapshot-isolated read-only transaction over current
  projections: coverage, per-material study state with the exact `study-progress` rule, `dueByDay[7]` in the
  `zoneinfo` claim zone (fallback `Europe/Moscow`; Study's own fallback stays UTC), enabled exercises per mechanic and
  open captures. The item list takes `sort=exerciseCount` (enabled assessed exercises ascending, ties by authoring
  ordinal, keyset cursor bound to the Deck revision; one statement reuses the member-page traversal so every entry keeps
  its true ordinal) and `include=exerciseCount`; every summary carries `exemplar`. «Эталон» is the table
  `deck_item_exemplar` (V25), not a revision: `POST .../items/{memberKey}/exemplar` sets a desired value under a
  per-Deck advisory lock (≤10), preconditioned by `expectedItemRevisionId`; `deleteHead` drops the flag in the delete
  publication and reads join `deck_head_item`, so a stale row is never visible. Bulk delete
  (`ItemBulkDeleteService`) resolves the selection against the named Deck revision's immutable member root and deletes
  through `ItemService.publish` in chunks of 100 with deterministic chunk command IDs; the outer receipt is stored at
  the end, so a crash between chunks is repaired by an exact retry. Limit problems carry the typed
  `ProblemExtension` member `limit`. Measured numbers are in the #285 PR evidence.

## Notification center

`app.mnema.learning.notification` implements [`contracts/notifications`](../../../contracts/notifications/README.md)
(AI-07, #284): a durable, general in-app inbox. The contract files are normative; this section records only what the
code adds.

- **Producer port.** `NotificationPublisher.publish(owner, kind, dedupeKey, params, route)` has `MANDATORY` propagation:
  it joins the transaction of the domain change and fails without one, so there is no outbox and a notification exists
  exactly when its change commits. Call it as the **last write** of that transaction: it locks the owner's
  `notification_cursor` row (created on first use) to allocate `seq`, and the lock lasts until the caller commits. Under
  the lock it checks `(owner, dedupe_key)` first; a repeat is a no-op (`false`, the first stands, no `seq` consumed). A
  dedupe key held only by an expired row is free again. The 201st notification of an owner deletes everything beyond the
  newest 200 sequence slots.
- **Params are validated, not trusted.** `NotificationKind` carries each kind's severity, route rule and exact params
  fields; only UUIDs, bounded tokens (`[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}`), non-negative counts and `Instant`s pass, so
  prose and personal data cannot be stored. A violation is a producer bug and throws `IllegalArgumentException`.
  `MEDIA_PROCESSING_FAILED` is `DYNAMIC`: `WORKSHOP` with a `sessionId`, `DECK` with only a `deckId`, else `NONE`;
  the publisher rejects any other route.
- **HTTP.** `GET /api/notifications` (`limit` 1..100, default 20; `after` for the ascending catch-up; `cursor` for the next
  page; `after` with `cursor` is 400), `PUT /api/notifications/read-cursor` (`{"readUpto":"<seq>"}`, a monotonic maximum,
  above the latest seq is 400) and `DELETE /api/notifications/{id}` (204, repeat 204, foreign/absent/expired opaque 404). All
  answer `Cache-Control: private, no-store`. The list `ETag` is a strong opaque
  `"n-<latestSeq>-<readUpto>-<visibleCount>-<activeWork>-<earliestExpiryEpochSec>-<queryHash>"`; the visible count makes a
  dismissal, expiry or eviction change it and the query hash keeps different `limit`/`after`/`cursor` apart. A matching
  `If-None-Match` is a 304 without a body and without reading the page. The list is read in one `REPEATABLE READ`
  snapshot. Scopes are the blanket rule of the security chain: `learning.read` for GET, `learning.write` for the rest.
- **`activeWork`** is `0` until generation exists: `ActiveWorkCounter` is satisfied by the placeholder `NoActiveWork`.
  TODO(AI-04, #287): replace it with the owner's count of sessions in `PLANNING` or `RUNNING` and delete the placeholder.
- **Retention.** `expires_at = created_at + learning.notifications.retention` (default 30 days) is fixed at publication.
  `NotificationRetentionWorker` deletes expired rows in locked batches of 500 (at most 20 batches per tick;
  `learning.notifications.cleanup-initial-delay` `PT5M`, `cleanup-interval` `PT1H`). The cap needs no sweep because
  publication enforces it. Dismissed rows stay until expiry so their dedupe key still holds.
- **First producer.** `MediaProcessingRepository` publishes `MEDIA_PROCESSING_FAILED` (`media:{assetId}:failed`, route
  `NONE`, deck/session/artifact/slot null because the pipeline does not know them) in the transaction that records a
  terminal failure: a rejected asset (`VERIFICATION_REJECTED`) and exhausted retries or an interrupted asset that ran out
  of attempts (`PROCESSING_FAILED`). Retryable attempts notify nobody. There is deliberately no "media ready" kind.
- Keys are listed in the [runtime policy index](../../../docs/engineering/runtime-policy-index.md).

## AI provider layer

`app.mnema.learning.ai` is the domain-free provider foundation of the AI layer (AI-02, #282; contracts in
[`contracts/generation`](../../../contracts/generation/README.md), architecture §4, §8, §9). Generation sessions, steps
and the UI (AI-04+) build on it; nothing here debits the user's quota, it only reports usage and cost.

- **Ports.** `TextGeneration` (route, ordered segments with a `cacheable` flag that must form a leading run, output
  contract `MBM_TEXT` or `JSON`, `maxOutputTokens`, temperature, deadline, opaque `userKey`, optional `StreamListener`,
  step id and attempt) returns `AiResult<TextResponse>`: text, finish reason, usage `{promptTokens, cacheHitTokens,
  cacheMissTokens, completionTokens}`, cost in micro-USD, provider request id and the route used. A failure is a sealed
  `AiFailure` (`RATE_LIMITED`, `TRANSIENT`, `TIMEOUT`, `INVALID_OUTPUT`, `REFUSAL`, `BUDGET_EXHAUSTED`, `NOT_CONFIGURED`,
  `CIRCUIT_OPEN`) with fixed detail codes, never text. `SpeechSynthesis`, `Transcription`, `ImageSearch`,
  `ImageGeneration`, `WebSearch` and `VideoGeneration` are interfaces only.
- **Adapter.** `OpenAiCompatibleAdapter` on the JDK `HttpClient` and Jackson 3 trees: no redirects, a connect limit, one
  deadline over headers and body, an idle limit for SSE (a virtual-thread watchdog closes the stream), a hard body cap, and
  error bodies are never read. DeepSeek: `thinking` is disabled explicitly, `user_id`, `prompt_cache_hit/miss_tokens`.
  GigaChat: OAuth exchange of the authorization key (`GigaChatTokens`, covered by a recorded fixture only, not yet run
  against the live service) and `precached_prompt_tokens`. OpenRouter: config and adapter only, no default route.
- **Routing and failure policy** (`RoutedTextGeneration`). 429: wait `max(Retry-After, jitter)` and retry up to six
  times, never past the deadline (a longer `Retry-After` is handed back as `RATE_LIMITED`); 5xx, network and idle/connect
  timeouts (response headers must arrive within `learning.ai.transport.first-byte`, so a silent provider times out with deadline
  left): up to three tries, then the next route entry; invalid output: one repair on the same entry (the cacheable
  prefix is untouched), then the escalation route (`text-fast` to `text-strong`), then `INVALID_OUTPUT`; refusal,
  rejected credentials and a passed deadline: no retry, no fallback. Circuit breaker per `(provider, capability)`: five
  consecutive transport failures inside 60 s open it for 30 s, then one probe (`CircuitBreaker`, `Clock`-driven; a
  throttled ladder counts as one failure, invalid output and refusals do not count). Per-capability semaphores; a late
  caller gets `RATE_LIMITED`. The daily budget per capability is summed from the journal and checked before any call.
- **No transaction around a provider call.** `generate` throws `IllegalStateException` inside a transaction. Each call
  writes an intent row (own short transaction) before the HTTP call and the outcome after it into
  `ai_provider_call` (V27): ids, hashes, counts, cost and outcome; never a prompt, response or key. The row moves once
  from `PENDING` (trigger-enforced); a crash leaves a visible `PENDING` row. A journal failure before the call fails
  the call closed (`TRANSIENT journal_unavailable`); after it, it is only logged.
- **Observability.** One `ai_call ...` log line and `mnema_ai_calls_total`, `mnema_ai_call_seconds`,
  `mnema_ai_cost_micros_total` per provider call (`AiTelemetry`); a test asserts that no key, prompt, user key or
  provider message reaches a log.
- **Capabilities.** `GET /api/capabilities` returns eight keys (`aiAssessment`, `speechToText`, `aiGeneration`,
  `textToSpeech`, `imageSearch`, `imageGeneration`, `videoGeneration`, `webSearch`). A capability is available only when
  its flag and an adapter exist; `aiGeneration` needs a usable key on the `text-fast` route and the user-key secret, or the
  Stub. `TEMPORARILY_UNAVAILABLE` is an open circuit on every route entry or a spent daily budget and clears by itself.
  The capability problem members (`capability`, `reason`) arrive with the typed `ProblemExtension` of the usage/deck-hub work.
- **Stub.** `learning.ai.provider=stub` (local and CI; the only way to register it, with a startup WARN; a `stub` route entry is a
  startup error): the answer is a pure function of the request, MBM output is one of
  five documents copied from the MBM valid fixtures (a test keeps them byte-equal to the contract and compiling), and the
  markers `[[stub:rate-limit]]`, `[[stub:transient]]`, `[[stub:timeout]]`, `[[stub:refusal]]`, `[[stub:invalid]]` and
  `[[stub:invalid-mbm]]` simulate failures; the two `invalid` markers stop applying once a repair segment is present.
- **Prompt library** (`ai.prompt`). `PromptLibrary` loads `ai/prompts/v*/**` strictly (front matter keys, version equals
  directory, no placeholder in the static layers). `PromptRenderer` fills `{{name}}` in one pass, so a placeholder inside
  data stays inert; text values are redacted (`Redactor`: e-mail, Luhn-valid cards, phone forms) and escaped, block values
  (`*_blocks`, `*_lines`, `allowed_links`, `document`, `schema`) come from `PromptBlocks`, and a missing required value is a
  hard `PromptException`. `PromptAssembler` orders segments for the provider's prefix cache (core, style, five skills,
  deck brief cacheable; the task section volatile) and enforces per-section and 32k input ceilings with
  `TokenCounter`, an estimate (about 3.5 characters per token for Latin, 2.2 for Cyrillic, one per CJK character).
  `UserKeys` makes `HMAC-SHA256(accountId)` with a key id from `learning.ai.user-key.secret` as an `OpaqueUserKey`, the only type
  `TextRequest` accepts. Block values are `PromptBlock`s, which only `PromptBlocks` makes (it redacts and escapes), and one text
  value may not exceed 64 KiB. `toString()` of requests, responses, keys and provider settings never prints text or secrets.
- **Opt-in eval and live tests** (`AiEvalRunner`, `LiveProviderTest`, skipped without `MNEMA_AI_EVAL` / `MNEMA_AI_LIVE`) are
  documented in [selfhost-local](../../../docs/deploy/selfhost-local.md#ai-provider-layer-local).
- Keys are listed in the [runtime policy index](../../../docs/engineering/runtime-policy-index.md).

Fresh Learning migrations V1–V27 are the database source of truth. V27 adds `ai_provider_call` (the provider-call journal). V21 (unified exercise
mechanics) fails closed when pre-#266 exercise data exists: use a fresh local database. V23
only widens the exercise type and answer-key kind constraints for `ORDER` and `CATEGORIZE`
(no data rewrite); V24 adds the notification tables; V25 adds `deck_item_exemplar` and two indexes (assessed-binding by
member, open captures per Deck) and rewrites nothing; V26 adds the usage ledger tables (`usage_allowance`,
`usage_balance`, `usage_reservation`, `usage_ledger_entry`, `usage_counter`, `entitlement_inbox`) and rewrites nothing. Do not append
Study tables to legacy `core` migrations or port old review algorithms.

Sources: [Spring Security 7.1 JWT](https://docs.spring.io/spring-security/reference/7.1/servlet/oauth2/resource-server/jwt.html)
for signature/claims/scope boundaries; the exact 6.5.11 source establishes claim
conversion behavior; [Java 25 HTTP](https://docs.oracle.com/en/java/javase/25/docs/api/java.net.http/java/net/http/HttpRequest.Builder.html)
for request deadlines, supplemented by explicit bounded body completion/cancellation;
[Spring scheduling](https://docs.spring.io/spring-framework/reference/integration/scheduling.html)
for the enabled fixed-delay retention worker and duration-based configuration;
[Java 25 Normalizer](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/text/Normalizer.html)
and [Unicode UAX #15](https://www.unicode.org/reports/tr15/) for canonical
decomposition in soft text matching; [PostgreSQL constraints](https://www.postgresql.org/docs/18/sql-createtable.html)
for the deferred ordinal uniqueness during transactional roster compaction.
