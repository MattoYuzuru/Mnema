# Mnema

Mnema — greenfield-платформа обучения вокруг версионируемых `LearningItem`,
разных типов упражнений и объяснимого spaced practice. Репозиторий находится в
прямой замене v1: compatibility API, `/v2`, dual read/write и legacy fallback не
являются требованиями.

## Текущее состояние

После завершения [Epic #74](https://github.com/MattoYuzuru/Mnema/issues/74)
replacement runtime содержит:

- единый Spring Boot runtime **Identity & Account** для аккаунтов, OAuth/OIDC,
  сессий, профиля и account lifecycle;
- Spring Boot **Learning API** с приватными Deck, deck-local LearningItem,
  immutable revisions, native content, EditingDraft и CaptureNote;
- Angular 22 SPA с paper/antiquity/indigo интерфейсом создания колоды,
  Capture, редактора, явной публикации и Browse;
- PostgreSQL 18 integration tests, real Identity/Learning security composition и
  локальный HTTPS browser harness для authoring-пути.

Модули `core`, `media`, `import` и `ai`, а также часть старых frontend-компонентов
остаются в дереве как legacy replacement input. Они не задают архитектуру новой
Study-системы и удаляются только в границах
[#146](https://github.com/MattoYuzuru/Mnema/issues/146) после готовности #74–#76.

Следующий продуктовый этап —
[Epic #75: deck-scoped Study, M:N exercises и новый scheduler](https://github.com/MattoYuzuru/Mnema/issues/75).
Его реализация ещё не начата; Epic остаётся в Backlog до отдельного refinement и
разбиения на reviewable задачи.

## С чего начать

1. Прочитайте [`AGENTS.md`](AGENTS.md) — это нормативные правила разработки.
2. Откройте [каноническую навигацию](docs/README.md).
3. Используйте [обзор текущей системы](docs/system-overview.md) и
   [карту репозитория](docs/engineering/repository-guide.md).
4. Перед Issue или PR следуйте
   [work item standard](docs/engineering/work-item-standard.md).

## Быстрые проверки

Требуются JDK 21, Node 22.23.2, npm, Chrome/Chromium и Docker для fail-closed
PostgreSQL/Testcontainers проверок. Для Colima сначала примените socket environment
из [repository guide](docs/engineering/repository-guide.md#полный-quality-gate).

```bash
cd backend
./gradlew clean quality

cd ../frontend
npm ci
npm run lint
npm run test
npm run build

cd ..
python3 scripts/verify_docs.py
python3 -m unittest discover -s scripts/tests -p 'test_verify_*.py' -v
```

Это не весь repository gate: security, browser, backup, purge и release-contract
проверки перечислены в
[repository guide](docs/engineering/repository-guide.md#полный-quality-gate) и
исполняются PR workflow. Coverage thresholds и container-backed tests являются
обязательными.

## Локальный runtime и delivery

Persistent local launcher поднимает PostgreSQL 18, Identity & Account, Learning и
production Angular frontend с локальным HTTPS и same-origin `/api`; обычный restart
сохраняет данные. Безопасный bootstrap, trust локального CA, start/stop/smoke/reset
описаны в [local replacement runtime](docs/deploy/selfhost-local.md). Smoke проверяет
реальные auth, authoring, scheduled Study, progress, restart, replay и practice API.
Backend-only
`docker-compose.yml` остаётся maintenance-контуром.

Общий сервер недоступен. Текущая граница готовности:

```text
feature branch → полный local gate → hosted PR checks → protected squash → main checks
```

Staging, production, SSH, rollout и recovery не входят в local delivery и не
заявляются как выполненные. Каноническая политика —
[Delivery without hosted infrastructure](docs/operations/local-development-delivery.md).

## Лицензия и участие

Текущие ревизии распространяются по
[Mnema Source-Available License 1.0](LICENSE). Условия участия — в
[`CONTRIBUTING.md`](CONTRIBUTING.md), security reporting — в
[`SECURITY.md`](SECURITY.md). Последний Apache 2.0-срез сохранён в теге
[`v1-apache-final`](https://github.com/MattoYuzuru/Mnema/tree/v1-apache-final).
