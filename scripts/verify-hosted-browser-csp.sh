#!/bin/sh
set -eu

TARGET_URL="${1:-}"
case "$TARGET_URL" in
  https://*/login) ;;
  *) echo "Usage: $0 https://<host>/login" >&2; exit 2 ;;
esac
TARGET_ORIGIN=${TARGET_URL%/login}

if [ -n "${CHROME_BIN:-}" ] && [ -x "$CHROME_BIN" ]; then
  chrome="$CHROME_BIN"
elif command -v google-chrome >/dev/null 2>&1; then
  chrome=$(command -v google-chrome)
elif command -v chromium >/dev/null 2>&1; then
  chrome=$(command -v chromium)
else
  echo "Chrome or Chromium is required for the hosted CSP browser smoke" >&2
  exit 1
fi

TEST_ROOT=$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/mnema-browser-csp.XXXXXX")
trap 'rm -rf "$TEST_ROOT"' EXIT HUP INT TERM

"$chrome" \
  --headless \
  --no-sandbox \
  --disable-gpu \
  --enable-logging=stderr \
  --virtual-time-budget=12000 \
  --dump-dom \
  "$TARGET_URL" > "$TEST_ROOT/dom.html" 2> "$TEST_ROOT/chrome.log"

if ! grep -Fq 'data-turnstile="true"' "$TEST_ROOT/dom.html"; then
  echo "Hosted login shell did not attempt to load the configured Turnstile client" >&2
  exit 1
fi
if ! grep -Eq '<iframe[^>]+challenges\.cloudflare\.com' "$TEST_ROOT/dom.html"; then
  echo "Hosted Turnstile client did not render its challenge frame" >&2
  exit 1
fi

grep -Ei \
  'Refused to .*Content Security Policy|violates the following Content Security Policy directive|Content Security Policy.*violation' \
  "$TEST_ROOT/chrome.log" | grep -F "source: $TARGET_ORIGIN" > "$TEST_ROOT/violations.log" || true
if [ -s "$TEST_ROOT/violations.log" ]; then
  echo "Hosted browser observed a CSP violation:" >&2
  head -n 20 "$TEST_ROOT/violations.log" >&2
  exit 1
fi

printf 'hosted_browser_csp=ok url=%s\n' "$TARGET_URL"
