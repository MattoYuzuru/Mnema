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
if hostname == "localhost" or hostname.endswith(".localhost") or hostname == "127" or hostname.startswith("127."):
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
  echo "KUBE_API_SERVER must be a non-loopback HTTPS origin reachable from the GitHub-hosted runner" >&2
  exit 1
fi
case "$TOKEN_DURATION" in
  "" | *[!0-9smhd]*)
    echo "TOKEN_DURATION must use kubectl duration syntax, for example 720h" >&2
    exit 1
    ;;
esac

server_git_version=$(kubectl version -o json | python3 -c '
import json
import sys
value = json.load(sys.stdin).get("serverVersion", {}).get("gitVersion", "")
if not value:
    raise SystemExit(1)
print(value)
') || {
  echo "Unable to resolve the Kubernetes server version" >&2
  exit 1
}
pod_security_version=$(printf '%s\n' "$server_git_version" | \
  sed -E 's/^(v[0-9]+\.[0-9]+).*/\1/')
case "$pod_security_version" in
  v[0-9]*.[0-9]*) ;;
  *)
    echo "Unable to derive a Pod Security policy version from $server_git_version" >&2
    exit 1
    ;;
esac

kubectl label namespace "$NAMESPACE" --overwrite \
  "pod-security.kubernetes.io/enforce-version=$pod_security_version" \
  "pod-security.kubernetes.io/audit-version=$pod_security_version" \
  "pod-security.kubernetes.io/warn-version=$pod_security_version" >/dev/null

pod_security_labels=$(kubectl get namespace "$NAMESPACE" -o jsonpath='{.metadata.labels.pod-security\.kubernetes\.io/enforce}{"|"}{.metadata.labels.pod-security\.kubernetes\.io/enforce-version}{"|"}{.metadata.labels.pod-security\.kubernetes\.io/audit}{"|"}{.metadata.labels.pod-security\.kubernetes\.io/audit-version}{"|"}{.metadata.labels.pod-security\.kubernetes\.io/warn}{"|"}{.metadata.labels.pod-security\.kubernetes\.io/warn-version}')
IFS='|' read -r enforce_mode enforce_version audit_mode audit_version warn_mode warn_version <<EOF
$pod_security_labels
EOF
case "$enforce_mode" in
  baseline | restricted) ;;
  *)
    echo "Staging must enforce at least the Kubernetes baseline Pod Security policy" >&2
    exit 1
    ;;
esac
if [ "$enforce_version" != "$pod_security_version" ] || \
   [ "$audit_mode" != restricted ] || [ "$audit_version" != "$pod_security_version" ] || \
   [ "$warn_mode" != restricted ] || [ "$warn_version" != "$pod_security_version" ]; then
  echo "Staging Pod Security labels must be pinned to $pod_security_version with restricted audit/warn" >&2
  exit 1
fi

admission_resources=$(kubectl api-resources \
  --api-group=admissionregistration.k8s.io -o name)
printf '%s\n' "$admission_resources" | \
  grep -Fxq validatingadmissionpolicies.admissionregistration.k8s.io
printf '%s\n' "$admission_resources" | \
  grep -Fxq validatingadmissionpolicybindings.admissionregistration.k8s.io
for policy_name in \
  mnema-staging-secret-boundary \
  mnema-staging-service-boundary \
  mnema-staging-controller-identity \
  mnema-staging-pod-identity
do
  kubectl get validatingadmissionpolicy "$policy_name" >/dev/null
  kubectl get validatingadmissionpolicybinding "$policy_name" >/dev/null
done

KUBE_API_SERVER="$KUBE_API_SERVER" MODE=check \
  "$SCRIPT_DIR/reconcile-staging-host-firewall.sh" >/dev/null
"$SCRIPT_DIR/verify-production-telemetry-boundary.py" >/dev/null
systemctl is-enabled --quiet mnema-staging-host-boundary.service
systemctl is-enabled --quiet mnema-staging-host-boundary.timer
systemctl is-active --quiet mnema-staging-host-boundary.timer
KUBE_API_SERVER="$KUBE_API_SERVER" \
  "$SCRIPT_DIR/verify-staging-network-boundary.sh" >/dev/null
"$SCRIPT_DIR/verify-staging-tls-boundary.sh" >/dev/null

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
probe_output="${tmp_output}.probe"
trap 'rm -f "$tmp_output" "$probe_output"' EXIT HUP INT TERM

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
test "$(KUBECONFIG="$tmp_output" kubectl auth can-i create services -n "$NAMESPACE")" = yes
test "$(KUBECONFIG="$tmp_output" kubectl auth can-i update secret/mnema-secrets -n "$NAMESPACE")" = yes
test "$(KUBECONFIG="$tmp_output" kubectl auth can-i create secrets -n "$NAMESPACE")" = no
test "$(KUBECONFIG="$tmp_output" kubectl auth can-i list secrets -n "$NAMESPACE")" = no
test "$(KUBECONFIG="$tmp_output" kubectl auth can-i create ingresses.networking.k8s.io -n "$NAMESPACE")" = no
test "$(KUBECONFIG="$tmp_output" kubectl auth can-i get secrets -n prod)" = no
test "$(KUBECONFIG="$tmp_output" kubectl auth can-i create namespaces)" = no

assert_pod_security_rejects() {
  probe_name=$1
  if KUBECONFIG="$tmp_output" kubectl apply --dry-run=server -f - >"$probe_output" 2>&1; then
    echo "Pod Security admitted the forbidden $probe_name staging workload" >&2
    exit 1
  fi
  if ! grep -Fq 'violates PodSecurity' "$probe_output"; then
    echo "The $probe_name probe failed without proving Pod Security enforcement" >&2
    exit 1
  fi
}

assert_pod_security_rejects privileged <<'YAML'
apiVersion: apps/v1
kind: Deployment
metadata:
  name: mnema-staging-pss-probe-privileged
  namespace: mnema-staging
spec:
  replicas: 0
  selector: {matchLabels: {app: mnema-staging-pss-probe-privileged}}
  template:
    metadata: {labels: {app: mnema-staging-pss-probe-privileged}}
    spec:
      containers:
        - name: probe
          image: registry.k8s.io/pause:3.10
          securityContext: {privileged: true}
YAML

assert_pod_security_rejects hostPath <<'YAML'
apiVersion: apps/v1
kind: Deployment
metadata:
  name: mnema-staging-pss-probe-hostpath
  namespace: mnema-staging
spec:
  replicas: 0
  selector: {matchLabels: {app: mnema-staging-pss-probe-hostpath}}
  template:
    metadata: {labels: {app: mnema-staging-pss-probe-hostpath}}
    spec:
      containers:
        - name: probe
          image: registry.k8s.io/pause:3.10
          volumeMounts: [{name: host, mountPath: /host}]
      volumes: [{name: host, hostPath: {path: /}}]
YAML

assert_pod_security_rejects hostNetwork <<'YAML'
apiVersion: apps/v1
kind: Deployment
metadata:
  name: mnema-staging-pss-probe-hostnetwork
  namespace: mnema-staging
spec:
  replicas: 0
  selector: {matchLabels: {app: mnema-staging-pss-probe-hostnetwork}}
  template:
    metadata: {labels: {app: mnema-staging-pss-probe-hostnetwork}}
    spec:
      hostNetwork: true
      containers:
        - name: probe
          image: registry.k8s.io/pause:3.10
YAML

assert_admission_rejects() {
  probe_name=$1
  expected_message=$2
  if KUBECONFIG="$tmp_output" kubectl apply --dry-run=server -f - >"$probe_output" 2>&1; then
    echo "Admission policy allowed the forbidden $probe_name staging object" >&2
    exit 1
  fi
  if ! grep -Fq "$expected_message" "$probe_output"; then
    echo "The $probe_name probe failed without proving the expected admission boundary" >&2
    exit 1
  fi
}

assert_admission_rejects service-nodeport 'Mnema staging Services must remain cluster-internal' <<'YAML'
apiVersion: v1
kind: Service
metadata:
  name: mnema-staging-boundary-probe-nodeport
  namespace: mnema-staging
spec:
  type: NodePort
  selector: {app: mnema-staging-boundary-probe}
  ports: [{port: 80, targetPort: 8080}]
YAML

assert_admission_rejects service-loadbalancer 'Mnema staging Services must remain cluster-internal' <<'YAML'
apiVersion: v1
kind: Service
metadata:
  name: mnema-staging-boundary-probe-loadbalancer
  namespace: mnema-staging
spec:
  type: LoadBalancer
  selector: {app: mnema-staging-boundary-probe}
  ports: [{port: 80, targetPort: 8080}]
YAML

assert_admission_rejects service-externalips 'Mnema staging Services must not declare externalIPs' <<'YAML'
apiVersion: v1
kind: Service
metadata:
  name: mnema-staging-boundary-probe-externalips
  namespace: mnema-staging
spec:
  type: ClusterIP
  externalIPs: [192.0.2.10]
  selector: {app: mnema-staging-boundary-probe}
  ports: [{port: 80, targetPort: 8080}]
YAML

assert_admission_rejects deployer-service-account 'Workloads must not run as the Mnema staging deployer ServiceAccount' <<'YAML'
apiVersion: apps/v1
kind: Deployment
metadata:
  name: mnema-staging-boundary-probe-deployer
  namespace: mnema-staging
spec:
  replicas: 0
  selector: {matchLabels: {app: mnema-staging-boundary-probe-deployer}}
  template:
    metadata: {labels: {app: mnema-staging-boundary-probe-deployer}}
    spec:
      serviceAccountName: mnema-deployer
      automountServiceAccountToken: false
      containers: [{name: probe, image: registry.k8s.io/pause:3.10}]
YAML

assert_admission_rejects token-automount 'Workloads must explicitly disable ServiceAccount token automounting' <<'YAML'
apiVersion: batch/v1
kind: Job
metadata:
  name: mnema-staging-boundary-probe-automount
  namespace: mnema-staging
spec:
  template:
    metadata: {labels: {app: mnema-staging-boundary-probe-automount}}
    spec:
      automountServiceAccountToken: true
      restartPolicy: Never
      containers: [{name: probe, image: registry.k8s.io/pause:3.10}]
YAML

assert_admission_rejects excessive-ephemeral-storage 'maximum limit usage per Container is 4Gi' <<'YAML'
apiVersion: apps/v1
kind: Deployment
metadata:
  name: mnema-staging-boundary-probe-ephemeral-storage
  namespace: mnema-staging
spec:
  replicas: 0
  selector: {matchLabels: {app: mnema-staging-boundary-probe-ephemeral-storage}}
  template:
    metadata: {labels: {app: mnema-staging-boundary-probe-ephemeral-storage}}
    spec:
      automountServiceAccountToken: false
      containers:
        - name: probe
          image: registry.k8s.io/pause:3.10
          resources:
            limits: {ephemeral-storage: 5Gi}
YAML

if KUBECONFIG="$tmp_output" kubectl patch secret mnema-secrets -n "$NAMESPACE" \
  --type=merge --dry-run=server \
  -p '{"metadata":{"annotations":{"kubernetes.io/service-account.name":"mnema-deployer"}},"type":"kubernetes.io/service-account-token"}' \
  >"$probe_output" 2>&1; then
  echo "Admission policy allowed the application Secret to mint a ServiceAccount token" >&2
  exit 1
fi
if ! grep -Fq 'The Mnema staging application Secret must remain Opaque' "$probe_output"; then
  echo "The Secret probe failed without proving the ServiceAccount-token boundary" >&2
  exit 1
fi

mv "$tmp_output" "$OUTPUT"
trap - EXIT HUP INT TERM
printf 'staging_kubeconfig=%s\n' "$OUTPUT"
printf 'token_expires_at=%s\n' "$token_expires_at"
printf 'pod_security_version=%s\n' "$pod_security_version"
