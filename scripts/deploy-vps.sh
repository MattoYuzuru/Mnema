#!/usr/bin/env bash
# CI can invoke the root-admitted release, never upload runtime configuration.
set -euo pipefail
umask 077

reject() { printf '%s\n' 'VPS deployment rejected before rollout.' >&2; exit 1; }

[[ ${GITHUB_EVENT_NAME:-} == workflow_dispatch ]] || reject
[[ ${GITHUB_REF:-} == refs/heads/main ]] || reject
[[ ${GITHUB_RUN_ATTEMPT:-} == 1 ]] || reject
[[ ${GITHUB_REPOSITORY:-} == MattoYuzuru/Mnema ]] || reject
[[ ${MNEMA_RELEASE_SHA:-} =~ ^[0-9a-f]{40}$ ]] || reject
[[ $MNEMA_RELEASE_SHA == "${GITHUB_SHA:-}" ]] || reject
[[ ${MNEMA_DEPLOY_HOST:-} == 135.106.175.30 ]] || reject
[[ ${MNEMA_DEPLOY_USER:-} == mnema-deploy ]] || reject
[[ -n ${MNEMA_DEPLOY_SSH_KEY:-} && -n ${MNEMA_DEPLOY_KNOWN_HOSTS:-} ]] || reject

# This runs after Environment protection, so approval waiting cannot admit old main.
current_main=$(gh api repos/MattoYuzuru/Mnema/git/ref/heads/main --jq .object.sha)
[[ $current_main == "$MNEMA_RELEASE_SHA" ]] || reject

credentials=$(mktemp -d "${RUNNER_TEMP:?}/mnema-vps-ssh.XXXXXXXX")
trap 'rm -rf -- "$credentials"' EXIT
trap 'exit 1' HUP INT TERM
printf '%s\n' "$MNEMA_DEPLOY_SSH_KEY" > "$credentials/key"
printf '%s\n' "$MNEMA_DEPLOY_KNOWN_HOSTS" > "$credentials/known_hosts"
unset MNEMA_DEPLOY_SSH_KEY MNEMA_DEPLOY_KNOWN_HOSTS GH_TOKEN
chmod 600 "$credentials/key" "$credentials/known_hosts"
ssh-keygen -F 135.106.175.30 -f "$credentials/known_hosts" >/dev/null || reject

options=(-F /dev/null -n -i "$credentials/key"
  -o BatchMode=yes -o IdentitiesOnly=yes -o StrictHostKeyChecking=yes
  -o "UserKnownHostsFile=$credentials/known_hosts" -o GlobalKnownHostsFile=/dev/null
  -o ControlMaster=no -o ControlPath=none -o ClearAllForwardings=yes
  -o RequestTTY=no -o ConnectTimeout=10 -o ConnectionAttempts=1
  -o ServerAliveInterval=10 -o ServerAliveCountMax=2)

ssh "${options[@]}" mnema-deploy@135.106.175.30 status
ssh "${options[@]}" mnema-deploy@135.106.175.30 "preflight $MNEMA_RELEASE_SHA"
ssh "${options[@]}" mnema-deploy@135.106.175.30 "deploy $MNEMA_RELEASE_SHA"
ssh "${options[@]}" mnema-deploy@135.106.175.30 status
# Public smoke and Caddy promotion are separate administrator acceptance steps.
