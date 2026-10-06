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
STORAGE_CERT_FILE="$STATE_DIR/storage.crt"
STORAGE_KEY_FILE="$STATE_DIR/storage.key"
TRUSTSTORE_FILE="$STATE_DIR/learning-truststore.p12"
MEDIA_WORK_ROOT="$STATE_DIR/media-processing"
SMOKE_STATE_FILE="$STATE_DIR/smoke-account.json"
MEDIA_SMOKE_STATE_FILE="$STATE_DIR/media-smoke.json"
PROJECT_NAME="${MNEMA_LOCAL_PROJECT_NAME:-mnema-local-v2}"
MEDIA_WORKER_IMAGE="${MNEMA_LOCAL_MEDIA_WORKER_IMAGE:-mnema-media-worker:local}"
TRUSTSTORE_PASSWORD="changeit"

fail() {
  printf '[error] %s\n' "$1" >&2
  exit 1
}

[[ "$STATE_DIR" == /* ]] || fail "MNEMA_LOCAL_STATE_DIR must be an absolute path"
[[ "$PROJECT_NAME" =~ ^[a-z0-9][a-z0-9_-]{0,62}$ ]] || fail "MNEMA_LOCAL_PROJECT_NAME must be a lowercase Compose project name"
[[ "$MEDIA_WORKER_IMAGE" =~ ^[a-zA-Z0-9][a-zA-Z0-9._:/@-]{0,255}$ ]] || fail "MNEMA_LOCAL_MEDIA_WORKER_IMAGE is not a valid image reference"

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "$1 is required; see docs/deploy/selfhost-local.md"
}

private_mode() {
  local mode
  # GNU stat accepts -f with different semantics and can emit filesystem details
  # before failing on the BSD format operand, so try its -c form first.
  mode="$(stat -c '%a' "$1" 2>/dev/null || stat -f '%Lp' "$1" 2>/dev/null)" || return 1
  (( (8#$mode & 077) == 0 ))
}

validate_port() {
  local label="$1" value="$2"
  [[ "$value" =~ ^[0-9]+$ ]] || fail "$label must be an integer between 1024 and 65535"
  (( value >= 1024 && value <= 65535 )) || fail "$label must be between 1024 and 65535"
}

# Reads the owner-only runtime state. Without "upgradeable", the object-storage keys
# are required; bootstrap passes it once to add them to state created before storage.
load_runtime() {
  local mode="${1:-}"
  [[ -f "$RUNTIME_FILE" ]] || fail "missing $RUNTIME_FILE; run '$0 bootstrap'"
  private_mode "$RUNTIME_FILE" || fail "$RUNTIME_FILE must not be accessible by group or other users"
  local key value unknown=0
  MNEMA_LOCAL_POSTGRES_PASSWORD_VALUE=""
  MNEMA_LOCAL_POSTGRES_DB_VALUE=""
  MNEMA_LOCAL_POSTGRES_USER_VALUE=""
  MNEMA_LOCAL_WEB_PORT_VALUE=""
  MNEMA_LOCAL_IDENTITY_PORT_VALUE=""
  MNEMA_LOCAL_STORAGE_PORT_VALUE=""
  MNEMA_LOCAL_S3_ACCESS_KEY_VALUE=""
  MNEMA_LOCAL_S3_SECRET_KEY_VALUE=""
  while IFS='=' read -r key value || [[ -n "$key$value" ]]; do
    case "$key" in
      MNEMA_LOCAL_POSTGRES_PASSWORD) MNEMA_LOCAL_POSTGRES_PASSWORD_VALUE="$value" ;;
      MNEMA_LOCAL_POSTGRES_DB) MNEMA_LOCAL_POSTGRES_DB_VALUE="$value" ;;
      MNEMA_LOCAL_POSTGRES_USER) MNEMA_LOCAL_POSTGRES_USER_VALUE="$value" ;;
      MNEMA_LOCAL_WEB_PORT) MNEMA_LOCAL_WEB_PORT_VALUE="$value" ;;
      MNEMA_LOCAL_IDENTITY_PORT) MNEMA_LOCAL_IDENTITY_PORT_VALUE="$value" ;;
      MNEMA_LOCAL_STORAGE_PORT) MNEMA_LOCAL_STORAGE_PORT_VALUE="$value" ;;
      MNEMA_LOCAL_S3_ACCESS_KEY) MNEMA_LOCAL_S3_ACCESS_KEY_VALUE="$value" ;;
      MNEMA_LOCAL_S3_SECRET_KEY) MNEMA_LOCAL_S3_SECRET_KEY_VALUE="$value" ;;
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
  if [[ "$mode" == upgradeable && -z "$MNEMA_LOCAL_S3_ACCESS_KEY_VALUE$MNEMA_LOCAL_S3_SECRET_KEY_VALUE$MNEMA_LOCAL_STORAGE_PORT_VALUE" ]]; then
    return
  fi
  [[ "$MNEMA_LOCAL_S3_ACCESS_KEY_VALUE" =~ ^[0-9a-f]{32}$ ]] \
    || fail "local object-storage access key state is missing or invalid; run '$0 bootstrap'"
  [[ "$MNEMA_LOCAL_S3_SECRET_KEY_VALUE" =~ ^[0-9a-f]{64}$ ]] \
    || fail "local object-storage secret key state is missing or invalid; run '$0 bootstrap'"
  validate_port MNEMA_LOCAL_STORAGE_PORT "$MNEMA_LOCAL_STORAGE_PORT_VALUE"
  [[ "$MNEMA_LOCAL_STORAGE_PORT_VALUE" != "$MNEMA_LOCAL_WEB_PORT_VALUE" \
    && "$MNEMA_LOCAL_STORAGE_PORT_VALUE" != "$MNEMA_LOCAL_IDENTITY_PORT_VALUE" ]] \
    || fail "web, Identity and object-storage ports must differ"
}

# With "rotating" only the private files and the CA are checked, so reset-certificates
# can replace certificates that are expired or otherwise rejected here.
validate_material() {
  local file rotating="${1:-}"
  for file in "$JWK_FILE" "$CA_KEY_FILE" "$TLS_KEY_FILE" "$STORAGE_KEY_FILE" "$TRUSTSTORE_FILE"; do
    [[ -s "$file" ]] || fail "missing local private material: $file; run '$0 bootstrap'"
    private_mode "$file" || fail "$file must not be accessible by group or other users"
  done
  for file in "$CA_CERT_FILE" "$TLS_CERT_FILE" "$STORAGE_CERT_FILE"; do
    [[ -s "$file" ]] || fail "missing local certificate: $file; run '$0 bootstrap'"
  done
  grep -q '"kid":"blackbox"' "$JWK_FILE" || fail "local Identity JWKSet does not contain active kid blackbox"
  [[ "$rotating" != rotating ]] || return 0
  openssl verify -CAfile "$CA_CERT_FILE" "$TLS_CERT_FILE" >/dev/null 2>&1 \
    || fail "local TLS certificate is not signed by the retained local CA; run '$0 reset-certificates --confirm'"
  openssl x509 -checkend 604800 -noout -in "$TLS_CERT_FILE" >/dev/null 2>&1 \
    || fail "local TLS certificate expires within seven days; run '$0 reset-certificates --confirm'"
  openssl x509 -checkhost localhost -noout -in "$TLS_CERT_FILE" >/dev/null 2>&1 \
    || fail "local TLS certificate does not cover localhost"
  openssl x509 -checkhost frontend -noout -in "$TLS_CERT_FILE" >/dev/null 2>&1 \
    || fail "local TLS certificate does not cover the internal TLS proxy name"
  for file in "$TLS_CERT_FILE" "$STORAGE_CERT_FILE"; do
    # Python 3.13+ and other strict verifiers reject leaves without an Authority Key Identifier;
    # certificates issued by OpenSSL 4 only carry one when requested explicitly.
    [[ "$(openssl x509 -noout -ext authorityKeyIdentifier -in "$file" 2>/dev/null)" == *"Authority Key Identifier"* ]] \
      || fail "$file lacks an Authority Key Identifier required by strict TLS clients; run '$0 reset-certificates --confirm'"
  done
  openssl verify -CAfile "$CA_CERT_FILE" "$STORAGE_CERT_FILE" >/dev/null 2>&1 \
    || fail "local storage TLS certificate is not signed by the retained local CA; run '$0 reset-certificates --confirm'"
  openssl x509 -checkend 604800 -noout -in "$STORAGE_CERT_FILE" >/dev/null 2>&1 \
    || fail "local storage TLS certificate expires within seven days; run '$0 reset-certificates --confirm'"
  openssl x509 -checkhost storage.mnema.localhost -noout -in "$STORAGE_CERT_FILE" >/dev/null 2>&1 \
    || fail "local storage TLS certificate does not cover storage.mnema.localhost"
  keytool -list -keystore "$TRUSTSTORE_FILE" -storetype PKCS12 -storepass "$TRUSTSTORE_PASSWORD" \
    -alias mnema-local-ca >/dev/null 2>&1 || fail "Learning local truststore is invalid"
}

# Issues the object-storage leaf from the CA in $2 (the retained CA unless rotating).
generate_storage_certificate() {
  local destination="$1" ca_dir="$2"
  openssl req -new -newkey rsa:2048 -sha256 -nodes -subj '/CN=storage.mnema.localhost' \
    -keyout "$destination/storage.key" -out "$destination/storage.csr" >/dev/null 2>&1
  printf '%s\n' \
    'basicConstraints=critical,CA:FALSE' \
    'keyUsage=critical,digitalSignature,keyEncipherment' \
    'extendedKeyUsage=serverAuth' \
    'subjectKeyIdentifier=hash' 'authorityKeyIdentifier=keyid:always' \
    'subjectAltName=DNS:storage.mnema.localhost' > "$destination/storage.ext"
  openssl x509 -req -sha256 -days 825 -in "$destination/storage.csr" \
    -CA "$ca_dir/local-ca.crt" -CAkey "$ca_dir/local-ca.key" -CAcreateserial \
    -extfile "$destination/storage.ext" -out "$destination/storage.crt" >/dev/null 2>&1
}

# Adds object-storage credentials, port and certificate to state created before the
# media stack existed, without rotating the signing key, database password or CA.
ensure_storage_state() {
  local temporary
  if [[ -z "$MNEMA_LOCAL_S3_ACCESS_KEY_VALUE" ]]; then
    local storage_port="${MNEMA_LOCAL_STORAGE_PORT:-3445}"
    validate_port MNEMA_LOCAL_STORAGE_PORT "$storage_port"
    temporary="$(mktemp "$STATE_DIR/runtime.XXXXXX")"
    cp "$RUNTIME_FILE" "$temporary"
    printf 'MNEMA_LOCAL_STORAGE_PORT=%s\nMNEMA_LOCAL_S3_ACCESS_KEY=%s\nMNEMA_LOCAL_S3_SECRET_KEY=%s\n' \
      "$storage_port" "$(openssl rand -hex 16)" "$(openssl rand -hex 32)" >> "$temporary"
    chmod 600 "$temporary"
    mv "$temporary" "$RUNTIME_FILE"
    load_runtime
  fi
  if [[ -e "$STORAGE_CERT_FILE" || -e "$STORAGE_KEY_FILE" ]]; then
    [[ -e "$STORAGE_CERT_FILE" && -e "$STORAGE_KEY_FILE" ]] \
      || fail "partial local storage certificate state in $STATE_DIR; use reset-certificates"
    return
  fi
  temporary="$(mktemp -d "$STATE_DIR/storage.XXXXXX")"
  generate_storage_certificate "$temporary" "$STATE_DIR"
  chmod 600 "$temporary/storage.key"
  chmod 644 "$temporary/storage.crt"
  mv "$temporary/storage.key" "$STORAGE_KEY_FILE"
  mv "$temporary/storage.crt" "$STORAGE_CERT_FILE"
  rm -rf "$temporary"
}

generate_truststore() {
  local destination="$1" ca_file="$2" java_runtime
  java_runtime="$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^[[:space:]]*java.home = //p')"
  [[ -f "$java_runtime/lib/security/cacerts" ]] || fail "Java public CA bundle is missing"
  # A custom JSSE truststore replaces the platform roots; retain both public HTTPS
  # providers and the local proxy/storage CA instead of trusting only localhost.
  keytool -importkeystore -noprompt -srckeystore "$java_runtime/lib/security/cacerts" \
    -srcstorepass changeit -destkeystore "$destination" -deststoretype PKCS12 \
    -deststorepass "$TRUSTSTORE_PASSWORD" >/dev/null 2>&1
  keytool -importcert -noprompt -storetype PKCS12 -alias mnema-local-ca \
    -file "$ca_file" -keystore "$destination" -storepass "$TRUSTSTORE_PASSWORD" >/dev/null 2>&1
  chmod 600 "$destination"
}

ensure_public_truststore() {
  local certificates temporary
  certificates="$(keytool -list -rfc -keystore "$TRUSTSTORE_FILE" -storetype PKCS12 \
    -storepass "$TRUSTSTORE_PASSWORD" 2>/dev/null | grep -c 'BEGIN CERTIFICATE')"
  (( certificates > 1 )) && return
  temporary="$(mktemp -d "$STATE_DIR/truststore.XXXXXX")"
  generate_truststore "$temporary/truststore.p12" "$CA_CERT_FILE"
  mv "$temporary/truststore.p12" "$TRUSTSTORE_FILE"
  rmdir "$temporary"
}

generate_certificate_material() {
  local destination="$1"
  openssl req -x509 -newkey rsa:3072 -sha256 -nodes -days 3650 \
    -subj '/CN=Mnema Local Development CA' \
    -addext 'basicConstraints=critical,CA:TRUE' \
    -addext 'keyUsage=critical,keyCertSign,cRLSign' \
    -addext 'subjectKeyIdentifier=hash' \
    -keyout "$destination/local-ca.key" -out "$destination/local-ca.crt" >/dev/null 2>&1
  openssl req -new -newkey rsa:2048 -sha256 -nodes -subj '/CN=localhost' \
    -keyout "$destination/localhost.key" -out "$destination/localhost.csr" >/dev/null 2>&1
  printf '%s\n' \
    'basicConstraints=critical,CA:FALSE' \
    'keyUsage=critical,digitalSignature,keyEncipherment' \
    'extendedKeyUsage=serverAuth' \
    'subjectKeyIdentifier=hash' 'authorityKeyIdentifier=keyid:always' \
    'subjectAltName=DNS:localhost,DNS:frontend,IP:127.0.0.1,IP:::1' > "$destination/localhost.ext"
  openssl x509 -req -sha256 -days 825 -in "$destination/localhost.csr" \
    -CA "$destination/local-ca.crt" -CAkey "$destination/local-ca.key" -CAcreateserial \
    -extfile "$destination/localhost.ext" -out "$destination/localhost.crt" >/dev/null 2>&1
  generate_storage_certificate "$destination" "$destination"
  generate_truststore "$destination/learning-truststore.p12" "$destination/local-ca.crt"
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
    load_runtime upgradeable
    ensure_storage_state
    validate_material
    ensure_public_truststore
    install -d -m 700 "$MEDIA_WORK_ROOT"
    printf '[ok] Reusing retained local credentials and certificates in %s\n' "$STATE_DIR"
    return
  fi
  (( present == 0 )) || fail "partial local security state found in $STATE_DIR; remove it manually after inspection or use reset-certificates"

  local web_port="${MNEMA_LOCAL_WEB_PORT:-3443}"
  local identity_port="${MNEMA_LOCAL_IDENTITY_PORT:-3444}"
  validate_port MNEMA_LOCAL_WEB_PORT "$web_port"
  local storage_port="${MNEMA_LOCAL_STORAGE_PORT:-3445}"
  validate_port MNEMA_LOCAL_IDENTITY_PORT "$identity_port"
  validate_port MNEMA_LOCAL_STORAGE_PORT "$storage_port"
  [[ "$web_port" != "$identity_port" && "$web_port" != "$storage_port" && "$identity_port" != "$storage_port" ]] \
    || fail "web, Identity and object-storage ports must differ"
  local temporary
  temporary="$(mktemp -d "$STATE_DIR/bootstrap.XXXXXX")"
  trap 'rm -rf "$temporary"' EXIT HUP INT TERM
  generate_certificate_material "$temporary"
  java "$ROOT_DIR/scripts/learning-security/FixtureKey.java" "$temporary/identity-signing-jwk-set.json"
  printf '%s\n' \
    "MNEMA_LOCAL_POSTGRES_PASSWORD=$(openssl rand -hex 32)" \
    'MNEMA_LOCAL_POSTGRES_DB=mnema' 'MNEMA_LOCAL_POSTGRES_USER=mnema' \
    "MNEMA_LOCAL_WEB_PORT=$web_port" "MNEMA_LOCAL_IDENTITY_PORT=$identity_port" \
    "MNEMA_LOCAL_STORAGE_PORT=$storage_port" \
    "MNEMA_LOCAL_S3_ACCESS_KEY=$(openssl rand -hex 16)" "MNEMA_LOCAL_S3_SECRET_KEY=$(openssl rand -hex 32)" \
    > "$temporary/runtime.env"
  chmod 600 "$temporary"/*
  chmod 644 "$temporary/local-ca.crt" "$temporary/localhost.crt" "$temporary/storage.crt"
  mv "$temporary/runtime.env" "$RUNTIME_FILE"
  mv "$temporary/identity-signing-jwk-set.json" "$JWK_FILE"
  mv "$temporary/local-ca.crt" "$CA_CERT_FILE"
  mv "$temporary/local-ca.key" "$CA_KEY_FILE"
  mv "$temporary/localhost.crt" "$TLS_CERT_FILE"
  mv "$temporary/localhost.key" "$TLS_KEY_FILE"
  mv "$temporary/storage.crt" "$STORAGE_CERT_FILE"
  mv "$temporary/storage.key" "$STORAGE_KEY_FILE"
  mv "$temporary/learning-truststore.p12" "$TRUSTSTORE_FILE"
  rm -rf "$temporary"
  trap - EXIT HUP INT TERM
  load_runtime
  validate_material
  install -d -m 700 "$MEDIA_WORK_ROOT"
  printf '[ok] Generated retained local-only credentials in %s (nothing is committed).\n' "$STATE_DIR"
}

compose() {
  require_command docker
  docker compose version >/dev/null 2>&1 || fail "Docker Compose plugin is required"
  local build_id
  local compose_files=(--file "$COMPOSE_FILE")
  case "${MNEMA_LOCAL_AI_SPLIT:-false}" in
    true) compose_files+=(--file "$ROOT_DIR/compose.local-ai-worker.yml") ;;
    false) ;;
    *) fail "MNEMA_LOCAL_AI_SPLIT must be true or false" ;;
  esac
  # Compose parses dotenv as data; never source the owner's file as shell code. The AI provider names (MNEMA_AI_*,
  # LEARNING_FEATURES_AI_GENERATION_ENABLED, LEARNING_FEATURES_AI_ASSESSMENT_ENABLED, LEARNING_FEATURES_IMAGE_SEARCH_ENABLED, LEARNING_AI_PROVIDER) are not listed here: Compose substitutes them from this
  # process environment or from that file straight into the Learning service (see compose.local-full-stack.yml).
  local oauth_env_file="${MNEMA_LOCAL_OAUTH_ENV_FILE:-$ROOT_DIR/.env}"
  if [[ ! -e "$oauth_env_file" && -z "${MNEMA_LOCAL_OAUTH_ENV_FILE:-}" ]]; then oauth_env_file=/dev/null; fi
  [[ -r "$oauth_env_file" && ! -d "$oauth_env_file" ]] || fail "OAuth env file is not readable"
  build_id="$(git -C "$ROOT_DIR" rev-parse HEAD 2>/dev/null || printf dev)"
  COMPOSE_DISABLE_ENV_FILE=true \
  MNEMA_LOCAL_TRUSTSTORE_REVISION="$(openssl dgst -sha256 "$TRUSTSTORE_FILE" | awk '{print $NF}')" \
  MNEMA_LOCAL_POSTGRES_PASSWORD="$MNEMA_LOCAL_POSTGRES_PASSWORD_VALUE" \
  MNEMA_LOCAL_POSTGRES_DB="$MNEMA_LOCAL_POSTGRES_DB_VALUE" \
  MNEMA_LOCAL_POSTGRES_USER="$MNEMA_LOCAL_POSTGRES_USER_VALUE" \
  MNEMA_LOCAL_WEB_PORT="$MNEMA_LOCAL_WEB_PORT_VALUE" \
  MNEMA_LOCAL_IDENTITY_PORT="$MNEMA_LOCAL_IDENTITY_PORT_VALUE" \
  MNEMA_LOCAL_STORAGE_PORT="$MNEMA_LOCAL_STORAGE_PORT_VALUE" \
  MNEMA_LOCAL_S3_ACCESS_KEY="$MNEMA_LOCAL_S3_ACCESS_KEY_VALUE" \
  MNEMA_LOCAL_S3_SECRET_KEY="$MNEMA_LOCAL_S3_SECRET_KEY_VALUE" \
  MNEMA_LOCAL_MEDIA_WORK_ROOT="$MEDIA_WORK_ROOT" \
  MNEMA_LOCAL_MEDIA_WORKER_IMAGE="$MEDIA_WORKER_IMAGE" \
  MNEMA_LOCAL_BUILD_ID="$build_id" \
  MNEMA_LOCAL_IDENTITY_SIGNING_JWK_SET_FILE="$JWK_FILE" \
  MNEMA_LOCAL_TLS_CERT_FILE="$TLS_CERT_FILE" \
  MNEMA_LOCAL_TLS_KEY_FILE="$TLS_KEY_FILE" \
  MNEMA_LOCAL_STORAGE_TLS_CERT_FILE="$STORAGE_CERT_FILE" \
  MNEMA_LOCAL_STORAGE_TLS_KEY_FILE="$STORAGE_KEY_FILE" \
  MNEMA_LOCAL_CA_CERT_FILE="$CA_CERT_FILE" \
  MNEMA_LOCAL_TRUSTSTORE_FILE="$TRUSTSTORE_FILE" \
    docker compose --env-file "$oauth_env_file" --project-name "$PROJECT_NAME" "${compose_files[@]}" "$@"
}

require_private_state() {
  local file
  for file in "$@"; do
    [[ -e "$file" ]] || continue
    [[ -f "$file" && ! -L "$file" ]] || fail "$file must be a regular file"
    private_mode "$file" || fail "$file contains local credentials and must not be accessible by group or other users"
  done
}

account_smoke() {
  python3 "$ROOT_DIR/scripts/local-full-stack/smoke.py" \
    --web-origin "https://localhost:$MNEMA_LOCAL_WEB_PORT_VALUE" \
    --identity-origin "https://localhost:$MNEMA_LOCAL_IDENTITY_PORT_VALUE" \
    --ca "$CA_CERT_FILE" --state-file "$SMOKE_STATE_FILE" "$@"
}

media_smoke() {
  python3 "$ROOT_DIR/scripts/local-full-stack/media_smoke.py" \
    --web-origin "https://localhost:$MNEMA_LOCAL_WEB_PORT_VALUE" \
    --identity-origin "https://localhost:$MNEMA_LOCAL_IDENTITY_PORT_VALUE" \
    --storage-port "$MNEMA_LOCAL_STORAGE_PORT_VALUE" \
    --ca "$CA_CERT_FILE" --state-file "$MEDIA_SMOKE_STATE_FILE"
}

# "smoke" runs the account/Study checks and the media upload/processing check
# independently and fails if either fails; "smoke-media" runs only the latter.
smoke() {
  require_command python3
  require_private_state "$SMOKE_STATE_FILE" "$MEDIA_SMOKE_STATE_FILE"
  local status=0
  account_smoke "$@" || status=1
  # Readiness-only runs (used by start) skip the slower upload/processing round trip.
  local argument
  for argument in "$@"; do
    [[ "$argument" != --readiness-only ]] || return "$status"
  done
  media_smoke || status=1
  return "$status"
}

start() {
  require_command docker
  require_command openssl
  require_command keytool
  require_command python3
  require_command curl
  bootstrap
  # The processor starts this image per job through Docker; it is never a compose service.
  compose --profile worker-image build media-worker-image
  if ! compose up --detach --build --wait --wait-timeout 300; then
    printf '[error] local stack failed readiness; inspect bounded status/logs below\n' >&2
    compose ps >&2 || true
    compose logs --tail=80 >&2 || true
    compose stop >/dev/null 2>&1 \
      || printf '[error] failed to stop the unhealthy local stack; run %s stop\n' "$0" >&2
    exit 1
  fi
  smoke --readiness-only
  printf '[ok] Mnema and local media processing are ready at https://localhost:%s\n' "$MNEMA_LOCAL_WEB_PORT_VALUE"
  if ! curl --silent --fail "https://localhost:$MNEMA_LOCAL_WEB_PORT_VALUE/" >/dev/null 2>&1; then
    printf '[action] Trust this local CA in your browser/OS, then reopen Mnema: %s\n' "$CA_CERT_FILE"
  fi
  printf '[info] Run %s smoke for PKCE, authoring, Study and media upload/processing verification.\n' "$0"
}

reset_data() {
  [[ "${1:-}" == "--confirm-delete-local-data" ]] \
    || fail "reset deletes the $PROJECT_NAME PostgreSQL and object-storage volumes; rerun with --confirm-delete-local-data"
  require_command openssl
  require_command keytool
  load_runtime
  validate_material
  compose down --volumes --remove-orphans
  rm -f "$SMOKE_STATE_FILE" "$MEDIA_SMOKE_STATE_FILE"
  if [[ -d "$MEDIA_WORK_ROOT" && ! -L "$MEDIA_WORK_ROOT" ]]; then
    rm -rf "$MEDIA_WORK_ROOT" || printf '[warn] could not remove %s; delete it manually\n' "$MEDIA_WORK_ROOT" >&2
    install -d -m 700 "$MEDIA_WORK_ROOT"
  fi
  printf '[ok] Deleted only the %s containers, PostgreSQL and object-storage volumes and smoke state; retained local CA/JWK material.\n' "$PROJECT_NAME"
}

reset_certificates() {
  [[ "${1:-}" == "--confirm" ]] || fail "certificate reset changes the trusted local CA; rerun with --confirm"
  require_command openssl
  require_command keytool
  load_runtime
  validate_material rotating
  compose down --remove-orphans >/dev/null 2>&1 || true
  local temporary
  temporary="$(mktemp -d "$STATE_DIR/certificates.XXXXXX")"
  trap 'rm -rf "$temporary"' EXIT HUP INT TERM
  generate_certificate_material "$temporary"
  chmod 600 "$temporary"/*
  chmod 644 "$temporary/local-ca.crt" "$temporary/localhost.crt" "$temporary/storage.crt"
  mv "$temporary/local-ca.crt" "$CA_CERT_FILE"
  mv "$temporary/local-ca.key" "$CA_KEY_FILE"
  mv "$temporary/localhost.crt" "$TLS_CERT_FILE"
  mv "$temporary/localhost.key" "$TLS_KEY_FILE"
  mv "$temporary/storage.crt" "$STORAGE_CERT_FILE"
  mv "$temporary/storage.key" "$STORAGE_KEY_FILE"
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
  smoke-media)
    require_command openssl; require_command keytool; require_command python3
    load_runtime; validate_material; require_private_state "$MEDIA_SMOKE_STATE_FILE"; media_smoke ;;
  reset) reset_data "$@" ;;
  reset-certificates) reset_certificates "$@" ;;
  *) fail "usage: $0 {bootstrap|start|stop|status|logs [lines]|smoke|smoke-media|reset --confirm-delete-local-data|reset-certificates --confirm}" ;;
esac
