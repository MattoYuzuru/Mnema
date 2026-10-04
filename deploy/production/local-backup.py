#!/usr/bin/python3 -I
"""On-host backups and isolated restore rehearsal; does not cover VPS loss."""
import datetime
import fcntl
import hashlib
import json
import os
from pathlib import Path
import stat
import subprocess
import sys
import time
import uuid

DIRECTORY = Path('/var/backups/mnema')
SOURCE = 'mnema-prod-postgres-1'
IMAGE = 'postgres:18@sha256:06cad38a5d9f5d24b4d83d86def30795d5e4b757fedbf5281172b576dedcd941'
ENV = {'PATH': '/usr/sbin:/usr/bin:/sbin:/bin', 'HOME': '/root', 'DOCKER_CONFIG': '/root/.docker'}


def protected(path):
    for item in (path, *path.parents):
        info = item.lstat()
        if stat.S_ISLNK(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o022:
            raise ValueError('unsafe backup path')


def docker(args, **kwargs):
    return subprocess.run(['/usr/bin/docker', *args], env=ENV, check=True,
        stderr=subprocess.PIPE, timeout=kwargs.pop('timeout', 600), **kwargs)


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


def restore(path, expected):
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
            IMAGE], stdout=subprocess.DEVNULL)
        created_container = True
        for _ in range(60):
            ready = subprocess.run(['/usr/bin/docker', 'exec', identifier, 'pg_isready', '-U', 'postgres', '-d', 'mnema'],
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


def backup(rehearse=False):
    DIRECTORY.mkdir(mode=0o700, exist_ok=True)
    protected(DIRECTORY)
    if DIRECTORY.stat().st_mode & 0o077:
        raise ValueError('backup directory requires mode 0700')
    actual = docker(['inspect', '--format', '{{.Config.Image}}', SOURCE], stdout=subprocess.PIPE).stdout.decode().strip()
    if actual != IMAGE:
        raise ValueError('source database image is not the reviewed pin')
    before = fingerprint(SOURCE) if rehearse else None
    identifier = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ') + '-' + uuid.uuid4().hex[:8]
    target = DIRECTORY / (identifier + '.dump')
    started = time.monotonic()
    with target.open('xb') as handle:
        docker(['exec', SOURCE, 'pg_dump', '--no-password', '-U', 'postgres', '-p', '15432', '-d', 'mnema', '-Fc'], stdout=handle)
        handle.flush()
        os.fsync(handle.fileno())
    target.chmod(0o600)
    evidence = {'schemaVersion': 1, 'backup': target.name, 'sha256': digest(target),
        'bytes': target.stat().st_size, 'databaseImage': IMAGE, 'location': 'same-RU-VPS', 'offsite': False}
    if rehearse:
        if fingerprint(SOURCE) != before:
            raise ValueError('source changed; rehearse in a quiet maintenance window')
        evidence['restore'] = restore(target, before)
        evidence['restore']['seconds'] = round(time.monotonic() - started, 2)
    with (DIRECTORY / (identifier + '.json')).open('x') as handle:
        json.dump(evidence, handle)
        handle.flush()
        os.fsync(handle.fileno())
    print(json.dumps({key: evidence[key] for key in ('backup', 'bytes', 'location', 'offsite')} |
        {'restoreVerified': rehearse, 'restore': evidence.get('restore')}))


def main():
    operation = sys.argv[1:] or ['preview']
    if operation == ['preview']:
        print('Root-only local dump; rehearse restores in an isolated network-none PostgreSQL18 container.')
        print('No remote bucket, retention deletion, production restore or host-loss recovery.')
        return
    if os.geteuid() != 0 or operation not in (['backup'], ['rehearse']):
        raise ValueError('unsupported backup operation')
    with open('/run/mnema-production.lock', 'w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        backup(operation == ['rehearse'])


if __name__ == '__main__':
    try:
        os.umask(0o077)
        main()
    except (OSError, ValueError, subprocess.SubprocessError):
        print('local backup/rehearsal failed; inspect privately, existing dumps retained', file=sys.stderr)
        sys.exit(1)
