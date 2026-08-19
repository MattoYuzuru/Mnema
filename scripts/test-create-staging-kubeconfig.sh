#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
CREATE_KUBECONFIG="$SCRIPT_DIR/create-staging-kubeconfig.sh"
TEST_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/mnema-staging-kubeconfig.XXXXXX")
trap 'rm -rf "$TEST_ROOT"' EXIT HUP INT TERM

mkdir "$TEST_ROOT/bin"
cat >"$TEST_ROOT/bin/kubectl" <<'EOF'
#!/bin/sh
set -eu

if [ "${1:-}" = version ]; then
  printf '%s\n' '{"clientVersion":{"gitVersion":"v1.36.0"},"serverVersion":{"gitVersion":"v1.36.2+k3s1"}}'
  exit 0
fi
if [ "${1:-}" = label ] && [ "${2:-}" = namespace ] && \
   [ "${3:-}" = mnema-staging ]; then
  printf '%s\n' pod-security-label >>"$FAKE_KUBECTL_LOG"
  exit 0
fi
if [ "${1:-}" = get ] && [ "${2:-}" = namespace ] && \
   [ "${3:-}" = mnema-staging ]; then
  printf '%s' "${FAKE_POD_SECURITY_LABELS:-baseline|v1.36|restricted|v1.36|restricted|v1.36}"
  exit 0
fi
if [ "${1:-}" = api-resources ]; then
  if [ "${FAKE_MISSING_VAP_API:-false}" = true ]; then
    printf '%s\n' validatingadmissionpolicies.admissionregistration.k8s.io
  else
    printf '%s\n' \
      validatingadmissionpolicies.admissionregistration.k8s.io \
      validatingadmissionpolicybindings.admissionregistration.k8s.io
  fi
  exit 0
fi
if [ "${1:-}" = get ] && { [ "${2:-}" = validatingadmissionpolicy ] || \
   [ "${2:-}" = validatingadmissionpolicybinding ]; }; then
  exit 0
fi
if [ "${1:-}" = -n ] && [ "${2:-}" = mnema-staging ] && \
   [ "${3:-}" = get ] && [ "${4:-}" = networkpolicy ]; then
  exit 0
fi
if [ "${1:-}" = -n ] && [ "${2:-}" = mnema-staging ] && \
   [ "${3:-}" = get ] && [ "${4:-}" = pod ]; then
  case "$*" in
    *"mnema-staging-network-probe-server -o jsonpath="*) printf '%s' 10.42.0.20 ;;
    *"mnema-staging-network-probe-client -o jsonpath="*) printf '%s' '256Mi|2Gi' ;;
    *) exit 1 ;;
  esac
  exit 0
fi
if [ "${1:-}" = create ] && [ "${2:-}" = -f ] && [ "${3:-}" = - ]; then
  while IFS= read -r _line; do :; done
  printf '%s\n' network-probe-create >>"$FAKE_KUBECTL_LOG"
  exit 0
fi
if [ "${1:-}" = -n ] && [ "${2:-}" = mnema-staging ] && \
   [ "${3:-}" = wait ]; then
  exit 0
fi
if [ "${1:-}" = -n ] && [ "${2:-}" = mnema-staging ] && \
   [ "${3:-}" = delete ] && [ "${4:-}" = pod ]; then
  exit 0
fi
if [ "${1:-}" = -n ] && [ "${2:-}" = mnema-staging ] && \
   [ "${3:-}" = exec ]; then
  case "$*" in
    *'redis-cli -h 10.42.0.20 -p 6379 ping'*) printf '%s\n' PONG ;;
    *'getent hosts kubernetes.default.svc.cluster.local'*) printf '%s\n' '10.43.0.1 kubernetes.default.svc.cluster.local' ;;
    *'getent hosts redis.prod.svc.cluster.local'*) printf '%s\n' '10.43.0.20 redis.prod.svc.cluster.local' ;;
    *'nc -z -w 5 github.com 443'*) exit 0 ;;
    *'nc -z -w 3 kubernetes.default.svc.cluster.local 443'*)
      if [ "${FAKE_NETWORK_BREACH:-}" = kubernetes ]; then exit 0; fi
      exit 1
      ;;
    *'nc -z -w 3 redis.prod.svc.cluster.local 6379'*)
      if [ "${FAKE_NETWORK_BREACH:-}" = prod-redis ]; then exit 0; fi
      exit 1
      ;;
    *'nc -z -w 3 169.254.169.254 80'*)
      if [ "${FAKE_NETWORK_BREACH:-}" = metadata ]; then exit 0; fi
      exit 1
      ;;
    *) exit 67 ;;
  esac
  exit 0
fi
if [ "${1:-}" = config ]; then
  printf '%s' 'dGVzdC1jYQ=='
  exit 0
fi
if [ "${1:-}" = -n ] && [ "${2:-}" = mnema-staging ] && \
   [ "${3:-}" = create ] && [ "${4:-}" = token ] && \
   [ "${5:-}" = mnema-deployer ] && [ "${6:-}" = --duration=720h ] && \
   [ "${7:-}" = -o ]; then
  printf '%s' 'header.payload.signature|2026-09-18T00:00:00Z'
  exit 0
fi
if [ "${1:-}" = auth ] && [ "${2:-}" = can-i ]; then
  case "$*" in
    *'create deployments.apps -n mnema-staging'*) printf '%s\n' yes ;;
    *'create services -n mnema-staging'*) printf '%s\n' yes ;;
    *'update secret/mnema-secrets -n mnema-staging'*) printf '%s\n' yes ;;
    *'create secrets -n mnema-staging'*) printf '%s\n' no ;;
    *'list secrets -n mnema-staging'*) printf '%s\n' no ;;
    *'create ingresses.networking.k8s.io -n mnema-staging'*) printf '%s\n' no ;;
    *'get secrets -n prod'*) printf '%s\n' no ;;
    *'create namespaces'*) printf '%s\n' no ;;
    *) exit 65 ;;
  esac
  exit 0
fi
if [ "${1:-}" = apply ] && [ "${2:-}" = --dry-run=server ] && \
   [ "${3:-}" = -f ] && [ "${4:-}" = - ]; then
  payload=$(while IFS= read -r line; do printf '%s\n' "$line"; done)
  case "$payload" in
    *mnema-staging-pss-probe-privileged*)
      probe=pss-privileged
      message='forbidden: violates PodSecurity "baseline:v1.36"'
      ;;
    *mnema-staging-pss-probe-hostpath*)
      probe=pss-hostpath
      message='forbidden: violates PodSecurity "baseline:v1.36"'
      ;;
    *mnema-staging-pss-probe-hostnetwork*)
      probe=pss-hostnetwork
      message='forbidden: violates PodSecurity "baseline:v1.36"'
      ;;
    *mnema-staging-boundary-probe-nodeport*)
      probe=service-nodeport
      message='denied: Mnema staging Services must remain cluster-internal'
      ;;
    *mnema-staging-boundary-probe-loadbalancer*)
      probe=service-loadbalancer
      message='denied: Mnema staging Services must remain cluster-internal'
      ;;
    *mnema-staging-boundary-probe-externalips*)
      probe=service-externalips
      message='denied: Mnema staging Services must not declare externalIPs'
      ;;
    *mnema-staging-boundary-probe-deployer*)
      probe=deployer-service-account
      message='denied: Workloads must not run as the Mnema staging deployer ServiceAccount'
      ;;
    *mnema-staging-boundary-probe-automount*)
      probe=token-automount
      message='denied: Workloads must explicitly disable ServiceAccount token automounting'
      ;;
    *mnema-staging-boundary-probe-ephemeral-storage*)
      probe=excessive-ephemeral-storage
      message='denied: maximum limit usage per Container is 4Gi'
      ;;
    *) exit 66 ;;
  esac
  printf '%s\n' "$probe" >>"$FAKE_KUBECTL_LOG"
  if [ "${FAKE_ALLOWED_PROBE:-}" = "$probe" ]; then
    printf '%s\n' accepted
    exit 0
  fi
  printf '%s\n' "$message" >&2
  exit 1
fi
if [ "${1:-}" = patch ] && [ "${2:-}" = secret ] && \
   [ "${3:-}" = mnema-secrets ]; then
  printf '%s\n' secret-token-patch >>"$FAKE_KUBECTL_LOG"
  if [ "${FAKE_ALLOW_SECRET_TOKEN_PATCH:-false}" = true ]; then
    printf '%s\n' accepted
    exit 0
  fi
  echo 'denied: The Mnema staging application Secret must remain Opaque' >&2
  exit 1
fi

exit 64
EOF
chmod +x "$TEST_ROOT/bin/kubectl"

output="$TEST_ROOT/credentials/staging.kubeconfig"
fake_log="$TEST_ROOT/kubectl.log"
result=$(PATH="$TEST_ROOT/bin:$PATH" FAKE_KUBECTL_LOG="$fake_log" \
  OUTPUT="$output" \
  KUBE_API_SERVER=https://kubernetes.main.example:6443 \
  TOKEN_DURATION=720h \
  "$CREATE_KUBECONFIG")

grep -Fq 'server: https://kubernetes.main.example:6443' "$output"
grep -Fq 'token: header.payload.signature' "$output"
printf '%s\n' "$result" | grep -Fq 'token_expires_at=2026-09-18T00:00:00Z'
printf '%s\n' "$result" | grep -Fq 'pod_security_version=v1.36'
test "$(grep -c '^pod-security-label$' "$fake_log")" -eq 1
test "$(grep -c '^pss-' "$fake_log")" -eq 3
test "$(grep -c '^service-' "$fake_log")" -eq 3
test "$(grep -c '^deployer-service-account$' "$fake_log")" -eq 1
test "$(grep -c '^token-automount$' "$fake_log")" -eq 1
test "$(grep -c '^excessive-ephemeral-storage$' "$fake_log")" -eq 1
test "$(grep -c '^secret-token-patch$' "$fake_log")" -eq 1
test "$(grep -c '^network-probe-create$' "$fake_log")" -eq 2

if stat -f '%Lp' "$output" >/dev/null 2>&1; then
  mode=$(stat -f '%Lp' "$output")
else
  mode=$(stat -c '%a' "$output")
fi
test "$mode" = 600

loopback_index=0
for loopback_endpoint in \
  https://127.0.0.1 \
  https://127.0.0.2:6443 \
  https://127.1 \
  https://localhost \
  'https://[::1]' \
  'https://[::1]:6443' \
  'https://[::ffff:127.0.0.1]:6443'
do
  loopback_index=$((loopback_index + 1))
  if PATH="$TEST_ROOT/bin:$PATH" FAKE_KUBECTL_LOG="$fake_log" \
    OUTPUT="$TEST_ROOT/loopback-${loopback_index}.kubeconfig" \
    KUBE_API_SERVER="$loopback_endpoint" \
    "$CREATE_KUBECONFIG" >/dev/null 2>&1; then
    echo "loopback Kubernetes API endpoint was accepted: $loopback_endpoint" >&2
    exit 1
  fi
done

if PATH="$TEST_ROOT/bin:$PATH" FAKE_KUBECTL_LOG="$fake_log" \
  OUTPUT="$output" \
  KUBE_API_SERVER=https://kubernetes.main.example:6443 \
  "$CREATE_KUBECONFIG" >/dev/null 2>&1; then
  echo 'existing credentials must not be overwritten' >&2
  exit 1
fi

if PATH="$TEST_ROOT/bin:$PATH" FAKE_KUBECTL_LOG="$fake_log" \
  FAKE_POD_SECURITY_LABELS='baseline|latest|restricted|latest|restricted|latest' \
  OUTPUT="$TEST_ROOT/unpinned.kubeconfig" \
  KUBE_API_SERVER=https://kubernetes.main.example:6443 \
  "$CREATE_KUBECONFIG" >/dev/null 2>&1; then
  echo 'unpinned Pod Security policy labels must block credential creation' >&2
  exit 1
fi

if PATH="$TEST_ROOT/bin:$PATH" FAKE_KUBECTL_LOG="$fake_log" \
  FAKE_ALLOWED_PROBE=service-nodeport \
  OUTPUT="$TEST_ROOT/missing-service-policy.kubeconfig" \
  KUBE_API_SERVER=https://kubernetes.main.example:6443 \
  "$CREATE_KUBECONFIG" >/dev/null 2>&1; then
  echo 'an admitted NodePort probe must block credential creation' >&2
  exit 1
fi
test ! -e "$TEST_ROOT/missing-service-policy.kubeconfig"

if PATH="$TEST_ROOT/bin:$PATH" FAKE_KUBECTL_LOG="$fake_log" \
  FAKE_ALLOW_SECRET_TOKEN_PATCH=true \
  OUTPUT="$TEST_ROOT/missing-secret-policy.kubeconfig" \
  KUBE_API_SERVER=https://kubernetes.main.example:6443 \
  "$CREATE_KUBECONFIG" >/dev/null 2>&1; then
  echo 'an admitted ServiceAccount-token Secret patch must block credential creation' >&2
  exit 1
fi
test ! -e "$TEST_ROOT/missing-secret-policy.kubeconfig"

if PATH="$TEST_ROOT/bin:$PATH" FAKE_KUBECTL_LOG="$fake_log" \
  FAKE_MISSING_VAP_API=true \
  OUTPUT="$TEST_ROOT/missing-vap-api.kubeconfig" \
  KUBE_API_SERVER=https://kubernetes.main.example:6443 \
  "$CREATE_KUBECONFIG" >/dev/null 2>&1; then
  echo 'a cluster without the stable admission-policy API must block credential creation' >&2
  exit 1
fi
test ! -e "$TEST_ROOT/missing-vap-api.kubeconfig"

if PATH="$TEST_ROOT/bin:$PATH" FAKE_KUBECTL_LOG="$fake_log" \
  FAKE_NETWORK_BREACH=prod-redis \
  OUTPUT="$TEST_ROOT/network-breach.kubeconfig" \
  KUBE_API_SERVER=https://kubernetes.main.example:6443 \
  "$CREATE_KUBECONFIG" >/dev/null 2>&1; then
  echo 'live staging-to-production connectivity must block credential creation' >&2
  exit 1
fi
test ! -e "$TEST_ROOT/network-breach.kubeconfig"

printf 'staging_kubeconfig_contract=ok\n'
