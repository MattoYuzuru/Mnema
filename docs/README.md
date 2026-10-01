---
artifact:
  id: documentation-navigator
  type: navigator
  title: "Mnema documentation"
  status: current
  updated_at: "2026-10-01"
  owners: ["project-owner"]
  evidence_revision: "f6955a5fb9889f546dc47129e5e4bed7b913f95a"
---

# Mnema Docs

Это единственная каноническая точка навигации. Статусы означают:
`current` — фактический checkout/runtime; `accepted` — принятое решение или input;
`proposed` — требует refinement/решения; `historical` — evidence прошлого этапа;
`superseded` — заменено указанным источником; `legacy` — v1 replacement input.
Каждый `current`/`accepted` документ ниже доступен не более чем за два перехода от
этой страницы; историческое evidence вынесено в отдельный раздел и не является
текущим поведением.

## Маршрут implementation-агента

1. **Current:** корневой [`AGENTS.md`](../AGENTS.md) — короткий нормативный контракт
   (hard constraints, security, UI direction, quality gate, delivery и merge boundary).
   Локальные отличия: [`backend/AGENTS.md`](../backend/AGENTS.md),
   [`frontend/AGENTS.md`](../frontend/AGENTS.md).
2. **Current:** [Domain truth map](./engineering/domain-truth-map.md) — какой источник
   отвечает на какой доменный вопрос, и глоссарий (mechanic / content / objective /
   evaluator, presentation, scheduled / replay / practice, capability).
3. **Current:** [Agent runbook](./engineering/agent-runbook.md) — проверенные команды
   gate, настройка JDK 21 / Colima / Node 22, browser harness и локальный стек.
4. **Current:** [Repository guide](./engineering/repository-guide.md) — версии,
   каталоги, runtime boundaries, change routes и
   [полный gate](./engineering/repository-guide.md#полный-quality-gate).
5. **Current:** [System overview](./system-overview.md) и
   [Local-only delivery](./operations/local-development-delivery.md) — что реально
   работает и почему merge не требует deployment.
6. **Study:** исполняемый контракт и все семь механик —
   [`contracts/study`](../contracts/study/README.md); продуктовое обоснование —
   [exercise catalog](./product/exercise-catalog-v2.md); acceptance —
   [Epic #75](./engineering/evidence/epic-75/verification/integrated-main-2026-09-24.md).
7. **Media:** [Epic #76 refinement](./engineering/epic-76-refinement.md) и раздел
   [Media](#media-epic-76) ниже.

## Agent-facing engineering files

| Статус | Документ | Назначение |
|---|---|---|
| current | [Engineering standards](./engineering/engineering-standards.md) | Полная формулировка общих правил (research, UX/a11y, backend, frontend, tests, TODO, формат ответа); краткая форма — в `AGENTS.md`. |
| current | [Domain truth map](./engineering/domain-truth-map.md) | Источник истины по доменным вопросам и глоссарий. |
| current | [Agent runbook](./engineering/agent-runbook.md) | Команды и настройка рабочей машины с пометкой verified/not re-run. |
| superseded | [Старый agent guide](./engineering/agent-guide.md) | Указатель на `AGENTS.md`; не копировать правила обратно. |
| historical | [Epic #74 execution prompt](./engineering/prompts/epic-74-end-to-end.md) | Исходное поручение; не текущая инструкция. |

## Product

| Статус | Документ | Назначение |
|---|---|---|
| accepted | [Owner decisions](./decisions/owner-decisions-2026-08.md) | Решения владельца и явно открытые вопросы. |
| accepted | [Source license transition](./decisions/source-license-transition.md) | Решение о переходе лицензии исходного кода. |
| accepted | [Authoring and Study workflows](./product/authoring-and-study-workflows.md) | Product contract для authoring, Capture, exercises и режимов практики. |
| accepted | [Exercise catalog](./product/exercise-catalog-v2.md) | Механики, attempt/evidence, reducer и retention boundaries. |
| accepted | [UX refinement 2026-09-29](./product/ux-improvements-2026-09-29.md) | Приёмочные сценарии доработки редактора, упражнений и основных экранов. |
| accepted | [Final polish 2026-09-29](./product/final-polish-2026-09-29.md) | Удаление, заметки «На потом», пропуски и режимы проверки текста. |
| proposed | [Product direction](./product/product-direction-v2.md) | Product hypotheses, roadmap и метрики. |
| proposed | [Launch economics](./product/russia-launch-economics-2026.md) | Коммерческие гипотезы. |
| proposed | [Legal/payment checklist](./product/russia-legal-launch-checklist-2026.md) | Human/legal gates; не юридическая гарантия. |

## Architecture и backend

| Статус | Документ | Назначение |
|---|---|---|
| current | [System overview](./system-overview.md) | Replacement topology и legacy boundary. |
| accepted | [Content and Study platform](./architecture/content-platform-v2.md) | Общая модель; P0 content/Study реализованы, P1/P2 — границы будущей работы. |
| accepted | [Native content format](./architecture/learning-content-format-v2.md) | Persisted native document contract. |
| current | [Revision storage and runtime boundaries](./architecture/revision-storage-and-runtime-boundaries.md) | Выбранное и реализованное storage-направление. |
| current | [Counted-page contract](./architecture/counted-page-contract.md) | Counted-page/structural invariants. |
| current | [Identity & Account guide](../backend/services/identity-account/guide.md) | Identity runtime. |
| current | [Learning API guide](../backend/services/learning/guide.md) | Content, exercise, Study и media runtime. |
| current | [Learning runtime policy index](./engineering/runtime-policy-index.md) | Реестр runtime policies Learning. |
| current | Контракты: [study](../contracts/study/README.md), [authoring](../contracts/authoring/README.md), [decks](../contracts/decks/README.md), [items](../contracts/items/README.md), [native content](../contracts/content/native-v1/README.md) | Общие fixtures backend/frontend. |
| historical | [`v1-apache-final`](https://github.com/MattoYuzuru/Mnema/tree/v1-apache-final) | Последний полный срез старых сервисов и миграций. |

## Media (Epic #76)

| Статус | Документ | Назначение |
|---|---|---|
| accepted | [Epic #76 refinement](./engineering/epic-76-refinement.md) | Scope, архитектурные границы и задачи. |
| current | [Upload transport](./engineering/media-upload-transport.md) | Прямая передача в S3-совместимое хранилище, API и состояния. |
| current | [Playback](./engineering/media-playback.md) | Подписанные playback URL и обновление. |
| current | [Offline manifest](./engineering/media-offline-manifest.md) | Неизменяемый inventory media колоды. |
| current | [Reachability and GC](./engineering/media-gc.md) | Holds, tombstones и физическое удаление. |
| current | [Editor media workflow](./engineering/editor-media-workflow.md) | Загрузка и вставка media в редакторе. |
| historical | [Epic #76 acceptance evidence](./engineering/evidence/epic-76/integrated-browser/README.md) | Локальный browser/Study run; ещё [worker](./engineering/evidence/epic-76/phone-worker.md) и [visual](./engineering/evidence/epic-76/media-visual/README.md). |

## Frontend

| Статус | Документ | Назначение |
|---|---|---|
| current | [Mnema brand and UI contract](./frontend/mnema-brand-and-ui-contract.md) | Правила оформления и проверки изменений в действующем Angular UI. |
| accepted | [Design and experience](./frontend/design-and-experience-2026-09.md) | Выбранное paper/antiquity/indigo направление и a11y boundaries. |
| historical | [Frontend brand restoration evidence](./engineering/evidence/frontend-brand-2026-09-28/README.md) | Снимки production Angular и результат реального HTTPS-сценария. |
| historical | [Interactive prototype](../design/prototype/README.md) | Design evidence, не production architecture. |
| superseded | [Experience audit 2026-08](./frontend/experience-audit-2026-08.md) | Findings сохранены, Liquid Glass/Focused Study Desk direction отклонено. |

## Engineering и quality

| Статус | Документ | Назначение |
|---|---|---|
| current | [Repository guide](./engineering/repository-guide.md) | Карта кода, версии, change routes и gate. |
| current | [Work item standard](./engineering/work-item-standard.md) | Issue/PR/Project status contract. |
| current | [Capability inventory](./engineering/capability-inventory.yaml) | Машиночитаемый список команд и harnesses; при расхождении приоритетнее runbook и `--help`. |
| current | [Evidence index](./engineering/evidence/README.md) | Короткий вход в большие evidence-наборы. |
| accepted | [Epic #75 refinement](./engineering/epic-75-refinement.md) | Принятые решения и реализованные delivery slices. |
| historical | [Epic #74 refinement](./engineering/epic-74-refinement.md), [dependency decisions](./engineering/epic-74-dependency-decisions.md), [hardware handoff](./engineering/epic-74-hardware-handoff.md) | Выполненный план, принятые зависимости и session handoff #74. |
| historical | [GitHub execution model](./engineering/github-execution-model.md) | Исходная настройка Project #4; текущие статусы читаются из GitHub. |
| proposed | [Greenfield delivery plan](./engineering/v2-delivery-plan-2026-08.md) | Sequencing proposal; GitHub state и этот navigator приоритетнее. |

## Operations

| Статус | Документ | Назначение |
|---|---|---|
| current | [Local-only delivery](./operations/local-development-delivery.md) | Действующая completion boundary. |
| current | [Persistent local runtime](./deploy/selfhost-local.md) | HTTPS-запуск Identity, Learning, Angular, media и Colima clock; historical v1 часть помечена отдельно. |
| current | [Security automation triage](./operations/security-triage.md) | Dependabot/dependency review/CodeQL policy. |
| current | [CI artifact boundary](./operations/ci-artifact-security-boundary.md) | Artifact and token policy; image publication сейчас paused. |
| current | [Browser security headers](./operations/browser-security-headers.md) | Проверяемый response-security contract. |
| current | [No-snapshot purge rehearsal](./operations/no-snapshot-purge-rehearsal.md) | Disposable policy test; не production purge. |
| current | [Production image inventory](./operations/production-image-inventory.md), [release security evidence](./operations/release-security-evidence.md) | Supply-chain contracts; publication сейчас paused. |
| superseded | [Staging runbook](./operations/staging-runbook.md), [release verification](./operations/release-verification-runbook.md), [database recovery](./operations/database-recovery-runbook.md) | Restoration blueprints; local-only policy запрещает их запуск/ожидание. |
| proposed | [Delivery audit](./operations/delivery-audit-2026-08.md) | Исходные delivery recommendations; local-only policy приоритетнее. |
| proposed | [Reset/capacity/offline plan](./operations/v2-reset-capacity-and-offline-plan.md) | Будущие #76/#147 boundaries; не разрешение на destructive work. |
| historical | [GitHub/staging plan](./operations/github-platform-and-staging-plan-2026-08.md) | План и evidence ранее доступного hosted delivery. |

Deployment отсутствует в текущем completion boundary, потому что общий сервер
недоступен и operational workflows fail-closed. Реактивация — отдельная reviewed
infrastructure task; никакие прошлые staging результаты не доказывают текущую
доступность.

## Historical evidence (только чтение)

- **historical:** [Evidence index](./engineering/evidence/README.md) — acceptance
  Epic #74/#75/#76, storage, browser, security и research evidence. Ключевые записи:
  [закрытие #74](./engineering/evidence/epic-74/verification/integrated-main-2026-09-19.md),
  [закрытие #75](./engineering/evidence/epic-75/verification/integrated-main-2026-09-24.md).
- **historical:** [Project review](./reviews/project-review-2026-08.md) и
  [September refinement research](./reviews/product-refinement-2026-09.md) —
  исходные findings; принятые решения перенесены в owner/architecture docs.

## Legacy v1 (не часть маршрута реализации)

Объясняют только старый runtime; точный код сохранён тегом `v1-apache-final` и Git
history. Не переносите из них правила, имена и таблицы.

- **legacy:** [v1 schema](./core-entities-schema.md);
- **legacy:** сервисные описания
  [auth](./services/auth-service.md), [user](./services/user-service.md),
  [core](./services/core-service.md), [media](./services/media-service.md),
  [import](./services/import-service.md), [ai](./services/ai-service.md) и
  [frontend overview](./services/frontend.md);
- **legacy:** [public self-host](./deploy/selfhost-public.md) и
  [model matrix](./deploy/model-matrix.md).

При расхождении prose с executable sources сначала проверяйте build files,
migrations, routes, tests и workflows, затем исправляйте канонический документ —
не создавайте ещё один конкурирующий источник.
