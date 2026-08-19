#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
BIND="$SCRIPT_DIR/create-kubernetes-live-release-binding.py"
REPLACE="$SCRIPT_DIR/replace-kubernetes-secret-if-current.py"
DRIFT="$SCRIPT_DIR/detect-kubernetes-reconciliation-drift.py"
TEST_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/mnema-live-binding.XXXXXX")
trap 'rm -rf "$TEST_ROOT"' EXIT HUP INT TERM
mkdir "$TEST_ROOT/bin"

cat >"$TEST_ROOT/bin/kubectl" <<'EOF'
#!/bin/sh
set -eu
printf '%s\n' "$*" >>"$FAKE_KUBECTL_LOG"
case "$*" in
  '-n prod get secret mnema-secrets --ignore-not-found=true -o json'|'-n prod get secret mnema-secrets -o json')
    printf '{"metadata":{"name":"mnema-secrets","namespace":"prod","uid":"app-uid","resourceVersion":"%s","labels":{"owner":"mnema"}},"type":"Opaque","data":{"TOKEN":"%s"}}\n' \
      "${FAKE_APP_RV:-11}" "${FAKE_APP_VALUE:-YQ==}"
    ;;
  '-n observability get secret grafana-secrets --ignore-not-found=true -o json'|'-n observability get secret grafana-secrets -o json')
    if [ "${FAKE_MISSING_GRAFANA:-false}" = true ]; then exit 0; fi
    printf '%s\n' '{"metadata":{"name":"grafana-secrets","namespace":"observability","uid":"grafana-uid","resourceVersion":"22"},"type":"Opaque","data":{"ADMIN":"YQ=="}}'
    ;;
  '-n prod get configmap mnema-secret-reconciliation --ignore-not-found=true -o json')
    if [ "${FAKE_MISSING_MARKER:-false}" = true ]; then exit 0; fi
    printf '{"metadata":{"name":"mnema-secret-reconciliation","namespace":"prod","uid":"marker-uid","resourceVersion":"33"},"data":{"applicationGeneration":"%s","grafanaGeneration":"grafana-a"}}\n' "${FAKE_APP_GENERATION:-app-a}"
    ;;
  'replace -f -')
    payload=$(dd 2>/dev/null)
    printf '%s' "$payload" >"$FAKE_REPLACE_PAYLOAD"
    ;;
  *) exit 64 ;;
esac
EOF
chmod +x "$TEST_ROOT/bin/kubectl" "$BIND" "$REPLACE" "$DRIFT"

binding_key='independent-live-release-binding-key-01'
run_binding() {
  PATH="$TEST_ROOT/bin:$PATH" FAKE_KUBECTL_LOG="$TEST_ROOT/kubectl.log" \
    SECRET_SNAPSHOT_BINDING_KEY="$binding_key" "$BIND" production:release \
    prod/mnema-secrets observability/grafana-secrets prod/mnema-secret-reconciliation
}

first=$(run_binding)
same=$(run_binding)
changed=$(PATH="$TEST_ROOT/bin:$PATH" FAKE_KUBECTL_LOG="$TEST_ROOT/kubectl.log" \
  FAKE_APP_VALUE=Yg== SECRET_SNAPSHOT_BINDING_KEY="$binding_key" "$BIND" production:release \
  prod/mnema-secrets observability/grafana-secrets prod/mnema-secret-reconciliation)
changed_rv=$(PATH="$TEST_ROOT/bin:$PATH" FAKE_KUBECTL_LOG="$TEST_ROOT/kubectl.log" \
  FAKE_APP_RV=12 SECRET_SNAPSHOT_BINDING_KEY="$binding_key" "$BIND" production:release \
  prod/mnema-secrets observability/grafana-secrets prod/mnema-secret-reconciliation)
missing_marker=$(PATH="$TEST_ROOT/bin:$PATH" FAKE_KUBECTL_LOG="$TEST_ROOT/kubectl.log" \
  FAKE_MISSING_MARKER=true SECRET_SNAPSHOT_BINDING_KEY="$binding_key" "$BIND" production:release \
  prod/mnema-secrets observability/grafana-secrets prod/mnema-secret-reconciliation)

test "$first" = "$same"
test "$first" != "$changed"
test "$first" != "$changed_rv"
test "$first" != "$missing_marker"
python3 -c 'import json,sys; v=json.loads(sys.argv[1]); assert len(v["hmac"]) == 64; assert v["resourceVersions"]["prod/mnema-secrets"] == "11"' "$first"
if PATH="$TEST_ROOT/bin:$PATH" FAKE_KUBECTL_LOG="$TEST_ROOT/kubectl.log" \
  FAKE_MISSING_GRAFANA=true SECRET_SNAPSHOT_BINDING_KEY="$binding_key" "$BIND" production:release \
  prod/mnema-secrets observability/grafana-secrets prod/mnema-secret-reconciliation >/dev/null 2>&1; then
  echo 'missing production Secret must fail the live binding' >&2
  exit 1
fi

test "$(PATH="$TEST_ROOT/bin:$PATH" FAKE_KUBECTL_LOG="$TEST_ROOT/kubectl.log" \
  "$DRIFT" prod mnema-secret-reconciliation applicationGeneration app-a)" = false
test "$(PATH="$TEST_ROOT/bin:$PATH" FAKE_KUBECTL_LOG="$TEST_ROOT/kubectl.log" \
  FAKE_APP_GENERATION=app-b "$DRIFT" prod mnema-secret-reconciliation applicationGeneration app-a)" = true
test "$(PATH="$TEST_ROOT/bin:$PATH" FAKE_KUBECTL_LOG="$TEST_ROOT/kubectl.log" \
  FAKE_MISSING_MARKER=true "$DRIFT" prod mnema-secret-reconciliation applicationGeneration app-a)" = true

replace_payload="$TEST_ROOT/replacement.json"
PATH="$TEST_ROOT/bin:$PATH" FAKE_KUBECTL_LOG="$TEST_ROOT/kubectl.log" \
  FAKE_REPLACE_PAYLOAD="$replace_payload" TOKEN=new-value \
  "$REPLACE" prod mnema-secrets 11 TOKEN >/dev/null
python3 - "$replace_payload" <<'PY'
import base64, json, sys
with open(sys.argv[1], encoding="utf-8") as source:
    value = json.load(source)
assert value["metadata"]["resourceVersion"] == "11"
assert value["metadata"]["labels"] == {"owner": "mnema"}
assert base64.b64decode(value["data"]["TOKEN"]) == b"new-value"
PY
if PATH="$TEST_ROOT/bin:$PATH" FAKE_KUBECTL_LOG="$TEST_ROOT/kubectl.log" \
  FAKE_REPLACE_PAYLOAD="$replace_payload" TOKEN=new-value \
  "$REPLACE" prod mnema-secrets 10 TOKEN >/dev/null 2>&1; then
  echo 'stale Secret resourceVersion must fail before replace' >&2
  exit 1
fi

if grep -ERq 'new-value|independent-live-release-binding-key' "$TEST_ROOT"/*.log; then
  echo 'live release binding must not log values or binding keys' >&2
  exit 1
fi
printf 'kubernetes_live_release_binding=ok\n'
