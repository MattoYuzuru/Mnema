---
artifact:
  id: repository-guide
  type: navigator
  title: "Mnema repository guide"
  status: current
  created_at: "2026-08-15"
  updated_at: "2026-10-07"
  owners: ["project-owner"]
---

# Repository guide

This guide describes the current Identity/Learning/Angular checkout, including the
AI generation, speech, assessment, usage, plans and promo work of Epic #77. Root
[`AGENTS.md`](../../AGENTS.md) is normative; [docs/README.md](../README.md) owns
documentation status/navigation. Commands and workstation setup:
[agent runbook](./agent-runbook.md); which source answers which domain question, plus the
glossary: [domain truth map](./domain-truth-map.md).

## First read

1. [`AGENTS.md`](../../AGENTS.md).
2. [System overview](../system-overview.md), then the
   [domain truth map](./domain-truth-map.md) for the owning contract.
3. The current guide for the owning runtime and its nearby tests.
4. [Work item standard](./work-item-standard.md) for GitHub delivery;
   [production delivery](../operations/production-delivery.md) only for release work.
5. For Study, use the [implemented contract below](#epic-75-study-contract) and
   [integrated acceptance](./evidence/epic-75/verification/integrated-main-2026-09-24.md).

## Platform baseline

| Platform | Exact repository baseline | Source |
|---|---:|---|
| Java | toolchain 25 (LTS) | `backend/services/*/build.gradle.kts`, CI setup-java |
| Spring Boot | 4.1.1; Tomcat 11.0.26 and Jackson 3.1.7 overrides (other versions resolve from the Boot BOM) | `backend/settings.gradle.kts`, `backend/build.gradle.kts`, Boot BOM |
| Gradle | 9.8.0 | `backend/gradle/wrapper/gradle-wrapper.properties` |
| Testcontainers / JaCoCo | 2.0.5 / 0.8.15 | Boot BOM, `backend/build.gradle.kts` |
| Angular | core/CLI/build 22.2.1, zoneless | `frontend/package.json` |
| TypeScript | 6.0.3 | `frontend/package.json` |
| Node | 24.21.0 (LTS) in CI/images | workflows and `frontend/Dockerfile` |
| Frontend unit tests | Vitest 5 + jsdom (`@angular/build:unit-test`) | `frontend/angular.json`, `frontend/vitest.config.mts` |
| PostgreSQL | 18 in replacement compose/tests | `docker-compose.yml`, test fixtures |

The workstation JDK/Node may be newer (on the owner's Mac the default `java` is 26); release
claims use repository/CI toolchains, not whichever executable happens to be first on `PATH`.
Exact per-shell setup: [agent runbook](./agent-runbook.md#workstation-setup-owners-mac).

## Repository map

```text
Mnema/
├── backend/
│   ├── build.gradle.kts                 aggregate quality and coverage
│   └── services/
│       ├── identity-account/            current account/OAuth/OIDC runtime
│       └── learning/                    content/authoring/Study/media and AI/usage runtime
├── frontend/src/app/
│   ├── app.routes.ts                    current route source of truth
│   ├── home-page.component.ts           current public landing
│   ├── content/                         native document/editor/renderer
│   ├── features/authoring/              Capture, Draft, editor and Browse
│   ├── features/own-decks/              canonical private Deck UI
│   ├── features/study/                  canonical deck-scoped Study UI
│   ├── features/generation/             AI composer, Workshop, edits and planner
│   ├── features/usage, plans, promo/     budget, paywall and promo UI
│   ├── features/goal, experiment/       onboarding copy and server-assigned variants
│   ├── styleguide/                      development-only shared UI catalogue
│   └── core/, shared/                   shell, authentication and shared UI
├── contracts/                           shared content/authoring/Study fixtures;
│                                        generation, usage, notifications and speech contracts
├── scripts/
│   ├── browser-identity/                real local HTTPS browser/E2E harness
│   ├── local-full-stack/                persistent local API smoke
│   ├── learning-security/               real Identity↔Learning harness
│   ├── smoke/                           public post-deploy smoke
│   └── tests/                           repository policy tests
├── docs/                                canonical navigator and evidence
├── design/prototype/                    historical design evidence
├── deploy/                              VPS runtime, dispatcher and local full-stack sources
├── docker-compose.yml                   replacement backend maintenance runtime
├── compose.local-full-stack.yml         persistent local HTTPS product runtime
└── .github/workflows/                   protected quality, Main CI release and manual VPS operations
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

Read [its guide](../../backend/services/learning/guide.md). Fresh migrations
(`src/main/resources/db/learning/migration`; list the directory for the current head) own
platform/storage, private Deck, deck-local LearningItem, EditingDraft, CaptureNote,
immutable objective/exercise authoring, bounded Study session snapshots, the media
lifecycle, the unified exercise mechanics (V21, V23), AI work queues and usage,
plans and promo data (V26–V42 at this audit). API paths are canonical
under `/api`; there is no `/v2` or v1 alias.

The important #75 inputs already implemented are UUID identity, canonical JSON,
global command receipts, CAS, RFC 9457 errors, owner ACL, immutable revisions,
deck-local item identity, counted pages, native content, stable objectives,
versioned exercise bindings, pinned session presentations, deterministic
attempts for all seven mechanics, durable evidence, explicit restart and the baseline
`StudyState` reducer. Progress, additional session modes and retention cleanup
are now part of the canonical Learning runtime: progress is cursor-bounded,
selection is server-enforced for scheduled/replay/practice, and cleanup preserves
durable evidence and attempt tombstones.

### Frontend

`app.routes.ts` is the route source of truth. `/decks` authoring routes are current
and lazy. Material Browse/editor links to the lazy step-by-step exercise editor
(`/decks/:deckId/materials/:memberKey/exercises/new` and
`/decks/:deckId/exercises/:exerciseId/edit`) for all seven mechanics. It uses current
node projections, strict exercise envelopes, recoverable conflict/retry state and an
interactive preview served by `POST /api/exercise-previews` (no attempt, evidence or
progress), without loading Study.
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

The canonical `/decks/:deckId/study` route is lazy and deck-scoped. It implements all seven
mechanics (`SELF_CHECK`, `FREE_RESPONSE`, multi-blank `CLOZE`, single/multiple `CHOICE`,
mixed-media `MATCH`, `ORDER`, `CATEGORIZE`; wire contract in
[`contracts/study`](../../contracts/study/README.md#exercise-mechanics-266)), PREPARING polling, strict server-envelope
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

Prerequisites: JDK 25 toolchain availability, Node 24.21.0, npm, Chrome/Chromium (browser harness only; frontend unit tests need no browser) and
working Docker/Testcontainers resources. Do not allow database tests to skip. With
Colima on macOS, point discovery at its host socket and mounts at the Linux VM socket;
the verified per-shell setup (`JAVA_HOME`, `DOCKER_HOST`,
`TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE`, Node 24 path) is in the
[agent runbook](./agent-runbook.md#workstation-setup-owners-mac).

```bash
cd backend
./gradlew clean quality

cd ../frontend
npm ci
npm run lint
npm run test
npm run build

cd ..
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

The remaining mandatory release/security/smoke contracts are the exact
commands in the `frontend-quality` job of
[PR Quality](../../.github/workflows/pull-request.yaml). They include every maintained
`scripts/test-*.sh` and the smoke unit suite; VPS backup and restore are covered by
`scripts/tests/test_vps_*.py` in `backend-quality`. Operational contract tests do not
perform a deployment.

The real browser harness covers auth, authoring and one `FREE_RESPONSE` Study interaction in
its base `--authoring` flow; `--authoring --media --mechanics` extends it to authoring and
studying every mechanic through the real UI. It is not a substitute for unit gates:

```bash
python3 scripts/browser-identity/run.py --dist frontend/dist/mnema-frontend \
  --node /absolute/path/to/node24 --authoring            # add --media --mechanics for all mechanics
```

It uses disposable local services and a real HTTPS Chrome flow. Follow
[`scripts/browser-identity/README.md`](../../scripts/browser-identity/README.md) for
environment prerequisites and cleanup.

## Delivery mode

Production is available on the Russian VPS `mnema` (`ssh mnema`,
`135.106.175.30`), serving `mnema.app` and `auth.mnema.app`. Development releases
run in Main CI after the protected squash: scoped five-image publication, one
`prod` Environment approval, automatic admission, deployment, verification and public
smoke (`deploy.yaml`; `vps-deploy.yaml` is for manual status/verify/rollback). Follow
[production delivery](../operations/production-delivery.md). Keep local/hosted gates and
protected squash; merge alone is not live verification.

## Change routes

### Frontend feature changes

| Feature directory | Ownership and canonical behavior |
|---|---|
| `authoring`, `own-decks`, `study` | Manual editor/Browse, Deck hub and Study; [authoring/Study workflow](../product/authoring-and-study-workflows.md) and [Study contract](../../contracts/study/README.md) |
| `generation` | AI composer, Workshop, exercise builder, edits and planner; [generation contract](../../contracts/generation/README.md) and [brand UI](../frontend/mnema-brand-and-ui-contract.md#composer-и-мастерская) |
| `usage`, `plans`, `promo`, `experiment` | AI budget, paywall/profile tier, explicit code field/popup, server-assigned experiments; [usage contract](../../contracts/usage/README.md) |
| `billing` | Checkout call from `/plans`, trusted T-Bank redirect, return page `/plans/payment/:orderId` that only reads the server order; [billing contract](../../contracts/billing/README.md) |
| `goal`, `ai-info` | Once-asked learning goal (`LearningGoalStore`, `goal-copy.ts`) and public `/ai`; the goal changes copy/recommendations and never reaches a provider |

- `/plans` is authenticated; `/ai` is public. The goal prompt is allowlisted only on
  `/decks`, `/profile` and `/plans` in `goal-onboarding.component.ts`; new routes are quiet by default.
- Promo redemption with a lost, malformed successful or 5xx reply has an unknown outcome:
  preserve the normalized code's idempotency key. A discount is pending payment, not access.
  Preference acknowledgement is only `Promo-Event-Recorded: true`; pending receipts suppress
  new offers until retry at an allowed breakpoint. Account/navigation changes fence late replies;
  account-session frequency is client-held, cooldown/decline/purchase eligibility server-owned.
  Experiments use server assignments and reset on account change; events contain no client variant.
- Workshop alone enables renderer `exposeNodeIds` and `overlay`; Browse, Study and editor preview
  expose no node ids. Selection edits send whole selected blocks by id and announce through the
  Workshop summary. Shift+F10/menu key opens the group; keyboard order keeps block links/players
  before that group, with Tab also reaching it.
- «Попросить Мнему…» is the collapsed revise composer for materials/exercises when `aiGeneration`
  is available. `createIntent` is free; only «Запустить» creates the session and reserves.
  `REVISE_ITEM`/`REVISE_EXERCISE` stay in the Workshop: approval is an ordinary revise command,
  «Вернуть» restores the draft's first revision and «Ещё раз» starts a new edit.

### Epic #75: Study/exercises/scheduler

- Add new domain code under `backend/services/learning`; do not repair or import
  `core/.../review` algorithms/entities/migrations.
- Add new fresh Learning migrations after the current head (list
  `db/learning/migration` for its latest version); never edit an applied migration.
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
- Production release acceptance needs actual runtime evidence; workflow contract tests
  alone do not prove readiness, backup durability or browser acceptance.

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

Agent instruction files: root `AGENTS.md` (always loaded, short, normative) plus
`backend/AGENTS.md` and `frontend/AGENTS.md` (local differences only, loaded on demand).
Long rationale belongs in docs linked from them, not in those files.
`CLAUDE.md` imports the root contract and routes Claude Code to the same scoped rules;
`.github/copilot-instructions.md` is a thin GitHub adapter. Do not copy contracts or
model-specific prompting/API settings into a second rule set.

Start with the owning contract and nearby tests, search exact symbols with `rg`, and
expand only for an unresolved question. Read historical evidence when it explains a
decision or an acceptance limit. Keep required gates; after they pass, repeat or
broaden checks only for a new change, failure or unresolved concern. Parallel work
needs explicit file/resource ownership and the task's agent limit, with one integrator
for commits and delivery; do not have several agents mutate the same local stack.

This navigation follows official [OpenAI AGENTS.md discovery](https://learn.chatgpt.com/docs/agent-configuration/agents-md),
[GPT-6 guidance (including GPT-6.1 Sol)](https://developers.openai.com/api/docs/guides/latest-model?model=gpt-6.1-sol)
and [Claude Code best practices](https://code.claude.com/docs/en/best-practices): scoped
instructions, one statement of each rule and domain context on demand. The
[Opus 5.5](https://platform.claude.com/docs/en/build-with-claude/prompt-engineering/prompting-claude-opus-5-5)
and [Sonnet 5.5](https://platform.claude.com/docs/en/build-with-claude/prompt-engineering/prompting-claude-sonnet-5-5)
guides support clear completion and verification boundaries; their chat/API thinking
advice does not replace repository quality gates. These are design choices, not
measured token, cost or task-time savings.
