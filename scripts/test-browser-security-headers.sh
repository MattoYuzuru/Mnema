#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH='' cd -- "$SCRIPT_DIR/.." && pwd)
FRONTEND_DIST="${FRONTEND_DIST:-$REPO_ROOT/frontend/dist/mnema-frontend}"
GENERATOR="$REPO_ROOT/frontend/docker/40-gen-app-config.sh"
VERIFIER="$SCRIPT_DIR/verify_browser_security_headers.py"
TEST_ROOT=$(mktemp -d "$REPO_ROOT/.browser-security-contract.XXXXXX")
CONTAINER_ID=""

cleanup() {
  if [ -n "$CONTAINER_ID" ]; then
    docker stop "$CONTAINER_ID" >/dev/null 2>&1 || true
  fi
  rm -rf "$TEST_ROOT"
}
trap cleanup EXIT HUP INT TERM

if [ ! -f "$FRONTEND_DIST/index.html" ]; then
  echo "Missing production frontend build: $FRONTEND_DIST/index.html" >&2
  exit 1
fi
if ! command -v docker >/dev/null 2>&1 || ! docker info >/dev/null 2>&1; then
  echo "Docker is required for the nginx response-security contract" >&2
  exit 1
fi

NGINX_IMAGE=$(awk '/^FROM nginx:/ {print $2; exit}' "$REPO_ROOT/frontend/Dockerfile")
case "$NGINX_IMAGE" in
  nginx:*@sha256:*) ;;
  *) echo "Frontend nginx image must be pinned by sha256" >&2; exit 1 ;;
esac

grep -Fq 'server_tokens off;' "$REPO_ROOT/frontend/nginx.conf"
grep -Fq 'add_header_inherit merge;' "$REPO_ROOT/frontend/nginx.conf"
grep -Fq 'include /etc/nginx/conf.d/security-headers.inc;' "$REPO_ROOT/frontend/nginx.conf"
grep -Fq 'run: ./scripts/test-browser-security-headers.sh' "$REPO_ROOT/.github/workflows/deploy.yaml"
grep -Fq 'run: ./scripts/test-browser-security-headers.sh' "$REPO_ROOT/.github/workflows/pull-request.yaml"
grep -Fq 'python3 scripts/verify_browser_security_headers.py hosted' "$REPO_ROOT/.github/workflows/staging-deploy.yaml"
grep -Fq 'python3 scripts/verify_browser_security_headers.py hosted' "$REPO_ROOT/.github/workflows/production-deploy.yaml"
grep -Fq './scripts/verify-hosted-browser-csp.sh https://staging.mnema.app/login' "$REPO_ROOT/.github/workflows/staging-deploy.yaml"
grep -Fq './scripts/verify-hosted-browser-csp.sh https://mnema.app/login' "$REPO_ROOT/.github/workflows/production-deploy.yaml"
sh -n "$REPO_ROOT/scripts/verify-hosted-browser-csp.sh"

FAKE_CHROME="$REPO_ROOT/scripts/tests/fake-hosted-chrome.sh"
MNEMA_FAKE_CHROME_CASE=rendered CHROME_BIN="$FAKE_CHROME" \
  "$REPO_ROOT/scripts/verify-hosted-browser-csp.sh" \
  https://app.example.test/login >/dev/null
if MNEMA_FAKE_CHROME_CASE=missing-response CHROME_BIN="$FAKE_CHROME" \
  "$REPO_ROOT/scripts/verify-hosted-browser-csp.sh" \
  https://app.example.test/login >/dev/null 2>&1; then
  echo "Hosted browser verifier accepted a Turnstile script without a rendered widget" >&2
  exit 1
fi
if MNEMA_FAKE_CHROME_CASE=csp-violation CHROME_BIN="$FAKE_CHROME" \
  "$REPO_ROOT/scripts/verify-hosted-browser-csp.sh" \
  https://app.example.test/login >/dev/null 2>&1; then
  echo "Hosted browser verifier accepted a same-origin CSP violation" >&2
  exit 1
fi

generate() {
  mode="$1"
  mode_root="$TEST_ROOT/$mode"
  mkdir -p "$mode_root/html"
  cp -R "$FRONTEND_DIST/." "$mode_root/html/"
  MNEMA_APP_CONFIG_OUT="$mode_root/html/app-config.js" \
  MNEMA_AI_ROUTE_OUT="$mode_root/ai-route.inc" \
  MNEMA_SECURITY_HEADERS_OUT="$mode_root/security-headers.inc" \
  MNEMA_APP_ENV="$mode" \
  MNEMA_PUBLIC_ORIGIN="https://app.example.test" \
  MNEMA_AUTH_SERVER_URL="https://auth.example.test" \
  MNEMA_STORAGE_ORIGIN="https://storage.example.test" \
  MNEMA_FEATURE_AI_ENABLED=false \
    "$GENERATOR"
  python3 "$VERIFIER" config \
    --headers "$mode_root/security-headers.inc" \
    --index "$mode_root/html/index.html" \
    --mode "$mode" \
    --auth-origin https://auth.example.test \
    --storage-origin https://storage.example.test
}

generate development
generate staging
generate prod

cp "$TEST_ROOT/staging/security-headers.inc" "$TEST_ROOT/missing-nosniff.inc"
sed -i.bak '/X-Content-Type-Options/d' "$TEST_ROOT/missing-nosniff.inc"
rm -f "$TEST_ROOT/missing-nosniff.inc.bak"
if python3 "$VERIFIER" config \
  --headers "$TEST_ROOT/missing-nosniff.inc" \
  --index "$TEST_ROOT/staging/html/index.html" \
  --mode staging \
  --auth-origin https://auth.example.test \
  --storage-origin https://storage.example.test >/dev/null 2>&1; then
  echo "Verifier accepted a missing nosniff header" >&2
  exit 1
fi

invalid_headers="$TEST_ROOT/invalid-security-headers.inc"
printf '%s\n' sentinel > "$invalid_headers"
if MNEMA_APP_CONFIG_OUT="$TEST_ROOT/invalid-app-config.js" \
  MNEMA_AI_ROUTE_OUT="$TEST_ROOT/invalid-ai-route.inc" \
  MNEMA_SECURITY_HEADERS_OUT="$invalid_headers" \
  MNEMA_APP_ENV=prod \
  MNEMA_PUBLIC_ORIGIN=https://app.example.test \
  MNEMA_AUTH_SERVER_URL='https://auth.example.test;add_header X-Injected yes' \
  MNEMA_STORAGE_ORIGIN=https://storage.example.test \
    "$GENERATOR" >/dev/null 2>&1; then
  echo "Runtime generator accepted an injected CSP origin" >&2
  exit 1
fi
test "$(cat "$invalid_headers")" = sentinel

if MNEMA_APP_CONFIG_OUT="$TEST_ROOT/invalid-mode-app-config.js" \
  MNEMA_AI_ROUTE_OUT="$TEST_ROOT/invalid-mode-ai-route.inc" \
  MNEMA_SECURITY_HEADERS_OUT="$TEST_ROOT/invalid-mode-security-headers.inc" \
  MNEMA_APP_ENV=preview \
    "$GENERATOR" >/dev/null 2>&1; then
  echo "Runtime generator accepted an unknown deployment environment" >&2
  exit 1
fi

run_container_contract() {
  mode="$1"
  mode_root="$TEST_ROOT/$mode"
  CONTAINER_ID=$(docker run --detach --rm \
    --publish 127.0.0.1::80 \
    --volume "$REPO_ROOT/frontend/nginx.conf:/etc/nginx/conf.d/default.conf:ro" \
    --volume "$mode_root/security-headers.inc:/etc/nginx/conf.d/security-headers.inc:ro" \
    --volume "$mode_root/ai-route.inc:/etc/nginx/conf.d/ai-route.inc:ro" \
    --volume "$mode_root/html:/usr/share/nginx/html:ro" \
    "$NGINX_IMAGE")
  published=$(docker port "$CONTAINER_ID" 80/tcp)
  port=${published##*:}
  base_url="http://127.0.0.1:$port"

  attempts=0
  until curl --silent --show-error --fail "$base_url/" >/dev/null 2>&1; do
    attempts=$((attempts + 1))
    if [ "$attempts" -ge 30 ]; then
      docker logs "$CONTAINER_ID" >&2 || true
      echo "nginx contract container did not become ready" >&2
      exit 1
    fi
    sleep 1
  done

  python3 "$VERIFIER" hosted \
    --base-url "$base_url" \
    --mode "$mode" \
    --auth-origin https://auth.example.test \
    --storage-origin https://storage.example.test
  docker stop "$CONTAINER_ID" >/dev/null
  CONTAINER_ID=""
}

run_container_contract staging
run_container_contract prod

printf 'browser_security_headers_contract=ok\n'
