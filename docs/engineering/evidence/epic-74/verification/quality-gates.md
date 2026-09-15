# Quality gates, evidence, and cleanup

Inspected workflow: `.github/workflows/pull-request.yaml` at
`33a71f814185a16e922923e034518e25baeadbb8`. No gate was run while preparing this
strategy.

## Environment prerequisites

- JDK 21 (local Temurin 21.0.11 is available).
- Docker with PostgreSQL 18 image for Learning/Identity Testcontainers and Docker
  for nginx, PostgreSQL 16→18 recovery, and purge-rehearsal integration.
- Node **22.23.2**, npm, and Chrome for the frontend gate. Local default Node is
  26.3.0, but an exact task runtime is available at
  `/tmp/mnema-143-resume/node22/node-v22.23.2-darwin-arm64/bin` (Node 22.23.2,
  npm 10.9.8). Because `/tmp` is disposable, verify it again immediately before use.
- Use only synthetic/disposable databases, containers, ports, and content. Never run
  these commands against production or a shared namespace.

## Backend gate

From the repository root, the CI-equivalent backend gate is:

```bash
cd backend
./gradlew quality
```

On this Docker 29/Colima workstation, the repository guide records this compatible
invocation. The registry prefix is workstation-specific and must not override an
approved registry elsewhere:

```bash
cd backend
JAVA_TOOL_OPTIONS='-Dapi.version=1.44' \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
TESTCONTAINERS_HUB_IMAGE_NAME_PREFIX=docker.io/ \
./gradlew quality
```

`quality` compiles all six backend modules, runs all Gradle `Test` tasks, produces
per-module and aggregate JaCoCo reports, and enforces
`backend/coverage-baseline.json`. There is no dedicated backend lint/static-analysis
task in the current root gate; report it as not configured, not passed.

### #74 real-PostgreSQL and proposed no-skip audit

New Learning integration tests must extend
`app.mnema.learning.support.PostgresIntegrationTest`, whose Testcontainers annotation
is fail-closed (`disabledWithoutDocker = false`) and whose PostgreSQL image is 18.
There is no checked-in container-skip verifier under `backend/scripts/` at this
revision (`check_coverage.py` is the only file). After the targeted or full Gradle
test run, use this proposed review command until an owning implementation slice adds
an approved repository check:

```bash
cd backend
./gradlew :services:learning:test :services:identity-account:test
! rg -n '@Testcontainers\(disabledWithoutDocker\s*=\s*true\)|Assumptions\.assume|assumeTrue\(' \
  services/learning/src/test services/identity-account/src/test
python3 - <<'PY'
from pathlib import Path
import xml.etree.ElementTree as ET

roots = [
    Path('services/learning/build/test-results/test'),
    Path('services/identity-account/build/test-results/test'),
]
reports = [p for root in roots for p in root.glob('TEST-*.xml')]
assert reports, 'no JUnit XML reports found'
tests = failures = errors = skipped = 0
for report in reports:
    suite = ET.parse(report).getroot()
    tests += int(suite.attrib.get('tests', 0))
    failures += int(suite.attrib.get('failures', 0))
    errors += int(suite.attrib.get('errors', 0))
    skipped += int(suite.attrib.get('skipped', 0))
assert tests > 0, 'no tests executed'
assert failures == 0 and errors == 0 and skipped == 0, (
    f'tests={tests} failures={failures} errors={errors} skipped={skipped}'
)
print(f'tests={tests} failures=0 errors=0 skipped=0')
PY
```

The full repository still contains `disabledWithoutDocker = true` in legacy
core/media/import/AI tests. Do not broaden the static check to those modules and then
misclassify inherited v1 debt as a new #74 failure; the aggregate gate and coverage
still run them as currently configured. New #74 tests may not copy that pattern.

## Frontend gate

With exact Node 22.23.2 active:

```bash
export PATH=/tmp/mnema-143-resume/node22/node-v22.23.2-darwin-arm64/bin:$PATH
node --version
test "$(node --version)" = v22.23.2
cd frontend
npm ci
npm run lint
npm run test
npm run build
```

The current test command is Karma/ChromeHeadless. There is no configured frontend
coverage threshold or E2E suite. Lint/test/build are necessary but do not establish
browser flow, visual, mobile, performance, or screen-reader acceptance.

## Repository policy and integration commands

The `frontend-quality` workflow runs all of the following from the repository root,
after frontend install/lint/test/build. Preserve this order for local parity:

```bash
python3 scripts/verify_github_actions_pins.py
python3 scripts/verify_security_automation_policy.py
python3 scripts/verify_artifact_security_policy.py
python3 scripts/verify_production_image_pins.py
python3 -m unittest discover -s scripts/tests -p 'test_verify_*.py' -v

./scripts/test-frontend-release-contract.sh
./scripts/test-browser-security-headers.sh
./scripts/test-render-release-manifest.sh
./scripts/test-capture-release-diff.sh
./scripts/test-detect-kubernetes-secret-drift.sh
./scripts/test-secret-snapshot-binding.sh
./scripts/test-kubernetes-bootstrap-secret-values.sh
./scripts/test-environment-secret-separation.sh
./scripts/test-production-deploy-safety.sh
./scripts/test-kubernetes-live-release-binding.sh
./scripts/test-staging-plan-preview.sh
./scripts/test-kubernetes-secret-consumer-reconciliation.sh
./scripts/test-kubernetes-secret-rollback.sh
./scripts/test-production-telemetry-boundary.sh
./scripts/test-release-security-contract.sh
./scripts/test-deployment-contract.sh
./scripts/test-create-staging-kubeconfig.sh
./scripts/test-staging-host-firewall.sh
./scripts/test-staging-tls-boundary.sh
./scripts/test-apply-staging-bootstrap.sh

python3 -m unittest discover -s scripts/smoke/tests -v
./scripts/test-backup-contract.sh
./scripts/test-create-recovery-kubeconfig.sh
PYTHONPATH=scripts/backup python3 -m unittest discover -s scripts/backup/tests -v
./scripts/test-backup-integration.sh
./scripts/test-purge-rehearsal-integration.sh
```

The last two commands are real Docker integration tests: PostgreSQL 16→18 backup
recovery and disposable no-snapshot purge rehearsal. The nginx browser-header test
also requires Docker. A successful purge rehearsal proves policy automation against
synthetic resources only; it is not permission or evidence for #147.

The separate GitHub `dependency-review` job rejects new High/Critical vulnerable
runtime, development, or unknown-scope dependencies. It is implemented by a GitHub
Action and has no exact local equivalent in this repository. Any editor/security
dependency change requires owner approval first and then this hosted check.

## Candidate-specific checks beyond the current workflow

The current workflow does not yet prove Epic #74. Add and execute, without replacing
the full gate:

- real PostgreSQL storage property/integration/concurrency/crash tests and R74-S
  measurement harness;
- real Identity-to-Learning HTTP authentication/revocation tests;
- shared AST/API success/error/golden fixtures consumed by Java and Angular;
- renderer adversarial corpus and direct-ID ownership matrix;
- connected authoring E2E with the real API, hard reload, multiple tabs, failure,
  retry, conflict, and logout/login;
- visual/responsive/keyboard/reduced-motion/manual screen-reader evidence;
- long-document/large-deck browser, API, query-plan, row/TOAST/index/WAL measurements;
- static route/source/schema/bundle scan proving direct replacement and #75/#76/#146/
  #147 exclusions.

## Fixture isolation and cleanup

- Give each run a unique UUID seed, database/container name, and loopback port.
- Use synthetic multilingual content only. Never copy owner/private content into
  screenshots, traces, logs, or benchmark dumps.
- Stop containers and delete only exact run-owned volumes/temp directories after raw
  metrics and sanitized reports are retained. A failed run preserves the seed and
  evidence long enough to reproduce; cleanup must not target repository/workspace roots.
- Browser profiles are disposable and account-scoped. Clear tokens/drafts after the
  run; never include bearer values in HAR/console artifacts.
- Fault injection stops if it reaches a non-disposable/shared endpoint, if target SHA
  changes, or if fixture ownership cannot be proven.

## Evidence layout for a candidate

Store a compact index under a SHA-specific subdirectory, for example:

```text
verification/<candidate-sha>/
  index.md
  commands.tsv
  junit-summary.txt
  contracts/
  browser/
  accessibility/
  performance/
  security/
```

`commands.tsv` records command, start/end UTC, exit status, environment, and artifact
path; it does not duplicate full routine logs. `index.md` maps every matrix ID to
`PASS`, `FAIL`, `BLOCKED`, or `NOT RUN`, with residual risk. Screenshots alone never
prove persistence, round-trip, security, or screen-reader behavior.
