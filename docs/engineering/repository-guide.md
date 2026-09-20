---
artifact:
  id: repository-guide
  type: navigator
  title: "Mnema repository guide"
  status: current
  created_at: "2026-08-15"
  updated_at: "2026-09-20"
  owners: ["project-owner"]
  evidence_revision: "933da3e60add2102ed7480342dfd3de95a8a255b"
---

# Repository guide

This guide describes the checkout after Epic #74. Root [`AGENTS.md`](../../AGENTS.md)
is normative; [docs/README.md](../README.md) owns documentation status/navigation.

## First read

1. [`AGENTS.md`](../../AGENTS.md).
2. [System overview](../system-overview.md).
3. The current guide for the owning runtime and its nearby tests.
4. [Local-only delivery](../operations/local-development-delivery.md) before any
   delivery decision.
5. For #75, use the [handoff below](#handoff-для-epic-75), not legacy review code.

## Platform baseline

| Platform | Exact repository baseline | Source |
|---|---:|---|
| Java | toolchain 21 | `backend/build.gradle.kts`, CI setup-java |
| Spring Boot | 3.5.16 | `backend/settings.gradle.kts` |
| Kotlin | 2.1.10 | `backend/settings.gradle.kts` |
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
│       ├── learning/                    current content/authoring runtime
│       ├── core/                        legacy deck/card/review input
│       ├── media/                       legacy media input
│       ├── import/                      legacy import input
│       └── ai/                          legacy/deferred AI input
├── frontend/src/app/
│   ├── content/                         native document/editor/renderer
│   ├── features/authoring/              Capture, Draft, editor and Browse
│   ├── features/own-decks/              canonical private Deck UI
│   └── core/, shared/, other features/  mixed current shell and legacy input
├── contracts/                           shared native/content/deck/item fixtures
├── scripts/
│   ├── browser-identity/                real local HTTPS browser/E2E harness
│   ├── learning-security/               real Identity↔Learning harness
│   ├── backup/, smoke/, purge/          deterministic policy/integration tools
│   └── tests/                           repository policy tests
├── docs/                                canonical navigator and evidence
├── design/prototype/                    historical design evidence
├── k8s/, deploy/                        paused/restoration operational sources
├── docker-compose.yml                   replacement backend maintenance runtime
└── .github/workflows/                   protected quality and dormant operations
```

`settings.gradle.kts` intentionally still compiles six modules. Shipping/local
replacement topology is only Identity & Account + Learning; remaining legacy module
removal belongs to #146.

## Current runtime contracts

### Identity & Account

Read [its guide](../../backend/services/identity-account/guide.md). It owns account
identity, local/federated login, OAuth/OIDC, browser sessions, profile/moderation,
account avatar, transfer and deletion. Learning validates bearer claims and calls
Identity `/userinfo`; it never reads Identity tables.

### Learning

Read [its guide](../../backend/services/learning/guide.md). Fresh migrations V1–V8
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
follow in the remaining #75 slices.

### Frontend

`app.routes.ts` is the route source of truth. `/decks` authoring routes are current
and lazy. Material Browse/editor links to a separate lazy exercise inspector for
all four P0 mechanics; the inspector uses current node projections, strict
exercise envelopes and recoverable conflict/retry state without loading Study.
`my-study`, public-deck, template, old review/import/media/AI services and components
are legacy or deferred; inspect them only as deletion/research evidence.
The accepted visual direction is
[paper/antiquity/indigo](../frontend/design-and-experience-2026-09.md).

The canonical `/decks/:deckId/study` route is lazy and deck-scoped. It currently
implements all four scheduled P0 presentations (`SELF_CHECK`, `TYPED`, one-blank
`CLOZE_SINGLE` and `SINGLE_CHOICE`), PREPARING polling, strict server-envelope
validation, account-bound 24-hour session recovery and an
exact-attempt retry after an unknown network outcome. Reference answers remain
hidden until a production attempt is accepted. Choice option IDs are checked
against pinned server bindings and never credit distractors. Replay, practice and
progress are owned by the remaining #75 slices rather than legacy
`my-study` code.

## Canonical executable sources

| Question | Source of truth |
|---|---|
| Modules and dependency versions | `backend/settings.gradle.kts`, service build files, `frontend/package.json` |
| Database shape | ordered migrations under each runtime's `src/main/resources/db` |
| HTTP routes | Spring controllers/security tests and `frontend/src/app/app.routes.ts` |
| Local replacement topology | `docker-compose.yml` |
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

The real browser harness is proportional for auth/authoring changes, not a substitute
for unit gates:

```bash
python3 scripts/browser-identity/run.py --authoring --output /tmp/mnema-authoring-evidence
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
- Add lazy Angular Study routes/components; do not build on legacy `my-study` or
  old review services simply because they remain in source.
- Preserve keyboard, screen-reader, touch and non-drag alternatives from the accepted
  exercise catalog/a11y boundary.

### Epic #76: media lifecycle

- The `media` module and v1 S3 rows/URLs are legacy evidence, not the target.
- New logical authorized references must integrate with native content capabilities
  without granting access by hash/object key.
- Lifecycle, finalize races, variants, tombstones, GC and offline manifests require
  separate refinement and object-protocol evidence.

### Legacy removal

Do not broaden feature work into global deletion. #146 owns remaining module/build/
route removal only after #74–#76 gates. #147 owns production cutover/purge and is
outside local delivery.

## High-risk areas

- A green unit test cannot prove correct per-objective credit, deterministic replay,
  session snapshot isolation or bounded M:N fan-out for #75.
- Existing `core` scheduler names and tables can accidentally bias the new model;
  they are deletion/research evidence only.
- Frontend still contains legacy routes/services next to canonical authoring code;
  route imports and bundles must be checked when adding Study.
- PostgreSQL-backed integration tests are fail-closed. Docker/socket failure is an
  environment failure, not permission to accept skipped coverage.
- Local browser evidence does not certify VoiceOver/TalkBack, physical touch, Safari,
  Firefox or production latency/capacity.
- Hosted operations are paused; passing workflow contract tests does not prove a
  server, backup, image or deployment exists.

## Handoff для Epic #75

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
The epic is split into reviewable issues #212–#219, #58 and #221; related local
developer usability is #220. Move only the active slice through `In progress` and
`In review`; a later slice stays Backlog until its dependencies are merged.

The first implementation vertical should be one deck-scoped, single assessed
objective, deterministic typed-answer or behavioral-self-check flow using a pinned
Deck/Item revision and exact retry receipt. It should prove no cross-deck candidate,
no state change on browse/cancel/failure, one transition on retry, replayable
algorithm/config identity and accessible feedback before adding M:N mechanics.

Run the full gate above plus focused PostgreSQL concurrency/idempotency tests and the
real browser harness. Residual risks that unit tests cannot hide: false mastery from
wrong objective attribution, duplicate/out-of-order attempts, stale session pools,
unbounded fan-out/locking, inaccessible mechanics and lack of production/device/
cohort calibration evidence.

## Documentation contract

Add durable docs only when they own a decision, contract or evidence set. Give them
one explicit status and link them from [docs/README.md](../README.md). Run
`python3 scripts/verify_docs.py`; never fix drift by copying the same rules into a
second agent guide.
