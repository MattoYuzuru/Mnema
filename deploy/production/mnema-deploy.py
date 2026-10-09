#!/usr/bin/python3 -I
"""Root-owned deployment dispatcher; SSH callers cannot supply files or commands."""

import datetime
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import select
import shutil
import signal
import stat
import subprocess
import sys
import tempfile
import time
import urllib.request

ROOT = Path('/etc/mnema/production')
STATE = Path('/var/lib/mnema-release')
LOCK = Path('/run/mnema-production.lock')
BACKUP_TOOL = Path('/usr/local/sbin/mnema-local-backup')
# Worst case of the deploy call alone (production-delivery.md has the full budget): lock 10 min,
# preflight 4, pin 2, schema 2x4, stop 4, backup 15.5, rollout 7, prune 4, offsite 5.5 => 60 min.
LOCK_WAIT_SECONDS = 10 * 60
LOCK_POLL_SECONDS = 5
BACKUP_TIMEOUT_SECONDS = 15 * 60
OFFSITE_TIMEOUT_SECONDS = 5 * 60
PRUNE_BUDGET_SECONDS = 120
TERMINATE_GRACE_SECONDS = 30
STDIN_TIMEOUT_SECONDS = 30
MAX_CANDIDATE_BYTES = 16 * 1024
MAX_CONFIG_BYTES = 32 * 1024
MAX_MANIFEST_BYTES = 16 * 1024
SHA = r'[0-9a-f]{40}'
SERVICES = ('frontend', 'identity-account', 'learning', 'postgres')
WRITERS = ('identity-account', 'learning')
SCHEMAS = ('app_identity', 'app_learning')
IMAGE_NAMESPACE = 'ghcr.io/mattoyuzuru/mnema/'
POSTGRES = 'mnema-prod-postgres-1'
BACKUP_NAME = r'[0-9]{8}T[0-9]{6}Z-[0-9a-f]{8}'
OFFSITE_DISABLED_EXIT = 3
CONTAINERS = {service: 'mnema-prod-' + service + '-1' for service in SERVICES}
READINESS = {
    'identity': 'http://127.0.0.1:18081/api/actuator/health/readiness',
    'learning': 'http://127.0.0.1:18082/api/actuator/health/readiness',
    'frontend': 'http://127.0.0.1:18080/index.html',
}
# Installed file -> status key. The CI drift check compares these hashes with the
# reviewed repository files, so host configuration is never uploaded by CI.
CONFIG_FILES = {
    'compose.yaml': ROOT / 'compose.yaml',
    'nginx.conf': ROOT / 'nginx.conf',
    'Caddyfile': Path('/etc/caddy/Caddyfile'),
    'mnema-deploy': Path('/usr/local/sbin/mnema-deploy'),
    'mnema-local-backup': Path('/usr/local/sbin/mnema-local-backup'),
    'mnema-health': Path('/usr/local/sbin/mnema-health'),
    'mnema-health.service': Path('/etc/systemd/system/mnema-health.service'),
    'mnema-health.timer': Path('/etc/systemd/system/mnema-health.timer'),
    'mnema-local-backup.service': Path('/etc/systemd/system/mnema-local-backup.service'),
    'mnema-local-backup.timer': Path('/etc/systemd/system/mnema-local-backup.timer'),
    'mnema-deploy-ssh': Path('/usr/local/bin/mnema-deploy-ssh'),
    'mnema-deploy.sudoers': Path('/etc/sudoers.d/mnema-deploy'),
    '60-mnema-deploy.conf': Path('/etc/ssh/sshd_config.d/60-mnema-deploy.conf'),
}
# Application configuration CI may set from GitHub Environment `prod` secrets
# (PROD_<NAME> -> <NAME>). Mirrored in app-config.keys for CI and docs; a test keeps both
# equal. Adding a name is a reviewed PR plus an administrator-installed dispatcher.
# Media keys (Yandex static access keys) are here; future AI keys belong here too, never as a pattern or wildcard.
APP_ENV_NAMES = (
    'TURNSTILE_SITE_KEY', 'TURNSTILE_SECRET_KEY',
    'GOOGLE_CLIENT_ID', 'GOOGLE_CLIENT_SECRET',
    'YANDEX_CLIENT_ID', 'YANDEX_CLIENT_SECRET',
    'GH_CLIENT_ID', 'GH_CLIENT_SECRET',
    'MNEMA_POSTBOX_ACCESS_KEY', 'MNEMA_POSTBOX_SECRET_KEY',
    'MNEMA_PROMO_HASH_SECRET', 'MNEMA_EXPERIMENT_SECRET',
    'MNEMA_EVENTS_OWNER_ACCOUNT_ID', 'MNEMA_IDENTITY_TURNSTILE_MODE',
    'LEARNING_MEDIA_UPLOAD_ACCESS_KEY', 'LEARNING_MEDIA_UPLOAD_SECRET_KEY',
    'MNEMA_AVATAR_ACCESS_KEY', 'MNEMA_AVATAR_SECRET_KEY',
)
TURNSTILE_MODES = ('blocked', 'required')
TURNSTILE_KEYS = ('TURNSTILE_SITE_KEY', 'TURNSTILE_SECRET_KEY')
MAX_CONFIG_VALUE = 4096
FORBIDDEN_VALUE_CHARACTERS = frozenset('"\'\\$`')
ENV = {'PATH': '/usr/sbin:/usr/bin:/sbin:/bin', 'HOME': '/root', 'DOCKER_CONFIG': '/root/.docker'}
CANDIDATE_KEYS = {'schemaVersion', 'sha', 'images', 'source', 'securityEvidenceSha256'}
SOURCE_KEYS = {'repository', 'commit', 'workflow', 'runId', 'runAttempt'}


class Rejected(Exception):
    """Static, value-free reason that is safe to show to the SSH caller."""


def emit(text, stream=None):
    """Write for the caller; a vanished SSH session must never abort a rollout."""
    try:
        print(text, file=stream or sys.stdout, flush=True)
    except (OSError, ValueError):
        pass


def output(value):
    emit(json.dumps(value))


def phase(name):
    emit('phase=' + name, sys.stderr)


def install_signal_handlers():
    # The caller (ssh, sudo, the CI job) can vanish mid-rollout. Survive hang-up and a
    # closed pipe; turn SIGTERM into SystemExit so recovery handlers run.
    signal.signal(signal.SIGHUP, signal.SIG_IGN)
    signal.signal(signal.SIGPIPE, signal.SIG_IGN)
    signal.signal(signal.SIGTERM, lambda signum, frame: sys.exit(143))


def parse_command(value):
    if not re.fullmatch(r'(?:status|verify|rollback|configure|(?:admit|preflight|deploy) ' + SHA + r')', value):
        raise Rejected('unsupported operation')
    return value.split(' ')


def protected(path):
    # Check every ancestor: a root-owned leaf in a writable parent is not trusted.
    for candidate in (path, *path.parents):
        info = candidate.lstat()
        if stat.S_ISLNK(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o022:
            raise Rejected('configuration ownership or permissions invalid')
    return path


def validate_images(images):
    if not isinstance(images, dict) or set(images) != set(SERVICES):
        raise Rejected('release binding invalid')
    for service, image in images.items():
        pattern = re.escape(IMAGE_NAMESPACE) + re.escape(service) + r'@sha256:[0-9a-f]{64}'
        if not isinstance(image, str) or not re.fullmatch(pattern, image):
            raise Rejected('immutable image binding invalid')
    return images


def load_release(sha):
    if not re.fullmatch(SHA, sha):
        raise Rejected('invalid release identifier')
    path = protected(ROOT / 'releases' / (sha + '.json'))
    if path.stat().st_size > MAX_MANIFEST_BYTES:
        raise Rejected('release manifest too large')
    data = json.loads(path.read_text())
    if not isinstance(data, dict) or data.get('sha') != sha:
        raise Rejected('release binding invalid')
    validate_images(data.get('images'))
    return data


def validate_candidate(raw, sha):
    """Strictly validate the CI candidate document received on stdin."""
    if len(raw) > MAX_CANDIDATE_BYTES:
        raise Rejected('candidate too large')
    try:
        data = json.loads(raw)
    except ValueError:
        raise Rejected('candidate is not valid JSON') from None
    if not isinstance(data, dict) or set(data) != CANDIDATE_KEYS:
        raise Rejected('candidate schema invalid')
    if data['schemaVersion'] != 1 or type(data['schemaVersion']) is not int:
        raise Rejected('candidate schema version unsupported')
    if data['sha'] != sha:
        raise Rejected('candidate does not match the requested release')
    validate_images(data['images'])
    source = data['source']
    if not isinstance(source, dict) or set(source) != SOURCE_KEYS:
        raise Rejected('candidate source invalid')
    if (source['repository'] != 'mattoyuzuru/mnema' or source['commit'] != sha
            or source['workflow'] != '.github/workflows/deploy.yaml'):
        raise Rejected('candidate source is not the reviewed repository workflow')
    for key in ('runId', 'runAttempt'):
        if type(source[key]) is not int or source[key] <= 0:
            raise Rejected('candidate build identity invalid')
    if not isinstance(data['securityEvidenceSha256'], str) or not re.fullmatch(r'[0-9a-f]{64}', data['securityEvidenceSha256']):
        raise Rejected('candidate evidence binding invalid')
    return {'sha': sha, 'images': data['images'], 'source': source}


def sync_directory(path):
    descriptor = os.open(path, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def atomic_replace(directory, name, text):
    """Durably replace directory/name with root-only content."""
    with tempfile.NamedTemporaryFile(mode='w', dir=directory, delete=False) as f:
        temporary = Path(f.name)
        try:
            os.fchmod(f.fileno(), 0o600)
            f.write(text)
            f.flush()
            os.fsync(f.fileno())
        except BaseException:
            temporary.unlink(missing_ok=True)
            raise
    try:
        os.replace(temporary, directory / name)
    except BaseException:
        temporary.unlink(missing_ok=True)
        raise
    sync_directory(directory)


def write_state(name, value):
    STATE.mkdir(mode=0o700, parents=True, exist_ok=True)
    protected(STATE)
    sync_directory(STATE.parent)
    atomic_replace(STATE, name, json.dumps(value))


def append_admission(entry):
    STATE.mkdir(mode=0o700, parents=True, exist_ok=True)
    protected(STATE)
    descriptor = os.open(STATE / 'admissions.jsonl', os.O_WRONLY | os.O_APPEND | os.O_CREAT, 0o600)
    try:
        os.write(descriptor, (json.dumps(entry, sort_keys=True) + '\n').encode())
        os.fsync(descriptor)
    finally:
        os.close(descriptor)
    sync_directory(STATE)


def now():
    return datetime.datetime.now(datetime.timezone.utc).isoformat(timespec='seconds')


def read_stdin(limit, timeout=STDIN_TIMEOUT_SECONDS, descriptor=0, clock=time.monotonic):
    """Bounded read (size cap and deadline); always performed before the production lock."""
    deadline = clock() + timeout
    chunks = []
    total = 0
    while total <= limit:
        remaining = deadline - clock()
        if remaining <= 0:
            raise Rejected('input timed out')
        ready, _, _ = select.select([descriptor], [], [], remaining)
        if not ready:
            raise Rejected('input timed out')
        chunk = os.read(descriptor, min(65536, limit + 1 - total))
        if not chunk:
            break
        chunks.append(chunk)
        total += len(chunk)
    return b''.join(chunks)


def admit(sha, raw):
    """Record a CI candidate as admitted. Authority is the protected prod Environment
    approval plus attestation re-verification in CI; the dispatcher validates form,
    namespace and digests but cannot re-check provenance itself."""
    manifest = validate_candidate(raw, sha)
    releases = ROOT / 'releases'
    protected(ROOT)
    releases.mkdir(mode=0o700, exist_ok=True)
    protected(releases)
    path = releases / (sha + '.json')
    if os.path.lexists(path):
        if load_release(sha)['images'] != manifest['images']:
            raise Rejected('release already admitted with different images')
    else:
        atomic_replace(releases, sha + '.json', json.dumps({**manifest, 'admitted_at': now()}))
    append_admission({'sha': sha, 'images': manifest['images'], 'runId': manifest['source']['runId'],
                      'time': now()})
    atomic_replace(ROOT, 'admitted.json', json.dumps({'sha': sha}))
    output({'target': 'mnema-prod', 'sha': sha, 'admitted': True})


def validate_entry(name, value):
    """One allowlisted name and its plain value ('' means absent); shared with the local sync tool."""
    if name not in APP_ENV_NAMES:
        raise Rejected('configuration name not allowed')
    if not isinstance(value, str):
        raise Rejected('configuration value for ' + name + ' must be a string')
    if value and (len(value) > MAX_CONFIG_VALUE or value[0] == '#'
                  or any(not 0x21 <= ord(c) <= 0x7e or c in FORBIDDEN_VALUE_CHARACTERS for c in value)):
        raise Rejected('configuration value for ' + name + ' is invalid')
    if name == 'MNEMA_IDENTITY_TURNSTILE_MODE' and value and value not in TURNSTILE_MODES:
        raise Rejected('configuration value for ' + name + ' must be blocked or required')
    return value


def validate_app_config(raw):
    """Allowlisted names and plain values only: nothing here can alter compose structure."""
    if len(raw) > MAX_CONFIG_BYTES:
        raise Rejected('configuration too large')
    try:
        data = json.loads(raw)
    except ValueError:
        raise Rejected('configuration is not valid JSON') from None
    if not isinstance(data, dict):
        raise Rejected('configuration must be an object')
    values = {name: value for name, value in ((n, validate_entry(n, v)) for n, v in data.items()) if value}
    mode = values.get('MNEMA_IDENTITY_TURNSTILE_MODE')
    if mode == 'required' and not all(key in values for key in TURNSTILE_KEYS):
        raise Rejected('Turnstile mode required needs both Turnstile keys')
    return values


def read_env_file(path):
    return protected(path).read_text() if path.exists() else ''


def configure(raw):
    """Stage the CI allowlist as app.env.next (root-only, atomic). It becomes app.env only
    inside a deploy/rollback, after the pending marker exists. Values are never printed."""
    values = validate_app_config(raw)
    protected(ROOT)
    atomic_replace(ROOT, 'app.env.next', ''.join(name + '=' + values[name] + '\n' for name in sorted(values)))
    names = sorted(values)
    output({'target': 'mnema-prod', 'configured': names,
            'names_sha256': hashlib.sha256('\n'.join(names).encode()).hexdigest()})


def app_config_names(name='app.env'):
    path = ROOT / name
    try:
        protected(path)
        return sorted(line.split('=', 1)[0] for line in path.read_text().splitlines() if '=' in line)
    except (FileNotFoundError, Rejected, OSError):
        return []


def docker(args, input=None, timeout=120):
    result = subprocess.run(['/usr/bin/docker', *args], env=ENV, capture_output=True,
                            input=input, timeout=timeout)
    if result.returncode:
        # Docker errors may contain interpolated configuration; do not echo them.
        raise Rejected('docker operation failed; administrator inspection required')
    return result.stdout.decode()


def compose(data, operation):
    protected(ROOT / 'compose.yaml')
    secret = protected(ROOT / 'runtime.env')
    if secret.stat().st_mode & 0o077:
        raise Rejected('runtime secrets require mode 0600')
    # Later env-files win: host-generated runtime.env, CI-delivered app.env, then the release.
    env_files = [ROOT / 'runtime.env']
    # Preflight validates the configuration the rollout is about to promote.
    app = ROOT / 'app.env.next' if operation == 'preflight' and (ROOT / 'app.env.next').exists() else ROOT / 'app.env'
    if app.exists():
        if protected(app).stat().st_mode & 0o077:
            raise Rejected('application configuration requires mode 0600')
        env_files.append(app)
    # No caller-supplied environment, shell, path, Docker argument or writable YAML.
    with tempfile.NamedTemporaryFile(mode='w', prefix='mnema-release-', dir='/run') as f:
        f.write('MNEMA_BUILD_ID=' + data['sha'] + '\n')
        for service, image in data['images'].items():
            f.write('MNEMA_' + service.upper().replace('-', '_') + '_IMAGE=' + image + '\n')
        f.flush()
        command = ['/usr/bin/docker', 'compose', '--project-name', 'mnema-prod']
        for env_file in (*env_files, Path(f.name)):
            command += ['--env-file', str(env_file)]
        command += ['--file', str(ROOT / 'compose.yaml')]
        timeout = 240
        if operation == 'preflight':
            command += ['config', '--quiet']
        elif operation == 'stop':
            command += ['stop', *WRITERS]
        else:
            command += ['up', '--detach', '--wait', '--wait-timeout', '300']
            timeout = 420
        result = subprocess.run(command, env=ENV, capture_output=True, timeout=timeout, cwd=ROOT)
        if result.returncode:
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


def psql(statement):
    return docker(['exec', '-i', POSTGRES, 'psql', '--no-password', '-U', 'postgres', '-p', '15432',
                   '-d', 'mnema', '-At', '--set', 'ON_ERROR_STOP=1'],
                  input=statement.encode(), timeout=60).strip()


def schema_fingerprint():
    """Migration-history identity per schema; equal before/after means no schema change."""
    result = {}
    for schema in SCHEMAS:
        table = schema + '.flyway_schema_history'
        if psql("SELECT to_regclass('" + table + "') IS NOT NULL;") != 't':
            result[schema] = None
            continue
        row = json.loads(psql(
            "SELECT json_build_array(count(*), coalesce(max(installed_rank), 0), coalesce(md5(string_agg("
            "coalesce(version, 'R:' || script) || ':' || coalesce(checksum::text, ''), ',' "
            "ORDER BY installed_rank)), '')) FROM " + table + ' WHERE success;'))
        result[schema] = {'count': row[0], 'max_rank': row[1], 'md5': row[2]}
    return result


def write_postgres_pin(image):
    atomic_replace(ROOT, 'postgres-image', image + '\n')


def align_postgres_pin(data):
    """The pin belongs to the release that is actually running, not to an older SHA."""
    if data is None:
        return
    running = docker(['inspect', '--format', '{{.Config.Image}}', POSTGRES]).strip()
    if running == data['images']['postgres']:
        write_postgres_pin(running)


def terminate_group(process, grace=TERMINATE_GRACE_SECONDS):
    """SIGTERM the child's whole session, wait for it to empty, then SIGKILL stragglers."""
    deadline = time.monotonic() + grace
    try:
        os.killpg(process.pid, signal.SIGTERM)
        while time.monotonic() < deadline:
            process.poll()  # reap the leader so only live descendants keep the group alive
            os.killpg(process.pid, 0)
            time.sleep(0.05)
        os.killpg(process.pid, signal.SIGKILL)
    except (ProcessLookupError, PermissionError):
        pass  # the group is gone (macOS reports EPERM for a zombie-only group)
    process.wait()


def run_bounded(command, timeout, grace=TERMINATE_GRACE_SECONDS):
    """Run in its own session; on timeout or interruption terminate every descendant."""
    process = subprocess.Popen(command, env=ENV, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                               stderr=subprocess.DEVNULL, start_new_session=True)
    try:
        stdout, _ = process.communicate(timeout=timeout)
    except BaseException:
        terminate_group(process, grace)
        process.stdout.close()
        raise
    return process.returncode, stdout


def check_backup_tool():
    """Static checks that must pass before any writer is stopped."""
    protected(BACKUP_TOOL)
    if not os.access(BACKUP_TOOL, os.X_OK):
        raise Rejected('backup tool is not executable')


def pre_deploy_backup():
    check_backup_tool()
    try:
        code, stdout = run_bounded([str(BACKUP_TOOL), 'pre-deploy'], BACKUP_TIMEOUT_SECONDS)
    except subprocess.TimeoutExpired:
        raise Rejected('pre-deploy backup timed out') from None
    except OSError:
        raise Rejected('pre-deploy backup tool could not be started') from None
    if code:
        raise Rejected('pre-deploy backup or restore rehearsal failed')
    try:
        name = json.loads(stdout.decode().strip().splitlines()[-1])['backup']
    except (ValueError, KeyError, IndexError, TypeError):
        raise Rejected('pre-deploy backup returned no verified backup name') from None
    if not isinstance(name, str) or not re.fullmatch(BACKUP_NAME + r'\.dump', name):
        raise Rejected('pre-deploy backup returned no verified backup name')
    return name


def offsite_upload(backup):
    """Best-effort offsite copy after the rollout; never affects the deployment result."""
    try:
        code, _ = run_bounded([str(BACKUP_TOOL), 'offsite', backup.removesuffix('.dump')], OFFSITE_TIMEOUT_SECONDS)
    except (subprocess.SubprocessError, OSError):
        return 'failed'
    return {0: 'uploaded', OFFSITE_DISABLED_EXIT: 'disabled'}.get(code, 'failed')


def newest_backup_age_hours():
    """The backup tool owns the definition of a complete backup."""
    try:
        check_backup_tool()
        code, stdout = run_bounded([str(BACKUP_TOOL), 'newest'], 30)
        modified = json.loads(stdout.decode().strip().splitlines()[-1])['modified'] if code == 0 else None
        return None if modified is None else round((time.time() - float(modified)) / 3600, 1)
    except (subprocess.SubprocessError, OSError, ValueError, KeyError, IndexError, TypeError, Rejected):
        return None


def try_release(sha):
    try:
        return load_release(sha) if sha else None
    except (Rejected, OSError, ValueError):
        return None


def prune_images(keep_images):
    """Remove local Mnema images outside the current/previous release; never volumes or others."""
    keep = {image.split('@', 1)[1] for image in keep_images}
    listing = docker(['image', 'ls', '--digests', '--no-trunc', '--format', '{{.Repository}} {{.Digest}}'],
                     timeout=60)
    removed = 0
    deadline = time.monotonic() + PRUNE_BUDGET_SECONDS
    for line in listing.splitlines():
        if time.monotonic() > deadline:
            break  # pruning is housekeeping; never let it stretch a release
        parts = line.split(' ')
        if len(parts) != 2:
            continue
        repository, digest = parts
        if not repository.startswith(IMAGE_NAMESPACE) or repository[len(IMAGE_NAMESPACE):] not in SERVICES:
            continue
        if not re.fullmatch(r'sha256:[0-9a-f]{64}', digest) or digest in keep:
            continue
        try:
            docker(['image', 'rm', repository + '@' + digest], timeout=30)
        except Rejected:
            continue  # in use or already gone: leave it
        removed += 1
    return removed


def promote_app_config(operation, state):
    """Make app.env part of the release transaction; call only after pending.json exists.

    deploy: app.env.next -> app.env, the old file kept as app.env.previous (identical content
    changes nothing). rollback: if the release being left changed the configuration, swap
    app.env.previous back in. Returns whether this release changed the configuration."""
    app, staged, previous = ROOT / 'app.env', ROOT / 'app.env.next', ROOT / 'app.env.previous'
    current_text = read_env_file(app)
    if operation == 'rollback':
        if state and state.get('config_changed') is True and previous.exists():
            restored = read_env_file(previous)
            atomic_replace(ROOT, 'app.env.previous', current_text)
            atomic_replace(ROOT, 'app.env', restored)
        return False
    if not staged.exists():
        return False
    staged_text = read_env_file(staged)
    if staged_text == current_text:
        staged.unlink()
        sync_directory(ROOT)
        return False
    atomic_replace(ROOT, 'app.env.previous', current_text)
    os.chmod(protected(staged), 0o600)
    os.replace(staged, app)
    sync_directory(ROOT)
    return True


def restart_current(current_data):
    """Best effort: bring the previously recorded release back after an aborted quiesce."""
    if current_data is None:
        return False
    try:
        compose(current_data, 'deploy')
        return True
    except Exception:
        return False


def mutate(data, state, operation):
    current_data = try_release(state.get('sha') if state else None)
    phase('schema-before')
    align_postgres_pin(current_data)
    before = schema_fingerprint()
    phase('quiesce-writers')
    try:
        # Everything from the first stopped container until the pending marker exists must
        # leave production running if it fails, including loss of the caller.
        compose(data, 'stop')
        phase('pre-deploy-backup')
        backup = pre_deploy_backup()
        # Persist intent before any container/migration mutation. A timeout, failed
        # readiness, crash or recording failure blocks ordinary rollback/redeployment.
        write_state('pending.json', {'sha': data['sha'], 'baseline': state.get('sha') if state else None,
                                     'operation': operation, 'backup': backup})
    except BaseException as error:
        restarted = restart_current(current_data)
        if restarted:
            (STATE / 'pending.json').unlink(missing_ok=True)
        if not isinstance(error, Exception):
            raise
        if restarted:
            raise Rejected('rollout aborted before migration; current release restarted, nothing migrated') from None
        if current_data is None:
            raise Rejected('rollout aborted; no recorded release can be restarted, applications are stopped') from None
        raise Rejected('rollout aborted and the current release did not restart') from None
    config_changed = promote_app_config(operation, state)
    phase('rollout')
    compose(data, 'deploy')
    phase('schema-after')
    after = schema_fingerprint()
    same_release = bool(state) and state.get('sha') == data['sha']
    if operation == 'rollback':
        # Rolling back again would roll forward onto the schema we just left.
        previous_sha = None
        compatible = before == after
    elif same_release:
        # A repeat must not forget the schema change that introduced this release.
        previous_sha = state.get('previous')
        before = state.get('schema_before', before)
        compatible = before == after and state.get('rollback_compatible') is True
    else:
        previous_sha = state.get('sha') if state else None
        compatible = before == after
    write_state('current.json', {'sha': data['sha'], 'previous': previous_sha, 'schema_before': before,
                                 'schema_after': after, 'rollback_compatible': compatible,
                                 'backup': backup, 'operation': operation, 'config_changed': config_changed})
    write_postgres_pin(data['images']['postgres'])
    (STATE / 'pending.json').unlink()
    sync_directory(STATE)
    phase('prune-images')
    try:
        keep = [image for release in (data, try_release(previous_sha)) if release
                for image in release['images'].values()]
        pruned = prune_images(keep)
    except (Rejected, OSError, subprocess.SubprocessError):
        pruned = 0
    phase('offsite-upload')
    offsite = offsite_upload(backup)
    output({'target': 'mnema-prod', 'sha': data['sha'], 'readiness': 'passed',
            'backup': backup, 'schema_changed': before != after,
            'rollback_compatible': compatible, 'pruned_images': pruned, 'offsite': offsite,
            'config_changed': config_changed})


def release(operation, sha):
    if pending():
        raise Rejected('uncertain rollout requires administrator reconciliation')
    state = current()
    if operation == 'deploy':
        admitted(sha)
    if operation == 'rollback':
        if not state or not state.get('previous'):
            raise Rejected('no verified rollback release')
        if state.get('rollback_compatible') is not True:
            raise Rejected('rollback requires reviewed schema compatibility')
        data = load_release(state['previous'])
    else:
        data = load_release(sha)
    if shutil.disk_usage('/').free < 10 * 1024 ** 3:
        raise Rejected('insufficient rollout disk reserve')
    phase('preflight')
    check_backup_tool()
    compose(data, 'preflight')
    if operation == 'preflight':
        output({'target': 'mnema-prod', 'sha': data['sha'], 'preflight': 'passed'})
        return
    mutate(data, state, operation)


def file_sha256(path):
    """sha256 of an installed file; null when absent, 'unprotected' when ownership is unsafe."""
    try:
        protected(path)
        with path.open('rb') as handle:
            return hashlib.file_digest(handle, 'sha256').hexdigest()
    except FileNotFoundError:
        return None
    except Rejected:
        return 'unprotected'


def status():
    state = current()
    marker = pending()
    try:
        admitted_sha = json.loads(protected(ROOT / 'admitted.json').read_text()).get('sha')
    except (Rejected, OSError, ValueError, AttributeError):
        admitted_sha = None
    output({'target': 'mnema-prod', 'recorded_release': state is not None,
            'verified_sha': state.get('sha') if state else None,
            'previous_sha': state.get('previous') if state else None,
            'rollback_compatible': state.get('rollback_compatible') if state else None,
            'admitted_sha': admitted_sha,
            'live_state': 'not_probed',
            'rollout_state': 'needs_admin_reconciliation' if marker else 'no_pending_operation',
            'pending_backup': marker.get('backup') if marker else None,
            'app_config_names': app_config_names(),
            'app_config_next_names': app_config_names('app.env.next'),
            'config': {name: file_sha256(path) for name, path in CONFIG_FILES.items()},
            'newest_backup_age_hours': newest_backup_age_hours()})


def readiness_ok(url):
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(url, timeout=5) as response:
            return response.status == 200
    except Exception:
        return False


def verify():
    state = current()
    if not state:
        raise Rejected('no recorded release to verify')
    data = load_release(state['sha'])
    images_match = True
    for service, container in CONTAINERS.items():
        try:
            actual = docker(['inspect', '--format', '{{.Config.Image}}', container]).strip()
        except Rejected:
            actual = None
        images_match = images_match and actual == data['images'][service]
    ready = {name: readiness_ok(url) for name, url in READINESS.items()}
    no_pending = pending() is None
    output({'target': 'mnema-prod', 'sha': data['sha'], 'images_match': images_match,
            'readiness': 'passed' if all(ready.values()) else 'failed',
            'pending': not no_pending})
    if not (images_match and all(ready.values()) and no_pending):
        raise Rejected('verification failed')


def main(payload=None):
    if os.geteuid() != 0 or len(sys.argv) != 2:
        raise Rejected('dispatcher requires its privileged single-argument entrypoint')
    operation, *arguments = parse_command(sys.argv[1])
    if operation == 'status':
        status()
    elif operation == 'verify':
        verify()
    elif operation == 'admit':
        admit(arguments[0], payload if payload is not None else read_stdin(MAX_CANDIDATE_BYTES))
    elif operation == 'configure':
        configure(payload if payload is not None else read_stdin(MAX_CONFIG_BYTES))
    else:
        release(operation, arguments[0] if arguments else None)


def acquire(lock, timeout=LOCK_WAIT_SECONDS, interval=LOCK_POLL_SECONDS,
            clock=time.monotonic, sleep=time.sleep):
    """Wait for the production lock (shared with the backup timer) instead of failing at once."""
    deadline = clock() + timeout
    while True:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            return
        except BlockingIOError:
            if clock() >= deadline:
                raise Rejected('another production operation holds the lock') from None
            sleep(interval)


def entrypoint():
    install_signal_handlers()
    try:
        os.umask(0o077)
        if os.geteuid() != 0 or len(sys.argv) != 2:
            raise Rejected('dispatcher requires its privileged single-argument entrypoint')
        operation = parse_command(sys.argv[1])[0]
        # Untrusted input is read, bounded and with a deadline, before taking the lock.
        payload = {'admit': MAX_CANDIDATE_BYTES, 'configure': MAX_CONFIG_BYTES}.get(operation)
        payload = read_stdin(payload) if payload else None
        with open(LOCK, 'w') as lock:
            acquire(lock)
            main(payload)
        return 0
    except Rejected as error:
        emit('deployment rejected: ' + str(error), sys.stderr)
    except Exception:
        emit('deployment operation failed; no sensitive diagnostics emitted', sys.stderr)
    return 1


if __name__ == '__main__':
    sys.exit(entrypoint())
