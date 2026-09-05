# Local maintenance runtimes

Current revisions permit private personal use by one natural person on owned or
controlled devices. Shared or organizational use needs a
[separate written license](../../COMMERCIAL-LICENSING.md).

## Current replacement setup — #143

The canonical `docker-compose.yml` starts only PostgreSQL 18, Identity & Account,
and Learning. Both application shells report `maintenance` / `identity-learning`;
product UI, login and learning flows belong to their implementation epics.

Docker Engine and the Docker Compose plugin are required. Run commands from the
repository root. Set a fresh password for this replacement database; a missing or
empty `MNEMA_LOCAL_POSTGRES_PASSWORD` fails configuration before any container starts.

Bash/zsh:

```bash
printf 'Fresh replacement database password: '
read -r -s MNEMA_LOCAL_POSTGRES_PASSWORD
printf '\n'
export MNEMA_LOCAL_POSTGRES_PASSWORD
export COMPOSE_DISABLE_ENV_FILE=true
export MNEMA_LOCAL_BUILD_ID="$(git rev-parse HEAD)"
docker compose --project-name mnema-replacement --file docker-compose.yml up -d --build
```

PowerShell:

```powershell
$env:MNEMA_LOCAL_POSTGRES_PASSWORD = [System.Net.NetworkCredential]::new('', (Read-Host 'Fresh replacement database password' -AsSecureString)).Password
$env:COMPOSE_DISABLE_ENV_FILE = 'true'
$env:MNEMA_LOCAL_BUILD_ID = git rev-parse HEAD
docker compose --project-name mnema-replacement --file docker-compose.yml up -d --build
```

These commands disable implicit `.env` loading and fix the project/file explicitly.
The Compose source consumes only `MNEMA_LOCAL_*` settings, with no old `.env.local`,
`.env.public`, override files or legacy database volume. Its fresh named volume is
`mnema-replacement_replacement_postgres_data`. Startup does not migrate, stop or
remove an existing v1 stack.

| Setting | Default | Purpose |
|---|---|---|
| `MNEMA_LOCAL_POSTGRES_PASSWORD` | required | Fresh replacement DB password |
| `MNEMA_LOCAL_POSTGRES_DB` | `mnema` | Replacement database name |
| `MNEMA_LOCAL_POSTGRES_USER` | `mnema` | Replacement database user |
| `MNEMA_LOCAL_POSTGRES_PORT` | `55432` | Loopback PostgreSQL port |
| `MNEMA_LOCAL_IDENTITY_PORT` | `18081` | Loopback Identity HTTP port |
| `MNEMA_LOCAL_LEARNING_PORT` | `18080` | Loopback Learning HTTP port |
| `MNEMA_LOCAL_IDENTITY_ISSUER` | `https://localhost:18081` | HTTPS identity contract |
| `MNEMA_LOCAL_BUILD_ID` | `dev` | Reported source identity |

The issuer is an HTTPS identifier required by `IssuerContract`. Local actuator
transport is HTTP on loopback; no TLS listener or authentication flow is provisioned.
Set the build ID to the checked-out commit for diagnostic correlation and rebuild
after source changes; this local marker is not hosted release attestation.

```bash
curl -fsS http://127.0.0.1:18081/api/actuator/health/readiness
curl -fsS http://127.0.0.1:18081/api/actuator/info
curl -fsS http://127.0.0.1:18080/api/actuator/health/readiness
curl -fsS http://127.0.0.1:18080/api/actuator/info
docker compose --project-name mnema-replacement --file docker-compose.yml stop
```

Keep the same configuration for later Compose commands. `stop` preserves the new
volume. Password/DB/user changes after initialization are not PostgreSQL credential
rotation. Old volumes remain outside this workflow; removal or account/data transfer
requires its own explicit procedure. PostgreSQL 18 mounts `/var/lib/postgresql`
according to the [official image contract](https://hub.docker.com/_/postgres).
The project boundary and loopback publications follow
[Compose project naming](https://docs.docker.com/reference/compose-file/version-and-name/#name-top-level-element)
and [port definitions](https://docs.docker.com/reference/compose-file/services/#ports).

## Historical v1 self-host reference

The instructions below describe `v1-apache-final`, not the current checkout.
Use a separate checkout of that historical tag to consult its matching launcher
and Compose implementation. Current `mnema-local.sh` / `.ps1` and `mnema-public.sh`
/ `.ps1` refuse before filesystem or container mutation; #146 owns removal of their
remaining historical bodies. Hosted replacement requirements do not include v1
self-host compatibility.

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

## Prerequisites

Перед запуском убедитесь, что доступны:

- Docker Engine + Docker Compose plugin (`docker compose version`)
- если хотите GPU для Ollama и local TTS backends (`Qwen3-TTS`, `Kokoro`): Docker GPU runtime (`docker run --rm --gpus all nvidia/cuda:12.3.2-base-ubuntu22.04 nvidia-smi`)

### Linux (NVIDIA)

- Ubuntu/Debian: установите `nvidia-container-toolkit` из официального репозитория NVIDIA, затем `sudo nvidia-ctk runtime configure --runtime=docker` и перезапуск Docker.
- Fedora/RHEL: аналогично, через RPM repo NVIDIA + `nvidia-container-toolkit`, затем `sudo nvidia-ctk runtime configure --runtime=docker` и перезапуск Docker.

Если GPU runtime недоступен, bootstrap скрипт автоматически оставит Ollama на CPU и выведет предупреждение.

Скрипт:
- спрашивает минимальные креды для `Postgres` и `MinIO`;
- интерактивно (стрелками) выбирает:
  - primary/secondary text model;
  - optional vision model;
  - local TTS providers (`Piper`, `Qwen3-TTS`, `Kokoro`);
  - optional image model;
  - GPU device для Ollama при нескольких GPU;
- спрашивает режимы backend-ов:
  - audio: `local` (default, multi-provider TTS) / `ollama` (experimental) / `custom` / `none`;
  - image: `ollama` (experimental, default) / `diffusers` / `custom` / `none`;
- заполняет `OPENAI_*` модели и gateway-переменные (`REMOTE_OPENAI_BASE_URL`, `OLLAMA_AUDIO_EXPERIMENTAL`, `OLLAMA_IMAGE_EXPERIMENTAL`);
- автоматически проверяет доступность Docker GPU runtime и включает `gpus: all` для `ollama` (fallback на CPU с предупреждением, если runtime недоступен);
- генерирует `.env.local`;
- генерирует `.mnema/compose.ports.yml`;
- запускает `docker compose pull`, поднимает `ollama + local-ai-gateway` и выбранные local gateway dependencies;
- делает `ollama pull` только для Ollama text/vision/image моделей; `local-audio-gateway` сам подкачивает дефолтные local TTS assets при старте и догружает новые voices/model assets при первом реальном использовании;
- затем поднимает весь стек;
- включает профиль `SPRING_PROFILES_ACTIVE=dev,selfhost-local` для backend сервисов.
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
- `8091` (Local Audio Gateway, если выбран `local`)
- `8092` (Local Image Gateway, если выбран `diffusers`)

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
  - TTS идет через `local-audio-gateway` на локальные провайдеры (`Piper`, `Qwen3-TTS`, `Kokoro`), если выбран режим `local`;
  - image идет через Ollama OpenAI-compatible endpoint или через `local-image-gateway`, если выбран режим `diffusers`;
  - STT/video проксируются gateway в соответствующие локальные backends при их настройке;
  - персональные provider keys остаются доступны в UI (можно использовать одновременно system provider и внешние ключи).
  - runtime discovery endpoint: `GET /api/ai/runtime/capabilities`.

## Local AI Gateway

Gateway поднимается вместе со стеком и дает единый OpenAI-compatible endpoint для Mnema AI сервиса.

Локальные переменные в `.env.local`:

- `LOCAL_AUDIO_BASE_URL` — backend для `/v1/audio/*` (например встроенный `local-audio-gateway` или внешний OpenAI-compatible audio service)
- `LOCAL_AUDIO_MODELS` — локальные TTS model ids для встроенного `local-audio-gateway` (`piper-tts,qwen3-tts,kokoro-82m`)
- `LOCAL_AUDIO_PRELOAD` — preload дефолтных local TTS assets при старте gateway (`true` по умолчанию)
- `LOCAL_AUDIO_PIPER_VOICES` — дефолтные Piper voices, которые будут поставлены при старте и показаны в UI
- `LOCAL_AUDIO_QWEN_VOICES` — дефолтные Qwen speakers, которые будут показаны в UI после preload модели
- `LOCAL_AUDIO_KOKORO_VOICES` — дефолтные Kokoro voices, которые будут поставлены при старте
- `LOCAL_IMAGE_BASE_URL` — backend для `/v1/images/*` (если хотите вынести image отдельно)
- `LOCAL_IMAGE_DEFAULT_MODEL` — default model для встроенного `local-image-gateway`
- `LOCAL_VIDEO_BASE_URL` — backend для `/v1/videos*`
- `LOCAL_TTS_VOICES` — fallback список голосов через запятую, если audio backend не отдает `/v1/audio/voices`
- `LOCAL_AI_GATEWAY_TIMEOUT_SECONDS` — timeout для upstream запросов gateway (по умолчанию `600`)
- `REMOTE_OPENAI_BASE_URL` — внешний OpenAI endpoint (по умолчанию `https://api.openai.com`), используется gateway при наличии Bearer API key;
- `OLLAMA_AUDIO_EXPERIMENTAL` — включает fallback `/v1/audio/*` в Ollama (experimental, используется только если gateway подтвердил доступность Ollama `/v1/audio` endpoint-ов);
- `OLLAMA_IMAGE_EXPERIMENTAL` — включает fallback `/v1/images/*` в Ollama (experimental).

Если `LOCAL_*_BASE_URL` пустой, gateway пытается использовать Ollama для этого типа запросов.

Примечание: по официальной документации Ollama OpenAI-compat гарантирован для chat/responses/completions и experimental image endpoint; audio endpoint-ы остаются experimental/ограниченными в зависимости от сборки и моделей.

Для Linux-host audio backend используется `host.docker.internal` через `extra_hosts` в `local-ai-gateway`. Если после старта видите warning про недоступный audio backend, проверьте `LOCAL_AUDIO_BASE_URL` и что backend действительно слушает указанный порт.

## Local Audio Gateway

Режим `local` поднимает `local-audio-gateway` и выставляет `LOCAL_AUDIO_BASE_URL=http://local-audio-gateway:8091`.

Gateway реализует минимальный OpenAI-compatible контур:
- `GET /v1/models` возвращает локальные TTS models (`piper-tts`, `qwen3-tts`, `kokoro-82m`);
- `GET /v1/audio/voices` возвращает уже установленные / preload voices для UI;
- `POST /v1/audio/speech` генерирует `wav`, `mp3` или `ogg`.

Assets хранятся в volume `mnema_audio_data`. Первый старт preload-ит дефолтные assets, а любые новые voices/model assets догружаются лениво в момент первой генерации и затем переиспользуются из кеша.

Практический UX:
- в bootstrap-скрипте выбираются только local TTS providers, а не конкретные voices;
- в UI пользователь выбирает `TTS model`, а при необходимости вводит `custom voice` вручную;
- если voice еще не установлен, gateway сам пытается скачать/подготовить его при первом `POST /v1/audio/speech`, после чего voice появляется в `/v1/audio/voices` как локально доступный.

Проверка после запуска:

```bash
curl http://localhost:8090/v1/audio/voices
curl -sS http://localhost:8090/v1/audio/speech \
  -H 'Content-Type: application/json' \
  -d '{"model":"piper-tts","voice":"ru_RU-irina-medium","input":"Проверка локального синтеза речи.","response_format":"wav"}' \
  --output mnema-tts-sample.wav
```

## Local Image Gateway

Режим `diffusers` поднимает `local-image-gateway` и выставляет `LOCAL_IMAGE_BASE_URL=http://local-image-gateway:8092`.
Это простой fallback backend для `/v1/images/generations` на Diffusers/SD Turbo. По умолчанию image generation остается в режиме `ollama`, потому что Diffusers backend существенно тяжелее по Docker image, RAM/VRAM и времени первого скачивания модели.

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
