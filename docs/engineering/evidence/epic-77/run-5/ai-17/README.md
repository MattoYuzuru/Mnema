# AI-17 operations and evaluation evidence

Status: operations implementation merged through [PR #370](https://github.com/MattoYuzuru/Mnema/pull/370)
as `f554a2b5`; owner acceptance of the evaluation remains pending.
Issue [#300](https://github.com/MattoYuzuru/Mnema/issues/300). Final integrated S1–S8 evidence is tracked
in [#302](https://github.com/MattoYuzuru/Mnema/issues/302). This record claims neither deployment,
prompt activation nor enabled production AI.

Learning can separate HTTP admission from provider execution without placing keys on the API.
Intent parsing now joins the worker boundary; its existing POST response waits for an ephemeral
database request without a transaction or borrowed connection. Worker claims are exclusive;
the deadline, interruption and deletion fence late results. Request text/context is cleared at
completion and expired rows are swept. Assessment claims, PostgreSQL wake hints, provider kill
switches, Moscow-day budgets and private metrics complete the recovered operations work.

The optional local/production split definitions reuse the Learning image. Local API credentials
are explicitly cleared; worker application/management listeners remain private. The production
overlay is outside the default protected rollout and keeps AI flags disabled. Activating that
topology requires its own reviewed dispatcher/monitoring admission. The operations contract is
the [AI runbook](../../../../../operations/ai-runbook.md).

## Evaluation result and limits

The [complete candidate report](./candidate-v2-report.md) and [JSON](./candidate-v2-report.json)
cover all 300 generation fixtures (80 notes, 80 prompts, 60 edits, 80 exercises), including 90
held-out cases. The production text route was tested with candidate prompt **v2** and
`golden-rubric-v3`; both acceptance judges answered every fixture. Active runtime prompts remain
**v1**. Neither a new prompt activation nor a human-reviewed quality gate is claimed.

| Measure | Result |
|---|---:|
| First-try / final validity | 97.3% / 100% |
| Repair rate | 2.7% |
| Exercise first-try / final validity | 92.5% / 100% |
| Both-judge acceptance, RU / other fixture languages | 92.4% / 87.1% |
| Critical judge flags / exemplar-copy violations | 0 / 0 |
| Prefix cache hit share | 94.6% |
| Generation p50 / p95 | 2.0 s / 6.0 s |
| Full run cost | $0.3357, using the adapter's configured price table |

These machine thresholds pass. The 144-answer semantic report embedded in the result is
**recovered evidence from 2026-10-05**, not a fresh assessment run: QWK 0.983/0.959/0.919 for
S1/S2/S3; false-accept 0 across the five adversarial kinds. Its labels remain `proposed`.
Acceptance judges' Cohen kappa 0.443 is diagnostic; it is not the answer-assessment QWK gate.

The first two Codex runs remain under [previous](./previous/): one flagged two outputs; the
second had 52 unavailable Qwen judgements and two flags. The final run lowered concurrency
from six to three, and neither judge was unavailable. Original Claude reports remain in the
owner's private scratch; they were not overwritten. Four current runs, including the extra
calibration sample, cost about $1.08 total.

The fixture/rubric corrections and why old/new results are not directly comparable are in the
[criteria drift log](../../../../../../contracts/generation/eval/README.md#criteria-drift-and-corrections):
the Korean destination rule contradicted its source; an edit judge lacked unchanged context
and the complete pre-edit source; the HSK request omitted the specific words its hidden
expectation required. Expectations, thresholds and held-out membership were not weakened.

This corpus covers short, synthetic text inputs, not media, large decks, long source budgets,
production usage/queue behavior or real-user acceptance. The database/role/retention behavior
is verified separately by integration tests. Judge opinions still need owner calibration.

## Owner evidence still required

The local `owner-calibration-review.md` deliverable contains **60 unique cases**: the balanced
40-case final-run sample plus 20 additional dev cases chosen without inspecting their outcomes.
It withholds judge opinions to reduce anchoring, includes inputs and unchanged edit context,
and leaves every accept/reject decision unmarked. The companion reports retain opinions for
comparison after scoring. Generated texts are not committed.

The owner must score the sample and calibrate the judges (at least 50 decisions), and review
the 144 proposed assessment labels. Real-user admission additionally depends on #280/#351.
The merged operations implementation does not replace these human decisions; neither unit tests
nor machine proxy acceptance establishes owner approval of candidate v2.

## Validation

The entries below preserve recovered/pre-integration evidence. The protected implementation
review and final slice gates are linked from PR #370; the final run5 composition belongs to #302.

- Targeted backend tests passed: existing intent behavior, keyless API/worker hand-over,
  exclusive claims, deadline and discarded-result fencing, caller-transaction rejection,
  permit recovery, provider switches, budget day boundary, wake listener, management boundary,
  corpus/schema and evaluation calculations.
- Optional compose topology tests, VPS/local runtime tests and metrics snapshot tests passed;
  the readiness helper compiled on JDK 25; documentation validation passed.
- `backend ./gradlew quality` passed on the recovered branch plus the current local corrections: Identity coverage 92.56%, Learning 95.11% (both floors 90%). This is preliminary evidence; the integrator re-runs the clean exact-head project gate after main integration.
- The full backend run exposed an existing media test fixture whose timestamp regressed 24 ms
  on Colima. Its four state transitions now use the same monotonic timestamp rule as production;
  the affected tests passed. The exact final project gate and independent review are recorded
  by the integrator after merging the current main into this branch.
- This slice has no frontend source changes. Preliminary `npm ci`, lint, 1,905 frontend tests and build passed on Node 24; the integrator repeats the exact-head project gate and integrated browser scenarios on the delivered tree. Existing Mermaid CommonJS build warnings remain.

## Rollback and sources

V39 adds notification triggers/assessment claims; V40 adds ephemeral intent requests. They were
unmerged/unapplied when numbered. Before an older runtime rollback, drain or remove expired
ephemeral requests so text does not remain without its sweeper. Keep the public AI flags disabled
while the human/quality gates are pending. Do not reset retained owner databases.

Official references consulted: [PostgreSQL NOTIFY](https://www.postgresql.org/docs/current/sql-notify.html)
for commit-only wake hints and sweep fallback;
[Spring Boot management ports](https://docs.spring.io/spring-boot/reference/actuator/monitoring.html)
for private listeners and the separate readiness path;
[Java 25 InetAddress](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/net/InetAddress.html)
for literal-only address validation without DNS;
[Spring Security request authorization](https://docs.spring.io/spring-security/reference/servlet/authorization/authorize-http-requests.html)
for the management GET/HEAD boundary.
