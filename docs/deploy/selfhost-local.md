# Persistent local replacement runtime

Current revisions permit private personal use by one natural person on owned or
controlled devices. Shared or organizational use needs a
[separate written license](../../COMMERCIAL-LICENSING.md).

## Supported full-stack launcher — #220

The supported personal-development path is `scripts/mnema-local-full-stack.sh` plus
`compose.local-full-stack.yml`. It builds the production Angular image, the real
Identity and Learning images and the local media worker image, starts PostgreSQL 18
and MinIO with retained named volumes, and publishes only three loopback TLS
listeners (see [Full local stack with media](#full-local-stack-with-media)):

- `https://localhost:3443` — the URL to open; static production frontend and
  same-origin `/api` Learning traffic;
- `https://localhost:3444` — the separate OAuth/OIDC issuer required by the browser
  PKCE boundary. Identity and Learning themselves have no host HTTP ports;
- `https://storage.mnema.localhost:3445` — S3-compatible object storage for avatars
  and Learning media uploads.

The Learning-to-Identity call also uses TLS. Its private truststore contains only the
generated local CA; the launcher never enables the test-only plaintext transport.
The disposable `scripts/browser-identity` and `scripts/learning-security` harnesses
keep their own temporary processes, database and keys and do not consume this volume.

### Style Guide in the same local runtime

The default frontend configuration is `production`. To inspect the shared UI catalogue
with the current backend and retained data, run from the same checkout/state directory:

```bash
MNEMA_LOCAL_FRONTEND_CONFIGURATION=development ./scripts/mnema-local-full-stack.sh start
```

Open [Style Guide](https://localhost:3443/styleguide), substituting the web port retained
at bootstrap if it differs. Repeat the flag on later starts while needed; a normal
`start` restores the production frontend. Both modes use the same named volumes and
local security configuration. No data reset is needed. The flag affects only the local
frontend build and accepts `development` or `production`; production release images
still exclude the catalogue. Fixture-only dev-server and component rules:
[frontend styleguide](../frontend/styleguide.md#как-открыть).

### First start

Prerequisites are Docker Engine with the Compose plugin, Java/JDK 25 (`java` and
`keytool`), OpenSSL, Python 3 and curl. The local backend Dockerfile mirrors the
pinned release build/runtime stages without its optional BuildKit cache mount, so the
workflow also works with a Compose installation that has no buildx plugin. Run from
the repository root:

```bash
./scripts/mnema-local-full-stack.sh start
```

`start` invokes the bounded bootstrap automatically. Running `bootstrap` separately
is optional when you want to inspect/trust the CA before building images. Bootstrap
creates a random PostgreSQL password, random object-storage keys, RSA Identity
signing JWKSet, local CA, localhost/server and storage certificates and Learning
truststore under ignored
`.mnema/local-full-stack/`. Private files are owner-only and are reused on ordinary
starts. A missing, partial, permissive, mismatched or expiring set fails before
Compose is invoked; there is no HTTP or anonymous-signing fallback.

Import `.mnema/local-full-stack/local-ca.crt` into the current user's OS/browser
trust store, explicitly as a local development root, then open
`https://localhost:3443`. Browsers with a separate certificate store need the same
one-time import there. Do not trust the private key, reuse this CA outside Mnema, or
commit anything under `.mnema`. The launcher prints the exact CA path until curl sees
it as trusted.

Ports can be selected during the first bootstrap and are then retained with the
local security/database configuration:

```bash
MNEMA_LOCAL_WEB_PORT=4443 MNEMA_LOCAL_IDENTITY_PORT=4444 MNEMA_LOCAL_STORAGE_PORT=4445 \
  ./scripts/mnema-local-full-stack.sh bootstrap
```

The retained OAuth redirect and issuer then use those ports. The three ports must
differ; the object-storage port is part of every signed URL, so it is also retained.

### Verify, stop and restart

```bash
./scripts/mnema-local-full-stack.sh smoke
./scripts/mnema-local-full-stack.sh status
./scripts/mnema-local-full-stack.sh logs 100
./scripts/mnema-local-full-stack.sh stop
./scripts/mnema-local-full-stack.sh start
./scripts/mnema-local-full-stack.sh smoke
```

The smoke creates one private random local account, completes real S256 PKCE through
the HTTPS issuer, and creates/reloads a Deck and Capture through the frontend's
same-origin `/api`. Its owner-only credentials remain beside the other local state so
the second smoke proves restart persistence. It also publishes one retained native
material and `FREE_RESPONSE` exercise, completes a real scheduled attempt and observes
material progress. The smoke then restarts that material and runs replay plus
introduced-only practice, requiring both feedback-only modes to report
`canonicalEffects: false` and leave the restarted progress projection unchanged. Six
additional retained fixture Decks exercise `SELF_CHECK`, multi-blank `CLOZE` with a
server-issued first-letter hint, `MULTIPLE` `CHOICE`, `MATCH` with a durable wrong
pair check, `ORDER` and `CATEGORIZE` through real scheduled API attempts, exact retries and progress reads; every
issued presentation is checked for leaked answer keys, bindings, media titles or
transcripts. The smoke also requires both AI capabilities to report `DISABLED` and
direct publication of `ai-semantic` or speech-input exercises to fail with
`CAPABILITY_UNAVAILABLE`. Conservative self-check, choice, matching and categorization evidence may leave
progress in `LEARNING`. Anonymous
Study start still has to fail closed with `401`.

`stop` retains PostgreSQL, uploaded objects, accounts, content, JWK and certificates.
A clean data reset is destructive and requires the exact opt-in:

```bash
./scripts/mnema-local-full-stack.sh reset --confirm-delete-local-data
```

Issue #266 replaced the exercise mechanics without compatibility readers: Learning
migration `V21` refuses to start over a database that already contains exercises from an
earlier build. Run this reset once before the first start of a #266 build (only after
confirming the local data is disposable).

Issue #278 moved the backend to Java 25, Spring Boot 4.1, Spring Security 7 and Spring Session 4.
Flyway history and schema are unchanged, and no manual step is required: a browser session
created by an earlier build is ended at the authorization endpoint (the user is sent to sign in
again), and an unredeemed authorization code from an earlier session answers `invalid_grant`
instead of failing. Serialized OAuth2 rows from earlier builds are otherwise not guaranteed to
be readable, so clearing them once is optional hygiene (everyone signs in again); it is not a
full data reset:

```sql
-- psql against the Identity database
DELETE FROM app_identity.oauth2_authorization;
DELETE FROM app_identity.oauth2_authorization_consent;
DELETE FROM app_identity.spring_session; -- spring_session_attributes cascades
```

It deletes only this Compose project's containers, PostgreSQL and object-storage
volumes, media scratch files and the synthetic smoke-account state files; local
certificates and signing JWK remain. Local certificate rotation is
separate and preserves the database credentials, Identity signing key and smoke
account:

```bash
./scripts/mnema-local-full-stack.sh reset-certificates --confirm
```

Stop the stack first, remove the old CA from browser/OS trust, and trust the newly
printed CA path. The storage certificate is reissued from the same new CA. Neither
reset touches legacy v1 projects or hosted infrastructure. The same command repairs a
state directory whose certificates were issued without key identifiers (the launcher
reports this): strict TLS clients such as Python 3.13+ reject a leaf certificate
without an Authority Key Identifier, and OpenSSL 4 no longer adds one by default.

<!-- BEGIN full-local-stack-with-media -->
## Full local stack with media

One command brings up every service needed to test Mnema by hand, including uploaded
media. This is **local development tooling only**: it is not a production or staging
path, shares nothing with `k8s/`, and must not be copied into a deployment.

### Prerequisites

Everything listed under [First start](#first-start), plus:

- a Docker daemon that can bind-mount this checkout (Colima shares paths under your
  home directory; Docker Desktop shares it by default). The state directory must be an
  absolute path inside that shared area.
- the pinned official MinIO image `quay.io/minio/minio@sha256:14cea493...` in the
  local image store. MinIO no longer serves it from Docker Hub or Quay, so a fresh
  machine cannot pull it. The repository already pins the same digest for staging
  (`k8s/staging/data.yaml`), and
  [the image-environment evidence](../engineering/evidence/epic-74/verification/image-environment.md)
  describes how it was imported into the local cache. If it is missing, `start`
  fails at the image pull; restore it by that procedure rather than substituting an
  unverified mirror.

### Commands

```bash
./scripts/mnema-local-full-stack.sh start        # build, start, wait for health
./scripts/mnema-local-full-stack.sh smoke        # account/Study smoke, then media smoke
./scripts/mnema-local-full-stack.sh smoke-media  # avatar + media upload/processing only
./scripts/mnema-local-full-stack.sh status
./scripts/mnema-local-full-stack.sh logs 100
./scripts/mnema-local-full-stack.sh stop
./scripts/mnema-local-full-stack.sh reset --confirm-delete-local-data
```

The first `start` builds the backend images, the frontend and
`mnema-media-worker:local` from `backend/media-worker/Dockerfile` (later starts
reuse the Docker build cache; `start` also retires the Docker-socket `media-processor` of
older stacks with `--remove-orphans`). `MNEMA_LOCAL_PROJECT_NAME`, `MNEMA_LOCAL_STATE_DIR` and
`MNEMA_LOCAL_MEDIA_WORKER_IMAGE` override the Compose project name (default
`mnema-local-v2`), the state directory and the worker image tag; use them to run a
second isolated copy next to the default one.

### What runs

| Service | Role |
| --- | --- |
| `postgres` | PostgreSQL 18, named volume `local_postgres_data` |
| `minio` | Official MinIO, TLS on the storage port, named volume `local_object_data` |
| `minio-init` | One-shot, idempotent: creates `mnema-local-avatars` (versioned) and `mnema-local-media` |
| `identity-account` | Identity, writes avatars to MinIO |
| `learning` | Browser-facing Learning API; issues signed upload URLs and drains the media queue; no Docker access |
| `media-work-init` | One-shot: lays out the `local_media_work` volume like production (root-owned root, Learning's private `spool/`) |
| `media-runner` | **Development-only exception:** the production runner script in a container that holds the Docker socket; starts one throw-away container per media job |
| `media-worker-image` | Build-only (`--profile worker-image`): the worker image the runner starts per job |
| `frontend` | Production Angular build and local TLS proxy |

The worker image and the runner script are the ones production uses (`backend/media-worker/Dockerfile`,
`deploy/production/mnema-media-runner.py`; design in
[the worker README](../../backend/media-worker/README.md#media-runner-and-job-protocol-v1)); the
launcher builds the image before it brings the stack up.

Signed upload and playback URLs use `https://storage.mnema.localhost:<port>`. The name
resolves to MinIO inside Compose and to loopback in browsers, so the browser PUT works
without mixed content; the certificate for that name is signed by the same local CA
(trust it once, as above). MinIO allows CORS only from the local web origin.

`smoke-media` registers its own synthetic account (kept in an owner-only state file),
uploads a generated avatar PNG, a generated PNG and a generated MP3 through the real
API and signed URLs, waits for the runner to mark both media assets `READY`,
downloads the processed WebP and M4A variants and compares the original. A processing
failure ends the run with the asset state and a pointer to the Learning and `media-runner` logs.

### Media runner boundary (and the one development-only exception)

Production runs the media runner as a root host service and never mounts the Docker socket
into a container. A developer machine has no such service, so the local stack runs the **same
script** (`deploy/production/mnema-media-runner.py`) in the `media-runner` container, which
holds `/var/run/docker.sock`. That is host-root-equivalent on the Docker host (or inside its VM):
run this stack only on a machine you trust with a personal Docker daemon, exactly as with the
previous Docker-per-job gateway. Containment: only `media-runner` has the socket (Learning, the
backend images and every job container have neither the socket nor a Docker client); it has no
published port, a read-only root, only the capabilities it needs (`CHOWN`, `DAC_OVERRIDE`,
`FOWNER`, `FSETID`, `KILL`) and no credentials; each job runs in a fresh container with no network,
a read-only root, no capability, UID 10002 and two mounts of the `local_media_work` volume (subpaths of
the runner's scratch), and only the runner writes a verdict. Learning sees only the `spool/` subpath
of the volume. `reset` deletes the volume with the other local data.

### Reset semantics and state

`reset --confirm-delete-local-data` removes only the selected project's containers and
its `local_postgres_data`, `local_object_data` and `local_media_work` volumes
and the two smoke state files. The CA, signing JWK, truststore and credentials stay.
`stop`/`start` keep every volume; the bucket step is idempotent. State created before
object storage existed is upgraded in place by `start`/`bootstrap` without rotating
the signing key, database password or CA.
### AI budget plan (usage, #281)

Learning keeps an AI budget per account ([usage contract](../../contracts/usage/README.md);
`GET /api/usage` shows it). Until billing exists (#79) the plan comes from configuration and
every account is **Free** (a 50-credit bar that opens in weekly portions). The local owner gets
a paid plan the same way, through Spring configuration of the Learning process:

- every account on one plan, which is what a one-owner stack wants:
  `LEARNING_USAGE_ENTITLEMENTS_DEFAULT_PLAN=PRO` (`FREE`, `PLUS`, `PRO` or `MAX`);
- one account only, by the UUID that is the token subject:
  `SPRING_APPLICATION_JSON='{"learning.usage.entitlements.overrides.<accountUuid>":"PRO"}'`
  (a UUID is not expressible as an environment-variable name, hence the JSON property).

The full-stack launcher passes `MNEMA_LOCAL_AI_PLAN` (default `FREE`) to Learning as the default plan, so
`MNEMA_LOCAL_AI_PLAN=PRO scripts/mnema-local-full-stack.sh start` puts every local account on Pro. The per-account JSON
override needs a local edit of `x-learning-environment` in `compose.local-full-stack.yml`. Outside Compose set it in the environment of
`./gradlew :services:learning:bootRun`. A plan change takes effect on the next request: limits
change at once and the missing credits are granted on the next reservation. Since #301 an entitlement snapshot accepted by
`EntitlementInbox` (the billing and promo contract; there is no HTTP endpoint and no payments yet) overrides this configuration for
its owner while it is valid, so on a local stack the configuration above stays the way to pick a plan. `/plans` shows the
catalogue and the current plan and charges nothing; `learning.plans.max-teaser.enabled=true` shows Max as «В работе». Credits and counters
live in the retained PostgreSQL volume and are cleared by `reset`.

### Promo codes, A/B and the promo popup (#302)

Promo codes need no extra configuration locally. An administrator creates a code with `POST /api/admin/promo-codes` (the caller's token and an
`admin` account in Identity; there is no UI yet) and redeems it in `/plans` or the profile with a verified email. The attempt limits
are per account and per address hash (`learning.promo.attempts-per-hour` and `ip-attempts-per-hour`); on the local stack every browser reaches
Learning through one proxy address, so raise the address limit (default 20 an hour) for repeated runs with `MNEMA_PROMO_IP_ATTEMPTS_PER_HOUR`. Set
`MNEMA_PROMO_HASH_SECRET` to make the address and stored code hashes comparable across restarts (without it a random secret is drawn per process, with a
WARN, and issued codes do not survive a restart; production, `APP_ENV=prod`, without a secret of 32 or more characters switches promo codes off with an ERROR log, the rest keeps running) and
`MNEMA_EXPERIMENT_SECRET` to switch A/B assignment on (without it everybody is `control`). The promo popup is off unless
`MNEMA_PROMO_POPUP_ENABLED=true` with `MNEMA_PROMO_POPUP_ID`, `_TITLE` and `_BODY` (and optionally `_CTA`, `_CODE`, `_COOLDOWN`). See the
runtime policy index for every key.

### Colima clock

**Symptom.** Sporadic HTTP 500 (for example on `POST /api/media-assets/{id}/upload/finalize`)
or a flaky `AuthoringServiceIntegrationTest`, with a `DataIntegrityViolationException` on a
`updated_at >= created_at` style CHECK. The Colima VM clock, and therefore PostgreSQL's
`CURRENT_TIMESTAMP`, stepped backwards by roughly 100-150 ms every ~10 s because two time
syncs fought each other. The application now keeps such row timestamps monotonic, so a small
backwards step no longer fails requests, but the VM clock should still be fixed at the source.

**Fix.** `lima-guestagent` must be the only time source in the Colima VM. Disable the second one:

```bash
colima ssh -- sudo systemctl disable --now systemd-timesyncd
```

**Verify.** `colima ssh -- journalctl | grep SyncTime` must not show recurring corrections of
100 ms or more.

**Rollback.** `colima ssh -- sudo systemctl enable --now systemd-timesyncd`.

`colima delete` recreates the VM, so repeat the fix after every recreation.

<!-- END full-local-stack-with-media -->

`docker-compose.yml` remains the backend-only maintenance runtime from #143. Use it
only when frontend/HTTPS login is intentionally unnecessary. PostgreSQL 18 mounts
`/var/lib/postgresql` according to the
[official image contract](https://hub.docker.com/_/postgres); readiness ordering uses
Compose [health dependencies](https://docs.docker.com/compose/how-tos/startup-order/),
and private files are mounted through Compose
[secrets](https://docs.docker.com/compose/how-tos/use-secrets/).

## AI provider layer (local)

The AI layer ([architecture §9](../architecture/ai-generation-platform.md), issue #282) is **off by default** and CI never
calls a provider: tests run on a deterministic Stub or on recorded fixtures served from a loopback `HttpServer`.
Nothing here is committed with a value; the names below come from the launcher's process environment or from the
owner's private `.env` that Compose already reads (`MNEMA_LOCAL_OAUTH_ENV_FILE` for a worktree). They reach only the
browser-facing Learning service, never the media processor or the frontend.

| Name | Meaning |
|---|---|
| `MNEMA_AI_DEEPSEEK_API_KEY` | Direct DeepSeek (primary text route) |
| `MNEMA_AI_GIGACHAT_AUTH_KEY` | GigaChat authorization key (fallback; exchanged for a short-lived token; needs the Russian CA in the Java truststore) |
| `MNEMA_AI_OPENROUTER_API_KEY` | OpenRouter: the fallback of every DeepSeek route (the same DeepSeek models, owner decision 2026-10-04); optional, without it the routes use DeepSeek alone |
| `MNEMA_AI_EGRESS_PROXY_URL` / `MNEMA_AI_EGRESS_PROXY_USER` / `MNEMA_AI_EGRESS_PROXY_PASSWORD` | Optional stateless HTTP CONNECT proxy (`http://host:port`, user and password together) for providers unreachable from Russia; never needed locally or in CI. See [AI egress proxy](../operations/ai-egress-proxy.md) |
| `MNEMA_AI_PIXABAY_API_KEY` | Pixabay key for licensed image search ([API terms](https://pixabay.com/api/docs/): answers cached 24 h, images downloaded to our storage, never hot-linked). Without it Pixabay is not asked |
| `MNEMA_AI_OPENVERSE_CLIENT_ID` / `MNEMA_AI_OPENVERSE_CLIENT_SECRET` | Openverse client credentials (both or neither). Called directly by default (verified from a Russian network 2026-10-05); if a network meets its Cloudflare challenge, set `learning.ai.providers.openverse.egress=proxy` (needs the egress proxy). Wikimedia Commons needs no key and is always asked |
| `MNEMA_AI_GOOGLE_API_KEY` | Google Gemini key for speech synthesis ([Gemini speech generation](https://ai.google.dev/gemini-api/docs/speech-generation), model `gemini-3.8-flash-tts`) and for speech to text ([Gemini audio transcription](https://ai.google.dev/gemini-api/docs/transcribe), models `gemini-3.5-flash-lite` and `gemini-3.5-transcribe`; the key's own plan decides the rate limits: the dedicated model answered 429 to a burst of sequential calls in the spike). The API is not reachable from Russia, so the provider defaults to the egress proxy (`learning.ai.providers.google.egress=proxy`): without an active `MNEMA_AI_EGRESS_PROXY_URL` it is not configured. Not verified live yet |
| `MNEMA_AI_TTS_API_KEY` / `MNEMA_AI_YANDEX_FOLDER_ID` | Yandex SpeechKit v1 (Russian only): API key and the Yandex Cloud folder id, both or neither. It is called only when it is listed in `learning.ai.routes.tts` or `tts-ru` (not in the default route: the live comparison with Gemini decides). Not verified live yet |
| `MNEMA_AI_YANDEX_SEARCH_API_KEY` / `MNEMA_AI_YANDEX_FOLDER_ID` | Yandex Search API v2 (веб-исследование и «Источники», #299): API-ключ сервисного аккаунта (scope `yc.search-api.execute`, роль `search-api.webSearch.user` на каталоге) и id каталога Yandex Cloud, оба или ни одного; `MNEMA_AI_YANDEX_FOLDER_ID` общий с SpeechKit. Поиск вызывается напрямую (не через egress proxy) с российского хоста; платный (≈0,49 ₽ за запрос, 5 кредитов). Не проверено live |
| `MNEMA_AI_PERPLEXITY_API_KEY` | Perplexity Search API: **fallback, выключен по умолчанию**. Он вызывается, только если владелец внёс его в `learning.ai.routes.search` (`yandex,perplexity`); идёт через egress proxy (`learning.ai.providers.perplexity.egress=proxy`): без активного `MNEMA_AI_EGRESS_PROXY_URL` его нет. Один запрос на пять поисковых запросов. Не проверено live |
| `MNEMA_AI_USER_KEY_SECRET` | At least 16 random characters: HMAC secret of the opaque per-account user id sent to providers. Required for a real provider; generate once and keep it (`openssl rand -hex 32`) |
| `LEARNING_FEATURES_AI_GENERATION_ENABLED` | `true` turns `aiGeneration` on (default `false`) |
| `LEARNING_FEATURES_AI_ASSESSMENT_ENABLED` | `true` turns `aiAssessment` on (default `false`): the AI check of free explanations (`ai-semantic` exercises). It needs a usable `assess` route: a DeepSeek key with the user-key secret, or `LEARNING_AI_PROVIDER=stub`. Without it publishing such an exercise is `409 CAPABILITY_UNAVAILABLE` |
| `LEARNING_FEATURES_TEXT_TO_SPEECH_ENABLED` | `true` turns `textToSpeech` on (default `false`): the clips of `::audio` blocks, «Озвучить заново другим голосом» and the voice change of an exercise. It needs a callable route entry (the Google key with the egress proxy, or the SpeechKit key with the folder id) or `LEARNING_AI_PROVIDER=stub` (a deterministic tone, no network). Clips are cached by text, language, voice and take for 180 days: the same clip again is free and makes no provider call. The media worker image (`mnema-media-worker:local`) must be rebuilt: it now accepts a WAV source for clips the server made |
| `LEARNING_FEATURES_SPEECH_TO_TEXT_ENABLED` | `true` turns `speechToText` on (default `false`): dictation into a text field and spoken Study answers (`contracts/speech`; the transcript is shown for editing and is never sent or graded automatically). It needs a callable route entry (the Google key with the egress proxy, or `MNEMA_AI_STT_BASE_URL`) or `LEARNING_AI_PROVIDER=stub` (a fixed sentence, no network; the harness scripts the text with the `X-Stub-Transcript` request header, percent-encoded). Voice needs the account's consent (`GET/PUT /api/speech-consent`: `RU` for a self-hosted route, `ABROAD` for Gemini). The reverse proxy must let a 2 MiB body through to `/api/speech-inputs` (nginx: `client_max_body_size 2m`). The default route is Gemini 3.5 Flash-Lite with `gemini-3.5-transcribe` behind it (the numbers are in the guide's «Speech input»); it runs through the egress proxy like every Gemini call. Not verified against a live self-hosted container: none exists yet |
| `MNEMA_AI_STT_BASE_URL` / `MNEMA_AI_STT_API_KEY` | Optional self-hosted speech-to-text container that speaks the OpenAI audio API (`POST {base}/v1/audio/transcriptions`, multipart, `verbose_json`; speaches / faster-whisper-server, vLLM for Qwen3-ASR, or a GigaAM wrapper). `http` is accepted for a private host only (a compose service name such as `http://stt:8000`, a 10/8, 172.16/12 or 192.168/16 address, loopback); anything else needs `https`. The key, if the container asks for one, is sent as `Authorization: Bearer`. Nothing routes to it until `learning.ai.routes.stt-ru` / `stt` list a `selfhost:<model>` entry (see the runtime policy index). **No container is part of the compose stack**: adding one (Python, torch/ONNX, model downloads, a 2 vCPU / 2.5 GB budget) needs the owner's approval |
| `LEARNING_FEATURES_IMAGE_SEARCH_ENABLED` | `true` turns `imageSearch` on (default `false`): the stock images of materials (`::image mode=search`), «Найти похожее», «Заменить» and the choice among found images. It needs a callable source (Wikimedia Commons always is, Pixabay with its key, Openverse with credentials) or `LEARNING_AI_PROVIDER=stub` (a deterministic search and a drawn PNG, no network); only commercially usable licenses are kept (CC0, public domain, CC BY, CC BY-SA, Pixabay Content License) and the author, source and license go into the image caption on approval. Verified live on 2026-10-05 with the owner's keys: Pixabay, Openverse and Wikimedia Commons (search and safe download) |
| `LEARNING_FEATURES_WEB_SEARCH_ENABLED` | `true` включает `webSearch` (по умолчанию `false`): «Проверять факты» в composer, шаг `RESEARCH` и раздел «Источники» материала. Нужна вызываемая запись `learning.ai.routes.search` (ключ и folder Yandex; Perplexity — ключ и proxy) или `LEARNING_AI_PROVIDER=stub` (детерминированная выдача `example.org`, без сети). Каждый запрос поиска платный; потолки 2/6/3 запроса на материал (Средний/Подробный/Авто) и `learning.ai.research.max-requests`. Без него сессия с «Проверять факты» на effort выше Кратко — `409 CAPABILITY_UNAVAILABLE` |
| `LEARNING_AI_PROVIDER` | `stub` selects the deterministic Stub for every text route (no key needed); empty uses the real routes |
| `MNEMA_LOCAL_AI_SPLIT` | `true` adds the optional local worker overlay; default `false` keeps `all`. Set it on every launcher command for that stack. Provider credentials are cleared from the API and only the worker receives them. See the AI runbook |
| `MNEMA_RUNTIME_ROLES` | Optional (`api`, `worker` or `all`; default `all`). Generation steps, speech inputs and the grading of answers are executed by the worker half only; the local stack runs both in one process, so nothing needs to be set. A process with `api` creates and reads sessions but never claims a step |
| `MNEMA_PROVIDER_CREDENTIALS` | Optional (`local` default, or `worker`): for an `api` process whose worker holds the keys, so that `GET /api/capabilities` is reported from the shared configuration without a key on that host. Refused with any other role. See the [AI runbook](../operations/ai-runbook.md) |
| `MANAGEMENT_SERVER_PORT` / `MANAGEMENT_SERVER_ADDRESS` / `MNEMA_MANAGEMENT_EXPOSURE` | Optional, off by default: actuator `metrics` on a separate private port (for example `18083`, `127.0.0.1`, `health,info,metrics`); never on the public API. The three go together or the service refuses to start. `python3 scripts/ai-ops/metrics_snapshot.py --url http://127.0.0.1:18083/actuator` prints a table; metrics and thresholds are in the runbook |

**Enable it locally.** Put the names above in the private `.env`, then restart with the launcher. With a DeepSeek key,
the user-key secret and the flag, `GET /api/capabilities` reports `aiGeneration: {available: true}`; without a key it
reports `PROVIDER_NOT_CONFIGURED`, with the flag off `DISABLED`, and `TEMPORARILY_UNAVAILABLE` while the circuit of every
route entry is open or the global daily budget is spent. To try the plumbing without a provider set
`LEARNING_AI_PROVIDER=stub` and the flag. Each provider call is journaled without text in `app_learning.ai_provider_call`
(90 days) and logged as `ai_call provider=... model=... capability=... outcome=... latency_ms=... in_hit=... in_miss=...
out=... cost_micros=...`; metrics are `mnema_ai_calls_total`, `mnema_ai_call_seconds` and `mnema_ai_cost_micros_total` (the whole list, the private management port that serves them and the thresholds are in the [AI runbook](../operations/ai-runbook.md)).
Every property is listed in the [runtime policy index](../engineering/runtime-policy-index.md).

**Opt-in checks.** Gradle does not treat the environment as a test input, so `cleanTest` forces the rerun. Both skip
themselves in CI because their variables are absent. Run from the repository root:

```bash
# Offline eval on the Stub (no key, no network); report in backend/services/learning/build/reports/ai-eval/report.{json,md}
cd backend && MNEMA_AI_EVAL=stub ./gradlew :services:learning:cleanTest :services:learning:test --tests '*AiEvalRunner*'

# The same eval on the real route; spends a few cents, needs MNEMA_AI_DEEPSEEK_API_KEY in the environment
cd backend && MNEMA_AI_EVAL=live ./gradlew :services:learning:cleanTest :services:learning:test --tests '*AiEvalRunner*'

# Live smoke test of the DeepSeek adapter (three tiny calls: plain, streamed, JSON); the key is read from the environment
cd backend && MNEMA_AI_LIVE=true ./gradlew :services:learning:cleanTest :services:learning:test --tests 'app.mnema.learning.ai.eval.LiveProviderTest'

# Live speech to text (#298): transcribes one clip through Gemini (called directly: a workstation outside Russia needs no proxy) and/or the self-hosted container;
# prints latency, seconds and cost, never the key or the text. MNEMA_STT_LIVE_WAV is an ogg/mp4/webm/mp3 clip of at most 2 MiB (MNEMA_STT_LIVE_MIME if it is not ogg)
cd backend && MNEMA_AI_LIVE=true MNEMA_STT_LIVE_WAV=/path/to/clip.ogg ./gradlew :services:learning:cleanTest :services:learning:test --tests '*SttLiveTest*'
# The spike behind the default route (stdlib Python, 16 known-text clips, latency, error rate and cost per model; the key is read from the environment)
python3 scripts/ai-spikes/stt_gemini_spike.py --samples <dir of <model>-<lang>-<voice>.wav> --out /tmp/stt-spike

# Live generation: the real worker path creates a session from «20 глаголов движения» on DeepSeek, expects REVIEW within six
# minutes, a valid native-v1 revision and the ledger debit with the measured cost (a few cents; needs the key)
cd backend && MNEMA_AI_LIVE=true ./gradlew :services:learning:cleanTest :services:learning:test --tests '*GenerationLiveProviderTest*'

# Golden eval (issue #300): the ~300 fixtures of contracts/generation/eval through the real pipeline pieces, scored by two
# judges of other model families via OpenRouter; stub is offline and proves only the plumbing
cd backend && MNEMA_AI_EVAL=stub ./gradlew :services:learning:goldenEval
# Live: the production text route plus the judges; costs about $2 to $3 (stops itself at MNEMA_GOLDEN_BUDGET_MICROS, default $2.80);
# needs MNEMA_AI_DEEPSEEK_API_KEY and MNEMA_AI_OPENROUTER_API_KEY in the environment. Report in build/reports/golden-eval/
cd backend && MNEMA_AI_EVAL=live ./gradlew :services:learning:goldenEval
```

The eval renders the prompt of every MBM valid fixture as a material task, compiles the answer with the MBM compiler,
repairs once and reports validity pass rate, repair rate, p50/p95 latency and cost; neither test prints a key or a
prompt. The live variants need the key exported in the shell that runs Gradle (for example `export
MNEMA_AI_DEEPSEEK_API_KEY=...` from your private environment, not on the command line).

The golden eval is not part of `check` or `quality`: `goldenEval` is its own Gradle task, and the offline contract of the
corpus (`GoldenCorpusTest`, `GoldenEvalSmokeTest`) is what CI runs. Its report (`report.json`, `report.md`: validity on the
first try and after repair, repair rate, latency p50/p95, cost per item and per accepted item, judge acceptance and
agreement, thresholds with pass or fail, the boundaries of the corpus) holds identifiers and numbers only; `owner-review.md`
is the 40-item sample with generated texts for the owner's acceptance and stays out of the repository. Corpus, rubric,
thresholds and what it does not cover: [`contracts/generation/eval`](../../contracts/generation/eval/README.md).

## Historical v1 self-host reference

The old local and public launchers were removed from this checkout in #146. Their
matching source, Compose files and runbooks remain available in the
[`v1-apache-final`](https://github.com/MattoYuzuru/Mnema/tree/v1-apache-final)
tag and Git history. They are not supported by the replacement runtime.


## Google, Yandex and GitHub login

The local launcher reads the checkout's optional `.env` through Docker Compose,
not by executing it as shell code. For a worktree, set `MNEMA_LOCAL_OAUTH_ENV_FILE`
to the absolute path of the owner's existing file. A missing default `.env` keeps
password login usable; an explicitly selected unreadable file fails before Compose.
Keep the file private and out of Git. Only these values go to Identity:

- `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`
- `YANDEX_CLIENT_ID`, `YANDEX_CLIENT_SECRET`
- `GH_CLIENT_ID`, `GH_CLIENT_SECRET`

Compose maps them to `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_*` settings.
No provider credentials are included in frontend configuration or other services.
The UI enables only providers returned by Identity's public `/api/accounts/providers`.
A missing credential pair leaves that provider unavailable; availability is not proof
that credentials or provider-console settings are valid.

For the default Identity port, register these exact redirect URIs in the existing
provider applications (retain separately used production addresses):

| Provider | Local redirect URI |
|---|---|
| Google | `https://localhost:3444/login/oauth2/code/google` |
| Yandex | `https://localhost:3444/login/oauth2/code/yandex` |
| GitHub | `https://localhost:3444/login/oauth2/code/github` |

Use the retained, trusted local CA. Never disable TLS verification to make browser
login work. With another Identity port, change the registered URI accordingly.
The launcher's Java truststore contains the installed JDK's public CA bundle plus
the retained local CA. Bootstrap upgrades an older local-only truststore atomically
without rotating the CA, signing keys or database credentials. This is necessary
for verified outbound HTTPS to providers as well as the local storage proxy;
see Java's [keytool import commands](https://docs.oracle.com/en/java/javase/21/docs/specs/man/keytool.html).
The launcher includes the truststore content digest in Compose configuration so a
later `start` recreates its Java consumers, including after a separate `bootstrap`.
After changing configuration, rebuild/restart Identity and the frontend with the
launcher. A real check must reach the provider, return to Mnema, show the authenticated
account and load its decks; a redirect alone is not successful login. Cancelling at
the provider must leave Mnema signed out with a retry action. Existing accounts are
identified by provider plus subject; matching email never automatically links accounts.

Provider settings: [Google clients](https://console.cloud.google.com/auth/clients),
[Yandex applications](https://oauth.yandex.ru/),
[GitHub OAuth applications](https://github.com/settings/developers).
