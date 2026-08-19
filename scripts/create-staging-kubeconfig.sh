#!/bin/sh
set -eu

# Run with an existing cluster-admin kubectl context after applying
# k8s/staging/bootstrap.yaml. The generated credential is namespace-scoped and
# must be stored directly as the STAGING_KUBECONFIG_B64 environment secret.
OUTPUT="${OUTPUT:?OUTPUT must be an absolute path outside this repository}"
KUBE_API_SERVER="${KUBE_API_SERVER:?KUBE_API_SERVER must be the externally reachable Kubernetes API URL}"
TOKEN_DURATION="${TOKEN_DURATION:-720h}"
NAMESPACE=mnema-staging
SERVICE_ACCOUNT=mnema-deployer

SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH='' cd -- "$SCRIPT_DIR/.." && pwd)

case "$OUTPUT" in
  /*) ;;
  *)
    echo "OUTPUT must be an absolute path" >&2
    exit 1
    ;;
esac
case "$OUTPUT" in
  "$REPO_ROOT" | "$REPO_ROOT"/*)
    echo "Refusing to write a credential inside the repository" >&2
    exit 1
    ;;
esac
if [ -e "$OUTPUT" ]; then
  echo "Refusing to overwrite an existing credential: $OUTPUT" >&2
  exit 1
fi
case "$KUBE_API_SERVER" in
  https://127.0.0.1:* | https://localhost:* | https://\[::1\]:*)
    echo "KUBE_API_SERVER must be reachable from the GitHub-hosted runner, not loopback" >&2
    exit 1
    ;;
  https://*) ;;
  *)
    echo "KUBE_API_SERVER must use HTTPS" >&2
    exit 1
    ;;
esac
case "$KUBE_API_SERVER" in
  *[[:space:]]*)
    echo "KUBE_API_SERVER must not contain whitespace" >&2
    exit 1
    ;;
esac
case "$TOKEN_DURATION" in
  "" | *[!0-9smhd]*)
    echo "TOKEN_DURATION must use kubectl duration syntax, for example 720h" >&2
    exit 1
    ;;
esac

ca_data=$(kubectl config view --raw --minify -o jsonpath='{.clusters[0].cluster.certificate-authority-data}')
token_response=$(kubectl -n "$NAMESPACE" create token "$SERVICE_ACCOUNT" \
  --duration="$TOKEN_DURATION" \
  -o jsonpath='{.status.token}{"|"}{.status.expirationTimestamp}')
token=${token_response%%|*}
token_expires_at=${token_response#*|}

if [ "$token" = "$token_response" ] || [ -z "$ca_data" ] || [ -z "$token" ] || \
   [ -z "$token_expires_at" ]; then
  echo "Unable to resolve the cluster CA or bounded ServiceAccount token metadata" >&2
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
  - name: mnema-staging
    cluster:
      certificate-authority-data: ${ca_data}
      server: ${KUBE_API_SERVER}
contexts:
  - name: mnema-staging
    context:
      cluster: mnema-staging
      namespace: ${NAMESPACE}
      user: mnema-staging-deployer
current-context: mnema-staging
users:
  - name: mnema-staging-deployer
    user:
      token: ${token}
YAML

test "$(KUBECONFIG="$tmp_output" kubectl auth can-i create deployments.apps -n "$NAMESPACE")" = yes
test "$(KUBECONFIG="$tmp_output" kubectl auth can-i create secrets -n "$NAMESPACE")" = yes
test "$(KUBECONFIG="$tmp_output" kubectl auth can-i create ingresses.networking.k8s.io -n "$NAMESPACE")" = no
test "$(KUBECONFIG="$tmp_output" kubectl auth can-i get secrets -n prod)" = no
test "$(KUBECONFIG="$tmp_output" kubectl auth can-i create namespaces)" = no

mv "$tmp_output" "$OUTPUT"
trap - EXIT HUP INT TERM
printf 'staging_kubeconfig=%s\n' "$OUTPUT"
printf 'token_expires_at=%s\n' "$token_expires_at"
