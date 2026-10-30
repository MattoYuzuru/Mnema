---
artifact:
  id: agent-runbook
  type: runbook
  title: "Mnema agent runbook: commands and local-machine setup"
  status: current
  created_at: "2026-10-01"
  updated_at: "2026-10-15"
  owners: ["project-owner"]
---

# Agent runbook: how to run things

Deterministic commands for coding agents on the owner's macOS workstation (Colima as the Docker
runtime). CI uses the repository toolchains, not whatever is first on `PATH`: release claims never
rest on a workstation JDK/Node. Exact versions are owned by the build files
(`backend/build.gradle.kts`, `frontend/package.json`, workflows); the
[repository guide](./repository-guide.md#platform-baseline) lists them.

Policy (what must pass before a push or merge) lives in root [`AGENTS.md`](../../AGENTS.md);
module map and versions in the [repository guide](./repository-guide.md).

## Workstation setup (owner's Mac)

The default `java` and `node` are newer than the repository baseline. Set these per shell;
the defaults are not the baseline.

```bash
# Backend: the repository toolchain JDK (25); the default java on this Mac is newer and is not the baseline
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"   # run.py and the local launcher call plain `java`/`keytool`

# Testcontainers on Colima: host socket for discovery, VM socket for mounts, no Ryuk reaper
export DOCKER_HOST=unix:///Users/yuzuru/.colima/default/docker.sock
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
export TESTCONTAINERS_RYUK_DISABLED=true

# Node must be 24.x for the browser harness and the frontend gate (the CI/image version is in the workflows)
NODE24=/opt/homebrew/opt/node@24/bin/node
export PATH="/opt/homebrew/opt/node@24/bin:$PATH"   # npm ci/lint/test/build use this Node, not the newer default
```

`TESTCONTAINERS_RYUK_DISABLED` is an owner-supplied workaround, not documented elsewhere in
`docs/`; without Ryuk, leaked test containers are not reaped, so check `docker ps -a` after
an aborted run. PostgreSQL-backed tests fail closed: a Docker/socket failure is an environment
problem, never permission to accept skipped tests.

## Quality gate (what CI-equivalent means)

Use the [scope rule in AGENTS.md](../../AGENTS.md#quality-gate) before choosing commands. Read-only
answers do not need a product gate. Prose-only changes use `python3 scripts/verify_docs.py`,
`git diff --check` and a review of affected claims/instructions; they do not create a full-gate record.
The candidate workflow below applies to code, tests, contracts, configuration and runtime changes.
Required hosted checks remain mandatory for every PR; this exception changes no workflow or protection.

[`scripts/checks.py`](../../scripts/checks.py) is the single catalogue of checks: each entry states its
guarantee, local commands, owning paths and the hosted job lines that repeat it.
`scripts/tests/test_verify_checks.py` fails when a quality job in either workflow runs a line no entry owns,
or lacks one an entry declares, and when a `scripts/tests` suite is run by no job.

```bash
# From the repository root after the per-shell setup above, on a committed candidate:
python3 scripts/checks.py plan                    # full plan, local+hosted placement of every check
python3 scripts/checks.py plan --affected         # checks owning the paths changed since origin/main
python3 scripts/checks.py run                     # the full gate on HEAD
python3 scripts/checks.py verify                  # before code push: reuse a valid unchanged candidate record
python3 scripts/checks.py clean                   # remove that candidate's validation worktree
python3 scripts/checks.py drift                   # catalogue vs hosted jobs, without running anything
```

`run` resolves the candidate commit (`--candidate REV`, default `HEAD`), creates a detached worktree of it
under `~/.local/state/agent-dev/mnema-candidates/` (`MNEMA_CANDIDATE_DIR`) and runs the checks there in order,
stopping at the first failure. Uncommitted edits are not part of a candidate, and edits by you or another
session in the working checkout cannot change it, so a long backend run is not lost to a concurrent edit.
Each command goes through `agent-dev run` with the shared `heavy` lock, JDK 25 / Node 24 selection, the
Docker endpoint and a per-check timeout, so its receipt is source-bound to the validation worktree; without
`agent-dev` (or with `MNEMA_CHECK_RUNNER=direct`) commands run directly. The run record
(`<sha12>.json`, next to the worktree) binds commit, tree and plan digest. `verify` rejects a missing or
partial (`--affected`) plan, a failed or incomplete run, a changed validation worktree and any stale receipt;
the fix is a new run, never a reused result. `run` and `verify` continue with the candidate's own
`scripts/checks.py`, so an edited or different catalogue in the working checkout cannot define the gate, and
a `--affected` run is recorded separately (`<sha12>.affected.json`) without replacing a full-gate record.
Each check is one `agent-dev run`: heavy checks hold the shared `heavy` lock for all of their commands and
wait for it (`MNEMA_CHECK_LOCK_TIMEOUT`, default 6 hours) instead of failing; a lock that never frees is
recorded as `blocked`, not `failed`. A rerun of the same candidate reuses its clean worktree, including its
build directories (the backend gate starts with `clean`); `clean` removes the worktree, records and logs.
`MNEMA_CHECK_RUNNER=direct` (or no `agent-dev`) runs commands without toolchain selection or receipts; such
a record is bound only to the commit and the clean worktree, and says so (`"runner": "direct"`).

`--affected` selects checks by owned paths with both sides of renames and deletions; an unknown path, a
missing merge base or an empty change set selects the full plan. It is for feedback during work only: the
final gate is always the full plan. `release-images` is hosted only (it needs a fresh Trivy database) and
runs in PR Quality when the pull request changes a release-image input (`release_scope.py --changed`);
Main CI repeats the scan on the exact release digests. A docs- or test-only pull request records the skip in
its summary. Local Docker checks use the fixed `:verified` tags the suites expect; the `heavy` lock held per
check keeps another cooperating session from retagging them mid-check.

`quality` has no separate backend lint/static-analysis task today (compile + tests +
coverage). Java compilation is the configured static check (`-Xlint:all -Werror`).
Hosted CI keeps its parallel topology (eight backend shards, security, runtime, frontend checks/tests and
the release-image matrix); locally `backend-quality` runs the same discovery, tests and floors as
`./gradlew clean quality`.

### Test throughput and resource envelope

`clean quality` remains the complete local backend gate. Its default is **two isolated test
JVMs**, each with a 512 MiB heap and serial JUnit execution. Every JVM owns its PostgreSQL
and HTTP fixtures; methods sharing clocks, queues, counters or mocks do not run concurrently.
`-PtestForks=1` selects the serial comparison; only 1 or 2 are accepted. Gradle's daemon heap
is separate (1500 MiB). These limits are deliberate; do not derive fork counts from the Mac's
host CPU count, because Colima and the retained development stacks share fewer resources.
Coordinate heavy checks through the shared `workstation-dev` resource `heavy`, including
frontend tests. A busy lock is a queue, never permission to stop the current owner.

The ordinary `test` task still discovers all tests. Both CI workflows run eight class shards
(`-PtestShard=1/8` through `8/8`), with at most two JVMs each on separate `ubuntu-latest`
runners. Modules run concurrently with `--parallel --max-workers=2`: Gradle limits the total
test processes across all `Test` tasks, not two per concurrent module.
Freshly discovered classes, including nonstandard names such as `*EvalRunner`, are assigned
longest-estimated-first to the lightest shard within each module; nested tests stay with their outer class.
`backend/test-durations.tsv` contains only performance estimates, with a 5-second default for
new classes. Obsolete entries never select tests. Equal estimates use SHA-256 of the UTF-8
outer class name as a deterministic tie-break, with the lowest shard index winning equal loads.
No manual class allowlist
can silently leave a new test out. The `testInventory` task uses the existing JUnit Platform
launcher to discover the expected classes without executing tests or initializing fixtures.
Packing/merging evidence requires exact agreement with that inventory. Unexpected skips fail;
intentional provider/eval/media opt-ins live in `backend/test-opt-ins.json`.

Each shard uploads only JaCoCo execution data and bounded suite metadata (7 days), through
the safe artifact action. Console/XML output and runtime fixtures are not uploaded. The
aggregate verifies all eight shard identities, source/revision, checksums, discovered classes
and uniqueness, then reads exactly those execution files with `-PcoverageShards=8`.
The existing 90% per-service line floors apply to the combined report. Ordinary local reports
read only `test.exec`, so old downloaded shards cannot inflate a later local gate.

Security/cancellation and runtime/media contracts run in separate jobs. Security explicitly
pulls its cached `postgres:18` input before the cancellation harness; runtime installs JDK 25
for the compiled readiness-probe contract. Neither relies on a previous Gradle test job's
side effects. `backend-quality` remains the required aggregate: failure, cancellation or skipping of a dependency fails it.
`frontend-quality` likewise requires both `frontend-tests` and `frontend-checks`; the latter
retains lint/build/styleguide/release/browser-response/delivery contracts and automation checks.
Frontend specs keep isolated environments and the same coverage thresholds, with two workers
locally or four in CI (`MNEMA_TEST_WORKERS`, accepted range 1–4); the CI test process and
inherited workers each cap V8 heap at 1536 MiB.
The main test fan-out is twelve concurrent jobs (eight backend shards, security, runtime,
frontend tests, frontend checks), below the standard Free account concurrency limit of 20;
two simultaneous PR gates and other workflows share that account quota and can cause queueing.
Aggregation is a later phase.

The 15-minute job timeout is a growth/failure ceiling, not the five-minute performance goal.
Every shard prints suite totals and its ten slowest classes. When time grows, inspect those
measurements and the dataset/fixture phases before increasing the fixed worker/shard ceilings.
Refresh timing estimates from a complete green set of shard manifests when imbalance returns:
sum `seconds` for nested suites by outer class, round up to milliseconds (minimum 1), and retain
the source run/head in the TSV comments. The profile must stay an estimate, never an inventory.
Changes to it invalidate shard evidence through the source digest; the aggregate independently
recomputes allocation from current discovery and the same versioned profile.
Do not accept fewer classes, new skips or lower coverage to make the time green. Compare a
serial and a two-JVM run on the same source and environment, record peak JVM/container memory,
and verify the union of hosted shards before claiming a speedup. A hosted duration excludes
account queueing; report end-to-end waiting separately when it is material.

Large community preconditions use the test-only `BulkItemFixture`: strict native validation,
production codecs and path-copy page editing, reachable objects in bounded storage batches,
normal repository writes, durable root pins and PostgreSQL FK/trigger checks, with rollback.
It does not disable constraints, database durability or isolation. The scenarios and sizes from
[community architecture §§5, 7 and 12](../architecture/community-decks.md) remain unchanged:
1,000 materials, 200-edit slices, crash/retry/idempotency and bounded plan work. Authoring-command
coverage remains in `ItemServiceIntegrationTest`; real edits, forks and update commands remain
production operations. Historical operation timings in the Learning guide are not fixture
construction timings and are not rewritten as new measurements.

Catalogue scale measurements reset their feature tables before as well as after
each scenario; class order and a previous scheduler's rank generation cannot change the dataset.
Semantic-grading deadline tests advance an injected monotonic nanosecond source through the
provider call, asserting both that no repair starts after expiry and that an already expired
budget starts no call. Production still uses `System.nanoTime`, the elapsed-time source specified
by [Java 25](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/System.html#nanoTime()).
No deadline or query-performance threshold is relaxed.

References deciding the mechanism: [Gradle test processes](https://docs.gradle.org/current/userguide/java_testing.html#sec:test_execution),
[Gradle global test-process limit](https://docs.gradle.org/current/dsl/org.gradle.api.tasks.testing.Test.html#org.gradle.api.tasks.testing.Test:maxParallelForks),
[Spring parallel-test constraints](https://docs.spring.io/spring-framework/reference/testing/testcontext-framework/parallel-test-execution.html),
[JUnit discovery](https://docs.junit.org/current/api/org.junit.platform.launcher/org/junit/platform/launcher/core/LauncherFactory.html),
[GitHub runner resources](https://docs.github.com/en/actions/reference/runners/github-hosted-runners)
and [account concurrency](https://docs.github.com/en/actions/reference/limits).

### Keeping tests fast as the project grows

These rules apply to new tests and throughput changes, including work by agents:

- Choose the lightest layer that observes the required behaviour. Pure calculations and state
  transitions belong in unit tests; HTTP mapping/wiring can use a slice. Transactions, database
  constraints, snapshots, races and cross-service contracts still need their real boundary.
  Coverage percentage alone does not justify replacing those checks with mocks or snapshots.
- Separate dataset construction from the measured operation. Avoid thousands of individual
  authoring transactions solely to prepare a large precondition. Reuse the owning domain's
  bounded fixture builder where it fits; keep production encoding, repository semantics,
  constraints, durability and rollback. A new bulk shortcut needs parity/rollback evidence.
  The operation being tested must still run through production commands. Cache only pure,
  immutable calculations within the fixture, never mutable database state across tests.
- Keep ownership explicit: each test JVM owns its containers and static fixtures; methods remain
  serial. Reset shared feature tables before and after a scenario. Do not introduce shared
  reusable databases, fixed external ports or method-level parallelism to gain speed.
  For frontend tests, retain isolated environments and restore mocks/timers after use.
- Model deadlines with an injectable monotonic clock when elapsed time is the behaviour.
  For asynchronous integration work, await observable completion with a bounded timeout.
  Do not add arbitrary sleeps, ordering assumptions or retries that hide a failed assertion.
- New tests enter through normal JUnit/Vitest discovery, never a hand-maintained shard allowlist.
  The TSV affects scheduling only. Preserve scenario sizes, error paths, intentional skip policy,
  coverage floors and the required fail-closed aggregates. Never disable a useful slow test to
  meet the performance goal; investigate its setup and owning layer first.
- For a material increase in datasets or framework contexts, record targeted setup/operation
  timings and the full gate's before/after duration on comparable source/runtime. Inspect the
  slowest classes and shard imbalance, refresh estimates from a complete green run, then repeat
  the hosted measurement through the required aggregate. Treat a sustained increase beyond
  the roughly five-minute CI goal as an investigation trigger, not permission to raise timeouts.
  Keep the fixed JVM/worker/shard caps until new peak-memory and account-concurrency evidence
  justifies a reviewed change. Other PRs, cache eviction and dependency startup count towards
  the observed waiting time; report cold/warm runs and queueing explicitly.

### Docker Hub pulls in CI

GitHub-hosted runners share IPs, so anonymous Docker Hub pulls hit `toomanyrequests` or
`auth.docker.io` timeouts (PR #411, 2026-10-09). Every CI job that pulls Docker Hub images
(backend shards/security/runtime, frontend checks in PR and Main CI, and the release image build) first runs
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
  limits: [harness README](../../scripts/browser-identity/README.md); `run.py --help` is the exact list.
- AI scenarios (Stub provider only, never a real one; both need `--authoring`): `--generation` (composer, Workshop, approval, exercises,
  planner, selection edits, voice, images, research on the Stub) and `--assessment` (rubric editor and the learner's check of an
  explanation: waiting, «Оценить себя», result, self-check, dispute).
- Development aids, never a substitute for the full run (each runs the base flow and then one scenario): `--only-edits`, `--only-images`,
  `--only-speech`, `--only-voice`, `--only-research`, `--only-ask`, `--only-plan` (all need `--generation`; `--only-images` and
  `--only-speech` also need `--media`), `--only-plans` (paywall, goal question and public `/ai`; needs `--authoring`) and `--only-promo`
  (promo codes, A/B assignment and the promo popup; needs `--authoring`).
- `--community` (no `--authoring` needed): community decks end to end (publication, guest, copy, Study, a complaint, by-invitation access with a request,
  its withdrawal, an approval and an invitation link redeemed by a new account) on desktop and mobile viewports, about 50 s; turns the three
  `learning.community.*` kill switches on for that disposable run only (README, «Community decks»).
- `--updates` (same composition as `--community`, a scenario of its own): the update of a copy (author republishes, learner reviews on `/decks/{id}/update`, accepts the
  safe part, decides a conflict and a deletion, studies on, detaches) on desktop and mobile viewports, about 90 s (README, «Update of a copy»).
- `--catalog` (exclusive: the composition of `--community` plus the catalogue switch, the frontend launch flag opened for this loopback run, a console owner and a short rank
  schedule; it starts from an empty catalogue): the community catalogue end to end (shelves, search, filters, infinite scroll, honest counts, the author's page, the masthead entry,
  the editorial shelf and hiding, the guest limit, reduced motion, accessibility smoke) on desktop and mobile viewports, about 4 min (README, «Community catalogue»).
- `--recs`: recommendations end to end (own disposable catalogue, interest onboarding, «Для вас», telemetry, reset, mobile and accessibility)
  (README, «Community recommendations»).
- `--onboarding` (exclusive, signs up accounts of its own): the first-entry tour «Начало пути» end to end with the catalogue composition of `--catalog`
  and `learning.onboarding.enabled` on for the disposable run (README, «Onboarding tour»).
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
Telegram smoke. See the [console architecture](../architecture/admin-console.md) for admin-host rollout and
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
