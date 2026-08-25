#!/bin/sh
set -eu

OUTPUT="${OUTPUT:?OUTPUT must be an absolute path outside this repository}"
KUBE_API_SERVER="${KUBE_API_SERVER:?KUBE_API_SERVER must be the externally reachable Kubernetes API URL}"
TOKEN_DURATION="${TOKEN_DURATION:-720h}"
NAMESPACE=mnema-restore-drill
SERVICE_ACCOUNT=mnema-recovery

SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH='' cd -- "$SCRIPT_DIR/.." && pwd)

case "$OUTPUT" in
  /*) ;;
  *) echo 'OUTPUT must be an absolute path' >&2; exit 1 ;;
esac
case "$OUTPUT" in
  "$REPO_ROOT" | "$REPO_ROOT"/*)
    echo 'Refusing to write a credential inside the repository' >&2
    exit 1
    ;;
esac
if [ -e "$OUTPUT" ]; then
  echo "Refusing to overwrite an existing credential: $OUTPUT" >&2
  exit 1
fi
if ! python3 - "$KUBE_API_SERVER" <<'PY'
import ipaddress
import sys
from urllib.parse import urlsplit

try:
    endpoint = urlsplit(sys.argv[1])
    port = endpoint.port
except ValueError:
    raise SystemExit(1)
if (
    endpoint.scheme != "https"
    or not endpoint.hostname
    or endpoint.username is not None
    or endpoint.password is not None
    or endpoint.path not in ("", "/")
    or endpoint.query
    or endpoint.fragment
):
    raise SystemExit(1)
hostname = endpoint.hostname.rstrip(".").lower()
if hostname == "localhost" or hostname.endswith(".localhost"):
    raise SystemExit(1)
try:
    address = ipaddress.ip_address(hostname)
except ValueError:
    address = None
if address is not None:
    mapped = getattr(address, "ipv4_mapped", None)
    if address.is_loopback or (mapped is not None and mapped.is_loopback):
        raise SystemExit(1)
if port is not None and not 1 <= port <= 65535:
    raise SystemExit(1)
PY
then
  echo 'KUBE_API_SERVER must be a non-loopback HTTPS origin' >&2
  exit 1
fi
case "$TOKEN_DURATION" in
  "" | *[!0-9smhd]*) echo 'TOKEN_DURATION must use kubectl duration syntax' >&2; exit 1 ;;
esac

test "$(kubectl -n "$NAMESPACE" get configmap mnema-restore-boundary -o jsonpath='{.data.contractVersion}')" = 1
test "$(kubectl -n "$NAMESPACE" get configmap mnema-restore-boundary -o jsonpath='{.data.targetNamespace}')" = "$NAMESPACE"
kubectl -n "$NAMESPACE" get serviceaccount "$SERVICE_ACCOUNT" >/dev/null

ca_data=$(kubectl config view --raw --minify -o jsonpath='{.clusters[0].cluster.certificate-authority-data}')
token_response=$(kubectl -n "$NAMESPACE" create token "$SERVICE_ACCOUNT" \
  --duration="$TOKEN_DURATION" \
  -o jsonpath='{.status.token}{"|"}{.status.expirationTimestamp}')
token=${token_response%%|*}
token_expires_at=${token_response#*|}
if [ "$token" = "$token_response" ] || [ -z "$ca_data" ] || [ -z "$token" ] || [ -z "$token_expires_at" ]; then
  echo 'Unable to resolve the cluster CA or bounded recovery token' >&2
  exit 1
fi

umask 077
mkdir -p "$(dirname -- "$OUTPUT")"
tmp_output=$(mktemp "${OUTPUT}.tmp.XXXXXX")
trap 'rm -f "$tmp_output"' EXIT HUP INT TERM
cat > "$tmp_output" <<YAML
apiVersion: v1
kind: Config
clusters:
  - name: mnema-recovery
    cluster:
      certificate-authority-data: ${ca_data}
      server: ${KUBE_API_SERVER}
contexts:
  - name: mnema-recovery
    context:
      cluster: mnema-recovery
      namespace: ${NAMESPACE}
      user: mnema-recovery
current-context: mnema-recovery
users:
  - name: mnema-recovery
    user:
      token: ${token}
YAML

can_i() {
  KUBECONFIG="$tmp_output" kubectl auth can-i "$@"
}
test "$(can_i get configmap/mnema-restore-boundary -n "$NAMESPACE")" = yes
test "$(can_i create services -n "$NAMESPACE")" = yes
test "$(can_i create statefulsets.apps -n "$NAMESPACE")" = yes
test "$(can_i create jobs.batch -n "$NAMESPACE")" = yes
test "$(can_i create secrets -n "$NAMESPACE")" = yes
test "$(can_i create networkpolicies.networking.k8s.io -n "$NAMESPACE")" = no
test "$(can_i delete persistentvolumeclaim/data-postgres-0 -n "$NAMESPACE")" = no
test "$(can_i get secret/mnema-restore-db-secrets -n "$NAMESPACE")" = yes
test "$(can_i get secrets -n "$NAMESPACE")" = no
test "$(can_i list secrets -n "$NAMESPACE")" = no
test "$(can_i delete configmap/mnema-restore-boundary -n "$NAMESPACE")" = no
test "$(can_i create namespaces)" = no
test "$(can_i delete namespaces)" = no
test "$(can_i get statefulsets.apps -n prod)" = no
test "$(can_i create jobs.batch -n prod)" = no
test "$(can_i get secrets -n prod)" = no

mv "$tmp_output" "$OUTPUT"
trap - EXIT HUP INT TERM
printf 'recovery_kubeconfig=%s\n' "$OUTPUT"
printf 'token_expires_at=%s\n' "$token_expires_at"
