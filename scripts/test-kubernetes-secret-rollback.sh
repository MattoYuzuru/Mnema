#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
PRESERVE="$SCRIPT_DIR/preserve-kubernetes-secret.py"
TEST_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/mnema-secret-rollback.XXXXXX")
trap 'rm -rf "$TEST_ROOT"' EXIT HUP INT TERM
mkdir "$TEST_ROOT/bin"

cat >"$TEST_ROOT/bin/kubectl" <<'EOF'
#!/bin/sh
set -eu
printf '%s\n' "$*" >>"$FAKE_KUBECTL_LOG"
case "$*" in
  '-n prod get secret mnema-secrets -o json')
    if [ "${FAKE_PHASE:-saved}" = saved ]; then
      printf '%s\n' '{"apiVersion":"v1","kind":"Secret","metadata":{"name":"mnema-secrets","namespace":"prod","uid":"secret-uid","resourceVersion":"11","labels":{"owner":"mnema"},"annotations":{"mnema.app/generation":"saved"}},"type":"Opaque","data":{"TOKEN":"b2xk","SMOKE_LOGIN":"b2xkLWxvZ2lu","SMOKE_TURNSTILE_BYPASS_KEY":"b2xkLWtleQ=="}}'
    else
      printf '{"apiVersion":"v1","kind":"Secret","metadata":{"name":"mnema-secrets","namespace":"prod","uid":"%s","resourceVersion":"12","labels":{"owner":"mnema"},"annotations":{"mnema.app/generation":"candidate"}},"type":"Opaque","data":{"TOKEN":"%s","SMOKE_LOGIN":"Y2FuZGlkYXRlLWxvZ2lu","SMOKE_TURNSTILE_BYPASS_KEY":"Y2FuZGlkYXRlLWtleQ=="}}\n' \
        "${FAKE_UID:-secret-uid}" "${FAKE_VALUE:-bmV3}"
    fi
    ;;
  'replace -f -')
    dd 2>/dev/null >"$FAKE_REPLACE_PAYLOAD"
    ;;
  *) exit 64 ;;
esac
EOF
chmod +x "$TEST_ROOT/bin/kubectl" "$PRESERVE"

snapshot="$TEST_ROOT/snapshot.json"
PATH="$TEST_ROOT/bin:$PATH" FAKE_KUBECTL_LOG="$TEST_ROOT/kubectl.log" \
  "$PRESERVE" snapshot prod mnema-secrets 11 "$snapshot" >"$TEST_ROOT/snapshot.out"
test "$(stat -f '%Lp' "$snapshot" 2>/dev/null || stat -c '%a' "$snapshot")" = 600
grep -Fxq 'resource_version=11' "$TEST_ROOT/snapshot.out"

replacement="$TEST_ROOT/replacement.json"
PATH="$TEST_ROOT/bin:$PATH" FAKE_KUBECTL_LOG="$TEST_ROOT/kubectl.log" \
  FAKE_PHASE=candidate FAKE_REPLACE_PAYLOAD="$replacement" \
  TOKEN=new SMOKE_LOGIN=candidate-login SMOKE_TURNSTILE_BYPASS_KEY=candidate-key \
  "$PRESERVE" restore prod mnema-secrets "$snapshot" \
  SMOKE_LOGIN,SMOKE_TURNSTILE_BYPASS_KEY \
  TOKEN SMOKE_LOGIN SMOKE_TURNSTILE_BYPASS_KEY >/dev/null
python3 - "$replacement" <<'PY'
import base64, json, sys
with open(sys.argv[1], encoding="utf-8") as source:
    value = json.load(source)
assert value["metadata"]["resourceVersion"] == "12"
assert value["metadata"]["labels"] == {"owner": "mnema"}
assert value["metadata"]["annotations"] == {"mnema.app/generation": "saved"}
assert base64.b64decode(value["data"]["TOKEN"]) == b"old"
assert base64.b64decode(value["data"]["SMOKE_LOGIN"]) == b"candidate-login"
assert base64.b64decode(value["data"]["SMOKE_TURNSTILE_BYPASS_KEY"]) == b"candidate-key"
PY

if PATH="$TEST_ROOT/bin:$PATH" FAKE_KUBECTL_LOG="$TEST_ROOT/kubectl.log" \
  FAKE_PHASE=candidate FAKE_VALUE=dW5leHBlY3RlZA== FAKE_REPLACE_PAYLOAD="$replacement" \
  TOKEN=new SMOKE_LOGIN=candidate-login SMOKE_TURNSTILE_BYPASS_KEY=candidate-key \
  "$PRESERVE" restore prod mnema-secrets "$snapshot" \
  SMOKE_LOGIN,SMOKE_TURNSTILE_BYPASS_KEY \
  TOKEN SMOKE_LOGIN SMOKE_TURNSTILE_BYPASS_KEY >/dev/null 2>&1; then
  echo 'Secret rollback must reject unexpected candidate data' >&2
  exit 1
fi
if PATH="$TEST_ROOT/bin:$PATH" FAKE_KUBECTL_LOG="$TEST_ROOT/kubectl.log" \
  FAKE_PHASE=candidate FAKE_UID=other-uid FAKE_REPLACE_PAYLOAD="$replacement" \
  TOKEN=new SMOKE_LOGIN=candidate-login SMOKE_TURNSTILE_BYPASS_KEY=candidate-key \
  "$PRESERVE" restore prod mnema-secrets "$snapshot" \
  SMOKE_LOGIN,SMOKE_TURNSTILE_BYPASS_KEY \
  TOKEN SMOKE_LOGIN SMOKE_TURNSTILE_BYPASS_KEY >/dev/null 2>&1; then
  echo 'Secret rollback must reject a replaced Secret identity' >&2
  exit 1
fi
if grep -ERq 'b2xk|bmV3|candidate|new|old' "$TEST_ROOT"/*.log "$TEST_ROOT"/*.out; then
  echo 'Secret rollback must not log Secret values' >&2
  exit 1
fi

printf 'kubernetes_secret_rollback=ok\n'
