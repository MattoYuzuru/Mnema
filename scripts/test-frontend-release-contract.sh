#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH='' cd -- "$SCRIPT_DIR/.." && pwd)
FRONTEND_DIST="${FRONTEND_DIST:-$REPO_ROOT/frontend/dist/mnema-frontend}"
INDEX_HTML="$FRONTEND_DIST/index.html"
TEST_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/mnema-frontend-release.XXXXXX")
trap 'rm -rf "$TEST_ROOT"' EXIT HUP INT TERM

if [ ! -f "$INDEX_HTML" ]; then
  echo "Missing production frontend build: $INDEX_HTML" >&2
  exit 1
fi

# The application builder emits name-HASH assets and folds runtime into its module
# graph. Check actual script/stylesheet/preload references, not a webpack filename.
python3 -m unittest discover -s "$SCRIPT_DIR/tests" -p test_frontend_release_assets.py
python3 -m unittest discover -s "$SCRIPT_DIR/tests" -p test_browser_identity_config.py
python3 "$SCRIPT_DIR/frontend_release_assets.py" "$FRONTEND_DIST"

nginx_config="$REPO_ROOT/frontend/nginx.conf"
index_location=$(awk '/location = \/index.html \{/{capture=1} capture{print} capture && /^  }$/{exit}' "$nginx_config")
app_config_location=$(awk '/location = \/app-config.js \{/{capture=1} capture{print} capture && /^  }$/{exit}' "$nginx_config")
asset_location=$(awk '/location ~\*/{capture=1} capture{print} capture && /^  }$/{exit}' "$nginx_config")

printf '%s\n' "$index_location" | grep -Fq 'must-revalidate'
if printf '%s\n' "$index_location" | grep -Fq 'immutable'; then
  echo "Frontend HTML must not be cached as immutable" >&2
  exit 1
fi
printf '%s\n' "$app_config_location" | grep -Fq 'Cache-Control "no-store"'
printf '%s\n' "$asset_location" | grep -Fq 'Cache-Control "public, immutable"'

release_sha=0123456789abcdef0123456789abcdef01234567
MNEMA_APP_CONFIG_OUT="$TEST_ROOT/app-config.js" \
MNEMA_AI_ROUTE_OUT="$TEST_ROOT/ai-route.inc" \
MNEMA_SECURITY_HEADERS_OUT="$TEST_ROOT/security-headers.inc" \
MNEMA_APP_ENV=development \
MNEMA_BUILD_ID="$release_sha" \
MNEMA_FEATURE_AI_ENABLED=false \
  "$REPO_ROOT/frontend/docker/40-gen-app-config.sh"

if ! grep -Fq "window.MNEMA_APP_CONFIG.buildId = \"$release_sha\";" "$TEST_ROOT/app-config.js"; then
  echo "Runtime frontend config did not expose the release build identity" >&2
  exit 1
fi

printf 'frontend_release_contract=ok\n'
