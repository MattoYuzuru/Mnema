---
artifact:
  id: agent-runbook
  type: runbook
  title: "Mnema agent runbook: commands and local-machine setup"
  status: current
  created_at: "2026-10-01"
  updated_at: "2026-10-01"
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
# Backend: JDK 21 (default java here is 26 and is not the baseline)
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"   # run.py and the local launcher call plain `java`/`keytool`

# Testcontainers on Colima: host socket for discovery, VM socket for mounts, no Ryuk reaper
export DOCKER_HOST=unix:///Users/yuzuru/.colima/default/docker.sock
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
export TESTCONTAINERS_RYUK_DISABLED=true

# Node must be exactly 22.x for the browser harness (CI/images use 22.23.2)
NODE22=/opt/homebrew/opt/node@22/bin/node
```

`TESTCONTAINERS_RYUK_DISABLED` is an owner-supplied workaround, not documented elsewhere in
`docs/`; without Ryuk, leaked test containers are not reaped, so check `docker ps -a` after
an aborted run. PostgreSQL-backed tests fail closed: a Docker/socket failure is an environment
problem, never permission to accept skipped tests.

## Quality gate (what CI-equivalent means)

```bash
cd backend && ./gradlew clean quality          # compile, tests, JaCoCo, coverage-baseline.json floors
cd frontend && npm ci && npm run lint && npm run test && npm run build   # tests need Chrome
python3 scripts/verify_docs.py                 # links, anchors, doc statuses
python3 -m unittest discover -s scripts/tests -p 'test_*.py'   # ~35 s
```

`quality` has no separate backend lint/static-analysis task today (compile + tests +
coverage). CI additionally runs `scripts/verify_github_actions_pins.py`,
`verify_security_automation_policy.py`, `verify_artifact_security_policy.py`,
`verify_production_image_pins.py`, the maintained `scripts/test-*.sh` contracts and the
real cross-service checks; the authoritative list is the `frontend-quality` job in
[`.github/workflows/pull-request.yaml`](../../.github/workflows/pull-request.yaml).
Cross-service security/cancellation harness (needs built boot jars):

```bash
./backend/gradlew -p backend :services:identity-account:bootJar :services:learning:bootJar
python3 scripts/learning-security/run.py
```

## Browser harness (real HTTPS Chrome against real services)

```bash
cd frontend && npm run build && cd ..
python3 scripts/browser-identity/run.py --dist frontend/dist/mnema-frontend \
  --node "$NODE22" --authoring --media --mechanics
```

- Needs: JDK 21 first on `PATH` (the harness starts `java -jar`), built `bootJar`s, Chrome, cached `postgres:18`, and for
  `--media` the cached pinned MinIO image plus `mnema-media-worker:local`. It builds and pulls nothing.
- Flags: `--authoring` (Deck/Capture/draft/publish/Browse + one Study interaction), `--media`
  (needs `--authoring`), `--mechanics` (needs both; default deadline 600 s). Scope and
  limits: [harness README](../../scripts/browser-identity/README.md).
- Chrome always runs muted (`--mute-audio`); audio is verified through media-element state.
  `--mechanics` uses Chrome's synthetic microphone, not a real device.
- Screenshots/evidence go to a `mnema-browser-evidence-*` temp directory. `--keep-on-failure`
  additionally keeps a mode-0700 fixture directory with disposable secrets, cookies and logs;
  inspect locally, never publish it, and delete that exact directory afterwards.

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
| `JAVA_HOME` path exists, reports JDK 21.0.12; default `java` is 26.0.2 | verified |
| `./gradlew quality --dry-run` under JDK 21 resolves the task graph | verified |
| Colima socket present, `docker version` answers (29.5.2) with that `DOCKER_HOST` | verified |
| Testcontainers env above: `:services:identity-account:test --tests '*AccountTransferIntegrationTest'` ran 4 tests, 0 skipped, passed | verified |
| `/opt/homebrew/opt/node@22/bin/node` is v22.23.2; default `node` is 26.9 | verified |
| `run.py --help` lists `--authoring --media --mechanics --keep-on-failure --node`; `--mute-audio` and temp-dir behaviour read from source | verified (source) |
| `verify_docs.py` passes; `unittest discover scripts/tests -p 'test_*.py'` 188 tests OK | verified |
| MinIO digest `14cea493…` present in the local Docker image cache | verified |
| `systemd-timesyncd` inactive and disabled in the Colima VM now | verified |
| Launcher subcommands, env overrides and SKI/AKI issuance | verified (source) |
| Full `clean quality`, `npm ci/lint/test/build`, a real `run.py` run, `start`/`smoke` | not re-run here (proven in earlier sessions; slow or touch the shared default stack) |
| `TESTCONTAINERS_RYUK_DISABLED` necessity; Python strict-X.509 failure mode | owner-reported / doc-stated, not reproduced |
