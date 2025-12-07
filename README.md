
<a id="readme-top"></a>

<div align="center">
  <!-- Заглушка под логотип -->
  <img src="images/read-me-512x512.png" alt="Mnema Logo" width="96" height="96">
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

* **Backend:** Spring Boot (Java, Kotlin, JDK 21), тесты: Mockito + JUnit 6
* **Frontend:** Angular 18, i18n: en/ru
* **Хранилище:** PostgreSQL 18, Redis 8 (кеш/сессии), S3-совместимый провайдер в проде — Yandex Cloud
* **DevOps:** Docker, k8s (k3s, одна нода), Traefik (Ingress), GitHub Actions (CI/CD)
* **Observability:** Grafana + Prometheus + Loki (логи/метрики/трейсы по мере готовности)

> Инструменты и версии могут меняться по мере развития

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

# Получить Google OAuth секреты для своего домена
GOOGLE_CLIENT_ID=
GOOGLE_CLIENT_SECRET=

# Локальные переменные для dev
SPRING_DATASOURCE_USERNAME=
SPRING_DATASOURCE_PASSWORD=
```
2. `docker compose up -d --build` для запуска всех контейнеров.

3. Перейти на [сайт](http://localhost:3005)

---

## Развёртывание в k3s

Пока в работе, но в моем проекте все развертывается при деплое. 

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


## Авторизация и безопасность

* **JWT** для пользовательских сессий (в разработке)
* **OAuth 2.0 провайдеры:** GitHub, Google, Yandex.

Несмотря на тесты, авторизацию и использование современных практик и версий инструментов, я не гарантирую сохранность продовой БД, ваших данных и постоянную работу сервиса.  

---

## Наблюдаемость

* **Prometheus** — метрики (включая Spring Actuator/экспортеры), **Grafana** — дашборды, **Loki** — логи.

Пока что даже не в планах реализации. 

---

## Алгоритмы обучения

* Первый реализуемый алгоритм интервального повторения — **SM2 или FSRSv5**.
* Коротко: модель оптимизирует интервалы повторений на основе обратной связи от пользователя, повышая эффективность запоминания при ограниченном времени. Детали и математика будут описаны в Wiki на Github. 

---

## API и документация

* **OpenAPI/Swagger** будет доступен на `/swagger-ui`.

---

## Архитектура

> Будет на вики в соответствующем разделе, финальная архитектура дорабатывается

---

## Дорожная карта

Роадмапа постоянно меняется. Ближайшие ориентиры:

* [x] Деплой и CI/CD на тестовую машину
* [x] Auth Service: OAuth 2.0 (Google)
* [x] User Service (CRUD)
* [x] Базовый CRUD по карточкам/колодам/шаблонам
* [x] Покрытие тестами главных компонентов системы
* [x] Реализовать фронтенд для базовых сервисов
* [ ] SM2: начало сервиса повторений

*(Фактический трекинг — GitHub Projects/Телеграм избранное XD/Obsidian)*

---

## Как поучаствовать, попользоваться

Пока внешних контрибьюторов не ожидается, но любой фидбек полезен:

0. https://mnema.app должен работать, попробуйте.  
1. Если вы нашли баг или уронили демо, или у вас есть идеи, откройте issue с предложением или багом.
2. Я очень хочу сделать `Mnema` удобным для локального развертывания через **Docker**, без сервисов авторизации и части лишнего функционала, но это фича не первой важности.

Состояние задач: GitHub Projects (канбан) в этом репозитории, но можно уточнить у меня в личке тг.

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
