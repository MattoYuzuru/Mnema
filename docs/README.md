# Mnema Docs

Каноническая точка навигации. Документы со статусом **current** описывают существующий checkout; **proposed** требуют решения и ещё не реализованы; **legacy** сохранены только для понимания v1.

## Начать здесь

- **Current:** [Repository guide](./engineering/repository-guide.md) — карта кода, источники истины, команды и маршруты изменений.
- **Current:** [Обзор системы](./system-overview.md) — существующая сервисная топология и возможности.
- **Accepted input:** [Owner decisions](./decisions/owner-decisions-2026-08.md) — зафиксированные ответы, отклонённые варианты и действительно открытые решения.
- **Proposed:** [Source license transition](./decisions/source-license-transition.md) — что остаётся Apache, почему права нельзя отозвать и как отделить hosted v2.
- **Proposed:** [Итоговое ревью проекта](./reviews/project-review-2026-08.md) — новая рекомендация, риски и последовательность работ.
- **Proposed:** [Product direction v2](./product/product-direction-v2.md) — deck-scoped learning, hosted business, roadmap и метрики.
- **Proposed:** [Content and study platform v2](./architecture/content-platform-v2.md) — shared revisions, sparse overlays and study model.
- **Proposed:** [Native content format v2](./architecture/learning-content-format-v2.md) — structured AST, Markdown/editor, rendering, Anki, media and offline boundary.
- **Proposed:** [Exercise catalog v2](./product/exercise-catalog-v2.md) — 30+ mechanics, common attempt contract and P0/P1/P2.
- **Proposed:** [Russia launch economics](./product/russia-launch-economics-2026.md) — market scenarios, T‑Bank, tiers, providers and AI costs.
- **Proposed:** [Russia legal/payment checklist](./product/russia-legal-launch-checklist-2026.md) — ИП/НПД, recurring consent, чеки, privacy, Роскомнадзор и DeepSeek data flow.

## Инженерия и операции

- **Current:** [Capability inventory](./engineering/capability-inventory.yaml) — машиночитаемый каталог команд, workflows и пробелов harness.
- **Current:** [Work item standard](./engineering/work-item-standard.md) — единый человеко- и агентопонятный формат epic, задачи, human action и PR.
- **Current:** [GitHub execution model](./engineering/github-execution-model.md) — Project #4, созданные epics #70–#80, DoR/DoD и commit/PR discipline без новой taxonomy.
- **Proposed:** [V2 delivery plan](./engineering/v2-delivery-plan-2026-08.md) — реалистичный scope на 17–31 августа, parallel lanes и production gates.
- **Proposed:** [Frontend / UX audit](./frontend/experience-audit-2026-08.md) — performance, a11y, Liquid Glass и exercise shell.
- **Proposed:** [Delivery audit](./operations/delivery-audit-2026-08.md) — CI/CD, Kubernetes, recovery и безопасный rollout.
- **Proposed:** [GitHub platform and staging plan](./operations/github-platform-and-staging-plan-2026-08.md) — фактические settings, GitHub Pro/Copilot, test layers, два сервера и P0 delivery epic.
- **Current:** [Staging bootstrap and secret contract](./operations/staging-runbook.md) — namespace/RBAC bootstrap, prefixed GitHub Environment keys, promotion and rollback boundary.
- **Current:** [Release verification runbook](./operations/release-verification-runbook.md) — hosted smoke, safe diagnostics, complete-manifest rollback and staging recovery drill.
- **Proposed:** [V2 reset, capacity and offline plan](./operations/v2-reset-capacity-and-offline-plan.md) — account-only cutover, 1k/10k/100k capacity, scale triggers, offline and MinIO harness.
- **Legacy:** [Схема core-сущностей v1](./core-entities-schema.md) — обзор старой модели; миграции остаются источником истины.

## Развёртывание

Текущие self-host документы описывают v1 и остаются полезны для существующего checkout. Они **не являются launch requirement для hosted v2** и могут быть заморожены/вынесены в отдельный downstream repository после отдельного решения.

- [Self-Hosted Local Deployment](./deploy/selfhost-local.md)
- [Self-Hosted Public Deployment](./deploy/selfhost-public.md)
- [Local AI Model Matrix](./deploy/model-matrix.md)
- [Scripts catalog](../scripts/README.md)

## Текущие сервисы

- [Auth Service](./services/auth-service.md)
- [User Service](./services/user-service.md)
- [Core Service](./services/core-service.md)
- [Media Service](./services/media-service.md)
- [Import Service](./services/import-service.md)
- [AI Service](./services/ai-service.md)
- [Frontend](./services/frontend.md)

При расхождении prose с кодом сверяйтесь с ordered Flyway migrations, `application.properties`, build-файлами и тестами. Исправляйте устаревший документ вместо копирования спорного утверждения в новый.
