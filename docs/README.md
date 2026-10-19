---
artifact:
  id: documentation-navigator
  type: navigator
  title: "Mnema documentation"
  status: current
  updated_at: "2026-10-19"
  owners: ["project-owner"]
---

# Mnema Docs

Это единственная каноническая точка навигации. Статусы означают:
`current` — реализовано, описывает фактический checkout/runtime; `accepted` — принятое
решение или input, реализация неполна или не предполагается; `proposed` — требует
refinement или решения, не реализовано; `historical` — доказательство или решение, оставленное
потому, что на него ссылается канонический документ; `superseded` — заменено указанным
источником. Журналов задач, промптов сессий и backlog здесь нет: статус эпиков и задач живёт
в GitHub Issues и на доске «Mnema Kanban». Старый v1-код — только в теге
[`v1-apache-final`](https://github.com/MattoYuzuru/Mnema/tree/v1-apache-final) и в Git history.
Каждый `current`/`accepted` документ ниже доступен не более чем за два перехода от этой страницы.

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
| Улучшить core обучения | [Исследование, два эпика и entrypoint](./engineering/learning-core/README.md) (`proposed`, не реализовано и не действующий контракт) |
| Онбординг: «Начало пути», «Путь Mnema», ритм | [Пакет онбординга](./engineering/onboarding/README.md): реализован и включён в production с 2026-10-19 (Путь и ритм включены; учебная колода выключена до шаблона владельца; награда выключена); wire — [`contracts/onboarding`](../contracts/onboarding/README.md) |
| Изменить authoring/content | [Learning guide](../backend/services/learning/guide.md) и [native content contract](../contracts/content/native-v1/README.md) |
| Изменить media | Раздел [Media](#media-epic-76) ниже |
| Колоды сообщества: доступ, публикация, копии, обновления, каталог, рекомендации, жалобы | [Product contract](./product/community-decks.md), затем [architecture](./architecture/community-decks.md) и разделы в [Learning guide](../backend/services/learning/guide.md); выключатели — [runtime policy index](./engineering/runtime-policy-index.md) |
| Изменить AI | [Generation](../contracts/generation/README.md), [speech](../contracts/speech/README.md), [usage](../contracts/usage/README.md), [notifications](../contracts/notifications/README.md); смысл — [product contract](./product/ai-layer-2026-10.md) |
| GitHub Issue/PR | [Work item standard](./engineering/work-item-standard.md) |
| Публикация или deployment | [Production delivery](./operations/production-delivery.md), только в scope соответствующего поручения |

## Agent-facing engineering files

| Статус | Документ | Назначение |
|---|---|---|
| current | [Claude Code adapter](../CLAUDE.md) | Импортирует `AGENTS.md` и ведёт к тем же scoped-правилам; не отдельная политика. |
| current | [Engineering standards](./engineering/engineering-standards.md) | Полная формулировка общих правил (research, UX/a11y, backend, frontend, tests, TODO, нагрузка и styleguide-first, формат ответа); краткая форма — в `AGENTS.md`. |
| current | [Domain truth map](./engineering/domain-truth-map.md) | Источник истины по доменным вопросам (включая онбординг и колоды сообщества) и глоссарий. |
| current | [Agent runbook](./engineering/agent-runbook.md) | Команды и настройка рабочей машины с пометкой verified/not re-run. |

## Product

| Статус | Документ | Назначение |
|---|---|---|
| accepted | [Owner decisions](./decisions/owner-decisions-2026-08.md) | Решения владельца и явно открытые вопросы. |
| accepted | [Source license transition](./decisions/source-license-transition.md) | Решение о переходе лицензии исходного кода. |
| accepted | [Authoring and Study workflows](./product/authoring-and-study-workflows.md) | Product contract для authoring, Capture, exercises и режимов практики. |
| accepted | [Exercise catalog](./product/exercise-catalog-v2.md) | Механики, attempt/evidence, reducer и retention boundaries. |
| accepted | [UX refinement 2026-09-29](./product/ux-improvements-2026-09-29.md) | Приёмочные сценарии доработки редактора, упражнений и основных экранов. |
| accepted | [Final polish 2026-09-29](./product/final-polish-2026-09-29.md) | Удаление, заметки «На потом», пропуски и режимы проверки текста. |
| accepted | [Owner console](./product/admin-console.md) | Кабинет владельца: пользователи, расходы/usage, промокоды и кампании, новости, поддержка, жалобы, полка сообщества, отчёт онбординга. |
| accepted | [AI layer contract](./product/ai-layer-2026-10.md) | Принятые решения AI-слоя (допущен в production 2026-10-15/16; Issue #77 открыт для остального): слои, сценарии, UX, usage, тарифы, промокоды, legal gates. |
| proposed | [Product direction](./product/product-direction-v2.md) | Product hypotheses, roadmap и метрики. |
| proposed | [Legal/payment checklist](./product/russia-legal-launch-checklist-2026.md) | Текущий статус РКН/эквайринга и оставшиеся human gates; не юридическая гарантия. |
| current | [Колоды сообщества](./product/community-decks.md) | Доступ, публикация, копии и обновления, «Сообщество», поиск, профили, рекомендации и правила. Реализовано; каталог, запись событий и «Для вас» включены в production с политикой 1.6 (2026-10-15), автоприём обновлений выключен. |

## Architecture и backend

| Статус | Документ | Назначение |
|---|---|---|
| accepted | [Owner console architecture](./architecture/admin-console.md) | Owner boundary, точность отчётности, admin-host и приватный support bridge; [admin contract](../contracts/admin/README.md). |
| current | [Public events and feedback](./architecture/public-updates-and-feedback.md) | События, редактор владельца, Telegram inbox и исследованная граница подтверждения аккаунта. |
| current | [System overview](./system-overview.md) | Топология production, компоненты и границы. |
| accepted | [Content and Study platform](./architecture/content-platform-v2.md) | Общая модель; реализованы content/Study, медиа, AI-слой, колоды сообщества; отдельные границы будущей работы названы в тексте. |
| accepted | [Native content format](./architecture/learning-content-format-v2.md) | Persisted native document contract. |
| accepted | [AI generation platform](./architecture/ai-generation-platform.md) | Архитектура AI-слоя: модули в Learning, Мастерская, MBM, провайдеры, usage, уведомления, безопасность. |
| current | [Community decks architecture](./architecture/community-decks.md) | Линия ревизий, DeckAccess, явная публикация, копии O(1), обновления, медиа для чужих, каталог/поиск/ранг, нагрузка 0 и высокий MAU. Таблица состояния реализации по частям. |
| current | [Revision storage and runtime boundaries](./architecture/revision-storage-and-runtime-boundaries.md) | Выбранное и реализованное storage-направление. |
| current | [Counted-page contract](./architecture/counted-page-contract.md) | Counted-page/structural invariants. |
| current | [Identity & Account guide](../backend/services/identity-account/guide.md) | Identity runtime. |
| current | [Learning API guide](../backend/services/learning/guide.md) | Content, exercise, Study и media runtime. |
| current | [Learning runtime policy index](./engineering/runtime-policy-index.md) | Реестр runtime policies Learning, включая выключатели, лимиты и метрики колод сообщества с значениями в production. |
| current | Контракты: [study](../contracts/study/README.md), [authoring](../contracts/authoring/README.md), [decks](../contracts/decks/README.md) (метаданные, публичное чтение, публикация, доступ, копии, превью), [community](../contracts/community/README.md) (каталог, события, интересы, «Для вас»), [moderation](../contracts/moderation/README.md) (жалобы и снятие), [onboarding](../contracts/onboarding/README.md) (тур, «Путь», ритм, награда), [identity](../contracts/identity/README.md) (публичный профиль), [events](../contracts/events/README.md), [admin](../contracts/admin/README.md), [items](../contracts/items/README.md), [native content](../contracts/content/native-v1/README.md) | Общие fixtures backend/frontend. |
| accepted | AI-контракты: [generation](../contracts/generation/README.md) (состояния, HTTP, события, ошибки, [MBM v1](../contracts/generation/mbm-v1/README.md), [упражнения](../contracts/generation/exercises/README.md)), [usage](../contracts/usage/README.md), [billing](../contracts/billing/README.md), [notifications](../contracts/notifications/README.md), [speech input](../contracts/speech/README.md) и [prompt library](../backend/services/learning/src/main/resources/ai/prompts/README.md) | Исполняемые fixtures для реализованных AI/Workshop, speech, usage/plans/promo, billing (T-Bank checkout и уведомления) и notifications; доступность функций задаёт runtime-конфигурация. |
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
| historical | [Epic #76 acceptance evidence](./engineering/evidence/epic-76/integrated-browser/README.md) | Локальный browser/Study run; ещё [worker](./engineering/evidence/epic-76/phone-worker.md) и [visual](./engineering/evidence/epic-76/media-visual/README.md). Остальное evidence — по [правилу](./engineering/evidence/README.md). |

## Frontend

| Статус | Документ | Назначение |
|---|---|---|
| current | [Mnema brand and UI contract](./frontend/mnema-brand-and-ui-contract.md) | Правила оформления и проверки изменений в действующем Angular UI. |
| accepted | [Owner console UX](./frontend/admin-console-ux.md) | Фильтры, карточка пользователя, источник каждого показателя, тикеты/доставка и styleguide reuse. |
| current | [Frontend styleguide](./frontend/styleguide.md) | Живой каталог `/styleguide` (только dev-сборка): правила «бери из стайлгайда», как открыть, карта разделов. |
| accepted | [Design and experience](./frontend/design-and-experience-2026-09.md) | Выбранное paper/antiquity/indigo направление и a11y boundaries. |
| historical | [Interactive prototype](../design/prototype/README.md) | Design evidence, не production architecture. |

## Engineering и quality

| Статус | Документ | Назначение |
|---|---|---|
| current | [Repository guide](./engineering/repository-guide.md) | Карта кода, версии, change routes и gate. |
| current | [Work item standard](./engineering/work-item-standard.md) | Issue/PR/Project status contract. |
| proposed | [Learning core improvement backlog](./engineering/learning-core/README.md) | Научная критика, current-source map, два ограниченных эпика, task bodies и migration; не реализовано, задачи — Issues #503–#516. |
| current | [Onboarding package](./engineering/onboarding/README.md) | Реализовано и включено в production с 2026-10-19: что включено, proposal (поведение), architecture (устройство), пять ADR; wire — [`contracts/onboarding`](../contracts/onboarding/README.md). |
| current | [Capability inventory](./engineering/capability-inventory.yaml) | Машиночитаемый список команд и harnesses; при расхождении приоритетнее runbook и `--help`. |
| current | [Evidence index](./engineering/evidence/README.md) | Правило: evidence живёт в PR и Issue; в репозитории остаётся только то, на что ссылаются документы, контракты и тесты. |
| accepted | [Epic #75 refinement](./engineering/epic-75-refinement.md) | Принятые решения и реализованные delivery slices. |
| accepted | [Epic #77 refinement](./engineering/epic-77-refinement.md) | AI-слой: delivery slices, принятые отличия от плана и acceptance эпика; статус задач — в Issues. |
| historical | [Epic #74 dependency decisions](./engineering/epic-74-dependency-decisions.md) | Принятые зависимости и ответы владельца для хранилища и редактора; на него ссылается [архитектура хранения](./architecture/revision-storage-and-runtime-boundaries.md). |

## Operations

| Статус | Документ | Назначение |
|---|---|---|
| current | [Production delivery](./operations/production-delivery.md), [VPS runtime](./operations/vps-runtime.md), [image publication](./operations/vps-image-publication.md) | Работающий production, publication/admission/deploy и verification. |
| current | [Identity abuse protection](./operations/turnstile.md) | Production password sign-in и registration проходят Cloudflare Turnstile (`required`); `blocked` — kill switch, OAuth не затрагивается. Публичные политика ПДн и соглашение публикуются из frontend; статус уведомлений — в [legal checklist](./product/russia-legal-launch-checklist-2026.md). |
| current | [Persistent local runtime](./deploy/selfhost-local.md) | HTTPS-запуск Identity, Learning, Angular, media и Colima clock. |
| current | [Security automation triage](./operations/security-triage.md) | Dependabot/dependency review/CodeQL policy. |
| current | [CI artifact boundary](./operations/ci-artifact-security-boundary.md) | Artifact and token policy для ручной публикации четырёх образов. |
| current | [AI operations runbook](./operations/ai-runbook.md) | Роли api/worker, ключи только на worker, kill switches и бюджеты, метрики на отдельном порту, инциденты провайдеров, хранение данных, eval-gate. |
| current | [AI egress proxy](./operations/ai-egress-proxy.md) | Stateless Squid CONNECT proxy в Финляндии для AI-провайдеров, недоступных из РФ; kill switch и fallback. |
| current | [Browser security headers](./operations/browser-security-headers.md) | Проверяемый response-security contract. |
| current | [Link previews](./operations/link-previews.md) | OG-превью `/d/*` без SSR: nginx SSI + `GET /api/public/meta/…` Learning, три конфигурации web-сервера, порядок установки. |
| current | [Production image inventory](./operations/production-image-inventory.md), [release security evidence](./operations/release-security-evidence.md) | Пины и supply-chain evidence четырёх VPS images. |
| proposed | [Reset/capacity/offline plan](./operations/v2-reset-capacity-and-offline-plan.md) | Остаточные purge/capacity/offline решения с legacy inventory; текущий VPS сохраняет старые данные. |

Production работает на RU VPS; deployment идёт через Main CI и protected VPS workflow.
Kubernetes-доставка (staging/production workflows, `k8s/`, recovery и purge tooling) удалена;
её точное состояние сохранено в Git history, а старых сервисов — ещё и в теге `v1-apache-final`. [Runtime](./operations/vps-runtime.md) фиксирует текущее
состояние и проверку; [publication](./operations/vps-image-publication.md) — candidate
и security evidence; [dispatcher](../deploy/production/README.md) — admission.

## Evidence и исследования (только чтение)

Evidence конкретных изменений лежит в PR и Issues. В репозитории остаётся то, на что ссылаются
канонические документы, контракты и тесты — [правило и список](./engineering/evidence/README.md).
Исследования хранятся ради решений, которые на них ссылаются:

- **historical:** [Community decks RFC](./reviews/community-decks-2026-10/rfc.md) — факты кода, варианты, отклонённые решения и ответы владельца 2026-10-10; поведение задают [продуктовый контракт](./product/community-decks.md) и [архитектура](./architecture/community-decks.md).
- **historical:** [AI layer research 2026-10](./reviews/ai-layer-research-2026-10/README.md) — отчёты по UX, контексту и качеству, платформе и речи; принятые решения перенесены в product/architecture docs.

При расхождении prose с executable sources сначала проверяйте build files,
migrations, routes, tests и workflows, затем исправляйте канонический документ —
не создавайте ещё один конкурирующий источник.
