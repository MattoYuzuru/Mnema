# AI Service (`backend/services/ai`)

## Назначение

`ai` инкапсулирует AI-функциональность Mnema:
- генерация/улучшение карточек;
- AI-импорт контента;
- управление провайдерами;
- quota/usage контроль.

## Главные фичи

- API для запуска AI jobs и отслеживания статуса/результатов;
- AI import (`/imports/preview`, `/imports/generate`);
- поддержка нескольких провайдеров:
  - OpenAI;
  - Gemini;
  - Claude;
  - Qwen;
  - Grok;
  - stub-provider для fallback/dev.
- работа с мультимодальностью: текст, OCR/PDF, аудио/STT/TTS, image/video generation (в зависимости от провайдера);
- управление credential-ами провайдеров;
- квоты токенов и usage ledger.

## Технологии

- Java 21 + Spring Boot 3.5;
- Spring Security Resource Server;
- Spring Data JPA + PostgreSQL + Flyway;
- Spring Cache + Redis;
- OCR/документы: PDFBox, Apache POI, Tess4J;
- фоновые job-worker-ы (`@Scheduled`) с retry/backoff.

## Технические решения и паттерны

- Processor router: единый входной pipeline и маршрутизация на конкретный AI-процессор.
- Provider abstraction: одинаковый жизненный цикл job при разных внешних API.
- Job orchestration в БД (очередь, статусы, попытки, lock TTL).
- Usage accounting:
  - ledger по потреблению;
  - периодические quota окна с токен-лимитами.
- Секреты провайдеров вынесены в `SecretVault` абстракцию.

## Связь с другими сервисами

- Валидирует JWT от `auth`.
- Использует `core` как источник/приемник карточек и шаблонов.
- Использует `media` для загрузки/резолва AI-сгенерированных ассетов.
- Вызывается фронтендом из AI-модалок и AI import workflow.

