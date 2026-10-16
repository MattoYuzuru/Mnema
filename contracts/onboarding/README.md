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
| Holdout report (owner console) | `GET /api/admin/onboarding/experiment?from=&to=` | 200 aggregates per variant |

- **Private.** Bearer required (`learning.read` for `GET`, `learning.write` for `PUT`); `Cache-Control: private, no-store`.
  The goal and the interests stay in their own APIs (`/api/learning-profile`, `/api/community/interests`); this contract
  stores only the tour state, `experience` and `weeklyTarget`. No user text, deck title or search query is stored.
- **The view.** `enabled` mirrors `learning.onboarding.enabled`; while it is false `journey` is `null`, every `PUT` is
  409 `ONBOARDING_UNAVAILABLE` and the client shows neither «Начало пути» nor the tour (rollback). `milestones`, `path`,
  `rhythm` and `sampleDeck` are reserved now so the client never changes shape: until OB-E2/E3/E4 they are `[]`, `null`,
  `null` and `{"available": false, "deckId": null, "updateAvailable": false}`.
- **Journey state.** `NOT_OFFERED` means there is no row: the account was never offered the tour and did not start it
  from the profile. The first `GET` of an **eligible** account creates the row in `OFFERED` exactly once
  (`INSERT … ON CONFLICT DO NOTHING`, concurrent first reads included). Eligible means: the flag is on, the time is at
  or after `learning.onboarding.eligible-from`, Identity marks the account new (`mnema_new_account`; an absent or
  malformed claim is not new) and the account owns no live deck. A non-eligible account gets `NOT_OFFERED` with
  `eligible=false` and no row.
- **Holdout (OB-105).** When the row is created the server assigns the account to the cohort of the experiment
  `onboarding_v1` (existing `ExperimentAssignments`; `control` is the holdout) and freezes it on the row. A disabled
  experiment or a missing experiment secret puts the account in `TOUR` (nobody is held out by accident). `eligible`
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
  set by the first `STEP` into a branch step and cleared by `REPLAY`.
- **Report.** The owner console reads aggregates only: for accounts that entered the cohort in `[from, to)` (by
  `offeredAt`), per variant the group size, the activated accounts (a completed SCHEDULED session within 7 days of
  `offeredAt`) and the returned ones (completed SCHEDULED or PRACTICE sessions on two different local days within 14
  days). `smallSample` is true while a group has fewer than `minimumGroup` accounts. No account id leaves the server.
- Failure codes: `INVALID_REQUEST` (400), `AUTHENTICATION_REQUIRED` (401), `ACCESS_DENIED` (403, report without the admin
  scope), `ONBOARDING_TRANSITION` and `ONBOARDING_UNAVAILABLE` and `IDEMPOTENCY_CONFLICT` (409), `VERSION_CONFLICT` (412),
  `PRECONDITION_REQUIRED` (428).

Account purge (#409) deletes `onboarding_journey` by `account_id` (TODO in the repository, like `learning_profile`).
