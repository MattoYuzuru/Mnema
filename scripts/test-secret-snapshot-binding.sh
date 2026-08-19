#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
BIND="$SCRIPT_DIR/create-secret-snapshot-binding.py"
TEST_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/mnema-secret-binding.XXXXXX")
trap 'rm -rf "$TEST_ROOT"' EXIT HUP INT TERM

binding_key='independent-production-binding-key-0001'
first=$(SECRET_SNAPSHOT_BINDING_KEY="$binding_key" \
  AUTH_ISSUER=https://auth.mnema.app \
  CORE_INTERNAL_TOKEN=core-token-a \
  "$BIND" prod:aaaaaaaa AUTH_ISSUER CORE_INTERNAL_TOKEN)
same_reordered=$(SECRET_SNAPSHOT_BINDING_KEY="$binding_key" \
  AUTH_ISSUER=https://auth.mnema.app \
  CORE_INTERNAL_TOKEN=core-token-a \
  "$BIND" prod:aaaaaaaa CORE_INTERNAL_TOKEN AUTH_ISSUER)
changed=$(SECRET_SNAPSHOT_BINDING_KEY="$binding_key" \
  AUTH_ISSUER=https://auth.mnema.app \
  CORE_INTERNAL_TOKEN=core-token-b \
  "$BIND" prod:aaaaaaaa AUTH_ISSUER CORE_INTERNAL_TOKEN)
changed_context=$(SECRET_SNAPSHOT_BINDING_KEY="$binding_key" \
  AUTH_ISSUER=https://auth.mnema.app \
  CORE_INTERNAL_TOKEN=core-token-a \
  "$BIND" prod:bbbbbbbb AUTH_ISSUER CORE_INTERNAL_TOKEN)

test "$first" = "$same_reordered"
test "$first" != "$changed"
test "$first" != "$changed_context"
printf '%s\n' "$first" | grep -Eq '^[0-9a-f]{64}$'

if SECRET_SNAPSHOT_BINDING_KEY=short \
  AUTH_ISSUER=https://auth.mnema.app \
  CORE_INTERNAL_TOKEN=core-token-a \
  "$BIND" prod:aaaaaaaa AUTH_ISSUER CORE_INTERNAL_TOKEN \
  >"$TEST_ROOT/short.out" 2>"$TEST_ROOT/short.err"; then
  echo 'short binding keys must fail closed' >&2
  exit 1
fi
if SECRET_SNAPSHOT_BINDING_KEY="$binding_key" \
  AUTH_ISSUER=https://auth.mnema.app \
  "$BIND" prod:aaaaaaaa AUTH_ISSUER CORE_INTERNAL_TOKEN \
  >"$TEST_ROOT/missing.out" 2>"$TEST_ROOT/missing.err"; then
  echo 'missing desired values must fail closed' >&2
  exit 1
fi
if grep -ERq 'core-token|binding-key|auth\.mnema\.app' "$TEST_ROOT"; then
  echo 'binding failures must not expose Secret values' >&2
  exit 1
fi

printf 'secret_snapshot_binding=ok\n'
