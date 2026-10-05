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
`mnema-media-worker:local` from `backend/media-worker/Dockerfile.local` (later starts
reuse the Docker build cache). `MNEMA_LOCAL_PROJECT_NAME`, `MNEMA_LOCAL_STATE_DIR` and
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
| `learning` | Browser-facing Learning API; issues signed upload URLs, no Docker access |
| `media-processor` | A second Learning instance with media processing enabled and no published port |
| `frontend` | Production Angular build and local TLS proxy |

The `media-worker-image` Compose entry only builds the worker image
(`--profile worker-image`); the processor starts that image once per job.

Signed upload and playback URLs use `https://storage.mnema.localhost:<port>`. The name
resolves to MinIO inside Compose and to loopback in browsers, so the browser PUT works
without mixed content; the certificate for that name is signed by the same local CA
(trust it once, as above). MinIO allows CORS only from the local web origin.

`smoke-media` registers its own synthetic account (kept in an owner-only state file),
uploads a generated avatar PNG, a generated PNG and a generated MP3 through the real
API and signed URLs, waits for the processor to mark both media assets `READY`,
downloads the processed WebP and M4A variants and compares the original. A processing
failure ends the run with the asset state and a pointer to the processor logs.

### Docker socket trade-off

The existing worker gateway starts every FFmpeg job with `docker run` (no network,
read-only root, all capabilities dropped, 3 GiB / 2 CPUs, non-root UID). The processor
therefore needs a Docker client and the daemon socket, which is equivalent to root on
the Docker host (or inside its VM). Containment:

- only the `media-processor` service mounts `/var/run/docker.sock`, and only its image
  target (`learning-media-processor-runtime`) contains the `docker` client; the
  browser-facing `learning` service has neither;
- the processor has no published port and listens on its container loopback; its
  container runs as root with every capability dropped, a read-only root filesystem
  and `no-new-privileges`;
- its scratch directory is `.mnema/local-full-stack/media-processing/` (mode 700), mounted
  at the same absolute path so the daemon can bind it into worker containers;
  completed jobs are deleted.

Run this stack only on a machine you trust with a personal Docker daemon. A narrower
design (a Docker-API proxy or a dedicated rootless daemon) would need a new image and
was not adopted for a local-only convenience stack.

### Reset semantics and state

`reset --confirm-delete-local-data` removes only the selected project's containers and
its `local_postgres_data` and `local_object_data` volumes, the media scratch directory
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
change at once and the missing credits are granted on the next reservation. Credits and counters
live in the retained PostgreSQL volume and are cleared by `reset`.

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
| `MNEMA_AI_OPENROUTER_API_KEY` | OpenRouter (optional; no default route uses it) |
| `MNEMA_AI_EGRESS_PROXY_URL` / `MNEMA_AI_EGRESS_PROXY_USER` / `MNEMA_AI_EGRESS_PROXY_PASSWORD` | Optional stateless HTTP CONNECT proxy (`http://host:port`, user and password together) for providers unreachable from Russia; never needed locally or in CI. See [AI egress proxy](../operations/ai-egress-proxy.md) |
| `MNEMA_AI_PIXABAY_API_KEY` | Pixabay key for licensed image search ([API terms](https://pixabay.com/api/docs/): answers cached 24 h, images downloaded to our storage, never hot-linked). Without it Pixabay is not asked |
| `MNEMA_AI_OPENVERSE_CLIENT_ID` / `MNEMA_AI_OPENVERSE_CLIENT_SECRET` | Openverse client credentials (both or neither). From some networks its API answers a Cloudflare challenge, so Openverse defaults to the egress proxy (`learning.ai.providers.openverse.egress=proxy`): without an active `MNEMA_AI_EGRESS_PROXY_URL` it is simply not configured. Wikimedia Commons needs no key and is always asked |
| `MNEMA_AI_GOOGLE_API_KEY` | Google Gemini key for speech synthesis ([Gemini speech generation](https://ai.google.dev/gemini-api/docs/speech-generation), model `gemini-3.8-flash-tts`). The API is not reachable from Russia, so the provider defaults to the egress proxy (`learning.ai.providers.google.egress=proxy`): without an active `MNEMA_AI_EGRESS_PROXY_URL` it is not configured. Not verified live yet |
| `MNEMA_AI_TTS_API_KEY` / `MNEMA_AI_YANDEX_FOLDER_ID` | Yandex SpeechKit v1 (Russian only): API key and the Yandex Cloud folder id, both or neither. It is called only when it is listed in `learning.ai.routes.tts` or `tts-ru` (not in the default route: the live comparison with Gemini decides). Not verified live yet |
| `MNEMA_AI_YANDEX_SEARCH_API_KEY` / `MNEMA_AI_YANDEX_FOLDER_ID` | Yandex Search API v2 (веб-исследование и «Источники», #299): API-ключ сервисного аккаунта (scope `yc.search-api.execute`, роль `search-api.webSearch.user` на каталоге) и id каталога Yandex Cloud, оба или ни одного; `MNEMA_AI_YANDEX_FOLDER_ID` общий с SpeechKit. Поиск вызывается напрямую (не через egress proxy) с российского хоста; платный (≈0,49 ₽ за запрос, 5 кредитов). Не проверено live |
| `MNEMA_AI_PERPLEXITY_API_KEY` | Perplexity Search API: **fallback, выключен по умолчанию**. Он вызывается, только если владелец внёс его в `learning.ai.routes.search` (`yandex,perplexity`); идёт через egress proxy (`learning.ai.providers.perplexity.egress=proxy`): без активного `MNEMA_AI_EGRESS_PROXY_URL` его нет. Один запрос на пять поисковых запросов. Не проверено live |
| `MNEMA_AI_USER_KEY_SECRET` | At least 16 random characters: HMAC secret of the opaque per-account user id sent to providers. Required for a real provider; generate once and keep it (`openssl rand -hex 32`) |
| `LEARNING_FEATURES_AI_GENERATION_ENABLED` | `true` turns `aiGeneration` on (default `false`) |
| `LEARNING_FEATURES_AI_ASSESSMENT_ENABLED` | `true` turns `aiAssessment` on (default `false`): the AI check of free explanations (`ai-semantic` exercises). It needs a usable `assess` route: a DeepSeek key with the user-key secret, or `LEARNING_AI_PROVIDER=stub`. Without it publishing such an exercise is `409 CAPABILITY_UNAVAILABLE` |
| `LEARNING_FEATURES_TEXT_TO_SPEECH_ENABLED` | `true` turns `textToSpeech` on (default `false`): the clips of `::audio` blocks, «Озвучить заново другим голосом» and the voice change of an exercise. It needs a callable route entry (the Google key with the egress proxy, or the SpeechKit key with the folder id) or `LEARNING_AI_PROVIDER=stub` (a deterministic tone, no network). Clips are cached by text, language, voice and take for 180 days: the same clip again is free and makes no provider call. The media worker image (`mnema-media-worker:local`) must be rebuilt: it now accepts a WAV source for clips the server made |
| `LEARNING_FEATURES_IMAGE_SEARCH_ENABLED` | `true` turns `imageSearch` on (default `false`): the stock images of materials (`::image mode=search`), «Найти похожее», «Заменить» and the choice among found images. It needs a callable source (Wikimedia Commons always is, Pixabay with its key, Openverse with credentials and the proxy) or `LEARNING_AI_PROVIDER=stub` (a deterministic search and a drawn PNG, no network); only commercially usable licenses are kept (CC0, public domain, CC BY, CC BY-SA, Pixabay Content License) and the author, source and license go into the image caption on approval. Not verified against the live sources yet except Wikimedia (see the guide's «Image search») |
| `LEARNING_FEATURES_WEB_SEARCH_ENABLED` | `true` включает `webSearch` (по умолчанию `false`): «Проверять факты» в composer, шаг `RESEARCH` и раздел «Источники» материала. Нужна вызываемая запись `learning.ai.routes.search` (ключ и folder Yandex; Perplexity — ключ и proxy) или `LEARNING_AI_PROVIDER=stub` (детерминированная выдача `example.org`, без сети). Каждый запрос поиска платный; потолки 2/6/3 запроса на материал (Средний/Подробный/Авто) и `learning.ai.research.max-requests`. Без него сессия с «Проверять факты» на effort выше Кратко — `409 CAPABILITY_UNAVAILABLE` |
| `LEARNING_AI_PROVIDER` | `stub` selects the deterministic Stub for every text route (no key needed); empty uses the real routes |
| `MNEMA_RUNTIME_ROLES` | Optional (`api`, `worker` or `all`; default `all`). Generation steps are executed by the worker half only; the local stack runs both in one process, so nothing needs to be set. A process with `api` creates and reads sessions but never claims a step |

**Enable it locally.** Put the names above in the private `.env`, then restart with the launcher. With a DeepSeek key,
the user-key secret and the flag, `GET /api/capabilities` reports `aiGeneration: {available: true}`; without a key it
reports `PROVIDER_NOT_CONFIGURED`, with the flag off `DISABLED`, and `TEMPORARILY_UNAVAILABLE` while the circuit of every
route entry is open or the global daily budget is spent. To try the plumbing without a provider set
`LEARNING_AI_PROVIDER=stub` and the flag. Each provider call is journaled without text in `app_learning.ai_provider_call`
(90 days) and logged as `ai_call provider=... model=... capability=... outcome=... latency_ms=... in_hit=... in_miss=...
out=... cost_micros=...`; metrics are `mnema_ai_calls_total`, `mnema_ai_call_seconds` and `mnema_ai_cost_micros_total`.
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

# Live generation: the real worker path creates a session from «20 глаголов движения» on DeepSeek, expects REVIEW within six
# minutes, a valid native-v1 revision and the ledger debit with the measured cost (a few cents; needs the key)
cd backend && MNEMA_AI_LIVE=true ./gradlew :services:learning:cleanTest :services:learning:test --tests '*GenerationLiveProviderTest*'
```

The eval renders the prompt of every MBM valid fixture as a material task, compiles the answer with the MBM compiler,
repairs once and reports validity pass rate, repair rate, p50/p95 latency and cost; neither test prints a key or a
prompt. The live variants need the key exported in the shell that runs Gradle (for example `export
MNEMA_AI_DEEPSEEK_API_KEY=...` from your private environment, not on the command line).

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
