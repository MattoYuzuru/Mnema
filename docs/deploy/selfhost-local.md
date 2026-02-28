# Self-Hosted Local Deployment

Этот режим предназначен для запуска Mnema на одной машине без внешних облачных сервисов.

## Что поднимается

- `postgres`
- `redis`
- `minio` + `minio-init` (автосоздание bucket `mnema-local`)
- `ollama`
- backend сервисы (`auth`, `user`, `core`, `media`, `import`, `ai`)
- `frontend`

## Быстрый запуск

### Linux/macOS

```bash
./scripts/mnema-local.sh
```

### Windows (PowerShell)

```powershell
.\scripts\mnema-local.ps1
```

Скрипт:
- спрашивает минимальные креды для `Postgres` и `MinIO`;
- генерирует `.env.local`;
- генерирует `.mnema/compose.ports.yml`;
- запускает `docker compose` с учетом auto-selected портов.
- включает профиль `SPRING_PROFILES_ACTIVE=dev,selfhost-local` для backend сервисов.
- после старта проверяет `Ollama /api/tags`, предлагает рекомендованную модель (`qwen3:4b/8b/14b`) по объему RAM и может сразу выполнить `ollama pull`.

Для проверки без запуска контейнеров:

```bash
MNEMA_DRY_RUN=1 ./scripts/mnema-local.sh
```

## Что происходит при занятых портах

Скрипт проверяет порты по умолчанию:
- `3005` (frontend)
- `5432` (postgres)
- `6379` (redis)
- `8083-8088` (backend)
- `9000` / `9001` (MinIO API/console)
- `11434` (Ollama)

Если порт занят, выбирается следующий свободный (`+1`, `+2`, ...). В консоль выводится, какой порт заменен.

## Поведение selfhost-local профиля

- `auth` сервис:
  - `app.auth.features.federated-enabled=false`
  - `app.auth.features.require-email-verification=false`
  - `turnstile` отключен (пустые ключи)
- frontend на `localhost`:
  - скрывает OAuth-блок на странице логина;
  - скрывает warning про `email_verified`.

- `ai` сервис:
  - системный provider mode (`user keys` не обязателен);
  - default provider для self-host: `ollama` (через OpenAI-compatible контур);
  - runtime discovery endpoint: `GET /api/ai/runtime/capabilities`.

## Оценка ресурсов (приблизительно)

## RAM

- Базовый стек Mnema + DB/Cache/Storage: `~4-7 GB`
- Ollama idle: `~0.5-1 GB`
- Ollama с активной моделью 7B/8B (quantized): `+4-8 GB`
- Video/GIF generation worker (если подключен): обычно `+8-24+ GB VRAM` и заметный рост CPU/RAM/IO

## Disk

- Docker images базового стека (без веса моделей): `~8-15 GB`
- Ollama модели: `~2-8+ GB` на модель (крупные модели значительно больше)
- Рабочие данные (`Postgres`, `MinIO`, импорты/медиа): зависит от объема контента

## Рекомендованные параметры хоста

- CPU: `8 vCPU` или выше
- RAM: `16 GB` (лучше `24+ GB`, если несколько AI задач одновременно)
- Disk: `60+ GB` свободного места

Для video/gif сценариев:
- GPU: `16+ GB VRAM` (минимально рабочий уровень)
- предпочтительно отдельный GPU worker и отдельная очередь задач

## Проверка фактической нагрузки

```bash
docker stats
docker system df
```

Модельная матрица и рекомендации по modality:
- [Local AI Model Matrix](./model-matrix.md)

## Остановка и очистка

```bash
docker compose --env-file .env.local -f docker-compose.yml -f .mnema/compose.ports.yml down
```

Полная очистка с удалением volumes:

```bash
docker compose --env-file .env.local -f docker-compose.yml -f .mnema/compose.ports.yml down -v
```
