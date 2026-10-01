---
artifact:
  id: repository-guide
  type: navigator
  title: "Mnema repository guide"
  status: current
  created_at: "2026-08-15"
  updated_at: "2026-09-28"
  owners: ["project-owner"]
  evidence_revision: "1879d9ae0cadde67bf8a0ccc74fbccb53f2acee5"
---

# Repository guide

This guide describes the checkout after Epics #74–#76. Root [`AGENTS.md`](../../AGENTS.md)
is normative; [docs/README.md](../README.md) owns documentation status/navigation.

## First read

1. [`AGENTS.md`](../../AGENTS.md).
2. [System overview](../system-overview.md).
3. The current guide for the owning runtime and its nearby tests.
4. [Local-only delivery](../operations/local-development-delivery.md) before any
   delivery decision.
5. For Study, use the [implemented contract below](#epic-75-study-contract) and
   [integrated acceptance](./evidence/epic-75/verification/integrated-main-2026-09-24.md).

## Platform baseline

| Platform | Exact repository baseline | Source |
|---|---:|---|
| Java | toolchain 21 | `backend/build.gradle.kts`, CI setup-java |
| Spring Boot | 3.5.16 | `backend/settings.gradle.kts` |
| Kotlin | 2.4.20 | `backend/settings.gradle.kts` |
| Gradle | 8.14.5 | `backend/gradle/wrapper/gradle-wrapper.properties` |
| Angular | core 22.1.5; CLI/build 22.1.7 | `frontend/package.json` |
| TypeScript | 6.0.3 | `frontend/package.json` |
| Node | 22.23.2 in CI/images | workflows and `frontend/Dockerfile` |
| PostgreSQL | 18 in replacement compose/tests | `docker-compose.yml`, test fixtures |

The workstation JDK/Node may be newer; release claims use repository/CI toolchains,
not whichever executable happens to be first on `PATH`.

## Repository map

```text
Mnema/
├── backend/
│   ├── build.gradle.kts                 aggregate quality and coverage
│   └── services/
│       ├── identity-account/            current account/OAuth/OIDC runtime
│       └── learning/                    current content/authoring/Study/media runtime
├── frontend/src/app/
│   ├── app.routes.ts                    current route source of truth
│   ├── home-page.component.ts           current public landing
│   ├── content/                         native document/editor/renderer
│   ├── features/authoring/              Capture, Draft, editor and Browse
│   ├── features/own-decks/              canonical private Deck UI
│   ├── features/study/                  canonical deck-scoped Study UI
│   └── core/, shared/                  current shell and translation helpers
├── contracts/                           shared native/content/deck/item fixtures
├── scripts/
│   ├── browser-identity/                real local HTTPS browser/E2E harness
│   ├── local-full-stack/                persistent local API smoke
│   ├── learning-security/               real Identity↔Learning harness
│   ├── backup/, smoke/, purge/          deterministic policy/integration tools
│   └── tests/                           repository policy tests
├── docs/                                canonical navigator and evidence
├── design/prototype/                    historical design evidence
├── k8s/, deploy/                        paused/restoration operational sources
├── docker-compose.yml                   replacement backend maintenance runtime
├── compose.local-full-stack.yml         persistent local HTTPS product runtime
└── .github/workflows/                   protected quality and dormant operations
```

`settings.gradle.kts` compiles only Identity & Account and Learning. Old service
source/migrations are available through `v1-apache-final` and Git history.

## Current runtime contracts

### Identity & Account

Read [its guide](../../backend/services/identity-account/guide.md). It owns account
identity, local/federated login, OAuth/OIDC, browser sessions, profile/moderation,
account avatar, transfer and deletion. Learning validates bearer claims and calls
Identity `/userinfo`; it never reads Identity tables.

### Learning

Read [its guide](../../backend/services/learning/guide.md). Fresh migrations V1–V15
own platform/storage, private Deck, deck-local LearningItem, EditingDraft,
CaptureNote, immutable objective/exercise authoring and bounded Study session
snapshots. API paths are canonical
under `/api`; there is no `/v2` or v1 alias.

The important #75 inputs already implemented are UUID identity, canonical JSON,
global command receipts, CAS, RFC 9457 errors, owner ACL, immutable revisions,
deck-local item identity, counted pages, native content, stable objectives,
versioned P0 exercise bindings, pinned session presentations, deterministic
all four P0 attempts, durable evidence, explicit restart and the baseline
`StudyState` reducer. Progress, additional session modes and retention cleanup
are now part of the canonical Learning runtime: progress is cursor-bounded,
selection is server-enforced for scheduled/replay/practice, and cleanup preserves
durable evidence and attempt tombstones.

### Frontend

`app.routes.ts` is the route source of truth. `/decks` authoring routes are current
and lazy. Material Browse/editor links to a separate lazy exercise inspector for
all four P0 mechanics; the inspector uses current node projections, strict
exercise envelopes and recoverable conflict/retry state without loading Study.
Old `my-study`, public-deck, template, review/import/media/AI services and routes
were removed in #146; account profile uses native Identity API.
For frontend changes, use the current
[brand and UI contract](../frontend/mnema-brand-and-ui-contract.md). Its visual
source is the accepted
[paper/antiquity/indigo direction](../frontend/design-and-experience-2026-09.md).
The canonical public landing now uses the Mnemosyne engraving and editorial
section layout. Its Angular route, responsive CSS and local production assets
are under `frontend/src/app` and `frontend/src/assets/brand`; the historical
[Angular paper-shell spike](./evidence/epic-74/editor/paper-shell-prototype/angular/paper-landing.component.html)
remains design evidence, not runtime code. Theme values are centralized in
`frontend/src/theme/tokens.css`.

The canonical `/decks/:deckId/study` route is lazy and deck-scoped. It implements the five
#266 mechanics (`SELF_CHECK`, `FREE_RESPONSE`, multi-blank `CLOZE`, single/multiple
`CHOICE` and mixed-media `MATCH`), PREPARING polling, strict server-envelope
validation, account-bound 24-hour session recovery and an
exact-attempt retry after an unknown network outcome. Learner presentations never
contain answer keys; references arrive only in feedback, and first-letter hints and
transcripts are server-recorded accommodations. Choice option IDs are checked
against the pinned exercise answer key and never credit distractors. The same route now
owns replay from a selected completed session, introduced-only practice by default,
material progress and explicit restart confirmation; non-scheduled modes state that
they do not change canonical progress. Before scheduled start, quick 10/2 and
standard 20/5 budgets are explicit; both remain one scheduler/attempt contract.
There is no legacy `my-study` fallback.

## Canonical executable sources

| Question | Source of truth |
|---|---|
| Modules and dependency versions | `backend/settings.gradle.kts`, service build files, `frontend/package.json` |
| Database shape | ordered migrations under each runtime's `src/main/resources/db` |
| HTTP routes | Spring controllers/security tests and `frontend/src/app/app.routes.ts` |
| Local replacement topology | `compose.local-full-stack.yml`, `scripts/mnema-local-full-stack.sh` |
| Protected CI | `.github/workflows/pull-request.yaml`, `.github/workflows/deploy.yaml` |
| Coverage floors | `backend/coverage-baseline.json` |
| Documentation entry | `docs/README.md` |
| Issue/PR format and statuses | `docs/engineering/work-item-standard.md` |

## Полный quality gate

Prerequisites: JDK 21 toolchain availability, Node 22.23.2, npm, Chrome/Chromium and
working Docker/Testcontainers resources. Do not allow database tests to skip. With
Colima on macOS, point discovery at its host socket and mounts at the Linux VM socket:

```bash
export DOCKER_HOST="unix://${HOME}/.colima/default/docker.sock"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
```

```bash
cd backend
./gradlew clean quality

cd ../frontend
npm ci
npm run lint
npm run test
npm run build
```

Repository policy and docs:

```bash
python3 scripts/verify_docs.py
python3 scripts/verify_github_actions_pins.py
python3 scripts/verify_security_automation_policy.py
python3 scripts/verify_artifact_security_policy.py
python3 scripts/verify_production_image_pins.py
python3 -m unittest discover -s scripts/tests -p 'test_verify_*.py' -v
```

Real cross-service security/cancellation:

```bash
./backend/gradlew -p backend :services:identity-account:bootJar :services:learning:bootJar
MNEMA_RUN_CANCELLATION_INTEGRATION=1 python3 -m unittest discover -s scripts/learning-security/tests -v
python3 scripts/learning-security/run.py
python3 scripts/learning-security/verify_cancellation.py
```

The remaining mandatory release/security/smoke/backup/purge contracts are the exact
commands in the `frontend-quality` job of
[PR Quality](../../.github/workflows/pull-request.yaml). They include every maintained
`scripts/test-*.sh`, smoke and backup unit suites, PostgreSQL 16→18 recovery and the
disposable no-snapshot purge rehearsal. Operational contract tests do not perform a
deployment.

The real browser harness covers auth, authoring and one typed Study interaction; it is not a substitute
for unit gates:

```bash
python3 scripts/browser-identity/run.py --dist frontend/dist/mnema-frontend \
  --node /absolute/path/to/node22 --authoring
```

It uses disposable local services and a real HTTPS Chrome flow. Follow
[`scripts/browser-identity/README.md`](../../scripts/browser-identity/README.md) for
environment prerequisites and cleanup.

## Delivery mode

The shared server is unavailable. `main` image publication and operational workflows
are fail-closed/paused. A locally and hosted-verified protected squash is complete
local delivery; it is not deployed or production-verified. Do not rerun historical
operational workflows or contact the former host. Reactivation needs its own reviewed
infrastructure issue.

## Change routes

### Epic #75: Study/exercises/scheduler

- Add new domain code under `backend/services/learning`; do not repair or import
  `core/.../review` algorithms/entities/migrations.
- Add new fresh Learning migrations after V5; never edit applied V1–V5.
- Keep content, exercise revision, attempt/evaluation/evidence and `StudyState`
  separate. Only explicitly `ASSESSED` objectives may receive scheduler evidence.
- Reuse command receipts/CAS/problem details and Deck/LearningItem revision pins.
- Keep Study routes/components on the current Learning contract.
- Preserve keyboard, screen-reader, touch and non-drag alternatives from the accepted
  exercise catalog/a11y boundary.

### Native media changes

- The removed v1 `media` module and S3 rows/URLs are historical evidence only.
- Extend the implemented Learning media lifecycle through its current guide,
  migrations V10–V15 and [accepted #76 contract](./epic-76-refinement.md).
- Keep logical references authorized through native content capabilities; a hash
  or object key alone grants no access. Recheck finalize races, variants,
  tombstones, GC and offline manifests with object-protocol evidence when changed.

### Legacy removal

Module/build/route removal belongs to #146. #147 owns production cutover/purge and
is outside local delivery.

## High-risk areas

- A green unit test cannot prove correct per-objective credit, deterministic replay,
  session snapshot isolation or bounded M:N fan-out for #75.
- Historical `core` scheduler names and tables are research evidence in Git history,
  not a model for new work.
- PostgreSQL-backed integration tests are fail-closed. Docker/socket failure is an
  environment failure, not permission to accept skipped coverage.
- Local browser evidence does not certify VoiceOver/TalkBack, physical touch, Safari,
  Firefox or production latency/capacity.
- Hosted operations are paused; passing workflow contract tests does not prove a
  server, backup, image or deployment exists.

## Epic #75 Study contract

Canonical reading order:

1. [Epic #75](https://github.com/MattoYuzuru/Mnema/issues/75).
2. [Accepted owner decisions](../decisions/owner-decisions-2026-08.md) and
   [authoring/Study workflows](../product/authoring-and-study-workflows.md).
3. [Content/Study platform](../architecture/content-platform-v2.md) and
   [exercise catalog](../product/exercise-catalog-v2.md).
4. Current [Learning guide](../../backend/services/learning/guide.md), migrations,
   platform tests and [#74 acceptance](./evidence/epic-74/verification/integrated-main-2026-09-19.md).

Existing contracts: deck-local identities; immutable Deck/Item revisions; native
projection capabilities; command idempotency; row-version CAS; owner ACL; stable
Problem Details; fail-closed Identity; counted membership/storage roots. Do not
redefine them during Study refinement without evidence of a conflict.

Owner decisions are accepted in [Epic #75 refinement](./epic-75-refinement.md), and
the exact shared examples live in [`contracts/study`](../../contracts/study/README.md).
The epic was split into protected slices #212–#219, #58 and closure #221;
#220 supplies the persistent local launcher. The
[integrated acceptance record](./evidence/epic-75/verification/integrated-main-2026-09-24.md)
maps every P0 criterion to its executable test or real local flow. Run the gate
above plus the persistent smoke and real browser harness when changing Study.
Manual device/assistive-technology testing, live 50k database latency and cohort
calibration remain explicit evidence limits.

## Documentation contract

Add durable docs only when they own a decision, contract or evidence set. Give them
one explicit status and link them from [docs/README.md](../README.md). Run
`python3 scripts/verify_docs.py`; never fix drift by copying the same rules into a
second agent guide.
