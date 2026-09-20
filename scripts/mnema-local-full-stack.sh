#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="$ROOT_DIR/compose.local-full-stack.yml"
STATE_DIR="${MNEMA_LOCAL_STATE_DIR:-$ROOT_DIR/.mnema/local-full-stack}"
RUNTIME_FILE="$STATE_DIR/runtime.env"
JWK_FILE="$STATE_DIR/identity-signing-jwk-set.json"
CA_CERT_FILE="$STATE_DIR/local-ca.crt"
CA_KEY_FILE="$STATE_DIR/local-ca.key"
TLS_CERT_FILE="$STATE_DIR/localhost.crt"
TLS_KEY_FILE="$STATE_DIR/localhost.key"
TRUSTSTORE_FILE="$STATE_DIR/learning-truststore.p12"
SMOKE_STATE_FILE="$STATE_DIR/smoke-account.json"
PROJECT_NAME="mnema-local-v2"
TRUSTSTORE_PASSWORD="changeit"

fail() {
  printf '[error] %s\n' "$1" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "$1 is required; see docs/deploy/selfhost-local.md"
}

private_mode() {
  local mode
  mode="$(stat -f '%Lp' "$1" 2>/dev/null || stat -c '%a' "$1" 2>/dev/null)" || return 1
  (( (8#$mode & 077) == 0 ))
}

validate_port() {
  local label="$1" value="$2"
  [[ "$value" =~ ^[0-9]+$ ]] || fail "$label must be an integer between 1024 and 65535"
  (( value >= 1024 && value <= 65535 )) || fail "$label must be between 1024 and 65535"
}

load_runtime() {
  [[ -f "$RUNTIME_FILE" ]] || fail "missing $RUNTIME_FILE; run '$0 bootstrap'"
  private_mode "$RUNTIME_FILE" || fail "$RUNTIME_FILE must not be accessible by group or other users"
  local key value unknown=0
  MNEMA_LOCAL_POSTGRES_PASSWORD_VALUE=""
  MNEMA_LOCAL_POSTGRES_DB_VALUE=""
  MNEMA_LOCAL_POSTGRES_USER_VALUE=""
  MNEMA_LOCAL_WEB_PORT_VALUE=""
  MNEMA_LOCAL_IDENTITY_PORT_VALUE=""
  while IFS='=' read -r key value || [[ -n "$key$value" ]]; do
    case "$key" in
      MNEMA_LOCAL_POSTGRES_PASSWORD) MNEMA_LOCAL_POSTGRES_PASSWORD_VALUE="$value" ;;
      MNEMA_LOCAL_POSTGRES_DB) MNEMA_LOCAL_POSTGRES_DB_VALUE="$value" ;;
      MNEMA_LOCAL_POSTGRES_USER) MNEMA_LOCAL_POSTGRES_USER_VALUE="$value" ;;
      MNEMA_LOCAL_WEB_PORT) MNEMA_LOCAL_WEB_PORT_VALUE="$value" ;;
      MNEMA_LOCAL_IDENTITY_PORT) MNEMA_LOCAL_IDENTITY_PORT_VALUE="$value" ;;
      '') ;;
      *) unknown=1 ;;
    esac
  done < "$RUNTIME_FILE"
  (( unknown == 0 )) || fail "$RUNTIME_FILE contains unsupported settings"
  [[ "$MNEMA_LOCAL_POSTGRES_PASSWORD_VALUE" =~ ^[0-9a-f]{64}$ ]] || fail "local PostgreSQL password state is invalid"
  [[ "$MNEMA_LOCAL_POSTGRES_DB_VALUE" =~ ^[a-zA-Z0-9_]{1,63}$ ]] || fail "local PostgreSQL database name is invalid"
  [[ "$MNEMA_LOCAL_POSTGRES_USER_VALUE" =~ ^[a-zA-Z0-9_]{1,63}$ ]] || fail "local PostgreSQL user is invalid"
  validate_port MNEMA_LOCAL_WEB_PORT "$MNEMA_LOCAL_WEB_PORT_VALUE"
  validate_port MNEMA_LOCAL_IDENTITY_PORT "$MNEMA_LOCAL_IDENTITY_PORT_VALUE"
  [[ "$MNEMA_LOCAL_WEB_PORT_VALUE" != "$MNEMA_LOCAL_IDENTITY_PORT_VALUE" ]] || fail "web and Identity ports must differ"
}

validate_material() {
  local file
  for file in "$JWK_FILE" "$CA_KEY_FILE" "$TLS_KEY_FILE" "$TRUSTSTORE_FILE"; do
    [[ -s "$file" ]] || fail "missing local private material: $file; run '$0 bootstrap'"
    private_mode "$file" || fail "$file must not be accessible by group or other users"
  done
  for file in "$CA_CERT_FILE" "$TLS_CERT_FILE"; do
    [[ -s "$file" ]] || fail "missing local certificate: $file; run '$0 bootstrap'"
  done
  grep -q '"kid":"blackbox"' "$JWK_FILE" || fail "local Identity JWKSet does not contain active kid blackbox"
  openssl verify -CAfile "$CA_CERT_FILE" "$TLS_CERT_FILE" >/dev/null 2>&1 \
    || fail "local TLS certificate is not signed by the retained local CA; run '$0 reset-certificates --confirm'"
  openssl x509 -checkend 604800 -noout -in "$TLS_CERT_FILE" >/dev/null 2>&1 \
    || fail "local TLS certificate expires within seven days; run '$0 reset-certificates --confirm'"
  openssl x509 -checkhost localhost -noout -in "$TLS_CERT_FILE" >/dev/null 2>&1 \
    || fail "local TLS certificate does not cover localhost"
  openssl x509 -checkhost frontend -noout -in "$TLS_CERT_FILE" >/dev/null 2>&1 \
    || fail "local TLS certificate does not cover the internal TLS proxy name"
  keytool -list -keystore "$TRUSTSTORE_FILE" -storetype PKCS12 -storepass "$TRUSTSTORE_PASSWORD" \
    -alias mnema-local-ca >/dev/null 2>&1 || fail "Learning local truststore is invalid"
}

generate_certificate_material() {
  local destination="$1"
  openssl req -x509 -newkey rsa:3072 -sha256 -nodes -days 3650 \
    -subj '/CN=Mnema Local Development CA' \
    -addext 'basicConstraints=critical,CA:TRUE' \
    -addext 'keyUsage=critical,keyCertSign,cRLSign' \
    -keyout "$destination/local-ca.key" -out "$destination/local-ca.crt" >/dev/null 2>&1
  openssl req -new -newkey rsa:2048 -sha256 -nodes -subj '/CN=localhost' \
    -keyout "$destination/localhost.key" -out "$destination/localhost.csr" >/dev/null 2>&1
  printf '%s\n' \
    'basicConstraints=critical,CA:FALSE' \
    'keyUsage=critical,digitalSignature,keyEncipherment' \
    'extendedKeyUsage=serverAuth' \
    'subjectAltName=DNS:localhost,DNS:frontend,IP:127.0.0.1,IP:::1' > "$destination/localhost.ext"
  openssl x509 -req -sha256 -days 825 -in "$destination/localhost.csr" \
    -CA "$destination/local-ca.crt" -CAkey "$destination/local-ca.key" -CAcreateserial \
    -extfile "$destination/localhost.ext" -out "$destination/localhost.crt" >/dev/null 2>&1
  keytool -importcert -noprompt -storetype PKCS12 -alias mnema-local-ca \
    -file "$destination/local-ca.crt" -keystore "$destination/learning-truststore.p12" \
    -storepass "$TRUSTSTORE_PASSWORD" >/dev/null 2>&1
}

bootstrap() {
  require_command openssl
  require_command java
  require_command keytool
  umask 077
  install -d -m 700 "$STATE_DIR"
  local present=0 file
  for file in "$RUNTIME_FILE" "$JWK_FILE" "$CA_CERT_FILE" "$CA_KEY_FILE" "$TLS_CERT_FILE" "$TLS_KEY_FILE" "$TRUSTSTORE_FILE"; do
    [[ -e "$file" ]] && present=$((present + 1))
  done
  if (( present == 7 )); then
    load_runtime
    validate_material
    printf '[ok] Reusing retained local credentials and certificates in %s\n' "$STATE_DIR"
    return
  fi
  (( present == 0 )) || fail "partial local security state found in $STATE_DIR; remove it manually after inspection or use reset-certificates"

  local web_port="${MNEMA_LOCAL_WEB_PORT:-3443}"
  local identity_port="${MNEMA_LOCAL_IDENTITY_PORT:-3444}"
  validate_port MNEMA_LOCAL_WEB_PORT "$web_port"
  validate_port MNEMA_LOCAL_IDENTITY_PORT "$identity_port"
  [[ "$web_port" != "$identity_port" ]] || fail "web and Identity ports must differ"
  local temporary
  temporary="$(mktemp -d "$STATE_DIR/bootstrap.XXXXXX")"
  trap 'rm -rf "$temporary"' EXIT HUP INT TERM
  generate_certificate_material "$temporary"
  java "$ROOT_DIR/scripts/learning-security/FixtureKey.java" "$temporary/identity-signing-jwk-set.json"
  printf 'MNEMA_LOCAL_POSTGRES_PASSWORD=%s\nMNEMA_LOCAL_POSTGRES_DB=mnema\nMNEMA_LOCAL_POSTGRES_USER=mnema\nMNEMA_LOCAL_WEB_PORT=%s\nMNEMA_LOCAL_IDENTITY_PORT=%s\n' \
    "$(openssl rand -hex 32)" "$web_port" "$identity_port" > "$temporary/runtime.env"
  chmod 600 "$temporary"/*
  chmod 644 "$temporary/local-ca.crt" "$temporary/localhost.crt"
  mv "$temporary/runtime.env" "$RUNTIME_FILE"
  mv "$temporary/identity-signing-jwk-set.json" "$JWK_FILE"
  mv "$temporary/local-ca.crt" "$CA_CERT_FILE"
  mv "$temporary/local-ca.key" "$CA_KEY_FILE"
  mv "$temporary/localhost.crt" "$TLS_CERT_FILE"
  mv "$temporary/localhost.key" "$TLS_KEY_FILE"
  mv "$temporary/learning-truststore.p12" "$TRUSTSTORE_FILE"
  rm -rf "$temporary"
  trap - EXIT HUP INT TERM
  load_runtime
  validate_material
  printf '[ok] Generated retained local-only credentials in %s (nothing is committed).\n' "$STATE_DIR"
}

compose() {
  require_command docker
  docker compose version >/dev/null 2>&1 || fail "Docker Compose plugin is required"
  local build_id
  build_id="$(git -C "$ROOT_DIR" rev-parse HEAD 2>/dev/null || printf dev)"
  COMPOSE_DISABLE_ENV_FILE=true \
  MNEMA_LOCAL_POSTGRES_PASSWORD="$MNEMA_LOCAL_POSTGRES_PASSWORD_VALUE" \
  MNEMA_LOCAL_POSTGRES_DB="$MNEMA_LOCAL_POSTGRES_DB_VALUE" \
  MNEMA_LOCAL_POSTGRES_USER="$MNEMA_LOCAL_POSTGRES_USER_VALUE" \
  MNEMA_LOCAL_WEB_PORT="$MNEMA_LOCAL_WEB_PORT_VALUE" \
  MNEMA_LOCAL_IDENTITY_PORT="$MNEMA_LOCAL_IDENTITY_PORT_VALUE" \
  MNEMA_LOCAL_BUILD_ID="$build_id" \
  MNEMA_LOCAL_IDENTITY_SIGNING_JWK_SET_FILE="$JWK_FILE" \
  MNEMA_LOCAL_TLS_CERT_FILE="$TLS_CERT_FILE" \
  MNEMA_LOCAL_TLS_KEY_FILE="$TLS_KEY_FILE" \
  MNEMA_LOCAL_TRUSTSTORE_FILE="$TRUSTSTORE_FILE" \
    docker compose --project-name "$PROJECT_NAME" --file "$COMPOSE_FILE" "$@"
}

smoke() {
  require_command python3
  if [[ -e "$SMOKE_STATE_FILE" ]]; then
    [[ -f "$SMOKE_STATE_FILE" ]] || fail "$SMOKE_STATE_FILE must be a regular file"
    private_mode "$SMOKE_STATE_FILE" \
      || fail "$SMOKE_STATE_FILE contains local credentials and must not be accessible by group or other users"
  fi
  python3 "$ROOT_DIR/scripts/local-full-stack/smoke.py" \
    --web-origin "https://localhost:$MNEMA_LOCAL_WEB_PORT_VALUE" \
    --identity-origin "https://localhost:$MNEMA_LOCAL_IDENTITY_PORT_VALUE" \
    --ca "$CA_CERT_FILE" --state-file "$SMOKE_STATE_FILE" "$@"
}

start() {
  require_command docker
  require_command openssl
  require_command keytool
  require_command python3
  require_command curl
  bootstrap
  if ! compose up --detach --build --wait --wait-timeout 300; then
    printf '[error] local stack failed readiness; inspect bounded status/logs below\n' >&2
    compose ps >&2 || true
    compose logs --tail=80 >&2 || true
    compose stop >/dev/null 2>&1 \
      || printf '[error] failed to stop the unhealthy local stack; run %s stop\n' "$0" >&2
    exit 1
  fi
  smoke --readiness-only
  printf '[ok] Mnema is ready at https://localhost:%s\n' "$MNEMA_LOCAL_WEB_PORT_VALUE"
  if ! curl --silent --fail "https://localhost:$MNEMA_LOCAL_WEB_PORT_VALUE/" >/dev/null 2>&1; then
    printf '[action] Trust this local CA in your browser/OS, then reopen Mnema: %s\n' "$CA_CERT_FILE"
  fi
  printf '[info] Run %s smoke for PKCE + persistent authoring verification.\n' "$0"
}

reset_data() {
  [[ "${1:-}" == "--confirm-delete-local-data" ]] \
    || fail "reset deletes the mnema-local-v2 PostgreSQL volume; rerun with --confirm-delete-local-data"
  require_command openssl
  require_command keytool
  load_runtime
  validate_material
  compose down --volumes --remove-orphans
  rm -f "$SMOKE_STATE_FILE"
  printf '[ok] Deleted only the mnema-local-v2 containers and PostgreSQL volume; retained local CA/JWK material.\n'
}

reset_certificates() {
  [[ "${1:-}" == "--confirm" ]] || fail "certificate reset changes the trusted local CA; rerun with --confirm"
  require_command openssl
  require_command keytool
  load_runtime
  validate_material
  compose down --remove-orphans >/dev/null 2>&1 || true
  local temporary
  temporary="$(mktemp -d "$STATE_DIR/certificates.XXXXXX")"
  trap 'rm -rf "$temporary"' EXIT HUP INT TERM
  generate_certificate_material "$temporary"
  chmod 600 "$temporary"/*
  chmod 644 "$temporary/local-ca.crt" "$temporary/localhost.crt"
  mv "$temporary/local-ca.crt" "$CA_CERT_FILE"
  mv "$temporary/local-ca.key" "$CA_KEY_FILE"
  mv "$temporary/localhost.crt" "$TLS_CERT_FILE"
  mv "$temporary/localhost.key" "$TLS_KEY_FILE"
  mv "$temporary/learning-truststore.p12" "$TRUSTSTORE_FILE"
  rm -rf "$temporary"
  trap - EXIT HUP INT TERM
  validate_material
  printf '[action] Remove the old CA trust entry and trust the new certificate: %s\n' "$CA_CERT_FILE"
}

command="${1:-start}"
shift || true
case "$command" in
  bootstrap) bootstrap ;;
  start) start ;;
  stop) load_runtime; compose stop ;;
  status) load_runtime; compose ps ;;
  logs) load_runtime; compose logs --tail="${1:-100}" ;;
  smoke) require_command openssl; require_command keytool; load_runtime; validate_material; smoke ;;
  reset) reset_data "$@" ;;
  reset-certificates) reset_certificates "$@" ;;
  *) fail "usage: $0 {bootstrap|start|stop|status|logs [lines]|smoke|reset --confirm-delete-local-data|reset-certificates --confirm}" ;;
esac
