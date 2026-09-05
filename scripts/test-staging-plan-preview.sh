#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
PREVIEW="$SCRIPT_DIR/preview-staging-plan.sh"
TEST_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/mnema-staging-preview.XXXXXX")
trap 'rm -rf "$TEST_ROOT"' EXIT HUP INT TERM
mkdir "$TEST_ROOT/bin"
for manifest in secret data release; do
  printf '%s\n' "kind: ${manifest}" >"$TEST_ROOT/$manifest.yaml"
done
cat >"$TEST_ROOT/bucket.yaml" <<'EOF'
apiVersion: batch/v1
kind: Job
metadata:
  name: minio-bucket-bootstrap
EOF
cat >"$TEST_ROOT/bin/kubectl" <<'EOF'
#!/bin/sh
set -eu
printf '%s\n' "$*" >>"$FAKE_KUBECTL_LOG"
case "$*" in
  '-n mnema-staging apply --dry-run=server -f '*) exit "${FAKE_DRY_RUN_STATUS:-0}" ;;
  '-n mnema-staging diff -f '*) exit "${FAKE_DIFF_STATUS:-1}" ;;
  'apply --dry-run=server -f '*) exit "${FAKE_DRY_RUN_STATUS:-0}" ;;
  'create --dry-run=server -f '*)
    grep -Eq '^  name: minio-bucket-bootstrap-preview-[0-9]+$' "$4" || exit 65
    exit "${FAKE_JOB_CREATE_STATUS:-0}"
    ;;
  'diff --show-secrets=false -f '*) exit "${FAKE_SECRET_DIFF_STATUS:-1}" ;;
  'diff -f '*)
    case "$3" in
      *mnema-staging-job-preview.*)
        grep -Eq '^  name: minio-bucket-bootstrap-preview-[0-9]+$' "$3" || exit 65
        exit "${FAKE_JOB_DIFF_STATUS:-1}"
        ;;
      *) exit "${FAKE_DIFF_STATUS:-1}" ;;
    esac
    ;;
esac
exit 64
EOF
chmod +x "$TEST_ROOT/bin/kubectl" "$PREVIEW"

run_preview() {
  PATH="$TEST_ROOT/bin:$PATH" FAKE_KUBECTL_LOG="$TEST_ROOT/kubectl.log" "$PREVIEW" \
    "$TEST_ROOT/secret.yaml" "$TEST_ROOT/data.yaml" "$TEST_ROOT/bucket.yaml" "$TEST_ROOT/release.yaml"
}
run_preview >/dev/null
test "$(grep -c '^apply --dry-run=server' "$TEST_ROOT/kubectl.log")" -eq 3
for manifest in secret data release; do
  grep -Fqx "apply --dry-run=server -f $TEST_ROOT/$manifest.yaml" "$TEST_ROOT/kubectl.log"
done
if grep -Fqx "apply --dry-run=server -f $TEST_ROOT/bucket.yaml" "$TEST_ROOT/kubectl.log"; then
  echo 'The replace-on-deploy Job must not be previewed as an update' >&2
  exit 1
fi
test "$(grep -c '^create --dry-run=server -f .*/mnema-staging-job-preview\.' "$TEST_ROOT/kubectl.log")" -eq 1
test "$(grep -c '^diff --show-secrets=false' "$TEST_ROOT/kubectl.log")" -eq 1
test "$(grep -c '^diff -f .*/mnema-staging-job-preview\.' "$TEST_ROOT/kubectl.log")" -eq 1
grep -Fqx '  name: minio-bucket-bootstrap' "$TEST_ROOT/bucket.yaml"

for failure in dry-run job-create secret-diff job-diff ordinary-diff; do
  : >"$TEST_ROOT/kubectl.log"
  case "$failure" in
    dry-run) setting=FAKE_DRY_RUN_STATUS=1 ;;
    job-create) setting=FAKE_JOB_CREATE_STATUS=1 ;;
    secret-diff) setting=FAKE_SECRET_DIFF_STATUS=2 ;;
    job-diff) setting=FAKE_JOB_DIFF_STATUS=2 ;;
    ordinary-diff) setting=FAKE_DIFF_STATUS=2 ;;
  esac
  if env PATH="$TEST_ROOT/bin:$PATH" FAKE_KUBECTL_LOG="$TEST_ROOT/kubectl.log" \
    "$setting" "$PREVIEW" "$TEST_ROOT/secret.yaml" "$TEST_ROOT/data.yaml" \
    "$TEST_ROOT/bucket.yaml" "$TEST_ROOT/release.yaml" >/dev/null 2>&1; then
    echo "staging preview accepted $failure failure" >&2
    exit 1
  fi
  if grep -Eq '(^| )apply -f|(^| )create -f|(^| )delete|rollout restart' "$TEST_ROOT/kubectl.log"; then
    echo 'staging preview must never mutate the cluster' >&2
    exit 1
  fi
done

# Exercise the actual replacement branch with its exact resource inventory.
# This mock accepts only flags supported by the workflow's kubectl 1.35.
PYTHONPATH="$SCRIPT_DIR/smoke/tests" python3 - "$TEST_ROOT" <<'PY'
import sys
from pathlib import Path
from test_maintenance_release_state import manifest
root = Path(sys.argv[1])
(root / "source.yaml").write_text(manifest(False))
(root / "target.yaml").write_text(manifest(True))
(root / "forbidden.yaml").write_text(manifest(True) + '''\n---
apiVersion: v1
kind: Secret
metadata:
  name: forbidden
  namespace: mnema-staging
''')
PY
run_replacement() {
  PATH="$TEST_ROOT/bin:$PATH" FAKE_KUBECTL_LOG="$TEST_ROOT/kubectl.log" "$PREVIEW" \
    --replacement "$1" "$TEST_ROOT/source.yaml" "$TEST_ROOT/transition.json"
}
: >"$TEST_ROOT/kubectl.log"
run_replacement "$TEST_ROOT/target.yaml" >/dev/null
test "$(wc -l <"$TEST_ROOT/kubectl.log" | tr -d ' ')" -eq 2
grep -Fqx -- "-n mnema-staging apply --dry-run=server -f $TEST_ROOT/target.yaml" "$TEST_ROOT/kubectl.log"
grep -Fqx -- "-n mnema-staging diff -f $TEST_ROOT/target.yaml" "$TEST_ROOT/kubectl.log"
for failure in dry-run diff; do
  case "$failure" in
    dry-run) setting=FAKE_DRY_RUN_STATUS=1 ;;
    diff) setting=FAKE_DIFF_STATUS=2 ;;
  esac
  if env PATH="$TEST_ROOT/bin:$PATH" FAKE_KUBECTL_LOG="$TEST_ROOT/kubectl.log" "$setting" \
    "$PREVIEW" --replacement "$TEST_ROOT/target.yaml" "$TEST_ROOT/source.yaml" \
    "$TEST_ROOT/transition.json" >/dev/null 2>&1; then
    echo "replacement preview accepted $failure failure" >&2
    exit 1
  fi
done
: >"$TEST_ROOT/kubectl.log"
if run_replacement "$TEST_ROOT/forbidden.yaml" >/dev/null 2>&1; then
  echo 'replacement preview accepted a Secret resource' >&2
  exit 1
fi
test ! -s "$TEST_ROOT/kubectl.log"
printf 'staging_plan_preview=ok\n'
