# Mnema: обзор системы

## Что получает пользователь

Mnema — это платформа для интервального обучения на карточках с акцентом на практический workflow, а не только на «карточки и кнопки Again/Good».

Ключевые пользовательские фичи:
- создание личных и публичных колод;
- версионирование колод и шаблонов с последующей синхронизацией;
- гибкие шаблоны полей карточек (включая markdown и медиа);
- продвинутый review-режим с несколькими SRS-алгоритмами;
- импорт из APKG/CSV/TSV/TXT с превью и маппингом полей;
- экспорт колод;
- AI-генерация/улучшение контента карточек и AI-импорт;
- мультимедиа (изображения, аудио, видео, аватары, иконки) с безопасной загрузкой;
- OAuth2 + локальная авторизация.

## Чем Mnema выделяется на фоне Anki/Quizlet

- Версионируемые публичные колоды и шаблоны: можно обновлять источник и аккуратно синхронизировать пользовательские копии.
- Гибридный подход к SRS: поддержка нескольких алгоритмов (`SM2`, `FSRS v6`, `HLR`) и настройка параметров на уровне колоды.
- Встроенный pipeline импорта: превью, маппинг, фоновые job-ы и перенос части review-прогресса.
- AI как часть домена, а не внешний скрипт: отдельный сервис с квотами, usage ledger, воркером и несколькими AI-провайдерами.
- Отдельный media-сервис с S3 presigned/multipart upload, что лучше масштабируется для больших вложений.

## Архитектура верхнего уровня

Mnema — сервисно-модульная система:
- backend: набор Spring Boot сервисов;
- frontend: Angular SPA (standalone components, маршрутизация, интеграция со всеми API);
- инфраструктура: PostgreSQL (схемы по сервисам), Redis (кэши), S3-compatible object storage, OAuth2/JWT.

### Бэкенд-сервисы
- `auth` — OAuth2 Authorization Server + локальная авторизация;
- `user` — профиль пользователя и связанные данные;
- `core` — основной домен обучения (колоды, карточки, шаблоны, review, поиск);
- `media` — загрузка и раздача медиа через presigned URL;
- `import` — импорт/экспорт колод, фоновые job-ы обработки;
- `ai` — AI jobs, AI import/generate, провайдеры, квоты.

## Основные потоки

1. Аутентификация:
- frontend получает токены в `auth`;
- остальные сервисы валидируют JWT как resource servers.

2. Работа с колодой:
- frontend вызывает `core`;
- `core` при необходимости резолвит media URL через `media`.

3. Импорт:
- файл уходит в `media`;
- `import` создаёт job, делает превью/обработку и пишет результат в `core`.

4. AI-процессы:
- `ai` создаёт/выполняет job;
- при необходимости читает/создаёт данные в `core` и загружает ассеты в `media`.

## Технологический срез

- Java 21 + Kotlin (в отдельных сервисах), Spring Boot 3.5.x;
- Spring Security, OAuth2 Authorization Server, OAuth2 Resource Server;
- Spring Data JPA + Flyway + PostgreSQL;
- Redis cache;
- Actuator + Prometheus metrics;
- Angular 18 на фронтенде;
- S3 SDK для object storage;
- парсинг импорта: CSV/APKG/SQLite;
- AI-интеграции: OpenAI, Gemini, Claude, Qwen, Grok (плюс stub).

## Быстрый map по документации

- [Auth Service](./services/auth-service.md)
- [User Service](./services/user-service.md)
- [Core Service](./services/core-service.md)
- [Media Service](./services/media-service.md)
- [Import Service](./services/import-service.md)
- [AI Service](./services/ai-service.md)
- [Frontend (Angular)](./services/frontend.md)
