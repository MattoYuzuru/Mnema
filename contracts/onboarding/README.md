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
  409 `ONBOARDING_UNAVAILABLE` and the client shows neither «Начало пути» nor the tour (rollback). `milestones`, `path`,
  `rhythm` are reserved now so the client never changes shape: until OB-E3/E4 they are `[]`, `null` and `null`.
  `sampleDeck` (OB-E2): `available` — the flag `learning.onboarding.sample-deck.enabled`, a configured template that is live and
  has a published revision; `deckId` — the account's live sample copy or `null`; `updateAvailable` — that copy has an update of
  the template waiting (the ordinary «Есть обновление»); `freeChecksLeft` — AI checks still free for the account in its sample
  deck (0–3, per account, not per copy).
- **Journey state.** `NOT_OFFERED` means there is no row: the account was never offered the tour and did not start it
  from the profile. The first `GET` of an **eligible** account creates the row in `OFFERED` exactly once
  (`INSERT … ON CONFLICT DO NOTHING`, concurrent first reads included). Eligible means: the flag is on, the time is at
  or after `learning.onboarding.eligible-from`, Identity marks the account new (`mnema_new_account`; an absent or
  malformed claim is not new) and the account owns no live deck. A non-eligible account gets `NOT_OFFERED` with
  `eligible=false` and no row.
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
  needs a row (409 `ONBOARDING_TRANSITION` on `NOT_OFFERED`) and at least one of its fields; `null` clears a field.
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
  `RESOURCE_LIMIT_EXCEEDED` (deck caps) and 503 `FORKS_BUSY` (materialization queue), each with its usual meaning.
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

Account purge (#409) deletes `onboarding_journey` by `account_id` (TODO in the repository, like `learning_profile`).
