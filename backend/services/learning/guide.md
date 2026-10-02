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
- AI layer (Epic #77): the notification center is implemented (below); generation and usage still exist only as
  contracts — [`contracts/generation`](../../../contracts/generation/README.md),
  [`contracts/usage`](../../../contracts/usage/README.md) — plus the versioned prompt sections in
  `src/main/resources/ai/prompts/` (resources, not loaded by any code yet). Run-2 tasks (#281, #282, #285, #303) add the rest.
- MBM compiler (#283): `app.mnema.learning.generation.mbm` is a pure package (no Spring, I/O or clock; identifiers come from
  the injected `IdAllocator`) that compiles MBM v1 to a native-v1 document and renders native-v1 back to MBM.
  `MbmCompiler.compile(source, MbmOptions, IdAllocator)` returns `MbmResult.Success` (document already read by
  `NativeDocumentReader`, media `MbmSlot`s, warnings) or `MbmResult.Failure` (at most 20 errors, no document); the
  `href`/`lang` profile is the reader itself (`NativeProfile` probes it), so there is no second validator.
  `MbmRenderer` writes `[[bN]]` handles and is lossless or refuses (`MbmUnsupportedContentException`: it re-compiles every
  block and compares). `MbmAutoFixer` repairs harmless slips before compiling, `MbmRepairList` formats errors for the repair
  prompt, `MbmLint` is the hook for the copy lint. Inline scanning has a linear work budget and a nesting bound that report
  `MBM_DOCUMENT_TOO_LARGE`. Fenced `code_block` stays `MBM_CODE_BLOCK_UNSUPPORTED` until #303 (the single fence scan in
  `BlockParser` is where it plugs in). The executable contract is `contracts/generation/mbm-v1`.

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
  and `INTERNAL_ERROR`. Public details
  never contain exception messages, SQL or stored command data.

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

Fresh Learning migrations V1–V25 are the database source of truth. V21 (unified exercise
mechanics) fails closed when pre-#266 exercise data exists: use a fresh local database. V23
only widens the exercise type and answer-key kind constraints for `ORDER` and `CATEGORIZE`
(no data rewrite); V24 adds the notification tables; V25 adds `deck_item_exemplar` and two indexes (assessed-binding by
member, open captures per Deck) and rewrites nothing. Do not append
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
