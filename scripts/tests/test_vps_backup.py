"""Backup retention, offsite upload and a real pinned PostgreSQL dump/restore reconciliation."""
import contextlib
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time
import types
import unittest
import uuid
from unittest.mock import patch

from test_vps_runtime import load, ROOT

BACKUP = load('vps_backup', 'deploy/production/local-backup.py')
HEALTH = load('vps_health_freshness', 'deploy/production/health-monitor.py')
FIXTURE_IMAGE = 'mnema-vps-postgres-fixture:verified'
PIN = 'ghcr.io/mattoyuzuru/mnema/postgres@sha256:' + 'a' * 64
ACCESS = 'AKIDEXAMPLE0001'
SECRET = 'secret/EXAMPLE+key=value0002'
OFFSITE = '\n'.join((
    '# uploader credentials are write-only',
    'MNEMA_OFFSITE_ENDPOINT=https://storage.yandexcloud.net',
    'MNEMA_OFFSITE_REGION=ru-central1',
    'MNEMA_OFFSITE_BUCKET=mnema-backups',
    'MNEMA_OFFSITE_PREFIX=mnema-vps/postgres/',
    'MNEMA_OFFSITE_KMS_KEY_ID=abjkmsexample0001',
    'MNEMA_OFFSITE_ACCESS_KEY_ID=' + ACCESS,
    'MNEMA_OFFSITE_SECRET_ACCESS_KEY=' + SECRET,
))


def name(index):
    return '2026100%dT010203Z-abcdef%02d' % (index, index)


def add_backup(directory, index, verified='list', size=4):
    (directory / (name(index) + '.dump')).write_bytes(b'd' * size)
    meta = {'bytes': size}
    if verified:
        meta['verified'] = verified
    (directory / (name(index) + '.json')).write_text(json.dumps(meta))


class BackupUnitTest(unittest.TestCase):
    def setUp(self):
        self.directory = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.directory, ignore_errors=True)
        patcher = patch.object(BACKUP, 'DIRECTORY', self.directory)
        patcher.start()
        self.addCleanup(patcher.stop)

    # --- retention -------------------------------------------------------------------

    def test_retention_keeps_exactly_the_two_newest_complete_pairs_and_touches_nothing_else(self):
        for index in (1, 2, 3, 4):
            add_backup(self.directory, index)
        add_backup(self.directory, 5, verified=None)                 # incomplete: no verification marker
        (self.directory / 'operator-notes.txt').write_text('keep')
        (self.directory / (name(6) + '.dump')).write_bytes(b'orphan')  # dump without metadata
        add_backup(self.directory, 7, size=4)
        (self.directory / (name(7) + '.dump')).write_bytes(b'truncated-longer')  # size mismatch: incomplete
        BACKUP.apply_retention()
        remaining = sorted(path.name for path in self.directory.iterdir())
        self.assertEqual(remaining, sorted([
            name(3) + '.dump', name(3) + '.json', name(4) + '.dump', name(4) + '.json',
            name(5) + '.dump', name(5) + '.json', name(6) + '.dump', name(7) + '.dump', name(7) + '.json',
            'operator-notes.txt']))

    def test_completeness_requires_marker_matching_size_and_a_valid_name(self):
        add_backup(self.directory, 1, verified='restore')
        add_backup(self.directory, 2, verified='list')
        add_backup(self.directory, 3, verified=None)
        add_backup(self.directory, 4, verified='unknown')
        legacy = self.directory / (name(5) + '.json')
        (self.directory / (name(5) + '.dump')).write_bytes(b'dddd')
        legacy.write_text(json.dumps({'bytes': 4, 'restore': {'rows': 1}}))   # pre-retention rehearsal record
        self.assertEqual([m.name for m in BACKUP.complete_backups()],
                         [name(1) + '.json', name(2) + '.json', name(5) + '.json'])

    def run_backup(self, kind='backup', fail_dump=False, fail_list=False):
        calls = []

        def docker(args, **kwargs):
            calls.append(args)
            if args[0] == 'inspect':
                return types.SimpleNamespace(stdout=(PIN + '\n').encode())
            if 'pg_dump' in args:
                kwargs['stdout'].write(b'PGDMP-partial')
                if fail_dump:
                    raise subprocess.CalledProcessError(1, args)
            if 'pg_restore' in args and fail_list:
                raise subprocess.CalledProcessError(1, args)
            return types.SimpleNamespace(stdout=b'')

        output = io.StringIO()
        with patch.object(BACKUP, 'docker', side_effect=docker), patch.object(BACKUP, 'protected'), \
             patch.object(BACKUP, 'database_image', return_value=PIN), \
             patch.object(BACKUP, 'fingerprint', return_value={'t': [1, 'x']}), \
             patch.object(BACKUP, 'restore', return_value={'tables': 1, 'rows': 1, 'roleIsolation': True}) as restore, \
             patch.object(BACKUP, 'offsite_upload', return_value=None), \
             contextlib.redirect_stdout(output), contextlib.redirect_stderr(io.StringIO()):
            BACKUP.backup(kind)
        return output.getvalue(), calls, restore

    def test_daily_backup_is_checked_with_a_toc_listing_and_applies_retention_after_it(self):
        add_backup(self.directory, 1)
        add_backup(self.directory, 2)
        text, calls, restore = self.run_backup('backup')
        restore.assert_not_called()
        self.assertTrue(any('pg_restore' in c and '--list' in c for c in calls))
        info = json.loads(text)
        self.assertEqual((info['kind'], info['verified'], info['restoreVerified'], info['offsite']),
                         ('backup', 'list', False, False))
        self.assertEqual(len(BACKUP.complete_backups()), 2)
        self.assertFalse((self.directory / (name(1) + '.dump')).exists())
        self.assertTrue((self.directory / (name(2) + '.dump')).exists())
        newest = json.loads(next(m for m in BACKUP.complete_backups() if m.name != name(2) + '.json').read_text())
        self.assertEqual(newest['verified'], 'list')

    def test_rehearsal_records_a_restore_marker(self):
        text, _, restore = self.run_backup('rehearse')
        restore.assert_called_once()
        self.assertEqual(json.loads(text)['verified'], 'restore')

    def test_failed_dump_or_listing_removes_only_the_partial_files_and_never_runs_retention(self):
        add_backup(self.directory, 1)
        add_backup(self.directory, 2)
        before = sorted(path.name for path in self.directory.iterdir())
        for options in ({'fail_dump': True}, {'fail_list': True}):
            with self.subTest(options=options), patch.object(BACKUP, 'apply_retention') as retention, \
                 self.assertRaises(subprocess.CalledProcessError):
                self.run_backup('backup', **options)
            retention.assert_not_called()
            self.assertEqual(sorted(path.name for path in self.directory.iterdir()), before)

    def test_failed_rehearsal_removes_its_dump_and_keeps_older_backups(self):
        add_backup(self.directory, 1)
        before = sorted(path.name for path in self.directory.iterdir())
        with patch.object(BACKUP, 'restore', side_effect=ValueError('restore reconciliation differs')):
            with self.assertRaises(ValueError), patch.object(BACKUP, 'docker', side_effect=lambda a, **k: (
                    k['stdout'].write(b'x') if 'pg_dump' in a else None) or types.SimpleNamespace(stdout=(PIN + '\n').encode())), \
                    patch.object(BACKUP, 'protected'), patch.object(BACKUP, 'database_image', return_value=PIN), \
                    patch.object(BACKUP, 'fingerprint', return_value={}):
                BACKUP.backup('rehearse')
        self.assertEqual(sorted(path.name for path in self.directory.iterdir()), before)

    def test_pre_deploy_requires_the_dispatcher_lock_and_stopped_writers(self):
        for lock, stopped in ((False, True), (True, False), (False, False)):
            with self.subTest(lock=lock, stopped=stopped), patch.object(BACKUP, 'protected'), \
                 patch.object(BACKUP, 'lock_held_elsewhere', return_value=lock), \
                 patch.object(BACKUP, 'writers_stopped', return_value=stopped), \
                 patch.object(BACKUP, 'docker') as docker, self.assertRaises(ValueError):
                BACKUP.backup('pre-deploy')
            docker.assert_not_called()
        text, _, restore = self.run_pre_deploy()
        restore.assert_called_once()
        self.assertEqual(json.loads(text)['kind'], 'pre-deploy')

    def run_pre_deploy(self):
        with patch.object(BACKUP, 'lock_held_elsewhere', return_value=True), \
             patch.object(BACKUP, 'writers_stopped', return_value=True):
            return self.run_backup('pre-deploy')

    def test_pre_deploy_does_not_take_the_lock_but_other_operations_do(self):
        with patch.object(BACKUP.os, 'geteuid', return_value=0), patch.object(BACKUP, 'backup') as backup, \
             patch.object(BACKUP, 'acquire') as acquire, patch.object(BACKUP.sys, 'argv', ['b', 'pre-deploy']):
            BACKUP.main()
        backup.assert_called_once_with('pre-deploy')
        acquire.assert_not_called()
        for operation in ('backup', 'rehearse'):
            with patch.object(BACKUP.os, 'geteuid', return_value=0), patch.object(BACKUP, 'backup') as backup, \
                 patch.object(BACKUP, 'acquire') as acquire, patch.object(BACKUP.sys, 'argv', ['b', operation]), \
                 patch('builtins.open', unittest.mock.mock_open()):
                BACKUP.main()
            acquire.assert_called_once()
            backup.assert_called_once_with(operation)
        for argv in (['b', 'restore'], ['b', 'offsite', '../x'], ['b', 'pre-deploy', 'extra']):
            with patch.object(BACKUP.os, 'geteuid', return_value=0), patch.object(BACKUP.sys, 'argv', argv), \
                 self.assertRaises(ValueError):
                BACKUP.main()

    # --- offsite --------------------------------------------------------------------------

    def offsite_files(self, text=OFFSITE, mode=0o600):
        env = self.directory / 'offsite.env'
        env.write_text(text)
        env.chmod(mode)
        return patch.multiple(BACKUP, OFFSITE_ENV=env, OFFSITE_SUCCESS=self.directory / 'offsite-last-success',
                              STATE=self.directory, RUN_DIRECTORY=str(self.directory), protected=lambda path: None)

    def test_offsite_configuration_is_strict_and_optional(self):
        with patch.object(BACKUP, 'OFFSITE_ENV', self.directory / 'missing.env'):
            self.assertIsNone(BACKUP.offsite_config())
            self.assertIsNone(BACKUP.offsite_upload(name(1)))
        with self.offsite_files():
            config = BACKUP.offsite_config()
        self.assertEqual(config['MNEMA_OFFSITE_PREFIX'], 'mnema-vps/postgres')
        broken = {
            'unknown key': OFFSITE + '\nEXTRA=1',
            'duplicate key': OFFSITE + '\nMNEMA_OFFSITE_REGION=ru-central1',
            'missing key': OFFSITE.replace('MNEMA_OFFSITE_KMS_KEY_ID=abjkmsexample0001\n', ''),
            'http endpoint': OFFSITE.replace('https://storage', 'http://storage'),
            'endpoint path': OFFSITE.replace('yandexcloud.net', 'yandexcloud.net/x'),
            'prefix traversal': OFFSITE.replace('mnema-vps/postgres/', 'a/../b'),
            'empty prefix': OFFSITE.replace('mnema-vps/postgres/', ''),
            'bucket': OFFSITE.replace('mnema-backups', 'Bad_Bucket'),
            'kms key': OFFSITE.replace('abjkmsexample0001', 'a b'),
            'credential with quote': OFFSITE.replace(ACCESS, 'AK"INJECT1234'),
        }
        for label, text in broken.items():
            with self.subTest(label), self.offsite_files(text), self.assertRaises(ValueError):
                BACKUP.offsite_config()
        with self.offsite_files(mode=0o644), self.assertRaises(ValueError):
            BACKUP.offsite_config()

    def test_every_upload_is_one_write_once_kms_put_with_credentials_outside_argv(self):
        add_backup(self.directory, 1)
        commands = []
        configs = []

        kwargs_seen = []

        def run(command, **kwargs):
            commands.append(command)
            kwargs_seen.append(kwargs)
            config = Path(command[command.index('-K') + 1])
            configs.append((config.stat().st_mode & 0o777, config.read_text(), config.parent))
            return types.SimpleNamespace(returncode=0, stdout=b'200')

        with self.offsite_files(), patch.object(BACKUP.subprocess, 'run', side_effect=run):
            self.assertEqual(BACKUP.offsite_upload(name(1)), name(1))
            self.assertTrue((self.directory / 'offsite-last-success').read_text().strip().endswith('+00:00'))
        self.assertEqual(len(commands), 2)
        urls = [command[-1] for command in commands]
        self.assertEqual(urls, [
            'https://storage.yandexcloud.net/mnema-backups/mnema-vps/postgres/%s/%s.dump' % (name(1), name(1)),
            'https://storage.yandexcloud.net/mnema-backups/mnema-vps/postgres/%s/%s.json' % (name(1), name(1))])
        for command, (mode, text, parent) in zip(commands, configs):
            self.assertEqual(command[:3], ['/usr/bin/curl', '-q', '--fail'])
            self.assertIn('--show-error', command)
            self.assertEqual(kwargs_seen[0]['timeout'], 700)
            self.assertIn('aws:amz:ru-central1:s3', command)
            self.assertIn('--max-time', command)
            for header in ('x-amz-server-side-encryption: aws:kms',
                           'x-amz-server-side-encryption-aws-kms-key-id: abjkmsexample0001',
                           'x-amz-acl: private', 'If-None-Match: *'):
                self.assertIn(header, command)
            self.assertTrue(any(part.startswith('x-amz-content-sha256: ') and len(part) == 22 + 64 for part in command))
            self.assertNotIn('gpg', ' '.join(command))
            joined = ' '.join(command)
            self.assertNotIn(ACCESS, joined)
            self.assertNotIn(SECRET, joined)
            self.assertEqual(mode, 0o600)
            self.assertEqual(text, 'user = "%s:%s"\n' % (ACCESS, SECRET))
            self.assertEqual(parent, self.directory)
        self.assertEqual(list(self.directory.glob('mnema-curl-*')), [])   # credentials never persist

    def test_existing_immutable_object_is_success_but_other_rejections_fail(self):
        add_backup(self.directory, 1)
        for outcome, ok in ((types.SimpleNamespace(returncode=22, stdout=b'412'), True),
                            (types.SimpleNamespace(returncode=22, stdout=b'403'), False),
                            (types.SimpleNamespace(returncode=7, stdout=b''), False)):
            with self.subTest(outcome=outcome.returncode, body=outcome.stdout), self.offsite_files(), \
                 patch.object(BACKUP.subprocess, 'run', return_value=outcome):
                if ok:
                    self.assertEqual(BACKUP.offsite_upload(name(1)), name(1))
                else:
                    with self.assertRaises(ValueError):
                        BACKUP.offsite_upload(name(1))
            self.assertEqual(list(self.directory.glob('mnema-curl-*')), [])

    def test_only_a_complete_named_backup_can_be_uploaded(self):
        add_backup(self.directory, 1, verified=None)
        for backup in (name(1), name(2), '../etc/passwd'):
            with self.subTest(backup=backup), self.offsite_files(), patch.object(BACKUP.subprocess, 'run') as run, \
                 self.assertRaises(ValueError):
                BACKUP.offsite_upload(backup)
            run.assert_not_called()

    def test_offsite_failure_never_fails_a_local_backup_and_leaks_no_detail(self):
        errors = io.StringIO()
        output = io.StringIO()

        def docker(args, **kwargs):
            if args[0] == 'inspect':
                return types.SimpleNamespace(stdout=(PIN + '\n').encode())
            if 'pg_dump' in args:
                kwargs['stdout'].write(b'PGDMP')
            return types.SimpleNamespace(stdout=b'')

        with patch.object(BACKUP, 'docker', side_effect=docker), patch.object(BACKUP, 'database_image', return_value=PIN), \
             patch.object(BACKUP, 'offsite_upload', side_effect=subprocess.CalledProcessError(22, ['curl', SECRET])), \
             contextlib.redirect_stdout(output), contextlib.redirect_stderr(errors), self.offsite_files():
            BACKUP.backup('backup')
        self.assertEqual(len(BACKUP.complete_backups()), 1)
        self.assertFalse(json.loads(output.getvalue())['offsite'])
        self.assertIn('offsite upload failed', errors.getvalue())
        self.assertNotIn(SECRET, errors.getvalue() + output.getvalue())

    def test_manual_offsite_operation_needs_no_lock_and_reports_disabled_with_a_distinct_exit_code(self):
        add_backup(self.directory, 1)
        base = patch.multiple(BACKUP.os, geteuid=lambda: 0)
        with base, patch.object(BACKUP, 'acquire') as acquire, patch.object(BACKUP.sys, 'argv', ['b', 'offsite', name(1)]), \
             patch('builtins.open', unittest.mock.mock_open()):
            with patch.object(BACKUP, 'offsite_upload', return_value=None), contextlib.redirect_stderr(io.StringIO()), \
                 self.assertRaises(SystemExit) as disabled:
                BACKUP.main()
            self.assertEqual(disabled.exception.code, 3)
            with patch.object(BACKUP, 'offsite_upload', side_effect=ValueError('rejected')), self.assertRaises(ValueError):
                BACKUP.main()
            with patch.object(BACKUP, 'offsite_upload', return_value=name(1)), contextlib.redirect_stdout(io.StringIO()) as out:
                BACKUP.main()
            self.assertIn('"offsite": true', out.getvalue())
        acquire.assert_not_called()

    def test_pre_deploy_never_uploads_offsite_but_the_daily_backup_does(self):
        add_backup(self.directory, 1)
        calls = []

        def fake_upload(identifier):
            calls.append(identifier)

        def docker(args, **kwargs):
            if args[0] == 'inspect':
                return types.SimpleNamespace(stdout=(PIN + '\n').encode())
            if 'pg_dump' in args:
                kwargs['stdout'].write(b'PGDMP')
            return types.SimpleNamespace(stdout=b'')

        for kind, expected in (('pre-deploy', 0), ('backup', 1)):
            calls.clear()
            with patch.object(BACKUP, 'docker', side_effect=docker), patch.object(BACKUP, 'protected'), \
                 patch.object(BACKUP, 'database_image', return_value=PIN), \
                 patch.object(BACKUP, 'fingerprint', return_value={}), patch.object(BACKUP, 'restore', return_value={}), \
                 patch.object(BACKUP, 'lock_held_elsewhere', return_value=True), patch.object(BACKUP, 'writers_stopped', return_value=True), \
                 patch.object(BACKUP, 'offsite_upload', side_effect=fake_upload), \
                 contextlib.redirect_stdout(io.StringIO()):
                BACKUP.backup(kind)
            self.assertEqual(len(calls), expected, kind)

    def test_newest_is_the_single_definition_of_a_complete_backup(self):
        self.assertEqual(BACKUP.newest(), {'backup': None, 'modified': None})
        add_backup(self.directory, 1)
        add_backup(self.directory, 2, verified=None)             # unverified: not eligible
        add_backup(self.directory, 3)
        os.utime(self.directory / (name(3) + '.json'), (1000, 1000))
        os.utime(self.directory / (name(1) + '.json'), (2000, 2000))
        (self.directory / 'not-a-name.dump').write_bytes(b'dddd')
        (self.directory / 'not-a-name.json').write_text(json.dumps({'bytes': 4, 'verified': 'list'}))   # NAME check
        self.assertEqual(BACKUP.newest(), {'backup': name(1), 'modified': 2000.0})
        with patch.object(BACKUP.os, 'geteuid', return_value=0), patch.object(BACKUP.sys, 'argv', ['b', 'newest']), \
             contextlib.redirect_stdout(io.StringIO()) as out:
            BACKUP.main()
        self.assertEqual(json.loads(out.getvalue()), {'backup': name(1), 'modified': 2000.0})

    # --- bounded network use and signal cleanup -------------------------------------------------

    def test_uploads_are_bounded_in_time_and_speed(self):
        config = BACKUP.parse_offsite(OFFSITE)
        command = BACKUP.curl_command(config, Path('/run/x'), Path('/tmp/f'), 'p/n/n.dump', '0' * 64)
        text = ' '.join(command)
        for option in ('--max-time 600', '--speed-limit 1024', '--speed-time 60', '--connect-timeout 20'):
            self.assertIn(option, text)

    def test_newest_survives_a_concurrent_retention_removing_a_pair_mid_scan(self):
        add_backup(self.directory, 1)
        add_backup(self.directory, 2)
        real_complete = BACKUP.complete_backups
        calls = []

        def racing():
            found = real_complete()
            if not calls:
                calls.append(1)
                (self.directory / (name(2) + '.json')).unlink()     # removed after the scan, before stat
            return found

        with patch.object(BACKUP, 'complete_backups', side_effect=racing):
            result = BACKUP.newest()
        self.assertEqual(result['backup'], name(1))                 # re-scanned once, no crash
        self.assertEqual(len(calls), 1)

    def test_newest_gives_up_after_one_rescan(self):
        add_backup(self.directory, 1)
        broken = lambda: [self.directory / 'gone.json']
        with patch.object(BACKUP, 'complete_backups', side_effect=broken):
            self.assertEqual(BACKUP.newest(), {'backup': None, 'modified': None})

    def test_sigterm_handler_turns_the_signal_into_an_exit_so_cleanup_runs(self):
        with self.assertRaises(SystemExit) as caught:
            BACKUP.terminate(15, None)
        self.assertEqual(caught.exception.code, 143)

    def test_exit_during_the_dump_removes_the_partial_dump_and_metadata_temp(self):
        add_backup(self.directory, 1)
        before = sorted(path.name for path in self.directory.iterdir())

        def docker(args, **kwargs):
            if args[0] == 'inspect':
                return types.SimpleNamespace(stdout=(PIN + '\n').encode())
            kwargs['stdout'].write(b'PGDMP-partial')
            raise SystemExit(143)

        with patch.object(BACKUP, 'docker', side_effect=docker), patch.object(BACKUP, 'protected'), \
             patch.object(BACKUP, 'database_image', return_value=PIN), self.assertRaises(SystemExit):
            BACKUP.backup('backup')
        self.assertEqual(sorted(path.name for path in self.directory.iterdir()), before)

    def test_exit_during_a_restore_removes_the_rehearsal_container_and_volume(self):
        calls = []

        def docker(args, **kwargs):
            calls.append(args[:2])
            if args[0] == 'exec' and 'pg_restore' in args:
                raise SystemExit(143)
            return types.SimpleNamespace(stdout=b'')

        dump = self.directory / 'x.dump'
        dump.write_bytes(b'd')
        with patch.object(BACKUP, 'docker', side_effect=docker), patch.object(BACKUP, 'sql', return_value=''), \
             patch.object(BACKUP.subprocess, 'run', return_value=types.SimpleNamespace(returncode=0)), \
             self.assertRaises(SystemExit):
            BACKUP.restore(dump, {}, PIN)
        self.assertIn(['rm', '--force'], calls)
        self.assertIn(['volume', 'rm'], calls)

    def test_exit_during_an_upload_removes_the_credentials_file(self):
        add_backup(self.directory, 1)
        seen = []

        def run(command, **kwargs):
            seen.append(Path(command[command.index('-K') + 1]))
            raise SystemExit(143)

        with self.offsite_files(), patch.object(BACKUP.subprocess, 'run', side_effect=run), self.assertRaises(SystemExit):
            BACKUP.offsite_upload(name(1))
        self.assertFalse(seen[0].exists())

    def test_a_real_sigterm_stops_a_running_backup_and_leaves_no_partial_files(self):
        import signal
        add_backup(self.directory, 1)
        before = sorted(path.name for path in self.directory.iterdir())
        child = r"""
import importlib.util, signal, sys, time, types
from pathlib import Path
from unittest.mock import patch
spec = importlib.util.spec_from_file_location('b', sys.argv[1]); b = importlib.util.module_from_spec(spec); spec.loader.exec_module(b)
signal.signal(signal.SIGTERM, b.terminate)
def docker(args, **kwargs):
    if args[0] == 'inspect':
        return types.SimpleNamespace(stdout=(sys.argv[3] + '\n').encode())
    kwargs['stdout'].write(b'PGDMP-partial'); kwargs['stdout'].flush()
    print('dumping', flush=True)
    time.sleep(60)
with patch.object(b, 'DIRECTORY', Path(sys.argv[2])), patch.object(b, 'docker', docker), patch.object(b, 'protected'),      patch.object(b, 'database_image', return_value=sys.argv[3]):
    b.backup('backup')
"""
        process = subprocess.Popen([sys.executable, '-c', child, str(ROOT / 'deploy/production/local-backup.py'),
                                    str(self.directory), PIN], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.addCleanup(process.kill)
        self.addCleanup(process.stdout.close)
        self.addCleanup(process.stderr.close)
        self.assertEqual(process.stdout.readline().strip(), b'dumping')
        self.assertTrue(any(path.suffix == '.dump' and path.name != name(1) + '.dump' for path in self.directory.iterdir()))
        process.send_signal(signal.SIGTERM)
        self.assertEqual(process.wait(timeout=20), 143)
        self.assertEqual(sorted(path.name for path in self.directory.iterdir()), before)

    def test_only_the_exact_admin_bound_postgres_digest_is_accepted(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(BACKUP, 'protected'):
            path = Path(temporary) / 'postgres-image'
            with patch.object(BACKUP, 'IMAGE_PIN', path):
                for value in ('postgres:latest', 'ghcr.io/other/postgres@sha256:' + 'a' * 64,
                              'ghcr.io/mattoyuzuru/mnema/postgres@sha256:' + 'A' * 64):
                    path.write_text(value)
                    with self.assertRaises(ValueError): BACKUP.database_image()
                value = 'ghcr.io/mattoyuzuru/mnema/postgres@sha256:' + 'a' * 64
                path.write_text(value + '\n')
                self.assertEqual(BACKUP.database_image(), value)


class BackupFreshnessAlertTest(unittest.TestCase):
    """The health monitor asks the backup tool, so there is one definition of 'complete'."""

    def setUp(self):
        self.directory = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.directory, ignore_errors=True)
        self.backups = self.directory / 'backups'
        self.backups.mkdir()
        self.offsite_env = self.directory / 'offsite.env'
        self.offsite_success = self.directory / 'offsite-last-success'
        real_stat = os.stat
        self.tool_owner = types.SimpleNamespace(st_uid=0, st_mode=0o100755)
        stat = lambda path, *a, **k: self.tool_owner if str(path) == HEALTH.BACKUP_TOOL else real_stat(path, *a, **k)
        for patcher in (patch.multiple(HEALTH, OFFSITE_CONFIG=self.offsite_env, OFFSITE_SUCCESS=self.offsite_success),
                        patch.object(BACKUP, 'DIRECTORY', self.backups),
                        patch.object(HEALTH.os, 'stat', side_effect=stat),
                        patch.object(HEALTH.subprocess, 'run', side_effect=self.tool)):
            patcher.start()
            self.addCleanup(patcher.stop)

    def tool(self, command, **kwargs):
        self.assertEqual(command[1:], ['newest'])
        return types.SimpleNamespace(returncode=0, stdout=json.dumps(BACKUP.newest()).encode())

    def age(self, path, hours):
        stamp = time.time() - hours * 3600
        os.utime(path, (stamp, stamp))

    def test_missing_or_old_or_unverified_backups_are_stale_and_a_fresh_complete_one_is_not(self):
        self.assertEqual(HEALTH.stale_components(), ['backup_stale'])
        add_backup(self.backups, 1, verified=None)                       # no verification marker
        self.assertEqual(HEALTH.stale_components(), ['backup_stale'])
        add_backup(self.backups, 2)
        self.age(self.backups / (name(2) + '.json'), 27)
        self.assertEqual(HEALTH.stale_components(), ['backup_stale'])
        add_backup(self.backups, 3, verified='restore')
        self.age(self.backups / (name(3) + '.json'), 1)
        self.assertEqual(HEALTH.stale_components(), [])

    def test_a_tool_that_is_not_root_owned_or_is_writable_is_never_executed(self):
        add_backup(self.backups, 1)
        for owner in (types.SimpleNamespace(st_uid=1000, st_mode=0o100755), types.SimpleNamespace(st_uid=0, st_mode=0o100775)):
            self.tool_owner = owner
            with patch.object(HEALTH.subprocess, 'run') as run:
                self.assertEqual(HEALTH.stale_components(), ['backup_stale'])
            run.assert_not_called()

    def test_the_monitors_own_timeouts_fit_inside_the_unit_timeout(self):
        unit = (ROOT / 'deploy/production/mnema-health.service').read_text()
        timeout = int(unit.split('TimeoutStartSec=')[1].split('s')[0])
        # four 5 s probes + the 5 s worker inspect + the newest call + the 5 s logger call, with headroom
        self.assertLess(4 * 5 + 5 + HEALTH.NEWEST_TIMEOUT + 5 + 5, timeout)

    def test_an_unavailable_backup_tool_counts_as_stale(self):
        for failure in (types.SimpleNamespace(returncode=1, stdout=b''), types.SimpleNamespace(returncode=0, stdout=b'garbage'),
                        OSError(), subprocess.TimeoutExpired(['t'], 20)):
            with self.subTest(failure=failure), patch.object(
                    HEALTH.subprocess, 'run', side_effect=failure if isinstance(failure, Exception) else None,
                    return_value=None if isinstance(failure, Exception) else failure):
                self.assertEqual(HEALTH.stale_components(), ['backup_stale'])

    def test_offsite_is_checked_only_when_configured(self):
        add_backup(self.backups, 1)
        self.assertEqual(HEALTH.stale_components(), [])
        self.offsite_env.write_text('configured')
        self.assertEqual(HEALTH.stale_components(), ['offsite_stale'])
        self.offsite_success.write_text('2026-10-08T00:00:00+00:00\n')
        self.assertEqual(HEALTH.stale_components(), [])
        self.age(self.offsite_success, 27)
        self.assertEqual(HEALTH.stale_components(), ['offsite_stale'])

    def test_the_journal_event_names_components_only(self):
        output = io.StringIO()
        with patch.object(HEALTH, 'check', return_value=['disk_reserve']), \
             patch.object(HEALTH, 'stale_components', return_value=['backup_stale']), \
             patch.object(HEALTH.os, 'geteuid', return_value=0), patch.object(HEALTH.sys, 'argv', ['m']), \
             patch.object(HEALTH.subprocess, 'run') as logger, contextlib.redirect_stdout(output), \
             self.assertRaises(SystemExit):
            HEALTH.main()
        event = json.loads(output.getvalue())
        self.assertEqual(event['components'], ['backup_stale', 'disk_reserve'])
        self.assertEqual(set(event), {'service', 'status', 'components'})
        logger.assert_called_once()


class BackupDockerTest(unittest.TestCase):
    def test_dump_restores_rows_and_role_isolation_in_a_network_none_fixture(self):
        name = 'mnema-backup-test-' + uuid.uuid4().hex
        docker = shutil.which('docker')
        real_run = subprocess.run
        def local_run(command, **kwargs):
            command = list(command)
            if command[0] == '/usr/bin/docker': command[0] = docker
            return real_run(command, **kwargs)
        env = {**BACKUP.ENV, 'DOCKER_HOST': os.environ.get('DOCKER_HOST', 'unix:///var/run/docker.sock')}
        with tempfile.TemporaryDirectory() as temporary, patch.object(BACKUP, 'SOURCE', name), \
             patch.object(BACKUP, 'DIRECTORY', Path(temporary)), \
             patch.object(BACKUP, 'protected'), patch.object(BACKUP, 'ENV', env), \
             patch.object(BACKUP, 'database_image', return_value=FIXTURE_IMAGE), \
             patch.object(BACKUP, 'offsite_upload', return_value=None), \
             patch.object(BACKUP.subprocess, 'run', side_effect=local_run):
            old_umask = os.umask(0o077)
            started = False
            try:
                BACKUP.docker(['run', '--detach', '--pull', 'never', '--name', name, '--network', 'none',
                    '--tmpfs', '/var/lib/postgresql:size=256m', '-e', 'POSTGRES_PASSWORD=fixture-superuser',
                    '-e', 'POSTGRES_DB=mnema', '-e', 'MNEMA_IDENTITY_DB_PASSWORD=fixture-identity',
                    '-e', 'MNEMA_LEARNING_DB_PASSWORD=fixture-learning', '--mount',
                    'type=bind,src=' + str(ROOT / 'deploy/production/init-database.sh') + ',dst=/docker-entrypoint-initdb.d/10-mnema.sh,readonly',
                    FIXTURE_IMAGE, 'postgres', '-c', 'listen_addresses=127.0.0.1', '-c', 'port=15432'], stdout=subprocess.DEVNULL)
                started = True
                for _ in range(60):
                    try:
                        if BACKUP.sql(name, "SELECT count(*) FROM pg_roles WHERE rolname='app_learning';") == '1': break
                    except subprocess.CalledProcessError:
                        pass
                    time.sleep(1)
                else: self.fail('fixture database did not initialize')
                BACKUP.sql(name, "CREATE TABLE app_identity.restore_fixture(id integer PRIMARY KEY, value text); INSERT INTO app_identity.restore_fixture VALUES (1,'ordinary fixture'),(2,'second row');")
                output = io.StringIO()
                with contextlib.redirect_stdout(output): BACKUP.backup('rehearse')
                self.assertIn('"restoreVerified": true', output.getvalue())
                self.assertIn('"rows": 2', output.getvalue())
                self.assertNotIn('ordinary fixture', output.getvalue())
                dumps = list(Path(temporary).glob('*.dump'))
                self.assertEqual(len(dumps), 1)
                self.assertEqual(dumps[0].stat().st_mode & 0o777, 0o600)
                self.assertEqual(BACKUP.sql(name, 'SELECT count(*) FROM app_identity.restore_fixture;'), '2')
                # A failed rehearsal leaves no dump or metadata of its own and keeps the earlier backup.
                with patch.object(BACKUP, 'fingerprint', side_effect=[{}, {'changed': [1, 'x']}]), \
                     self.assertRaises(ValueError):
                    BACKUP.backup('rehearse')
                self.assertEqual(len(list(Path(temporary).glob('*.dump'))), 1)
                # The daily path validates the real archive with pg_restore --list and rotates to two.
                for _ in range(2):
                    time.sleep(1.1)  # distinct second-resolution names
                    with contextlib.redirect_stdout(io.StringIO()): BACKUP.backup('backup')
                complete = BACKUP.complete_backups()
                self.assertEqual(len(complete), 2)
                self.assertEqual(len(list(Path(temporary).glob('*.dump'))), 2)
                self.assertEqual(json.loads(complete[-1].read_text())['verified'], 'list')
                self.assertEqual(list(Path(temporary).glob('*.tmp')), [])
            finally:
                os.umask(old_umask)
                if started: BACKUP.docker(['rm', '--force', name], stdout=subprocess.DEVNULL)


if __name__ == '__main__':
    unittest.main()
