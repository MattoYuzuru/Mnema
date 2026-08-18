#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
CAPTURE="$SCRIPT_DIR/capture-release-diff.sh"
TEST_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/mnema-release-diff.XXXXXX")
trap 'rm -rf "$TEST_ROOT"' EXIT HUP INT TERM

mkdir "$TEST_ROOT/bin"
cat >"$TEST_ROOT/bin/kubectl" <<'EOF'
#!/bin/sh
set -eu

case "$1" in
  apply)
    exit "${FAKE_DRY_RUN_STATUS:-0}"
    ;;
  diff)
    if [ -n "${FAKE_DIFF_BODY:-}" ]; then
      printf '%s\n' "$FAKE_DIFF_BODY"
    fi
    exit "${FAKE_DIFF_STATUS:-0}"
    ;;
  *)
    exit 64
    ;;
esac
EOF
chmod +x "$TEST_ROOT/bin/kubectl"

manifest="$TEST_ROOT/release.yaml"
diff_file="$TEST_ROOT/release.diff"
printf '%s\n' 'apiVersion: v1' >"$manifest"

expected_no_change=$(printf 'No application release changes.\n' | sha256sum | awk '{print $1}')
actual_no_change=$(PATH="$TEST_ROOT/bin:$PATH" FAKE_DIFF_STATUS=0 \
  "$CAPTURE" "$manifest" "$diff_file")
test "$actual_no_change" = "$expected_no_change"
grep -Fxq 'No application release changes.' "$diff_file"

expected_change=$(printf '%s\n' 'sanitized-release-diff' | sha256sum | awk '{print $1}')
actual_change=$(PATH="$TEST_ROOT/bin:$PATH" FAKE_DIFF_STATUS=1 \
  FAKE_DIFF_BODY='sanitized-release-diff' \
  "$CAPTURE" "$manifest" "$diff_file")
test "$actual_change" = "$expected_change"
grep -Fxq 'sanitized-release-diff' "$diff_file"

PATH="$TEST_ROOT/bin:$PATH" FAKE_DIFF_STATUS=1 \
  FAKE_DIFF_BODY='sanitized-release-diff' \
  "$CAPTURE" "$manifest" "$diff_file" "$expected_change" >/dev/null

if PATH="$TEST_ROOT/bin:$PATH" FAKE_DIFF_STATUS=1 \
  FAKE_DIFF_BODY='unexpected-valid-release-diff' \
  "$CAPTURE" "$manifest" "$diff_file" "$expected_change" >/dev/null 2>&1; then
  echo 'a changed valid diff must fail before production mutation' >&2
  exit 1
fi

if PATH="$TEST_ROOT/bin:$PATH" FAKE_DIFF_STATUS=1 \
  FAKE_DIFF_BODY='sanitized-release-diff' \
  "$CAPTURE" "$manifest" "$diff_file" '' >/dev/null 2>&1; then
  echo 'a missing approved diff hash must fail before production mutation' >&2
  exit 1
fi

if PATH="$TEST_ROOT/bin:$PATH" FAKE_DIFF_STATUS=2 \
  "$CAPTURE" "$manifest" "$diff_file" >/dev/null 2>&1; then
  echo 'kubectl diff errors must fail the preview' >&2
  exit 1
fi

if PATH="$TEST_ROOT/bin:$PATH" FAKE_DRY_RUN_STATUS=1 \
  "$CAPTURE" "$manifest" "$diff_file" >/dev/null 2>&1; then
  echo 'server-side dry-run errors must fail the preview' >&2
  exit 1
fi

printf 'capture_release_diff=ok\n'
