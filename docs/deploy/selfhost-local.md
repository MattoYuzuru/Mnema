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

Prerequisites are Docker Engine with the Compose plugin, Java/JDK 21 (`java` and
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
`canonicalEffects: false` and leave the restarted progress projection unchanged. Four
additional retained fixture Decks exercise `SELF_CHECK`, multi-blank `CLOZE` with a
server-issued first-letter hint, `MULTIPLE` `CHOICE` and `MATCH` with a durable wrong
pair check through real scheduled API attempts, exact retries and progress reads; every
issued presentation is checked for leaked answer keys, bindings, media titles or
transcripts. The smoke also requires both AI capabilities to report `DISABLED` and
direct publication of `ai-semantic` or speech-input exercises to fail with
`CAPABILITY_UNAVAILABLE`. Conservative self-check/choice/matching evidence may leave
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
<!-- END full-local-stack-with-media -->

`docker-compose.yml` remains the backend-only maintenance runtime from #143. Use it
only when frontend/HTTPS login is intentionally unnecessary. PostgreSQL 18 mounts
`/var/lib/postgresql` according to the
[official image contract](https://hub.docker.com/_/postgres); readiness ordering uses
Compose [health dependencies](https://docs.docker.com/compose/how-tos/startup-order/),
and private files are mounted through Compose
[secrets](https://docs.docker.com/compose/how-tos/use-secrets/).

## Historical v1 self-host reference

The old local and public launchers were removed from this checkout in #146. Their
matching source, Compose files and runbooks remain available in the
[`v1-apache-final`](https://github.com/MattoYuzuru/Mnema/tree/v1-apache-final)
tag and Git history. They are not supported by the replacement runtime.
