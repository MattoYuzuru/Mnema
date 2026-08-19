#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
VERIFY="$SCRIPT_DIR/verify-staging-tls-boundary.sh"
TEST_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/mnema-staging-tls.XXXXXX")
trap 'rm -rf "$TEST_ROOT"' EXIT HUP INT TERM
mkdir "$TEST_ROOT/bin"

cat >"$TEST_ROOT/bin/kubectl" <<'EOF'
#!/bin/sh
set -eu
if [ "$*" = 'get clusterissuer letsencrypt-prod -o json' ]; then
  service_type=${FAKE_SOLVER_SERVICE_TYPE:-ClusterIP}
  printf '{"spec":{"acme":{"solvers":[{"selector":{"dnsZones":["staging.mnema.app"]},"http01":{"ingress":{"ingressClassName":"traefik","serviceType":"%s"}}}]}},"status":{"conditions":[{"type":"Ready","status":"True"}]}}\n' "$service_type"
  exit 0
fi
if [ "$*" = '-n mnema-staging get resourcequota mnema-staging-quota -o jsonpath={.spec.hard.count\/secrets}' ]; then
  printf '%s' "${FAKE_SECRET_QUOTA:-12}"
  exit 0
fi
case "$*" in
  '-n mnema-staging wait --for=create certificate/'*' --timeout=120s') exit 0 ;;
  '-n mnema-staging wait --for=condition=Ready=True certificate/'*' --timeout=300s')
    if [ "${FAKE_CERTIFICATE_NOT_READY:-false}" = true ]; then exit 1; fi
    exit 0
    ;;
  '-n mnema-staging get certificate '*'-tls -o json')
    printf '%s\n' '{"spec":{"issuerRef":{"kind":"ClusterIssuer","name":"letsencrypt-prod"}},"status":{"renewalTime":"2026-10-01T00:00:00Z"}}'
    exit 0
    ;;
  '-n mnema-staging get secret '*'-tls -o json')
    printf '%s\n' '{"type":"kubernetes.io/tls","data":{"tls.crt":"Y3J0","tls.key":"a2V5"}}'
    exit 0
    ;;
esac
exit 64
EOF
chmod +x "$TEST_ROOT/bin/kubectl" "$VERIFY"

PATH="$TEST_ROOT/bin:$PATH" "$VERIFY" >/dev/null
for failure in solver quota certificate; do
  case "$failure" in
    solver) failure_env='FAKE_SOLVER_SERVICE_TYPE=NodePort' ;;
    quota) failure_env='FAKE_SECRET_QUOTA=2' ;;
    certificate) failure_env='FAKE_CERTIFICATE_NOT_READY=true' ;;
  esac
  if env PATH="$TEST_ROOT/bin:$PATH" "$failure_env" "$VERIFY" >/dev/null 2>&1; then
    echo "invalid staging TLS boundary was accepted: $failure" >&2
    exit 1
  fi
done

printf 'staging_tls_boundary=ok\n'
