# Auth Service (`backend/services/auth`)

## Назначение

`auth` — центр аутентификации и авторизации Mnema:
- выпускает JWT;
- реализует OAuth2 Authorization Server;
- поддерживает локальную регистрацию/логин;
- интегрируется с federated login (Google/GitHub/Yandex).

## Главные фичи

- OAuth2 Authorization Code + PKCE для веб-клиента;
- refresh token flow;
- локальные endpoint-ы `/auth/register`, `/auth/login`, `/auth/password`, `/auth/account`;
- Turnstile-проверка для локального auth flow;
- федеративный вход через внешних провайдеров;
- кастомизация JWT claims под остальные сервисы (например `user_id`, scopes).

## Технологии

- Kotlin + Spring Boot 3.5;
- Spring Security + Spring Authorization Server;
- Spring OAuth2 Client/Resource Server;
- Spring Data JPA + PostgreSQL + Flyway;
- Actuator + Prometheus;
- OpenAPI (springdoc).

## Технические решения и паттерны

- Несколько `SecurityFilterChain` по зонам ответственности:
  - локальный auth API;
  - authorization server endpoints;
  - application endpoints.
- Централизованная конфигурация клиентов OAuth2 в БД.
- Отдельные таблицы пользователей и федеративных identity для более чистой модели аккаунтов.
- Структурированные логи с `trace_id`, `request_id`.

## Связь с другими сервисами

- Является issuer JWT для `user/core/media/import/ai`.
- Вызывается фронтендом напрямую для login/register/token exchange.
- Может быть вызван `user`-сервисом для операций, связанных с удалением аккаунта.

