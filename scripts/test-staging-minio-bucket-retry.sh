#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH='' cd -- "$SCRIPT_DIR/.." && pwd)
MANIFEST="$REPO_ROOT/k8s/staging/minio-bucket-job.yaml"
TEST_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/mnema-minio-retry.XXXXXX")
trap 'rm -rf "$TEST_ROOT"' EXIT HUP INT TERM

command_block=$(
  sed -n '/^            - |$/,/^          resources:$/p' "$MANIFEST" |
    sed '1d;$d;s/^              //'
)
if [ -z "$command_block" ]; then
  echo 'Unable to extract the MinIO bucket bootstrap command' >&2
  exit 1
fi

mkdir "$TEST_ROOT/bin"
cat >"$TEST_ROOT/bin/mc" <<'EOF'
#!/bin/sh
set -eu

case "${1:-} ${2:-}" in
  'alias set') operation=alias ;;
  'mb --ignore-existing') operation=mb ;;
  *) echo "Unexpected mc operation: $*" >&2; exit 64 ;;
esac

counter="$MINIO_FAKE_STATE/$operation"
count=0
if [ -f "$counter" ]; then
  count=$(cat "$counter")
fi
count=$((count + 1))
printf '%s\n' "$count" >"$counter"

case "$MINIO_FAKE_MODE:$operation:$count" in
  eventual:alias:1 | eventual:alias:2 | eventual:mb:1 | always:alias:*) exit 1 ;;
  *) exit 0 ;;
esac
EOF
cat >"$TEST_ROOT/bin/sleep" <<'EOF'
#!/bin/sh
exit 0
EOF
chmod 700 "$TEST_ROOT/bin/mc" "$TEST_ROOT/bin/sleep"

export AWS_ACCESS_KEY_ID=test-access
export AWS_SECRET_ACCESS_KEY=test-secret
export AWS_BUCKET_NAME=test-bucket
export MINIO_FAKE_STATE="$TEST_ROOT/state"
mkdir "$MINIO_FAKE_STATE"

MINIO_FAKE_MODE=eventual PATH="$TEST_ROOT/bin:$PATH" \
  /bin/sh -ec "$command_block" >"$TEST_ROOT/eventual.out" 2>&1
test "$(cat "$MINIO_FAKE_STATE/alias")" -eq 3
test "$(cat "$MINIO_FAKE_STATE/mb")" -eq 2

rm -f "$MINIO_FAKE_STATE/alias" "$MINIO_FAKE_STATE/mb"
if MINIO_FAKE_MODE=always PATH="$TEST_ROOT/bin:$PATH" \
  /bin/sh -ec "$command_block" >"$TEST_ROOT/always.out" 2>&1; then
  echo 'MinIO bootstrap retry must fail after the bounded attempt limit' >&2
  exit 1
fi
test "$(cat "$MINIO_FAKE_STATE/alias")" -eq 30
test ! -e "$MINIO_FAKE_STATE/mb"
grep -Fq 'MinIO bootstrap command did not become ready after 30 attempts' \
  "$TEST_ROOT/always.out"

printf 'staging_minio_bucket_retry=ok\n'
