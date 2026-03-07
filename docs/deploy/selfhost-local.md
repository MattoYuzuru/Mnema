# Self-Hosted Local Deployment

Этот режим предназначен для запуска Mnema на одной машине без внешних облачных сервисов.

## Что поднимается

- `postgres`
- `redis`
- `minio` + `minio-init` (автосоздание bucket `mnema-local`)
- `ollama`
- `local-ai-gateway` (OpenAI-compatible шлюз для локальных backends)
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
- спрашивает starter text model для Ollama и сохраняет его в `OPENAI_DEFAULT_MODEL`;
- генерирует `.env.local`;
- генерирует `.mnema/compose.ports.yml`;
- запускает `docker compose` с учетом auto-selected портов.
- включает профиль `SPRING_PROFILES_ACTIVE=dev,selfhost-local` для backend сервисов.
- после старта проверяет `Ollama /api/tags`, предлагает:
  - starter text model (`qwen3:4b/8b/14b`) по объему RAM;
  - backup text model (`qwen2.5:3b/7b` или `qwen3:8b`);
  - vision model (`qwen2.5vl:3b/7b` или `minicpm-v:8b`).
- для каждой рекомендации можно сразу сделать `ollama pull`.
- в конце печатает команды для ручного управления моделями (`ollama list/pull/run`, `curl /api/tags`).

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
- `8090` (Local AI Gateway)

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
  - default provider для self-host: `ollama`;
  - OpenAI-compatible base URL указывает на `local-ai-gateway` (`OPENAI_BASE_URL=http://local-ai-gateway:8089`);
  - text/chat/vision идут через Ollama;
  - TTS/STT/image/video проксируются gateway в соответствующие локальные backends при их настройке;
  - runtime discovery endpoint: `GET /api/ai/runtime/capabilities`.

## Local AI Gateway

Gateway поднимается вместе со стеком и дает единый OpenAI-compatible endpoint для Mnema AI сервиса.

Локальные переменные в `.env.local`:

- `LOCAL_AUDIO_BASE_URL` — backend для `/v1/audio/*` (например локальный Speaches/Orpheus/Piper-gateway)
- `LOCAL_IMAGE_BASE_URL` — backend для `/v1/images/*` (если хотите вынести image отдельно)
- `LOCAL_VIDEO_BASE_URL` — backend для `/v1/videos*`
- `LOCAL_TTS_VOICES` — fallback список голосов через запятую, если audio backend не отдает `/v1/audio/voices`
- `LOCAL_AI_GATEWAY_TIMEOUT_SECONDS` — timeout для upstream запросов gateway (по умолчанию `600`)

Если `LOCAL_*_BASE_URL` пустой, gateway пытается использовать Ollama для этого типа запросов.

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

## Управление моделями Ollama вручную

После старта скрипт печатает команды вида:

```bash
docker compose --env-file .env.local -f docker-compose.yml -f .mnema/compose.ports.yml exec -T ollama ollama list
docker compose --env-file .env.local -f docker-compose.yml -f .mnema/compose.ports.yml exec -T ollama ollama pull <model>
docker compose --env-file .env.local -f docker-compose.yml -f .mnema/compose.ports.yml exec -T ollama ollama run <model>
curl http://localhost:11434/api/tags
curl http://localhost:8090/v1/models
curl http://localhost:8090/v1/audio/voices
```

Если порт `11434` был занят, используйте фактический порт, который показал bootstrap скрипт.
Аналогично для gateway (`8090` по умолчанию).

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
