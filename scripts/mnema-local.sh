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

write_env_file() {
  local postgres_user="$1"
  local postgres_password="$2"
  local postgres_db="$3"
  local postgres_port="$4"
  local minio_user="$5"
  local minio_password="$6"

  cat > "$ENV_FILE" <<ENV
POSTGRES_DB=$postgres_db
POSTGRES_USER=$postgres_user
POSTGRES_PASSWORD=$postgres_password
POSTGRES_PORT=$postgres_port

SPRING_DATASOURCE_USERNAME=$postgres_user
SPRING_DATASOURCE_PASSWORD=$postgres_password

AUTH_ISSUER=http://localhost:${AUTH_PORT}
AUTH_ISSUER_URI=http://localhost:${AUTH_PORT}

GOOGLE_CLIENT_ID=
GOOGLE_CLIENT_SECRET=
GH_CLIENT_ID=
GH_CLIENT_SECRET=
YANDEX_CLIENT_ID=
YANDEX_CLIENT_SECRET=

TURNSTILE_SITE_KEY=
TURNSTILE_SECRET_KEY=

MEDIA_INTERNAL_TOKEN=$(rand_hex 24)

AWS_REGION=us-east-1
AWS_BUCKET_NAME=mnema-local
AWS_ENDPOINT=http://minio:9000
AWS_PATH_STYLE_ACCESS=true
AWS_ACCESS_KEY_ID=$minio_user
AWS_SECRET_ACCESS_KEY=$minio_password

CORE_BASE_URL=http://core:8080/api/core
MEDIA_BASE_URL=http://media:8080/api/media

AI_PROVIDER=openai
AI_SYSTEM_MANAGED_PROVIDER_ENABLED=true
AI_SYSTEM_PROVIDER_NAME=ollama
AI_OLLAMA_ENABLED=true
AI_VAULT_MASTER_KEY=$(rand_hex 32)
AI_VAULT_KEY_ID=local-v1

OPENAI_BASE_URL=http://ollama:11434/v1
OLLAMA_BASE_URL=http://ollama:11434
OPENAI_DEFAULT_MODEL=qwen3:8b
OPENAI_TTS_MODEL=
OPENAI_STT_MODEL=

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
  cat > "$OVERRIDE_FILE" <<YAML
services:
  postgres:
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

check_requirements

echo "== Mnema local bootstrap =="
echo "The script will detect busy ports and move to the next free port automatically."

default_db_user="mnema"
default_db_name="mnema"
default_db_password="mnema_local"
default_minio_user="mnema"
default_minio_password="mnema_minio_local"

DB_USER="$(prompt_default 'Postgres user' "$default_db_user")"
DB_NAME="$(prompt_default 'Postgres database' "$default_db_name")"
DB_PASSWORD="$(prompt_default 'Postgres password' "$default_db_password")"
MINIO_USER="$(prompt_default 'MinIO root user' "$default_minio_user")"
MINIO_PASSWORD="$(prompt_default 'MinIO root password' "$default_minio_password")"

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

write_env_file "$DB_USER" "$DB_PASSWORD" "$DB_NAME" "$POSTGRES_PORT" "$MINIO_USER" "$MINIO_PASSWORD"
write_override_file

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

if command_exists curl; then
  if curl -fsS "http://localhost:${OLLAMA_PORT}/api/tags" >/dev/null 2>&1; then
    echo "[ok] Ollama is reachable."
    local_model="$(recommend_ollama_model)"
    echo "[info] Recommended starter model for this host: ${local_model}"
    read -r -p "Pull recommended model now? [y/N]: " pull_choice
    if [[ "${pull_choice:-}" =~ ^[Yy]$ ]]; then
      docker compose --env-file "$ENV_FILE" -f docker-compose.yml -f "$OVERRIDE_FILE" exec -T ollama ollama pull "$local_model" || true
    fi
  else
    echo "[warn] Ollama API is not reachable yet. You can retry later with:"
    echo "       docker compose --env-file $ENV_FILE -f docker-compose.yml -f $OVERRIDE_FILE exec -T ollama ollama pull qwen3:8b"
  fi
fi

echo "[ok] To stop: docker compose --env-file $ENV_FILE -f docker-compose.yml -f $OVERRIDE_FILE down"
