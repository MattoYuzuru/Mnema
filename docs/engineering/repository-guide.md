---
artifact:
  id: repository-guide
  type: navigator
  title: "Mnema repository guide"
  status: current
  created_at: "2026-08-15"
  updated_at: "2026-08-30"
  owners: ["project-owner"]
  evidence_revision: "8e0c83d"
---

# Repository guide

Start here when changing Mnema. This page maps the v1 checkout as evidence; the accepted target is a direct greenfield replacement. Proposed relational/product details remain labelled, but `/v2`, compatibility wrappers and preservation of old module/algorithm boundaries are already rejected.

## First read

1. Root [AGENTS.md](../../AGENTS.md) — engineering, UX, dependency and quality constraints.
2. [System overview](../system-overview.md) — current service topology.
3. The service document and nearby tests for the area being changed.
4. For content/review changes, read the current schema migrations, accepted [owner decisions](../decisions/owner-decisions-2026-08.md), proposed [content platform v2](../architecture/content-platform-v2.md), [native content format](../architecture/learning-content-format-v2.md) and [exercise catalog](../product/exercise-catalog-v2.md).
5. For work that can reach production, read the [delivery audit](../operations/delivery-audit-2026-08.md).

The [project review](../reviews/project-review-2026-08.md) and [product direction](../product/product-direction-v2.md) are proposals, not descriptions of implemented behavior.

## Map

```text
Mnema/
├── backend/
│   ├── build.gradle.kts             aggregate quality/coverage tasks
│   ├── settings.gradle.kts          six Spring Boot modules
│   ├── scripts/                     backend quality support
│   └── services/
│       ├── auth/                    OAuth/login/JWT issuer
│       ├── user/                    profile and user-facing account data
│       ├── core/                    decks, cards, templates, review/SRS
│       ├── media/                   object storage and media metadata
│       ├── import/                  asynchronous import jobs
│       └── ai/                      providers, generation jobs, quotas
├── frontend/                        Angular standalone SPA
│   └── src/app/
│       ├── core/                    app-wide services, models, guards, shell
│       ├── features/                route/page features
│       └── shared/                  reusable UI, pipes and utilities
├── k8s/                             current production-style manifests
├── monitoring/                      monitoring configuration/assets
├── scripts/                         local launchers and local AI gateways
├── docs/                            canonical navigation and service docs
├── docker-compose.yml               local multi-service stack
└── .github/workflows/               PR quality and main CI/CD
```

The current six services share one PostgreSQL instance with separate schemas/migration histories. That is a legacy runtime fact, not a target. Ordinary fixes never edit deployed migrations; the greenfield runtime instead starts a new migration history and later deletes the replaced modules/histories from the shipping build.

## Canonical locations

| Question | Current source of truth |
|---|---|
| Build versions/modules | [backend/settings.gradle.kts](../../backend/settings.gradle.kts) and [frontend/package.json](../../frontend/package.json) |
| Backend quality gate | [backend/build.gradle.kts](../../backend/build.gradle.kts) |
| Frontend commands | [frontend/package.json](../../frontend/package.json) |
| API/runtime defaults | each service's `src/main/resources/application.properties` |
| Database shape | each service's ordered `db/migration` directory |
| Browser routes | [app.routes.ts](../../frontend/src/app/app.routes.ts) |
| Local topology | [docker-compose.yml](../../docker-compose.yml) |
| Production manifests | [k8s](../../k8s) |
| PR/main automation | [.github/workflows](../../.github/workflows) |
| Proposed greenfield model | [content-platform-v2.md](../architecture/content-platform-v2.md) |
| Native content/rendering contract | [learning-content-format-v2.md](../architecture/learning-content-format-v2.md) |
| Exercise contracts | [exercise-catalog-v2.md](../product/exercise-catalog-v2.md) |
| Product hypotheses | [product-direction-v2.md](../product/product-direction-v2.md) |
| No-snapshot account-only cutover/capacity/offline | [v2-reset-capacity-and-offline-plan.md](../operations/v2-reset-capacity-and-offline-plan.md) |

When prose and executable configuration disagree, verify runtime behavior and fix the prose; do not preserve a stale claim for consistency.

## Quality commands

Prerequisites: JDK 21, Node 22.23.2 (the CI and image-build baseline for Angular 18.2), npm and Chrome/Chromium for Karma. Docker is only needed for stack/integration work.

```bash
cd backend
./gradlew quality
```

This compiles all modules, runs backend tests and checks the repository's per-service coverage baseline.

### Docker 29 / Colima note

On the workstation audited on 2026-08-15, Testcontainers 1.21.3 initially attempted Docker API 1.32 while Docker Engine 29 accepted 1.40+, then tried to mount the macOS Colima socket path inside the Linux VM. Because tests use `disabledWithoutDocker = true`, container-backed tests were marked skipped and the coverage gate failed rather than reporting a normal test failure.

The verified workstation-local invocation was:

```bash
cd backend
JAVA_TOOL_OPTIONS='-Dapi.version=1.44' \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
TESTCONTAINERS_HUB_IMAGE_NAME_PREFIX=docker.io/ \
./gradlew quality
```

The image prefix override was needed only to bypass this user's stale corporate mirror; use the approved registry for the actual environment. Docker documents the Engine 29 minimum API change in its [release notes](https://docs.docker.com/engine/release-notes/29/), and the matching Testcontainers 1.21.3 failure is tracked in [testcontainers-java issue 11235](https://github.com/testcontainers/testcontainers-java/issues/11235). A repository-level Testcontainers upgrade/configuration is preferable, but changing that dependency requires a separate compatibility proposal and permission under `AGENTS.md`.

```bash
cd frontend
npm ci
npm run lint
npm run test
npm run build
```

Run the complete relevant gate before presenting or pushing a change. There is no configured frontend coverage threshold or end-to-end test suite at this revision; do not imply those checks ran.

For the local stack, first read the root [source license](../../LICENSE), then
follow [Self-hosted local deployment](../deploy/selfhost-local.md) and the
launchers documented in [scripts/README.md](../../scripts/README.md). Current
source permits only private personal use by one natural person unless a separate
written license applies. Never assume checked-in defaults are safe production
secrets.

## Change routes

### Deck, card, version or template behavior

- Start at `backend/services/core/.../deck` and the core Flyway migrations.
- Check both browser and review adapters: current version selection is not uniform.
- Preserve stable identities across edits; never use mutable content/checksum as identity.
- Distinguish draft, publish, subscribe, update and fork in naming and tests.
- Templates/fields are legacy evidence only. Greenfield work must not repair, wrap or carry them into new APIs/migrations; #74 replaces the whole path.
- Treat the v2 architecture documents as proposals until ADRs accept their exact contracts.

### Review or exercise behavior

- Current scheduler implementations live under `core/.../review/algorithm` as deletion/research evidence; #75 does not port them.
- Current state/log persistence lives under `core/.../review/entity` and core migrations.
- The frontend review route is currently one reveal/self-rating flow.
- Keep content, exercise attempts and scheduler state separate in new contracts.
- Add idempotency tests for answer retries and concurrency tests for first state creation.

### Deferred AI or import behavior

- Neither is part of the first replacement runtime. #77 requires explicit reactivation; a later import compiler follows native launch.
- Define idempotency, retry, timeout, partial-success and job-recovery behavior before changing a client loop.
- Never log provider credentials, prompts containing sensitive material or tokens.
- Verify provider/security APIs in official current documentation.

### Frontend behavior

- Scan the containing feature and shared primitives before adding a component.
- New route features should be lazy unless they are part of the initial shell.
- Use accessible dialog/focus behavior, semantic controls, mobile-first layout and reduced-motion fallbacks.
- Do not preserve Liquid Glass or the current visual identity. Until #74 receives owner design input, use a minimal accessible semantic HTML/CSS baseline.
- Keep user-scoped local state keyed by identity and clear it when identity changes.

### Schema migration

- Add a new migration; never rewrite V1–V25 or any migration already deployed.
- For ordinary v1 fixes, use expand/contract migrations.
- For greenfield replacement, start a fresh PostgreSQL migration history; do not run the v1 chain, build transformers or add compatibility migrations.
- Verify the account allowlist and rehearse only account-only restore. No full legacy snapshot is created. Follow the [reset plan](../operations/v2-reset-capacity-and-offline-plan.md); the first deletion is the explicit point of no return.

### Deployment

- Repository access does not imply production-cluster access.
- Resolve exact target/environment before any mutation.
- Run the gate on the exact commit being deployed.
- Use immutable image references and a complete release identity.
- Verify rollout plus an end-to-end smoke; readiness alone is insufficient.

## Known high-risk areas

- `CardService.java` is over 2,000 lines and combines several content/version workflows.
- Current public-card snapshots grow quadratically when large decks are edited incrementally.
- Pinned deck revisions are bypassed by some card read/review paths.
- Published revisions can be mutated through stale update sessions.
- Subscription eagerly creates per-card user rows.
- Review idempotency and first-state concurrency are incomplete.
- AI/import jobs have partial-commit and recovery gaps.
- Clients that previously cached the old unhashed frontend bundles may retain them until the already-issued cache lifetime expires; current production builds use content-hashed asset names.
- Large inline Angular components mix view, state, HTTP orchestration and persistence.
- Production backup/restore and release atomicity are not demonstrated.

The evidence and priorities behind these points are in the [project review](../reviews/project-review-2026-08.md).

## Repository automation status

The repository currently contains quality scripts and delivery workflows, but no repo-local Codex skill/plugin/hook catalog, deterministic fixture generator, end-to-end harness or architecture verification command. The machine-readable inventory is [capability-inventory.yaml](./capability-inventory.yaml).

Do not create empty framework folders. Add a repo-owned tool when its first consumer exists, and document input, output, side effects and validation in `scripts/README.md`. The first justified additions are:

1. deterministic native-document/exercise fixture generator;
2. account-only export/import reconciliation checker;
3. PostgreSQL 18 + MinIO API/worker E2E harness;
4. anonymous/authenticated release smoke;
5. bounded deck/attempt/evidence/media load profile;
6. docs/link validation.

## Documentation rule

Every durable document should say whether it is `current`, `proposed`, `historical` or `deprecated`. Put unresolved decisions in one owning artifact and link to them; do not copy competing roadmaps across service pages. Update [docs/README.md](../README.md) when adding a canonical document.
