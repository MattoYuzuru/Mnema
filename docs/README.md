# Mnema Docs

Каноническая точка навигации. Документы со статусом **current** описывают существующий checkout; **proposed** требуют решения и ещё не реализованы; **legacy** сохранены только для понимания v1.

## Начать здесь

- **Current:** [Repository guide](./engineering/repository-guide.md) — карта кода, источники истины, команды и маршруты изменений.
- **Current:** [Обзор системы](./system-overview.md) — существующая сервисная топология и возможности.
- **Accepted input:** [Owner decisions](./decisions/owner-decisions-2026-08.md) — зафиксированные ответы, отклонённые варианты и действительно открытые решения.
- **Accepted input + proposed defaults:** [Authoring and study workflows](./product/authoring-and-study-workflows.md) — уточнения 6 сентября: личные колоды, drafts/«На потом», проекции, три режима занятий, будущие forks/native/подписки.
- **Reviewed local handoff:** [Refinement report, September 6](./reviews/product-refinement-2026-09.md) — карта результатов, supersession, проверки и оставшиеся engineering gates.
- **Accepted:** [Source license transition](./decisions/source-license-transition.md) — personal-use source-available для новых ревизий и неизменяемая Apache-граница `v1-apache-final`.
- **Proposed, owner-updated:** [Итоговое ревью проекта](./reviews/project-review-2026-08.md) — рекомендация, риски и последовательность greenfield-работ.
- **Proposed:** [Product direction v2](./product/product-direction-v2.md) — deck-scoped learning, hosted business, roadmap и метрики.
- **Proposed:** [Content and study platform v2](./architecture/content-platform-v2.md) — shared revisions, sparse overlays and study model.
- **Proposed:** [Revision storage and runtime boundaries](./architecture/revision-storage-and-runtime-boundaries.md) — блоковое переиспользование, bounded reads, дешёвый fork, workers и объяснение Git-like версий.
- **Proposed:** [Native content format v2](./architecture/learning-content-format-v2.md) — structured AST, Markdown/editor, rendering, Anki, media and offline boundary.
- **Proposed:** [Exercise catalog v2](./product/exercise-catalog-v2.md) — 30+ mechanics, common attempt contract and P0/P1/P2.
- **Proposed:** [Russia launch economics](./product/russia-launch-economics-2026.md) — market scenarios, T‑Bank, tiers, providers and AI costs.
- **Proposed:** [Russia legal/payment checklist](./product/russia-legal-launch-checklist-2026.md) — ИП/НПД, recurring consent, чеки, privacy, Роскомнадзор и DeepSeek data flow.

## Инженерия и операции

- **Current:** [Capability inventory](./engineering/capability-inventory.yaml) — машиночитаемый каталог команд, workflows и пробелов harness.
- **Current:** [Work item standard](./engineering/work-item-standard.md) — единый человеко- и агентопонятный формат epic, задачи, human action и PR.
- **Current:** [GitHub execution model](./engineering/github-execution-model.md) — Project #4, созданные epics #70–#80, DoR/DoD и commit/PR discipline без новой taxonomy.
- **Proposed:** [Greenfield delivery plan](./engineering/v2-delivery-plan-2026-08.md) — границы эпиков, reviewable work items и destructive-cutover gate.
- **Proposed execution slices:** [Подготовка #74](./engineering/epic-74-refinement.md) — два первых spikes, критерии выбора storage/editor, contract gate и parallel content/UI очередь.
- **Superseded direction / retained evidence:** [Frontend / UX audit](./frontend/experience-audit-2026-08.md) — актуальные performance/a11y findings, но прежняя Liquid Glass/Focused Study Desk рекомендация отменена owner decision.
- **Selected direction + prototype:** [Paper UI handoff](./frontend/design-and-experience-2026-09.md) — русский B2C лендинг, античность/индиго, редактор и занятия; [попробовать локальный макет](../design/prototype/README.md).
- **Proposed:** [Delivery audit](./operations/delivery-audit-2026-08.md) — CI/CD, Kubernetes, recovery и безопасный rollout.
- **Proposed:** [GitHub platform and staging plan](./operations/github-platform-and-staging-plan-2026-08.md) — фактические settings, GitHub Pro/Copilot, test layers, два сервера и P0 delivery epic.
- **Current:** [Staging bootstrap and secret contract](./operations/staging-runbook.md) — namespace/RBAC bootstrap, prefixed GitHub Environment keys, promotion and rollback boundary.
- **Current:** [Release verification runbook](./operations/release-verification-runbook.md) — hosted smoke, safe diagnostics, complete-manifest rollback and staging recovery drill.
- **Current:** [Database recovery runbook](./operations/database-recovery-runbook.md) — off-host PostgreSQL backup, pre-migration evidence, isolated restore drill and measured RPO/RTO contract.
- **Current:** [No-snapshot purge rehearsal](./operations/no-snapshot-purge-rehearsal.md) — exact private manifest, fail-closed disposable preflight, one-way purge and absence evidence.
- **Current:** [Security automation triage](./operations/security-triage.md) — baseline, new-regression blocking and expiring private exceptions for Dependabot, dependency review and CodeQL.
- **Current:** [CI artifact security boundary](./operations/ci-artifact-security-boundary.md) — exact upload inventory, pre-upload secret classes and least-privilege workflow tokens.
- **Current:** [Production image inventory](./operations/production-image-inventory.md) — immutable build/runtime pins, production image boundary, update verification and rollback.
- **Proposed:** [Greenfield reset, capacity and offline plan](./operations/v2-reset-capacity-and-offline-plan.md) — account-only cutover без legacy snapshot/rollback, capacity boundaries, offline and MinIO harness.
- **Legacy:** [Схема core-сущностей v1](./core-entities-schema.md) — обзор старой модели; миграции остаются источником истины.

## Развёртывание

Self-host документы описывают v1 и не являются launch requirement для hosted
v2. Текущий local runbook разрешён публичной лицензией только одному физическому
лицу для личного использования. Public/multi-user runbook является архивным и
относится к Apache-срезу `v1-apache-final`; для текущих ревизий такое
развёртывание требует отдельной письменной лицензии.

- [Self-Hosted Local Deployment](./deploy/selfhost-local.md)
- [Self-Hosted Public Deployment](./deploy/selfhost-public.md)
- [Local AI Model Matrix](./deploy/model-matrix.md)

## Runtime guides и исторические service docs

Identity & Account уже объединён в исходниках; Learning API пока foundation shell.
Это не означает, что content/study из legacy core уже перенесены. Приоритет имеют
[Identity & Account guide](../backend/services/identity-account/guide.md) и
[Learning API guide](../backend/services/learning/guide.md). Auth/User и описание
шестисервисной topology ниже — replacement input, не target architecture.

- [Auth Service](./services/auth-service.md)
- [User Service](./services/user-service.md)
- [Core Service](./services/core-service.md)
- [Media Service](./services/media-service.md)
- [Import Service](./services/import-service.md)
- [AI Service](./services/ai-service.md)
- [Frontend](./services/frontend.md)

При расхождении prose с кодом сверяйтесь с ordered Flyway migrations, `application.properties`, build-файлами и тестами. Исправляйте устаревший документ вместо копирования спорного утверждения в новый.
