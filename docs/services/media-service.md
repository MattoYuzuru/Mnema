# Media Service (`backend/services/media`)

## Назначение

`media` управляет пользовательскими файлами и бинарными вложениями:
- аватары;
- иконки колод;
- изображения/аудио/видео карточек;
- файлы для импорта.

## Главные фичи

- выдача presigned URL для upload/download;
- single-part и multipart upload (для крупных файлов);
- завершение/отмена multipart загрузок;
- резолв mediaId -> временный URL;
- internal direct upload endpoint для сервисов (без UI).

## Технологии

- Java 21 + Spring Boot 3.5;
- Spring Security Resource Server + method security;
- AWS SDK v2 S3 (работает с S3-compatible storage);
- Spring Data JPA + PostgreSQL + Flyway;
- Redis cache;
- Actuator + Prometheus.

## Технические решения и паттерны

- Presigned-first архитектура: backend не проксирует большие тела файлов.
- Централизованная `MediaPolicy` (валидация MIME/type/size) для единых правил на все клиенты.
- Internal token для trusted service-to-service upload.
- Отдельные сущности `media_assets` и `media_uploads` для прозрачного жизненного цикла файла.

## Связь с другими сервисами

- Валидирует JWT от `auth`.
- Используется `core` и `user` для резолва ссылок на медиа.
- Используется `import` и `ai` для server-side upload файлов/ассетов.
- Вызывается фронтендом при пользовательских загрузках (аватар, медиа карточек и т.д.).

