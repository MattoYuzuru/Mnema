# Self-Hosted Public Deployment

Этот режим предназначен для собственного домена/сервера, когда к Mnema подключаются пользователи через интернет.

## Что поднимается

- `postgres`
- `redis`
- `minio` + `minio-init` (bucket `mnema-public`)
- `ollama`
- backend сервисы (`auth`, `user`, `core`, `media`, `import`, `ai`)
- `frontend`

## Быстрый запуск

### Linux/macOS

```bash
./scripts/mnema-public.sh
```

### Windows (PowerShell)

```powershell
.\scripts\mnema-public.ps1
```

Скрипт:
- запрашивает `web domain` и `auth domain`;
- запрашивает базовые креды `Postgres` и `MinIO`;
- проверяет порты и при конфликте выбирает следующий свободный (`+1`, `+2`, ...);
- генерирует `.env.public` и `.mnema/compose.public.yml`;
- включает `SPRING_PROFILES_ACTIVE=prod,selfhost-public` для backend;
- настраивает frontend runtime overrides (`MNEMA_AUTH_SERVER_URL`, feature flags) без пересборки frontend image;
- по умолчанию предлагает сразу запустить compose.

Проверка без запуска контейнеров:

```bash
MNEMA_DRY_RUN=1 ./scripts/mnema-public.sh
```

## Профиль `selfhost-public`

- CORS для backend сервисов берется из `PUBLIC_WEB_ORIGIN`.
- `auth` использует:
  - `AUTH_ISSUER`
  - `AUTH_LOGOUT_DEFAULT_REDIRECT`
  - redirect-uri списки через `AUTH_WEB_REDIRECT_URIS` и `AUTH_SWAGGER_REDIRECT_URIS`
- Federated OAuth можно оставить включенным или отключить в момент инициализации.

## DNS и reverse proxy

Минимально рекомендуемая схема:
- `https://<web-domain>` -> `frontend`
- `https://<auth-domain>` -> `auth`
- `https://<web-domain>/api/user` -> `user`
- `https://<web-domain>/api/core` -> `core`
- `https://<web-domain>/api/media` -> `media`
- `https://<web-domain>/api/import` -> `import`
- `https://<web-domain>/api/ai` -> `ai`

Требования:
- валидный TLS сертификат для web/auth доменов;
- доступ к backend портам ограничить firewall (публиковать наружу только reverse proxy);
- в proxy добавить ограничения размера body для upload/import сценариев.

## OAuth providers (опционально)

Если federated OAuth включен, заполните в `.env.public`:
- `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET`
- `GH_CLIENT_ID` / `GH_CLIENT_SECRET`
- `YANDEX_CLIENT_ID` / `YANDEX_CLIENT_SECRET`

Redirect URI в кабинетах провайдеров должны совпадать с вашим `auth` доменом и endpoint-ами `auth` сервиса.

## Multi-cluster вариант

Для раздельного размещения (например, AI отдельно):
- оставьте frontend + `auth` + `user/core/media/import` в основном кластере;
- `ai` можно вынести в отдельный кластер/VPC;
- между кластерами используйте private connectivity (VPN/peering) и mTLS;
- выставляйте наружу только публичный ingress основной зоны;
- в основном кластере проксируйте `/api/ai` на внутренний адрес AI-кластера.

Минимальный порядок:
1. Поднять БД/кэш/объектное хранилище и основные сервисы.
2. Поднять отдельный AI кластер.
3. Настроить private network route и health checks.
4. Переключить `/api/ai` маршрут на AI кластер.
5. Проверить auth flow, CORS и upload/import сценарии end-to-end.

## Ресурсы (приблизительно)

- Single-node public stack без тяжелых AI задач: `8 vCPU`, `16 GB RAM`, `60+ GB disk`.
- Если используются image/video workloads локально:
  - отдельный GPU worker;
  - `16+ GB VRAM` (реалистичный нижний порог для стабильной video inference);
  - дополнительный запас диска под кэш моделей.

## Остановка

```bash
docker compose --env-file .env.public -f docker-compose.yml -f .mnema/compose.public.yml down
```

Очистка с volumes:

```bash
docker compose --env-file .env.public -f docker-compose.yml -f .mnema/compose.public.yml down -v
```
