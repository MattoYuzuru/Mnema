#!/bin/sh
# New mnema host only; no uninstall, data removal or Docker-group grant.
set -eu
case "${1:-preview}" in
  preview)
    printf '%s\n' 'Target: mnema Ubuntu24.04 135.106.175.30, new Docker installation.' \
      'Official stable apt repository; bounded local logs; Unix socket only.' \
      'Preserve Caddy/SSH/UFW/egress; no user receives Docker-group access.' \
      'Rollback: stop Docker/containerd, retain configuration/images/volumes.'
    exit 0 ;;
  --apply) ;;
  *) exit 2 ;;
esac
[ "$(id -u)" = 0 ] || exit 1
. /etc/os-release
[ "$ID" = ubuntu ] && [ "$VERSION_ID" = 24.04 ] || exit 1
ip -4 address show scope global | grep -Fq '135.106.175.30/' || exit 1
for path in /etc/docker /var/lib/docker /var/lib/containerd /etc/apt/keyrings/docker.asc /etc/apt/sources.list.d/docker.sources; do
  [ ! -e "$path" ] && [ ! -L "$path" ] || { echo 'existing runtime requires review' >&2; exit 1; }
done
for package in docker-ce docker-ce-cli docker.io containerd containerd.io runc podman-docker; do
  status=$(dpkg-query -W -f='${db:Status-Status}' "$package" 2>/dev/null || true)
  [ "$status" != installed ] || { echo 'existing runtime package requires review' >&2; exit 1; }
done
systemctl mask docker.service docker.socket containerd.service
apt-get update -qq
DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends ca-certificates curl python3-cryptography
install -d -o root -g root -m 0755 /etc/apt/keyrings
curl --fail --silent --show-error --proto '=https' --tlsv1.2 \
  https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
chmod 0644 /etc/apt/keyrings/docker.asc
cat > /etc/apt/sources.list.d/docker.sources <<EOF
Types: deb
URIs: https://download.docker.com/linux/ubuntu
Suites: noble
Components: stable
Architectures: $(dpkg --print-architecture)
Signed-By: /etc/apt/keyrings/docker.asc
EOF
apt-get update -qq
candidate=$(apt-cache policy docker-ce | sed -n 's/^  Candidate: //p')
case "$candidate" in 5:29.*-1~ubuntu.24.04~noble) ;; *) echo 'unexpected Docker stable version' >&2; exit 1 ;; esac
DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
  "docker-ce=$candidate" "docker-ce-cli=$candidate" containerd.io docker-buildx-plugin docker-compose-plugin
install -d -o root -g root -m 0755 /etc/docker
printf '%s\n' '{"log-driver":"local","log-opts":{"max-size":"10m","max-file":"3"},"live-restore":true}' > /etc/docker/daemon.json
chmod 0644 /etc/docker/daemon.json
dockerd --validate --config-file /etc/docker/daemon.json
systemctl unmask docker.service docker.socket containerd.service
systemctl enable --now containerd.service docker.service
systemctl is-active --quiet docker.service
docker version --format '{{.Server.Version}}'
docker compose version --short
printf '%s\n' 'Docker installed; no application or database was started.'
