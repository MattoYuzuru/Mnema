---
artifact:
  id: epic-75-integrated-acceptance
  type: verification-evidence
  title: "Epic #75 integrated local acceptance"
  status: historical
  updated_at: "2026-09-24"
  owners: ["project-owner"]
  evidence_revision: "d7fd1b1d509a0ab598976f87af88101bfc3945ac"
---

# Epic #75: integrated local acceptance — 2026-09-24

## Result and boundary

The implementation baseline is protected `main` squash
`d7fd1b1d509a0ab598976f87af88101bfc3945ac`, containing implementation
children #212–#219, #58 and the related persistent launcher #220. This closing
slice adds an all-P0 real-API smoke, real Chrome Study journey, a focus fix and
reconciled documentation. Its protected PR and exact merged revision are recorded
in [#221](https://github.com/MattoYuzuru/Mnema/issues/221).

An authenticated local user can publish versioned material and an exercise, start
one deck's bounded Study queue, complete all four P0 mechanics, see explainable
progress, explicitly restart, replay a completed session and practice without
changing canonical progress. The local launcher supplies persistent PostgreSQL,
Identity, Learning and a production Angular bundle over localhost HTTPS. There
was no deployment or hosted-environment operation.

## Acceptance trace

The contract identifiers come from the [exercise catalog](../../../../product/exercise-catalog-v2.md).
The paths below are executable sources; the full tests were run in the gate below.

| Contract | Integrated evidence |
|---|---|
| AC-STUDY-01 | `StudySessionServiceIntegrationTest` and `ExerciseServiceIntegrationTest` check deck/owner and pinned revision isolation. The browser and persistent API smoke use a deck-scoped Study path; choice options are server-pinned bindings. |
| AC-STUDY-02 | Browse is read-only; `AttemptServiceIntegrationTest.cancelAndPracticeProduceNoCanonicalEvidenceOrRawResponse` checks cancel. No scheduled transition exists before submit in `quickBudgetSelectsKnownFirstAndIntroducesAtMostTwoObjectives`. |
| AC-STUDY-03 | `AttemptServiceIntegrationTest` verifies one assessed objective/evidence/transition; `ExerciseServiceIntegrationTest` enforces binding publication. The persistent all-P0 smoke checks scheduled evidence and material progress for every mechanic. |
| AC-STUDY-04 | Cancel, old epoch and unavailable/error handling are tested by `AttemptServiceIntegrationTest`, `AttemptEvaluationTest` and Study controller/component suites. Failures do not create incorrect evidence. |
| AC-STUDY-05 | Exact retry and conflicting payload/terminal submission tests in `AttemptServiceIntegrationTest`, including concurrent first attempts (one receipt and one transition). The HTTPS smoke repeats each P0 attempt and requires `Idempotency-Replayed: true`. |
| AC-STUDY-06 | `oneHundredPracticeAndReplaySubmissionsCannotChangeCanonicalState` compares state, exposure, evidence and transitions. Persistent HTTPS smoke checks replay/practice receipts and unchanged progress. Offline synchronization is outside this local online runtime; a forged mode cannot change a server-pinned session. |
| AC-STUDY-07 | `scheduledAttemptRetriesTransitionsAndRestartRetainsHistoryWhileRejectingOldEpoch` checks history and stale epoch. Persistent smoke observes restart and repeatability after stop/start. |
| AC-STUDY-08 | `BoundedCandidatePlannerTest` exercises 1k/10k/50k candidate windows. `StudySessionServiceIntegrationTest` checks the seeded/wrapped PostgreSQL window, an introduced objective outside it, server-enforced quick 10/2 and zero pre-submit transitions. The `deck-due-new-v3` query reads due/unassessed objectives through indexed state/objective paths (each capped at 80) and at most two indexed slices of 80 new-candidate ordinals before ranking; each batch issues at most 20 presentations. This proves bounded selection memory and indexed access, not a measured PostgreSQL 50k latency/SLO. |
| AC-EVAL-01, AC-EVAL-02, AC-EVAL-03 | `AttemptEvaluationTest`, `StudyContractFixtureTest` and `AttemptServiceIntegrationTest` prove deterministic result classes, reason/rule feedback and the versioned reducer table. The Chrome flow displays server feedback after submit. |
| AC-EVAL-04, AC-EVAL-05 | `clozeAndChoiceUseCanonicalReducerWithConservativeEvidence` and `selfCheckCreatesLowEvidenceAndForeignRestartIsOpaque` check recognition/self-check LOW and hinted cloze MEDIUM. The real HTTPS all-P0 smoke repeats those classes. Confidence/time are diagnostic in the canonical contract. |
| AC-MULTI-01, AC-MULTI-02 | `ExerciseServiceIntegrationTest` pins immutable item/objective/exercise revisions and OPTION bindings; `AttemptServiceIntegrationTest` checks only the assessed objective receives state. Multi-assessed matching is explicitly P1. |
| AC-MULTI-03, AC-MULTI-04 | The accepted P0 model publishes one directional objective per attempt; reverse is separately identified. Invalid or undecomposable assessed bindings are rejected by `ExerciseServiceIntegrationTest` and command validation. |
| AC-A11Y-01, AC-A11Y-02, AC-A11Y-03 | `StudySessionPageComponent` tests all four semantic controls, answer secrecy, feedback and post-render focus. Real Chrome confirms keyboard Space start, answer/feedback focus, reduced motion, 1440/390/320 CSS px reflow, DPR 2 and 44px primary action; screenshots below. Manual screen-reader, speech and physical touch sessions remain unrun. |
| AC-LEGACY-01 | `app.routes.ts` routes canonical Study to the new lazy component. Learning Study packages depend on fresh Learning domain/reducer, not `core` review algorithms. The old `my-study` route remains isolated legacy input for #146; no replacement Study flow calls it. |

## Runs on the integrated baseline and closing candidate

- `./backend/gradlew clean quality`: pass, 41 tasks; Learning coverage 95.75%,
  all six service coverage floors green. No separate backend lint/static-analysis
  task is configured.
- Frontend lint, ChromeHeadless tests and production build: pass; 191 tests after
  the post-render focus regression test.
- Real Identity↔Learning black box: 25 scenarios, including authorization,
  revocation, 120-request bounded sequence, timeout and outage fail-closed.
  Fourteen cancellation tests and both real signal cleanup cases pass.
- Persistent launcher: `start → smoke → stop → start → smoke → stop` passes. Both
  smokes reuse the synthetic account and retained exercises; all P0 scheduled
  attempts produce their expected `HIGH/MEDIUM/LOW` evidence, exact retries and
  progress. Replay/practice do not change progress. The first start on this
  workstation encountered a retained PostgreSQL volume with missing local
  credential state; the local role password was synchronized with a newly
  generated owner-only state without deleting that volume, then both starts pass.
- Real HTTPS Chrome 154: Deck → Capture → acknowledged draft → publish → Browse →
  typed Study via keyboard → server feedback passes with zero runtime errors and
  zero external requests. [Desktop](study-1440.png), [390 px](study-390.png),
  [320 px at DPR 2](study-320-at-200-percent.png) and
  [feedback](study-feedback-1440.png) are synthetic browser captures.
- Repository policy, docs, release/security, backup/recovery contract and
  disposable purge checks are required again on the closing PR's exact commit;
  hosted `backend-quality`/`frontend-quality` and protected squash are the
  final delivery gates. Passing these fixtures is not an operational run.

## Remaining limits and rollback

No production, staging, deployment, data recovery, physical device or manual
assistive-technology result is claimed. The 50k planner fixture bounds requested
windows but does not establish a live database latency target at 50k exercises;
cohort calibration of evidence classes and week-two retention needs real users.
P1/P2 mechanics, cross-item assessed matching, media #76, global legacy removal
#146 and cutover #147 remain separate work. The local implementation can be
reverted through a protected PR; its append-only data migrations require a
forward migration after any real persisted Study data exists.
