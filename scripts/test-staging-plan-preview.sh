#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
PREVIEW="$SCRIPT_DIR/preview-staging-plan.sh"
TEST_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/mnema-staging-preview.XXXXXX")
trap 'rm -rf "$TEST_ROOT"' EXIT HUP INT TERM
mkdir "$TEST_ROOT/bin"
for manifest in secret data bucket release; do
  printf '%s\n' "kind: ${manifest}" >"$TEST_ROOT/$manifest.yaml"
done
cat >"$TEST_ROOT/bin/kubectl" <<'EOF'
#!/bin/sh
set -eu
printf '%s\n' "$*" >>"$FAKE_KUBECTL_LOG"
case "$*" in
  'apply --dry-run=server -f '*) exit "${FAKE_DRY_RUN_STATUS:-0}" ;;
  'diff --show-secrets=false -f '*) exit "${FAKE_SECRET_DIFF_STATUS:-1}" ;;
  'diff -f '*) exit "${FAKE_DIFF_STATUS:-1}" ;;
esac
exit 64
EOF
chmod +x "$TEST_ROOT/bin/kubectl" "$PREVIEW"

run_preview() {
  PATH="$TEST_ROOT/bin:$PATH" FAKE_KUBECTL_LOG="$TEST_ROOT/kubectl.log" "$PREVIEW" \
    "$TEST_ROOT/secret.yaml" "$TEST_ROOT/data.yaml" "$TEST_ROOT/bucket.yaml" "$TEST_ROOT/release.yaml"
}
run_preview >/dev/null
test "$(grep -c '^apply --dry-run=server' "$TEST_ROOT/kubectl.log")" -eq 4
test "$(grep -c '^diff --show-secrets=false' "$TEST_ROOT/kubectl.log")" -eq 1

for failure in dry-run secret-diff ordinary-diff; do
  : >"$TEST_ROOT/kubectl.log"
  case "$failure" in
    dry-run) setting=FAKE_DRY_RUN_STATUS=1 ;;
    secret-diff) setting=FAKE_SECRET_DIFF_STATUS=2 ;;
    ordinary-diff) setting=FAKE_DIFF_STATUS=2 ;;
  esac
  if env PATH="$TEST_ROOT/bin:$PATH" FAKE_KUBECTL_LOG="$TEST_ROOT/kubectl.log" \
    "$setting" "$PREVIEW" "$TEST_ROOT/secret.yaml" "$TEST_ROOT/data.yaml" \
    "$TEST_ROOT/bucket.yaml" "$TEST_ROOT/release.yaml" >/dev/null 2>&1; then
    echo "staging preview accepted $failure failure" >&2
    exit 1
  fi
  if grep -Eq '(^| )apply -f|(^| )delete|rollout restart' "$TEST_ROOT/kubectl.log"; then
    echo 'staging preview must never mutate the cluster' >&2
    exit 1
  fi
done
printf 'staging_plan_preview=ok\n'
