#!/usr/bin/python3 -I
"""On-host backups, isolated restore rehearsal and optional KMS-encrypted offsite copy."""
import datetime
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import stat
import subprocess
import sys
import tempfile
import time
import uuid

DIRECTORY = Path('/var/backups/mnema')
SOURCE = 'mnema-prod-postgres-1'
WRITERS = ('mnema-prod-identity-account-1', 'mnema-prod-learning-1')
ROOT = Path('/etc/mnema/production')
IMAGE_PIN = ROOT / 'postgres-image'
OFFSITE_ENV = ROOT / 'offsite.env'
RUN_DIRECTORY = '/run'
STATE = Path('/var/lib/mnema-release')
OFFSITE_SUCCESS = STATE / 'offsite-last-success'
LOCK = Path('/run/mnema-production.lock')
LOCK_WAIT_SECONDS = 10 * 60
KEEP_COMPLETE = 2
OFFSITE_DISABLED_EXIT = 3
NAME = re.compile(r'[0-9]{8}T[0-9]{6}Z-[0-9a-f]{8}')
ENV = {'PATH': '/usr/sbin:/usr/bin:/sbin:/bin', 'HOME': '/root', 'DOCKER_CONFIG': '/root/.docker'}
OFFSITE_KEYS = ('MNEMA_OFFSITE_ENDPOINT', 'MNEMA_OFFSITE_REGION', 'MNEMA_OFFSITE_BUCKET',
                'MNEMA_OFFSITE_PREFIX', 'MNEMA_OFFSITE_KMS_KEY_ID', 'MNEMA_OFFSITE_ACCESS_KEY_ID',
                'MNEMA_OFFSITE_SECRET_ACCESS_KEY')


def protected(path):
    for item in (path, *path.parents):
        info = item.lstat()
        if stat.S_ISLNK(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o022:
            raise ValueError('unsafe backup path')


def docker(args, **kwargs):
    return subprocess.run(['/usr/bin/docker', *args], env=ENV, check=True,
        stderr=subprocess.PIPE, timeout=kwargs.pop('timeout', 600), **kwargs)


def database_image():
    protected(IMAGE_PIN)
    if IMAGE_PIN.stat().st_size > 256:
        raise ValueError('database binding too large')
    image = IMAGE_PIN.read_text().strip()
    if not re.fullmatch(r'ghcr\.io/mattoyuzuru/mnema/postgres@sha256:[0-9a-f]{64}', image):
        raise ValueError('database requires an administrator-owned immutable release binding')
    return image


def sql(container, statement):
    return docker(['exec', '-i', container, 'psql', '--no-password', '-U', 'postgres',
        '-d', 'mnema', '-p', '15432' if container == SOURCE else '5432', '-At', '--set', 'ON_ERROR_STOP=1'], input=statement.encode(),
        stdout=subprocess.PIPE, timeout=60).stdout.decode().strip()


def fingerprint(container):
    tables = json.loads(sql(container, "SELECT coalesce(json_agg(ARRAY[schemaname,tablename] ORDER BY schemaname,tablename),'[]') FROM pg_tables WHERE schemaname IN ('app_identity','app_learning');"))
    result = {}
    for schema, table in tables:
        # Names come from pg_catalog; quote identifiers, never interpolate row data.
        name = '.'.join('"' + part.replace('"', '""') + '"' for part in (schema, table))
        value = sql(container, 'SELECT json_build_array(count(*),md5(coalesce(string_agg(md5(row_to_json(t)::text),\'\' ORDER BY md5(row_to_json(t)::text)),\'\'))) FROM ' + name + ' t;')
        result[schema + '.' + table] = json.loads(value)
    return result


def digest(path):
    with path.open('rb') as handle:
        return hashlib.file_digest(handle, 'sha256').hexdigest()


def restore(path, expected, image):
    identifier = 'mnema-restore-' + uuid.uuid4().hex
    volume = identifier + '-data'
    created_volume = False
    created_container = False
    try:
        docker(['volume', 'create', '--label', 'mnema.restore=ephemeral', volume], stdout=subprocess.DEVNULL)
        created_volume = True
        docker(['run', '--detach', '--pull', 'never', '--name', identifier,
            '--label', 'mnema.restore=ephemeral', '--network', 'none', '--memory', '3g',
            '--cpus', '2', '--pids-limit', '256', '-e', 'POSTGRES_DB=mnema',
            '-e', 'POSTGRES_HOST_AUTH_METHOD=trust', '--mount', 'type=volume,src=' + volume + ',dst=/var/lib/postgresql',
            image], stdout=subprocess.DEVNULL)
        created_container = True
        for _ in range(60):
            # The entrypoint's temporary init server has only a Unix socket;
            # require final TCP readiness to avoid its stop/restart interval.
            ready = subprocess.run(['/usr/bin/docker', 'exec', identifier, 'pg_isready', '-h', '127.0.0.1', '-p', '5432', '-U', 'postgres', '-d', 'mnema'],
                env=ENV, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=5)
            if ready.returncode == 0:
                break
            time.sleep(1)
        else:
            raise ValueError('isolated restore did not become ready')
        sql(identifier, 'CREATE ROLE app_identity NOSUPERUSER NOCREATEDB NOCREATEROLE; CREATE ROLE app_learning NOSUPERUSER NOCREATEDB NOCREATEROLE;')
        with path.open('rb') as handle:
            docker(['exec', '-i', identifier, 'pg_restore', '--exit-on-error', '--no-password',
                '-U', 'postgres', '-d', 'mnema'], stdin=handle, stdout=subprocess.DEVNULL)
        restored = fingerprint(identifier)
        if restored != expected:
            raise ValueError('restore reconciliation differs')
        # Confirm the two roles cannot cross the schema boundary after restore.
        denied = sql(identifier, "SELECT has_schema_privilege('app_identity','app_learning','USAGE') OR has_schema_privilege('app_learning','app_identity','USAGE');")
        if denied != 'f':
            raise ValueError('restored role isolation differs')
        return {'tables': len(restored), 'rows': sum(value[0] for value in restored.values()), 'roleIsolation': True}
    finally:
        # Only the unique resources created above are removed; no broad prune or production volume removal.
        if created_container:
            docker(['rm', '--force', identifier], stdout=subprocess.DEVNULL)
        if created_volume:
            docker(['volume', 'rm', volume], stdout=subprocess.DEVNULL)


def list_dump(path):
    """Cheap integrity check: pg_restore must be able to read the whole archive TOC."""
    with path.open('rb') as handle:
        docker(['exec', '-i', SOURCE, 'pg_restore', '--list'], stdin=handle, stdout=subprocess.DEVNULL)


def is_complete(meta):
    """A complete backup is a dump plus a metadata file carrying a verification marker."""
    try:
        info = json.loads(meta.read_text())
        dump = meta.with_suffix('.dump')
        verified = info.get('verified') in ('list', 'restore') or isinstance(info.get('restore'), dict)
        return bool(verified and NAME.fullmatch(meta.stem) and dump.stat().st_size == info.get('bytes'))
    except (OSError, ValueError, AttributeError):
        return False


def complete_backups():
    return sorted((meta for meta in DIRECTORY.glob('*.json') if is_complete(meta)), key=lambda meta: meta.name)


def newest():
    """The one definition of 'newest complete backup' for the dispatcher and health monitor."""
    for attempt in range(2):
        try:  # retention in a concurrent daily backup can remove a pair between scan and stat
            complete = [(meta.stat().st_mtime, meta) for meta in complete_backups()]
            break
        except FileNotFoundError:
            if attempt:
                return {'backup': None, 'modified': None}
    if not complete:
        return {'backup': None, 'modified': None}
    modified, meta = max(complete, key=lambda item: item[0])
    return {'backup': meta.stem, 'modified': modified}


def apply_retention():
    """Keep the newest complete pairs; touch nothing else, and run only after a verified backup."""
    for meta in complete_backups()[:-KEEP_COMPLETE]:
        # Remove the marker first so an interrupted deletion never leaves a "complete" pair.
        meta.unlink()
        meta.with_suffix('.dump').unlink(missing_ok=True)


def warn(message):
    print(message, file=sys.stderr)


def writers_stopped():
    for container in WRITERS:
        result = subprocess.run(['/usr/bin/docker', 'inspect', '--format', '{{.State.Running}}', container],
            env=ENV, capture_output=True, timeout=30)
        if result.returncode == 0 and result.stdout.decode().strip() != 'false':
            return False
    return True


def lock_held_elsewhere():
    with open(LOCK, 'w') as probe:
        try:
            fcntl.flock(probe, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            return True
    return False


def backup(kind='backup'):
    """kind: backup (daily, TOC check), rehearse (full restore) or pre-deploy (full restore, quiesced)."""
    DIRECTORY.mkdir(mode=0o700, exist_ok=True)
    protected(DIRECTORY)
    if DIRECTORY.stat().st_mode & 0o077:
        raise ValueError('backup directory requires mode 0700')
    if kind == 'pre-deploy' and not (lock_held_elsewhere() and writers_stopped()):
        raise ValueError('pre-deploy requires the dispatcher lock and stopped writers')
    actual = docker(['inspect', '--format', '{{.Config.Image}}', SOURCE], stdout=subprocess.PIPE).stdout.decode().strip()
    image = database_image()
    if actual != image:
        raise ValueError('source database image is not the reviewed pin')
    full = kind in ('rehearse', 'pre-deploy')
    before = fingerprint(SOURCE) if full else None
    identifier = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ') + '-' + uuid.uuid4().hex[:8]
    target = DIRECTORY / (identifier + '.dump')
    metadata = DIRECTORY / (identifier + '.json')
    temporary = DIRECTORY / (identifier + '.json.tmp')
    started = time.monotonic()
    handle = target.open('xb')  # fails before ownership if the name exists
    created = [target, temporary]
    try:
        with handle:
            docker(['exec', SOURCE, 'pg_dump', '--no-password', '-U', 'postgres', '-p', '15432', '-d', 'mnema', '-Fc'], stdout=handle)
            handle.flush()
            os.fsync(handle.fileno())
        target.chmod(0o600)
        evidence = {'schemaVersion': 1, 'kind': kind, 'backup': target.name, 'sha256': digest(target),
            'bytes': target.stat().st_size, 'databaseImage': image, 'location': 'same-RU-VPS', 'offsite': False}
        if full:
            if fingerprint(SOURCE) != before:
                raise ValueError('source changed; rehearse in a quiet maintenance window')
            evidence['restore'] = restore(target, before, image)
            evidence['restore']['seconds'] = round(time.monotonic() - started, 2)
            evidence['verified'] = 'restore'
        else:
            list_dump(target)
            evidence['verified'] = 'list'
        with temporary.open('x') as out:
            json.dump(evidence, out)
            out.flush()
            os.fsync(out.fileno())
        os.replace(temporary, metadata)  # the verification marker becomes visible atomically
        created = []
    except BaseException:
        # Remove only the files this run created; older backups stay untouched.
        for path in created:
            path.unlink(missing_ok=True)
        raise
    try:
        apply_retention()
    except OSError:
        warn('backup retention could not be applied; existing dumps retained')
    uploaded = False
    if kind != 'pre-deploy':  # the dispatcher uploads after the rollout so a slow network cannot hold writers down
        try:
            uploaded = offsite_upload(identifier) is not None
        except (OSError, ValueError, subprocess.SubprocessError):
            warn('offsite upload failed; local backup is intact')
    print(json.dumps({key: evidence[key] for key in ('backup', 'bytes', 'location', 'kind', 'verified')} |
        {'offsite': uploaded, 'restoreVerified': full, 'restore': evidence.get('restore')}))


def parse_offsite(text):
    """Strict KEY=VALUE parser for offsite.env; ValueError for anything malformed."""
    values = {}
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith('#'):
            continue
        match = re.fullmatch(r'([A-Z_]+)=(.*)', line)
        if not match or match.group(1) not in OFFSITE_KEYS or match.group(1) in values:
            raise ValueError('offsite configuration malformed')
        values[match.group(1)] = match.group(2)
    if set(values) != set(OFFSITE_KEYS):
        raise ValueError('offsite configuration incomplete')
    if not re.fullmatch(r'https://[a-z0-9.-]+(?::[0-9]{1,5})?', values['MNEMA_OFFSITE_ENDPOINT']):
        raise ValueError('offsite endpoint invalid')
    if not re.fullmatch(r'[a-z0-9-]{2,40}', values['MNEMA_OFFSITE_REGION']):
        raise ValueError('offsite region invalid')
    if not re.fullmatch(r'[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]', values['MNEMA_OFFSITE_BUCKET']):
        raise ValueError('offsite bucket invalid')
    prefix = values['MNEMA_OFFSITE_PREFIX'].strip('/')
    if not re.fullmatch(r'[A-Za-z0-9._-]+(?:/[A-Za-z0-9._-]+)*', prefix) or '..' in prefix:
        raise ValueError('offsite prefix invalid')
    if not re.fullmatch(r'[A-Za-z0-9_-]{8,64}', values['MNEMA_OFFSITE_KMS_KEY_ID']):
        raise ValueError('offsite KMS key invalid')
    for key in ('MNEMA_OFFSITE_ACCESS_KEY_ID', 'MNEMA_OFFSITE_SECRET_ACCESS_KEY'):
        if not re.fullmatch(r'[A-Za-z0-9/+=_.-]{8,200}', values[key]):
            raise ValueError('offsite credential invalid')
    return {**values, 'MNEMA_OFFSITE_PREFIX': prefix}


def offsite_config():
    """None when offsite is not configured; ValueError when it is configured badly."""
    if not OFFSITE_ENV.exists():
        return None
    protected(OFFSITE_ENV)
    if OFFSITE_ENV.stat().st_mode & 0o077 or OFFSITE_ENV.stat().st_size > 4096:
        raise ValueError('offsite configuration requires mode 0600')
    return parse_offsite(OFFSITE_ENV.read_text())


def object_key(config, name, suffix):
    # <prefix>/<backup>/<file>: the bucket's immutability rule matches <prefix>/*/*.
    return config['MNEMA_OFFSITE_PREFIX'] + '/' + name + '/' + name + suffix


def curl_command(config, credentials, source, key, payload_sha256):
    """Single PUT with the headers the bucket policy requires; credentials live in the -K file."""
    return ['/usr/bin/curl', '-q', '--fail', '--silent', '--show-error', '--proto', '=https', '--tlsv1.2',
        '--connect-timeout', '20', '--max-time', '600', '--speed-limit', '1024', '--speed-time', '60',
        '--noproxy', '*',
        '--output', '/dev/null', '--write-out', '%{http_code}',
        '--aws-sigv4', 'aws:amz:' + config['MNEMA_OFFSITE_REGION'] + ':s3', '-K', str(credentials),
        '-H', 'x-amz-content-sha256: ' + payload_sha256,
        '-H', 'x-amz-server-side-encryption: aws:kms',
        '-H', 'x-amz-server-side-encryption-aws-kms-key-id: ' + config['MNEMA_OFFSITE_KMS_KEY_ID'],
        '-H', 'x-amz-acl: private', '-H', 'If-None-Match: *',
        '--upload-file', str(source),
        config['MNEMA_OFFSITE_ENDPOINT'] + '/' + config['MNEMA_OFFSITE_BUCKET'] + '/' + key]


def put_object(config, source, key):
    """True when stored (or already stored: objects are immutable, so 412 is success)."""
    descriptor, name = tempfile.mkstemp(prefix='mnema-curl-', dir=RUN_DIRECTORY)
    credentials = Path(name)
    try:
        with os.fdopen(descriptor, 'w') as handle:
            handle.write('user = "' + config['MNEMA_OFFSITE_ACCESS_KEY_ID'] + ':' +
                         config['MNEMA_OFFSITE_SECRET_ACCESS_KEY'] + '"\n')
        result = subprocess.run(curl_command(config, credentials, source, key, digest(source)),
            env=ENV, capture_output=True, timeout=700)
    finally:
        credentials.unlink(missing_ok=True)
    if result.returncode == 0 or (result.returncode == 22 and result.stdout.decode().strip() == '412'):
        return True
    raise ValueError('offsite upload rejected')


def offsite_upload(name):
    """Upload dump and metadata with server-side KMS encryption. None when offsite is off."""
    config = offsite_config()
    if config is None:
        return None
    if not NAME.fullmatch(name):
        raise ValueError('backup name invalid')
    dump = DIRECTORY / (name + '.dump')
    metadata = DIRECTORY / (name + '.json')
    if not is_complete(metadata):
        raise ValueError('only a complete backup can be uploaded')
    put_object(config, dump, object_key(config, name, '.dump'))
    put_object(config, metadata, object_key(config, name, '.json'))
    STATE.mkdir(mode=0o700, parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile('w', dir=STATE, delete=False) as handle:
        handle.write(datetime.datetime.now(datetime.timezone.utc).isoformat(timespec='seconds') + '\n')
        handle.flush()
        os.fsync(handle.fileno())
        marker = Path(handle.name)
    os.replace(marker, OFFSITE_SUCCESS)
    return name


def acquire(lock, timeout=LOCK_WAIT_SECONDS, interval=5, clock=time.monotonic, sleep=time.sleep):
    deadline = clock() + timeout
    while True:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            return
        except BlockingIOError:
            if clock() >= deadline:
                raise ValueError('production lock is busy')
            sleep(interval)


def main():
    operation = sys.argv[1:] or ['preview']
    if operation == ['preview']:
        print('Root-only local dump (retention: two newest verified); full restore rehearsal in an isolated network-none PostgreSQL18 container.')
        print('Optional offsite copy (server-side KMS encryption, write-once) when offsite.env exists; no production restore or volume removal.')
        return
    if os.geteuid() != 0 or not (operation in (['backup'], ['rehearse'], ['pre-deploy'], ['newest']) or
            (len(operation) == 2 and operation[0] == 'offsite' and NAME.fullmatch(operation[1]))):
        raise ValueError('unsupported backup operation')
    if operation == ['newest']:
        print(json.dumps(newest()))
        return
    if operation[0] == 'offsite':
        # No lock: the dispatcher calls this while holding it, and uploads only read complete files.
        if offsite_upload(operation[1]) is None:
            print('offsite is not configured', file=sys.stderr)
            sys.exit(OFFSITE_DISABLED_EXIT)
        print(json.dumps({'backup': operation[1], 'offsite': True}))
        return
    if operation == ['pre-deploy']:
        # Only the deployment dispatcher calls this, already holding the production lock.
        backup('pre-deploy')
        return
    with open(LOCK, 'w') as lock:
        acquire(lock)
        backup(operation[0])


def terminate(signum, frame):
    # Run finally-blocks: restore container/volume, partial dump, .tmp and curl credentials.
    sys.exit(128 + signum)


if __name__ == '__main__':
    try:
        os.umask(0o077)
        signal.signal(signal.SIGTERM, terminate)
        main()
    except (OSError, ValueError, subprocess.SubprocessError):
        print('local backup/rehearsal failed; inspect privately, existing dumps retained', file=sys.stderr)
        sys.exit(1)
