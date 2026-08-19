#!/bin/sh
set -eu

KUBE_API_SERVER=${KUBE_API_SERVER:?KUBE_API_SERVER is required}
SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH='' cd -- "$SCRIPT_DIR/.." && pwd)

if [ "$(id -u)" -ne 0 ]; then
  echo "The persistent host boundary installer must run as root on the k3s node" >&2
  exit 1
fi
if ! python3 - "$KUBE_API_SERVER" <<'PY'
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
    or (port is not None and not 1 <= port <= 65535)
):
    raise SystemExit(1)
PY
then
  echo "KUBE_API_SERVER must be a path-free HTTPS origin" >&2
  exit 1
fi

install -d -m 0755 /usr/local/libexec/mnema /etc/mnema
install -m 0755 "$SCRIPT_DIR/reconcile-staging-host-firewall.sh" \
  /usr/local/libexec/mnema/reconcile-staging-host-firewall.sh
install -m 0755 "$SCRIPT_DIR/verify-production-telemetry-boundary.py" \
  /usr/local/libexec/mnema/verify-production-telemetry-boundary.py
install -m 0644 \
  "$REPO_ROOT/deploy/systemd/mnema-staging-host-boundary.service" \
  /etc/systemd/system/mnema-staging-host-boundary.service
install -m 0644 \
  "$REPO_ROOT/deploy/systemd/mnema-staging-host-boundary.timer" \
  /etc/systemd/system/mnema-staging-host-boundary.timer

umask 077
environment_tmp=$(mktemp /etc/mnema/staging-host-boundary.env.XXXXXX)
trap 'rm -f "$environment_tmp"' EXIT HUP INT TERM
printf 'KUBE_API_SERVER=%s\n' "$KUBE_API_SERVER" >"$environment_tmp"
chmod 0600 "$environment_tmp"
mv "$environment_tmp" /etc/mnema/staging-host-boundary.env
trap - EXIT HUP INT TERM

systemctl daemon-reload
systemctl enable --now \
  mnema-staging-host-boundary.service \
  mnema-staging-host-boundary.timer
KUBE_API_SERVER="$KUBE_API_SERVER" MODE=check \
  /usr/local/libexec/mnema/reconcile-staging-host-firewall.sh >/dev/null
/usr/local/libexec/mnema/verify-production-telemetry-boundary.py >/dev/null
systemctl is-enabled --quiet mnema-staging-host-boundary.service
systemctl is-enabled --quiet mnema-staging-host-boundary.timer
systemctl is-active --quiet mnema-staging-host-boundary.timer
printf 'staging_host_firewall_install=ok\n'
