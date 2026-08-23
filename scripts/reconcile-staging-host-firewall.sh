#!/bin/sh
set -eu

# Kubernetes NetworkPolicy intentionally cannot isolate Pods from their resident
# node. This owner-only helper adds a host boundary for every cluster Pod CIDR.
# It permits the shared public web listeners on node addresses, rejects every
# other node listener, and always rejects the exact externally advertised API.
MODE=${MODE:-apply}
KUBE_API_SERVER=${KUBE_API_SERVER:?KUBE_API_SERVER is required}
KUBE_API_ADDRESSES=${KUBE_API_ADDRESSES:-}
SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
TELEMETRY_VERIFY=${TELEMETRY_VERIFY:-$SCRIPT_DIR/verify-production-telemetry-boundary.py}
TELEMETRY_VERIFY_TIMEOUT_SECONDS=${TELEMETRY_VERIFY_TIMEOUT_SECONDS:-60}
CHAIN_A=MNEMA_POD_HOST_BOUNDARY_A
CHAIN_B=MNEMA_POD_HOST_BOUNDARY_B

case "$MODE" in
  apply | check) ;;
  *) echo "MODE must be apply or check" >&2; exit 64 ;;
esac
case "$TELEMETRY_VERIFY_TIMEOUT_SECONDS" in
  '' | *[!0-9]*)
    echo "TELEMETRY_VERIFY_TIMEOUT_SECONDS must be an integer" >&2
    exit 64
    ;;
esac
if [ "$TELEMETRY_VERIFY_TIMEOUT_SECONDS" -gt 300 ] || [ ! -x "$TELEMETRY_VERIFY" ]; then
  echo "A valid executable telemetry verifier and timeout <= 300 are required" >&2
  exit 64
fi

TEST_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/mnema-host-boundary.XXXXXX")
trap 'rm -rf "$TEST_ROOT"' EXIT HUP INT TERM
nodes_file="$TEST_ROOT/nodes.json"
inventory_file="$TEST_ROOT/inventory"
kubectl get nodes -o json >"$nodes_file"
kubernetes_service_ips=$(kubectl -n default get service kubernetes -o json | \
  python3 -c '
import json
import sys

service = json.load(sys.stdin)
values = service.get("spec", {}).get("clusterIPs") or [service.get("spec", {}).get("clusterIP", "")]
print(",".join(value for value in values if value and value != "None"))
')
metrics_server_ips=$(kubectl -n kube-system get pods -l k8s-app=metrics-server -o json | \
  python3 -c '
import json
import sys

pods = json.load(sys.stdin)
print(",".join(sorted({
    value
    for pod in pods.get("items", [])
    if not pod.get("metadata", {}).get("deletionTimestamp")
    and pod.get("status", {}).get("phase") == "Running"
    and any(
        condition.get("type") == "Ready" and condition.get("status") == "True"
        for condition in pod.get("status", {}).get("conditions", [])
    )
    for value in (
        [pod.get("status", {}).get("podIP", "")]
        + [entry.get("ip", "") for entry in pod.get("status", {}).get("podIPs", [])]
    )
    if value
})))
')
prometheus_ips=$(kubectl -n observability get pods -l app=prometheus -o json | \
  python3 -c '
import json
import sys

pods = json.load(sys.stdin)
print(",".join(sorted({
    value
    for pod in pods.get("items", [])
    if not pod.get("metadata", {}).get("deletionTimestamp")
    and pod.get("status", {}).get("phase") == "Running"
    and any(
        condition.get("type") == "Ready" and condition.get("status") == "True"
        for condition in pod.get("status", {}).get("conditions", [])
    )
    for value in (
        [pod.get("status", {}).get("podIP", "")]
        + [entry.get("ip", "") for entry in pod.get("status", {}).get("podIPs", [])]
    )
    if value
})))
')

python3 - "$nodes_file" "$KUBE_API_SERVER" "$KUBE_API_ADDRESSES" \
  "$kubernetes_service_ips" "$metrics_server_ips" "$prometheus_ips" >"$inventory_file" <<'PY'
import ipaddress
import json
import socket
import sys
from urllib.parse import urlsplit

nodes_path, api_url, configured_addresses, service_ip_values, metrics_values, prometheus_values = sys.argv[1:]
endpoint = urlsplit(api_url)
if endpoint.scheme != "https" or not endpoint.hostname:
    raise SystemExit("KUBE_API_SERVER must be an HTTPS origin")
api_port = endpoint.port or 443

with open(nodes_path, encoding="utf-8") as source:
    nodes = json.load(source)

pod_cidrs = set()
node_addresses = set()
for node in nodes.get("items", []):
    spec = node.get("spec", {})
    pod_cidrs.update(spec.get("podCIDRs") or ([spec["podCIDR"]] if spec.get("podCIDR") else []))
    for address in node.get("status", {}).get("addresses", []):
        if address.get("type") in {"InternalIP", "ExternalIP"}:
            node_addresses.add(address.get("address", ""))

if configured_addresses:
    api_addresses = {value.strip() for value in configured_addresses.split(",") if value.strip()}
else:
    api_addresses = {
        result[4][0]
        for result in socket.getaddrinfo(endpoint.hostname, api_port, type=socket.SOCK_STREAM)
    }

cidrs = [ipaddress.ip_network(value, strict=False) for value in pod_cidrs]
node_ips = {ipaddress.ip_address(value) for value in node_addresses}
api_ips = {ipaddress.ip_address(value) for value in api_addresses}
service_ips = {
    ipaddress.ip_address(value)
    for value in service_ip_values.split(",")
    if value
}
metrics_ips = {
    ipaddress.ip_address(value)
    for value in metrics_values.split(",")
    if value
}
prometheus_ips = {
    ipaddress.ip_address(value)
    for value in prometheus_values.split(",")
    if value
}
if not cidrs or not node_ips or not api_ips or not service_ips or not metrics_ips or not prometheus_ips:
    raise SystemExit("Pod CIDRs, node/API addresses and trusted telemetry Pods must all resolve")

records = set()
for cidr in cidrs:
    family = "4" if cidr.version == 4 else "6"
    family_metrics = ",".join(
        sorted(str(address) for address in metrics_ips if address.version == cidr.version)
    )
    family_prometheus = ",".join(
        sorted(str(address) for address in prometheus_ips if address.version == cidr.version)
    )
    if any(address.version == cidr.version for address in node_ips) and (
        not family_metrics or not family_prometheus
    ):
        raise SystemExit(f"Trusted telemetry Pod IPs are missing for IPv{cidr.version}")
    family_service_ips = sorted(
        str(address) for address in service_ips if address.version == cidr.version
    )
    service_ip = family_service_ips[0] if family_service_ips else "-"
    for address in node_ips:
        if address.version == cidr.version:
            records.add((family, str(cidr), str(address), "node", str(api_port), service_ip, family_metrics, family_prometheus))
    for address in api_ips:
        if address.version == cidr.version and address not in node_ips:
            records.add((family, str(cidr), str(address), "api", str(api_port), "-", family_metrics, family_prometheus))

if not records:
    raise SystemExit("No same-family Pod CIDR and node/API address pairs were found")
for record in sorted(records):
    print("|".join(record))
PY

manage_family() {
  family=$1
  firewall=$2
  family_inventory="$TEST_ROOT/inventory-$family"
  awk -F'|' -v family="$family" '$1 == family' "$inventory_file" >"$family_inventory"
  if [ ! -s "$family_inventory" ]; then
    return
  fi

  a_input=false
  a_forward=false
  b_input=false
  b_forward=false
  if "$firewall" -w -C INPUT -j "$CHAIN_A" 2>/dev/null; then a_input=true; fi
  if "$firewall" -w -C FORWARD -j "$CHAIN_A" 2>/dev/null; then a_forward=true; fi
  if "$firewall" -w -C INPUT -j "$CHAIN_B" 2>/dev/null; then b_input=true; fi
  if "$firewall" -w -C FORWARD -j "$CHAIN_B" 2>/dev/null; then b_forward=true; fi
  if [ "$a_input" != "$a_forward" ] || [ "$b_input" != "$b_forward" ]; then
    echo "Partial Mnema host-boundary hooks exist for IPv${family}; refusing mutation" >&2
    exit 1
  fi
  if [ "$a_input" = true ] && [ "$b_input" = true ]; then
    echo "Both Mnema host-boundary chains are active for IPv${family}; refusing mutation" >&2
    exit 1
  fi

  old_chain=
  if [ "$a_input" = true ]; then
    old_chain=$CHAIN_A
  elif [ "$b_input" = true ]; then
    old_chain=$CHAIN_B
  fi

  if [ "$MODE" = apply ]; then
    if [ "$old_chain" = "$CHAIN_A" ]; then
      CHAIN=$CHAIN_B
    else
      CHAIN=$CHAIN_A
    fi
    "$firewall" -w -N "$CHAIN" 2>/dev/null || true
    "$firewall" -w -F "$CHAIN"
    while IFS='|' read -r _family source target kind api_port service_ip metrics_ips prometheus_ips; do
      if [ "$kind" = api ]; then
        "$firewall" -w -A "$CHAIN" -s "$source" -d "$target" \
          -p tcp --dport "$api_port" -j REJECT
      else
        old_ifs=$IFS
        IFS=,
        for metrics_ip in $metrics_ips; do
          [ -n "$metrics_ip" ] || continue
          case "$family" in
            4) metrics_source="$metrics_ip/32" ;;
            6) metrics_source="$metrics_ip/128" ;;
          esac
          "$firewall" -w -A "$CHAIN" -s "$metrics_source" -d "$target" \
            -p tcp --dport 10250 -j RETURN
        done
        IFS=$old_ifs
        old_ifs=$IFS
        IFS=,
        for prometheus_ip in $prometheus_ips; do
          [ -n "$prometheus_ip" ] || continue
          case "$family" in
            4) prometheus_source="$prometheus_ip/32" ;;
            6) prometheus_source="$prometheus_ip/128" ;;
          esac
          "$firewall" -w -A "$CHAIN" -s "$prometheus_source" -d "$target" \
            -p tcp -m multiport --dports 9100,10250 -j RETURN
        done
        IFS=$old_ifs
        if [ "$service_ip" != - ]; then
          "$firewall" -w -A "$CHAIN" -s "$source" -d "$target" \
            -p tcp --dport 6443 -m conntrack \
            --ctorigdst "$service_ip" --ctorigdstport 443 -j RETURN
        fi
        "$firewall" -w -A "$CHAIN" -s "$source" -d "$target" \
          -p tcp -m multiport --dports 80,443 -j RETURN
        "$firewall" -w -A "$CHAIN" -s "$source" -d "$target" -j REJECT
      fi
    done <"$family_inventory"
    "$firewall" -w -A "$CHAIN" -j RETURN

    rollback_new_chain() {
      "$firewall" -w -D INPUT -j "$CHAIN" 2>/dev/null || true
      "$firewall" -w -D FORWARD -j "$CHAIN" 2>/dev/null || true
      "$firewall" -w -F "$CHAIN" 2>/dev/null || true
      "$firewall" -w -X "$CHAIN" 2>/dev/null || true
    }

    swap_started_at=$(date +%s)
    if ! "$firewall" -w -I INPUT 1 -j "$CHAIN"; then
      rollback_new_chain
      return 1
    fi
    if ! "$firewall" -w -I FORWARD 1 -j "$CHAIN"; then
      rollback_new_chain
      return 1
    fi
    if ! "$TELEMETRY_VERIFY" \
      --not-before-epoch "$swap_started_at" \
      --timeout-seconds "$TELEMETRY_VERIFY_TIMEOUT_SECONDS"; then
      rollback_new_chain
      echo "Fresh production telemetry failed after IPv${family} activation; restored prior hooks" >&2
      return 1
    fi
    if [ -n "$old_chain" ]; then
      "$firewall" -w -D INPUT -j "$old_chain"
      "$firewall" -w -D FORWARD -j "$old_chain"
      "$firewall" -w -F "$old_chain"
      "$firewall" -w -X "$old_chain"
    fi
  else
    if [ "$a_input" = true ]; then
      CHAIN=$CHAIN_A
    elif [ "$b_input" = true ]; then
      CHAIN=$CHAIN_B
    else
      echo "No complete active Mnema host-boundary chain for IPv${family}" >&2
      exit 1
    fi
    "$firewall" -w -L "$CHAIN" >/dev/null
    while IFS='|' read -r _family source target kind api_port service_ip metrics_ips prometheus_ips; do
      if [ "$kind" = api ]; then
        "$firewall" -w -C "$CHAIN" -s "$source" -d "$target" \
          -p tcp --dport "$api_port" -j REJECT
      else
        old_ifs=$IFS
        IFS=,
        for metrics_ip in $metrics_ips; do
          [ -n "$metrics_ip" ] || continue
          case "$family" in
            4) metrics_source="$metrics_ip/32" ;;
            6) metrics_source="$metrics_ip/128" ;;
          esac
          "$firewall" -w -C "$CHAIN" -s "$metrics_source" -d "$target" \
            -p tcp --dport 10250 -j RETURN
        done
        IFS=$old_ifs
        old_ifs=$IFS
        IFS=,
        for prometheus_ip in $prometheus_ips; do
          [ -n "$prometheus_ip" ] || continue
          case "$family" in
            4) prometheus_source="$prometheus_ip/32" ;;
            6) prometheus_source="$prometheus_ip/128" ;;
          esac
          "$firewall" -w -C "$CHAIN" -s "$prometheus_source" -d "$target" \
            -p tcp -m multiport --dports 9100,10250 -j RETURN
        done
        IFS=$old_ifs
        if [ "$service_ip" != - ]; then
          "$firewall" -w -C "$CHAIN" -s "$source" -d "$target" \
            -p tcp --dport 6443 -m conntrack \
            --ctorigdst "$service_ip" --ctorigdstport 443 -j RETURN
        fi
        "$firewall" -w -C "$CHAIN" -s "$source" -d "$target" \
          -p tcp -m multiport --dports 80,443 -j RETURN
        "$firewall" -w -C "$CHAIN" -s "$source" -d "$target" -j REJECT
      fi
    done <"$family_inventory"
    "$firewall" -w -C "$CHAIN" -j RETURN
  fi
}

manage_family 4 iptables
manage_family 6 ip6tables
printf 'staging_host_firewall=%s\n' "$MODE"
