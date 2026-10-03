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
  compiler (`generation.mbm`), the `code_block` node and the provider foundation (`app.mnema.learning.ai`, below); run 3 adds
  generation sessions, steps and the text draft (`app.mnema.learning.generation`, "Generation sessions (#287)" below); approval,
  edits ("Selection edits (#293)" below) and media execution follow (AI-05 and later).
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
- **Session list order** is by the mutable `last_activity_at` (the contract's order): a session touched between two pages may
  repeat or move; clients reconcile by `sessionId`.
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
  shape strictly and prices MATERIALS (one artifact per NOTE source or one when merged; `AUTO` effort is priced and run as medium (a plan chooses the effort of each material itself, #295) (this also lets a Free account start an `AUTO` session in its first week);
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

## Generation sessions (#287)

`app.mnema.learning.generation` implements the core of the Workshop ([`contracts/generation`](../../../contracts/generation/README.md),
[architecture §3-§6](../../../docs/architecture/ai-generation-platform.md)): sessions, artifacts, durable steps, events, the
`TEXT_DRAFT` step and its HTTP surface. Approval, rejection, hand-off, retry, delete, note archival and retention are
"Review commands (#288)" below, exercise sessions "Exercise generation (#291)", edits and revert "Selection edits (#293)". Not here yet:
the media executors; their tables exist (V28), their routes do not. The planner is "Planner (#295)" below.

- **HTTP** (`GenerationController`, every response `private, no-store`; unknown query parameters and body fields are `400`):
  `POST /api/decks/{deckId}/generation-sessions` (201, `Location`, `ETag`; an exact retry answers 201 with
  `Idempotency-Replayed: true` and no `ETag`), `GET` the same path (`limit` 1..100, `cursor`, `active`), `GET
  /api/generation-sessions?state=active` (every PLANNING, PLAN_READY, RUNNING and REVIEW session of the account; `state` is
  required), `GET .../{sessionId}`, `POST .../{sessionId}/cancellation`, `GET .../{sessionId}/events?after=&limit=` and `GET
  .../artifacts/{artifactId}?revisionId=`. A body is read (at most 64 KiB) before any transaction. Order of evaluation as in
  `http.json`: ownership (404), receipt replay, validation (400, 422 limits), sources (404, then 409 `SOURCE_UNAVAILABLE`),
  capabilities (409), active sessions (422, listing their ids), usage (409, inside the admission transaction, so a refusal
  leaves nothing). `EXERCISES` is supported (#291, below); `planFirst: true` is the planner's (#295, below) and is `422 SPEC_NOT_SUPPORTED`, in the estimate too, only when `learning.generation.planner.enabled` is `false`.
- **Tables** (`V28__generation.sql`, composite owner-scoped foreign keys as `V10`): `generation_session` (immutable `spec`,
  `row_version`, `last_event_seq`, `expires_at`), `generation_session_source` (pins), `generation_artifact`,
  `generation_artifact_revision` (immutable, at most 30, `payload` + `handles` + `prompt_version` + `model_route` +
  `validation`), `generation_artifact_turn` and `generation_provenance` (written by AI-05 and later), `generation_media_slot`
  (the compiler's pre-allocated `assetId` per slot), `generation_media_ref` (the media GC hold, see below), `generation_step`
  (the queue) and `generation_event` (append-only). Revisions, events, sources and provenance reject `UPDATE` by trigger.
- **One lock per session.** Every state change runs in one short transaction that locks the session row first, then the
  artifact and the step (`SessionLifecycle`). Event numbers are `UPDATE ... SET last_event_seq = last_event_seq + n` under
  that lock, with `UNIQUE (session_id, seq)`: consecutive in commit order, so a poller never skips one (a test races eight
  writers against a poller). The session `row_version` bumps with every transaction that changes something visible (state,
  pointers, activity); a `BLOCKS_APPENDED` checkpoint appends events without bumping it. Usage is called after the domain
  writes and notifications last (reservation, balance, notification cursor).
- **Create** reserves the p95 of the spec (capped by `budgetPercent`) in the same transaction as the session, its sources,
  one artifact per note (or one when merged or prompt-only; at most 20) and one `TEXT_DRAFT` step each, and emits
  `SESSION_STATE` and `ARTIFACT_STATE(QUEUED)`; an advisory lock per owner serializes the count of active sessions. `afterCommit`
  wakes the dispatcher.
- **Dispatcher** (`StepDispatcher`, only for `learning.runtime.roles=worker|all`, default `all`; an `api` process has no
  dispatcher and needs no provider key). `StepQueue` claims `READY`, due steps of a kind that has an executor, in a RUNNING or
  REVIEW session, `FOR UPDATE SKIP LOCKED`, under a soft cap of `learning.generation.worker.account-cap` running steps per
  account; the claim sets a new `lease_token`, `lease_until`, `attempts + 1` and the run's `deadline_at` (PT6M for
  `TEXT_DRAFT`) and never takes the session lock. Each run is a virtual thread; its heartbeat (`worker.heartbeat`) extends the
  lease only if the token matches, reads `cancel_requested` and, on a cancellation or a lost lease, interrupts the run so the
  provider call ends. Every result write checks the token (`StepRepository.lockHeld`): a late worker writes nothing. One permit
  per capability and instance comes from `learning.ai.permits.*` (text 16). The sweeper (`worker.sweep-interval`, 2 s) recovers
  expired leases (back to `READY` with backoff, or `FAILED(PROVIDER_UNAVAILABLE | DEADLINE_EXCEEDED)` after
  `step.max-attempts` claims, or when the step is older than `step.max-lifetime` counted from its first claim; a claim near
  the end of that lifetime gets only what is left, and a requeue delay is never longer than `step.backoff-cap`), claims due
  steps (also those of a REVIEW session, for the media kinds) and renews, in pages, the reservations of all running sessions.
  A clean shutdown waits a few seconds for the runs, then hands every run still going back (`READY` at once, the attempt not
  counted). The heartbeat interrupts a run only while it is inside the provider call; elsewhere a cooperative flag is enough. The media child steps
  (`TTS`, `IMAGE_SEARCH`, ...) are created `READY` with their slots and stay unclaimed until an executor registers.
- **Daily burst.** Before a claim, `UsageLedger.dailyDebitRoom` is read; a step that would exceed the day's room stays `READY`
  with `next_attempt_at` at the next day start and the session gets `USAGE_UPDATED.deferredUntil`; its hold is renewed.
- **`TEXT_DRAFT`.** `ContextBuilder` assembles the prompt through `PromptAssembler`: the deck brief (title, description,
  outline from `item_preview` titles, at most two exemplars (the pinned `STYLE_EXAMPLE`s, then with `similarToDeck` the starred
  materials) and the most recent material, deck terms from bold words of those), the sources as untrusted `<note>` blocks
  (redacted and escaped by `PromptBlocks`), the links of the user's own sources as the allowlist, and the task. Budgets in
  estimated tokens: sources 12k, exemplar 2.5k and 6k together (longer ones become a skeleton), outline 5k; a deck of at most 200
  materials is outlined whole, a larger one shows every starred material, the 40 latest and the top 40 by `word_similarity` of
  the cached title (needs `pg_trgm`; without it that part is skipped), and says how many it left out. `max_tokens` follows the
  effort (900 / 2400 / 4500; `AUTO` is `MEDIUM`; a planned material is written at the plan's effort), `thinking` is off (on only for the plan routes), `user_id` is the opaque HMAC key.
  Before the call the remaining hold must cover the material's weight, else `ESTIMATE_EXCEEDED` with no provider call. The call
  streams: `DraftStreamer` compiles the text up to the last block boundary and appends `BLOCKS_APPENDED` at most every
  `stream.checkpoint-interval` (750 ms), at most 24 KiB of blocks per event, with the artifact's `generation` counter (a restart for any
  reason increments it). The answer goes through `MbmAutoFixer` and `MbmCompiler`; a rejection is sent back once with the
  compact `MbmRepairList` (same route), once more on `text-strong`, then `FAILED(INVALID_OUTPUT)` with no debit. Success is one
  transaction: `DEBIT` (`debit:{stepId}:{attempt}`, the weight of `MATERIAL_*`, cost in millionths of a rouble from the
  provider cost times `learning.generation.usd-rub-rate`; it covers the successful calls of the step only, the full cost of every call,
  failed ones included, is in `ai_provider_call`), the revision (`INITIAL`, handles `b1..bn` of the top-level blocks,
  warnings: compiler warnings, `TITLE_MISSING`, `SIMILAR_TITLE` when `pg_trgm` finds a title above
  `learning.generation.similar-title`), media slots (`PENDING`) with their child steps, the artifact's move to `PROPOSED`, the
  step's `SUCCEEDED`, the events and, when no artifact is `QUEUED` or `GENERATING`, the session's move to `REVIEW` (or `CLOSED`)
  with the reservation released (the consumed part is kept) and the notification. Provider failures the router gave up on are
  retried with backoff up to `step.max-attempts`, then fail the artifact (`PROVIDER_UNAVAILABLE`, `DEADLINE_EXCEEDED`); a
  refusal is `REFUSAL`, no retry. **No transaction or connection is held during a provider call** (the router also refuses to
  run in one); `GenerationSessionIntegrationTest` asserts it at the call and at every streamed delta with a per-thread
  connection counter.
- **Notifications** (in the transaction that ends the run): `GENERATION_READY` when every artifact is approvable and none
  failed, `GENERATION_PARTIAL` with approvable and failed ones, `GENERATION_FAILED` (most frequent error code) only when
  no proposal is left to review (nothing `PROPOSED`, `REVISING` or `STALE`), never for a cancellation. A proposal with media still being made is not approvable and, with no failure, publishes
  nothing yet. TODO(AI-09, AI-10: media steps): publish `GENERATION_READY` when the last slot of a REVIEW session resolves.
- **Cancel** (`POST .../cancellation`, no `If-Match`, state-idempotent): waiting steps `CANCELLED`, running ones get
  `cancel_requested` (their heartbeat aborts the call and ends the step), `QUEUED` and `GENERATING` artifacts `FAILED(CANCELLED)`,
  the reservation released except what was consumed, and the media slots of the cancelled steps `FAILED(CANCELLED)` with
  `MEDIA_SLOT_STATE` events; a `CANCELLED` session answers 200 again, `CLOSED` and `EXPIRED` 409.
- **Media GC.** `generation_media_ref` is checked beside `draft_media_ref` in every hold of the media module (resolve,
  `expireUnattached`, reference validation and the GC's blob check): while the session has not expired its assets stay
  readable and out of the sweeps. It is written by the media steps (AI-09, AI-10); the table has a composite foreign key to
  `media_asset(asset_id, owner_id)`.
- **Retention** of sessions: see "Review commands (#288)" below.
- **Configuration** is in `application.properties` (`learning.runtime.roles`, `learning.generation.*`) and the
  [runtime policy index](../../../docs/engineering/runtime-policy-index.md). Tests: `app.mnema.learning.generation`
  (`GenerationSessionIntegrationTest`, `GenerationHttpContractTest`, `GenerationWorkerIntegrationTest`,
  `GenerationContextIntegrationTest`, `GenerationUnitTest`, `RuntimeRolesIntegrationTest` and, for #288, `GenerationReviewIntegrationTest`,
  `GenerationReviewContractTest`, `GenerationRetentionIntegrationTest` on the shared `GenerationReviewSupport`) run on the Stub with a decorator
  that records calls and simulates failures (`GenerationTestConfiguration`); `GenerationLiveProviderTest` is the opt-in run on
  DeepSeek. The test default is `learning.runtime.roles=api` (`src/test/resources/config/application.properties`), because the
  dispatcher claims steps of the whole shared test database; generation tests opt in with `roles=all`.

## Review commands (#288)

The commands that take a proposal out of review, in `app.mnema.learning.generation` (`ReviewService`, `GenerationController`; wire
shapes and decisions: [`contracts/generation`](../../../contracts/generation/README.md), decision 12). The order of evaluation of
`http.json` holds in every command: ownership (the opaque 404), receipt replay, request validation (400), preconditions (428, 412),
state (409) and usage (409, last, inside the transaction that changes state).

- **Approve** (`POST .../artifacts/{id}/approval`, `POST .../approvals`, at most 20). The generation module owns the caller-owned
  port `GeneratedItemPublisher` (and `GeneratedDraftOpener`); `catalog.item.GeneratedItemPublicationAdapter` implements it with the
  pattern of `CaptureItemPublicationAdapter` (accepted dependency, architecture §2: catalog adapters implement generation-owned
  ports, like `CaptureItemPublisher` the other way round; catalog domain code never calls `generation`, and `study` and `media`
  import nothing of it): one `ItemService.publish` of N `create` changes (member keys and the command id derived from the request's `commandId`, so
  a repeat is the same command) whose completion runs **in the publication's transaction**. Before it, in this order and without a
  long transaction: the deck's version and revision (412), the artifacts' versions and revisions (412, `artifactIds` for a bulk),
  the state (409 `ILLEGAL_STATE`), source drift (a `PROPOSED` artifact whose note pin moved or is gone, or whose `SOURCE` material is no
  longer the head, becomes `STALE` in `SourceDrift.markStale`, a short transaction of its own that commits before the 409
  `SOURCE_STALE`) and the media slots (409 `MEDIA_NOT_READY`: every slot of the current revision must be `READY` or `REMOVED`).
  The completion repeats what a concurrent command could change (it locks the session row first, then re-reads each artifact),
  then per artifact: `PUBLISHED`, `published_ref` (`{kind: ITEM, memberKey, itemRevisionId, ordinal}`), `publication_command_id`,
  a `generation_provenance` row (model routes and prompt versions of its revisions; never returned by any API), the release of
  its `generation_media_ref` holds (`ItemService` binds the assets of the stored document in the same transaction), the
  `ARTIFACT_STATE` event, and, once, the session's close when nothing is left to review, and stores the acknowledgement as the
  command's receipt. A replay of `commandId` is the stored acknowledgement with `Idempotency-Replayed: true` and no `ETag`
  (`ETag` is the new deck version). Lock order: deck row (the catalog's CAS), then the session row; nothing takes them the other way.
- **Reject / undo** (`POST .../rejection` with a body, `DELETE .../rejection` with `If-Match` = the artifact's version): `PROPOSED` or
  `STALE` to `REJECTED` and back. A session `REVIEW` or `CANCELLED` with nothing left to review, retry or wait for becomes `CLOSED`
  (`SessionLifecycle.closeIfDone`); the undo is still allowed in a `CLOSED` session until the purge and reopens it (`REVIEW`, or
  `CANCELLED` if it ended through cancellation; activity and expiry refresh); an `EXPIRED` one refuses it.
- **Hand-off** (`POST .../handoff`, 201, `Location`, `ETag` of the draft): `GeneratedDraftAdapter` creates an ordinary
  `EditingDraft` (`member_key` null) through `DraftService.create` in the same transaction as the artifact's `HANDED_OFF`; media nodes
  whose assets are not `READY` are left out of the draft document, and the artifact's waiting media steps are cancelled and its open slots settled `FAILED(CANCELLED)` in the same transaction. The account's draft quota is `422 RESOURCE_LIMIT_EXCEEDED`
  (`limit: EDITING_DRAFTS`) and rolls the hand-off back.
- **Retry** (`POST .../retry`): `FAILED` (not `REFUSAL`: 409 `NOT_RETRYABLE`) or `STALE` back to `QUEUED` in a `RUNNING` or `REVIEW`
  session. In one transaction: version (412), state (409), sources (`SourceDrift`: a stale artifact is re-pinned to what exists, a
  failed one needs its pins to hold, a deleted source is 409 `SOURCE_UNAVAILABLE` for both), capabilities (409), the active-session limit when a leftover-only `REVIEW` session would count again (422, admission lock first), a new `STEP`
  reservation of the material's weight (409 `USAGE_LIMIT_REACHED` rolls everything back), then the replaced revision's slots, holds
  and waiting media steps are dropped, the artifact is requeued, a new `TEXT_DRAFT` step (`draft:{artifactId}:{n}`) carries its
  `reservationId` in its input, and a `REVIEW` session returns to `RUNNING`. The step's debit draws from that reservation
  (`SessionReservations.forStep`); the session's `usage`, the `USAGE_UPDATED` events, the renewal and the release at `REVIEW` entry,
  cancel, expiry and delete cover all the holds of the session (its initial batch and these).
- **Delete** (`DELETE .../generation-sessions/{id}`, 204 then 404, no `If-Match`): releases the credit holds and deletes the session row;
  steps, artifacts, revisions, slots, holds, sources and events go by cascade. A running worker finds its session gone and writes nothing.
  Published materials, handed-off drafts and provenance stay.
- **Note archival** (`POST .../note-archival`, `NoteArchival`): archives the `NOTE` sources of `PUBLISHED` or `HANDED_OFF` artifacts
  through `CaptureService.archive` with the pinned `row_version` (the note rows are locked first, so the pin holds); a note that an
  artifact still in play pins is not "used" (archiving would turn that sibling `STALE`); changed, already
  archived and deleted notes are reported in `skipped`. `sessionDetail.notes` is `{used, archivable}`.
- **Retention** (`SessionRetention`, scheduled by `RetentionWorker` for `learning.runtime.roles=worker|all`): every
  `retention.interval` (`PT10M`) a pass (1) ends live sessions past `expires_at` as `EXPIRED` (stops steps, fails unfinished artifacts as
  cancelled, releases holds, emits `SESSION_STATE`; `expires_at` and activity do not move), (2) purges `CLOSED` and `CANCELLED`
  sessions past `expires_at` and `EXPIRED` ones `retention.expired-readable` (`P1D`) after it (holds released, rows cascaded; published
  content and provenance stay; afterwards every read is 404 and the media GC may collect the assets, since the hold is gone), (3)
  warns the owner `retention.warn-before` (`P3D`) before expiry with `GENERATION_SESSION_EXPIRING` (`pendingCount` = `PROPOSED`,
  `REVISING` and `STALE` artifacts; key per expiry date) and (4) deletes the events of sessions that ended more than
  `retention.events-after-end` (`P1D`) ago. Each session is handled in a transaction of its own under its row lock with the due
  condition re-read, so activity since it was found wins. Keys are in `application.properties` and the
  [runtime policy index](../../../docs/engineering/runtime-policy-index.md).
- **Tests**: `GenerationReviewIntegrationTest` (approve, replay, 412/428/400, not-ready slot with a real `READY` asset bound to the
  revision, drift to `STALE`, bulk atomicity and 20-limit, hand-off, quota, reject/undo, retry with and without budget, delete, note
  archival, session states), `GenerationReviewContractTest` (every answer has the members of its `http.json` example),
  `GenerationRetentionIntegrationTest` (expiry, readable window, purge with media GC, warning, events). A READY media slot is made in
  tests by giving the slot's pre-allocated asset id a real `READY` asset (`GenerationReviewSupport.readyAsset`).

## Notes as sources (#290)
- **Grouping**: `ONE_PER_NOTE` makes one artifact per NOTE source, whose `source_refs` hold that note (and the item sources);
  `MERGE_INTO_ONE` makes one artifact pinning all notes. `MaterialsSpec.forArtifact(sourceRefs)` gives the effective settings
  of a material (`MaterialsSpec.Effective`): the session's, with the sparse `overrides` of its note applied.
- **Overrides** are parsed strictly by `StandardSpecInterpreter` (usage module: shape, one-per-note only, pricing and capability
  facts per effective material) and stored in the spec as sent; `SessionService` and `ReviewService.requeue` write the effective
  `effort`/`operation`/`credits` into each TEXT_DRAFT step input, `ContextBuilder` reads the effective effort and media.
- **Pinned text**: `generation_note_snapshot` (`V29`) keeps the note text at the pinned `row_version`, written at admission
  (`snapshotNote`, in the admission transaction; a moved note is `409 SOURCE_UNAVAILABLE`) and on a retry's re-pin. The context
  reads only the snapshot. The rows are append-only and cascade with the session.
- **Status** of a NOTE pin in `getArtifact` (`SessionViews.withNoteStatus`): equal row version `CURRENT`; missing `DELETED`;
  moved and archived with unchanged text `ARCHIVED`; otherwise `CHANGED`. It never writes anything.

## Exercise generation (#291)

`kind: EXERCISES` sessions ([decision 14](../../../contracts/generation/README.md), [exercises contract](../../../contracts/generation/exercises/README.md))
reuse the session, step, event, usage and notification machinery above; what is specific is below.

- **Admission** (`SessionService.admitExercises`): the targets are `SOURCE` items of the session (a target that is not the head is
  `409 SOURCE_UNAVAILABLE`, checked by `GenerationGate`); `AdmissionPricing.Hold.exercises` is the quantity the usage interpreter resolved
  (`AUTO`, `EXACT`, `BUDGET_PERCENT`: unchanged numbers); `ExercisesSpec.spread` spreads it over the targets in processing order
  (`UNCOVERED_FIRST` sorts by `ContextRepository.exerciseCounts`, stable). One QUEUED artifact per exercise (`EXERCISE`, `source_refs` the
  target pin, ordinal in processing order) and one `TEXT_DRAFT` step per target with `input {operation: EXERCISES, memberKey,
  itemRevisionId, count, credits, artifactIds}`; `TextDraftExecutor` hands such a step to `ExerciseDraftExecutor`. `credits` is the step's
  share of the hold (the rounded-up price of the exercises up to it minus the price of those before it), so the shares sum to the
  reservation.
- **Context** (`ExerciseContexts`): the `<data_policy>` block of the core (a cacheable segment put in front by `PromptAssembler`), then
  the section of `ai/prompts/v1/exercises.md` verbatim: the schema (minified), `<material id="m1">` with `[[b1]]..` lines for the
  top-level blocks that read as text (`PinnedMaterials`; not blank, at most 4000 characters, within 5.5k tokens), `<objectives>`
  (`t1 · title · mechanics`), `<existing_exercises>` (first line of the prompt of each current enabled exercise, at most 30),
  `<neighbors>` (titles of other materials) and the task (`count`, `Механики: A, B, C`, language). Temperature 0.4, JSON output,
  `max_tokens` `800 + 800 x count` (at most 16k).
- **Run** (`ExerciseDraftExecutor`): `beginExercises` (the artifacts become GENERATING), the context, the reservation check
  (`ESTIMATE_EXCEEDED` without a call), then up to three provider calls with no transaction or connection open: the answer is parsed (a code
  fence is tolerated), the first `needed` exercises go through `ExerciseValidator` one by one (schema, lint, compile, `readCreate`,
  probes through `ExerciseProbeEvaluator`), the failures get `ExerciseRepairList` (the instruction "exactly K replacements" first, then
  `упражнение N: CODE (path)` lines and the rule of each code, at most 1800 characters) appended as a repair segment, the second repair goes to the strong
  route. `succeedExercises` is one transaction: the debit (`debit:{stepId}:{attempt}`, `EXERCISES_PER_MATERIAL`, the step's share in proportion
  to the exercises produced), an `INITIAL` revision `{kind: EXERCISE_COMMAND, command}` and PROPOSED for each exercise that passed (in artifact
  order), `FAILED(INVALID_OUTPUT)` for the artifacts that got none, the step's end and the events. There is no `BLOCKS_APPENDED`. `fail`, `recover`
  and `expire` of `SessionLifecycle` act on every open artifact named by the step input. Metrics: `mnema_generation_exercise_findings_total{code}`.
  A `COMMAND_REJECTED` is logged as `generation_exercise_command_rejected` (ids and index only).
- **Pipeline classes** (`generation.exercise`, pure and Spring-free): `ExerciseOutputSchema` (the classpath copy
  `ai/exercises/output.schema.json`; a test keeps it equal to the contract), `ExerciseLint`, `ExerciseCompiler` (the id allocator is injected:
  `ExerciseIds`), `ExerciseValidator`, `ExerciseRelint` (the rules that read the material text, for a re-pin), `ExerciseRepairList`.
  `study.attempt.ExerciseProbeEvaluator` is the only door to `AttemptEvaluation` for the generator.
- **Approval** (`ReviewService.publishWithExercises`): ONE transaction (timeout 120 s): the receipt of the approval, the materials as one bulk
  publication (`GeneratedItemPublisher`), then each exercise through `GeneratedExercisePublisher` (adapter `catalog.exercise.
  GeneratedExercisePublicationAdapter`: `ExerciseCommand.readCreate`, `ExerciseService.publish` joining the transaction, objective reuse by
  normalized title via `ExerciseRepository.objectivesOf`, `ExerciseNewMarks.mark`), each on the deck revision and version the previous one left, then
  `apply` (PUBLISHED, `publishedRef`, provenance with `edited`, events). An approval without exercises keeps the old path (the completion of the
  item publication). `replacement` is validated by `readCreate` and the subject member; with a drifted target it is never re-pinned: it is published when it
  already stands on the head, else `STALE`. A reused objective is published against its current head (`repository.objectiveHead`) while it is bound to the subject
  member, else `ObjectiveUnavailableException` makes the artifact `STALE` (409 `SOURCE_STALE`).
- **Re-pin** (`ExerciseRepin`, called from `ReviewService.check` before the publish transaction): see decision 14; one transaction per artifact,
  `REPIN` revision plus `repinStatus AUTO_REPINNED` (`GenerationRepository.repin`) only when every block the model was shown is unchanged in the head
  (`ExerciseRepin.unchanged`), or `markStale`. `SourceDrift` treats the one pin of an exercise
  artifact as bound to the head even when a retry moved it past the session's source row. Retry of an exercise (`requeueExercise`) is one
  step with `count 1` and a `STEP` reservation of `exerciseCredits(1)`.
- **`getArtifact`** adds `display` (`SessionViews.display`); because reading the material takes `FOR KEY SHARE` row locks that part runs in a second, ordinary
  transaction.

### New mark («Новое»)

`V30__exercise_new_mark.sql`: `exercise_new_mark(deck_id, exercise_id, owner_id, marked_at)`, owned by `catalog.exercise` (`ExerciseNewMarks`,
`learning.exercise.new-mark-ttl`, `P7D`); Study and the lists never read generation tables. The approval marks each published exercise in its
transaction. `isNew` = a row younger than the TTL: on every entry of `ExerciseService.list` (the whole deck and `?memberKey=`, which is the
material profile) and on Study presentations (`study_presentation.is_new`, decided and stored when the presentation is issued; a REPLAY copy keeps
the column default `false`). The mark is deleted by `DELETE /api/decks/{deckId}/exercises/{exerciseId}/new-mark` (204, idempotent, opaque 404) and
by `AttemptService.submit` on every terminal result (the attempt's transaction). Expired rows are purged by `StudyRetentionService.purgeBatch`
(hourly), the closest existing worker; readers compare `marked_at`, so an unpurged row is never shown. `V30` also adds
`generation_provenance.edited`. Tests: `GenerationExercisesIntegrationTest`, `ExerciseValidationFixtureTest`, `ExerciseValidatorTest`,
`StubExercisesTest`.

## Selection edits (#293)

`editArtifact` and `revertArtifact` of a material ([decision 15](../../../contracts/generation/README.md), architecture section 7), in `app.mnema.learning.generation`.
`generation_artifact_turn` (V28) and the `EDIT` step kind existed; `V31__generation_revision_headroom.sql` raises the table bound of `revision_count` and `revision_no` from 30 to 40 so that `REMOVE_MEDIA` (exempt from the 30-revision cap of a rewrite, at most eight media per draft) can always store its revision.

- **Admission** (`ArtifactEdits`, `POST .../artifacts/{id}/edits` answers `202 {turn, artifact}`, `POST .../revert` answers `200` with the artifact summary): the contract's order of
  evaluation, in one transaction under the session lock: replay, 400 body, 412 `expectedRevisionId`, 400 target (`EditTarget.resolve`: consecutive top-level blocks;
  `EditContexts.editable`: MBM can render them and the redaction would not change their text), 409 state (`EditInProgressException` carries `turnId`; an EXERCISE artifact
  is `ILLEGAL_STATE`), 409 capability (`GenerationGate.requireEdit`), 422 limits, then the reservation. A rewrite writes a QUEUED turn, an `EDIT` step (input `{turnId, action,
  revisionId, operation, credits, reservationId}`; its idempotency key is `edit:<turnId>`) and makes the artifact REVISING. `REMOVE_MEDIA` is done in the same transaction
  (`ArtifactEdits.removeMedia`). Pure document operations are in `EditDocument` and `EditTarget` (blocks, handles, range replacement, the same JSON outside the range).
- **The step** (`EditExecutor`, kind `EDIT`, capability `TEXT`, deadline `PT2M`, not streaming, temperature 0.7): `EditLifecycle.begin` (turn RUNNING), `EditContexts.build`
  (the deck brief comes from `ContextBuilder.briefValues`, shared with the material's own step so the cacheable prefix is the same), the turn's hold checked, the provider
  called outside any transaction, the answer unescaped once, auto-fixed, compiled in MBM edit mode (`MbmOptions.edit(handles)`, `maxMedia 0`), spliced
  (`EditDocument.replace`) and read by `NativeDocumentReader`; up to three rounds (answer, repair, strong route) then `FAILED(INVALID_OUTPUT)`.
- **State** (`EditLifecycle`, one short transaction each, session lock first): `succeed` settles `EDIT_SELECTION`, releases the turn's hold, inserts the revision (cause `EDIT`),
  moves the slots (`followSlots`: a slot whose node is gone is `REMOVED`, one whose node is there follows the new revision), sets the turn `APPLIED` and the artifact
  `PROPOSED`; `fail`, `recover` (expired lease) and `expire` (lifetime) end the turn `FAILED`/`CANCELLED`, release the hold unspent and put the artifact back to `PROPOSED`
  on its old revision. `StepDispatcher.recoverExpired` and `StepQueue` route an `EDIT` step to them (an artifact step would otherwise be cancelled with the artifact stuck in
  REVISING). `SessionLifecycle.stopWork` (cancel and expiry) cancels open turns and returns REVISING artifacts to PROPOSED; `settle` does not release the holds of edits
  still working. The daily burst never parks an edit (`StepQueue`); `StepDispatcher` offers EDIT first, `pickDue` orders by `priority` (an EDIT step is inserted with 10) and `StepQueue` fails an EDIT step that no worker
  claimed within `learning.generation.edit.queue-timeout` (`PT2M`, from `created_at`) through `EditLifecycle.expire` (turn `FAILED(DEADLINE_EXCEEDED)`, hold released). A void step (cancelled, lost lease, revision moved)
  also ends its turn and releases its hold.
- **Reads**: `SessionViews.artifactDetail` lists the `turns` and `revisions` of the current generation of the draft (from the latest `INITIAL` revision: a retry starts a new draft and its older revisions are
  not restorable, `ArtifactEdits.moveTo` refuses them with `ILLEGAL_STATE`) and the slots of the media nodes the shown revision holds; `?revisionId=` reads any revision. The prompt's history is the APPLIED
  rewrites whose result revision is in that generation and not after the current revision (`GenerationRepository.recentTurns`).
- **For AI-09 and AI-10 (media executors)**: a media step must update its slot by `slotKey` (a slot row is one per key and is attached to whichever revision is current: `revision_id` moves with edits and reverts), never
  by the revision it was created for, and must treat a slot whose state is `REMOVED` as gone. A revert that restores a node whose slot was `REMOVED` marks it `FAILED(NO_RESULT)` and does **not** restore its
  `generation_media_ref` hold (removal deleted it), so the asset is only reachable again through a new media step.
- **Stub**: `StubEdits` answers `<task kind="edit">` with the target blocks and their handles, each plain paragraph with one added sentence `Переписано: <preset>.`.
- Tests: `GenerationEditsIntegrationTest` (real context, PostgreSQL and the Stub; `GenerationEditsSupport` has the requests and the built documents; the test provider's
  `[[fake:hold-edit]]` holds an edit call), `EditDocumentTest` (targets, handles, range replacement, the MBM round trip, what is editable), `StubEditsTest`.

## «Попросить Мнему…»: intent and revisions (#294)

Contract: `contracts/generation/README.md` decision 16 and `http.json` (`createIntent`, `specReviseItem`, `specReviseExercise`). Owner decision 2026-10-03: the media part of `REVISE_EXERCISE` is a **Stub**
executor now; real synthesis and its acceptance are #297 (AI-09).

- **Intent** (`IntentService`, `IntentSpecs`, `IntentUses`; `POST /decks/{deckId}/generation-intents` in `GenerationController`). Order: deck ownership (404), body (400, `Commands`), context
  (404: a material that is not a head member of the deck, an exercise that is not on its roster), `GenerationGate.requireText` (409), `IntentUses.take` (hourly rate limit per account in `generation_intent_use`
  under an advisory lock, `429 RATE_LIMITED` via `RateLimitedException` and `Retry-After`; `RetentionWorker` purges the rows), then ONE call (`PromptTask.INTENT`, `AiRoute.TEXT_FAST`, JSON,
  temperature 0.2, `learning.generation.intent.deadline`), one repair, then `UNSUPPORTED`. The model's answer is read against a closed vocabulary (`IntentSpecs.read`: unknown members dropped, `perTarget` any integer) and the
  spec is **built by the server** (`IntentSpecs.build`): the target is the request's context at its head, mechanics are filtered by the registry, `perTarget` is clamped (note `PER_TARGET_CLAMPED`), `budgetPercent` is never
  set, the voice is dropped with a note when the exercise has no audio or `GenerationGate.voiceRevisionAvailable` is false. Nothing is reserved; the call is journaled by the provider layer. The Stub answers by keywords
  (`StubIntents`: «все типы» → AUTO, a number → perTarget, «голос» → media, «проще» → revise, «лимит» → a hostile answer that the server must clamp; markers `[[stub:intent-invalid]]`, `[[stub:intent-invalid-always]]`).
  New prompt sections `intent` and `exercise-edit` were added to `v1` (released sections stay byte-stable, `ai/prompts/README.md`).
- **Admission** (`ReviseAdmission`, called by `SessionService.create` for `REVISE_ITEM` / `REVISE_EXERCISE` inside the receipt transaction; `StandardSpecInterpreter` validates and prices the spec, `GenerationGate.checkSpec`
  checks ownership, head and capabilities, `ExerciseRef`/`voiceRevision` of `GenerationBoundary.SpecFacts`). Order: room (`AdmissionLimits`), the head re-read, target checks (400/422), `hold.requireFits`, the reservations (one `TURN` hold
  per turn, no session batch hold: `generation_session.reservation_id` is null), then the session (`RUNNING`, spec echoed), its one `SOURCE` (the target material), the artifact with its INITIAL revision (a copy, no model call) and
  its first turn and step. `SessionLifecycle.settleRevision` moves the session to REVIEW when a turn ends with the artifact PROPOSED (and `settle` publishes no notification for a revise session).
  REVISE_ITEM: the whole material is the target of one `FREE` turn over all top-level blocks (`EditContexts` unchanged; `MaterialsSpec.read` of the revise spec gives the defaults). REVISE_EXERCISE: the exercise is read with
  `ExerciseService.read`, moved to the head of its material when that moved (`ReviseAdmission.followHead`, the same checks as `ExerciseRepin`), `ExerciseDecompiler` must be able to show it to the model (else `TARGET_UNSUPPORTED_BLOCK`),
  `sourceRefs` are `[ITEM pin, EXERCISE pin]` (`SourceDrift` knows the `EXERCISE` type: an exercise head that moved is stale, never re-pinned; a material head that moved re-pins through `ExerciseRepin`).
- **Exercise rewrite** (`ExerciseEditExecutor`, called by `EditExecutor` when the artifact is an EXERCISE; `ExerciseContexts.buildEdit`): the current command is turned into the output form by `ExerciseDecompiler`
  (local ids numbered by appearance and mapped to the identifiers; audio blocks of the prompt set aside; the result must compile back to the stored exercise, which is what guarantees that only exercises the form can express are shown),
  the prompt is `PromptTask.EXERCISE_EDIT`, only the exercise's own mechanic is allowed, the answer's first exercise goes through `ExerciseValidator.validate(..., known ids)` (`ExerciseCompiler.compile` keeps the identifiers of local ids the model
  kept), then the objective is restored to the current one, `enabled` stays, the audio blocks go back, `ExerciseCommand.readCreate` runs again. `EditLifecycle.succeed` stores `Result.ofExercise` (payload `EXERCISE_COMMAND`) and re-attaches the audio slots.
- **Media turn** (`ArtifactEdits.redoAudio` for an edit, `ReviseAdmission` for a spec): a `TTS` step whose input has `turnId`, `voice`, `credits`, `reservationId`; with an instruction it is inserted `WAITING_DEPENDENCIES` (`depends_on` the EDIT step,
  `StepRepository.insertWaiting`) and `EditLifecycle.succeed` promotes it (`promoteDependents`) and inserts its turn; a failed or cancelled rewrite cancels it (`cancelDependents`) and releases its hold. `StubSpeechExecutor` (only with `learning.ai.provider=stub`;
  `StepExecutor.requiredInput() = turnId`, so it never claims the media steps of a material's slots) calls `EditLifecycle.succeedMedia`: revision `MEDIA`, every audio slot `READY` on the asset it had (`generation_media_slot.asset_id` is unique only for slots whose spec mode is not `existing`: V33; **#297 must write the `generation_media_ref` hold for the new asset when it moves a slot from an `existing` asset to a synthesized one**, and give it a spec without `mode: existing`),
  the voice in the slot's spec, the hold released unspent. `StepQueue`/`StepDispatcher` treat a step with `turnId` like an EDIT step (never parked by the daily burst, queue timeout, recovery through `EditLifecycle`).
  `GenerationGate.requireVoiceRevision`: the Stub passes, any other provider needs a real `textToSpeech` (none exists yet: fail closed).
- **Approval** (`ReviewService`): `reviseItem` calls `GeneratedItemPublisher.revise` (the catalog adapter reads the head, plans the structural edits with `NativeRevisionPlanner` and saves the member at its place with `ItemPublicationCommand.revision`);
  the exercise chain calls `GeneratedExercisePublisher.revise` (`ExerciseService.publish` with `pathExerciseId`, the current objective, no «Новое» mark). Drift of the exercise head or of the material head is `SOURCE_STALE`; `retry` is `NOT_RETRYABLE`;
  hand-off of a material opens a draft of the existing member (`GeneratedDraftOpener.openRevision`); a bulk approval of a revise session is 400.
- **Edits on an exercise artifact** (`ArtifactEdits`): only in a `REVISE_EXERCISE` session, `FREE` (no target) or `AUDIO_REGENERATE` (+ `voice`, `TARGET_NO_AUDIO` without audio slots); `revert` too (title from the exercise, slots re-attached).
  `SessionViews` returns the turn's `voice` and the exercise's audio slots (`mediaSlots`, `voice` additive).

## Planner (#295)

«Сначала показать план» (`settings.planFirst`, `MATERIALS` and `EXERCISES`; contract decision 17 in [`contracts/generation`](../../../contracts/generation/README.md)).
Code: `Plans` (pure: the model's plan and the owner's plan, validation, pricing, the wire shape of `getSession.plan`), `PlanContexts` (the prompt from the
database), `PlanExecutor` (the `PLAN` step), `SessionLifecycle` (`beginPlan`, `succeedPlan`, `failPlan`: every state change in its short transaction) and
`SessionService` (`admitPlan`, `approvePlan`).

- **Admission.** `SessionService.admit*` branches to `admitPlan` after the checks of an unplanned session: state `PLANNING`, no artifact, the sources pinned, **two holds**
  (the session reservation = the batch exactly as without the plan, `AdmissionPricing.Hold.credits`; and a `STEP` reservation of `Hold.planCredits` that the `PLAN` step carries as
  `reservationId`, so the existing renewal, release and `usage` totals cover it) and one `PLAN` step (`capability TEXT`, input `{operation, credits, budgetCredits, reservationId}`).
  `EstimateService.hold` keeps the plan out of the batch hold and of the `budgetPercent` cap; `StandardSpecInterpreter` adds the line `SMART_PLAN_FLASH` (cap bucket `smartPlan`).
- **Step.** `StepRepository.pickDue` claims a `PLAN` step of a `PLANNING` session; `StepQueue` gives it `learning.generation.planner.deadline` and never parks it for the daily burst. A run is
  `beginPlan` (still `PLANNING`), `fairUseFits(SMART_PLAN)` and the plan hold checked (no provider call otherwise), the prompt (`ai/prompts/v1/plan.md`: titles, counts, mechanics, clipped notes, the budget;
  no material text), then the call **without a transaction** on the route `AiRoute.PLAN` (`learning.ai.routes.plan`; `OpenAiCompatibleAdapter` sends `thinking: enabled` for a route with `thinking()`, `disabled`
  for every other; the output bound is `planner.max-output-tokens` because the reasoning counts), `Plans.fromModel`, and one repair, then `PLAN_STRONG`, then `INVALID_OUTPUT`. Provider failures map as in
  `ProviderFailures`; a retried step starts over (nothing of an earlier run was stored).
- **Result.** `succeedPlan` (one transaction, session lock first): `settle` of `SMART_PLAN_FLASH` against the plan's hold, `consume(SMART_PLAN, 1)` (a cap that filled meanwhile throws
  `UsageLimitReachedException`, the transaction rolls back and the executor fails the plan unpaid), `release` of the plan's hold, `setPlan`, the step `SUCCEEDED`, the session `PLAN_READY`,
  `USAGE_UPDATED`, `GENERATION_PLAN_READY`. `failPlan` (also from `fail`, `recover` and `expire`, which route a `PLAN` step there): a retry requeues with backoff, a final failure ends the step `FAILED`,
  **cancels the session** (`cancel(tx, "PLAN_FAILED")`: `stopWork`, both holds released) and publishes `GENERATION_FAILED(PLAN_FAILED)`; a cancellation only ends the step.
  A cancelled session whose call returns later writes nothing (`succeedPlan` sees a session that is not `PLANNING`).
- **Plan storage.** `generation_session.plan` (`V34`, jsonb, the wire shape): the model's while `PLAN_READY` (`approved false`), the owner's approved one afterwards. `SessionViews.detail` returns it (`null`
  otherwise). The batch hold of a `PLAN_READY` session is **not** renewed (`runningWithReservation` is `RUNNING` and `PLANNING`): it lapses after `learning.usage.reservation-ttl` and the approval reserves again.
- **Approval.** `SessionService.approvePlan` (`POST .../plan-approval`): ownership, receipt replay, `Plans.fromOwner` (strict, 400), then in the receipt's transaction `expectedSessionVersion` (412), `PLAN_READY`
  (409), `Plans.requireWithinLimits` (422), `queueExercises` / `queueMaterials` (**the same methods the unplanned admission uses**, fed with the plan's items), the batch hold re-sized (`release` of the old one, `reserve` of
  exactly the plan's cost; a refusal is `409 USAGE_LIMIT_REACHED` and rolls everything back), the plan stored as approved, `RUNNING`. A step of a planned exercise carries `mechanics` and `ExerciseDraftExecutor`
  narrows the spec to them (`ExercisesSpec.withMechanics`), so the prompt and the lint use the plan's set; a planned material is written by `ContextBuilder` at the effort and on the title of the approved plan item at its
  ordinal (`Plans.plannedMaterial`, also used by the retry of a material).
- **Stub.** `StubPlans` (`ai` package): one item per target or note, `[[stub:plan-invalid]]` / `[[stub:plan-invalid-always]]`.
- **Config.** `learning.generation.planner.enabled` (`true`), `planner.deadline` (`PT4M`), `planner.max-output-tokens` (`16000`), `learning.ai.routes.plan` and `plan-strong`.
- **Tests.** `GenerationPlanIntegrationTest` (the whole flow on the Stub and PostgreSQL), `PlansTest`, `StubPlansTest`, route and adapter tests in `ai`.

## AI assessment of free explanations (#292)

`evaluatorPolicy ai-semantic` of a `FREE_RESPONSE` ([contract](../../../contracts/study/README.md#ai-assessment-of-free-explanations-ai-semantic-292), architecture §11, research §5):
the model gives a verdict per rubric point, the server does the rest. Code in `study.attempt` (Study owns the flow and never depends on `generation`), the grader in `ai`, the rubric in `catalog.exercise`.

- **Rubric v1** (`catalog.exercise.Rubric`, parsed by `EvaluatorPolicy`; replaces the earlier `critical`/`levels` shape, nothing could exist with it): `referenceAnswer`, 3..10 criteria
  `{criterionId, description, tier CORE|DETAIL|TERM, weight 1..3}` with 2..3 CORE, 1..4 DETAIL, 0..2 TERM, `misconceptions`, `acceptableTerms`. Study reads it from the immutable
  `exercise_revision.evaluator_policy` (a presentation stores only the evaluator identity, so a learner never gets it).
- **Grader** (`ai.SemanticGrader implements capability.SemanticAssessmentProvider`; the port was reshaped to `grade(GradeRequest) -> GradeOutcome` and the old `Judgement/Level` removed):
  prompt `ai/prompts/v1/assessment.md` rendered by `PromptAssembler` as two segments (grader rules + exercise cacheable, answer source + the answer as one JSON string volatile),
  route `ASSESS` (no escalation), JSON output, 1 run at temperature 0.2 or 2 parallel virtual-thread runs at 0.3 (journal step id = the attempt id, attempt = the run), user key
  = the HMAC of the account (a fixed non-secret key for the Stub). Server validation: every point `c1..cN` once, verdict enum, a `MET`/`PARTLY` quote must be a verbatim fragment of
  the answer (NFC, whitespace, case, `…` fragments in order, the entities the renderer adds, or the redacted form), else `UNCLEAR`; one repair for output that does not fit, then
  `Unavailable`. A quote must be 3..200 characters, at most 15 words, in at most three ≥3-character fragments in order, and is stored and shown as the learner's own text (their case and spacing, located by the normalized match). **Receipts keep these short quotes of the learner's answers** (`feedback.assessment.covered[].quote`) for the life of a scheduled receipt, i.e. until account deletion, while the answer itself is kept 30 days (`study_raw_response`); the account purge must cover both (TODO in `AssessmentRepository`). Provider failures map to stable reasons (`TIMEOUT`, `PROVIDER_ERROR`, `INVALID_OUTPUT`, `REFUSAL`, `BUDGET`, `NOT_CONFIGURED`, `CIRCUIT_OPEN`, `DEADLINE`, `PROMPT`).
- **Policy** (`SemanticPolicy` = `ai-semantic-v1`, `SemanticStrictness`; pure, unit-tested by tables): strictness from the objective's state (S1 no assessed attempt in the epoch or level ≤ 1, S2 level 2–3,
  S3 level ≥ 4 or streak ≥ 2; first attempt at an exercise in an epoch capped at S2), the thresholds of research §5.3, `OFF_TOPIC`/`CONTRADICTED`/no CORE ≥ PARTLY → INSUFFICIENT, uncertainty
  (`ASR_GARBLED`, run disagreement, unclear CORE) → self-check. COMPLETE→CORRECT, PARTIAL→PARTIAL, INSUFFICIENT→INCORRECT; `LOW` at S1, `MEDIUM` at S2/S3, never `HIGH`; reason codes
  `AI_SEMANTIC`, `STRICTNESS_Sx`, `RUBRIC_V1`.
- **Flow** (`AttemptService.submit` → `AssessmentService.begin` in the same transaction; V32 `study_assessment`): the answer is stored `ASSESSING` (state `ASSESSING|DONE|SELF_CHECK|UNAVAILABLE`, strictness,
  payload hash, the stored response only until terminal), `202 {…ASSESSING, retryAfterMs}`; an `AssessmentAccepted` event handled `AFTER_COMMIT` by `AssessmentRunner` (a virtual thread, bounded by
  `learning.ai.assess.concurrency`) calls `prepare` (a read), the grader (no transaction, no connection held) and `complete` (a short `TransactionTemplate` transaction: advisory lock on the attempt,
  session, presentation, assessment row, objective state; fair-use `consume` of one `ASSESSMENT` check; `AttemptConclusion.conclude` writes receipt, evidence, transition, raw response exactly like a
  deterministic result; `nextStricter` from the reducer's preview). A `UsageLimitReachedException` rolls that transaction back and a second one ends the answer as `UNAVAILABLE/USAGE_LIMIT`.
  Everything else that is not a grade is a compare-and-set on `ASSESSING`: «Оценить себя» (`self-check`), the deadline sweeper (`AssessmentSweeper`, `learning.ai.assess.sweep-interval`, `SKIP LOCKED`,
  `learning.ai.assess.deadline` = `PT20S`), the grader's own `Unavailable`/uncertain outcome; a late grade finds the row moved and is discarded. A presentation keeps one terminal receipt: the learner's own rating
  (`self-rating`, evidence `SELF_REPORT` `LOW`, evaluator `self-check`, reason codes `AI_FALLBACK` + the reason) completes the same attempt.
  `AttemptConclusion` is the write half shared by `submit` and the assessment (it replaced the private tail of `AttemptService.submit`).
- **Fair-use** is a counter, not a hold, so there is nothing to release: `UsageLedger.fairUseFits` (a read) refuses early (`USAGE_LIMIT`, straight to self-check), `consume` counts one answer check when a grade is
  delivered (key `assessment:{attemptId}`); a self-check, a failure or a dispute leaves the count as it is (a dispute keeps it: the model did grade).
- **Dispute** (`AssessmentService.dispute`, V32 `study_assessment_dispute`, `study_transition.kind/compensates_attempt_id/reason_code`, `attempt_id` nullable): allowed while the AI transition is the last
  one of the objective in the current epoch (the state row is locked, then `sequence` and epoch are compared); the compensating transition restores the before-state (level, streak, lapses, and `last_assessed_at`/`next_due` of the closest earlier real attempt, looking through earlier compensations; with none: `last_assessed_at` NULL and due now, which V32 allows by relaxing the `study_state` check to «sequence 0 implies NULL»), the receipt becomes `NOT_ASSESSED` + `disputed`, the evidence row stays; the journal row is counts only unless `shareExample`. `409 DISPUTE_NOT_ALLOWED` / `ASSESSMENT_STATE_CONFLICT`.
- **Reads**: `GET .../attempts/{id}` (`AssessmentController`), and the session's presentation carries `assessment {attemptId, status}` while an answer is in assessment (`StudySessionRepository.pendingAssessments`),
  so a reload resumes instead of answering again. Retention (`StudyRetentionService`) clears answers kept in assessment rows past the presentation's expiry.
- **Stub** (`StubAssessments`): markers in the learner answer `[[stub:assess-complete|shallow|partial|contradicted|offtopic|unclear|asr|disagree|injection|invalid|slow]]` and a lexical heuristic (documented in its Javadoc);
  `slow` waits 8 s for the harness. **Roles**: the grader runs where the `assess` route has a key (the process with the keys); with `learning.runtime.roles=api` and no key `aiAssessment` is unavailable
  and an `ai-semantic` exercise cannot be published or issued there. **Permits**: an S2/S3 answer takes two `learning.ai.permits.assess` slots, so the default is 32 = 2 × `learning.ai.assess.concurrency` (16); keep that rule when changing either. **Attempt cap**: one provider attempt of the `assess` route takes at most `learning.ai.routes.assess-attempt-cap` (`PT8S`; a capped attempt that times out hands over to the next provider instead of being retried) so the fallback fits the 20 s deadline. **In flight**: at most `learning.ai.assess.max-in-flight` (3) answers per account are graded at once; the next goes straight to self-check with reason `BUSY` (soft cap). **Prompt size**: the `assessment` ceiling (24k estimated tokens) covers the largest exercise the contract allows (question cut at 16,000 chars), so a published rubric is always gradable. A result that cannot be stored is retried once, then ends `UNAVAILABLE/PROVIDER_UNAVAILABLE` at once.
- Tests: `AssessmentFlowIntegrationTest`, `AssessmentDisputeIntegrationTest` (real context, PostgreSQL, the Stub, a blocking test double in `AssessmentTestConfiguration`), `SemanticPolicyTest`, `SemanticGraderTest`,
  `StubAssessmentsTest`, `AssessmentGoldenPolicyTest` (`contracts/study/assessment-golden`, 12 × 10, status `proposed`), the contract fixture `assessment.json`; opt-in live eval `SemanticEvalRunner`
  (`MNEMA_AI_EVAL=live MNEMA_AI_DEEPSEEK_API_KEY=... ./gradlew :services:learning:cleanTest :services:learning:test --tests '*SemanticEvalRunner*'`, report in `build/reports/assessment-eval/`).

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
  `aiAssessment` and `speechToText` as `{available, reason}`. `speechToText` is
  available only when `learning.features.speech-to-text.enabled` is true **and** a
  `SpeechToTextProvider` bean exists (none today, so it stays `DISABLED` or
  `PROVIDER_NOT_CONFIGURED`). `aiAssessment` is available when
  `learning.features.ai-assessment.enabled` is true **and** the `assess` route has a usable
  adapter (a key or the Stub) and a healthy route (`AiAvailability.assessment()`): see
  [AI assessment (#292)](#ai-assessment-of-free-explanations-292). Candidates whose evaluator
  needs an unavailable capability are never issued; an issued `ai-semantic` presentation whose
  capability went away is answered with self-check, never exact-matched and never an error.
- `/api/decks/{deckId}/study-sessions` starts and resumes owner-only
  `SCHEDULED`, `REPLAY` and `PRACTICE` snapshots. Candidate preparation reads at
  most 500 exercise rows per poll, selection scans at most 80 candidates and a
  response contains at most 20 immutable presentations. The authenticated
  `zoneinfo` claim determines the local study date; invalid or absent values fall
  back to UTC, and clients cannot submit a timezone. Resume returns only
  presentations without a terminal attempt. A presentation carries learner
  `content` resolved once at issue time (`MATERIAL` becomes `TEXT`; `CHOICE` options, `MATCH` sides, `ORDER` items and `CATEGORIZE` items
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
  the end, so a crash between chunks is repaired by an exact retry. Both hub aggregates are written so no
  plan depends on planner statistics: insights set `enable_nestloop=off` for their read-only transaction, and the
  sorted list combines members and exercise rows with `UNION ALL` + `GROUP BY` instead of joining two sets (on
  bulk-loaded tables without statistics the planner picked a nested loop that rescans a set per row, quadratic in
  Deck size; 2.1 s cold at 10 000 materials, now ~0.1 s). Limit problems carry the typed
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
- **`activeWork`** is the owner's count of generation sessions in `PLANNING` or `RUNNING` (`ActiveGenerationWork`, #287); media
  processing of generated assets does not count yet (an open question of the contract).
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
  error bodies are never read. DeepSeek: `thinking` is disabled explicitly (enabled on the plan routes), `user_id`, `prompt_cache_hit/miss_tokens`.
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
  `[[stub:invalid-mbm]]` simulate failures; the two `invalid` markers stop applying once a repair segment is present. A grading request (the `<grader>` rules of the assessment prompt) is answered by `StubAssessments` (markers `[[stub:assess-*]]` in the learner answer, see AI assessment).
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

Fresh Learning migrations V1–V28 are the database source of truth. V28 adds the generation tables (below) and, where the role
may, `pg_trgm`. V27 adds `ai_provider_call` (the provider-call journal). V21 (unified exercise
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
