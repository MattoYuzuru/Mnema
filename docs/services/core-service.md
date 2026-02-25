# Core Service (`backend/services/core`)

## Назначение

`core` — центральный домен Mnema. Здесь живут:
- колоды (личные и публичные);
- карточки;
- шаблоны карточек и версии шаблонов;
- review-сессии и статистика;
- поисковые endpoint-ы по ключевым сущностям.

## Главные фичи

- управление пользовательскими и публичными колодами;
- форки публичных колод;
- версионирование колод (`public_decks.version`) и sync пользовательских копий;
- sync шаблонов (`sync-template`) и template versions;
- продвинутые операции над карточками:
  - поиск дубликатов;
  - анализ missing fields;
  - пакетные операции;
- review API:
  - next card;
  - submit answer;
  - выбор/смена алгоритма;
  - импорт состояний;
- review summary/stats (daily/hourly/quality metrics);
- поиск по колодам, карточкам, шаблонам.

## Технологии

- Java 21 + Spring Boot 3.5;
- Spring Modulith (модульный монолит внутри сервиса);
- Spring Security Resource Server;
- Spring Data JPA + PostgreSQL + Flyway;
- Redis cache;
- Actuator + Prometheus;
- springdoc/OpenAPI.

## Технические решения и архитектурные паттерны

- Модульный монолит (deck/review/media adapter), что даёт чёткие boundaries без избыточной сетевой нарезки.
- Версионирование доменных сущностей:
  - immutable версии публичных колод;
  - отдельные версии шаблонов (`card_template_versions`).
- Алгоритмический слой review как стратегия:
  - единый интерфейс `SrsAlgorithm`;
  - реализации `SM2`, `FSRS v6`, `HLR`;
  - `AlgorithmRegistry` для переключения.
- Буферизация обновлений конфигурации алгоритма (`DeckAlgorithmUpdateBuffer`) для снижения write-churn.
- Защита sync-процессов через checksum, optimistic locking и `deck_update_sessions`.

## Связь с другими сервисами

- Валидирует JWT от `auth`.
- Резолвит media URL через `media` (иконки, контент карточек).
- Принимает данные от `import` после обработки импортов.
- Используется `ai` для чтения/изменения карточек/колод при AI-пайплайнах.
- Является основным API для фронтенда по учебному домену.

