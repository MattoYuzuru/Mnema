#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
DETECT="$SCRIPT_DIR/detect-kubernetes-secret-drift.py"
TEST_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/mnema-secret-drift.XXXXXX")
trap 'rm -rf "$TEST_ROOT"' EXIT HUP INT TERM

mkdir "$TEST_ROOT/bin"
cat >"$TEST_ROOT/bin/kubectl" <<'EOF'
#!/bin/sh
set -eu

test "$*" = 'get secret mnema-secrets -n prod --ignore-not-found=true -o json'
case "${FAKE_SECRET_STATE:-missing}" in
  missing) exit 0 ;;
  matching)
    printf '%s\n' '{"type":"Opaque","data":{"AUTH_ISSUER":"aHR0cHM6Ly9hdXRoLm1uZW1hLmFwcA==","CORE_INTERNAL_TOKEN":"cHJvZC1jb3JlLXRva2Vu"}}'
    ;;
  changed)
    printf '%s\n' '{"type":"Opaque","data":{"AUTH_ISSUER":"aHR0cHM6Ly9hdXRoLm1uZW1hLmFwcA==","CORE_INTERNAL_TOKEN":"b2xkLWNvcmUtdG9rZW4="}}'
    ;;
  extra)
    printf '%s\n' '{"type":"Opaque","data":{"AUTH_ISSUER":"aHR0cHM6Ly9hdXRoLm1uZW1hLmFwcA==","CORE_INTERNAL_TOKEN":"cHJvZC1jb3JlLXRva2Vu","STALE":"c2VjcmV0"}}'
    ;;
  wrong-type)
    printf '%s\n' '{"type":"kubernetes.io/service-account-token","data":{"AUTH_ISSUER":"aHR0cHM6Ly9hdXRoLm1uZW1hLmFwcA==","CORE_INTERNAL_TOKEN":"cHJvZC1jb3JlLXRva2Vu"}}'
    ;;
  error) exit 1 ;;
  *) exit 64 ;;
esac
EOF
chmod +x "$TEST_ROOT/bin/kubectl" "$DETECT"

run_detect() {
  PATH="$TEST_ROOT/bin:$PATH" \
    AUTH_ISSUER=https://auth.mnema.app \
    CORE_INTERNAL_TOKEN=prod-core-token \
    FAKE_SECRET_STATE=$1 \
    "$DETECT" prod mnema-secrets AUTH_ISSUER CORE_INTERNAL_TOKEN
}

test "$(run_detect matching)" = false
for drift_state in missing changed extra wrong-type; do
  test "$(run_detect "$drift_state")" = true
done

if run_detect error >/dev/null 2>&1; then
  echo 'Kubernetes read errors must not be reported as ordinary drift' >&2
  exit 1
fi
if PATH="$TEST_ROOT/bin:$PATH" AUTH_ISSUER=https://auth.mnema.app \
  FAKE_SECRET_STATE=missing \
  "$DETECT" prod mnema-secrets AUTH_ISSUER CORE_INTERNAL_TOKEN \
  >"$TEST_ROOT/missing.out" 2>"$TEST_ROOT/missing.err"; then
  echo 'missing desired values must fail closed' >&2
  exit 1
fi

if run_detect changed >"$TEST_ROOT/output" 2>&1; then :; else exit 1; fi
if grep -Eq 'prod-core-token|old-core-token|[a-f0-9]{32,}' "$TEST_ROOT/output"; then
  echo 'Secret drift output must not expose values or value-derived hashes' >&2
  exit 1
fi

printf 'kubernetes_secret_drift=ok\n'
