#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK_DIR="$ROOT_DIR/.mnema"
ENV_FILE="$ROOT_DIR/.env.local"
OVERRIDE_FILE="$WORK_DIR/compose.ports.yml"
DRY_RUN="${MNEMA_DRY_RUN:-0}"

COLOR_RESET=$'\033[0m'
COLOR_DIM=$'\033[2m'
COLOR_LAVENDER=$'\033[38;5;183m'
COLOR_GREEN=$'\033[32m'
COLOR_YELLOW=$'\033[33m'
COLOR_CYAN=$'\033[36m'

mkdir -p "$WORK_DIR"

command_exists() {
  command -v "$1" >/dev/null 2>&1
}

supports_menu() {
  [[ -t 0 && -t 1 && "${TERM:-}" != "dumb" ]]
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

detect_gpu_entries() {
  if ! command_exists nvidia-smi; then
    return
  fi
  nvidia-smi --query-gpu=index,name,memory.total --format=csv,noheader,nounits 2>/dev/null \
    | awk -F',' '{gsub(/^ +| +$/, "", $1); gsub(/^ +| +$/, "", $2); gsub(/^ +| +$/, "", $3); print $1 "|" $2 "|" $3}'
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

prompt_required() {
  local question="$1"
  local value
  while true; do
    read -r -p "$question: " value
    if [[ -n "${value// }" ]]; then
      echo "$value"
      return
    fi
    echo "[error] Value is required."
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

menu_select() {
  local __result_var="$1"
  local title="$2"
  local default_index="$3"
  local options_name="$4"
  local -n options_ref="$options_name"

  local option_count="${#options_ref[@]}"
  if (( option_count == 0 )); then
    printf -v "$__result_var" '%s' ""
    return
  fi

  if (( default_index < 0 || default_index >= option_count )); then
    default_index=0
  fi

  if ! supports_menu; then
    echo "$title"
    local i
    for (( i=0; i<option_count; i++ )); do
      IFS='|' read -r value label hint color <<<"${options_ref[$i]}"
      local line="  $((i + 1)). $label"
      if [[ -n "${hint:-}" ]]; then
        line+=" (${hint})"
      fi
      if (( i == default_index )); then
        line+=" [default]"
      fi
      echo "$line"
    done
    local choice
    read -r -p "Choose option number [$((default_index + 1))]: " choice
    if [[ -z "${choice// }" ]]; then
      choice="$((default_index + 1))"
    fi
    if ! [[ "$choice" =~ ^[0-9]+$ ]] || (( choice < 1 || choice > option_count )); then
      choice="$((default_index + 1))"
    fi
    IFS='|' read -r selected_value _ <<<"${options_ref[$((choice - 1))]}"
    printf -v "$__result_var" '%s' "$selected_value"
    return
  fi

  local selected="$default_index"
  local rendered_lines=0

  while true; do
    if (( rendered_lines > 0 )); then
      printf '\033[%sA' "$rendered_lines"
    fi

    printf '\033[0J'
    printf "%s\n" "$title"
    printf "%b\n" "${COLOR_DIM}Use Up/Down arrows and Enter.${COLOR_RESET}"

    local i
    for (( i=0; i<option_count; i++ )); do
      IFS='|' read -r value label hint color <<<"${options_ref[$i]}"
      local option_color="${color:-$COLOR_RESET}"
      local marker="  "
      if (( i == selected )); then
        marker="${COLOR_LAVENDER}->${COLOR_RESET}"
        option_color="${COLOR_LAVENDER}"
      fi
      local line="${marker} ${option_color}${label}${COLOR_RESET}"
      if [[ -n "${hint:-}" ]]; then
        line+=" ${COLOR_DIM}${hint}${COLOR_RESET}"
      fi
      printf "%b\n" "$line"
    done

    rendered_lines=$((option_count + 2))

    local key
    IFS= read -rsn1 key
    case "$key" in
      $'\x1b')
        IFS= read -rsn2 key || true
        case "$key" in
          '[A')
            if (( selected == 0 )); then
              selected=$((option_count - 1))
            else
              selected=$((selected - 1))
            fi
            ;;
          '[B')
            if (( selected == option_count - 1 )); then
              selected=0
            else
              selected=$((selected + 1))
            fi
            ;;
        esac
        ;;
      ''|$'\n'|$'\r')
        local selected_value
        IFS='|' read -r selected_value _ <<<"${options_ref[$selected]}"
        printf '\033[%sA\033[0J' "$rendered_lines"
        printf "%s %b%s%b\n" "$title" "$COLOR_LAVENDER" "$selected_value" "$COLOR_RESET"
        printf -v "$__result_var" '%s' "$selected_value"
        return
        ;;
    esac
  done
}

normalize_optional_model() {
  local value="$1"
  local lowered
  lowered="$(echo "$value" | tr '[:upper:]' '[:lower:]' | xargs)"
  if [[ "$lowered" == "none" || "$lowered" == "skip" || "$lowered" == "no" ]]; then
    echo ""
    return
  fi
  echo "$(echo "$value" | xargs)"
}

model_exists_in_ollama_registry() {
  local model="$1"
  if [[ -z "${model// }" ]] || ! command_exists curl; then
    return 0
  fi
  local repo="${model%%:*}"
  repo="${repo%%@*}"
  curl -fsSI "https://ollama.com/library/${repo}" >/dev/null 2>&1
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
  local openai_image_model="${11}"
  local local_audio_base_url="${12}"
  local local_image_base_url="${13}"
  local local_tts_voices="${14}"
  local ollama_gpu_enabled="${15}"
  local ollama_visible_gpus="${16}"
  local remote_openai_base_url="${17}"
  local ollama_audio_experimental="${18}"
  local ollama_image_experimental="${19}"

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
OPENAI_TTS_VOICE=
OPENAI_TTS_FORMAT=wav
OPENAI_STT_MODEL=$openai_stt_model
OPENAI_IMAGE_MODEL=$openai_image_model
OPENAI_VIDEO_MODEL=

LOCAL_AI_GATEWAY_PORT=$local_ai_gateway_port
LOCAL_AI_GATEWAY_TIMEOUT_SECONDS=600
LOCAL_AUDIO_BASE_URL=$local_audio_base_url
LOCAL_IMAGE_BASE_URL=$local_image_base_url
LOCAL_VIDEO_BASE_URL=
LOCAL_TTS_VOICES=$local_tts_voices
REMOTE_OPENAI_BASE_URL=$remote_openai_base_url
OLLAMA_AUDIO_EXPERIMENTAL=$ollama_audio_experimental
OLLAMA_IMAGE_EXPERIMENTAL=$ollama_image_experimental
OLLAMA_GPU_ENABLED=$ollama_gpu_enabled
OLLAMA_VISIBLE_GPUS=$ollama_visible_gpus

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
      OPENAI_TTS_VOICE: "\${OPENAI_TTS_VOICE}"
      OPENAI_TTS_FORMAT: "\${OPENAI_TTS_FORMAT}"
      OPENAI_STT_MODEL: "\${OPENAI_STT_MODEL}"
      OPENAI_IMAGE_MODEL: "\${OPENAI_IMAGE_MODEL}"
      OPENAI_VIDEO_MODEL: "\${OPENAI_VIDEO_MODEL}"
    ports:
      - "${AI_PORT}:8080"

  frontend:
    environment:
      MNEMA_FEATURE_AI_SYSTEM_PROVIDER_ENABLED: "true"
      MNEMA_FEATURE_AI_SYSTEM_PROVIDER_NAME: "ollama"
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
    extra_hosts:
      - "host.docker.internal:host-gateway"
    build:
      context: ./scripts/local-ai-gateway
      dockerfile: Dockerfile
    environment:
      OLLAMA_BASE_URL: "http://ollama:11434"
      REMOTE_OPENAI_BASE_URL: "\${REMOTE_OPENAI_BASE_URL}"
      OLLAMA_AUDIO_EXPERIMENTAL: "\${OLLAMA_AUDIO_EXPERIMENTAL}"
      OLLAMA_IMAGE_EXPERIMENTAL: "\${OLLAMA_IMAGE_EXPERIMENTAL}"
      AUDIO_BASE_URL: "\${LOCAL_AUDIO_BASE_URL}"
      IMAGE_BASE_URL: "\${LOCAL_IMAGE_BASE_URL}"
      VIDEO_BASE_URL: "\${LOCAL_VIDEO_BASE_URL}"
      GATEWAY_DEFAULT_TEXT_MODEL: "${openai_default_model}"
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

recommend_secondary_model() {
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
    echo "llama3.1:8b"
  fi
}

recommend_vision_model() {
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

wait_for_ollama() {
  local timeout_s="$1"
  local started
  started="$(date +%s)"
  while true; do
    if curl -fsS "http://localhost:${OLLAMA_PORT}/api/tags" >/dev/null 2>&1; then
      return 0
    fi
    if (( $(date +%s) - started >= timeout_s )); then
      return 1
    fi
    sleep 2
  done
}

compose_cmd() {
  docker compose --env-file "$ENV_FILE" -f docker-compose.yml -f "$OVERRIDE_FILE" "$@"
}

build_model_pull_plan() {
  local -n __result_ref="$1"
  shift
  __result_ref=()
  local model
  for model in "$@"; do
    model="$(normalize_optional_model "$model")"
    if [[ -z "${model:-}" ]]; then
      continue
    fi
    local exists=0
    local current
    for current in "${__result_ref[@]}"; do
      if [[ "$current" == "$model" ]]; then
        exists=1
        break
      fi
    done
    if (( exists == 0 )); then
      __result_ref+=("$model")
    fi
  done
}

pull_models() {
  local -a models=("$@")
  if (( ${#models[@]} == 0 )); then
    echo "[info] No models selected for pull."
    return
  fi

  echo "[info] Pulling selected Ollama models..."
  local model
  for model in "${models[@]}"; do
    if ! model_exists_in_ollama_registry "$model"; then
      echo "[warn] Model '${model}' was not found in ollama.com/library (trying pull anyway)."
    fi
    if compose_cmd exec -T ollama ollama pull "$model"; then
      echo "[ok] pulled: $model"
    else
      echo "[warn] failed to pull: $model"
    fi
  done
}

print_ollama_next_commands() {
  local compose_cmd_text="docker compose --env-file $ENV_FILE -f docker-compose.yml -f $OVERRIDE_FILE"
  echo "[next] Inspect local models:"
  echo "       $compose_cmd_text exec -T ollama ollama list"
  echo "[next] Pull another model manually:"
  echo "       $compose_cmd_text exec -T ollama ollama pull <model>"
  echo "[next] Run model interactively:"
  echo "       $compose_cmd_text exec -T ollama ollama run <model>"
  echo "[next] Check Ollama API:"
  echo "       curl http://localhost:${OLLAMA_PORT}/api/tags"
  echo "[next] Check Gateway models/voices:"
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
SECONDARY_MODEL_RECOMMENDED="$(recommend_secondary_model)"
VISION_MODEL_RECOMMENDED="$(recommend_vision_model)"

AUDIO_MODE_OPTIONS=(
  "ollama|Ollama experimental|STT/TTS via Ollama OpenAI compatibility"
  "custom|Custom audio backend|Use external OpenAI-compatible /v1/audio service"
  "none|No audio backend|Disable STT/TTS defaults"
)
AUDIO_BACKEND_MODE=""
menu_select AUDIO_BACKEND_MODE "Select audio backend mode" 0 AUDIO_MODE_OPTIONS

IMAGE_MODE_OPTIONS=(
  "ollama|Ollama experimental image|Use Ollama /v1/images/generations"
  "custom|Custom image backend|Use external OpenAI-compatible /v1/images service"
  "none|No image backend|Disable image defaults"
)
IMAGE_BACKEND_MODE=""
menu_select IMAGE_BACKEND_MODE "Select image backend mode" 0 IMAGE_MODE_OPTIONS

TEXT_MODEL_OPTIONS=(
  "${STARTER_MODEL_RECOMMENDED}|Recommended text model|Primary text model"
  "qwen3:4b|qwen3:4b|Light tier"
  "qwen3:8b|qwen3:8b|Balanced tier"
  "qwen3:14b|qwen3:14b|Quality tier"
  "llama3.1:8b|llama3.1:8b|Alternative"
  "custom|Custom model|Enter any Ollama model"
  "none|No text model|You can set model in UI later"
)
OPENAI_DEFAULT_MODEL=""
menu_select OPENAI_DEFAULT_MODEL "Select primary text model" 0 TEXT_MODEL_OPTIONS
if [[ "$OPENAI_DEFAULT_MODEL" == "custom" ]]; then
  OPENAI_DEFAULT_MODEL="$(prompt_required 'Custom primary text model')"
elif [[ "$OPENAI_DEFAULT_MODEL" == "none" ]]; then
  OPENAI_DEFAULT_MODEL=""
fi

SECONDARY_TEXT_MODEL=""
if [[ -n "${OPENAI_DEFAULT_MODEL:-}" ]]; then
  SECONDARY_OPTIONS=(
    "${SECONDARY_MODEL_RECOMMENDED}|Recommended secondary model|Fallback/cheap option"
    "qwen2.5:3b|qwen2.5:3b|Very light"
    "qwen2.5:7b|qwen2.5:7b|Balanced fallback"
    "llama3.2:3b|llama3.2:3b|Alternative light"
    "custom|Custom model|Enter any Ollama model"
    "none|No secondary model|Skip"
  )
  menu_select SECONDARY_TEXT_MODEL "Select secondary text model" 0 SECONDARY_OPTIONS
  if [[ "$SECONDARY_TEXT_MODEL" == "custom" ]]; then
    SECONDARY_TEXT_MODEL="$(prompt_required 'Custom secondary text model')"
  elif [[ "$SECONDARY_TEXT_MODEL" == "none" ]]; then
    SECONDARY_TEXT_MODEL=""
  fi
  if [[ "$SECONDARY_TEXT_MODEL" == "$OPENAI_DEFAULT_MODEL" ]]; then
    SECONDARY_TEXT_MODEL=""
  fi
else
  echo "[info] Primary text model is empty, secondary text model is skipped."
fi

VISION_MODEL=""
VISION_OPTIONS=(
  "${VISION_MODEL_RECOMMENDED}|Recommended vision model|OCR/image understanding"
  "qwen2.5vl:3b|qwen2.5vl:3b|Light vision"
  "qwen2.5vl:7b|qwen2.5vl:7b|Balanced vision"
  "minicpm-v:8b|minicpm-v:8b|Alternative vision"
  "custom|Custom model|Enter any Ollama model"
  "none|No vision model|Skip"
)
menu_select VISION_MODEL "Select optional vision model" 0 VISION_OPTIONS
if [[ "$VISION_MODEL" == "custom" ]]; then
  VISION_MODEL="$(prompt_required 'Custom vision model')"
elif [[ "$VISION_MODEL" == "none" ]]; then
  VISION_MODEL=""
fi

OPENAI_TTS_MODEL=""
OPENAI_STT_MODEL=""
LOCAL_TTS_VOICES=""
LOCAL_AUDIO_BASE_URL=""
REMOTE_OPENAI_BASE_URL="https://api.openai.com"
OLLAMA_AUDIO_EXPERIMENTAL="false"

if [[ "$AUDIO_BACKEND_MODE" == "ollama" ]]; then
  OLLAMA_AUDIO_EXPERIMENTAL="true"
  TTS_OPTIONS=(
    "none|No TTS model|Skip TTS pulls"
    "legraphista/orpheus:latest|legraphista/orpheus:latest|Community TTS model"
    "custom|Custom TTS model|Enter any Ollama model"
  )
  STT_OPTIONS=(
    "none|No STT model|Skip STT pulls"
    "karanchopda333/whisper:latest|karanchopda333/whisper:latest|Community STT model"
    "custom|Custom STT model|Enter any Ollama model"
  )

  menu_select OPENAI_TTS_MODEL "Select optional TTS model (Ollama experimental)" 0 TTS_OPTIONS
  if [[ "$OPENAI_TTS_MODEL" == "custom" ]]; then
    OPENAI_TTS_MODEL="$(prompt_required 'Custom TTS model')"
  elif [[ "$OPENAI_TTS_MODEL" == "none" ]]; then
    OPENAI_TTS_MODEL=""
  fi

  menu_select OPENAI_STT_MODEL "Select optional STT model (Ollama experimental)" 0 STT_OPTIONS
  if [[ "$OPENAI_STT_MODEL" == "custom" ]]; then
    OPENAI_STT_MODEL="$(prompt_required 'Custom STT model')"
  elif [[ "$OPENAI_STT_MODEL" == "none" ]]; then
    OPENAI_STT_MODEL=""
  fi

  LOCAL_TTS_VOICES="$(prompt_default 'Fallback TTS voices (comma-separated, optional)' '')"
elif [[ "$AUDIO_BACKEND_MODE" == "custom" ]]; then
  LOCAL_AUDIO_BASE_URL="$(prompt_default 'Custom audio backend URL (inside Docker network)' 'http://host.docker.internal:8000')"
  OPENAI_TTS_MODEL="$(prompt_default 'Default TTS model (optional)' '')"
  OPENAI_STT_MODEL="$(prompt_default 'Default STT model (optional)' '')"
  LOCAL_TTS_VOICES="$(prompt_default 'Fallback TTS voices (comma-separated, optional)' '')"
fi

OPENAI_IMAGE_MODEL=""
LOCAL_IMAGE_BASE_URL=""
OLLAMA_IMAGE_EXPERIMENTAL="false"

if [[ "$IMAGE_BACKEND_MODE" == "ollama" ]]; then
  OLLAMA_IMAGE_EXPERIMENTAL="true"
  IMAGE_OPTIONS=(
    "x/z-image-turbo|x/z-image-turbo|Official Ollama image model"
    "x/flux2-klein|x/flux2-klein|Official Ollama image model"
    "custom|Custom image model|Enter any Ollama model"
    "none|No image model|Skip image pulls"
  )
  menu_select OPENAI_IMAGE_MODEL "Select image generation model" 0 IMAGE_OPTIONS
  if [[ "$OPENAI_IMAGE_MODEL" == "custom" ]]; then
    OPENAI_IMAGE_MODEL="$(prompt_required 'Custom image model')"
  elif [[ "$OPENAI_IMAGE_MODEL" == "none" ]]; then
    OPENAI_IMAGE_MODEL=""
  fi
elif [[ "$IMAGE_BACKEND_MODE" == "custom" ]]; then
  LOCAL_IMAGE_BASE_URL="$(prompt_default 'Custom image backend URL (inside Docker network)' 'http://host.docker.internal:8188')"
  OPENAI_IMAGE_MODEL="$(prompt_default 'Default image model (optional)' '')"
fi

OLLAMA_GPU_ENABLED="false"
OLLAMA_VISIBLE_GPUS="all"
if docker_gpu_available; then
  OLLAMA_GPU_ENABLED="true"
  mapfile -t gpu_entries < <(detect_gpu_entries || true)
  if (( ${#gpu_entries[@]} > 1 )); then
    GPU_SELECT_OPTIONS=()
    local_index=0
    for entry in "${gpu_entries[@]}"; do
      IFS='|' read -r idx name mem <<<"$entry"
      if (( local_index == 0 )); then
        GPU_SELECT_OPTIONS+=("gpu:${idx}|GPU ${idx}: ${name}|${mem} MiB")
      else
        GPU_SELECT_OPTIONS+=("gpu:${idx}|GPU ${idx}: ${name}|${mem} MiB")
      fi
      local_index=$((local_index + 1))
    done
    GPU_SELECT_OPTIONS+=("all|All GPUs|Expose all detected GPUs")
    GPU_SELECT_OPTIONS+=("cpu|CPU only|Disable Ollama GPU runtime")

    gpu_choice=""
    menu_select gpu_choice "Select GPU device for Ollama" 0 GPU_SELECT_OPTIONS
    case "$gpu_choice" in
      all)
        OLLAMA_VISIBLE_GPUS="all"
        ;;
      cpu)
        OLLAMA_GPU_ENABLED="false"
        OLLAMA_VISIBLE_GPUS="all"
        ;;
      gpu:*)
        OLLAMA_VISIBLE_GPUS="${gpu_choice#gpu:}"
        ;;
      *)
        OLLAMA_VISIBLE_GPUS="all"
        ;;
    esac
  elif (( ${#gpu_entries[@]} == 1 )); then
    IFS='|' read -r idx _ _ <<<"${gpu_entries[0]}"
    OLLAMA_VISIBLE_GPUS="$idx"
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

write_env_file "$DB_USER" "$DB_PASSWORD" "$DB_NAME" "$POSTGRES_PORT" "$MINIO_USER" "$MINIO_PASSWORD" "$OPENAI_DEFAULT_MODEL" "$LOCAL_AI_GATEWAY_PORT" "$OPENAI_TTS_MODEL" "$OPENAI_STT_MODEL" "$OPENAI_IMAGE_MODEL" "$LOCAL_AUDIO_BASE_URL" "$LOCAL_IMAGE_BASE_URL" "$LOCAL_TTS_VOICES" "$OLLAMA_GPU_ENABLED" "$OLLAMA_VISIBLE_GPUS" "$REMOTE_OPENAI_BASE_URL" "$OLLAMA_AUDIO_EXPERIMENTAL" "$OLLAMA_IMAGE_EXPERIMENTAL"
write_override_file "$OPENAI_DEFAULT_MODEL" "$OLLAMA_GPU_ENABLED"

echo "[info] Generated: $ENV_FILE"
echo "[info] Generated: $OVERRIDE_FILE"
if [[ "$DRY_RUN" == "1" ]]; then
  echo "[info] Dry run enabled (MNEMA_DRY_RUN=1), skipping docker compose up."
  exit 0
fi

cd "$ROOT_DIR"

echo "[info] Pulling base container images..."
compose_cmd pull --ignore-buildable || true

echo "[info] Starting Ollama and local-ai-gateway first..."
compose_cmd up -d --build ollama local-ai-gateway

if wait_for_ollama 120; then
  echo "[ok] Ollama is reachable."
else
  echo "[warn] Ollama API is not reachable yet; model pulls may fail."
fi

SELECTED_MODELS=()
build_model_pull_plan SELECTED_MODELS \
  "$OPENAI_DEFAULT_MODEL" \
  "$SECONDARY_TEXT_MODEL" \
  "$VISION_MODEL" \
  "$OPENAI_TTS_MODEL" \
  "$OPENAI_STT_MODEL" \
  "$OPENAI_IMAGE_MODEL"
pull_models "${SELECTED_MODELS[@]}"

echo "[info] Starting full Mnema stack..."
compose_cmd up -d --build

echo "[ok] Mnema is running"
echo "[ok] Frontend: http://localhost:${FRONTEND_PORT}"
echo "[ok] MinIO API: http://localhost:${MINIO_API_PORT}"
echo "[ok] MinIO Console: http://localhost:${MINIO_CONSOLE_PORT}"
echo "[ok] Ollama API: http://localhost:${OLLAMA_PORT}"
echo "[ok] Local AI Gateway: http://localhost:${LOCAL_AI_GATEWAY_PORT}"

echo "[info] Selected models:"
echo "       primary text: ${OPENAI_DEFAULT_MODEL:-<empty>}"
echo "       secondary text: ${SECONDARY_TEXT_MODEL:-<empty>}"
echo "       vision: ${VISION_MODEL:-<empty>}"
echo "       tts: ${OPENAI_TTS_MODEL:-<empty>}"
echo "       stt: ${OPENAI_STT_MODEL:-<empty>}"
echo "       image: ${OPENAI_IMAGE_MODEL:-<empty>}"

echo "[info] Audio backend mode: ${AUDIO_BACKEND_MODE}"
echo "[info] Image backend mode: ${IMAGE_BACKEND_MODE}"
echo "[info] Ollama audio experimental: ${OLLAMA_AUDIO_EXPERIMENTAL}"
echo "[info] Ollama image experimental: ${OLLAMA_IMAGE_EXPERIMENTAL}"
echo "[info] Ollama GPU enabled: ${OLLAMA_GPU_ENABLED}"
if [[ "$OLLAMA_GPU_ENABLED" == "true" ]]; then
  echo "[info] Ollama visible GPUs: ${OLLAMA_VISIBLE_GPUS}"
fi

if [[ "$AUDIO_BACKEND_MODE" == "custom" ]]; then
  if compose_cmd exec -T local-ai-gateway python -c 'import os,sys,urllib.request; u=os.getenv("AUDIO_BASE_URL", "").rstrip("/"); sys.exit(0 if (not u) else 0 if urllib.request.urlopen(u + "/v1/models", timeout=5).status < 500 else 1)' >/dev/null 2>&1; then
    echo "[ok] Custom audio backend URL is reachable from local-ai-gateway."
  else
    echo "[warn] Custom audio backend URL is not reachable from local-ai-gateway."
    echo "       Check LOCAL_AUDIO_BASE_URL in .env.local (for Linux host services use http://host.docker.internal:<port>)."
  fi
fi

if [[ "$IMAGE_BACKEND_MODE" == "custom" ]]; then
  if compose_cmd exec -T local-ai-gateway python -c 'import os,sys,urllib.request; u=os.getenv("IMAGE_BASE_URL", "").rstrip("/"); sys.exit(0 if (not u) else 0 if urllib.request.urlopen(u + "/v1/models", timeout=5).status < 500 else 1)' >/dev/null 2>&1; then
    echo "[ok] Custom image backend URL is reachable from local-ai-gateway."
  else
    echo "[warn] Custom image backend URL is not reachable from local-ai-gateway."
    echo "       Check LOCAL_IMAGE_BASE_URL in .env.local (for Linux host services use http://host.docker.internal:<port>)."
  fi
fi

print_ollama_next_commands

echo "[ok] To stop: docker compose --env-file $ENV_FILE -f docker-compose.yml -f $OVERRIDE_FILE down"
