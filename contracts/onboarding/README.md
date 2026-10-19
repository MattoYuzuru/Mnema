# Onboarding journey (OB-E1, #520)

Wire contract of the first-entry journey: «Начало пути», the survey answers that belong to onboarding, the guided tour
state and the owner's holdout report. Exact examples, the transition table and the step registry are in
[`journey.json`](journey.json); the behaviour is [onboarding proposal](../../docs/engineering/onboarding/proposal.md)
layers 1–2, the design [onboarding architecture §3–4, §8, §11](../../docs/engineering/onboarding/architecture.md).
Backend tests and the frontend contract spec both execute the fixture.

| Operation | Request | Success |
|---|---|---|
| Read | `GET /api/onboarding` | 200 view with a strong `ETag`; `If-None-Match` with the current tag is 304 |
| Tour command | `PUT /api/onboarding/journey` `{commandId, rowVersion, action, branch?, stepKey?}` | 200 view |
| Preferences | `PUT /api/onboarding/preferences` `{commandId, rowVersion, experience?, weeklyTarget?}` | 200 view |
| Sample deck (OB-201) | `POST /api/onboarding/sample-deck` `{commandId}` | 201 view with the new copy, 200 view when a live one exists |
| Holdout report (owner console) | `GET /api/admin/onboarding/experiment?from=&to=` | 200 aggregates per variant |

- **Private.** Bearer required (`learning.read` for `GET`, `learning.write` for `PUT`); `Cache-Control: private, no-store`.
  The goal and the interests stay in their own APIs (`/api/learning-profile`, `/api/community/interests`); this contract
  stores only the tour state, `experience` and `weeklyTarget`. No user text, deck title or search query is stored.
- **The view.** `enabled` mirrors `learning.onboarding.enabled`; while it is false `journey` is `null`, every `PUT` is
  409 `ONBOARDING_UNAVAILABLE` and the client shows neither «Начало пути», the tour nor «Путь» (rollback). `milestones` and
  `path` are the «Путь Mnema» (OB-E3, below); while the flag is off they are `[]` and `null`. `rhythm` is the weekly rhythm
  (OB-401, below), `null` while `learning.onboarding.rhythm.enabled` is off.
  `sampleDeck` (OB-E2): `available` — the flag `learning.onboarding.sample-deck.enabled`, a configured template that is live and
  has a published revision; `deckId` — the account's live sample copy or `null`; `updateAvailable` — that copy has an update of
  the template waiting (the ordinary «Есть обновление»); `freeChecksLeft` — AI checks still free for the account in its sample
  deck (0–3, per account, not per copy).
- **Journey state.** `NOT_OFFERED` means the account was never offered the tour and did not start it from the profile: there
  is no row, or a row that holds only the rhythm and the preferences of such an account (`state NOT_OFFERED`, no cohort, no
  `offeredAt`; OB-401), which reads exactly like no row. The first `GET` of an **eligible** account creates the row in `OFFERED` exactly once
  (one `INSERT … ON CONFLICT DO UPDATE … WHERE state = 'NOT_OFFERED'`, concurrent first reads included: exactly one of them
  creates or upgrades the row and counts the exposure). Eligible means: the flag is on, the time is at
  or after `learning.onboarding.eligible-from`, Identity marks the account new (`mnema_new_account`; an absent or
  malformed claim is not new) and the account owns no live deck; a `NOT_OFFERED` row of an eligible account becomes that
  `OFFERED` row the same way. A non-eligible account gets `NOT_OFFERED` with `eligible=false` and no new row.
- **Holdout (OB-105).** When the row is created the server assigns the account to the cohort of the experiment
  `onboarding_v1` (existing `ExperimentAssignments`; `control` is the holdout) and freezes it on the row. A disabled
  experiment or a missing experiment secret stores the account as `UNASSIGNED`: it gets the tour and the report leaves it out
  (nobody is held out by accident, and nobody is mixed into the groups). `eligible`
  is true for a row created by eligibility; `autoOffer` is true only for `OFFERED` + `eligible` + not held out — the client
  opens «Начало пути» by itself only then. The variant itself is not on the wire. Every account, the holdout included,
  can start the tour from the profile (`REPLAY`).
- **Commands** are idempotent by `commandId` (the global command receipts: a repeat returns the stored response, the same
  id with another body is 409 `IDEMPOTENCY_CONFLICT`) and compare-and-set by `rowVersion` (decimal string; `"0"` for
  `NOT_OFFERED`): a stale version is 412 `VERSION_CONFLICT` and the client re-reads, a missing one is 428
  `PRECONDITION_REQUIRED`. A transition the table does not allow is 409 `ONBOARDING_TRANSITION`. `PUT /preferences`
  needs at least one of its fields (`null` clears a field) and works in every state: on `NOT_OFFERED` without a row it
  creates the `NOT_OFFERED` row (version 1), so the profile can change the weekly target of any account.
- **Steps.** `stepKey` is one of the closed registry `steps` (a step of another branch than the journey's is 400);
  the client's tour definitions and the server's registry are tested against the fixture, and the counters
  `mnema_onboarding_journey_total{action,step}` take their labels from it only. `branch` is `SAMPLE|COMMUNITY|OWN`,
  set by the first `STEP` into a branch step and cleared by `REPLAY`; a `branch` without a `stepKey` of that branch is 400.
  A repeated `commandId` answers the stored view with `Idempotency-Replayed: true`.
- **Report.** The owner console reads aggregates only, with the window of the console report (`from`/`to` are UTC dates,
  `to` exclusive, 1–90 days, `to` not after tomorrow; otherwise 400): for accounts that entered the cohort in `[from, to)` (by
  `offeredAt`), per variant the group size, the activated accounts (a completed SCHEDULED session within 7 days of
  `offeredAt`) and the returned ones (completed SCHEDULED or PRACTICE sessions on two different local days within 14
  days). `smallSample` is true while a group has fewer than `minimumGroup` accounts. No account id leaves the server.
- `POST /sample-deck` also answers the copy limits of the ordinary copy: 429 `RATE_LIMITED` (the account's copy window), 422
  `RESOURCE_LIMIT_EXCEEDED` (too many copies of the account still preparing) and 503 `FORKS_BUSY` (materialization queue), each with its usual meaning.
- Failure codes: `INVALID_REQUEST` (400), `AUTHENTICATION_REQUIRED` (401), `ACCESS_DENIED` (403, report without the admin
  scope), `ONBOARDING_TRANSITION` and `ONBOARDING_UNAVAILABLE` and `IDEMPOTENCY_CONFLICT` (409), `VERSION_CONFLICT` (412),
  `PRECONDITION_REQUIRED` (428).

- **Sample deck (OB-E2, [ADR-ONB-003](../../docs/engineering/onboarding/adr/adr-onb-003-sample-deck.md)).** The template is the
  editorial deck whose id is `learning.onboarding.sample-deck.template-id`; Learning marks it `purpose=SAMPLE_TEMPLATE` when it
  starts, so the editorial account installs and updates it through the ordinary authoring API and publishes it at `LINK`
  (not in the catalogue; the level copies need to follow updates of their source). `POST /sample-deck` copies its
  published revision with the existing copy (`ForkService`) as a system path, `purpose=SAMPLE`; one live copy per account (a
  repeat or a race returns the live one with 200; after the copy is deleted a new one is made). Idempotent by `commandId`. It
  does not need a journey row. `409 SAMPLE_DECK_UNAVAILABLE` when the flag is off or the template is missing, deleted or has no
  published revision; `409 ONBOARDING_UNAVAILABLE` when onboarding is off. Publication, access level, link, invitations and
  copying of a `SAMPLE` or `SAMPLE_TEMPLATE` deck are `409 SAMPLE_DECK_NOT_SHAREABLE` (publishing a new revision of the
  template by its owner stays allowed). A sample deck writes no engagement, catalogue events or interest signal.
- **Free AI checks.** The first `freeAiChecks` AI checks of an account in its `SAMPLE` deck (an `ai-semantic` grade or a speech
  transcription of an answer) cost the user nothing: they are counted as `ONBOARDING_SAMPLE_CHECK` in the usage ledger instead of
  the plan's buckets. Later ones are ordinary checks.

- **«Путь Mnema» (OB-E3, [ADR-ONB-002](../../docs/engineering/onboarding/adr/adr-onb-002-milestones.md)).** The closed registry of
  13 milestones — key, chapter, target, visibility condition, client route key, the writer and what a backfill can restore — is
  [`milestones.json`](milestones.json). `milestones` lists the **visible** milestones in registry order as
  `{key, state: DONE|PARTIAL|TODO, progress, target, achievedAt}`; `path` is `{done, total, hidden}` where `hidden` names the
  milestones a switched-off feature hides (AI assessment, speech-to-text, AI generation, an empty or closed catalogue, the
  rhythm while `learning.onboarding.rhythm.enabled` is off). 100 % is `done = total` over the visible ones. The facts live in `account_milestone` (V74): one row per
  account and milestone, written **in the transaction of the domain action** by `MilestoneRecorder`
  (`@Transactional(MANDATORY)`, one upsert; a rolled-back action leaves no milestone), source `LIVE` or `BACKFILL`. Rows are
  written whatever `learning.onboarding.enabled` says (harmless facts), are never reset (a tour replay keeps them) and are read
  only while the flag is on. Practice, replay, someone else's copy and the sample deck do not count the author's milestones
  (`purpose=USER` in their conditions); the sample deck counts `FIRST_DECK`, `FIRST_SESSION`, `TWO_DAYS` and `AI_EXPLANATION`.
- **Milestone notification.** A live record that reaches its target publishes `PATH_MILESTONE` (route `PATH`, params
  `key`, `done`, `total`, dedupe `path:{key}`) in the same transaction, only while the flag is on
  ([notifications contract](../notifications/README.md)). The backfill never notifies.
- **Backfill (OB-302).** A bounded background job (queue `onboarding.backfill`; scheduled only while `learning.onboarding.backfill.enabled` is on, default off; `learning.jobs.onboarding.backfill.enabled` is the queue kill switch) writes `BACKFILL` rows
  from the account's history; it raises `progress` to what history shows (never lowers it) and sets `achievedAt` from the fact's
  time. Facts history no longer holds (deleted notes, speech transcripts after 15 minutes, practice outcomes after retention)
  stay undone; see `backfill.restorable` per milestone.

- **Weekly rhythm (OB-401, [ADR-ONB-004](../../docs/engineering/onboarding/adr/adr-onb-004-rhythm.md)).** `rhythm` is
  `{weekStart, today, target, days, weeksMet}`: `today` is the account's local date (the token's `zoneinfo` claim, UTC when it
  is absent or invalid, as Study), `weekStart` the Monday of its ISO week, `target` the journey's `weeklyTarget` or the default
  3, `days` the distinct `local_study_date`s of completed SCHEDULED or PRACTICE sessions in `[weekStart, weekStart + 7)` in
  ascending order (REPLAY never counts; the stored date of a session decides, so a change of time zone moves no session), and
  `weeksMet` the weeks in which the target was met. The week is met when `days` reaches `target`; the client derives it, there
  is no flag. `weeksMet` grows by one per ISO week at most, in the transaction of the completion that meets it (or of the
  `PUT /preferences` that lowers the target below this week's days): an account-scoped lock serializes the count of concurrent
  completions, and `last_met_week` (the Monday of the last counted week) makes the increment once per week. The first met
  week also achieves `RHYTHM_WEEK`. Nothing is negative: a missed day or week changes nothing and a new week starts empty. The
  weeks are counted from the moment the rhythm is on; history is not replayed (a past week's target is unknown).

- **Path reward (OB-402, [ADR-ONB-005](../../docs/engineering/onboarding/adr/adr-onb-005-path-reward.md)).** `reward` is `null` unless
  `learning.onboarding.reward.enabled` (default off) is on and the account has a reward; otherwise
  `{state: ACTIVE|REDEEMED|EXPIRED, code, offer: {type, percent, plan, days}, issuedAt, expiresAt}`. The reward is a **personal
  promo code** of the owner's enabled `PATH_REWARD` campaign ([promo contract](../usage/promo.json): the owner creates it in the
  console, a discount percent or a tier for N days, one enabled at a time, the console's kill switch). It is issued by the
  server, never claimed: the `GET` of an account whose visible milestones are all done (`path.done = path.total`) and that
  Identity calls trusted (`mnema_trusted`: verified e-mail, at least 3 days old) issues it once, under an account-scoped lock,
  in its own transaction before the read's, which it cannot fail (the client re-reads the view after every `PATH_MILESTONE`, so the read
  follows the last milestone; an untrusted account is retried on a later read). One per account for ever
  (`onboarding_reward.account_id` is the key): a replayed tour or a new campaign issues nothing more. `expiresAt` is
  `issuedAt + 7 days`, the end of the code's validity on the server; the client shows that date and time as text, never a
  countdown. `code` (display form) is present only while `ACTIVE`: it is derived from the code id under the promo secret, never
  stored, and only its recipient can redeem it on `/plans` through the ordinary redemption (another account gets
  `PROMO_INVALID`). The issuance publishes `PATH_REWARD_READY` ([notifications](../notifications/README.md)). At most
  `learning.onboarding.reward.daily-limit` codes per campaign in 24 hours. Codes already issued are not revoked by any switch.

Account purge (#409) deletes `onboarding_journey` (the `NOT_OFFERED` rows of the rhythm included), `account_milestone` and `onboarding_reward` by `account_id` (TODO in the repositories, like `learning_profile`).
