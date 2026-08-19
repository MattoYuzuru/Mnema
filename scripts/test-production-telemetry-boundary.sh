#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
VERIFY="$SCRIPT_DIR/verify-production-telemetry-boundary.py"
TEST_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/mnema-production-telemetry.XXXXXX")
trap 'rm -rf "$TEST_ROOT"' EXIT HUP INT TERM
mkdir "$TEST_ROOT/bin"
cat >"$TEST_ROOT/bin/kubectl" <<'EOF'
#!/bin/sh
set -eu
test "$*" = 'get --raw /api/v1/namespaces/observability/services/http:prometheus:9090/proxy/api/v1/targets'
case "${FAKE_TARGETS:-healthy}" in
  healthy) printf '%s\n' '{"data":{"activeTargets":[{"labels":{"job":"node-exporter"},"health":"up"},{"labels":{"job":"kubelet"},"health":"up"},{"labels":{"job":"cadvisor"},"health":"up"}]}}' ;;
  down) printf '%s\n' '{"data":{"activeTargets":[{"labels":{"job":"node-exporter"},"health":"down"},{"labels":{"job":"kubelet"},"health":"up"},{"labels":{"job":"cadvisor"},"health":"up"}]}}' ;;
  missing) printf '%s\n' '{"data":{"activeTargets":[{"labels":{"job":"node-exporter"},"health":"up"}]}}' ;;
  invalid) printf '%s\n' '{' ;;
esac
EOF
chmod +x "$TEST_ROOT/bin/kubectl" "$VERIFY"
PATH="$TEST_ROOT/bin:$PATH" "$VERIFY" >/dev/null
for state in down missing invalid; do
  if PATH="$TEST_ROOT/bin:$PATH" FAKE_TARGETS=$state "$VERIFY" >/dev/null 2>&1; then
    echo "unhealthy production telemetry was accepted: $state" >&2
    exit 1
  fi
done
printf 'production_telemetry_boundary=ok\n'
