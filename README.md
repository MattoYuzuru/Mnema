
<a id="readme-top"></a>

<div align="center">
  <!-- Заглушка под логотип -->
  <img src="images/logo.png" alt="Mnema Logo" width="96" height="96">
  <h1>Mnema</h1>
  <p>Веб-приложение для эффективного запоминания информации с помощью интерактивных карт.</p>

  <!-- Короткие ссылки -->

  <p>
    <a href="https://mnema.app">Демо</a>
    &middot;
    <a href="https://github.com/MattoYuzuru/Mnema">Репозиторий</a>
    &middot;
    <a href="#архитектура">Архитектура</a>
    &middot;
    <a href="#развёртывание-в-k3s">Деплой</a>
  </p>

  <!-- Мини-бейджи -->

  <p>
    <a href="https://github.com/MattoYuzuru/Mnema/blob/main/LICENSE"><img alt="License" src="https://img.shields.io/badge/license-Apache--2.0-blue.svg"></a>
    <a href="https://github.com/MattoYuzuru/Mnema/actions"><img alt="CI" src="https://img.shields.io/badge/CI-GitHub%20Actions-informational"></a>
    <img alt="i18n" src="https://img.shields.io/badge/i18n-ru%20%7C%20en-brightgreen">
  </p>
</div>

---

## Содержание

* [О проекте](#о-проекте)
* [Технологии](#технологии)
* [Структура репозитория](#структура-репозитория)
* [Локальная разработка](#локальная-разработка)
* [Развёртывание в k3s](#развёртывание-в-k3s)
* [CI/CD](#cicd)
* [Конфигурация и секреты](#конфигурация-и-секреты)
* [Авторизация и безопасность](#авторизация-и-безопасность)
* [Наблюдаемость](#наблюдаемость)
* [Алгоритмы обучения](#алгоритмы-обучения)
* [API и документация](#api-и-документация)
* [Архитектура](#архитектура)
* [Дорожная карта](#дорожная-карта)
* [Как поучаствовать](#как-поучаствовать)
* [Лицензия](#лицензия)
* [Контакты](#контакты)

---

## О проекте

**Mnema** — веб-приложение для запоминания через интерактивные карты и гибкие алгоритмы интервального повторения. Проект учебный, но инженерно-ориентированный: упор на архитектуру, функциональность и продовые практики (контейнеризация, k3s, observability, CI/CD).

**Демо:** [https://mnema.app](https://mnema.app)
**Код:** [https://github.com/MattoYuzuru/Mnema](https://github.com/MattoYuzuru/Mnema)

---

## Технологии

* **Backend:** Spring Boot (Kotlin, JDK — *LTS/последний стабильный*), тесты: Mockito + JUnit 6
* **Frontend:** Angular (*актуальный LTS/последний стабильный*), i18n: ru/en
* **Хранилище:** PostgreSQL 18, Redis 8 (кеш/сессии), S3-совместимый провайдер в проде — Yandex Cloud
* **DevOps:** Docker, k8s (k3s, одна нода), Nginx (Ingress), GitHub Actions (CI/CD)
* **Observability:** Grafana + Prometheus + Loki (логи/метрики/трейсы по мере готовности)

> Версии будут уточняться по мере развития — в репозитории укажу фиксированные теги образов/артефактов.

---

## Структура репозитория

Монорепозиторий:

```
Mnema/
├─ backend/
│  ├─ services/
│  │  ├─ service1/
│  │  │  ├─ src/
│  │  │  │  ├─ main/kotlin/...
│  │  │  │  ├─ main/resources/
│  │  │  │  └─ test/kotlin/...
│  │  │  ├─ build.gradle.kts
│  │  │  └─ Dockerfile
│  │  ├─ service2/
│  │  │  ├─ src/ (main/resources/test…)
│  │  │  ├─ build.gradle.kts
│  │  │  └─ Dockerfile
│  │  ├─ service3/
│  │  │  ├─ src/ (main/resources/test…)
│  │  │  ├─ build.gradle.kts
│  │  │  └─ Dockerfile
│  │  └─ service4/
│  │     ├─ src/
│  │     ├─ build.gradle.kts
│  │     └─ Dockerfile
│  │
│  ├─ libs/                            # общие библиотеки (без Dockerfile)
│  │  ├─ common/                       # утилиты, доменные модели (без зависимостей на web)
│  │  │  ├─ src/main/kotlin/...
│  │  │  └─ build.gradle.kts
│  │  ├─ persistence/                  # репозитории, flyway/liquibase helpers
│  │  │  ├─ src/main/kotlin/...
│  │  │  └─ build.gradle.kts
│  │  └─ security/                     # общие фильтры, конфиги Spring Security
│  │     ├─ src/main/kotlin/...
│  │     └─ build.gradle.kts
│  │
│  ├─ build.gradle.kts                 # корневой gradle (version catalog, плагины)
│  └─ settings.gradle.kts              # инклюды сабпроектов
│
├─ frontend/                           # Angular
│  ├─ src/
│  ├─ angular.json
│  └─ Dockerfile
├─ deploy/                             # k8s-манифесты и/или Helm/kustomize
│  ├─ base/
│  ├─ overlays/
│  └─ ingress/
├─ .github/
│  └─ workflows/                       # GitHub Actions (CI/CD)
├─ docs/                               # схемы, C4/Structurizr DSL, ADR и т. п.
├─ images/                             # логотипы/скриншоты
└─ LICENSE
```

---

## Локальная разработка

1. Создать `.env` файл, такого формата
```env
# --- DB ---
POSTGRES_DB=
POSTGRES_USER=
POSTGRES_PASSWORD=
POSTGRES_PORT=

# --- Issuer ---
AUTH_ISSUER=http://localhost:8083
AUTH_ISSUER_URI=http://localhost:8083

# Google OAuth
GOOGLE_CLIENT_ID=
GOOGLE_CLIENT_SECRET=

# Локальные переменные для dev
SPRING_DATASOURCE_USERNAME=
SPRING_DATASOURCE_PASSWORD=
```
2. `docker compose up -d --build`

### Предпосылки

* JDK (LTS), Kotlin toolchain
* Node.js + npm (актуальные стабильные)
* Локальные PostgreSQL 18 и Redis 8 (контейнеры)

### Быстрый старт

Backend:

```bash
cd backend
# Настрой нужные ENV (см. раздел "Конфигурация и секреты")
./gradlew bootRun
```

Frontend:

```bash
cd frontend
npm ci
npm start
```

---

## Развёртывание в k3s

> Базовые шаги для одной ноды (VPS). Ресурсы уточняются позже — оставлено место под спецификацию.

1. **Сборка и публикация образов**

```bash
# Пример: ghcr.io/MattoYuzuru/mnema-backend:TAG
#         ghcr.io/MattoYuzuru/mnema-frontend:TAG
docker build -t ghcr.io/MattoYuzuru/mnema-backend:TAG ./backend
docker build -t ghcr.io/MattoYuzuru/mnema-frontend:TAG ./frontend
docker push ghcr.io/MattoYuzuru/mnema-backend:TAG
docker push ghcr.io/MattoYuzuru/mnema-frontend:TAG
```

2. **Secret’ы и ConfigMap’ы**
   Создай Kubernetes Secret’ы для:

* `POSTGRES_URL`, `POSTGRES_USER`, `POSTGRES_PASSWORD`
* `REDIS_URL`
* `JWT_SECRET`
* `OAUTH_{GITHUB,GOOGLE,YANDEX}_{CLIENT_ID,CLIENT_SECRET}`, `OAUTH_REDIRECT_URI`
* `S3_ENDPOINT`, `S3_BUCKET`, `S3_ACCESS_KEY`, `S3_SECRET_KEY` *(в проде — Yandex Cloud)*

3. **Применение манифестов**

```bash
# Если используется kustomize:
kubectl apply -k deploy/overlays/prod
# или напрямую:
kubectl apply -f deploy/base/
kubectl apply -f deploy/ingress/
```

4. **Ingress + TLS**

* Ingress контроллер: Nginx
* Домены: `mnema.app` (+ поддомены при необходимости)
* TLS: cert-manager + Let’s Encrypt (Issuer/ClusterIssuer)
  *(включи манифесты при готовности; секцию оставили как заглушку)*

---

## CI/CD

**GitHub Actions**:

* **CI:** линт/сборка/тесты (backend+frontend), публикация Docker-образов в GHCR с тегами (`sha`, `latest`, релизные)
* **CD:** деплой в k3s через `kubectl`/`kustomize`/Helm (на выбор). Рекомендуется:

    * окружения `dev`/`prod` (Environments)
    * защитные правила на `prod` (manual approval)
    * отдельные `secrets`/`vars` на окружение

Пайплайн (в общих чертах):

```
push -> CI build & test -> build docker images -> push GHCR -> deploy job -> kubectl apply (prod/dev)
```

---

## Конфигурация и секреты

* **ENV-переменные** централизуем в одном месте (`deploy/` + README).
* Секреты **не коммитим**. Для GitHub Actions:

    * `Repository/Environment Secrets`
    * опционально OIDC + хранилище секретов провайдера (в проде)
* Для локалки добавим позже `env.example` при необходимости.

---

## Авторизация и безопасность

* **JWT** для пользовательских сессий.
* **OAuth 2.0 провайдеры:** GitHub, Google, Yandex.
* Рекомендуем:

    * короткоживущие access-токены + refresh-ритуал
    * HTTP-only cookies (если фронт/бек на одном домене), CSRF-защита где требуется
    * строгая CORS-политика (origin: `https://mnema.app`)
    * секреты только из Secret’ов k8s; RBAC для CI/CD

---

## Наблюдаемость

* **Prometheus** — метрики (включая Spring Actuator/экспортеры), **Grafana** — дашборды, **Loki** — логи.
* Инструкции по установке (Helm, values) добавим позже; в коде — метрики/лейблы по мере интеграции.

---

## Алгоритмы обучения

* Первый реализуемый алгоритм интервального повторения — **FSRS**.
* Коротко: модель оптимизирует интервалы повторений на основе обратной связи от пользователя, повышая эффективность запоминания при ограниченном времени. Детали и математика будут описаны в документации алгоритмов (`docs/algorithms/fsrs.md`).

---

## API и документация

* **OpenAPI/Swagger** будет доступен (путь уточняется; ожидаемо `/_/swagger` или `/swagger-ui`).
* Контракты публикуем вместе с релизами (генерация из backend-сервисов).

---

## Архитектура

> Пока что заглушки, под C4/Structurizr DSL.

* **HLD (C4: System/Container):** `docs/architecture/c4/hld.dsl` *(заглушка)*
* **LLD (C4: Component/Code):** `docs/architecture/c4/lld.dsl` *(заглушка)*
* **ADR (Architecture Decision Records):** `docs/adr/` *(по ключевым решениям — БД, кеш, OAuth, деплой, observability)*

---

## Дорожная карта

Роадмапа будет позднее. Ближайшие ориентиры:

* [ ] Конфиг для деплоя и CI/CD на тестовую машину
* [ ] JWT Auth (Начало auth service)
* [ ] OAuth 2.0 (GitHub/Google/Yandex)
* [ ] User Service (CRUD)
* [ ] Базовый CRUD по карточкам/колодам
* [ ] FSRS: первый проход

*(Фактический трекинг — GitHub Projects/Board.)*

---

## Как поучаствовать

Пока внешних контрибьюторов не ожидается, но любой фидбек полезен:

1. Открой issue с предложением или багом.
2. Для PR: форк → ветка `feature/*` → PR в `main`.
3. Соблюдай код-стайл (Kotlin/Angular), прогони тесты.

Состояние задач: GitHub Projects (канбан) в этом репозитории.

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
