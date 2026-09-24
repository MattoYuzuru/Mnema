---
artifact:
  id: documentation-navigator
  type: navigator
  title: "Mnema documentation"
  status: current
  updated_at: "2026-09-24"
  owners: ["project-owner"]
  evidence_revision: "d7fd1b1d509a0ab598976f87af88101bfc3945ac"
---

# Mnema Docs

Это единственная каноническая точка навигации. Статусы означают:
`current` — фактический checkout/runtime; `accepted` — принятое решение или input;
`proposed` — требует refinement/решения; `historical` — evidence прошлого этапа;
`superseded` — заменено указанным источником; `legacy` — v1 replacement input.

## Маршрут implementation-агента: 5 минут

1. **Current:** корневой [`AGENTS.md`](../AGENTS.md) — нормативные engineering,
   UX, security, quality и delivery rules. Других нормативных agent-guides нет.
2. **Current:** [System overview](./system-overview.md) — что реально работает после
   #74/#75 и что остаётся legacy.
3. **Current:** [Repository guide](./engineering/repository-guide.md) — версии,
   каталоги, runtime boundaries, harnesses и полный gate.
4. **Current:** [Local-only delivery](./operations/local-development-delivery.md) —
   почему merge не требует deployment и не является production verification.
5. **Historical acceptance evidence:**
   [закрытие Epic #74](./engineering/evidence/epic-74/verification/integrated-main-2026-09-19.md).
6. **Study:** [acceptance evidence Epic #75](./engineering/evidence/epic-75/verification/integrated-main-2026-09-24.md),
   [принятый refinement](./engineering/epic-75-refinement.md) и исполняемый
   [`contracts/study`](../contracts/study/README.md).

## Product

| Статус | Документ | Назначение |
|---|---|---|
| accepted | [Owner decisions](./decisions/owner-decisions-2026-08.md) | Решения владельца и явно открытые вопросы. |
| accepted | [Authoring and Study workflows](./product/authoring-and-study-workflows.md) | Product contract для authoring, Capture, exercises и режимов практики; P0 реализован в #74/#75. |
| accepted | [Exercise catalog](./product/exercise-catalog-v2.md) | Принятые mechanics, attempt/evidence, reducer и retention boundaries для #75. |
| proposed | [Product direction](./product/product-direction-v2.md) | Product hypotheses, roadmap и метрики. |
| proposed | [Launch economics](./product/russia-launch-economics-2026.md) | Коммерческие гипотезы. |
| proposed | [Legal/payment checklist](./product/russia-legal-launch-checklist-2026.md) | Human/legal gates; не юридическая гарантия. |

## Architecture и backend

| Статус | Документ | Назначение |
|---|---|---|
| current | [System overview](./system-overview.md) | Replacement topology и legacy boundary. |
| accepted | [Content and Study platform](./architecture/content-platform-v2.md) | Общая модель; content и P0 Study реализованы в #74/#75, P1/P2 остаются границами будущей работы. |
| accepted | [Native content format](./architecture/learning-content-format-v2.md) | Persisted native document contract; базовый формат реализован в #74. |
| current | [Revision storage and runtime boundaries](./architecture/revision-storage-and-runtime-boundaries.md) | Выбранное и реализованное storage-направление с остаточными границами. |
| current | [Counted-page contract](./architecture/counted-page-contract.md) | Реализованные counted-page/structural invariants. |
| current | [Identity & Account guide](../backend/services/identity-account/guide.md) | Текущий identity runtime. |
| current | [Learning API guide](../backend/services/learning/guide.md) | Текущий content, exercise и Study runtime. |
| legacy | [`core`, `media`, `import`, `ai` guides](#legacy-и-superseded) | Replacement input; не шаблон для #75/#76. |

## Frontend

| Статус | Документ | Назначение |
|---|---|---|
| accepted | [Design and experience](./frontend/design-and-experience-2026-09.md) | Выбранное paper/antiquity/indigo направление и a11y boundaries. |
| historical | [Epic #74 UI evidence](./engineering/evidence/README.md#frontend-и-browser) | Снимки, browser и component evidence завершённого этапа. |
| superseded | [Experience audit 2026-08](./frontend/experience-audit-2026-08.md) | Findings сохранены, Liquid Glass/Focused Study Desk direction отклонено. |
| historical | [Interactive prototype](../design/prototype/README.md) | Design evidence, не production architecture. |

## Engineering и quality

| Статус | Документ | Назначение |
|---|---|---|
| current | [Repository guide](./engineering/repository-guide.md) | Карта кода, версии, change routes и gate. |
| current | [Work item standard](./engineering/work-item-standard.md) | Issue/PR/Project status contract. |
| historical | [GitHub execution model](./engineering/github-execution-model.md) | Исходная настройка Project #4; текущие статусы читаются из GitHub. |
| current | [Capability inventory](./engineering/capability-inventory.yaml) | Машиночитаемый список команд и harnesses. |
| current | [Documentation/evidence index](./engineering/evidence/README.md) | Короткий вход в большие evidence-наборы. |
| historical | [Epic #74 refinement](./engineering/epic-74-refinement.md) | Выполненный план #74; не backlog #75. |
| accepted | [Epic #75 refinement](./engineering/epic-75-refinement.md) | Принятые решения и реализованные delivery slices. |
| historical | [Epic #74 dependency decisions](./engineering/epic-74-dependency-decisions.md) | Принятые зависимости и rationale. |
| historical | [Epic #74 hardware handoff](./engineering/epic-74-hardware-handoff.md) | Machine/session handoff завершённого этапа. |
| historical | [Epic #74 execution prompt](./engineering/prompts/epic-74-end-to-end.md) | Исходное поручение; не текущая инструкция. |
| proposed | [Greenfield delivery plan](./engineering/v2-delivery-plan-2026-08.md) | Sequencing proposal; GitHub state и текущий navigator приоритетнее. |

## Operations

| Статус | Документ | Назначение |
|---|---|---|
| current | [Local-only delivery](./operations/local-development-delivery.md) | Действующая completion boundary. |
| current | [Persistent local runtime](./deploy/selfhost-local.md) | HTTPS запуск Identity, Learning, Angular и сохранение данных между стартами; ниже отдельно отмечена historical v1 часть. |
| current | [Security automation triage](./operations/security-triage.md) | Dependabot/dependency review/CodeQL policy. |
| current | [CI artifact boundary](./operations/ci-artifact-security-boundary.md) | Artifact and token policy, хотя image publication сейчас paused. |
| current | [Browser security headers](./operations/browser-security-headers.md) | Проверяемый response-security contract. |
| current | [No-snapshot purge rehearsal](./operations/no-snapshot-purge-rehearsal.md) | Disposable policy test; не production purge. |
| superseded | [Staging runbook](./operations/staging-runbook.md), [release verification](./operations/release-verification-runbook.md), [database recovery](./operations/database-recovery-runbook.md) | Restoration blueprints; local-only policy запрещает их запуск/ожидание. |
| current | [Production image inventory](./operations/production-image-inventory.md), [release security evidence](./operations/release-security-evidence.md) | Проверяемые supply-chain contracts; publication сейчас paused. |
| proposed | [Delivery audit](./operations/delivery-audit-2026-08.md) | Исходные delivery recommendations; local-only policy приоритетнее. |
| historical | [GitHub/staging plan](./operations/github-platform-and-staging-plan-2026-08.md) | План и evidence доступного ранее hosted delivery. |
| proposed | [Reset/capacity/offline plan](./operations/v2-reset-capacity-and-offline-plan.md) | Будущие #76/#147 boundaries; не разрешение на destructive work. |

Deployment отсутствует в текущем completion boundary, потому что общий сервер
недоступен и operational workflows fail-closed. Реактивация — отдельная reviewed
infrastructure task; никакие прошлые staging результаты не доказывают текущую
доступность.

## Historical evidence

- **historical:** [Epic #74/#75 evidence index](./engineering/evidence/README.md) —
  acceptance, storage, browser, security и research evidence с короткими маршрутами.
- **historical:** [Project review](./reviews/project-review-2026-08.md) и
  [September refinement research](./reviews/product-refinement-2026-09.md) —
  исходные findings; принятые решения перенесены в owner/architecture docs.
- **legacy:** [v1 schema](./core-entities-schema.md) и service docs ниже — только
  объяснение старого runtime; точный код сохранён тегом `v1-apache-final` и Git history.

## Legacy и superseded

Следующие материалы не входят в обычный маршрут реализации:

- `docs/services/{auth,user,core,media,import,ai}-service.md` — **legacy** v1;
- `docs/services/frontend.md` — **legacy** UI overview;
- `docs/deploy/selfhost-public.md` и `docs/deploy/model-matrix.md` — **legacy** v1;
- historical section в [local self-host guide](./deploy/selfhost-local.md) —
  ссылка на Apache-срез, не исполняемый путь текущего checkout;
- `docs/engineering/agent-guide.md` — **superseded**, тонкий pointer на `AGENTS.md`.

При расхождении prose с executable sources сначала проверяйте build files,
migrations, routes, tests и workflows, затем исправляйте канонический документ —
не создавайте ещё один конкурирующий источник.
