#!/bin/sh
# Host bootstrap only; never deploys application images or initializes a DB.
set -eu
case "${1:-preview}" in
  preview)
    printf '%s\n' 'Target: mnema, Ubuntu24.04, 135.106.175.30.' \
      'Install official stable Caddy; preserve apt default config and certificate storage.' \
      'Serve HTTPS 503 maintenance on mnema.app/auth.mnema.app/www.mnema.app only.' \
      'No application, DB, DNS, firewall, CI credential or keykomi mutation.' \
      'Rollback: stop caddy; preserve certificate storage and original Caddyfile.'
    exit 0 ;;
  --apply) ;;
  *) exit 2 ;;
esac
[ "$(id -u)" = 0 ] || { echo 'root required' >&2; exit 1; }
. /etc/os-release
[ "$ID" = ubuntu ] && [ "$VERSION_ID" = 24.04 ] || exit 1
ip -4 address show scope global | grep -Fq '135.106.175.30/' || exit 1
if command -v caddy >/dev/null 2>&1 || [ -e /etc/caddy ] || [ -L /etc/caddy ] || [ -e /var/lib/caddy ]; then
  echo 'existing origin requires a separate reviewed update' >&2; exit 1
fi
if ss -H -ltn | awk '{print $4}' | grep -Eq ':(80|443|2019)$'; then
  echo 'occupied origin/admin listener requires review' >&2; exit 1
fi
origin_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
[ -f "$origin_dir/Caddyfile.maintenance" ] || exit 1
for path in /usr/share/keyrings/caddy-stable-archive-keyring.gpg /etc/apt/sources.list.d/caddy-stable.list; do
  [ ! -e "$path" ] && [ ! -L "$path" ] || { echo 'existing repository configuration requires review' >&2; exit 1; }
done
# Official packages start a default service on installation; mask it first.
systemctl mask caddy.service
apt-get update -qq
DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends ca-certificates curl gnupg
public_tmp=$(mktemp -d /run/mnema-origin.XXXXXX)
trap 'rm -rf "$public_tmp"' EXIT HUP INT TERM
curl --fail --silent --show-error --proto '=https' --tlsv1.2 \
  https://dl.cloudsmith.io/public/caddy/stable/gpg.key -o "$public_tmp/key.asc"
gpg --batch --dearmor -o "$public_tmp/key.gpg" "$public_tmp/key.asc"
curl --fail --silent --show-error --proto '=https' --tlsv1.2 \
  https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt -o "$public_tmp/caddy.list"
install -o root -g root -m 0644 "$public_tmp/key.gpg" /usr/share/keyrings/caddy-stable-archive-keyring.gpg
install -o root -g root -m 0644 "$public_tmp/caddy.list" /etc/apt/sources.list.d/caddy-stable.list
apt-get update -qq
candidate=$(apt-cache policy caddy | sed -n 's/^  Candidate: //p')
case "$candidate" in 2.1[0-9].*) ;; *) echo 'unexpected Caddy stable version' >&2; exit 1 ;; esac
DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends "caddy=$candidate"
install -o root -g root -m 0644 /etc/caddy/Caddyfile /etc/caddy/Caddyfile.package-backup
install -o root -g root -m 0644 "$origin_dir/Caddyfile.maintenance" /etc/caddy/Caddyfile
caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
systemctl unmask caddy.service
systemctl enable --now caddy.service
systemctl is-active --quiet caddy.service
caddy version
printf '%s\n' 'Origin installed; public certificate and HTTPS503 checks are still required.'
