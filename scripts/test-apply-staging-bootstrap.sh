#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
APPLY="$SCRIPT_DIR/apply-staging-bootstrap.sh"
TEST_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/mnema-staging-apply.XXXXXX")
trap 'rm -rf "$TEST_ROOT"' EXIT HUP INT TERM
mkdir "$TEST_ROOT/bin"

cat >"$TEST_ROOT/bin/kubectl" <<'EOF'
#!/bin/sh
set -eu
printf '%s\n' "$*" >>"$FAKE_KUBECTL_LOG"
if [ "$*" = 'get namespace mnema-staging' ]; then
  exit 0
fi
if [ "${1:-}" = diff ]; then
  case "$*" in
    *"${FAKE_DIFF_ERROR_FILE:-never-match}"*) exit 2 ;;
    *"${FAKE_DIFF_CHANGE_FILE:-never-match}"*) exit 1 ;;
    *) exit 0 ;;
  esac
fi
if [ "${1:-}" = apply ]; then
  exit 0
fi
exit 64
EOF
chmod +x "$TEST_ROOT/bin/kubectl" "$APPLY"

fake_log="$TEST_ROOT/kubectl.log"
PATH="$TEST_ROOT/bin:$PATH" FAKE_KUBECTL_LOG="$fake_log" \
  PHASE=boundary APPLY_CHANGES=false "$APPLY" >/dev/null
test "$(grep -c '^diff -f ' "$fake_log")" -eq 4
test "$(grep -c '^apply -f ' "$fake_log" || true)" -eq 0

: >"$fake_log"
if PATH="$TEST_ROOT/bin:$PATH" FAKE_KUBECTL_LOG="$fake_log" \
  FAKE_DIFF_ERROR_FILE=bootstrap.yaml \
  PHASE=boundary APPLY_CHANGES=true "$APPLY" >/dev/null 2>&1; then
  echo 'kubectl diff operational errors must fail the entire phase' >&2
  exit 1
fi
test "$(grep -c '^apply -f ' "$fake_log" || true)" -eq 0

: >"$fake_log"
PATH="$TEST_ROOT/bin:$PATH" FAKE_KUBECTL_LOG="$fake_log" \
  FAKE_DIFF_CHANGE_FILE=admission.yaml \
  PHASE=boundary APPLY_CHANGES=true "$APPLY" >/dev/null
test "$(grep -c '^diff -f ' "$fake_log")" -eq 4
test "$(grep -c '^apply -f ' "$fake_log")" -eq 4

: >"$fake_log"
PATH="$TEST_ROOT/bin:$PATH" FAKE_KUBECTL_LOG="$fake_log" \
  PHASE=namespace APPLY_CHANGES=true "$APPLY" >/dev/null
test "$(grep -c '^diff -f ' "$fake_log")" -eq 1
test "$(grep -c '^apply -f ' "$fake_log")" -eq 1

printf 'apply_staging_bootstrap=ok\n'
