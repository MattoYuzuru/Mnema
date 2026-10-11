#!/bin/sh
# Link previews without SSR (#436, docs/operations/link-previews.md): starts the pinned nginx image once per web-server configuration of the
# repository (the production host file, the standalone frontend image file, the local full stack file) against the stand-in Learning of
# scripts/tests/link-previews-fake-learning.conf and runs scripts/verify_link_previews.py. The production build of the frontend is required, because the
# contract binds the real index.html (its block, its include and the JSON-LD hash).
set -eu

SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH='' cd -- "$SCRIPT_DIR/.." && pwd)
FRONTEND_DIST="${FRONTEND_DIST:-$REPO_ROOT/frontend/dist/mnema-frontend}"
GENERATOR="$REPO_ROOT/frontend/docker/40-gen-app-config.sh"
VERIFIER="$SCRIPT_DIR/verify_link_previews.py"
FAKE="$SCRIPT_DIR/tests/link-previews-fake-learning.conf"
TEST_ROOT=$(mktemp -d "$REPO_ROOT/.link-previews-contract.XXXXXX")
CONTAINERS=""
NETWORK=""

cleanup() {
  for container in $CONTAINERS; do
    docker stop "$container" >/dev/null 2>&1 || true
  done
  if [ -n "$NETWORK" ]; then
    docker network rm "$NETWORK" >/dev/null 2>&1 || true
  fi
  rm -rf "$TEST_ROOT"
}
trap cleanup EXIT HUP INT TERM

if [ ! -f "$FRONTEND_DIST/index.html" ]; then
  echo "Missing production frontend build: $FRONTEND_DIST/index.html" >&2
  exit 1
fi
if ! command -v docker >/dev/null 2>&1 || ! docker info >/dev/null 2>&1; then
  echo "Docker is required for the link-preview contract" >&2
  exit 1
fi

NGINX_IMAGE=$(awk '/^FROM nginx:/ {print $2; exit}' "$REPO_ROOT/frontend/Dockerfile")
case "$NGINX_IMAGE" in
  nginx:*@sha256:*) ;;
  *) echo "Frontend nginx image must be pinned by sha256" >&2; exit 1 ;;
esac

# The built page carries the block and the include the nginx configurations rely on, and the JSON-LD block is still the one the CSP hash admits.
grep -Fq '<!--# block name="mnema_head" -->' "$FRONTEND_DIST/index.html"
grep -Fq '<!--# endblock -->' "$FRONTEND_DIST/index.html"
grep -Fq '<!--# include virtual="/__mnema_og_head" stub="mnema_head" -->' "$FRONTEND_DIST/index.html"
# The three configurations carry the same deck-preview locations; only the upstream line differs.
for config in deploy/production/nginx.conf frontend/nginx.conf deploy/local-full-stack/nginx.conf; do
  grep -Fq 'location @mnema_og_page {' "$REPO_ROOT/$config"
  grep -Fq 'location = /__mnema_og_gate {' "$REPO_ROOT/$config"
  grep -Fq 'location = /__mnema_og_head {' "$REPO_ROOT/$config"
done

prepare() {
  name="$1"
  root="$TEST_ROOT/$name"
  mkdir -p "$root/html"
  cp -R "$FRONTEND_DIST/." "$root/html/"
  MNEMA_APP_CONFIG_OUT="$root/html/app-config.js" \
  MNEMA_SECURITY_HEADERS_OUT="$root/security-headers.inc" \
  MNEMA_OG_META_OUT="$root/mnema-og-meta.inc" \
  MNEMA_OG_META_UPSTREAM="${2:-}" \
  MNEMA_APP_ENV=prod \
  MNEMA_PUBLIC_ORIGIN="https://app.example.test" \
  MNEMA_AUTH_SERVER_URL="https://auth.example.test" \
  MNEMA_STORAGE_ORIGIN="https://storage.example.test" \
    "$GENERATOR"
}

# How many calls a page view costs Learning, counted by the stand-in's log: a cached card is one call per minute for a code however often and however
# many visitors at once look at it, "disabled" is cached for ten seconds, a refusal for one visitor's limit (429) is never cached, and a failing gate
# is exactly one call (the plain page needs no second one).
cache_contract() {
  stand_in="$1"
  url="$2"
  label="$3"
  calls() {
    docker exec "$stand_in" sh -c "grep -c '/decks/$1/head' /var/log/nginx/fake-learning.log || true"
  }
  docker exec "$stand_in" sh -c ': > /var/log/nginx/fake-learning.log'
  for code in Ca7xT3mNpR Ca7xT3mNpR Ca7xT3mNpR/other-slug Cn7xT3mNpR Cn7xT3mNpR Cr7xT3mNpR Cr7xT3mNpR; do
    curl --silent --insecure --output /dev/null "$url/d/$code"
  done
  for _ in 1 2 3 4 5 6 7 8; do
    curl --silent --insecure --output /dev/null "$url/d/Cp7xT3mNpR/parallel" &
  done
  wait
  test "$(calls Ca7xT3mNpR)" = 1 || { echo "$label: a cached card cost $(calls Ca7xT3mNpR) calls, expected 1" >&2; return 1; }
  test "$(calls Cn7xT3mNpR)" = 1 || { echo "$label: a cached 404 cost $(calls Cn7xT3mNpR) calls, expected 1" >&2; return 1; }
  test "$(calls Cr7xT3mNpR)" = 2 || { echo "$label: a 429 must not be cached (two views, $(calls Cr7xT3mNpR) calls)" >&2; return 1; }
  test "$(calls Cp7xT3mNpR)" = 1 || { echo "$label: eight parallel views cost $(calls Cp7xT3mNpR) calls, expected 1" >&2; return 1; }
  printf 'link_previews_contract=%s-learning-calls:ok\n' "$label"
}

wait_ready() {
  url="$1"
  attempts=0
  until curl --silent --insecure --fail "$url" >/dev/null 2>&1; do
    attempts=$((attempts + 1))
    if [ "$attempts" -ge 30 ]; then
      echo "nginx contract container did not become ready: $url" >&2
      return 1
    fi
    sleep 1
  done
}

# 1. The production host file: Learning is on loopback of the same (host-network) container, here the stand-in in the same container.
prepare production
# The host file listens on loopback because the production container uses the host network; a published port needs the wildcard. That one line is the
# only difference from the file under test.
sed 's/^  listen 127.0.0.1:18080;$/  listen 18080;/' "$REPO_ROOT/deploy/production/nginx.conf" > "$TEST_ROOT/production/nginx.conf"
test "$(diff "$REPO_ROOT/deploy/production/nginx.conf" "$TEST_ROOT/production/nginx.conf" | grep -c '^[<>]')" = 2
# The container gets the capability set, limits and non-root cache directory of the production Compose service, because the proxy cache is created by the
# nginx master under exactly those capabilities.
container=$(docker run --detach --rm --publish 127.0.0.1::18080 \
  --cap-drop ALL --cap-add CHOWN --cap-add SETUID --cap-add SETGID --security-opt no-new-privileges:true --pids-limit 64 --memory 256m --cpus 0.5 \
  --volume "$TEST_ROOT/production/nginx.conf:/etc/nginx/conf.d/default.conf:ro" \
  --volume "$TEST_ROOT/production/security-headers.inc:/etc/nginx/conf.d/security-headers.inc:ro" \
  --volume "$FAKE:/etc/nginx/conf.d/fake-learning.conf:ro" \
  --volume "$TEST_ROOT/production/html:/usr/share/nginx/html:ro" \
  "$NGINX_IMAGE")
CONTAINERS="$CONTAINERS $container"
port=$(docker port "$container" 18080/tcp | head -n 1)
port=${port##*:}
wait_ready "http://127.0.0.1:$port/"
python3 "$VERIFIER" --base-url "http://127.0.0.1:$port" --label production-host-file --api-404
cache_contract "$container" "http://127.0.0.1:$port" production-host-file
docker stop "$container" >/dev/null
CONTAINERS=""

# 2. The standalone frontend image file with the generated include that names the upstream; and without it the feature is off.
prepare standalone 127.0.0.1:18082
container=$(docker run --detach --rm --publish 127.0.0.1::80 \
  --volume "$REPO_ROOT/frontend/nginx.conf:/etc/nginx/conf.d/default.conf:ro" \
  --volume "$TEST_ROOT/standalone/security-headers.inc:/etc/nginx/conf.d/security-headers.inc:ro" \
  --volume "$TEST_ROOT/standalone/mnema-og-meta.inc:/etc/nginx/conf.d/mnema-og-meta.inc:ro" \
  --volume "$FAKE:/etc/nginx/conf.d/fake-learning.conf:ro" \
  --volume "$TEST_ROOT/standalone/html:/usr/share/nginx/html:ro" \
  "$NGINX_IMAGE")
CONTAINERS="$container"
port=$(docker port "$container" 80/tcp | head -n 1)
port=${port##*:}
wait_ready "http://127.0.0.1:$port/"
python3 "$VERIFIER" --base-url "http://127.0.0.1:$port" --label standalone-image --api-404
cache_contract "$container" "http://127.0.0.1:$port" standalone-image
docker stop "$container" >/dev/null
CONTAINERS=""

prepare standalone-off
container=$(docker run --detach --rm --publish 127.0.0.1::80 \
  --volume "$REPO_ROOT/frontend/nginx.conf:/etc/nginx/conf.d/default.conf:ro" \
  --volume "$TEST_ROOT/standalone-off/security-headers.inc:/etc/nginx/conf.d/security-headers.inc:ro" \
  --volume "$TEST_ROOT/standalone-off/mnema-og-meta.inc:/etc/nginx/conf.d/mnema-og-meta.inc:ro" \
  --volume "$FAKE:/etc/nginx/conf.d/fake-learning.conf:ro" \
  --volume "$TEST_ROOT/standalone-off/html:/usr/share/nginx/html:ro" \
  "$NGINX_IMAGE")
CONTAINERS="$container"
port=$(docker port "$container" 80/tcp | head -n 1)
port=${port##*:}
wait_ready "http://127.0.0.1:$port/"
python3 - "$port" <<'PY'
import http.client, sys
connection = http.client.HTTPConnection("127.0.0.1", int(sys.argv[1]), timeout=10)
connection.request("GET", "/d/Kq7xT3mNpR/ispanskie-glagoly")
response = connection.getresponse()
body = response.read().decode()
assert response.status == 200, response.status
assert "<title>Mnema</title>" in body and "Испанские" not in body, "without an upstream /d must serve the defaults"
print("link_previews_contract=standalone-image-without-upstream:ok")
PY
docker stop "$container" >/dev/null
CONTAINERS=""

# 3. The local full stack file: Learning is the host `learning` of a Docker network (resolver 127.0.0.11), TLS with a throw-away certificate.
prepare local
openssl req -x509 -newkey rsa:2048 -sha256 -nodes -days 1 -subj '/CN=localhost' \
  -keyout "$TEST_ROOT/local/tls.key" -out "$TEST_ROOT/local/tls.crt" >/dev/null 2>&1
sed 's/listen 127.0.0.1:18082;/listen 8080;/' "$FAKE" > "$TEST_ROOT/local/fake-learning.conf"
NETWORK="mnema-link-previews-$$"
docker network create "$NETWORK" >/dev/null
fake=$(docker run --detach --rm --network "$NETWORK" --network-alias learning \
  --volume "$TEST_ROOT/local/fake-learning.conf:/etc/nginx/conf.d/default.conf:ro" "$NGINX_IMAGE")
CONTAINERS="$fake"
container=$(docker run --detach --rm --network "$NETWORK" --publish 127.0.0.1::8443 \
  --volume "$REPO_ROOT/deploy/local-full-stack/nginx.conf:/etc/nginx/conf.d/default.conf:ro" \
  --volume "$TEST_ROOT/local/security-headers.inc:/etc/nginx/conf.d/security-headers.inc:ro" \
  --volume "$TEST_ROOT/local/tls.crt:/run/secrets/mnema_local_tls_cert:ro" \
  --volume "$TEST_ROOT/local/tls.key:/run/secrets/mnema_local_tls_key:ro" \
  --volume "$TEST_ROOT/local/html:/usr/share/nginx/html:ro" \
  "$NGINX_IMAGE")
CONTAINERS="$fake $container"
port=$(docker port "$container" 8443/tcp | head -n 1)
port=${port##*:}
wait_ready "https://127.0.0.1:$port/"
python3 "$VERIFIER" --base-url "https://127.0.0.1:$port" --label local-full-stack --insecure
cache_contract "$fake" "https://127.0.0.1:$port" local-full-stack

printf 'link_previews_contract=ok\n'
