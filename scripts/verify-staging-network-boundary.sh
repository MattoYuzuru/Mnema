#!/bin/sh
set -eu

KUBE_API_SERVER=${KUBE_API_SERVER:?KUBE_API_SERVER is required}
NAMESPACE=mnema-staging
CLIENT_POD=mnema-staging-network-probe-client
SERVER_POD=mnema-staging-network-probe-server
REDIS_IMAGE='redis:7-alpine@sha256:e7723ff73d963f5cc6d9c4643ea3d989527a402a319239054e9472a7fb9219a2'
client_created=false
server_created=false

api_endpoint=$(python3 - "$KUBE_API_SERVER" <<'PY'
import sys
from urllib.parse import urlsplit

endpoint = urlsplit(sys.argv[1])
if endpoint.scheme != "https" or not endpoint.hostname:
    raise SystemExit(1)
print(f"{endpoint.hostname}|{endpoint.port or 443}")
PY
)
api_host=${api_endpoint%%|*}
api_port=${api_endpoint#*|}

cleanup() {
  if [ "$client_created" = true ]; then
    kubectl -n "$NAMESPACE" delete pod "$CLIENT_POD" --wait=true >/dev/null 2>&1 || true
  fi
  if [ "$server_created" = true ]; then
    kubectl -n "$NAMESPACE" delete pod "$SERVER_POD" --wait=true >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT HUP INT TERM

for pod_name in "$CLIENT_POD" "$SERVER_POD"; do
  if kubectl -n "$NAMESPACE" get pod "$pod_name" >/dev/null 2>&1; then
    echo "Refusing to replace an existing network probe Pod: $pod_name" >&2
    exit 1
  fi
done

kubectl -n "$NAMESPACE" get networkpolicy mnema-staging-default-deny >/dev/null
kubectl -n "$NAMESPACE" get networkpolicy mnema-staging-allowed-traffic >/dev/null

cat <<YAML | kubectl create -f - >/dev/null
apiVersion: v1
kind: Pod
metadata:
  name: ${SERVER_POD}
  namespace: ${NAMESPACE}
  labels: {app: mnema-staging-network-probe-server}
spec:
  automountServiceAccountToken: false
  restartPolicy: Never
  containers:
    - name: redis
      image: ${REDIS_IMAGE}
      args: ["redis-server", "--save", "", "--appendonly", "no"]
      ports: [{name: redis, containerPort: 6379}]
YAML
server_created=true

cat <<YAML | kubectl create -f - >/dev/null
apiVersion: v1
kind: Pod
metadata:
  name: ${CLIENT_POD}
  namespace: ${NAMESPACE}
  labels: {app: mnema-staging-network-probe-client}
spec:
  automountServiceAccountToken: false
  restartPolicy: Never
  containers:
    - name: client
      image: ${REDIS_IMAGE}
      command: ["sh", "-c", "sleep 600"]
YAML
client_created=true

kubectl -n "$NAMESPACE" wait --for=condition=Ready "pod/$SERVER_POD" --timeout=180s >/dev/null
kubectl -n "$NAMESPACE" wait --for=condition=Ready "pod/$CLIENT_POD" --timeout=180s >/dev/null
server_ip=$(kubectl -n "$NAMESPACE" get pod "$SERVER_POD" -o jsonpath='{.status.podIP}')
if [ -z "$server_ip" ]; then
  echo "The same-namespace network probe has no Pod IP" >&2
  exit 1
fi
injected_ephemeral_storage=$(kubectl -n "$NAMESPACE" get pod "$CLIENT_POD" \
  -o jsonpath='{.spec.containers[0].resources.requests.ephemeral-storage}{"|"}{.spec.containers[0].resources.limits.ephemeral-storage}')
if [ "$injected_ephemeral_storage" != '256Mi|2Gi' ]; then
  echo "Staging ephemeral-storage defaults were not injected into an admitted Pod" >&2
  exit 1
fi

kubectl -n "$NAMESPACE" exec "$CLIENT_POD" -- \
  redis-cli -h "$server_ip" -p 6379 ping | grep -Fxq PONG
kubectl -n "$NAMESPACE" exec "$CLIENT_POD" -- \
  getent hosts kubernetes.default.svc.cluster.local >/dev/null
kubectl -n "$NAMESPACE" exec "$CLIENT_POD" -- \
  getent hosts redis.prod.svc.cluster.local >/dev/null
kubectl -n "$NAMESPACE" exec "$CLIENT_POD" -- \
  nc -z -w 5 github.com 443

assert_unreachable() {
  target=$1
  port=$2
  label=$3
  if kubectl -n "$NAMESPACE" exec "$CLIENT_POD" -- \
    nc -z -w 3 "$target" "$port"; then
    echo "Staging can reach forbidden $label at $target:$port" >&2
    exit 1
  fi
}

assert_unreachable kubernetes.default.svc.cluster.local 443 \
  'cross-namespace Kubernetes Service'
assert_unreachable redis.prod.svc.cluster.local 6379 'production Redis Service'
assert_unreachable 169.254.169.254 80 'link-local metadata endpoint'
assert_unreachable "$api_host" "$api_port" 'externally advertised Kubernetes API'

node_addresses=$(kubectl get nodes -o json | python3 -c '
import json
import sys

nodes = json.load(sys.stdin)
addresses = {
    address.get("address", "")
    for node in nodes.get("items", [])
    for address in node.get("status", {}).get("addresses", [])
    if address.get("type") in {"InternalIP", "ExternalIP"}
}
for address in sorted(value for value in addresses if value):
    print(address)
')
if [ -z "$node_addresses" ]; then
  echo "Unable to discover resident node InternalIP/ExternalIP addresses" >&2
  exit 1
fi
for node_address in $node_addresses; do
  for sensitive_port in 2379 2380 6443 10250; do
    assert_unreachable "$node_address" "$sensitive_port" 'resident node listener'
  done
done

printf 'staging_network_boundary=ok\n'
