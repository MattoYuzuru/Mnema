#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
RECONCILE="$SCRIPT_DIR/reconcile-staging-host-firewall.sh"
VERIFY="$SCRIPT_DIR/verify-production-telemetry-boundary.py"
TEST_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/mnema-host-firewall.XXXXXX")
trap 'rm -rf "$TEST_ROOT"' EXIT HUP INT TERM
mkdir "$TEST_ROOT/bin"

cat >"$TEST_ROOT/bin/kubectl" <<'EOF'
#!/bin/sh
set -eu
if [ -n "${FAKE_KUBECTL_LOG:-}" ]; then
  printf 'kubectl %s\n' "$*" >>"$FAKE_KUBECTL_LOG"
fi
case "$*" in
  'get nodes -o json')
    printf '%s\n' '{"items":[{"spec":{"podCIDRs":["10.42.0.0/24","fd42::/64"]},"status":{"addresses":[{"type":"InternalIP","address":"10.0.0.10"},{"type":"ExternalIP","address":"198.51.100.10"},{"type":"InternalIP","address":"fd00::10"}]}}]}'
    ;;
  '-n default get service kubernetes -o json')
    printf '%s\n' '{"spec":{"clusterIPs":["10.43.0.1","fd43::1"]}}'
    ;;
  '-n kube-system get pods -l k8s-app=metrics-server -o json')
    printf '%s\n' '{"items":[{"status":{"phase":"Running","conditions":[{"type":"Ready","status":"True"}],"podIP":"10.42.0.5"}},{"status":{"phase":"Running","conditions":[{"type":"Ready","status":"True"}],"podIP":"fd42::5"}}]}'
    ;;
  '-n observability get pods -l app=prometheus -o json')
    if [ "${FAKE_MISSING_PROMETHEUS:-false}" = true ]; then
      printf '%s\n' '{"items":[]}'
    else
      printf '%s\n' '{"items":[{"status":{"phase":"Running","conditions":[{"type":"Ready","status":"True"}],"podIP":"10.42.0.9"}},{"status":{"phase":"Running","conditions":[{"type":"Ready","status":"True"}],"podIP":"fd42::9"}}]}'
    fi
    ;;
  'get --raw /api/v1/namespaces/observability/services/http:prometheus:9090/proxy/api/v1/targets')
    case "${FAKE_TARGETS:-healthy}" in
      healthy) printf '%s\n' '{"data":{"activeTargets":[{"labels":{"job":"node-exporter"},"health":"up","lastScrape":"2099-01-01T00:00:00Z"},{"labels":{"job":"kubelet"},"health":"up","lastScrape":"2099-01-01T00:00:00Z"},{"labels":{"job":"cadvisor"},"health":"up","lastScrape":"2099-01-01T00:00:00Z"}]}}' ;;
      stale) printf '%s\n' '{"data":{"activeTargets":[{"labels":{"job":"node-exporter"},"health":"up","lastScrape":"2020-01-01T00:00:00Z"},{"labels":{"job":"kubelet"},"health":"up","lastScrape":"2020-01-01T00:00:00Z"},{"labels":{"job":"cadvisor"},"health":"up","lastScrape":"2020-01-01T00:00:00Z"}]}}' ;;
    esac
    ;;
  'get --raw /apis/metrics.k8s.io/v1beta1/nodes')
    if [ "${FAKE_TARGETS:-healthy}" = stale ]; then
      printf '%s\n' '{"items":[{"timestamp":"2020-01-01T00:00:00Z"}]}'
    else
      printf '%s\n' '{"items":[{"timestamp":"2099-01-01T00:00:00Z"}]}'
    fi
    ;;
  *) exit 64 ;;
esac
EOF
cat >"$TEST_ROOT/bin/iptables" <<'EOF'
#!/bin/sh
set -eu
printf 'iptables %s\n' "$*" >>"$FAKE_FIREWALL_LOG"
if [ "${1:-}" = -w ] && [ "${2:-}" = -C ] && \
  { [ "${3:-}" = INPUT ] || [ "${3:-}" = FORWARD ]; }; then
  if [ "${FAKE_ACTIVE_CHAIN:-}" = A ] && [ "${5:-}" = MNEMA_POD_HOST_BOUNDARY_A ]; then
    exit 0
  fi
  if [ "${FAKE_ACTIVE_CHAIN:-}" = B ] && [ "${5:-}" = MNEMA_POD_HOST_BOUNDARY_B ]; then
    exit 0
  fi
  exit 1
fi
if [ "${FAKE_FAIL_NEW_CHAIN:-}" = B ] && [ "${1:-}" = -w ] && \
   [ "${2:-}" = -A ] && [ "${3:-}" = MNEMA_POD_HOST_BOUNDARY_B ]; then
  exit 1
fi
if [ "${FAKE_FIREWALL_MISSING_RULE:-false}" = true ] && [ "${1:-}" = -w ] && [ "${2:-}" = -C ]; then
  exit 1
fi
exit 0
EOF
cat >"$TEST_ROOT/bin/ip6tables" <<'EOF'
#!/bin/sh
set -eu
printf 'ip6tables %s\n' "$*" >>"$FAKE_FIREWALL_LOG"
if [ "${1:-}" = -w ] && [ "${2:-}" = -C ] && \
  { [ "${3:-}" = INPUT ] || [ "${3:-}" = FORWARD ]; }; then
  if [ "${FAKE_ACTIVE_CHAIN:-}" = A ] && [ "${5:-}" = MNEMA_POD_HOST_BOUNDARY_A ]; then
    exit 0
  fi
  if [ "${FAKE_ACTIVE_CHAIN:-}" = B ] && [ "${5:-}" = MNEMA_POD_HOST_BOUNDARY_B ]; then
    exit 0
  fi
  exit 1
fi
if [ "${FAKE_FIREWALL_MISSING_RULE:-false}" = true ] && [ "${1:-}" = -w ] && [ "${2:-}" = -C ]; then
  exit 1
fi
exit 0
EOF
chmod +x "$TEST_ROOT/bin/kubectl" "$TEST_ROOT/bin/iptables" "$TEST_ROOT/bin/ip6tables" \
  "$RECONCILE" "$VERIFY"

firewall_log="$TEST_ROOT/firewall.log"
PATH="$TEST_ROOT/bin:$PATH" FAKE_FIREWALL_LOG="$firewall_log" \
  FAKE_KUBECTL_LOG="$firewall_log" TELEMETRY_VERIFY_TIMEOUT_SECONDS=0 \
  KUBE_API_SERVER=https://kubernetes.main.example:6443 \
  KUBE_API_ADDRESSES=198.51.100.10,2001:db8::10 \
  MODE=apply "$RECONCILE" >/dev/null

grep -Fq 'iptables -w -A MNEMA_POD_HOST_BOUNDARY_A -s 10.42.0.0/24 -d 198.51.100.10 -j REJECT' "$firewall_log"
grep -Fq 'iptables -w -A MNEMA_POD_HOST_BOUNDARY_A -s 10.42.0.0/24 -d 10.0.0.10 -p tcp -m multiport --dports 80,443 -j RETURN' "$firewall_log"
grep -Fq 'iptables -w -A MNEMA_POD_HOST_BOUNDARY_A -s 10.42.0.5/32 -d 10.0.0.10 -p tcp --dport 10250 -j RETURN' "$firewall_log"
grep -Fq 'iptables -w -A MNEMA_POD_HOST_BOUNDARY_A -s 10.42.0.9/32 -d 10.0.0.10 -p tcp -m multiport --dports 9100,10250 -j RETURN' "$firewall_log"
grep -Fq 'iptables -w -A MNEMA_POD_HOST_BOUNDARY_A -s 10.42.0.0/24 -d 10.0.0.10 -p tcp --dport 6443 -m conntrack --ctorigdst 10.43.0.1 --ctorigdstport 443 -j RETURN' "$firewall_log"
grep -Fq 'iptables -w -A MNEMA_POD_HOST_BOUNDARY_A -s 10.42.0.0/24 -d 10.0.0.10 -j REJECT' "$firewall_log"
grep -Fq 'ip6tables -w -A MNEMA_POD_HOST_BOUNDARY_A -s fd42::/64 -d 2001:db8::10 -p tcp --dport 6443 -j REJECT' "$firewall_log"
grep -Fq 'iptables -w -I INPUT 1 -j MNEMA_POD_HOST_BOUNDARY_A' "$firewall_log"
grep -Fq 'iptables -w -I FORWARD 1 -j MNEMA_POD_HOST_BOUNDARY_A' "$firewall_log"

: >"$firewall_log"
PATH="$TEST_ROOT/bin:$PATH" FAKE_FIREWALL_LOG="$firewall_log" FAKE_ACTIVE_CHAIN=A \
  FAKE_KUBECTL_LOG="$firewall_log" TELEMETRY_VERIFY_TIMEOUT_SECONDS=0 \
  KUBE_API_SERVER=https://kubernetes.main.example:6443 \
  KUBE_API_ADDRESSES=198.51.100.10,2001:db8::10 \
  MODE=apply "$RECONCILE" >/dev/null
grep -Fq 'iptables -w -A MNEMA_POD_HOST_BOUNDARY_B -s 10.42.0.9/32 -d 10.0.0.10 -p tcp -m multiport --dports 9100,10250 -j RETURN' "$firewall_log"
grep -Fq 'iptables -w -I INPUT 1 -j MNEMA_POD_HOST_BOUNDARY_B' "$firewall_log"
grep -Fq 'iptables -w -D INPUT -j MNEMA_POD_HOST_BOUNDARY_A' "$firewall_log"
grep -Fq 'iptables -w -X MNEMA_POD_HOST_BOUNDARY_A' "$firewall_log"
telemetry_line=$(grep -n -m1 'kubectl get --raw /apis/metrics.k8s.io/v1beta1/nodes' "$firewall_log" | cut -d: -f1)
old_delete_line=$(grep -n -m1 'iptables -w -D INPUT -j MNEMA_POD_HOST_BOUNDARY_A' "$firewall_log" | cut -d: -f1)
if [ -z "$telemetry_line" ] || [ -z "$old_delete_line" ] || [ "$telemetry_line" -ge "$old_delete_line" ]; then
  echo 'the old host boundary must remain active until fresh telemetry succeeds' >&2
  exit 1
fi

: >"$firewall_log"
if PATH="$TEST_ROOT/bin:$PATH" FAKE_FIREWALL_LOG="$firewall_log" \
  FAKE_KUBECTL_LOG="$firewall_log" FAKE_ACTIVE_CHAIN=A FAKE_TARGETS=stale \
  TELEMETRY_VERIFY_TIMEOUT_SECONDS=0 \
  KUBE_API_SERVER=https://kubernetes.main.example:6443 \
  KUBE_API_ADDRESSES=198.51.100.10,2001:db8::10 \
  MODE=apply "$RECONCILE" >/dev/null 2>&1; then
  echo 'a refresh with stale post-swap telemetry must fail closed' >&2
  exit 1
fi
grep -Fq 'iptables -w -I INPUT 1 -j MNEMA_POD_HOST_BOUNDARY_B' "$firewall_log"
grep -Fq 'iptables -w -D INPUT -j MNEMA_POD_HOST_BOUNDARY_B' "$firewall_log"
grep -Fq 'iptables -w -X MNEMA_POD_HOST_BOUNDARY_B' "$firewall_log"
if grep -Fq 'iptables -w -D INPUT -j MNEMA_POD_HOST_BOUNDARY_A' "$firewall_log" || \
  grep -Fq 'iptables -w -F MNEMA_POD_HOST_BOUNDARY_A' "$firewall_log"; then
  echo 'failed post-swap telemetry must preserve the previous active boundary' >&2
  exit 1
fi

: >"$firewall_log"
if PATH="$TEST_ROOT/bin:$PATH" FAKE_FIREWALL_LOG="$firewall_log" \
  FAKE_ACTIVE_CHAIN=A FAKE_FAIL_NEW_CHAIN=B \
  TELEMETRY_VERIFY_TIMEOUT_SECONDS=0 \
  KUBE_API_SERVER=https://kubernetes.main.example:6443 \
  KUBE_API_ADDRESSES=198.51.100.10,2001:db8::10 \
  MODE=apply "$RECONCILE" >/dev/null 2>&1; then
  echo 'interrupted alternate-chain population must fail closed' >&2
  exit 1
fi
if grep -Fq 'iptables -w -I INPUT 1 -j MNEMA_POD_HOST_BOUNDARY_B' "$firewall_log" || \
   grep -Fq 'iptables -w -D INPUT -j MNEMA_POD_HOST_BOUNDARY_A' "$firewall_log"; then
  echo 'an incomplete alternate chain must not replace the active chain' >&2
  exit 1
fi

: >"$firewall_log"
if PATH="$TEST_ROOT/bin:$PATH" FAKE_FIREWALL_LOG="$firewall_log" \
  FAKE_ACTIVE_CHAIN=A FAKE_MISSING_PROMETHEUS=true TELEMETRY_VERIFY_TIMEOUT_SECONDS=0 \
  KUBE_API_SERVER=https://kubernetes.main.example:6443 \
  KUBE_API_ADDRESSES=198.51.100.10 \
  MODE=apply "$RECONCILE" >/dev/null 2>&1; then
  echo 'host boundary must not refresh without a trusted production Prometheus identity' >&2
  exit 1
fi
if grep -Eq '^(iptables|ip6tables) ' "$firewall_log"; then
  echo 'missing telemetry identity must fail before any firewall mutation' >&2
  exit 1
fi

: >"$firewall_log"
PATH="$TEST_ROOT/bin:$PATH" FAKE_FIREWALL_LOG="$firewall_log" \
  FAKE_ACTIVE_CHAIN=A TELEMETRY_VERIFY_TIMEOUT_SECONDS=0 \
  KUBE_API_SERVER=https://kubernetes.main.example:6443 \
  KUBE_API_ADDRESSES=198.51.100.10,2001:db8::10 \
  MODE=check "$RECONCILE" >/dev/null
if grep -Fq ' -A ' "$firewall_log" || grep -Fq ' -I ' "$firewall_log"; then
  echo 'firewall check mode must not mutate rules' >&2
  exit 1
fi

if PATH="$TEST_ROOT/bin:$PATH" FAKE_FIREWALL_LOG="$firewall_log" \
  FAKE_ACTIVE_CHAIN=A FAKE_FIREWALL_MISSING_RULE=true TELEMETRY_VERIFY_TIMEOUT_SECONDS=0 \
  KUBE_API_SERVER=https://kubernetes.main.example:6443 \
  KUBE_API_ADDRESSES=198.51.100.10 \
  MODE=check "$RECONCILE" >/dev/null 2>&1; then
  echo 'missing persistent host rules must fail closed' >&2
  exit 1
fi

printf 'staging_host_firewall=ok\n'
