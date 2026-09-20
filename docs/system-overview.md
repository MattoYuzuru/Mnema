---
artifact:
  id: system-overview
  type: architecture-overview
  title: "Mnema current system overview"
  status: current
  updated_at: "2026-09-20"
  owners: ["project-owner"]
  evidence_revision: "933da3e60add2102ed7480342dfd3de95a8a255b"
---

# Mnema: текущий обзор системы

Mnema напрямую заменяет v1 платформой вокруг versioned `LearningItem`. После Epic
#74 канонический authoring runtime уже находится в `identity-account`, `learning` и
Angular SPA. Epic #75 добавил objective/exercise authoring, bounded Study session
snapshots, deterministic attempts, baseline scheduler state и production exercise
inspector; Study runner, progress и remaining P0 adapters ещё в работе. Media
lifecycle относится к #76.

## Shipping и local replacement boundary

```text
Angular 22 SPA
  ├── OAuth 2.1/OIDC + account API ──> Identity & Account ─┐
  └── private authoring API ─────────> Learning API ──────┼─> PostgreSQL 18
                                      │                   │
                                      └─ validates token + active account via Identity
```

- `services:identity-account` владеет account identity, credentials, browser
  sessions, OAuth/OIDC grants, federation, profile/moderation, account avatar,
  transfer и deletion lifecycle.
- `services:learning` владеет свежей `app_learning` migration history, platform
  contracts, immutable storage и личным content/authoring доменом.
- Angular SPA использует standalone components и lazy routes. Канонический #74 flow:
  Deck → Capture/«На потом» → EditingDraft → явная публикация → Browse.
- `docker-compose.yml` запускает только PostgreSQL, Identity & Account и Learning.
  Frontend собирается и тестируется отдельно; compose — backend maintenance runtime,
  а не полный deployed product.

У replacement нет `/v2`, aliases к v1, dual write/read или scheduler fallback.
Identity и Learning — отдельные deployables без Gradle dependency на legacy modules.

## Что реализовано в Learning

- private Deck create/list/read/metadata update с owner ACL, CAS и idempotency;
- deck-local LearningItem `(deckId, memberKey)`, immutable revisions и atomic
  publication;
- Mnema-owned native document v1, безопасный renderer contract, immutable
  block/page storage и counted-page edits;
- acknowledged server `EditingDraft` и durable `CaptureNote` с idempotent conversion;
- immutable `MemoryObjective`/Exercise revisions и bounded owner-scoped
  `SCHEDULED`/`REPLAY`/`PRACTICE` session snapshots;
- deterministic typed/self-check attempts, durable evidence, versioned baseline
  reducer и explicit material restart без удаления истории;
- UUID, canonical JSON, command receipts, RFC 9457 Problem Details, row-version CAS;
- bearer scope enforcement и fail-closed current-account validation через Identity.

В Learning пока нет progress projection, cloze/choice adapters и полной
due/new/practice selection policy. Они остаются следующими slices #75; session
закрепляет reducer/config identity и immutable presentations, а scheduled attempt
атомарно пишет одну transition только assessed objective.

## Frontend boundary

Replacement routes `/decks`, `/decks/:deckId`, deck-scoped
`/decks/:deckId/materials/...`, `/decks/:deckId/capture` и editor реализуют выбранное
paper/antiquity/indigo направление. Отдельный lazy exercise inspector позволяет
выбрать актуальные node projections или короткий prompt, создать/переиспользовать/
изменить одну явную objective, настроить четыре P0 mechanics и preview без работы с
UUID/JSON. Native editor state не является persisted format; frontend валидирует
серверные envelopes и ETag/command contracts.

В исходниках всё ещё есть legacy components/services для public decks, old review,
templates, import, media и AI. Их наличие не делает поведение текущим и не разрешает
переиспользовать old card/template/scheduler boundaries в #75. Runtime-wide removal
остаётся задачей #146.

## Legacy build boundary

Gradle graph всё ещё содержит `core`, `media`, `import` и `ai`, чтобы полный gate
проверял не удалённый пока код. Старые `auth` и `user` modules уже заменены единым
Identity & Account. Legacy migrations и service docs сохраняются как evidence до
#146/#147; они не запускаются Learning и не являются rollback architecture.

## Проверка

- backend: compilation, unit/integration tests и per-service coverage floor;
- frontend: lint, component/protocol tests и production build;
- real PostgreSQL tests, Identity↔Learning black-box security/cancellation harness;
- real local HTTPS browser Identity/authoring harness;
- repository policy, security, release-contract, backup/recovery and disposable
  purge-rehearsal checks;
- deterministic internal Markdown link/status validation.

Точные команды и платформы: [Repository guide](./engineering/repository-guide.md).
Acceptance #74: [integrated main evidence](./engineering/evidence/epic-74/verification/integrated-main-2026-09-19.md).

## Delivery boundary

Общий сервер недоступен. Готовность заканчивается защищённым squash merge и
проверкой `main`; staging, SSH, deployment, recovery и production verification не
выполняются и не ожидаются. См.
[local development delivery](./operations/local-development-delivery.md).

## Следующие этапы

1. #75 — refinement и реализация deck-scoped Study, exercises/evidence и scheduler.
2. #76 — отдельный greenfield media lifecycle.
3. #146 — удаление оставшегося legacy runtime/build wiring после #74–#76.
4. #147 — отдельный production cutover/purge gate; сейчас не разрешён и не готов.
