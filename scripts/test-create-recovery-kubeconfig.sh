#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
TEST_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/mnema-recovery-kubeconfig.XXXXXX")
trap 'rm -rf "$TEST_ROOT"' EXIT HUP INT TERM
mkdir -p "$TEST_ROOT/bin" "$TEST_ROOT/out"

cat > "$TEST_ROOT/bin/kubectl" <<'FAKE'
#!/bin/sh
set -eu

case "$*" in
  *'get configmap mnema-restore-boundary -o jsonpath={.data.contractVersion}')
    printf '1'
    exit 0
    ;;
  *'get configmap mnema-restore-boundary -o jsonpath={.data.targetNamespace}')
    printf 'mnema-restore-drill'
    exit 0
    ;;
  *'get serviceaccount mnema-recovery') exit 0 ;;
  'config view --raw --minify -o jsonpath={.clusters[0].cluster.certificate-authority-data}')
    printf 'dGVzdC1jYQ=='
    exit 0
    ;;
  *'create token mnema-recovery --duration=720h -o jsonpath={.status.token}{"|"}{.status.expirationTimestamp}')
    printf 'test-token|2026-09-22T00:00:00Z'
    exit 0
    ;;
esac

if [ "${1:-}" = auth ] && [ "${2:-}" = can-i ]; then
  shift 2
  case "$*" in
    'get configmap/mnema-restore-boundary -n mnema-restore-drill' | \
    'create services -n mnema-restore-drill' | \
    'create statefulsets.apps -n mnema-restore-drill' | \
    'create jobs.batch -n mnema-restore-drill' | \
    'create secrets -n mnema-restore-drill' | \
    'delete persistentvolumeclaim/data-postgres-0 -n mnema-restore-drill')
      printf 'yes\n'
      ;;
    'get secrets -n mnema-restore-drill')
      if [ "${FAKE_ALLOW_SECRET_READ:-false}" = true ]; then printf 'yes\n'; else printf 'no\n'; fi
      ;;
    'list secrets -n mnema-restore-drill' | \
    'create networkpolicies.networking.k8s.io -n mnema-restore-drill' | \
    'delete configmap/mnema-restore-boundary -n mnema-restore-drill' | \
    'create namespaces' | \
    'delete namespaces' | \
    'get statefulsets.apps -n prod' | \
    'create jobs.batch -n prod' | \
    'get secrets -n prod')
      printf 'no\n'
      ;;
    *)
      printf 'unexpected auth check: %s\n' "$*" >&2
      exit 2
      ;;
  esac
  exit 0
fi

printf 'unexpected kubectl call: %s\n' "$*" >&2
exit 2
FAKE
chmod +x "$TEST_ROOT/bin/kubectl"

PATH="$TEST_ROOT/bin:$PATH" \
OUTPUT="$TEST_ROOT/out/recovery.kubeconfig" \
KUBE_API_SERVER=https://158.160.66.87:6443 \
  "$SCRIPT_DIR/create-recovery-kubeconfig.sh" > "$TEST_ROOT/result.txt"
grep -Fq 'token_expires_at=2026-09-22T00:00:00Z' "$TEST_ROOT/result.txt"
grep -Fq 'server: https://158.160.66.87:6443' "$TEST_ROOT/out/recovery.kubeconfig"
case "$(uname -s)" in
  Darwin) credential_mode=$(stat -f '%Lp' "$TEST_ROOT/out/recovery.kubeconfig") ;;
  *) credential_mode=$(stat -c '%a' "$TEST_ROOT/out/recovery.kubeconfig") ;;
esac
test "$credential_mode" = 600

if PATH="$TEST_ROOT/bin:$PATH" \
  FAKE_ALLOW_SECRET_READ=true \
  OUTPUT="$TEST_ROOT/out/overprivileged.kubeconfig" \
  KUBE_API_SERVER=https://158.160.66.87:6443 \
  "$SCRIPT_DIR/create-recovery-kubeconfig.sh" >/dev/null 2>&1; then
  echo 'Credential generation must reject Secret read permission' >&2
  exit 1
fi
test ! -e "$TEST_ROOT/out/overprivileged.kubeconfig"

printf 'recovery_kubeconfig_contract=ok\n'
