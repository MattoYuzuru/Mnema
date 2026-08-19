#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
CREATE_KUBECONFIG="$SCRIPT_DIR/create-staging-kubeconfig.sh"
TEST_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/mnema-staging-kubeconfig.XXXXXX")
trap 'rm -rf "$TEST_ROOT"' EXIT HUP INT TERM

mkdir "$TEST_ROOT/bin"
cat >"$TEST_ROOT/bin/kubectl" <<'EOF'
#!/bin/sh
set -eu

if [ "${1:-}" = config ]; then
  printf '%s' 'dGVzdC1jYQ=='
  exit 0
fi
if [ "${1:-}" = -n ] && [ "${2:-}" = mnema-staging ] && \
   [ "${3:-}" = create ] && [ "${4:-}" = token ] && \
   [ "${5:-}" = mnema-deployer ] && [ "${6:-}" = --duration=720h ] && \
   [ "${7:-}" = -o ]; then
  printf '%s' 'header.payload.signature|2026-09-18T00:00:00Z'
  exit 0
fi
if [ "${1:-}" = auth ] && [ "${2:-}" = can-i ]; then
  case "$*" in
    *'create deployments.apps -n mnema-staging'*) printf '%s\n' yes ;;
    *'create secrets -n mnema-staging'*) printf '%s\n' yes ;;
    *'create ingresses.networking.k8s.io -n mnema-staging'*) printf '%s\n' no ;;
    *'get secrets -n prod'*) printf '%s\n' no ;;
    *'create namespaces'*) printf '%s\n' no ;;
    *) exit 65 ;;
  esac
  exit 0
fi

exit 64
EOF
chmod +x "$TEST_ROOT/bin/kubectl"

output="$TEST_ROOT/credentials/staging.kubeconfig"
result=$(PATH="$TEST_ROOT/bin:$PATH" \
  OUTPUT="$output" \
  KUBE_API_SERVER=https://kubernetes.main.example:6443 \
  TOKEN_DURATION=720h \
  "$CREATE_KUBECONFIG")

grep -Fq 'server: https://kubernetes.main.example:6443' "$output"
grep -Fq 'token: header.payload.signature' "$output"
printf '%s\n' "$result" | grep -Fq 'token_expires_at=2026-09-18T00:00:00Z'

if stat -f '%Lp' "$output" >/dev/null 2>&1; then
  mode=$(stat -f '%Lp' "$output")
else
  mode=$(stat -c '%a' "$output")
fi
test "$mode" = 600

if PATH="$TEST_ROOT/bin:$PATH" \
  OUTPUT="$TEST_ROOT/loopback.kubeconfig" \
  KUBE_API_SERVER=https://127.0.0.1:6443 \
  "$CREATE_KUBECONFIG" >/dev/null 2>&1; then
  echo 'loopback Kubernetes API endpoints must be rejected' >&2
  exit 1
fi

if PATH="$TEST_ROOT/bin:$PATH" \
  OUTPUT="$output" \
  KUBE_API_SERVER=https://kubernetes.main.example:6443 \
  "$CREATE_KUBECONFIG" >/dev/null 2>&1; then
  echo 'existing credentials must not be overwritten' >&2
  exit 1
fi

printf 'staging_kubeconfig_contract=ok\n'
