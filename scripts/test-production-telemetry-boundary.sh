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
case "$*" in
  'get --raw /api/v1/namespaces/observability/services/http:prometheus:9090/proxy/api/v1/targets')
    case "${FAKE_TARGETS:-healthy}" in
      healthy | metrics-missing) printf '%s\n' '{"data":{"activeTargets":[{"labels":{"job":"node-exporter"},"health":"up","lastScrape":"2099-01-01T00:00:00Z"},{"labels":{"job":"kubelet"},"health":"up","lastScrape":"2099-01-01T00:00:00Z"},{"labels":{"job":"cadvisor"},"health":"up","lastScrape":"2099-01-01T00:00:00Z"}]}}' ;;
      down) printf '%s\n' '{"data":{"activeTargets":[{"labels":{"job":"node-exporter"},"health":"down","lastScrape":"2099-01-01T00:00:00Z"},{"labels":{"job":"kubelet"},"health":"up","lastScrape":"2099-01-01T00:00:00Z"},{"labels":{"job":"cadvisor"},"health":"up","lastScrape":"2099-01-01T00:00:00Z"}]}}' ;;
      missing) printf '%s\n' '{"data":{"activeTargets":[{"labels":{"job":"node-exporter"},"health":"up","lastScrape":"2099-01-01T00:00:00Z"}]}}' ;;
      stale) printf '%s\n' '{"data":{"activeTargets":[{"labels":{"job":"node-exporter"},"health":"up","lastScrape":"2020-01-01T00:00:00Z"},{"labels":{"job":"kubelet"},"health":"up","lastScrape":"2020-01-01T00:00:00Z"},{"labels":{"job":"cadvisor"},"health":"up","lastScrape":"2020-01-01T00:00:00Z"}]}}' ;;
      invalid) printf '%s\n' '{' ;;
    esac
    ;;
  'get --raw /apis/metrics.k8s.io/v1beta1/nodes')
    if [ "${FAKE_TARGETS:-healthy}" = metrics-missing ]; then
      printf '%s\n' '{"items":[]}'
    elif [ "${FAKE_TARGETS:-healthy}" = stale ]; then
      printf '%s\n' '{"items":[{"timestamp":"2020-01-01T00:00:00Z"}]}'
    else
      printf '%s\n' '{"items":[{"timestamp":"2099-01-01T00:00:00Z"}]}'
    fi
    ;;
  *) exit 64 ;;
esac
EOF
chmod +x "$TEST_ROOT/bin/kubectl" "$VERIFY"
PATH="$TEST_ROOT/bin:$PATH" "$VERIFY" --timeout-seconds 0 >/dev/null
for state in down missing invalid metrics-missing; do
  if PATH="$TEST_ROOT/bin:$PATH" FAKE_TARGETS=$state "$VERIFY" --timeout-seconds 0 >/dev/null 2>&1; then
    echo "unhealthy production telemetry was accepted: $state" >&2
    exit 1
  fi
done
if PATH="$TEST_ROOT/bin:$PATH" FAKE_TARGETS=stale "$VERIFY" \
  --not-before-epoch 2000000000 --timeout-seconds 0 >/dev/null 2>&1; then
  echo 'stale production telemetry was accepted' >&2
  exit 1
fi
printf 'production_telemetry_boundary=ok\n'
