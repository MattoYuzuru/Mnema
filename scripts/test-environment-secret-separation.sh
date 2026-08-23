#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
VERIFY="$SCRIPT_DIR/verify-environment-secret-separation.py"
TEST_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/mnema-secret-separation.XXXXXX")
trap 'rm -rf "$TEST_ROOT"' EXIT HUP INT TERM

keys='AUTH_JWT_PUBLIC_KEY AUTH_JWT_PRIVATE_KEY TURNSTILE_SECRET_KEY GOOGLE_CLIENT_SECRET GITHUB_CLIENT_SECRET YANDEX_CLIENT_SECRET POSTGRES_PASSWORD AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_BUCKET_NAME MEDIA_INTERNAL_TOKEN CORE_INTERNAL_TOKEN USER_INTERNAL_TOKEN SMOKE_LOGIN SMOKE_TURNSTILE_BYPASS_KEY SMOKE_PASSWORD'
for key in $keys; do
  eval "STAGING_${key}=staging-${key}"
  eval "PROD_${key}=prod-${key}"
  export "STAGING_${key}" "PROD_${key}"
done

test "$("$VERIFY" --desired)" = environment_secret_separation=ok
PROD_CORE_INTERNAL_TOKEN=$STAGING_CORE_INTERNAL_TOKEN
export PROD_CORE_INTERNAL_TOKEN
if "$VERIFY" --desired >"$TEST_ROOT/duplicate.out" 2>"$TEST_ROOT/duplicate.err"; then
  echo 'reused environment credentials must fail separation' >&2
  exit 1
fi
grep -Fxq 'forbidden_duplicate=CORE_INTERNAL_TOKEN' "$TEST_ROOT/duplicate.err"
if grep -Fq 'staging-CORE_INTERNAL_TOKEN' "$TEST_ROOT/duplicate.err"; then
  echo 'separation diagnostics must not expose compared values' >&2
  exit 1
fi
PROD_CORE_INTERNAL_TOKEN=prod-CORE_INTERNAL_TOKEN
export PROD_CORE_INTERNAL_TOKEN

PROD_SMOKE_PASSWORD=$STAGING_SMOKE_PASSWORD
export PROD_SMOKE_PASSWORD
if "$VERIFY" --desired >"$TEST_ROOT/smoke-password.out" 2>"$TEST_ROOT/smoke-password.err"; then
  echo 'reused smoke account passwords must fail separation' >&2
  exit 1
fi
grep -Fxq 'forbidden_duplicate=SMOKE_PASSWORD' "$TEST_ROOT/smoke-password.err"
PROD_SMOKE_PASSWORD=prod-SMOKE_PASSWORD
export PROD_SMOKE_PASSWORD

mkdir "$TEST_ROOT/bin"
cat >"$TEST_ROOT/bin/kubectl" <<'EOF'
#!/usr/bin/env python3
import base64
import json
import sys

namespace = sys.argv[sys.argv.index("-n") + 1]
keys = "AUTH_JWT_PUBLIC_KEY AUTH_JWT_PRIVATE_KEY TURNSTILE_SECRET_KEY GOOGLE_CLIENT_SECRET GITHUB_CLIENT_SECRET YANDEX_CLIENT_SECRET POSTGRES_PASSWORD AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_BUCKET_NAME MEDIA_INTERNAL_TOKEN CORE_INTERNAL_TOKEN USER_INTERNAL_TOKEN SMOKE_LOGIN SMOKE_TURNSTILE_BYPASS_KEY".split()
prefix = "staging" if namespace == "mnema-staging" else "prod"
print(json.dumps({"data": {key: base64.b64encode(f"{prefix}-{key}".encode()).decode() for key in keys}}))
EOF
chmod +x "$TEST_ROOT/bin/kubectl" "$VERIFY"
test "$(PATH="$TEST_ROOT/bin:$PATH" "$VERIFY" --live)" = environment_secret_separation=ok

printf 'environment_secret_separation_contract=ok\n'
