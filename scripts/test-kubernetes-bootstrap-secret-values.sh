#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
VERIFY="$SCRIPT_DIR/verify-kubernetes-bootstrap-secret-values.py"
TEST_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/mnema-bootstrap-secret.XXXXXX")
trap 'rm -rf "$TEST_ROOT"' EXIT HUP INT TERM

mkdir "$TEST_ROOT/bin"
cat >"$TEST_ROOT/bin/kubectl" <<'EOF'
#!/bin/sh
set -eu
test "$*" = 'get secret mnema-secrets -n mnema-staging --ignore-not-found=true -o json'
case "${FAKE_SECRET_STATE:-missing}" in
  missing) exit 0 ;;
  empty) printf '%s\n' '{"type":"Opaque","data":{}}' ;;
  matching) printf '%s\n' '{"type":"Opaque","data":{"POSTGRES_USER":"bW5lbWE=","POSTGRES_PASSWORD":"c3RhZ2luZy1wYXNzd29yZA=="}}' ;;
  changed) printf '%s\n' '{"type":"Opaque","data":{"POSTGRES_USER":"bW5lbWE=","POSTGRES_PASSWORD":"b2xkLXBhc3N3b3Jk"}}' ;;
  partial) printf '%s\n' '{"type":"Opaque","data":{"POSTGRES_USER":"bW5lbWE="}}' ;;
  wrong-type) printf '%s\n' '{"type":"kubernetes.io/service-account-token","data":{"POSTGRES_USER":"bW5lbWE=","POSTGRES_PASSWORD":"c3RhZ2luZy1wYXNzd29yZA=="}}' ;;
  error) exit 1 ;;
  *) exit 64 ;;
esac
EOF
chmod +x "$TEST_ROOT/bin/kubectl" "$VERIFY"

run_verify() {
  PATH="$TEST_ROOT/bin:$PATH" \
    POSTGRES_USER=mnema \
    POSTGRES_PASSWORD=staging-password \
    FAKE_SECRET_STATE=$1 \
    "$VERIFY" mnema-staging mnema-secrets POSTGRES_USER POSTGRES_PASSWORD
}

test "$(run_verify missing)" = initial
test "$(run_verify empty)" = initial
test "$(run_verify matching)" = unchanged
for rejected_state in changed partial wrong-type error; do
  if run_verify "$rejected_state" >"$TEST_ROOT/$rejected_state.out" 2>"$TEST_ROOT/$rejected_state.err"; then
    echo "unsafe bootstrap Secret state was accepted: $rejected_state" >&2
    exit 1
  fi
done
if grep -Eq 'staging-password|old-password|c3RhZ2luZy1wYXNzd29yZA' \
  "$TEST_ROOT"/*.out "$TEST_ROOT"/*.err; then
  echo 'bootstrap Secret validation must not expose values' >&2
  exit 1
fi

printf 'kubernetes_bootstrap_secret_values=ok\n'
