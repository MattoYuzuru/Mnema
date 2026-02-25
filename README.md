<a id="readme-top"></a>

<div align="center">
  <img src="images/read-me-512x512.png" alt="Mnema Logo" width="96" height="96">
  <h1>Mnema</h1>
  <p>Платформа интервального обучения: карточки, импорт, AI-генерация и аналитика.</p>

  <p>
    <a href="https://mnema.app">Демо</a>
    &middot;
    <a href="https://github.com/MattoYuzuru/Mnema">Репозиторий</a>
    &middot;
    <a href="docs/system-overview.md">System Docs</a>
    &middot;
    <a href="#дорожная-карта">Roadmap</a>
  </p>

  <p>
    <a href="https://github.com/MattoYuzuru/Mnema/blob/main/LICENSE"><img alt="License" src="https://img.shields.io/badge/license-Apache--2.0-blue.svg"></a>
    <a href="https://github.com/MattoYuzuru/Mnema/actions"><img alt="CI" src="https://img.shields.io/badge/CI-GitHub%20Actions-informational"></a>
    <img alt="i18n" src="https://img.shields.io/badge/i18n-ru%20%7C%20en-brightgreen">
  </p>
</div>

---

## Содержание

* [О проекте](#о-проекте)
* [Что уже умеет продукт](#что-уже-умеет-продукт)
* [Чем Mnema выделяется относительно Anki/Quizlet](#чем-mnema-выделяется-относительно-ankiquizlet)
* [Технологии](#технологии)
* [Архитектура](#архитектура)
* [Локальная разработка](#локальная-разработка)
* [Переменные окружения (.env)](#переменные-окружения-env)
* [CI/CD и деплой](#cicd-и-деплой)
* [Безопасность](#безопасность)
* [Наблюдаемость](#наблюдаемость)
* [API и документация](#api-и-документация)
* [Дорожная карта](#дорожная-карта)
* [Как поучаствовать](#как-поучаствовать)
* [Лицензия](#лицензия)
* [Контакты](#контакты)

---

## О проекте

**Mnema** — веб-платформа для интервального запоминания с упором на инженерную архитектуру и реальный production-пайплайн.

Проект включает:
- модульный backend на Spring Boot;
- frontend на Angular;
- полноценный auth контур (OAuth2 + local auth);
- отдельные сервисы под media/import/AI;
- CI/CD и k8s-инфраструктуру.

**Демо:** [https://mnema.app](https://mnema.app)

---

## Что уже умеет продукт

- Регистрация/логин через local auth и OAuth2 (Google/GitHub/Yandex).
- Создание и редактирование колод, карточек, шаблонов.
- Публичные колоды и форки.
- Версионирование колод и шаблонов + синхронизация пользовательских копий.
- Review-сессии с несколькими алгоритмами (SM2, FSRS v6, HLR).
- Ограничения на daily review/new, user timezone/day-cutoff, расширенная review-аналитика.
- Импорт из APKG/CSV/TSV/TXT с preview и field mapping.
- Экспорт колод.
- Медиа-вложения: image/audio/video, аватары, иконки.
- AI-функции:
  - генерация и улучшение карточек;
  - AI import (text/pdf/image/audio/docx);
  - мультимодальные провайдеры (OpenAI/Gemini/Claude/Qwen/Grok + stub);
  - квоты и учёт токенов.

---

## Чем Mnema выделяется относительно Anki/Quizlet

- Версионируемые публичные колоды и шаблоны с контролируемым sync.
- Несколько SRS-алгоритмов в одном продукте с переключением на уровне колоды.
- Отдельный media-сервис с presigned/multipart загрузками (масштабируемо для тяжёлых вложений).
- Отдельный import-сервис с фоновой обработкой и preview перед импортом.
- Отдельный AI-сервис с очередями задач, quota/ledger и мульти-провайдерами.
- Более «продуктовый» веб-поток: профиль, каталог, поиск, аналитика, мобильная навигация.

---

## Технологии

### Backend
- Java 21, Kotlin 2.1
- Spring Boot 3.5.x
- Spring Security, OAuth2 Authorization Server, OAuth2 Resource Server
- Spring Data JPA, Flyway
- PostgreSQL, Redis
- S3 API (AWS SDK v2)
- Testcontainers, JUnit

### Frontend
- Angular 18 (standalone)
- RxJS
- i18n ru/en
- Nginx (production serving)

### Infra / DevOps
- Docker / Docker Compose
- Kubernetes (k3s)
- Traefik ingress, cert-manager
- GitHub Actions + GHCR
- Prometheus + Grafana + Loki + Alloy

---

## Архитектура

Сервисы backend:
- `auth` — OAuth2 auth server + local auth.
- `user` — профиль пользователя и account-related операции.
- `core` — колоды, карточки, шаблоны, review, поиск, статистика.
- `media` — upload/resolve медиа, S3 presigned URLs.
- `import` — импорт/экспорт и фоновые import jobs.
- `ai` — AI jobs, AI import, провайдеры, квоты.

Frontend (`frontend`) общается с backend API:
- `/api/user`
- `/api/core`
- `/api/media`
- `/api/import`
- `/api/ai`
- auth endpoints через `auth`.

Подробная документация:
- [docs/README.md](docs/README.md)
- [docs/system-overview.md](docs/system-overview.md)

---

## Локальная разработка

1. Подготовить `.env` (пример ниже).
2. Поднять сервисы:
```bash
docker compose up -d --build
```
3. Открыть приложение: [http://localhost:3005](http://localhost:3005)

Локальные порты:
- frontend: `3005`
- auth: `8083`
- user: `8084`
- core: `8085`
- media: `8086`
- import: `8087`
- ai: `8088`
- postgres: `${POSTGRES_PORT}`
- redis: `6379`

---

## Переменные окружения (.env)

Ниже расширенный перечень того, что реально используется в compose/сервисах. Значения и сам `.env` не меняются этим README.

### База / общие
```env
POSTGRES_DB=
POSTGRES_USER=
POSTGRES_PASSWORD=
POSTGRES_PORT=

SPRING_DATASOURCE_USERNAME=
SPRING_DATASOURCE_PASSWORD=
```

### Auth / issuer
```env
AUTH_ISSUER=http://localhost:8083
AUTH_ISSUER_URI=http://localhost:8083

GOOGLE_CLIENT_ID=
GOOGLE_CLIENT_SECRET=
GH_CLIENT_ID=
GH_CLIENT_SECRET=
YANDEX_CLIENT_ID=
YANDEX_CLIENT_SECRET=

TURNSTILE_SITE_KEY=
TURNSTILE_SECRET_KEY=
```

### Media / S3
```env
MEDIA_INTERNAL_TOKEN=

AWS_REGION=
AWS_BUCKET_NAME=
AWS_ENDPOINT=https://storage.yandexcloud.net
AWS_PATH_STYLE_ACCESS=false
AWS_ACCESS_KEY_ID=
AWS_SECRET_ACCESS_KEY=
```

### Межсервисные URL (обычно задаются в compose автоматически)
```env
CORE_BASE_URL=http://core:8080/api/core
MEDIA_BASE_URL=http://media:8080/api/media
```

### AI vault / provider
```env
AI_PROVIDER=stub
AI_VAULT_MASTER_KEY=
AI_VAULT_KEY_ID=

OPENAI_BASE_URL=https://api.openai.com
OPENAI_DEFAULT_MODEL=gpt-4.1-mini
OPENAI_TTS_MODEL=gpt-4o-mini-tts
OPENAI_STT_MODEL=gpt-4o-mini-transcribe

GEMINI_BASE_URL=https://generativelanguage.googleapis.com
GEMINI_DEFAULT_MODEL=gemini-2.0-flash

ANTHROPIC_BASE_URL=https://api.anthropic.com
ANTHROPIC_API_VERSION=2023-06-01
ANTHROPIC_DEFAULT_MODEL=claude-3-5-sonnet-20241022

QWEN_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
QWEN_DASHSCOPE_BASE_URL=https://dashscope.aliyuncs.com

GROK_BASE_URL=https://api.x.ai
```

### Ограничения upload/import/AI (опционально)
```env
MEDIA_MAX_FILE_SIZE=350MB
MEDIA_MAX_REQUEST_SIZE=350MB
IMPORT_MAX_FILE_SIZE=350MB
IMPORT_MAX_REQUEST_SIZE=350MB

AI_IMPORT_MAX_BYTES=10485760
AI_IMPORT_PDF_MAX_BYTES=31457280
AI_IMPORT_IMAGE_MAX_BYTES=20971520
AI_IMPORT_AUDIO_MAX_BYTES=52428800
AI_IMPORT_MAX_CHARS=200000
```

### Cache / runtime
```env
SPRING_CACHE_TYPE=redis
REDIS_HOST=redis
REDIS_PORT=6379
APP_ENV=dev
```

Примечания:
- Если local auth не нужен, можно оставить OAuth2-провайдеры пустыми, но соответствующие сценарии не будут работать.
- Для AI-сценариев `stub` провайдер позволяет запускать пайплайн без внешних ключей.

---

## CI/CD и деплой

Текущий pipeline (`.github/workflows/deploy.yaml`):

1. `test-backend`: `./gradlew test` по backend.
2. `build-and-push`: сборка образов `frontend/auth/user/core/media/import/ai` и пуш в GHCR.
3. `apply-main-manifests`: деплой основной части в main cluster (`prod`).
4. `apply-ai-manifests`: отдельный деплой AI части/моста (по текущей схеме инфраструктуры).

В k8s используются:
- namespace `prod` + `observability`;
- отдельные манифесты на каждый сервис;
- ingress/bridge для AI-части;
- секреты через GitHub Environments/Secrets.

---

## Безопасность

- JWT + OAuth2 auth server.
- PKCE flow для frontend OAuth2 login.
- Method-level security в сервисах.
- Валидация входных DTO и CORS-политики.
- Внутрисервисный токен для media internal upload.
- Vault-слой в AI сервисе для секретов провайдеров.

См. также: [SECURITY.md](SECURITY.md)

---

## Наблюдаемость

- Spring Actuator + Prometheus endpoint в сервисах.
- Grafana dashboards + Loki logs + Alloy collection.
- Структурированная консольная log pattern (trace_id/span_id/request_id поля).

---

## API и документация

- Swagger UI у backend-сервисов (springdoc).
- Внутренние docs проекта:
  - [docs/README.md](docs/README.md)
  - [docs/system-overview.md](docs/system-overview.md)
  - [docs/services](docs/services)

---

## Дорожная карта

### Исторический путь (выполнено)

#### 0) Foundation (октябрь 2025)
- Инициализация репозитория, README, SECURITY, issue/PR templates.
- Базовый CI/CD каркас.
- Начальные k3s-манифесты и Docker-сборка.

#### 1) Auth + User base (октябрь-ноябрь 2025)
- Поднят `auth` service (Spring Initializr).
- Поднят `user` service.
- JPA/Flyway базы для user/auth.
- Базовые CRUD endpoint-ы user.
- OAuth2 интеграция, CORS, routing fixes.
- Начальные security-конфиги.

#### 2) Frontend bootstrap (ноябрь 2025)
- Angular приложение и базовые экраны.
- Подключение к backend API.
- Исправления auth-потока и роутинга.

#### 3) Core domain v1 (ноябрь-декабрь 2025)
- Создан `core` service.
- Большая миграция схемы `app_core`.
- Deck/card/template сущности, DTO, репозитории, контроллеры.
- Разделение контроллеров по доменам.
- Endpoint-ы создания колод и карт.
- Базовая security-защита endpoint-ов.

#### 4) Testing maturity (декабрь 2025)
- Наращивание тестовой базы core.
- Интеграция Testcontainers.
- Покрытие key-сценариев и стабилизация CI.

#### 5) Template workflows + UX polish (декабрь 2025 - январь 2026)
- Расширение CRUD шаблонов.
- Улучшение template wizard и визуального UX.
- Валидации форм на backend/frontend.
- Маркдаун в описаниях.

#### 6) Review engine (декабрь 2025 - январь 2026)
- Формирование review-модуля.
- Интерфейс алгоритмов + реализации.
- Миграции и сущности review-state/log.
- Ограничения `due/new` в день, user preferences.
- Улучшения логики выбора карт и quality fixes.

#### 7) OAuth/local auth hardening (январь 2026)
- Рефактор auth под multi-provider.
- Локальная регистрация/логин внутри auth сервиса.
- Rate limiting/валидация для auth форм.
- Улучшения удаления аккаунта и ошибок.

#### 8) Media service (январь 2026)
- Введение `media` сервиса.
- S3 слой, сущности, политики, security.
- Upload/resolve контроллеры.
- Поддержка media в UI карточек.

#### 9) Import/Export service (январь 2026)
- Введение `import` сервиса.
- APKG/CSV parser pipeline.
- Фоновые job-ы импорта/экспорта.
- Прогресс импорта и импорт review-прогресса.
- Поддержка Anki 21b zstd.
- Улучшения стилей/рендера импортированного контента.

#### 10) Caching + search + scalability tweaks (январь 2026)
- Redis-backed media resolve cache (`core/user/media`).
- Поиск по колодам/карточкам/шаблонам + индексы.
- Пагинация, lazy loading, стабильность fork/checksum сценариев.

#### 11) UI redesign phase (январь 2026)
- Новый визуальный стиль (Liquid Glass).
- Hero/landing, улучшенная навигация, адаптивность.
- Доработки UX карточного браузера и review.

#### 12) AI platform introduction (январь-февраль 2026)
- Введение `ai` сервиса и схемы `app_ai`.
- Очереди job-ов, квоты, usage ledger, токен-учёт.
- OpenAI прототип -> productionized pipeline.
- Расширение до Gemini + Claude + Qwen + Grok.
- TTS/STT/image/video сценарии (provider-dependent).
- AI enhancer/audit/import.
- Batch-операции и conflict fallback.

#### 13) AI multimodal import + docs/stats (февраль 2026)
- AI import modal: text/pdf/image/audio/docx.
- OCR/аудио chunking, лимиты, троттлинг TTS.
- Review analytics API + UI-панели.
- Mobile-first навигация и улучшения review UX.
- Введение внутренней документации по сервисам (`docs/`).

#### 14) Deploy reliability (весь путь, особенно февраль 2026)
- Много итераций CI/CD и k8s деплоя.
- Переносы инфраструктуры/AI кластера.
- Ingress/TLS/bridge стабилизация.
- Улучшение rollout устойчивости.

### Текущий фокус (in progress)

- Стабильность импортов для “грязных” файлов и edge-case форматов.
- Полировка стилей импортированных колод (Anki шаблоны + media html/css).
- Дальнейшая стабилизация AI batch/retry flows.
- Улучшение UX аналитики и мобильного review.

### Расширенный forward roadmap (план)

#### Near-term (1-2 релизных цикла)
- [ ] Улучшить стили импортированных колод (HTML/CSS compatibility слой + fallback renderer).
- [ ] Улучшить import pipeline для проблемных форматов и частично битых APKG/CSV.
- [ ] Добавить систему стриков (daily streak, freeze/day pass, streak analytics).
- [ ] Доработать global/local scope инструменты для массовых изменений карточек.
- [ ] Улучшить дедупликацию импортов и пост-импорт аудит качества.
- [ ] Улучшить explainability review-алгоритмов в UI.

#### Product / UX
- [ ] Полноценный PWA режим (manifest, offline cache strategy, install prompts).
- [ ] Push/Local notifications для review сессий (включая quiet hours).
- [ ] Улучшить onboarding и сценарии для first-time users.
- [ ] Отдельный режим «быстрый повтор» и «глубокая сессия».
- [ ] Расширенные фильтры в поиске и браузере карточек.

#### Platform / Architecture
- [ ] Релиз «only-local» версии без лишних сервисов (упрощённый single-host профиль).
- [ ] Dev profile: отключение внешних OAuth/AI провайдеров в пользу локальных заглушек.
- [ ] Опциональный “lite mode” без AI/media/import микросервисов (монолитный runtime режим).
- [ ] Расширенная конфигурация очередей AI/import (throughput controls, priority lanes).

#### AI roadmap
- [ ] Улучшить качество structured outputs для сложных шаблонов карточек.
- [ ] Добавить provider health scoring и auto-fallback policy.
- [ ] Улучшить cost-controls (пер-провайдер бюджеты, warnings, caps).
- [ ] Расширить AI-audit: семантические дубликаты, factual consistency checks.

#### Data / Quality
- [ ] Больше e2e на критические пользовательские потоки.
- [ ] Тесты несовместимых import edge-cases (коррупция, экзотические кодировки, broken media refs).
- [ ] Реплики/бэкапы: документация RPO/RTO и restore-runbook.

#### Observability / Ops
- [ ] SLO/SLI для ключевых API и фоновых job-ов.
- [ ] Tracing важных межсервисных вызовов.
- [ ] Alerting playbooks для импорта, AI quota, media upload деградаций.

---

## Как поучаствовать

- Используйте демо и открывайте issue с багами/идеями.
- Предлагайте улучшения импортов и UX review-сессий.
- Для крупных изменений желательно сначала описать proposal (краткий RFC в issue).

---

## Лицензия

Проект распространяется по лицензии **Apache 2.0**. См. файл [`LICENSE`](LICENSE).

---

## Контакты

Автор: Матвей Рябушкин
Telegram: [@Keyko_Mi](https://t.me/Keyko_Mi)
Email: [matveyryabushkin@gmail.com](mailto:matveyryabushkin@gmail.com)
Репозиторий: [https://github.com/MattoYuzuru/Mnema](https://github.com/MattoYuzuru/Mnema)

<p align="right">(<a href="#readme-top">наверх</a>)</p>
