---
artifact:
  id: agent-runbook
  type: runbook
  title: "Mnema agent runbook: commands and local-machine setup"
  status: current
  created_at: "2026-10-01"
  updated_at: "2026-10-10"
  owners: ["project-owner"]
---

# Agent runbook: how to run things

Deterministic commands for coding agents. Facts were checked on 2026-10-01 on the owner's
macOS workstation (Colima as the Docker runtime); the table at the end says which were
re-run then and which are carried over from earlier sessions. CI uses the repository
toolchains, not whatever is first on `PATH`: release claims never rest on a workstation JDK/Node.
Policy (what must pass before a push or merge) lives in root [`AGENTS.md`](../../AGENTS.md);
module map and versions in the [repository guide](./repository-guide.md).

## Workstation setup (owner's Mac)

The default `java` and `node` are newer than the repository baseline. Set these per shell;
the defaults are not the baseline.

```bash
# Backend: JDK 25 (default java here is 26 and is not the baseline)
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"   # run.py and the local launcher call plain `java`/`keytool`

# Testcontainers on Colima: host socket for discovery, VM socket for mounts, no Ryuk reaper
export DOCKER_HOST=unix:///Users/yuzuru/.colima/default/docker.sock
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
export TESTCONTAINERS_RYUK_DISABLED=true

# Node must be exactly 24.x for the browser harness and the frontend gate (CI/images use 24.21.0)
NODE24=/opt/homebrew/opt/node@24/bin/node
export PATH="/opt/homebrew/opt/node@24/bin:$PATH"   # npm ci/lint/test/build use this Node, not the newer default
```

`TESTCONTAINERS_RYUK_DISABLED` is an owner-supplied workaround, not documented elsewhere in
`docs/`; without Ryuk, leaked test containers are not reaped, so check `docker ps -a` after
an aborted run. PostgreSQL-backed tests fail closed: a Docker/socket failure is an environment
problem, never permission to accept skipped tests.

## Quality gate (what CI-equivalent means)

```bash
# From the repository root after the per-shell setup above:
(cd backend && ./gradlew clean quality)          # compile, tests, JaCoCo, coverage-baseline.json floors
(cd frontend && npm ci && npm run lint && npm run test && npm run build) # Vitest/jsdom: no browser needed
python3 scripts/verify_docs.py                 # links, anchors, doc statuses
python3 -m unittest discover -s scripts/tests -p 'test_*.py'   # ~35 s
```

`quality` has no separate backend lint/static-analysis task today (compile + tests +
coverage). Java compilation is the configured static check (`-Xlint:all -Werror`). CI additionally runs `scripts/verify_github_actions_pins.py`,
`verify_security_automation_policy.py`, `verify_artifact_security_policy.py`,
`verify_production_image_pins.py`, the maintained `scripts/test-*.sh` contracts and the
real cross-service checks; the authoritative list is the `frontend-quality` job in
[`.github/workflows/pull-request.yaml`](../../.github/workflows/pull-request.yaml).
Cross-service security/cancellation harness (needs built boot jars):

```bash
./backend/gradlew -p backend :services:identity-account:bootJar :services:learning:bootJar
python3 scripts/learning-security/run.py
```

### Docker Hub pulls in CI

GitHub-hosted runners share IPs, so anonymous Docker Hub pulls hit `toomanyrequests` or
`auth.docker.io` timeouts (PR #411, 2026-10-09). Every CI job that pulls Docker Hub images
(both quality jobs in PR and Main CI, and the release image build) first runs
[`.github/actions/docker-hub-mirror`](../../.github/actions/docker-hub-mirror/action.yml): it
sets the runner daemon's `registry-mirrors` to `https://mirror.gcr.io` and reloads it. That
covers `docker run`/`docker build` and Testcontainers, and the daemon falls back to Docker Hub
when the image isn't cached. The release job's `docker-container` BuildKit builder
does not read the daemon config, so `setup-buildx-action` passes its own
`[registry."docker.io"] mirrors = ["mirror.gcr.io"]`. Image references and `@sha256` pins
are unchanged; content is digest-verified, so the mirror cannot substitute an image.
Not used: a Docker Hub token (needs an owner secret, and a failing `auth.docker.io` still blocks
it) and Testcontainers `hub.image.name.prefix` (rewrites names with no Docker Hub fallback).
Sources: [Google cached Docker Hub images](https://docs.cloud.google.com/artifact-registry/docs/pull-cached-dockerhub-images),
[dockerd reloadable options](https://docs.docker.com/reference/cli/dockerd/),
[BuildKit registry mirror](https://docs.docker.com/build/buildkit/configure/#registry-mirror).
Local Colima runs are unaffected.

## Production release after merge

A runtime change merged to `main` is released by Main CI after one `prod` approval; a
docs-only merge is not. Full protocol and administrator install commands:
[production delivery](../operations/production-delivery.md#agent-protocol).

```bash
gh run list --workflow deploy.yaml --branch main --limit 1 --json databaseId,headSha,status
gh run watch <id> --exit-status        # background; do not poll
env_id=$(gh api repos/MattoYuzuru/Mnema/environments/prod --jq .id)
gh api -X POST repos/MattoYuzuru/Mnema/actions/runs/<id>/pending_deployments \
  -F "environment_ids[]=$env_id" -f state=approved -f comment='<owner task>'
python3 -m unittest discover -s scripts/smoke/tests -v    # smoke script unit tests (local)
python3 scripts/smoke/vps_public_smoke.py --sha <deployed-sha>   # same check CI runs after deploy
```

## Browser harness (real HTTPS Chrome against real services)

```bash
cd frontend && npm run build && cd ..
python3 scripts/browser-identity/run.py --dist frontend/dist/mnema-frontend \
  --node "$NODE24" --authoring --media --mechanics
```

- Needs: JDK 25 first on `PATH` (the harness starts `java -jar`), built `bootJar`s, Chrome, cached `postgres:18`, and for
  `--media` the cached pinned MinIO image plus `mnema-media-worker:local`. It builds and pulls nothing.
- Flags: `--authoring` (Deck/Capture/draft/publish/Browse + one Study interaction), `--media`
  (needs `--authoring`), `--mechanics` (needs both; default deadline 600 s). Scope and
  limits: [harness README](../../scripts/browser-identity/README.md).
- Chrome always runs muted (`--mute-audio`); audio is verified through media-element state.
  `--mechanics` uses Chrome's synthetic microphone, not a real device.
- Screenshots/evidence go to a `mnema-browser-evidence-*` temp directory. `--keep-on-failure`
  additionally keeps a mode-0700 fixture directory with disposable secrets, cookies and logs;
  inspect locally, never publish it, and delete that exact directory afterwards.

## Style Guide

From the checkout serving the current local runtime:

```bash
MNEMA_LOCAL_FRONTEND_CONFIGURATION=development scripts/mnema-local-full-stack.sh start
```

Open [Style Guide](https://localhost:3443/styleguide), using the retained web port if
it differs from 3443. This rebuilds only the local frontend configuration and retains
the same stack/data. Repeat the flag on subsequent starts while the catalogue is needed;
a normal `start` returns to the production build, which excludes `/styleguide`.
For the fixture-only dev-server option, component rules and production exclusion check,
see [frontend styleguide](../frontend/styleguide.md#как-открыть).

## Owner console browser fixture

After building both boot jars and the production frontend, the console is exercised
against disposable PostgreSQL and the real bot bridge source with synthetic tickets:

```bash
python3 scripts/browser-identity/run.py --dist frontend/dist/mnema-frontend \
  --node "$NODE24" --admin --admin-bot-source /absolute/path/to/Mnema-Telegram-Bot-with-admin-bridge
```

The explicit bot checkout must contain `mnema_bot/admin_server.py`. The fixture
starts only its own loopback process/SQLite and configures one real disposable
Identity owner; it never loads bot `.env`, starts a Telegram poller, sends a real
message or restarts the retained local stack. Reply evidence stops at durable
outbox queuing; actual delivery is covered by bot fixtures, not claimed as a real
Telegram smoke. See the [console delivery record](./evidence/admin-console-2026-10/README.md) for the first
snapshot's results and the [console architecture](../architecture/admin-console.md) for admin-host rollout and
unavailable financial sources. The fixture signs in through the learner web client, which the console now refuses
on every route; it needs a second origin for `mnema-admin-web` before it runs green again.

### Open the console on the persistent local stack

The console is off by default and needs the dedicated `mnema-admin-web` client, hence its own browser origin
`https://admin.localhost:<web port>` (same container, port and certificate as `localhost`; browsers resolve
`*.localhost` to loopback). In the shell that runs `scripts/mnema-local-full-stack.sh start` for the retained project:

```bash
# Your account UUID: Identity directory, or SELECT account_id FROM app_identity.account WHERE email = '...'
export MNEMA_ADMIN_OWNER_ACCOUNT_ID=<owner account uuid>          # lowercase canonical UUID
export MNEMA_IDENTITY_ADMIN_ORIGIN=https://admin.localhost:3443  # your retained web port
scripts/mnema-local-full-stack.sh reset-certificates --confirm    # once: the certificate gains DNS:admin.localhost
scripts/mnema-local-full-stack.sh start                           # recreates Identity and Learning with the owner settings
```

Open `https://admin.localhost:3443/manage`, sign in as that account. Remember `MNEMA_LOCAL_STATE_DIR` and
`MNEMA_LOCAL_OAUTH_ENV_FILE` for a worktree restart; unset both variables and restart to turn the console off.
Support stays "unavailable" locally unless `MNEMA_ADMIN_SUPPORT_ENDPOINT`/`_SECRET` and
`MNEMA_ADMIN_SUPPORT_ALLOW_LOOPBACK_HTTP=true` point at a loopback bridge. Never `reset` the data volumes for this.

## Persistent local stack

```bash
scripts/mnema-local-full-stack.sh bootstrap|start|status|logs [N]|smoke|smoke-media|stop
scripts/mnema-local-full-stack.sh reset --confirm-delete-local-data   # destructive
scripts/mnema-local-full-stack.sh reset-certificates --confirm
```

- Never `reset` the default project `mnema-local-v2` without the owner's explicit OK: it deletes
  that project's database and object-storage volumes.
- Isolated check beside the default stack: set `MNEMA_LOCAL_PROJECT_NAME` (lowercase compose
  name), `MNEMA_LOCAL_STATE_DIR` (absolute path **under `/Users`**, the only tree Colima shares),
  and different `MNEMA_LOCAL_WEB_PORT` / `_IDENTITY_PORT` / `_STORAGE_PORT` (1024–65535,
  all distinct; fixed at first bootstrap). Reset only that isolated project afterwards.
- `start` needs the pinned MinIO image digest `quay.io/minio/minio@sha256:14cea493…` in the
  **local image cache**; it cannot be pulled any more. If missing, follow the
  [image-environment evidence](./evidence/epic-74/verification/image-environment.md), do not
  substitute a mirror.
- Python 3.13+ (3.14 here) enforces strict X.509: leaf certificates need SKI/AKI. The launcher
  issues them; state created earlier is repaired by `reset-certificates --confirm`.
- Colima clock: `lima-guestagent` must be the only VM time source, otherwise PostgreSQL
  `CURRENT_TIMESTAMP` steps backwards and `updated_at >= created_at` CHECKs fail sporadically.
  Fix `colima ssh -- sudo systemctl disable --now systemd-timesyncd`, repeat after every
  `colima delete`. Runbook: [Colima clock](../deploy/selfhost-local.md#colima-clock).
- Full user-facing description: [persistent local runtime](../deploy/selfhost-local.md).

## What was verified on 2026-10-01

| Item | State |
|---|---|
| `JAVA_HOME` path exists, reports JDK 25.0.4.1 (Temurin-compatible OpenJDK); default `java` is 26.0.2 | verified |
| `./gradlew clean quality` with JDK 25 / Gradle 9.8.0 / Spring Boot 4.1.1 | earlier passing evidence; full gate not re-run for the 2026-10-01 workstation inventory |
| Colima socket present, `docker version` answers (29.5.2) with that `DOCKER_HOST` | verified |
| Testcontainers env above: `:services:identity-account:test --tests '*AccountTransferIntegrationTest'` ran 4 tests, 0 skipped, passed | verified |
| `/opt/homebrew/opt/node@24/bin/node` is v24.21.0 (npm 11.19.0); default `node` is 26.9 | verified |
| `run.py --help` lists `--authoring --media --mechanics --keep-on-failure --node`; `--mute-audio` and temp-dir behaviour read from source | verified (source) |
| `verify_docs.py` passes; `unittest discover scripts/tests -p 'test_*.py'` 188 tests OK | verified |
| MinIO digest `14cea493…` present in the local Docker image cache | verified |
| `systemd-timesyncd` inactive and disabled in the Colima VM now | verified |
| Launcher subcommands, env overrides and SKI/AKI issuance | verified (source) |
| Full `npm ci/lint/test/build`, a real `run.py` run, `start`/`smoke` | earlier evidence; not re-run for the 2026-10-01 workstation inventory |
| `TESTCONTAINERS_RYUK_DISABLED` necessity; Python strict-X.509 failure mode | owner-reported / doc-stated, not reproduced |
