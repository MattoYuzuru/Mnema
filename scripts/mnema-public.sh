#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK_DIR="$ROOT_DIR/.mnema"
ENV_FILE="$ROOT_DIR/.env.public"
OVERRIDE_FILE="$WORK_DIR/compose.public.yml"
DRY_RUN="${MNEMA_DRY_RUN:-0}"

mkdir -p "$WORK_DIR"

command_exists() { command -v "$1" >/dev/null 2>&1; }

check_requirements() {
  if ! command_exists docker; then
    echo "[error] Docker is required."
    exit 1
  fi
  if ! docker compose version >/dev/null 2>&1; then
    echo "[error] docker compose plugin is required."
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

next_free_port() {
  local port="$1"
  shift
  while is_port_busy "$port" || is_in_list "$port" "$@"; do
    port=$((port + 1))
  done
  echo "$port"
}

print_port_info() {
  local name="$1"
  local default_port="$2"
  local actual_port="$3"
  if [[ "$default_port" == "$actual_port" ]]; then
    echo "[port] $name: $actual_port"
  else
    echo "[port] $name: $default_port is busy, using $actual_port"
  fi
}

prompt_default() {
  local q="$1"
  local d="$2"
  local v
  read -r -p "$q [$d]: " v
  if [[ -z "${v// }" ]]; then
    echo "$d"
  else
    echo "$v"
  fi
}

rand_hex() {
  local bytes="$1"
  if command_exists openssl; then
    openssl rand -hex "$bytes"
  else
    head -c "$bytes" /dev/urandom | od -An -tx1 | tr -d ' \n'
  fi
}

check_requirements

echo "== Mnema public self-host init =="
WEB_DOMAIN="$(prompt_default 'Public web domain (without protocol)' 'mnema.local')"
AUTH_DOMAIN="$(prompt_default 'Auth domain (without protocol)' "auth.${WEB_DOMAIN}")"
WEB_ORIGIN="https://${WEB_DOMAIN}"
AUTH_ISSUER="https://${AUTH_DOMAIN}"

read -r -p "Enable federated OAuth providers? [y/N]: " federated_raw
AUTH_FEDERATED_ENABLED="false"
if [[ "${federated_raw:-}" =~ ^[Yy]$ ]]; then
  AUTH_FEDERATED_ENABLED="true"
fi

POSTGRES_USER="$(prompt_default 'Postgres user' 'mnema')"
POSTGRES_PASSWORD="$(prompt_default 'Postgres password' 'mnema_public')"
POSTGRES_DB="$(prompt_default 'Postgres database' 'mnema')"
MINIO_USER="$(prompt_default 'MinIO root user' 'mnema')"
MINIO_PASSWORD="$(prompt_default 'MinIO root password' 'mnema_public_minio')"

used_ports=()
POSTGRES_PORT="$(next_free_port 5432 "${used_ports[@]:-}")"; used_ports+=("$POSTGRES_PORT")
REDIS_PORT="$(next_free_port 6379 "${used_ports[@]}")"; used_ports+=("$REDIS_PORT")
AUTH_PORT="$(next_free_port 8083 "${used_ports[@]}")"; used_ports+=("$AUTH_PORT")
USER_PORT="$(next_free_port 8084 "${used_ports[@]}")"; used_ports+=("$USER_PORT")
CORE_PORT="$(next_free_port 8085 "${used_ports[@]}")"; used_ports+=("$CORE_PORT")
MEDIA_PORT="$(next_free_port 8086 "${used_ports[@]}")"; used_ports+=("$MEDIA_PORT")
IMPORT_PORT="$(next_free_port 8087 "${used_ports[@]}")"; used_ports+=("$IMPORT_PORT")
AI_PORT="$(next_free_port 8088 "${used_ports[@]}")"; used_ports+=("$AI_PORT")
FRONTEND_PORT="$(next_free_port 3005 "${used_ports[@]}")"; used_ports+=("$FRONTEND_PORT")
MINIO_API_PORT="$(next_free_port 9000 "${used_ports[@]}")"; used_ports+=("$MINIO_API_PORT")
MINIO_CONSOLE_PORT="$(next_free_port 9001 "${used_ports[@]}")"; used_ports+=("$MINIO_CONSOLE_PORT")
OLLAMA_PORT="$(next_free_port 11434 "${used_ports[@]}")"; used_ports+=("$OLLAMA_PORT")

print_port_info "postgres" 5432 "$POSTGRES_PORT"
print_port_info "redis" 6379 "$REDIS_PORT"
print_port_info "auth" 8083 "$AUTH_PORT"
print_port_info "user" 8084 "$USER_PORT"
print_port_info "core" 8085 "$CORE_PORT"
print_port_info "media" 8086 "$MEDIA_PORT"
print_port_info "import" 8087 "$IMPORT_PORT"
print_port_info "ai" 8088 "$AI_PORT"
print_port_info "frontend" 3005 "$FRONTEND_PORT"
print_port_info "minio-api" 9000 "$MINIO_API_PORT"
print_port_info "minio-console" 9001 "$MINIO_CONSOLE_PORT"
print_port_info "ollama" 11434 "$OLLAMA_PORT"

AUTH_SWAGGER_REDIRECTS="${WEB_ORIGIN}/api/user/swagger-ui/oauth2-redirect.html,${WEB_ORIGIN}/api/core/swagger-ui/oauth2-redirect.html,${WEB_ORIGIN}/api/media/swagger-ui/oauth2-redirect.html,${WEB_ORIGIN}/api/import/swagger-ui/oauth2-redirect.html,${WEB_ORIGIN}/api/ai/swagger-ui/oauth2-redirect.html"

cat > "$ENV_FILE" <<ENV
APP_ENV=public
POSTGRES_DB=$POSTGRES_DB
POSTGRES_USER=$POSTGRES_USER
POSTGRES_PASSWORD=$POSTGRES_PASSWORD
POSTGRES_PORT=$POSTGRES_PORT
SPRING_DATASOURCE_USERNAME=$POSTGRES_USER
SPRING_DATASOURCE_PASSWORD=$POSTGRES_PASSWORD

PUBLIC_WEB_ORIGIN=$WEB_ORIGIN
AUTH_ISSUER=$AUTH_ISSUER
AUTH_ISSUER_URI=$AUTH_ISSUER
AUTH_LOGOUT_DEFAULT_REDIRECT=$WEB_ORIGIN/
AUTH_WEB_REDIRECT_URIS=$WEB_ORIGIN/
AUTH_SWAGGER_REDIRECT_URIS=$AUTH_SWAGGER_REDIRECTS
AUTH_FEDERATED_ENABLED=$AUTH_FEDERATED_ENABLED
AUTH_REQUIRE_EMAIL_VERIFICATION=$AUTH_FEDERATED_ENABLED

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
AWS_BUCKET_NAME=mnema-public
AWS_ENDPOINT=http://minio:9000
AWS_PATH_STYLE_ACCESS=true
AWS_ACCESS_KEY_ID=$MINIO_USER
AWS_SECRET_ACCESS_KEY=$MINIO_PASSWORD

CORE_BASE_URL=http://core:8080/api/core
MEDIA_BASE_URL=http://media:8080/api/media

AI_PROVIDER=openai
AI_SYSTEM_MANAGED_PROVIDER_ENABLED=true
AI_SYSTEM_PROVIDER_NAME=ollama
AI_OLLAMA_ENABLED=true
OLLAMA_BASE_URL=http://ollama:11434
OPENAI_BASE_URL=http://ollama:11434/v1
OPENAI_SYSTEM_API_KEY=
OPENAI_DEFAULT_MODEL=qwen3:8b
AI_VAULT_MASTER_KEY=$(rand_hex 32)
AI_VAULT_KEY_ID=public-v1

REDIS_HOST=redis
REDIS_PORT=6379
SPRING_CACHE_TYPE=redis

MINIO_ROOT_USER=$MINIO_USER
MINIO_ROOT_PASSWORD=$MINIO_PASSWORD
ENV

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
      SPRING_PROFILES_ACTIVE: prod,selfhost-public
    ports:
      - "${AUTH_PORT}:8080"

  user:
    environment:
      SPRING_PROFILES_ACTIVE: prod,selfhost-public
    ports:
      - "${USER_PORT}:8080"

  core:
    environment:
      SPRING_PROFILES_ACTIVE: prod,selfhost-public
    ports:
      - "${CORE_PORT}:8080"

  media:
    environment:
      SPRING_PROFILES_ACTIVE: prod,selfhost-public
    depends_on:
      minio:
        condition: service_healthy
    ports:
      - "${MEDIA_PORT}:8080"

  import:
    environment:
      SPRING_PROFILES_ACTIVE: prod,selfhost-public
    ports:
      - "${IMPORT_PORT}:8080"

  ai:
    environment:
      SPRING_PROFILES_ACTIVE: prod,selfhost-public
    ports:
      - "${AI_PORT}:8080"

  frontend:
    environment:
      MNEMA_AUTH_SERVER_URL: ${AUTH_ISSUER}
      MNEMA_FEATURE_FEDERATED_AUTH_ENABLED: ${AUTH_FEDERATED_ENABLED}
      MNEMA_FEATURE_SHOW_EMAIL_VERIFICATION_WARNING: ${AUTH_FEDERATED_ENABLED}
      MNEMA_FEATURE_AI_SYSTEM_PROVIDER_ENABLED: true
      MNEMA_FEATURE_AI_SYSTEM_PROVIDER_NAME: ollama
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
        mc mb -p local/mnema-public || true;
        mc anonymous set private local/mnema-public;

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

echo "[ok] Generated $ENV_FILE"
echo "[ok] Generated $OVERRIDE_FILE"

COMPOSE_BASE=(docker compose --env-file "$ENV_FILE" -f docker-compose.yml -f "$OVERRIDE_FILE")
echo "[next] Start stack:"
echo "  ${COMPOSE_BASE[*]} up -d --build"

if [[ "$DRY_RUN" == "1" ]]; then
  echo "[dry-run] done (compose up skipped)."
  exit 0
fi

read -r -p "Start stack now? [Y/n]: " start_now
if [[ -z "${start_now:-}" || "${start_now}" =~ ^[Yy]$ ]]; then
  (cd "$ROOT_DIR" && "${COMPOSE_BASE[@]}" up -d --build)
  echo "[done] Mnema public stack is running."
  echo "[done] Frontend: http://localhost:${FRONTEND_PORT}"
else
  echo "[info] Skipped compose up."
fi

echo "[next] Public self-host runbook: docs/deploy/selfhost-public.md"
