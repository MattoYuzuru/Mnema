#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK_DIR="$ROOT_DIR/.mnema"
ENV_FILE="$ROOT_DIR/.env.local"
OVERRIDE_FILE="$WORK_DIR/compose.ports.yml"
DRY_RUN="${MNEMA_DRY_RUN:-0}"

mkdir -p "$WORK_DIR"

command_exists() {
  command -v "$1" >/dev/null 2>&1
}

check_requirements() {
  if ! command_exists docker; then
    echo "[error] Docker is not installed or not in PATH."
    exit 1
  fi

  if ! docker compose version >/dev/null 2>&1; then
    echo "[error] Docker Compose plugin is not available (docker compose)."
    exit 1
  fi
}

docker_gpu_available() {
  docker run --rm --gpus all alpine:3.20 true >/dev/null 2>&1
}

detect_gpu_devices() {
  if ! command_exists nvidia-smi; then
    echo ""
    return
  fi
  nvidia-smi -L 2>/dev/null | awk -F'[: ]+' '/^GPU [0-9]+:/ {print $2}' | paste -sd, -
}

is_port_busy() {
  local port="$1"

  if command_exists ss; then
    ss -ltn "sport = :$port" 2>/dev/null | tail -n +2 | grep -q .
    return $?
  fi

  if command_exists lsof; then
    lsof -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1
    return $?
  fi

  return 1
}

next_free_port() {
  local port="$1"
  shift
  while is_port_busy "$port" || is_in_list "$port" "$@"; do
    port=$((port + 1))
  done
  echo "$port"
}

is_in_list() {
  local candidate="$1"
  shift
  local used
  for used in "$@"; do
    if [[ "$used" == "$candidate" ]]; then
      return 0
    fi
  done
  return 1
}

prompt_default() {
  local question="$1"
  local default_value="$2"
  local value
  read -r -p "$question [$default_value]: " value
  if [[ -z "${value// }" ]]; then
    echo "$default_value"
  else
    echo "$value"
  fi
}

prompt_choice() {
  local question="$1"
  local default_value="$2"
  local value
  while true; do
    read -r -p "$question [$default_value]: " value
    if [[ -z "${value// }" ]]; then
      value="$default_value"
    fi
    value="$(echo "$value" | tr '[:upper:]' '[:lower:]')"
    case "$value" in
      ollama|custom|skip)
        echo "$value"
        return
        ;;
      *)
        echo "[error] Supported values: ollama, custom, skip."
        ;;
    esac
  done
}

is_valid_identifier() {
  local value="$1"
  [[ "$value" =~ ^[A-Za-z0-9._-]{3,64}$ ]]
}

is_valid_password() {
  local value="$1"
  (( ${#value} >= 8 ))
}

prompt_identifier() {
  local label="$1"
  local default_value="$2"
  local value
  while true; do
    value="$(prompt_default "$label" "$default_value")"
    if is_valid_identifier "$value"; then
      echo "$value"
      return
    fi
    echo "[error] $label must be 3-64 chars and contain only letters, digits, dot, underscore, hyphen."
  done
}

prompt_password() {
  local label="$1"
  local default_value="$2"
  local value
  while true; do
    value="$(prompt_default "$label" "$default_value")"
    if is_valid_password "$value"; then
      echo "$value"
      return
    fi
    echo "[error] $label must be at least 8 characters."
  done
}

rand_hex() {
  local bytes="$1"
  if command_exists openssl; then
    openssl rand -hex "$bytes"
    return 0
  fi
  if command_exists head && command_exists od; then
    head -c "$bytes" /dev/urandom | od -An -tx1 | tr -d ' \n'
    return 0
  fi
  date +%s%N | sha256sum | cut -c1-$((bytes * 2))
}

rand_b64() {
  local bytes="$1"
  if command_exists openssl; then
    openssl rand -base64 "$bytes" | tr -d '\n'
    return 0
  fi
  if command_exists head; then
    head -c "$bytes" /dev/urandom | base64 | tr -d '\n'
    return 0
  fi
  rand_hex "$bytes"
}

write_env_file() {
  local postgres_user="$1"
  local postgres_password="$2"
  local postgres_db="$3"
  local postgres_port="$4"
  local minio_user="$5"
  local minio_password="$6"
  local openai_default_model="$7"
  local local_ai_gateway_port="$8"
  local openai_tts_model="$9"
  local openai_stt_model="${10}"
  local local_audio_base_url="${11}"
  local local_tts_voices="${12}"
  local ollama_gpu_enabled="${13}"
  local ollama_visible_gpus="${14}"

  cat > "$ENV_FILE" <<ENV
POSTGRES_DB=$postgres_db
POSTGRES_USER=$postgres_user
POSTGRES_PASSWORD=$postgres_password
POSTGRES_PORT=$postgres_port

SPRING_DATASOURCE_USERNAME=$postgres_user
SPRING_DATASOURCE_PASSWORD=$postgres_password

AUTH_ISSUER=http://localhost:${AUTH_PORT}
AUTH_ISSUER_URI=http://localhost:${AUTH_PORT}

GOOGLE_CLIENT_ID=disabled-google
GOOGLE_CLIENT_SECRET=disabled-google-secret
GH_CLIENT_ID=disabled-github
GH_CLIENT_SECRET=disabled-github-secret
YANDEX_CLIENT_ID=disabled-yandex
YANDEX_CLIENT_SECRET=disabled-yandex-secret

TURNSTILE_SITE_KEY=
TURNSTILE_SECRET_KEY=

MEDIA_INTERNAL_TOKEN=$(rand_hex 24)

AWS_REGION=us-east-1
AWS_BUCKET_NAME=mnema-local
AWS_ENDPOINT=http://minio:9000
AWS_PUBLIC_ENDPOINT=http://localhost:${MINIO_API_PORT}
AWS_PATH_STYLE_ACCESS=true
AWS_ACCESS_KEY_ID=$minio_user
AWS_SECRET_ACCESS_KEY=$minio_password

CORE_BASE_URL=http://core:8080/api/core
MEDIA_BASE_URL=http://media:8080/api/media

AI_PROVIDER=ollama
AI_SYSTEM_MANAGED_PROVIDER_ENABLED=true
AI_SYSTEM_PROVIDER_NAME=ollama
AI_OLLAMA_ENABLED=true
AI_VAULT_MASTER_KEY=$(rand_b64 32)
AI_VAULT_KEY_ID=local-v1

OPENAI_BASE_URL=http://local-ai-gateway:8089
OLLAMA_BASE_URL=http://ollama:11434
OPENAI_SYSTEM_API_KEY=
OPENAI_DEFAULT_MODEL=$openai_default_model
OPENAI_TTS_MODEL=$openai_tts_model
OPENAI_STT_MODEL=$openai_stt_model
OPENAI_IMAGE_MODEL=
OPENAI_VIDEO_MODEL=

LOCAL_AI_GATEWAY_PORT=$local_ai_gateway_port
LOCAL_AI_GATEWAY_TIMEOUT_SECONDS=600
LOCAL_AUDIO_BASE_URL=$local_audio_base_url
LOCAL_IMAGE_BASE_URL=
LOCAL_VIDEO_BASE_URL=
LOCAL_TTS_VOICES=$local_tts_voices
OLLAMA_GPU_ENABLED=$ollama_gpu_enabled
OLLAMA_VISIBLE_GPUS=$ollama_visible_gpus

GEMINI_BASE_URL=
GEMINI_DEFAULT_MODEL=

ANTHROPIC_BASE_URL=
ANTHROPIC_API_VERSION=2023-06-01
ANTHROPIC_DEFAULT_MODEL=

QWEN_BASE_URL=
QWEN_DASHSCOPE_BASE_URL=

GROK_BASE_URL=

SPRING_CACHE_TYPE=redis
REDIS_HOST=redis
REDIS_PORT=6379
APP_ENV=local

MINIO_ROOT_USER=$minio_user
MINIO_ROOT_PASSWORD=$minio_password
ENV
}

write_override_file() {
  local openai_default_model="$1"
  local ollama_gpu_enabled="$2"
  local ollama_gpu_block=""
  if [[ "$ollama_gpu_enabled" == "true" ]]; then
    ollama_gpu_block=$'    gpus: all\n    environment:\n      NVIDIA_VISIBLE_DEVICES: "${OLLAMA_VISIBLE_GPUS}"\n      NVIDIA_DRIVER_CAPABILITIES: "compute,utility"'
  fi
  cat > "$OVERRIDE_FILE" <<YAML
services:
  postgres:
    env_file:
      - "${ENV_FILE}"
    ports:
      - "${POSTGRES_PORT}:5432"

  redis:
    ports:
      - "${REDIS_PORT}:6379"

  auth:
    environment:
      SPRING_PROFILES_ACTIVE: dev,selfhost-local
    ports:
      - "${AUTH_PORT}:8080"

  user:
    environment:
      SPRING_PROFILES_ACTIVE: dev,selfhost-local
    ports:
      - "${USER_PORT}:8080"

  core:
    environment:
      SPRING_PROFILES_ACTIVE: dev,selfhost-local
    ports:
      - "${CORE_PORT}:8080"

  media:
    environment:
      SPRING_PROFILES_ACTIVE: dev,selfhost-local
    depends_on:
      minio:
        condition: service_healthy
    ports:
      - "${MEDIA_PORT}:8080"

  import:
    environment:
      SPRING_PROFILES_ACTIVE: dev,selfhost-local
    ports:
      - "${IMPORT_PORT}:8080"

  ai:
    environment:
      SPRING_PROFILES_ACTIVE: dev,selfhost-local
      AI_SYSTEM_MANAGED_PROVIDER_ENABLED: "true"
      AI_SYSTEM_PROVIDER_NAME: "ollama"
      AI_OLLAMA_ENABLED: "true"
      OLLAMA_BASE_URL: "http://ollama:11434"
      OPENAI_BASE_URL: "http://local-ai-gateway:8089"
      OPENAI_SYSTEM_API_KEY: ""
      OPENAI_DEFAULT_MODEL: "${openai_default_model}"
      OPENAI_TTS_MODEL: "\${OPENAI_TTS_MODEL}"
      OPENAI_STT_MODEL: "\${OPENAI_STT_MODEL}"
    ports:
      - "${AI_PORT}:8080"

  frontend:
    ports:
      - "${FRONTEND_PORT}:80"

  minio:
    networks: [ mnema_net ]
    image: minio/minio:latest
    command: [ "server", "/data", "--console-address", ":9001" ]
    environment:
      MINIO_ROOT_USER: "${MINIO_USER}"
      MINIO_ROOT_PASSWORD: "${MINIO_PASSWORD}"
    ports:
      - "${MINIO_API_PORT}:9000"
      - "${MINIO_CONSOLE_PORT}:9001"
    volumes:
      - mnema_minio_data:/data
    healthcheck:
      test: [ "CMD", "curl", "-f", "http://localhost:9000/minio/health/live" ]
      interval: 5s
      timeout: 3s
      retries: 20

  local-ai-gateway:
    networks: [ mnema_net ]
    build:
      context: ./scripts/local-ai-gateway
      dockerfile: Dockerfile
    environment:
      OLLAMA_BASE_URL: "http://ollama:11434"
      AUDIO_BASE_URL: "\${LOCAL_AUDIO_BASE_URL}"
      IMAGE_BASE_URL: "\${LOCAL_IMAGE_BASE_URL}"
      VIDEO_BASE_URL: "\${LOCAL_VIDEO_BASE_URL}"
      GATEWAY_DEFAULT_TEXT_MODEL: "${OPENAI_DEFAULT_MODEL}"
      GATEWAY_DEFAULT_TTS_MODEL: "\${OPENAI_TTS_MODEL}"
      GATEWAY_DEFAULT_STT_MODEL: "\${OPENAI_STT_MODEL}"
      GATEWAY_DEFAULT_IMAGE_MODEL: "\${OPENAI_IMAGE_MODEL}"
      GATEWAY_DEFAULT_VIDEO_MODEL: "\${OPENAI_VIDEO_MODEL}"
      GATEWAY_TTS_VOICES: "\${LOCAL_TTS_VOICES}"
      GATEWAY_TIMEOUT_SECONDS: "\${LOCAL_AI_GATEWAY_TIMEOUT_SECONDS}"
    depends_on:
      ollama:
        condition: service_started
    ports:
      - "${LOCAL_AI_GATEWAY_PORT}:8089"
    healthcheck:
      test: [ "CMD", "curl", "-f", "http://localhost:8089/health" ]
      interval: 5s
      timeout: 3s
      retries: 20

  minio-init:
    networks: [ mnema_net ]
    image: minio/mc:latest
    depends_on:
      minio:
        condition: service_healthy
    entrypoint:
      - /bin/sh
      - -c
      - >-
        mc alias set local http://minio:9000 ${MINIO_USER} ${MINIO_PASSWORD};
        mc mb -p local/mnema-local || true;
        mc anonymous set private local/mnema-local;

  ollama:
    networks: [ mnema_net ]
    image: ollama/ollama:latest
${ollama_gpu_block}
    ports:
      - "${OLLAMA_PORT}:11434"
    volumes:
      - mnema_ollama_data:/root/.ollama

volumes:
  mnema_minio_data:
  mnema_ollama_data:
YAML
}

print_port_info() {
  local name="$1"
  local default_port="$2"
  local final_port="$3"
  if [[ "$default_port" == "$final_port" ]]; then
    echo "[port] $name: $final_port"
  else
    echo "[port] $name: $default_port is busy, using $final_port"
  fi
}

recommend_ollama_model() {
  local mem_kb
  mem_kb="$(grep -E '^MemTotal:' /proc/meminfo 2>/dev/null | awk '{print $2}')"
  if [[ -z "${mem_kb:-}" ]]; then
    echo "qwen3:8b"
    return
  fi
  local mem_gb=$((mem_kb / 1024 / 1024))
  if (( mem_gb < 12 )); then
    echo "qwen3:4b"
  elif (( mem_gb < 24 )); then
    echo "qwen3:8b"
  else
    echo "qwen3:14b"
  fi
}

recommend_ollama_text_backup() {
  local mem_kb
  mem_kb="$(grep -E '^MemTotal:' /proc/meminfo 2>/dev/null | awk '{print $2}')"
  if [[ -z "${mem_kb:-}" ]]; then
    echo "qwen2.5:7b"
    return
  fi
  local mem_gb=$((mem_kb / 1024 / 1024))
  if (( mem_gb < 12 )); then
    echo "qwen2.5:3b"
  elif (( mem_gb < 24 )); then
    echo "qwen2.5:7b"
  else
    echo "qwen3:8b"
  fi
}

recommend_ollama_vision_model() {
  local mem_kb
  mem_kb="$(grep -E '^MemTotal:' /proc/meminfo 2>/dev/null | awk '{print $2}')"
  if [[ -z "${mem_kb:-}" ]]; then
    echo "qwen2.5vl:3b"
    return
  fi
  local mem_gb=$((mem_kb / 1024 / 1024))
  if (( mem_gb < 16 )); then
    echo "qwen2.5vl:3b"
  elif (( mem_gb < 28 )); then
    echo "qwen2.5vl:7b"
  else
    echo "minicpm-v:8b"
  fi
}

print_ollama_next_commands() {
  local compose_cmd="docker compose --env-file $ENV_FILE -f docker-compose.yml -f $OVERRIDE_FILE"
  echo "[next] Inspect local models:"
  echo "       $compose_cmd exec -T ollama ollama list"
  echo "[next] Pull another model manually:"
  echo "       $compose_cmd exec -T ollama ollama pull <model>"
  echo "[next] Run model interactively:"
  echo "       $compose_cmd exec -T ollama ollama run <model>"
  echo "[next] Check via API (/api/tags):"
  echo "       curl http://localhost:${OLLAMA_PORT}/api/tags"
  echo "[next] Check gateway models/voices:"
  echo "       curl http://localhost:${LOCAL_AI_GATEWAY_PORT}/v1/models"
  echo "       curl http://localhost:${LOCAL_AI_GATEWAY_PORT}/v1/audio/voices"
}

check_requirements

echo "== Mnema local bootstrap =="
echo "The script will detect busy ports and move to the next free port automatically."

default_db_user="mnema"
default_db_name="mnema"
default_db_password="mnema_local"
default_minio_user="mnema"
default_minio_password="mnema_minio_local"

DB_USER="$(prompt_identifier 'Postgres user' "$default_db_user")"
DB_NAME="$(prompt_identifier 'Postgres database' "$default_db_name")"
DB_PASSWORD="$(prompt_password 'Postgres password' "$default_db_password")"
MINIO_USER="$(prompt_identifier 'MinIO root user' "$default_minio_user")"
MINIO_PASSWORD="$(prompt_password 'MinIO root password' "$default_minio_password")"
STARTER_MODEL_RECOMMENDED="$(recommend_ollama_model)"
OPENAI_DEFAULT_MODEL="$(prompt_default 'Starter Ollama text model' "$STARTER_MODEL_RECOMMENDED")"
echo "[setup] Offline audio backend for TTS/STT"
AUDIO_BACKEND_MODE="$(prompt_choice 'Audio backend mode (ollama/custom/skip)' 'ollama')"
OPENAI_TTS_MODEL=""
OPENAI_STT_MODEL=""
LOCAL_AUDIO_BASE_URL=""
LOCAL_TTS_VOICES=""
if [[ "$AUDIO_BACKEND_MODE" == "ollama" ]]; then
  OPENAI_TTS_MODEL="$(prompt_default 'Ollama TTS model' 'kokoro:8b')"
  OPENAI_STT_MODEL="$(prompt_default 'Ollama STT model (optional)' '')"
  LOCAL_TTS_VOICES="$(prompt_default 'Fallback TTS voices comma-separated (optional)' 'af_sarah,af_bella')"
elif [[ "$AUDIO_BACKEND_MODE" == "custom" ]]; then
  LOCAL_AUDIO_BASE_URL="$(prompt_default 'Local audio backend URL (inside docker network)' 'http://host.docker.internal:8000')"
  OPENAI_TTS_MODEL="$(prompt_default 'Default TTS model (optional)' 'kokoro')"
  OPENAI_STT_MODEL="$(prompt_default 'Default STT model (optional)' 'whisper-large-v3-turbo')"
  LOCAL_TTS_VOICES="$(prompt_default 'Fallback TTS voices comma-separated (optional)' '')"
fi

OLLAMA_GPU_ENABLED="false"
OLLAMA_VISIBLE_GPUS="all"
GPU_DEVICES_DETECTED="$(detect_gpu_devices)"
if docker_gpu_available; then
  OLLAMA_GPU_ENABLED="true"
  if [[ -n "${GPU_DEVICES_DETECTED:-}" ]]; then
    device_count="$(echo "$GPU_DEVICES_DETECTED" | awk -F',' '{print NF}')"
    if (( device_count > 1 )); then
      echo "[info] Detected multiple GPUs: ${GPU_DEVICES_DETECTED}"
      OLLAMA_VISIBLE_GPUS="$(prompt_default 'GPU devices for Ollama (all or comma-separated indices)' 'all')"
    fi
  fi
else
  echo "[warn] Docker GPU runtime is not available. Ollama will run on CPU."
fi

POSTGRES_PORT="$(next_free_port 5432)"
REDIS_PORT="$(next_free_port 6379 "$POSTGRES_PORT")"
AUTH_PORT="$(next_free_port 8083 "$POSTGRES_PORT" "$REDIS_PORT")"
USER_PORT="$(next_free_port 8084 "$POSTGRES_PORT" "$REDIS_PORT" "$AUTH_PORT")"
CORE_PORT="$(next_free_port 8085 "$POSTGRES_PORT" "$REDIS_PORT" "$AUTH_PORT" "$USER_PORT")"
MEDIA_PORT="$(next_free_port 8086 "$POSTGRES_PORT" "$REDIS_PORT" "$AUTH_PORT" "$USER_PORT" "$CORE_PORT")"
IMPORT_PORT="$(next_free_port 8087 "$POSTGRES_PORT" "$REDIS_PORT" "$AUTH_PORT" "$USER_PORT" "$CORE_PORT" "$MEDIA_PORT")"
AI_PORT="$(next_free_port 8088 "$POSTGRES_PORT" "$REDIS_PORT" "$AUTH_PORT" "$USER_PORT" "$CORE_PORT" "$MEDIA_PORT" "$IMPORT_PORT")"
FRONTEND_PORT="$(next_free_port 3005 "$POSTGRES_PORT" "$REDIS_PORT" "$AUTH_PORT" "$USER_PORT" "$CORE_PORT" "$MEDIA_PORT" "$IMPORT_PORT" "$AI_PORT")"
MINIO_API_PORT="$(next_free_port 9000 "$POSTGRES_PORT" "$REDIS_PORT" "$AUTH_PORT" "$USER_PORT" "$CORE_PORT" "$MEDIA_PORT" "$IMPORT_PORT" "$AI_PORT" "$FRONTEND_PORT")"
MINIO_CONSOLE_PORT="$(next_free_port 9001 "$POSTGRES_PORT" "$REDIS_PORT" "$AUTH_PORT" "$USER_PORT" "$CORE_PORT" "$MEDIA_PORT" "$IMPORT_PORT" "$AI_PORT" "$FRONTEND_PORT" "$MINIO_API_PORT")"
OLLAMA_PORT="$(next_free_port 11434 "$POSTGRES_PORT" "$REDIS_PORT" "$AUTH_PORT" "$USER_PORT" "$CORE_PORT" "$MEDIA_PORT" "$IMPORT_PORT" "$AI_PORT" "$FRONTEND_PORT" "$MINIO_API_PORT" "$MINIO_CONSOLE_PORT")"
LOCAL_AI_GATEWAY_PORT="$(next_free_port 8090 "$POSTGRES_PORT" "$REDIS_PORT" "$AUTH_PORT" "$USER_PORT" "$CORE_PORT" "$MEDIA_PORT" "$IMPORT_PORT" "$AI_PORT" "$FRONTEND_PORT" "$MINIO_API_PORT" "$MINIO_CONSOLE_PORT" "$OLLAMA_PORT")"

print_port_info "postgres" "5432" "$POSTGRES_PORT"
print_port_info "redis" "6379" "$REDIS_PORT"
print_port_info "auth" "8083" "$AUTH_PORT"
print_port_info "user" "8084" "$USER_PORT"
print_port_info "core" "8085" "$CORE_PORT"
print_port_info "media" "8086" "$MEDIA_PORT"
print_port_info "import" "8087" "$IMPORT_PORT"
print_port_info "ai" "8088" "$AI_PORT"
print_port_info "frontend" "3005" "$FRONTEND_PORT"
print_port_info "minio-api" "9000" "$MINIO_API_PORT"
print_port_info "minio-console" "9001" "$MINIO_CONSOLE_PORT"
print_port_info "ollama" "11434" "$OLLAMA_PORT"
print_port_info "local-ai-gateway" "8090" "$LOCAL_AI_GATEWAY_PORT"

write_env_file "$DB_USER" "$DB_PASSWORD" "$DB_NAME" "$POSTGRES_PORT" "$MINIO_USER" "$MINIO_PASSWORD" "$OPENAI_DEFAULT_MODEL" "$LOCAL_AI_GATEWAY_PORT" "$OPENAI_TTS_MODEL" "$OPENAI_STT_MODEL" "$LOCAL_AUDIO_BASE_URL" "$LOCAL_TTS_VOICES" "$OLLAMA_GPU_ENABLED" "$OLLAMA_VISIBLE_GPUS"
write_override_file "$OPENAI_DEFAULT_MODEL" "$OLLAMA_GPU_ENABLED"

echo "[info] Generated: $ENV_FILE"
echo "[info] Generated: $OVERRIDE_FILE"
if [[ "$DRY_RUN" == "1" ]]; then
  echo "[info] Dry run enabled (MNEMA_DRY_RUN=1), skipping docker compose up."
  exit 0
fi

echo "[info] Starting containers..."

cd "$ROOT_DIR"
docker compose --env-file "$ENV_FILE" -f docker-compose.yml -f "$OVERRIDE_FILE" up -d --build

echo "[ok] Mnema is running"
echo "[ok] Frontend: http://localhost:${FRONTEND_PORT}"
echo "[ok] MinIO API: http://localhost:${MINIO_API_PORT}"
echo "[ok] MinIO Console: http://localhost:${MINIO_CONSOLE_PORT}"
echo "[ok] Ollama API: http://localhost:${OLLAMA_PORT}"
echo "[ok] Local AI Gateway: http://localhost:${LOCAL_AI_GATEWAY_PORT}"
echo "[info] Audio backend mode: ${AUDIO_BACKEND_MODE}"
echo "[info] Ollama GPU enabled: ${OLLAMA_GPU_ENABLED}"
if [[ "$OLLAMA_GPU_ENABLED" == "true" ]]; then
  echo "[info] Ollama visible GPUs: ${OLLAMA_VISIBLE_GPUS}"
fi
if [[ "$AUDIO_BACKEND_MODE" == "custom" ]]; then
  echo "[info] Audio backend URL: ${LOCAL_AUDIO_BASE_URL}"
fi

if command_exists curl; then
  if curl -fsS "http://localhost:${OLLAMA_PORT}/api/tags" >/dev/null 2>&1; then
    echo "[ok] Ollama is reachable."
    local_model="$OPENAI_DEFAULT_MODEL"
    backup_model="$(recommend_ollama_text_backup)"
    vision_model="$(recommend_ollama_vision_model)"
    echo "[info] Recommended starter text model: ${local_model}"
    echo "[info] Optional backup text model: ${backup_model}"
    echo "[info] Optional vision model (OCR/image understanding): ${vision_model}"
    echo "[info] Notes:"
    echo "       - Ollama OpenAI-compatible endpoints currently cover text/vision + embeddings + experimental images."
    echo "       - STT/TTS/video in Mnema local usually require separate local services (whisper.cpp, Piper, ComfyUI pipelines)."
    read -r -p "Pull starter text model now? [y/N]: " pull_choice
    if [[ "${pull_choice:-}" =~ ^[Yy]$ ]]; then
      docker compose --env-file "$ENV_FILE" -f docker-compose.yml -f "$OVERRIDE_FILE" exec -T ollama ollama pull "$local_model" || true
    fi
    read -r -p "Pull backup text model (${backup_model}) too? [y/N]: " pull_backup
    if [[ "${pull_backup:-}" =~ ^[Yy]$ ]]; then
      docker compose --env-file "$ENV_FILE" -f docker-compose.yml -f "$OVERRIDE_FILE" exec -T ollama ollama pull "$backup_model" || true
    fi
    read -r -p "Pull vision model (${vision_model}) too? [y/N]: " pull_vision
    if [[ "${pull_vision:-}" =~ ^[Yy]$ ]]; then
      docker compose --env-file "$ENV_FILE" -f docker-compose.yml -f "$OVERRIDE_FILE" exec -T ollama ollama pull "$vision_model" || true
    fi
    if [[ "$AUDIO_BACKEND_MODE" == "ollama" && -n "${OPENAI_TTS_MODEL:-}" ]]; then
      read -r -p "Pull TTS model (${OPENAI_TTS_MODEL}) too? [y/N]: " pull_tts
      if [[ "${pull_tts:-}" =~ ^[Yy]$ ]]; then
        docker compose --env-file "$ENV_FILE" -f docker-compose.yml -f "$OVERRIDE_FILE" exec -T ollama ollama pull "$OPENAI_TTS_MODEL" || true
      fi
    fi
    if [[ "$AUDIO_BACKEND_MODE" == "ollama" && -n "${OPENAI_STT_MODEL:-}" ]]; then
      read -r -p "Pull STT model (${OPENAI_STT_MODEL}) too? [y/N]: " pull_stt
      if [[ "${pull_stt:-}" =~ ^[Yy]$ ]]; then
        docker compose --env-file "$ENV_FILE" -f docker-compose.yml -f "$OVERRIDE_FILE" exec -T ollama ollama pull "$OPENAI_STT_MODEL" || true
      fi
    fi
    print_ollama_next_commands
  else
    echo "[warn] Ollama API is not reachable yet. You can retry later with:"
    echo "       docker compose --env-file $ENV_FILE -f docker-compose.yml -f $OVERRIDE_FILE exec -T ollama ollama pull qwen3:8b"
    print_ollama_next_commands
  fi
fi

echo "[ok] To stop: docker compose --env-file $ENV_FILE -f docker-compose.yml -f $OVERRIDE_FILE down"
