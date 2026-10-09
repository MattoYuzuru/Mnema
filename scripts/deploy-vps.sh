#!/usr/bin/env bash
# CI invokes the root dispatcher over a forced-command SSH key. It sends a
# candidate document, allowlisted application config or a fixed operation name; it never
# uploads host files or configuration structure.
#   deploy-vps.sh <vps-candidate.json>
#   deploy-vps.sh --operation status|verify|rollback
set -euo pipefail
umask 077

reject() { printf '::error::VPS deployment rejected before rollout: %s\n' "$1" >&2; exit 1; }

repository_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
mode=release
candidate=
operation=
case "${1:-}" in
  --operation)
    [[ $# -eq 2 && $2 =~ ^(status|verify|rollback)$ ]] || reject 'operation must be status, verify or rollback'
    mode=operation
    operation=$2
    ;;
  '') reject 'candidate file or --operation is required' ;;
  *)
    [[ $# -eq 1 ]] || reject 'exactly one candidate file is accepted'
    candidate=$1
    ;;
esac

[[ ${GITHUB_REF:-} == refs/heads/main ]] || reject 'only refs/heads/main may operate production'
[[ ${GITHUB_REPOSITORY:-} == MattoYuzuru/Mnema ]] || reject 'unexpected repository'
[[ ${MNEMA_DEPLOY_HOST:-} == 135.106.175.30 ]] || reject 'unexpected deployment host'
[[ ${MNEMA_DEPLOY_USER:-} == mnema-deploy ]] || reject 'unexpected deployment user'
[[ -n ${MNEMA_DEPLOY_SSH_KEY:-} && -n ${MNEMA_DEPLOY_KNOWN_HOSTS:-} ]] || reject 'deployment credentials missing'

release_sha=
if [[ $mode == release ]]; then
  [[ ${GITHUB_EVENT_NAME:-} == push || ${GITHUB_EVENT_NAME:-} == workflow_dispatch ]] || reject 'unexpected trigger'
  [[ -f $candidate && ! -L $candidate && $(wc -c < "$candidate") -le 16384 ]] || reject 'candidate file missing or too large'
  release_sha=$(python3 -I -c 'import json,sys; print(json.load(open(sys.argv[1]))["sha"])' "$candidate") \
    || reject 'candidate file is not valid'
  [[ $release_sha =~ ^[0-9a-f]{40}$ ]] || reject 'candidate sha is invalid'
  [[ $release_sha == "${GITHUB_SHA:-}" ]] || reject 'candidate is not this workflow commit'
  # This runs after Environment approval, so waiting for approval cannot admit an old main.
  current_main=$(gh api repos/MattoYuzuru/Mnema/git/ref/heads/main --jq .object.sha)
  [[ $current_main == "$release_sha" ]] || reject 'candidate is no longer the current main commit'
else
  [[ ${GITHUB_EVENT_NAME:-} == workflow_dispatch ]] || reject 'operations require a manual dispatch'
fi

credentials=$(mktemp -d "${RUNNER_TEMP:?}/mnema-vps-ssh.XXXXXXXX")
trap 'rm -rf -- "$credentials"' EXIT
trap 'exit 1' HUP INT TERM
printf '%s\n' "$MNEMA_DEPLOY_SSH_KEY" > "$credentials/key"
printf '%s\n' "$MNEMA_DEPLOY_KNOWN_HOSTS" > "$credentials/known_hosts"
unset MNEMA_DEPLOY_SSH_KEY MNEMA_DEPLOY_KNOWN_HOSTS GH_TOKEN
chmod 600 "$credentials/key" "$credentials/known_hosts"
ssh-keygen -F 135.106.175.30 -f "$credentials/known_hosts" >/dev/null || reject 'host key is not pinned'

options=(-F /dev/null -i "$credentials/key"
  -o BatchMode=yes -o IdentitiesOnly=yes -o StrictHostKeyChecking=yes
  -o "UserKnownHostsFile=$credentials/known_hosts" -o GlobalKnownHostsFile=/dev/null
  -o ControlMaster=no -o ControlPath=none -o ClearAllForwardings=yes
  -o RequestTTY=no -o ConnectTimeout=10 -o ConnectionAttempts=1
  -o ServerAliveInterval=10 -o ServerAliveCountMax=2)
target=mnema-deploy@135.106.175.30

remote() { # remote <command>; stdin closed
  ssh -n "${options[@]}" "$target" "$1" || { printf '::error::Remote operation "%s" failed.\n' "${1%% *}" >&2; return 1; }
}
remote_stdin() { # remote_stdin <command> <stdin-file>; the candidate travels on stdin
  ssh "${options[@]}" "$target" "$1" < "$2" || { printf '::error::Remote operation "%s" failed.\n' "${1%% *}" >&2; return 1; }
}

summarize() { # summarize <title> <json-file>...
  [[ -n ${GITHUB_STEP_SUMMARY:-} ]] || return 0
  python3 -I - "$candidate" "$@" >> "$GITHUB_STEP_SUMMARY" <<'PY' || true
import json, re, sys
candidate, title, *files = sys.argv[1:]
def load(path):
    try:
        return json.loads(open(path).read().strip().splitlines()[-1])
    except (OSError, ValueError, IndexError):
        return {}
def text(value):
    if isinstance(value, list) and all(isinstance(v, str) and re.fullmatch(r'[A-Z][A-Z0-9_]{0,63}', v) for v in value):
        return ', '.join(value) or 'none'
    return value if isinstance(value, (bool, int)) or (isinstance(value, str) and re.fullmatch(r'[0-9A-Za-z._:@/-]{1,120}', value)) else 'n/a'
print('### ' + title + '\n')
rows = []
if candidate:
    try:
        data = json.load(open(candidate))
        rows.append(('commit', text(data['sha'])))
        rows += [('image ' + name, text(image)) for name, image in sorted(data['images'].items())]
    except (OSError, ValueError, KeyError, AttributeError):
        pass
for path in files:
    for key, value in load(path).items():
        if key in ('backup', 'schema_changed', 'rollback_compatible', 'pruned_images', 'offsite', 'config_changed', 'readiness',
                   'images_match', 'pending', 'configured', 'app_config_names', 'pending_backup', 'verified_sha', 'previous_sha', 'rollout_state', 'newest_backup_age_hours'):
            rows.append((key, text(value)))
print('| Item | Value |\n| --- | --- |')
for key, value in rows:
    print('| ' + key + ' | ' + str(value) + ' |')
PY
}

if [[ $mode == operation ]]; then
  remote "$operation" | tee "$credentials/operation.json"
  summarize "Production $operation" "$credentials/operation.json"
  exit 0
fi

# 1. Host state and configuration drift. Reviewed host files are installed by the
# administrator before approval; CI only compares hashes and never uploads them.
remote status > "$credentials/status.json"
if ! drift=$(python3 -I - "$credentials/status.json" "$repository_root" <<'PY'
import hashlib, json, sys
status_path, root = sys.argv[1:]
files = {
    'compose.yaml': 'deploy/production/compose.yaml',
    'nginx.conf': 'deploy/production/nginx.conf',
    'Caddyfile': 'deploy/production/Caddyfile',
    'mnema-deploy': 'deploy/production/mnema-deploy.py',
    'mnema-local-backup': 'deploy/production/local-backup.py',
    'mnema-health': 'deploy/production/health-monitor.py',
    'mnema-health.service': 'deploy/production/mnema-health.service',
    'mnema-health.timer': 'deploy/production/mnema-health.timer',
    'mnema-local-backup.service': 'deploy/production/mnema-local-backup.service',
    'mnema-local-backup.timer': 'deploy/production/mnema-local-backup.timer',
    'mnema-deploy-ssh': 'deploy/production/mnema-deploy-ssh',
    'mnema-deploy.sudoers': 'deploy/production/mnema-deploy.sudoers',
    '60-mnema-deploy.conf': 'deploy/production/60-mnema-deploy.conf',
}
try:
    status = json.loads(open(status_path).read().strip().splitlines()[-1])
except (OSError, ValueError, IndexError):
    print('status-unreadable'); sys.exit(2)
if status.get('rollout_state') != 'no_pending_operation':
    print('pending-rollout'); sys.exit(3)
installed = status.get('config')
if not isinstance(installed, dict):
    print(' '.join(files)); sys.exit(4)
drifted = [name for name, path in files.items()
           if installed.get(name) != hashlib.sha256(open(root + '/' + path, 'rb').read()).hexdigest()]
if drifted:
    print(' '.join(drifted)); sys.exit(4)
PY
); then
  case "$drift" in
    pending-rollout) reject 'an uncertain rollout is pending; the administrator must reconcile it first' ;;
    status-unreadable) reject 'host status could not be read' ;;
    *) printf '::error::Host configuration drift: %s. The administrator must install the reviewed repository files over ssh before this deployment (docs/operations/production-delivery.md).\n' "$drift" >&2; exit 1 ;;
  esac
fi

# 2. Admit this exact candidate (stdin). A different image set for an already admitted SHA is
# refused by the dispatcher, so a rebuild can never replace what was approved.
remote_stdin "admit $release_sha" "$candidate" > "$credentials/admit.json"

# 3. Application configuration from the prod Environment secrets (explicit allowlist, stdin
# only, never printed). The object is always sent, possibly empty: the Environment is the
# single source of truth, so an unset secret removes its key. The dispatcher only stages
# it; it becomes app.env inside the deploy transaction.
python3 -I "$repository_root/scripts/render_app_config.py" \
  --keys "$repository_root/deploy/production/app-config.keys" --output "$credentials/app-config.json"
for name in $(compgen -e | grep '^PROD_' || true); do unset "$name"; done
remote_stdin configure "$credentials/app-config.json" > "$credentials/configure.json"
rm -f "$credentials/app-config.json"

# 4. Deploy it, 5. verify the live state.
outcome=0
remote "deploy $release_sha" | tee "$credentials/deploy.json" || outcome=1
if [[ $outcome -eq 0 ]]; then
  remote verify | tee "$credentials/verify.json" || outcome=1
fi
summarize "Production deployment" "$credentials/configure.json" "$credentials/deploy.json" "$credentials/verify.json"
exit "$outcome"
