#!/usr/bin/python3 -I
"""Root-owned deployment dispatcher; SSH callers cannot supply files or commands."""

import fcntl
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
import tempfile

ROOT = Path('/etc/mnema/production')
STATE = Path('/var/lib/mnema-release')
SHA = r'[0-9a-f]{40}'
SERVICES = ('frontend', 'identity-account', 'learning')
GATES = ('source_ci_verified', 'image_security_verified', 'backup_restore_verified',
         'data_boundary_approved', 'auth_guard_verified')


class Rejected(Exception):
    pass


def parse_command(value):
    if not re.fullmatch(r'(?:status|rollback|(?:preflight|deploy) ' + SHA + r')', value):
        raise Rejected('unsupported operation')
    return value.split(' ')


def protected(path):
    # Check every ancestor: a root-owned leaf in a writable parent is not trusted.
    for candidate in (path, *path.parents):
        info = candidate.lstat()
        if stat.S_ISLNK(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o022:
            raise Rejected('configuration ownership or permissions invalid')
    return path


def load_release(sha):
    if not re.fullmatch(SHA, sha):
        raise Rejected('invalid release identifier')
    path = protected(ROOT / 'releases' / (sha + '.json'))
    if path.stat().st_size > 8192:
        raise Rejected('release manifest too large')
    data = json.loads(path.read_text())
    if not isinstance(data, dict) or data.get('sha') != sha or not isinstance(data.get('images'), dict) or set(data['images']) != set(SERVICES):
        raise Rejected('release binding invalid')
    if any(data.get(gate) is not True for gate in GATES):
        raise Rejected('release acceptance incomplete')
    for service, image in data['images'].items():
        pattern = r'ghcr\.io/mattoyuzuru/mnema/' + re.escape(service) + r'@sha256:[0-9a-f]{64}'
        if not isinstance(image, str) or not re.fullmatch(pattern, image):
            raise Rejected('immutable image binding invalid')
    return data


def compose(data, operation):
    protected(ROOT / 'compose.yaml')
    secret = protected(ROOT / 'runtime.env')
    if secret.stat().st_mode & 0o077:
        raise Rejected('runtime secrets require mode 0600')
    # No caller-supplied environment, shell, path, Docker argument or writable YAML.
    env = {'PATH': '/usr/sbin:/usr/bin:/sbin:/bin', 'HOME': '/root',
           'DOCKER_CONFIG': '/root/.docker'}
    with tempfile.NamedTemporaryFile(mode='w', prefix='mnema-release-', dir='/run') as f:
        f.write('MNEMA_BUILD_ID=' + data['sha'] + '\n')
        for service, image in data['images'].items():
            f.write('MNEMA_' + service.upper().replace('-', '_') + '_IMAGE=' + image + '\n')
        f.flush()
        command = ['/usr/bin/docker', 'compose', '--project-name', 'mnema-prod',
                   '--env-file', str(ROOT / 'runtime.env'), '--env-file', f.name,
                   '--file', str(ROOT / 'compose.yaml')]
        if operation == 'preflight':
            command += ['config', '--quiet']
        else:
            command += ['up', '--detach', '--wait', '--wait-timeout', '180']
        result = subprocess.run(command, env=env, capture_output=True, timeout=240, cwd=ROOT)
        if result.returncode:
            # Docker errors may contain interpolated configuration; do not echo them.
            raise Rejected('compose operation failed; administrator inspection required')


def admitted(sha):
    data = json.loads(protected(ROOT / 'admitted.json').read_text())
    if not isinstance(data, dict) or data.get('sha') != sha:
        raise Rejected('candidate is not the currently admitted release')


def pending():
    path = STATE / 'pending.json'
    if not path.exists():
        return None
    return json.loads(protected(path).read_text())


def current():
    path = STATE / 'current.json'
    if not path.exists():
        return None
    return json.loads(protected(path).read_text())


def sync_directory(path):
    descriptor = os.open(path, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def write_state(name, value):
    STATE.mkdir(mode=0o700, parents=True, exist_ok=True)
    protected(STATE)
    sync_directory(STATE.parent)
    path = STATE / name
    with tempfile.NamedTemporaryFile(mode='w', dir=STATE, delete=False) as f:
        json.dump(value, f)
        f.flush()
        os.fsync(f.fileno())
        temporary = Path(f.name)
    os.replace(temporary, path)
    sync_directory(STATE)


def main():
    if os.geteuid() != 0 or len(sys.argv) != 2:
        raise Rejected('dispatcher requires its privileged single-argument entrypoint')
    operation, *arguments = parse_command(sys.argv[1])
    if operation == 'status':
        state = current()
        print(json.dumps({'target': 'mnema-prod', 'recorded_release': state is not None,
                          'verified_sha': state.get('sha') if state else None,
                          'live_state': 'not_probed',
                          'rollout_state': 'needs_admin_reconciliation' if pending() else 'no_pending_operation'}))
        return
    if pending():
        raise Rejected('uncertain rollout requires administrator reconciliation')
    if operation == 'deploy':
        admitted(arguments[0])
    if operation == 'rollback':
        state = current()
        if not state or not state.get('previous'):
            raise Rejected('no verified rollback release')
        data = load_release(state['previous'])
        if load_release(state['sha']).get('rollback_compatible') is not True:
            raise Rejected('rollback requires reviewed schema compatibility')
    else:
        data = load_release(arguments[0])
    if shutil.disk_usage('/').free < 10 * 1024 ** 3:
        raise Rejected('insufficient rollout disk reserve')
    compose(data, 'preflight')
    if operation == 'preflight':
        print(json.dumps({'target': 'mnema-prod', 'sha': data['sha'], 'preflight': 'passed'}))
        return
    previous = current()
    # Persist intent before any container/migration mutation. A timeout, failed
    # readiness, crash or recording failure blocks ordinary rollback/redeployment.
    write_state('pending.json', {'sha': data['sha'], 'baseline': previous.get('sha') if previous else None})
    compose(data, 'deploy')
    previous_sha = (previous.get('previous') if previous.get('sha') == data['sha'] else previous.get('sha')) if previous else None
    write_state('current.json', {'sha': data['sha'], 'previous': previous_sha})
    (STATE / 'pending.json').unlink()
    sync_directory(STATE)
    print(json.dumps({'target': 'mnema-prod', 'sha': data['sha'], 'readiness': 'passed',
                      'user_acceptance': 'separate required smoke'}))


if __name__ == '__main__':
    try:
        os.umask(0o077)
        with open('/run/mnema-production.lock', 'w') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            main()
    except (Rejected, OSError, ValueError, subprocess.TimeoutExpired):
        print('deployment operation rejected; no sensitive diagnostics emitted', file=sys.stderr)
        sys.exit(1)
