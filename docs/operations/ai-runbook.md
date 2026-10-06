# AI operations runbook

Status: current. Applies to the Learning service of the AI layer (issues #282, #296 to #300).
It describes how the layer is run, switched and observed; it does not deploy anything. A
release follows [Production delivery](./production-delivery.md); the egress transport has its
own [AI egress proxy](./ai-egress-proxy.md) runbook. Architecture and decisions:
[ai-generation-platform.md](../architecture/ai-generation-platform.md) (§2 roles, §4 wake-up,
§9 providers and budgets). The quality gate before a change of a route, a prompt or a model is
the golden eval of the [eval gate](#8-eval-gate-before-changing-a-model-route-or-prompt).

## 1. Topology

One Learning image runs in one of three roles (`MNEMA_RUNTIME_ROLES` = `learning.runtime.roles`):

| Role | Runs | Needs provider keys |
|---|---|---|
| `all` (default, local and first release) | HTTP, step dispatcher, speech worker, answer grader, every sweeper | yes |
| `api` | HTTP, creates work (sessions, speech inputs, answers), retention sweepers that touch no provider | no |
| `worker` | step dispatcher, speech worker, answer grader, the wake listener | yes |

What calls a provider, and where it runs:

| Work | Component | Roles |
|---|---|---|
| Generation steps (draft, exercises, edit, plan, speech clips, image search, research) | `StepDispatcher` | `worker`, `all` |
| Speech inputs (dictation, spoken answers) | `SpeechInputWorker` | `worker`, `all` |
| Grading of an `ai-semantic` answer | `AssessmentRunner` | `worker`, `all` |
| «Попросить Мнему…» intent (free of credits) | `IntentRunner` | `worker`, `all` |

Non-provider sweepers follow their module rules: generation retention runs on the worker;
speech/answer expiry and usage holds touch only the database. No API request executes a provider call.

**Grading is a hand-over, not a request.** An answer is accepted by whichever process
received it and is stored `ASSESSING` with a 20 s deadline. The first grader to claim the row
(`study_assessment.claimed_at`) grades it; the claim makes the accepting process and a worker
safe to race. With `api` + `worker` the insert sends `NOTIFY mnema_assessments` and the worker
grades within milliseconds; if the notification is lost the worker's sweep
(`learning.ai.assess.sweep-interval`, 2 s) takes it, still inside the deadline. A crash after the
claim leaves the row to the deadline sweeper (the learner is offered self-check, nothing is lost).

**Deployment rule for a split topology.** `api` and `worker` run the same image with the same
non-secret configuration (routes, models, flags, kill switches, budgets, the egress proxy
address, the Yandex folder id). The provider keys, the user-key secret and the egress proxy
credentials exist only on the worker. On the `api` process set
`MNEMA_PROVIDER_CREDENTIALS=worker` (`learning.runtime.provider-credentials`): the credentials it
lacks are replaced by a non-secret placeholder for configuration inspection, so `GET /api/capabilities` is computed from the
configuration and reports what the worker can do, without a key on the api host. It is refused
with any other role than `api`. Without it an api process without keys would report
`PROVIDER_NOT_CONFIGURED` for everything. This mode is a declaration that the worker has been configured with those credentials, not a worker-health probe.
The worker always verifies its own actual configuration before a call. An API process refuses to start if it holds provider,
user-key or proxy credentials, including when its credential mode is `local`.

**Intent parsing** also goes through the worker. The API pins and validates the context, applies the existing hourly limit,
then stores an ephemeral `generation_intent_request` (V40). Its unchanged POST waits for the worker result with no open
transaction or borrowed connection. A worker claims it once, checks ownership and its own capability again, and uses only
the remaining part of the 20 s deadline. Completion clears request text/context; the API removes the row after returning,
on interruption or timeout. The sweeper deletes expired rows (at most one sweep after the deadline). A removed row fences
late results; a worker crash yields `409 CAPABILITY_UNAVAILABLE`, with no product publication or credit debit.

**Optional split definitions** are checked in but do not activate production features:

- Local: `MNEMA_LOCAL_AI_SPLIT=true scripts/mnema-local-full-stack.sh start` adds
  `compose.local-ai-worker.yml`; set the same flag on smoke/status/stop commands. The API gets no provider credentials,
  and `learning-ai-worker` reuses the Learning build and non-secret configuration. Its application and management listeners
  are container-loopback only, without published ports or Docker socket.
- Production: `deploy/production/compose.ai-worker.yaml` is an opt-in definition for a separate reviewed topology change,
  outside the default protected rollout. It reuses the verified Learning image, binds the worker at loopback 18084 and
  management at 18085, and keeps the base AI gates disabled. Give both roles identical admitted routes, feature flags,
  provider switches and budgets; keys go only to the worker. Keep the sum of both database pools within PostgreSQL capacity.
  Its health check reads the management readiness path. Do not include the overlay until the deployment dispatcher,
  monitoring and operator admission have been reviewed for the extra process.


**Wake-up when split.** Work is created in a transaction; PostgreSQL triggers (migration V39)
`NOTIFY` on `mnema_generation_steps`, `mnema_speech_inputs` , `mnema_assessments` and `mnema_generation_intents` when it
commits (a rolled-back transaction notifies nobody). A `worker` process holds one dedicated JDBC
connection that `LISTEN`s on the four channels (log lines `wake_listener_connected` and
`wake_listener_disconnected`; it reconnects with a backoff of 0.5 s to 30 s and looks at every
queue after each reconnect). A notification is a hint; each worker's sweeper reads the tables and
is the source of truth: if the listener is down the system is slower by at most one sweep
interval (2 s for steps, speech inputs and answers). `all` wakes itself in process and starts no
listener. The listener needs a direct connection to PostgreSQL (not a transaction-mode pooler).

**Egress.** Providers unreachable from Russia (Google Gemini for speech, Perplexity) go through
the stateless CONNECT proxy ([AI egress proxy](./ai-egress-proxy.md)); keys stay on the Russian
host. DeepSeek, OpenRouter, Yandex (SpeechKit, Search), Pixabay, Openverse and Wikimedia are
called directly.

## 2. Switches, and how to apply them without a release

Every switch is configuration: change the environment of the Learning container (the `environment`
block of `deploy/production/compose.yaml`, or the `.env` it reads on the host) and restart the
process. A restart of a worker is graceful (running steps are handed back, no attempt is burnt;
speech inputs and answers in flight fall to their deadlines). Nothing below needs an image.
Rolling a change out to the api process first or last does not matter.

| Switch | Setting | Effect |
|---|---|---|
| Capability flag | `LEARNING_FEATURES_<NAME>_ENABLED=false` for `AI_GENERATION`, `AI_ASSESSMENT`, `SPEECH_TO_TEXT`, `TEXT_TO_SPEECH`, `IMAGE_SEARCH`, `WEB_SEARCH` | the capability is `DISABLED`; its endpoints answer `409 CAPABILITY_UNAVAILABLE`; running work finishes |
| Provider kill switch | `learning.ai.providers.<id>.enabled=false` (`deepseek`, `openrouter`, `gigachat`, `google`, `yandex`, `yandex-search`, `perplexity`, `pixabay`, `openverse`, `wikimedia`, `selfhost`) | the provider has no usable adapter: its route entries are skipped and the next entry serves; with none left the capability is `PROVIDER_NOT_CONFIGURED`. Works for every kind (text, speech, speech to text, image sources, search) |
| Egress kill switch | `LEARNING_AI_EGRESS_ENABLED=false` | every `egress=proxy` provider has no transport (Google speech and speech to text, Perplexity) and falls back or is `PROVIDER_NOT_CONFIGURED`; direct providers are untouched |
| Daily budget | `LEARNING_AI_BUDGET_<TEXT\|ASSESS\|TTS\|IMAGE\|SEARCH\|IMAGE_SEARCH\|STT>_MICROS` (micro-dollars per Moscow day; `0` = no limit) | the capability is `TEMPORARILY_UNAVAILABLE` until the Moscow day ends, then clears itself |
| Route | `learning.ai.routes.<route>` (`provider:model,provider:model`) | order of preference and fallback; an entry whose provider has no key is skipped |
| Stub | `LEARNING_AI_PROVIDER=stub` | deterministic Stub for every text route; for tests and demos, never in production |

Spring's environment mapping removes dashes from map keys, so `LEARNING_AI_PROVIDERS_YANDEX-SEARCH_…`
cannot be written as a shell variable. For a provider id with a dash (`yandex-search`) use
`SPRING_APPLICATION_JSON='{"learning.ai.providers.yandex-search.enabled":false}'`; the other ids
work as `LEARNING_AI_PROVIDERS_<ID>_ENABLED=false` (for example `…_GOOGLE_ENABLED`).

Properties, defaults and ranges: [runtime policy index](../engineering/runtime-policy-index.md).

## 3. What a capability reason means

`GET /api/capabilities` (and the problem `reason` of `409 CAPABILITY_UNAVAILABLE`) is computed
per call; nothing below needs a restart to clear except the first two.

| Reason | Meaning | First thing to check |
|---|---|---|
| `DISABLED` | The flag is off | the `LEARNING_FEATURES_…` variable |
| `PROVIDER_NOT_CONFIGURED` | No route entry has an adapter: key or secret missing, provider killed, egress proxy not active for a proxied provider, `learning.ai.user-key.secret` missing for text | the key variables of the worker; the `ai_stub_active`, `ai_egress`, `ai_speech_provider`, `ai_stt_provider`, `ai_search_provider` and `ai_image_source` start-up lines say which entry is `not_configured`; on an api process, `MNEMA_PROVIDER_CREDENTIALS=worker` |
| `TEMPORARILY_UNAVAILABLE` | The capability's daily budget is spent, or every entry of the route has an open circuit breaker (5 failures in 60 s open it for 30 s; one probe goes after that) | `mnema_ai_cost_micros_total` against the budget; `mnema_ai_calls_total{outcome="CIRCUIT_OPEN"}` and the error outcomes of the provider |

Call outcomes in `mnema_ai_calls_total`: `OK`, `RATE_LIMITED` (429; waits `Retry-After` up to 30 s,
then falls through), `TRANSIENT` (5xx, network), `TIMEOUT`, `INVALID_OUTPUT` (after the repair
round), `REFUSAL` (policy; never retried), `BUDGET_EXHAUSTED`, `NOT_CONFIGURED`, `CIRCUIT_OPEN`.
Speech inputs fail with `UNAVAILABLE`, `TOO_LONG`, `UNSUPPORTED_AUDIO`, `NO_SPEECH`; answers fall
to self-check with `DEADLINE`, `CAPABILITY_UNAVAILABLE`, `PROVIDER_UNCERTAIN`, `USAGE_LIMIT`,
`BUSY`, `PROVIDER_UNAVAILABLE`.

## 4. Metrics

Actuator `health` and `metrics` are served on a **separate management port**, never on the public API
(the public port answers 401 or 404 for `/actuator/metrics`). The service refuses to start when
`metrics` is exposed without its own port, or with a port whose address is not a loopback/private IP literal. Wildcard/public addresses are rejected. Enable it with the
environment of the opt-in Learning worker (matching `compose.ai-worker.yaml`):

```text
MANAGEMENT_SERVER_PORT=18085
MANAGEMENT_SERVER_ADDRESS=127.0.0.1
MNEMA_MANAGEMENT_EXPOSURE=health,info,metrics
```

On the production host Learning is on host networking; the opt-in worker serves its private API on
`127.0.0.1:18084` and management on `127.0.0.1:18085`. Reach management over SSH (`ssh mnema`), never through Caddy. The management port
answers `GET` and `HEAD` without a token and nothing else. CSRF protection remains enabled through the default HttpOnly cookie
repository, without a server session. Safe health/metrics reads
defer token generation and create no cookie; unsafe methods remain forbidden even with a valid CSRF token
([Spring Security CSRF](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html)). The default logout handler is disabled,
since its filter would run before authorization and intercept `POST /logout`
([Spring logout architecture](https://docs.spring.io/spring-security/reference/servlet/authentication/logout.html)). With a separate management port,
the health probes move there too, as `/actuator/health/readiness` (without the `/api` prefix). The overlay health check already uses
`MnemaReadiness 18085 /actuator/health/readiness`; its bounded helper allows 18081, 18082 and 18085 only.
The default `deploy/production/health-monitor.py` still probes the API on 18082. An opt-in rollout must add a worker
readiness check at `http://127.0.0.1:18085/actuator/health/readiness` and metrics scraping on 18085. If management is moved
to a different port, update the helper allowlist, container health check and monitoring URLs together. Setting only the
port does not preserve readiness. Default is off: the variables are unset and the process behaves as before.

A snapshot as a table (stdlib only):

```sh
python3 scripts/ai-ops/metrics_snapshot.py --url http://127.0.0.1:18085/actuator
```

It prints calls, error rate, p95 latency and cost per capability and provider, the age of the
oldest due step and the credits held by reservations. Figures are since the process started; the
spend the budget guards is the sum of `app_learning.ai_provider_call` for the Moscow day.

| Metric | Type, tags | Use |
|---|---|---|
| `mnema_ai_calls_total` | counter: `provider`, `model`, `capability`, `outcome`, `egress` | error rate per provider; `egress` splits direct and proxy |
| `mnema_ai_call_seconds` and `.percentile` (`phi` 0.5, 0.95) | timer: `provider`, `model`, `capability` | latency |
| `mnema_ai_cost_micros_total` | counter: `provider`, `model`, `capability` | spend; compare with the daily budget |
| `mnema_generation_steps_total` | counter: `kind`, `outcome` | step success, invalid output, failures, cancellations |
| `mnema_generation_repairs_total` | counter: `route` | repair rate (repairs against steps) |
| `mnema_generation_exercise_findings_total` | counter: `code` | lint findings of generated exercises |
| `mnema_generation_plan_failed_cost_micros_total` | counter: `outcome` | money spent on plans that failed |
| `mnema_generation_step_queue_age_seconds` | gauge | how long the oldest due step has waited for a worker |
| `mnema_usage_reserved_credits` | gauge | credits held by active reservations (a growing floor is a leak) |
| `mnema_tts_cache_total` | counter: `outcome` | speech cache hits and misses |
| `mnema_stt_inputs_total` | counter: `outcome` | speech inputs by result |
| `mnema_stt_latency_seconds` | timer: `provider`, `outcome` | speech-to-text latency |
| `mnema_assessment_total` | counter: `outcome`, `reason` | answer checks: graded, uncertain, unavailable, discarded |
| `mnema_assessment_seconds` | timer: `strictness` (p50, p95) | accepted answer to stored grade; target p50 3 s, p95 8 s |

No Prometheus registry is bundled: the endpoint is the actuator's JSON. A scraper would need
`micrometer-registry-prometheus` (a dependency change the owner decides).
Not present: an accepted-ratio of generated material (the Workshop approval counts live in
the database, not as a meter).

Thresholds to watch (a starting set; tune against a week of real traffic):

| Signal | Warn | Act |
|---|---|---|
| error rate of a provider (`outcome` other than `OK`) over 15 min | > 5 % | > 20 %, or its circuit opens: [provider outage](#provider-outage) |
| p95 text call latency | > 60 s | > 120 s |
| `mnema_generation_step_queue_age_seconds` | > 30 s for 5 min | > 120 s: workers are saturated or down |
| repair rate | > 10 % | > 25 %: a prompt or model regression, run the [eval gate](#8-eval-gate-before-changing-a-model-route-or-prompt) |
| spend of a capability today | 70 % of its budget | 100 % (it turns `TEMPORARILY_UNAVAILABLE`) |
| `mnema_assessment_seconds` p95 | > 8 s | > 15 s; a rising `DEADLINE` share |
| `mnema_usage_reserved_credits` | grows between quiet hours | holds never end: check the expiry worker |
| `wake_listener_disconnected` lines | any burst | continuous: PostgreSQL unreachable for the worker |

## 5. Incident playbooks

### Provider outage

1. Look at `mnema_ai_calls_total` for the provider: `TRANSIENT`, `TIMEOUT` or `CIRCUIT_OPEN` outcomes, and
   `/api/capabilities`. Five failures in 60 s open the circuit for 30 s; the route then serves the
   next entry (DeepSeek, then OpenRouter with the same models, then GigaChat) and the capability stays
   `AVAILABLE` while one entry is healthy.
2. If the fallback is the same family and also failing, or the outage is long, set the capability
   flag off (it becomes `DISABLED`, the UI explains it) instead of letting every request wait.
3. When the provider is back the breaker half-opens by itself. A provider you took out with its
   kill switch needs the switch removed and a restart.

### Cost spike

1. `scripts/ai-ops/metrics_snapshot.py` for the capability and provider; the journal
   (`ai_provider_call`) for the Moscow day.
2. The daily budget stops the capability when it is spent (`TEMPORARILY_UNAVAILABLE` until the Moscow midnight).
   To stop earlier lower the `LEARNING_AI_BUDGET_<CAPABILITY>_MICROS` value and restart, or flip the flag.
3. Find the cause: a loop of repairs (repair rate), a `plan_failed_cost` rise, one account (fair-use and
   credit ledgers are per account; the journal has no text and no account, a step id leads to the session).
4. The provider-side spend cap remains the last fuse: the budget fails open if the journal cannot be read.

### Quota 429 (for example Gemini Flash RPD)

A daily request quota (a provider's RPD) returns 429 with a long or missing `Retry-After`; the router
waits at most 30 s, then falls through. Symptom: `RATE_LIMITED` outcomes of one provider, the other
entries serving. Actions: let the fallback serve (speech: Flash-Lite TTS then Flash TTS are separate
quotas); raise the provider's tier; or kill-switch the provider to stop wasting the wait. The
capability's own permits (`learning.ai.permits.*`) bound concurrency, not the daily quota.

### Egress down

Symptoms: `egress="proxy"` calls `TRANSIENT` or `TIMEOUT`, Gemini speech and speech to text unavailable
while DeepSeek, Yandex and the image sources work. Check [AI egress proxy](./ai-egress-proxy.md)
(tunnel unit, Squid on the Finnish host). Until it is back: set `LEARNING_AI_EGRESS_ENABLED=false` and restart, so that
proxied providers report `PROVIDER_NOT_CONFIGURED` at once and fall back, or switch the flags of the
affected capabilities off. A self-hosted speech route needs no egress.

### Speech to text and consent

Voice is never processed without the account's consent for the region that processes it (`GET/PUT /api/speech-consent`:
`RU` for a route in Russia, `ABROAD` for Gemini). An input whose consent was withdrawn, is outdated, or no longer
covers the region fails `UNAVAILABLE` with its audio deleted and **no provider is called**; a rise of
`mnema_stt_inputs_total{outcome="UNAVAILABLE"}` right after a consent-version change is expected. After a change of
`learning.speech.consent-version` every account sees the consent prompt again. An unrecognised or too-long clip is `NO_SPEECH` or `TOO_LONG`,
the learner's own. Audio is never kept after a result; a failure with the audio still present points to a stuck
sweeper (`SpeechInputSweeper` runs in every role).

## 6. Data retention

| Data | Kept | Where |
|---|---|---|
| Provider-call journal (no text, cost and outcome only) | 90 days (`learning.ai.call-retention`) | `app_learning.ai_provider_call` |
| Speech inputs (transcript and metadata; audio deleted at the result) | 15 minutes (`learning.speech.ttl`) | `speech_input` |
| Generation sessions | 30 days after last activity, then one day readable, then purged | `generation_session` and children |
| Speech clips cache | 180 days (`learning.ai.tts.cache-ttl`) | `speech_cache` |
| Licensed image search cache | 24 hours | `image_search_cache` |
| Answer text of a semantic check | until the presentation expires; the stored grade keeps short quotes | `study_assessment` |

## 7. Failure modes of the operations layer itself

| Symptom | Cause | Action |
|---|---|---|
| Capabilities `PROVIDER_NOT_CONFIGURED` on the api host only | `MNEMA_PROVIDER_CREDENTIALS=worker` not set there | set it; it is for `api` only |
| Service does not start: management exposure | `metrics` exposed without `MANAGEMENT_SERVER_PORT` and `MANAGEMENT_SERVER_ADDRESS` | set both or narrow `MNEMA_MANAGEMENT_EXPOSURE` |
| Answers wait the whole 20 s with `api` + `worker` | no worker running, or it has no grader (check the role) | start a worker; the answers fall to self-check meanwhile |
| Steps wait the sweep interval before starting | the wake listener is down | look for `wake_listener_disconnected`; the system is correct, only slower |

## 8. Eval gate before changing a model, route or prompt

Changing a model, a route order or a prompt version is gated by the golden eval, run by hand with the owner's keys
and never in CI. Corpus, thresholds, judges, and the owner-review sample are described in
[`contracts/generation/eval/README.md`](../../contracts/generation/eval/README.md); the answer-check cases are
[`contracts/study/assessment-golden/`](../../contracts/study/assessment-golden/README.md).

```sh
cd backend && MNEMA_AI_EVAL=live ./gradlew :services:learning:goldenEval
```

The run writes a JSON and a Markdown report to `backend/services/learning/build/reports/golden-eval/` and an
`owner-review.md` sample with checkboxes. Gate (the numbers of the issue #300): at least 90 % valid on the first try
and 98 % after repair, a repair rate of at most 10 %, no copy of a source longer than 8 words, and a judge acceptance
that does not fall against the previous report; any miss blocks the change. Acceptance judges' Cohen kappa is diagnostic only. The required answer-check gate is QWK >=0.6 against labelled answers at S1/S2/S3 and false-accept <=2%; missing live assessment evidence fails that machine gate. Proposed labels and the 40-item acceptance sample still need owner review, and judges must be calibrated with owner decisions.

The candidate `v2` prompt preserves `v1` and clarifies source-language quoting for CLOZE. It is not activated: `learning.ai.prompt.version` remains `v1` until both machine and human gates pass.

Keep the report with the change's evidence
and add the date to the drift log in the eval README. A route or a model that fails stays out of `application.properties`.
