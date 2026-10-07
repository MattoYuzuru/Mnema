---
artifact:
  id: documentation-navigator
  type: navigator
  title: "Mnema documentation"
  status: current
  updated_at: "2026-10-07"
  owners: ["project-owner"]
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

Начните с корневого [`AGENTS.md`](../AGENTS.md) и scoped-инструкции
[`backend/AGENTS.md`](../backend/AGENTS.md) или [`frontend/AGENTS.md`](../frontend/AGENTS.md).
Далее читайте только маршрут своей задачи; evidence и research нужны для конкретного
решения или предела приёмки, а не для каждого входа в репозиторий.

| Задача | Первый источник |
|---|---|
| Найти правило или термин | [Domain truth map](./engineering/domain-truth-map.md) |
| Запустить, проверить, открыть Style Guide | [Agent runbook](./engineering/agent-runbook.md) |
| Найти модуль, версию, owning tests или полный gate | [Repository guide](./engineering/repository-guide.md) |
| Изменить Study | [Study contract](../contracts/study/README.md); продукт — [exercise catalog](./product/exercise-catalog-v2.md) |
| Изменить authoring/content | [Learning guide](../backend/services/learning/guide.md) и [native content contract](../contracts/content/native-v1/README.md) |
| Изменить media | Раздел [Media](#media-epic-76) ниже |
| Изменить AI | [Generation](../contracts/generation/README.md), [speech](../contracts/speech/README.md), [usage](../contracts/usage/README.md), [notifications](../contracts/notifications/README.md); смысл — [product contract](./product/ai-layer-2026-10.md) |
| GitHub Issue/PR | [Work item standard](./engineering/work-item-standard.md) |
| Публикация или deployment | [Production delivery](./operations/production-delivery.md), только в scope соответствующего поручения |

## Agent-facing engineering files

| Статус | Документ | Назначение |
|---|---|---|
| current | [Claude Code adapter](../CLAUDE.md) | Импортирует `AGENTS.md` и ведёт к тем же scoped-правилам; не отдельная политика. |
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
| accepted | [AI layer contract](./product/ai-layer-2026-10.md) | Принятые решения AI-слоя: слои, сценарии, UX, usage, тарифы, промокоды, legal gates. |
| proposed | [Product direction](./product/product-direction-v2.md) | Product hypotheses, roadmap и метрики. |
| proposed | [Legal/payment checklist](./product/russia-legal-launch-checklist-2026.md) | Текущий статус РКН/эквайринга и оставшиеся human gates; не юридическая гарантия. |

## Architecture и backend

| Статус | Документ | Назначение |
|---|---|---|
| current | [System overview](./system-overview.md) | Replacement topology и legacy boundary. |
| accepted | [Content and Study platform](./architecture/content-platform-v2.md) | Общая модель; P0 content/Study реализованы, P1/P2 — границы будущей работы. |
| accepted | [Native content format](./architecture/learning-content-format-v2.md) | Persisted native document contract. |
| accepted | [AI generation platform](./architecture/ai-generation-platform.md) | Принятая архитектура AI-слоя: модули в Learning, Мастерская, MBM, провайдеры, usage, уведомления, безопасность. |
| current | [Revision storage and runtime boundaries](./architecture/revision-storage-and-runtime-boundaries.md) | Выбранное и реализованное storage-направление. |
| current | [Counted-page contract](./architecture/counted-page-contract.md) | Counted-page/structural invariants. |
| current | [Identity & Account guide](../backend/services/identity-account/guide.md) | Identity runtime. |
| current | [Learning API guide](../backend/services/learning/guide.md) | Content, exercise, Study и media runtime. |
| current | [Learning runtime policy index](./engineering/runtime-policy-index.md) | Реестр runtime policies Learning. |
| current | Контракты: [study](../contracts/study/README.md), [authoring](../contracts/authoring/README.md), [decks](../contracts/decks/README.md), [items](../contracts/items/README.md), [native content](../contracts/content/native-v1/README.md) | Общие fixtures backend/frontend. |
| accepted | AI-контракты: [generation](../contracts/generation/README.md) (состояния, HTTP, события, ошибки, [MBM v1](../contracts/generation/mbm-v1/README.md), [упражнения](../contracts/generation/exercises/README.md)), [usage](../contracts/usage/README.md), [notifications](../contracts/notifications/README.md), [speech input](../contracts/speech/README.md) и [prompt library](../backend/services/learning/src/main/resources/ai/prompts/README.md) | Исполняемые fixtures для реализованных AI/Workshop, speech, usage/plans/promo и notifications; доступность функций задаёт runtime-конфигурация. |
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
| current | [Frontend styleguide](./frontend/styleguide.md) | Живой каталог `/styleguide` (только dev-сборка): правила «бери из стайлгайда», как открыть, карта разделов. |
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
| accepted | [Epic #77 refinement](./engineering/epic-77-refinement.md) | AI-слой: delivery slices, пять прогонов и acceptance эпика. |
| current | [Epic #77 prompts](./engineering/prompts/epic-77-ai-layer.md) | Промпты последовательных прогонов агента для реактивированного AI-эпика. |
| historical | [Epic #74 refinement](./engineering/epic-74-refinement.md), [dependency decisions](./engineering/epic-74-dependency-decisions.md), [hardware handoff](./engineering/epic-74-hardware-handoff.md) | Выполненный план, принятые зависимости и session handoff #74. |
| historical | [GitHub execution model](./engineering/github-execution-model.md) | Исходная настройка Project #4; текущие статусы читаются из GitHub. |
| proposed | [Greenfield delivery plan](./engineering/v2-delivery-plan-2026-08.md) | Sequencing proposal; GitHub state и этот navigator приоритетнее. |

## Operations

| Статус | Документ | Назначение |
|---|---|---|
| current | [Production delivery](./operations/production-delivery.md), [VPS runtime](./operations/vps-runtime.md), [image publication](./operations/vps-image-publication.md) | Работающий production, publication/admission/deploy и verification. |
| current | [Persistent local runtime](./deploy/selfhost-local.md) | HTTPS-запуск Identity, Learning, Angular, media и Colima clock; historical v1 часть помечена отдельно. |
| current | [Security automation triage](./operations/security-triage.md) | Dependabot/dependency review/CodeQL policy. |
| current | [CI artifact boundary](./operations/ci-artifact-security-boundary.md) | Artifact and token policy для ручной публикации четырёх образов. |
| current | [AI operations runbook](./operations/ai-runbook.md) | Роли api/worker, ключи только на worker, kill switches и бюджеты, метрики на отдельном порту, инциденты провайдеров, хранение данных, eval-gate. |
| current | [AI egress proxy](./operations/ai-egress-proxy.md) | Stateless Squid CONNECT proxy в Финляндии для AI-провайдеров, недоступных из РФ; kill switch и fallback. |
| current | [Browser security headers](./operations/browser-security-headers.md) | Проверяемый response-security contract. |
| current | [No-snapshot purge rehearsal](./operations/no-snapshot-purge-rehearsal.md) | Disposable policy test; не production purge. |
| current | [Production image inventory](./operations/production-image-inventory.md), [release security evidence](./operations/release-security-evidence.md) | Пины и supply-chain evidence четырёх VPS images. |
| superseded | [Staging runbook](./operations/staging-runbook.md), [release verification](./operations/release-verification-runbook.md), [database recovery](./operations/database-recovery-runbook.md) | Legacy Kubernetes reference; текущая поставка — через VPS workflow. |
| historical | [Delivery audit](./operations/delivery-audit-2026-08.md) | Kubernetes audit и прежняя cutover-граница; findings проверяются заново на текущем VPS runtime. |
| proposed | [Reset/capacity/offline plan](./operations/v2-reset-capacity-and-offline-plan.md) | Остаточные purge/capacity/offline решения с legacy inventory; текущий VPS сохраняет старые данные. |
| historical | [GitHub/staging plan](./operations/github-platform-and-staging-plan-2026-08.md) | План и evidence ранее доступного hosted delivery. |

Production работает на RU VPS; deployment доступен через manual publication и
protected VPS workflow. [Runtime](./operations/vps-runtime.md) фиксирует текущее
состояние и проверку; [publication](./operations/vps-image-publication.md) — candidate
и security evidence; [dispatcher](../deploy/production/README.md) — admission.

## Historical evidence (только чтение)

- **historical:** [Evidence index](./engineering/evidence/README.md) — acceptance
  Epic #74/#75/#76, storage, browser, security и research evidence. Ключевые записи:
  [закрытие #74](./engineering/evidence/epic-74/verification/integrated-main-2026-09-19.md),
  [закрытие #75](./engineering/evidence/epic-75/verification/integrated-main-2026-09-24.md).
- **historical:** [AI layer research 2026-10](./reviews/ai-layer-research-2026-10/README.md) —
  исследования архитектуры, экономики, UX, контекста и платформы; принятые
  решения перенесены в product/architecture docs.
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
