"""The CI identity cannot select arbitrary commands, images or writable config."""

import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import stat
import subprocess
import tempfile
import time
import types
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('production_dispatcher', ROOT / 'deploy/production/mnema-deploy.py')
DISPATCH = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(DISPATCH)

OLD = 'a' * 40
NEW = 'b' * 40
SERVICES = DISPATCH.SERVICES


def images(digit):
    return {s: 'ghcr.io/mattoyuzuru/mnema/' + s + '@sha256:' + digit * 64 for s in SERVICES}


def candidate(sha=NEW, digit='c', **changes):
    document = {'schemaVersion': 1, 'sha': sha, 'images': images(digit),
                'source': {'repository': 'mattoyuzuru/mnema', 'commit': sha,
                           'workflow': '.github/workflows/deploy.yaml', 'runId': 42, 'runAttempt': 1},
                'securityEvidenceSha256': 'd' * 64}
    document.update(changes)
    return document


def raw(document):
    return json.dumps(document).encode()


DISPATCH_CHECK = DISPATCH.check_images_present
FINGERPRINT = {'app_identity': {'count': 3, 'max_rank': 3, 'md5': 'x'}, 'app_learning': None}
CHANGED = {'app_identity': {'count': 4, 'max_rank': 4, 'md5': 'y'}, 'app_learning': None}


class Host:
    """Temporary ROOT/STATE with the dispatcher's external effects replaced by recorders."""

    def __init__(self, case, current=None, fingerprints=(FINGERPRINT, FINGERPRINT), backup_error=None, start_error=None):
        self.case = case
        self.directory = Path(tempfile.mkdtemp())
        case.addCleanup(lambda: __import__('shutil').rmtree(self.directory, ignore_errors=True))
        self.root = self.directory / 'etc'
        self.state = self.directory / 'state'
        (self.root / 'releases').mkdir(parents=True)
        self.state.mkdir()
        self.events = []
        self.running_image = images('1')['postgres']
        for sha, digit in ((OLD, '1'), (NEW, '2')):
            (self.root / 'releases' / (sha + '.json')).write_text(json.dumps({'sha': sha, 'images': images(digit)}))
        (self.root / 'admitted.json').write_text(json.dumps({'sha': NEW}))
        if current:
            (self.state / 'current.json').write_text(json.dumps(current))
        self.fingerprints = list(fingerprints)
        self.backup_error = backup_error
        self.start_error = start_error
        self.listing = ''

    def compose(self, data, operation):
        self.events.append(('compose', data['sha'], operation))

    def backup(self):
        self.events.append(('backup',))
        if self.backup_error:
            raise self.backup_error
        return '20261008T010203Z-abcdef12.dump'

    def offsite(self, backup):
        self.events.append(('offsite', backup))
        return 'uploaded'

    def docker(self, args, **kwargs):
        if args[0] == 'inspect':
            return self.running_image + '\n'
        if args[:2] == ['image', 'ls']:
            return self.listing
        if args[0] == 'start':
            if self.start_error:
                raise self.start_error
            self.events.append(('start', *args[1:]))
            return ''
        if args[:2] == ['image', 'rm']:
            self.events.append(('rm', args[2]))
            return ''
        raise AssertionError(args)

    def fingerprint(self):
        self.events.append(('fingerprint',))
        return self.fingerprints.pop(0)

    def patches(self):
        stack = contextlib.ExitStack()
        for target, value in (('ROOT', self.root), ('STATE', self.state)):
            stack.enter_context(patch.object(DISPATCH, target, value))
        stack.enter_context(patch.object(DISPATCH, 'protected', side_effect=lambda p: p))
        stack.enter_context(patch.object(DISPATCH.shutil, 'disk_usage',
                                         return_value=types.SimpleNamespace(free=20 * 1024 ** 3)))
        stack.enter_context(patch.object(DISPATCH, 'compose', side_effect=self.compose))
        stack.enter_context(patch.object(DISPATCH, 'pre_deploy_backup', side_effect=self.backup))
        stack.enter_context(patch.object(DISPATCH, 'check_backup_tool'))
        stack.enter_context(patch.object(DISPATCH, 'check_media_work_root'))
        stack.enter_context(patch.object(DISPATCH, 'check_media_runner'))
        stack.enter_context(patch.object(DISPATCH, 'check_images_present'))
        stack.enter_context(patch.object(DISPATCH, 'offsite_upload', side_effect=self.offsite))
        stack.enter_context(patch.object(DISPATCH, 'newest_backup_age_hours', return_value=0.5))
        stack.enter_context(patch.object(DISPATCH, 'docker', side_effect=self.docker))
        stack.enter_context(patch.object(DISPATCH, 'schema_fingerprint', side_effect=self.fingerprint))
        return stack

    def run(self, command):
        output = io.StringIO()
        with self.patches(), patch.object(DISPATCH.os, 'geteuid', return_value=0), \
             patch.object(DISPATCH.sys, 'argv', ['dispatcher', command]), contextlib.redirect_stdout(output), \
             contextlib.redirect_stderr(io.StringIO()):
            DISPATCH.main()
        return output.getvalue()

    def state_file(self, name):
        return json.loads((self.state / name).read_text())


class ProductionDispatcherTest(unittest.TestCase):
    def test_state_rename_is_followed_by_directory_durability(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(DISPATCH, 'STATE', Path(temporary)), \
             patch.object(DISPATCH, 'protected', side_effect=lambda p: p), \
             patch.object(DISPATCH, 'sync_directory') as sync:
            DISPATCH.write_state('pending.json', {'sha': 'b' * 40})
            self.assertTrue((Path(temporary) / 'pending.json').exists())
            self.assertEqual((Path(temporary) / 'pending.json').stat().st_mode & 0o777, 0o600)
            self.assertEqual([c.args[0] for c in sync.call_args_list], [Path(temporary).parent, Path(temporary)])

    def test_shell_and_argument_injection_are_rejected(self):
        for command in ('', 'sh', 'status; id', 'status\nid', 'deploy ../../etc/passwd', 'admit', 'verify now',
                        'preflight ' + 'a' * 40 + ' --file evil.yml', 'deploy ' + 'A' * 40,
                        'rollback ' + 'a' * 40, 'sudo id', 'internal-sftp'):
            with self.subTest(command=command), self.assertRaises(DISPATCH.Rejected):
                DISPATCH.parse_command(command)
        self.assertEqual(DISPATCH.parse_command('deploy ' + 'a' * 40), ['deploy', 'a' * 40])
        self.assertEqual(DISPATCH.parse_command('admit ' + 'a' * 40), ['admit', 'a' * 40])
        self.assertEqual(DISPATCH.parse_command('pull ' + 'a' * 40), ['pull', 'a' * 40])
        for command in ('pull', 'pull ' + 'a' * 39, 'pull ' + 'a' * 40 + ' ghcr.io/x@sha256:' + 'b' * 64,
                        'pull ' + 'A' * 40, 'pull ' + 'a' * 40 + '\nid', 'pull  ' + 'a' * 40):
            with self.subTest(command=command), self.assertRaises(DISPATCH.Rejected):
                DISPATCH.parse_command(command)
        for command in ('status', 'verify', 'rollback'):
            self.assertEqual(DISPATCH.parse_command(command), [command])

    def test_untrusted_parent_or_symlink_cannot_bless_root_owned_config(self):
        good = types.SimpleNamespace(st_mode=stat.S_IFDIR | 0o755, st_uid=0)
        writable = types.SimpleNamespace(st_mode=stat.S_IFDIR | 0o777, st_uid=0)
        with patch.object(Path, 'lstat', side_effect=[good, writable]), self.assertRaises(DISPATCH.Rejected):
            DISPATCH.protected(Path('/etc/mnema/production/compose.yaml'))
        link = types.SimpleNamespace(st_mode=stat.S_IFLNK | 0o777, st_uid=0)
        with patch.object(Path, 'lstat', return_value=link), self.assertRaises(DISPATCH.Rejected):
            DISPATCH.protected(Path('/etc/mnema/production/compose.yaml'))

    def test_ssh_and_sudo_restrictions_are_scoped(self):
        config = (ROOT / 'deploy/production/60-mnema-deploy.conf').read_text()
        self.assertIn('Match User mnema-deploy', config)
        self.assertIn('DisableForwarding yes', config)
        self.assertIn('ForceCommand /usr/local/bin/mnema-deploy-ssh', config)
        sudo = (ROOT / 'deploy/production/mnema-deploy.sudoers').read_text()
        self.assertNotIn('NOPASSWD: ALL', sudo)
        self.assertNotIn('/usr/bin/docker', sudo)
        self.assertIn('/usr/local/sbin/mnema-deploy', sudo)

    # --- admission -----------------------------------------------------------------

    def admit(self, host, document, sha=NEW, data=None):
        output = io.StringIO()
        with host.patches(), contextlib.redirect_stdout(output):
            DISPATCH.admit(sha, data if data is not None else raw(document))
        return output.getvalue()

    def test_admit_records_manifest_audit_line_and_pointer(self):
        host = Host(self)
        (host.root / 'releases' / (NEW + '.json')).unlink()
        (host.root / 'admitted.json').write_text(json.dumps({'sha': OLD}))
        self.admit(host, candidate())
        manifest = json.loads((host.root / 'releases' / (NEW + '.json')).read_text())
        self.assertEqual(set(manifest), {'sha', 'images', 'source', 'admitted_at'})
        self.assertEqual(manifest['images'], images('c'))
        self.assertEqual((host.root / 'releases' / (NEW + '.json')).stat().st_mode & 0o777, 0o600)
        lines = (host.state / 'admissions.jsonl').read_text().splitlines()
        self.assertEqual(len(lines), 1)
        entry = json.loads(lines[0])
        self.assertEqual((entry['sha'], entry['runId'], entry['images']), (NEW, 42, images('c')))
        self.assertEqual((host.state / 'admissions.jsonl').stat().st_mode & 0o777, 0o600)
        self.assertEqual(json.loads((host.root / 'admitted.json').read_text()), {'sha': NEW})
        # Admission is append-only: a second call adds a line without rewriting the first.
        self.admit(host, candidate())
        self.assertEqual((host.state / 'admissions.jsonl').read_text().splitlines()[0], lines[0])
        self.assertEqual(len((host.state / 'admissions.jsonl').read_text().splitlines()), 2)

    def test_admit_rejects_every_malformed_or_foreign_candidate(self):
        bad_images = images('c')
        cases = {
            'foreign namespace': candidate(images={**bad_images, 'learning': 'ghcr.io/other/mnema/learning@sha256:' + 'c' * 64}),
            'mutable tag': candidate(images={**bad_images, 'learning': 'ghcr.io/mattoyuzuru/mnema/learning:latest'}),
            'service swap': candidate(images={**bad_images, 'learning': bad_images['frontend']}),
            'extra service': candidate(images={**bad_images, 'redis': 'ghcr.io/mattoyuzuru/mnema/redis@sha256:' + 'c' * 64}),
            'missing service': candidate(images={k: v for k, v in bad_images.items() if k != 'postgres'}),
            'four-image candidate without the media worker': candidate(images={
                k: v for k, v in bad_images.items() if k != 'media-worker'}),
            'wrong sha': candidate(sha='e' * 40),
            'source commit': candidate(source={**candidate()['source'], 'commit': OLD}),
            'source repository': candidate(source={**candidate()['source'], 'repository': 'other/repo'}),
            'source workflow': candidate(source={**candidate()['source'], 'workflow': '.github/workflows/x.yaml'}),
            'extra source key': candidate(source={**candidate()['source'], 'note': 'x'}),
            'boolean run id': candidate(source={**candidate()['source'], 'runId': True}),
            'zero attempt': candidate(source={**candidate()['source'], 'runAttempt': 0}),
            'schema version': candidate(schemaVersion=2),
            'boolean schema version': candidate(schemaVersion=True),
            'extra field': candidate(admitted=True),
            'evidence hash': candidate(securityEvidenceSha256='D' * 64),
        }
        for name, document in cases.items():
            with self.subTest(name), self.assertRaises(DISPATCH.Rejected):
                DISPATCH.validate_candidate(raw(document), NEW)
        for name, data in (('not json', b'{'), ('not an object', b'[]'), ('invalid utf-8', b'\xff\xfe'),
                           ('oversize', b' ' * (DISPATCH.MAX_CANDIDATE_BYTES + 1))):
            with self.subTest(name), self.assertRaises(DISPATCH.Rejected):
                DISPATCH.validate_candidate(data, NEW)

    def test_rejected_admission_writes_nothing(self):
        host = Host(self)
        (host.root / 'releases' / (NEW + '.json')).unlink()
        (host.root / 'admitted.json').write_text(json.dumps({'sha': OLD}))
        with self.assertRaises(DISPATCH.Rejected):
            self.admit(host, candidate(sha='e' * 40))
        self.assertFalse((host.root / 'releases' / (NEW + '.json')).exists())
        self.assertFalse((host.state / 'admissions.jsonl').exists())
        self.assertEqual(json.loads((host.root / 'admitted.json').read_text()), {'sha': OLD})

    def test_admit_never_overwrites_a_release_with_different_images(self):
        host = Host(self)  # NEW is already staged with digit-2 images
        before = (host.root / 'releases' / (NEW + '.json')).read_text()
        with self.assertRaises(DISPATCH.Rejected):
            self.admit(host, candidate(digit='c'))
        self.assertEqual((host.root / 'releases' / (NEW + '.json')).read_text(), before)
        self.assertFalse((host.state / 'admissions.jsonl').exists())
        # The same images are an idempotent re-admission (a re-run job).
        self.admit(host, candidate(digit='2'))
        self.assertEqual((host.root / 'releases' / (NEW + '.json')).read_text(), before)

    def test_stdin_is_read_with_a_size_cap_and_a_deadline(self):
        read, write = os.pipe()
        self.addCleanup(lambda: [os.close(fd) for fd in (read,) if True])
        os.write(write, b'x' * 5000)
        os.close(write)
        self.assertEqual(len(DISPATCH.read_stdin(100, descriptor=read)), 101)
        # A silent writer cannot hold the caller session (and the lock path) open.
        silent_read, silent_write = os.pipe()
        self.addCleanup(lambda: [os.close(fd) for fd in (silent_read, silent_write)])
        with self.assertRaisesRegex(DISPATCH.Rejected, 'timed out'):
            DISPATCH.read_stdin(100, timeout=0.05, descriptor=silent_read)

    def test_untrusted_input_is_read_before_the_production_lock_is_taken(self):
        order = []
        for operation, payload in (('admit ' + NEW, DISPATCH.MAX_CANDIDATE_BYTES), ('configure', DISPATCH.MAX_CONFIG_BYTES),
                                   ('pull ' + NEW, DISPATCH.MAX_TOKEN_BYTES)):
            order.clear()
            with tempfile.TemporaryDirectory() as temporary, patch.object(DISPATCH, 'LOCK', Path(temporary) / 'lock'), \
                 patch.object(DISPATCH.os, 'geteuid', return_value=0), patch.object(DISPATCH.sys, 'argv', ['d', operation]), \
                 patch.object(DISPATCH, 'install_signal_handlers'), \
                 patch.object(DISPATCH, 'read_stdin', side_effect=lambda limit: order.append(('read', limit)) or b'{}'), \
                 patch.object(DISPATCH, 'acquire', side_effect=lambda lock: order.append(('lock',))), \
                 patch.object(DISPATCH, 'main', side_effect=lambda data: order.append(('main', data))):
                self.assertEqual(DISPATCH.entrypoint(), 0)
            self.assertEqual(order, [('read', payload), ('lock',), ('main', b'{}')])
        order.clear()
        with tempfile.TemporaryDirectory() as temporary, patch.object(DISPATCH, 'LOCK', Path(temporary) / 'lock'), \
             patch.object(DISPATCH.os, 'geteuid', return_value=0), patch.object(DISPATCH.sys, 'argv', ['d', 'status']), \
             patch.object(DISPATCH, 'install_signal_handlers'), \
             patch.object(DISPATCH, 'read_stdin') as reader, patch.object(DISPATCH, 'main'):
            self.assertEqual(DISPATCH.entrypoint(), 0)
        reader.assert_not_called()

    # --- ephemeral registry pull ------------------------------------------------------

    TOKEN = 'ghs_' + 'T' * 36

    def pull(self, host, token=None, run=None, sha=NEW):
        """Run pull_images with docker replaced by a recorder; returns (calls, directories seen, output)."""
        calls, configs = [], []
        scratch = host.directory / 'run'
        scratch.mkdir(exist_ok=True)

        def fake_run(command, **kwargs):
            config = Path(command[command.index('--config') + 1])
            configs.append((config.is_dir(), stat.S_IMODE(config.stat().st_mode)))
            calls.append((command, kwargs.get('input'), config))
            return run(command, **kwargs) if run else types.SimpleNamespace(returncode=0, stdout=b'', stderr=b'')

        output = io.StringIO()
        with host.patches(), patch.object(DISPATCH, 'RUN_DIR', scratch), \
             patch.object(DISPATCH.subprocess, 'run', side_effect=fake_run), contextlib.redirect_stdout(output), \
             contextlib.redirect_stderr(io.StringIO()):
            try:
                DISPATCH.pull_images(sha, (token or self.TOKEN).encode() if not isinstance(token, bytes) else token)
            finally:
                self.leftover = list(scratch.iterdir())
        return calls, configs, output.getvalue()

    def test_the_registry_token_is_validated_for_form_and_never_echoed(self):
        valid = self.TOKEN.encode()
        self.assertEqual(DISPATCH.validate_token(valid), self.TOKEN)
        self.assertEqual(DISPATCH.validate_token(valid + b'\n'), self.TOKEN)
        self.assertEqual(DISPATCH.validate_token(b'a' * 1000), 'a' * 1000)   # no artificial length cap below the 4 KiB stdin bound
        for bad in (b'', b'short', b'a' * 15, b'a' * (DISPATCH.MAX_TOKEN_BYTES + 1), valid + b'\n\n',
                    valid[:20] + b' ' + valid[20:], valid + b'"', valid + b'$(id)', valid + b';', valid + b'\\',
                    'ghs_\u00e9' .encode() + b'a' * 20, b'ghs_\xff' + b'a' * 20, b'\n' + valid):
            with self.subTest(bad=bad[:24]), self.assertRaises(DISPATCH.Rejected) as caught:
                DISPATCH.validate_token(bad)
            self.assertIn(str(caught.exception), ('registry token invalid', 'registry token too large'))

    def test_pull_logs_in_on_stdin_with_a_private_config_and_pulls_exactly_the_manifest_digests(self):
        host = Host(self, current={'sha': OLD, 'previous': None})
        calls, configs, output = self.pull(host)
        commands = [command for command, _, _ in calls]
        # login first, token only on stdin; then exactly the five admitted digests, nothing else
        login = commands[0]
        self.assertEqual(login[:2], ['/usr/bin/docker', '--config'])
        self.assertEqual(login[3:], ['login', 'ghcr.io', '--username', 'x-access-token', '--password-stdin'])
        self.assertEqual(calls[0][1], self.TOKEN.encode() + b'\n')
        pulls = [command for command in commands[1:]]
        self.assertEqual([command[3:5] for command in pulls], [['pull', '--quiet']] * 5)
        self.assertEqual([command[5] for command in pulls], [images('2')[service] for service in SERVICES])
        self.assertEqual(len({str(call[2]) for call in calls}), 1)
        for command, stdin, _ in calls[1:]:
            self.assertIsNone(stdin)
        self.assertNotIn(self.TOKEN, ' '.join(' '.join(command) for command in commands))
        self.assertTrue(all(is_dir and mode == 0o700 for is_dir, mode in configs))
        self.assertTrue(calls[0][2].parent == host.directory / 'run' and calls[0][2].name.startswith('mnema-docker-'))
        self.assertFalse(calls[0][2].exists())
        self.assertEqual(self.leftover, [])
        self.assertEqual(json.loads(output), {'target': 'mnema-prod', 'sha': NEW, 'pulled': 5})
        self.assertNotIn(self.TOKEN, output)

    def test_pull_removes_the_temporary_config_when_login_or_a_pull_fails_and_stops_there(self):
        host = Host(self, current={'sha': OLD, 'previous': None})
        for failing in (0, 3):
            seen = []

            def run(command, **kwargs):
                seen.append(command)
                code = 1 if len(seen) == failing + 1 else 0
                return types.SimpleNamespace(returncode=code, stdout=b'', stderr=(b'denied: ' + self.TOKEN.encode()))

            with self.subTest(failing=failing), self.assertRaises(DISPATCH.Rejected) as caught:
                self.pull(host, run=run)
            self.assertEqual(len(seen), failing + 1)            # nothing after the first failure
            self.assertEqual(self.leftover, [])
            self.assertNotIn(self.TOKEN, str(caught.exception))  # Docker's output is never surfaced
            self.assertIn('registry operation failed', str(caught.exception))

    def test_pull_removes_the_temporary_config_on_sigterm_and_on_timeout(self):
        host = Host(self, current={'sha': OLD, 'previous': None})

        def terminated(command, **kwargs):
            if 'pull' in command:
                raise SystemExit(143)                            # what install_signal_handlers makes of SIGTERM
            return types.SimpleNamespace(returncode=0, stdout=b'', stderr=b'')

        with self.assertRaises(SystemExit):
            self.pull(host, run=terminated)
        self.assertEqual(self.leftover, [])

        def slow(command, **kwargs):
            raise subprocess.TimeoutExpired(command, 1)

        with self.assertRaisesRegex(DISPATCH.Rejected, 'timed out'):
            self.pull(host, run=slow)
        self.assertEqual(self.leftover, [])
        # the whole operation is bounded: an exhausted budget refuses further pulls
        clock = iter([0, 0, DISPATCH.PULL_TOTAL_SECONDS + 1] + [10 ** 6] * 20)
        with patch.object(DISPATCH.time, 'monotonic', side_effect=lambda: next(clock)), \
             self.assertRaisesRegex(DISPATCH.Rejected, 'timed out'):
            self.pull(host)
        self.assertEqual(self.leftover, [])

    def test_pull_removes_configs_left_behind_by_a_killed_earlier_pull(self):
        host = Host(self, current={'sha': OLD, 'previous': None})
        stale = host.directory / 'run' / 'mnema-docker-stale'
        (stale / 'nested').mkdir(parents=True)
        (stale / 'config.json').write_text('{"auths": {}}')
        unrelated = host.directory / 'run' / 'something-else'
        unrelated.mkdir()
        self.pull(host)
        self.assertFalse(stale.exists())
        self.assertTrue(unrelated.exists())

    def test_pull_refuses_before_touching_docker_or_disk_for_a_bad_token_unadmitted_sha_or_legacy_release(self):
        host = Host(self, current={'sha': OLD, 'previous': None})
        with self.assertRaisesRegex(DISPATCH.Rejected, 'token'):
            self.pull(host, token=b'not a token')
        with self.assertRaisesRegex(DISPATCH.Rejected, 'not the currently admitted'):
            self.pull(host, sha=OLD)
        legacy = {s: v for s, v in images('3').items() if s != 'media-worker'}
        (host.root / 'releases' / (NEW + '.json')).write_text(json.dumps({'sha': NEW, 'images': legacy}))
        with self.assertRaisesRegex(DISPATCH.Rejected, 'predates the media worker'):
            self.pull(host)
        self.assertEqual(self.leftover, [])

    def test_compose_never_pulls_and_a_release_with_a_missing_image_is_refused_before_any_writer_stops(self):
        command = self.compose_command(Host(self))
        self.assertEqual(command[command.index('up'):command.index('up') + 3], ['up', '--pull', 'never'])
        host = Host(self, current={'sha': OLD, 'previous': None})
        calls = []

        def docker(args, **kwargs):
            calls.append(args)
            raise DISPATCH.Rejected('docker operation failed; administrator inspection required')

        for operation in ('deploy ' + NEW, 'preflight ' + NEW):
            with host.patches(), patch.object(DISPATCH, 'check_images_present', DISPATCH_CHECK), \
                 patch.object(DISPATCH, 'docker', side_effect=docker), patch.object(DISPATCH.os, 'geteuid', return_value=0), \
                 patch.object(DISPATCH.sys, 'argv', ['dispatcher', operation]), contextlib.redirect_stderr(io.StringIO()), \
                 self.assertRaisesRegex(DISPATCH.Rejected, 'not present locally'):
                DISPATCH.main()
            self.assertNotIn(('compose', NEW, 'stop'), host.events)
        # all five refs are checked in one call, exactly the manifest's
        self.assertEqual(calls[0][:3], ['image', 'inspect', '--format'])
        self.assertEqual(calls[0][4:], [images('2')[service] for service in SERVICES])
        with patch.object(DISPATCH, 'docker', return_value='sha256:x\n'):
            DISPATCH_CHECK({'sha': NEW, 'images': images('2')})

    # --- release manifests and gates --------------------------------------------------

    def test_release_validity_is_the_image_binding_not_acceptance_flags(self):
        host = Host(self)
        release = {'sha': OLD, 'images': images('1')}
        with host.patches():
            path = host.root / 'releases' / (OLD + '.json')
            for extra in ({}, {'data_boundary_approved': False, 'auth_guard_verified': False,
                               'backup_restore_verified': False, 'source_ci_verified': False,
                               'image_security_verified': False}):
                path.write_text(json.dumps({**release, **extra}))
                self.assertEqual(DISPATCH.load_release(OLD)['sha'], OLD)
            for bad in ('ghcr.io/mattoyuzuru/mnema/learning:latest', 'ghcr.io/other/learning@sha256:' + 'b' * 64):
                path.write_text(json.dumps({**release, 'images': {**release['images'], 'learning': bad}}))
                with self.assertRaises(DISPATCH.Rejected):
                    DISPATCH.load_release(OLD)
            path.write_text(json.dumps({**release, 'sha': NEW}))
            with self.assertRaises(DISPATCH.Rejected):
                DISPATCH.load_release(OLD)

    def test_historical_deploy_cannot_bypass_the_current_admission_pointer(self):
        host = Host(self)
        (host.root / 'admitted.json').write_text(json.dumps({'sha': OLD}))
        with self.assertRaises(DISPATCH.Rejected):
            host.run('deploy ' + NEW)
        self.assertEqual(host.events, [])

    def test_uncertain_rollout_blocks_deploy_preflight_and_rollback(self):
        host = Host(self, current={'sha': OLD, 'previous': None})
        (host.state / 'pending.json').write_text(json.dumps({'sha': NEW}))
        for command in ('deploy ' + NEW, 'preflight ' + NEW, 'rollback'):
            with self.subTest(command=command), self.assertRaises(DISPATCH.Rejected):
                host.run(command)
        self.assertEqual(host.events, [])

    # --- deploy sequence, schema fingerprints, pin and prune ------------------------------

    def test_deploy_sequence_records_schema_backup_pin_and_clears_pending_last(self):
        host = Host(self, current={'sha': OLD, 'previous': None, 'rollback_compatible': True})
        host.listing = (
            'ghcr.io/mattoyuzuru/mnema/frontend sha256:' + '1' * 64 + '\n'     # previous release: kept
            'ghcr.io/mattoyuzuru/mnema/frontend sha256:' + '2' * 64 + '\n'     # new release: kept
            'ghcr.io/mattoyuzuru/mnema/frontend sha256:' + '9' * 64 + '\n'     # old: removed
            'ghcr.io/mattoyuzuru/mnema/postgres sha256:' + '8' * 64 + '\n'     # old: removed
            'ghcr.io/mattoyuzuru/mnema/frontend <none>\n'                      # tag-only: untouched
            'ghcr.io/mattoyuzuru/mnema/unknown sha256:' + '7' * 64 + '\n'      # not a release service
            'docker.io/library/postgres sha256:' + '6' * 64 + '\n'             # foreign: untouched
            'ghcr.io/other/mnema/frontend sha256:' + '5' * 64 + '\n')          # foreign namespace
        written = []
        real = DISPATCH.write_state
        pin_calls = []

        def record(name, value):
            written.append((name, json.loads(json.dumps(value))))
            real(name, value)

        with patch.object(DISPATCH, 'write_state', side_effect=record), \
             patch.object(DISPATCH, 'write_postgres_pin', side_effect=lambda image: (
                 pin_calls.append(image), host.events.append(('pin', image)))):
            output = json.loads(host.run('deploy ' + NEW))
        self.assertEqual(output, {'target': 'mnema-prod', 'sha': NEW, 'readiness': 'passed',
                                  'backup': '20261008T010203Z-abcdef12.dump', 'schema_changed': False,
                                  'rollback_compatible': True, 'pruned_images': 2, 'offsite': 'uploaded',
                                  'config_changed': False})
        # Offsite is a best-effort step after the rollout, never inside the quiesced window.
        self.assertEqual(host.events[-1], ('offsite', '20261008T010203Z-abcdef12.dump'))
        self.assertEqual(written[0][1]['backup'], '20261008T010203Z-abcdef12.dump')
        self.assertEqual(host.events[:7], [
            ('compose', NEW, 'preflight'),
            ('pin', images('1')['postgres']),            # pin follows the release that is actually running
            ('fingerprint',), ('compose', NEW, 'stop'), ('backup',), ('compose', NEW, 'deploy'),
            ('fingerprint',)])
        self.assertEqual(host.events[7:9], [('pin', images('2')['postgres']), ('rm', 'ghcr.io/mattoyuzuru/mnema/frontend@sha256:' + '9' * 64)])
        self.assertEqual(host.events[9], ('rm', 'ghcr.io/mattoyuzuru/mnema/postgres@sha256:' + '8' * 64))
        self.assertEqual([name for name, _ in written], ['pending.json', 'current.json'])
        self.assertEqual(written[0][1]['sha'], NEW)
        current = host.state_file('current.json')
        self.assertEqual((current['sha'], current['previous'], current['rollback_compatible'], current['backup']),
                         (NEW, OLD, True, '20261008T010203Z-abcdef12.dump'))
        self.assertEqual(current['schema_before'], current['schema_after'])
        self.assertFalse((host.state / 'pending.json').exists())
        self.assertEqual(pin_calls, [images('1')['postgres'], images('2')['postgres']])

    def test_pin_is_written_to_a_root_only_file_and_aligned_only_with_the_running_image(self):
        host = Host(self, current={'sha': OLD, 'previous': None})
        with host.patches():
            DISPATCH.write_postgres_pin(images('2')['postgres'])
            pin = host.root / 'postgres-image'
            self.assertEqual(pin.read_text(), images('2')['postgres'] + '\n')
            self.assertEqual(pin.stat().st_mode & 0o777, 0o600)
            host.running_image = 'ghcr.io/mattoyuzuru/mnema/postgres@sha256:' + '3' * 64  # unexpected image
            DISPATCH.align_postgres_pin(DISPATCH.load_release(OLD))
            self.assertEqual(pin.read_text(), images('2')['postgres'] + '\n')
            host.running_image = images('1')['postgres']
            DISPATCH.align_postgres_pin(DISPATCH.load_release(OLD))
            self.assertEqual(pin.read_text(), images('1')['postgres'] + '\n')

    def test_schema_change_marks_the_release_not_rollback_compatible(self):
        host = Host(self, current={'sha': OLD, 'previous': None}, fingerprints=(FINGERPRINT, CHANGED))
        output = json.loads(host.run('deploy ' + NEW))
        self.assertTrue(output['schema_changed'])
        self.assertFalse(output['rollback_compatible'])
        current = host.state_file('current.json')
        self.assertEqual((current['schema_before'], current['schema_after']), (FINGERPRINT, CHANGED))
        # ...and rollback is then refused before anything is stopped.
        host.events.clear()
        with self.assertRaises(DISPATCH.Rejected):
            host.run('rollback')
        self.assertEqual(host.events, [])

    def test_repeating_a_schema_changing_release_does_not_forget_the_incompatibility(self):
        previous = {'sha': NEW, 'previous': OLD, 'schema_before': FINGERPRINT, 'schema_after': CHANGED,
                    'rollback_compatible': False}
        host = Host(self, current=previous, fingerprints=(CHANGED, CHANGED))
        output = json.loads(host.run('deploy ' + NEW))
        self.assertFalse(output['rollback_compatible'])
        current = host.state_file('current.json')
        self.assertEqual((current['previous'], current['schema_before']), (OLD, FINGERPRINT))

    def test_failed_backup_restarts_the_current_release_and_leaves_no_pending_marker(self):
        host = Host(self, current={'sha': OLD, 'previous': None},
                    backup_error=DISPATCH.Rejected('pre-deploy backup or restore rehearsal failed'))
        with self.assertRaisesRegex(DISPATCH.Rejected, 'current release restarted, nothing migrated'):
            host.run('deploy ' + NEW)
        self.assertEqual([e for e in host.events if e[0] == 'compose'],
                         [('compose', NEW, 'preflight'), ('compose', NEW, 'stop'), ('compose', OLD, 'deploy')])
        self.assertFalse((host.state / 'pending.json').exists())
        self.assertEqual(host.state_file('current.json')['sha'], OLD)

    WRITER_CONTAINERS = ('mnema-prod-identity-account-1', 'mnema-prod-learning-1')

    def test_failed_backup_without_a_recorded_release_starts_the_stopped_containers_again(self):
        host = Host(self, backup_error=DISPATCH.Rejected('x'))
        with self.assertRaisesRegex(DISPATCH.Rejected, 'stopped application containers were started again, nothing migrated'):
            host.run('deploy ' + NEW)
        self.assertIn(('start', *self.WRITER_CONTAINERS), host.events)    # exactly the two writers, by their exact names
        self.assertFalse((host.state / 'pending.json').exists())

    def test_a_current_release_compose_cannot_start_is_restarted_by_starting_its_containers(self):
        legacy = {s: v for s, v in images('3').items() if s != 'media-worker'}
        host = Host(self, current={'sha': OLD, 'previous': None}, backup_error=DISPATCH.Rejected('x'))
        (host.root / 'releases' / (OLD + '.json')).write_text(json.dumps({'sha': OLD, 'images': legacy}))

        def compose(data, operation):
            host.events.append(('compose', data['sha'], operation))
            if operation == 'deploy':
                raise DISPATCH.Rejected('release predates the media worker; deploy a newer release instead')

        with host.patches(), patch.object(DISPATCH, 'compose', side_effect=compose), \
             patch.object(DISPATCH.os, 'geteuid', return_value=0), contextlib.redirect_stderr(io.StringIO()), \
             patch.object(DISPATCH.sys, 'argv', ['dispatcher', 'deploy ' + NEW]), \
             self.assertRaisesRegex(DISPATCH.Rejected, 'were started again, nothing migrated'):
            DISPATCH.main()
        self.assertEqual(host.events[-1], ('start', *self.WRITER_CONTAINERS))
        self.assertFalse((host.state / 'pending.json').exists())

    def test_when_neither_compose_nor_docker_start_works_the_message_names_the_containers(self):
        host = Host(self, current={'sha': OLD, 'previous': None}, backup_error=DISPATCH.Rejected('x'),
                    start_error=DISPATCH.Rejected('docker operation failed'))

        def compose(data, operation):
            if operation == 'deploy':
                raise DISPATCH.Rejected('x')

        with host.patches(), patch.object(DISPATCH, 'compose', side_effect=compose), \
             patch.object(DISPATCH.os, 'geteuid', return_value=0), contextlib.redirect_stderr(io.StringIO()), \
             patch.object(DISPATCH.sys, 'argv', ['dispatcher', 'deploy ' + NEW]), \
             self.assertRaisesRegex(DISPATCH.Rejected, 'did not restart.*mnema-prod-identity-account-1 and mnema-prod-learning-1'):
            DISPATCH.main()

    def test_failed_rollout_keeps_the_pending_boundary_without_recording_success(self):
        host = Host(self, current={'sha': OLD, 'previous': None})

        def compose(data, operation):
            host.events.append(('compose', data['sha'], operation))
            if operation == 'deploy':
                raise DISPATCH.Rejected('compose operation failed; administrator inspection required')

        with host.patches(), patch.object(DISPATCH, 'compose', side_effect=compose), \
             patch.object(DISPATCH.os, 'geteuid', return_value=0), \
             patch.object(DISPATCH.sys, 'argv', ['dispatcher', 'deploy ' + NEW]), \
             contextlib.redirect_stderr(io.StringIO()), self.assertRaises(DISPATCH.Rejected):
            DISPATCH.main()
        self.assertEqual(host.state_file('pending.json')['sha'], NEW)
        self.assertEqual(host.state_file('current.json')['sha'], OLD)
        self.assertFalse((host.root / 'postgres-image').read_text().endswith('2' * 64 + '\n'))

    def test_prune_never_removes_kept_untagged_or_foreign_images_and_tolerates_in_use_images(self):
        host = Host(self)
        host.listing = (
            'ghcr.io/mattoyuzuru/mnema/learning sha256:' + '4' * 64 + '\n'
            'ghcr.io/mattoyuzuru/mnema/learning sha256:' + '1' * 64 + '\n'
            'quay.io/mattoyuzuru/mnema/learning sha256:' + '4' * 64 + '\n'
            'ghcr.io/mattoyuzuru/mnema/learning sha256:not-a-digest\n'
            'malformed line with spaces\n')
        with host.patches():
            self.assertEqual(DISPATCH.prune_images(list(images('1').values())), 1)
        self.assertEqual([e for e in host.events if e[0] == 'rm'],
                         [('rm', 'ghcr.io/mattoyuzuru/mnema/learning@sha256:' + '4' * 64)])
        with host.patches(), patch.object(DISPATCH, 'docker', side_effect=[
                host.listing, DISPATCH.Rejected('in use')]):
            self.assertEqual(DISPATCH.prune_images(list(images('1').values())), 0)

    # --- rollback ----------------------------------------------------------------------

    def test_rollback_requires_a_previous_release_and_recorded_compatibility(self):
        for current in (None, {'sha': NEW, 'previous': None, 'rollback_compatible': True},
                        {'sha': NEW, 'previous': OLD},  # historical record without the flag
                        {'sha': NEW, 'previous': OLD, 'rollback_compatible': False}):
            with self.subTest(current=current):
                host = Host(self, current=current)
                with self.assertRaises(DISPATCH.Rejected):
                    host.run('rollback')
                self.assertEqual(host.events, [])

    def test_compatible_rollback_uses_the_same_quiesce_backup_pending_flow(self):
        host = Host(self, current={'sha': NEW, 'previous': OLD, 'rollback_compatible': True})
        host.running_image = images('2')['postgres']
        output = json.loads(host.run('rollback'))
        self.assertEqual(output['sha'], OLD)
        self.assertEqual([e for e in host.events if e[0] in ('compose', 'backup')],
                         [('compose', OLD, 'preflight'), ('compose', OLD, 'stop'), ('backup',),
                          ('compose', OLD, 'deploy')])
        current = host.state_file('current.json')
        self.assertEqual((current['sha'], current['previous'], current['operation']), (OLD, None, 'rollback'))
        # No second rollback that would roll forward onto the schema just left.
        with self.assertRaises(DISPATCH.Rejected):
            host.run('rollback')
        self.assertFalse((host.state / 'pending.json').exists())

    # --- verify and status ------------------------------------------------------------------

    def verify(self, host, inspected, ready=True, runner=True):
        def docker(args, **kwargs):
            return inspected[args[-1]] + '\n'
        output = io.StringIO()
        with host.patches(), patch.object(DISPATCH, 'docker', side_effect=docker), \
             patch.object(DISPATCH, 'unit_active', return_value=runner) as active, \
             patch.object(DISPATCH, 'readiness_ok', return_value=ready), contextlib.redirect_stdout(output):
            try:
                DISPATCH.verify()
                error = None
            except DISPATCH.Rejected as exc:
                error = exc
        self.runner_checked = [call.args for call in active.call_args_list]
        return json.loads(output.getvalue()), error

    def test_verify_compares_every_running_image_with_the_recorded_manifest(self):
        host = Host(self, current={'sha': NEW, 'previous': OLD})
        inspected = {DISPATCH.CONTAINERS[s]: images('2')[s] for s in DISPATCH.COMPOSE_SERVICES}
        result, error = self.verify(host, inspected)
        self.assertIsNone(error)
        self.assertEqual((result['images_match'], result['readiness'], result['pending']), (True, 'passed', False))
        for service in DISPATCH.COMPOSE_SERVICES:
            with self.subTest(service=service):
                result, error = self.verify(host, {**inspected, DISPATCH.CONTAINERS[service]: images('9')[service]})
                self.assertFalse(result['images_match'])
                self.assertIsNotNone(error)
        result, error = self.verify(host, inspected, ready=False)
        self.assertEqual(result['readiness'], 'failed')
        self.assertIsNotNone(error)
        (host.state / 'pending.json').write_text('{}')
        result, error = self.verify(host, inspected)
        self.assertTrue(result['pending'])
        self.assertIsNotNone(error)

    def test_verify_requires_the_media_runner_service_and_skips_it_for_a_release_that_has_none(self):
        host = Host(self, current={'sha': NEW, 'previous': OLD})
        inspected = {DISPATCH.CONTAINERS[s]: images('2')[s] for s in DISPATCH.COMPOSE_SERVICES}
        result, error = self.verify(host, inspected)
        self.assertIsNone(error)
        self.assertEqual(self.runner_checked, [('mnema-media-runner.service',)])
        result, error = self.verify(host, inspected, runner=False)
        self.assertEqual(result['readiness'], 'failed')
        self.assertIsNotNone(error)
        legacy = {s: v for s, v in images('3').items() if s != 'media-worker'}
        (host.root / 'releases' / (OLD + '.json')).write_text(json.dumps({'sha': OLD, 'images': legacy}))
        (host.state / 'current.json').write_text(json.dumps({'sha': OLD, 'previous': None}))
        legacy_containers = {DISPATCH.CONTAINERS[s]: legacy[s] for s in DISPATCH.COMPOSE_SERVICES}
        result, error = self.verify(host, legacy_containers, runner=False)
        self.assertIsNone(error)
        self.assertEqual(self.runner_checked, [])
        self.assertEqual((result['images_match'], result['readiness']), (True, 'passed'))

    def test_the_media_worker_is_no_compose_service_and_has_no_container_to_compare(self):
        self.assertNotIn('media-worker', DISPATCH.CONTAINERS)
        self.assertIn('media-worker', DISPATCH.SERVICES)             # but it is still admitted, pulled and checked as an image
        self.assertEqual(set(DISPATCH.COMPOSE_SERVICES) | {'media-worker'}, set(DISPATCH.SERVICES))

    def test_the_media_runner_must_be_active_before_a_deploy(self):
        for active, expected in ((True, 0), (False, 1)):
            with self.subTest(active=active), patch.object(DISPATCH, 'unit_active', return_value=active) as check:
                if expected:
                    with self.assertRaisesRegex(DISPATCH.Rejected, 'media runner'):
                        DISPATCH.check_media_runner()
                else:
                    DISPATCH.check_media_runner()
                check.assert_called_once_with('mnema-media-runner.service')
        done = types.SimpleNamespace(returncode=0, stdout=b'active\n')
        with patch.object(DISPATCH.subprocess, 'run', return_value=done) as run:
            self.assertTrue(DISPATCH.unit_active('x.service'))
        self.assertEqual(run.call_args.args[0], ['/usr/bin/systemctl', 'is-active', 'x.service'])
        for result in (types.SimpleNamespace(returncode=3, stdout=b'inactive\n'), OSError(), subprocess.TimeoutExpired(['systemctl'], 15)):
            with patch.object(DISPATCH.subprocess, 'run', side_effect=result if isinstance(result, Exception) else None,
                              return_value=None if isinstance(result, Exception) else result):
                self.assertFalse(DISPATCH.unit_active('x.service'))

    def test_a_release_admitted_before_the_media_worker_stays_readable_but_can_never_be_deployed(self):
        host = Host(self, current={'sha': OLD, 'previous': None})
        legacy = {s: v for s, v in images('3').items() if s != 'media-worker'}
        (host.root / 'releases' / (OLD + '.json')).write_text(json.dumps({'sha': OLD, 'images': legacy}))
        with host.patches():
            self.assertEqual(DISPATCH.load_release(OLD)['images'], legacy)
        with self.assertRaises(DISPATCH.Rejected):
            DISPATCH.validate_images(legacy)
        with patch.object(DISPATCH, 'ROOT', host.root), patch.object(DISPATCH, 'protected', side_effect=lambda p: p), \
             self.assertRaisesRegex(DISPATCH.Rejected, 'predates the media worker'):
            DISPATCH.compose({'sha': OLD, 'images': legacy}, 'deploy')
        # an aborted rollout cannot restart such a release; the dispatcher says so instead of guessing
        self.assertFalse(DISPATCH.restart_current({'sha': OLD, 'images': legacy}))
        # a recorded rollback target of this shape is refused rather than half-started
        host = Host(self, current={'sha': NEW, 'previous': OLD, 'rollback_compatible': True})
        (host.root / 'releases' / (OLD + '.json')).write_text(json.dumps({'sha': OLD, 'images': legacy}))
        with patch.object(DISPATCH, 'ROOT', host.root), patch.object(DISPATCH, 'STATE', host.state), \
             patch.object(DISPATCH, 'protected', side_effect=lambda p: p), \
             patch.object(DISPATCH.shutil, 'disk_usage', return_value=types.SimpleNamespace(free=20 * 1024 ** 3)), \
             patch.object(DISPATCH, 'check_backup_tool'), patch.object(DISPATCH, 'check_media_work_root'), \
             patch.object(DISPATCH, 'check_media_runner'), \
             self.assertRaisesRegex(DISPATCH.Rejected, 'predates the media worker'):
            DISPATCH.release('rollback', None)

    def test_the_media_work_directory_is_its_own_root_owned_filesystem_with_a_private_learning_spool(self):
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            root = parent / 'media-work'
            message = 'media work directory missing or unsafe'
            trusted = patch.object(DISPATCH, 'protected', side_effect=lambda p: p)
            with trusted, self.assertRaisesRegex(DISPATCH.Rejected, message):
                DISPATCH.check_media_work_root(root)                       # absent
            (root / 'spool').mkdir(parents=True)
            real_lstat = Path.lstat

            def describe(root_uid=0, root_mode=0o755, device=2, spool_uid=10001, spool_gid=10001, spool_mode=0o700, spool_dir=True):
                def lstat(path):
                    info = real_lstat(path)
                    kind = stat.S_IFDIR if spool_dir else stat.S_IFREG
                    if path == root:
                        return types.SimpleNamespace(st_mode=stat.S_IFDIR | root_mode, st_uid=root_uid, st_gid=0, st_dev=device)
                    if path == root / 'spool':
                        return types.SimpleNamespace(st_mode=kind | spool_mode, st_uid=spool_uid, st_gid=spool_gid, st_dev=device)
                    return types.SimpleNamespace(st_mode=info.st_mode, st_uid=info.st_uid, st_gid=info.st_gid, st_dev=1)
                return lstat

            with trusted, patch.object(Path, 'lstat', describe()):
                DISPATCH.check_media_work_root(root)
            for name, changes in (('not owned by root', {'root_uid': 10001}), ('group writable', {'root_mode': 0o775}),
                                  ('world writable', {'root_mode': 0o757}),
                                  ('not a separate filesystem', {'device': 1}),
                                  ('spool owned by root', {'spool_uid': 0}), ('spool owned by the worker', {'spool_uid': 10002}),
                                  ('spool group readable', {'spool_mode': 0o750}), ('spool world readable', {'spool_mode': 0o705}),
                                  ('spool wrong group', {'spool_gid': 10003}), ('spool is not a directory', {'spool_dir': False})):
                with self.subTest(name), trusted, patch.object(Path, 'lstat', describe(**changes)), \
                     self.assertRaisesRegex(DISPATCH.Rejected, message):
                    DISPATCH.check_media_work_root(root)
            link = parent / 'link'
            link.symlink_to(root)
            with trusted, self.assertRaisesRegex(DISPATCH.Rejected, message):
                DISPATCH.check_media_work_root(link)                       # a symlink is never followed
            with patch.object(DISPATCH, 'protected', side_effect=DISPATCH.Rejected('x')), \
                 self.assertRaisesRegex(DISPATCH.Rejected, message):
                DISPATCH.check_media_work_root(root)                       # unsafe parent

    def test_a_deploy_is_refused_before_any_writer_stops_when_the_work_directory_is_unsafe(self):
        host = Host(self, current={'sha': OLD, 'previous': None})
        with host.patches(), patch.object(DISPATCH, 'check_media_work_root',
                                          side_effect=DISPATCH.Rejected('media work directory missing or unsafe')), \
             patch.object(DISPATCH.os, 'geteuid', return_value=0), contextlib.redirect_stderr(io.StringIO()), \
             patch.object(DISPATCH.sys, 'argv', ['dispatcher', 'deploy ' + NEW]), self.assertRaises(DISPATCH.Rejected):
            DISPATCH.main()
        self.assertNotIn(('compose', NEW, 'stop'), host.events)

    def test_verify_without_a_recorded_release_is_rejected(self):
        with self.assertRaises(DISPATCH.Rejected), Host(self).patches():
            DISPATCH.verify()

    def test_status_reports_config_hashes_admission_pending_backup_and_config_names_without_values(self):
        host = Host(self, current={'sha': OLD, 'previous': None, 'rollback_compatible': True})
        (host.state / 'pending.json').write_text(json.dumps({'sha': NEW, 'backup': '20261008T010203Z-abcdef12.dump'}))
        (host.root / 'app.env').write_text('GOOGLE_CLIENT_ID=visible-name-only\nMNEMA_PROMO_HASH_SECRET=hidden\n')
        config = {name: host.directory / name for name in DISPATCH.CONFIG_FILES}
        config['compose.yaml'].write_bytes(b'compose')
        output = io.StringIO()
        with host.patches(), patch.object(DISPATCH, 'CONFIG_FILES', config), contextlib.redirect_stdout(output):
            DISPATCH.status()
        text = output.getvalue()
        status = json.loads(text)
        self.assertEqual(set(status['config']), {
            'compose.yaml', 'nginx.conf', 'Caddyfile', 'mnema-deploy', 'mnema-local-backup', 'mnema-health',
            'mnema-health.service', 'mnema-health.timer', 'mnema-local-backup.service', 'mnema-local-backup.timer',
            'media-work.mount', 'mnema-media-runner', 'mnema-media-runner.service', 'mnema-deploy-ssh', 'mnema-deploy.sudoers', '60-mnema-deploy.conf'})
        self.assertEqual(status['config']['compose.yaml'], __import__('hashlib').sha256(b'compose').hexdigest())
        self.assertIsNone(status['config']['nginx.conf'])
        self.assertEqual((status['admitted_sha'], status['verified_sha'], status['rollback_compatible']),
                         (NEW, OLD, True))
        self.assertEqual(status['rollout_state'], 'needs_admin_reconciliation')
        self.assertEqual(status['pending_backup'], '20261008T010203Z-abcdef12.dump')
        self.assertEqual(status['app_config_names'], ['GOOGLE_CLIENT_ID', 'MNEMA_PROMO_HASH_SECRET'])
        self.assertNotIn('hidden', text)
        self.assertNotIn('visible-name-only', text)

    def test_config_hash_flags_unsafe_ownership_instead_of_trusting_the_file(self):
        path = Path(tempfile.mkdtemp()) / 'unit'
        path.write_text('x')
        with patch.object(DISPATCH, 'protected', side_effect=DISPATCH.Rejected('unsafe')):
            self.assertEqual(DISPATCH.file_sha256(path), 'unprotected')
        with patch.object(DISPATCH, 'protected', side_effect=lambda p: p):
            self.assertEqual(DISPATCH.file_sha256(path), __import__('hashlib').sha256(b'x').hexdigest())
            self.assertIsNone(DISPATCH.file_sha256(path.with_name('missing')))

    # --- lock and error output ------------------------------------------------------------

    def test_lock_waits_for_the_backup_timer_instead_of_failing_immediately(self):
        attempts = []

        def flock(lock, flags):
            attempts.append(flags)
            if len(attempts) < 4:
                raise BlockingIOError()

        clock = {'now': 0.0}
        sleeps = []

        def sleep(seconds):
            sleeps.append(seconds)
            clock['now'] += seconds

        with patch.object(DISPATCH.fcntl, 'flock', side_effect=flock):
            DISPATCH.acquire(object(), clock=lambda: clock['now'], sleep=sleep)
        self.assertEqual(len(attempts), 4)
        self.assertEqual(sleeps, [5, 5, 5])

    def test_lock_wait_is_bounded_to_ten_minutes(self):
        clock = {'now': 0.0}

        def sleep(seconds):
            clock['now'] += seconds

        with patch.object(DISPATCH.fcntl, 'flock', side_effect=BlockingIOError()), \
             self.assertRaisesRegex(DISPATCH.Rejected, 'holds the lock'):
            DISPATCH.acquire(object(), clock=lambda: clock['now'], sleep=sleep)
        self.assertEqual(DISPATCH.LOCK_WAIT_SECONDS, 600)
        self.assertEqual(clock['now'], 600)

    def test_rejections_print_their_static_reason_and_other_failures_stay_generic(self):
        generic = 'deployment operation failed; no sensitive diagnostics emitted'
        with tempfile.TemporaryDirectory() as temporary, patch.object(DISPATCH, 'LOCK', Path(temporary) / 'lock'), \
             patch.object(DISPATCH.os, 'geteuid', return_value=0), patch.object(DISPATCH.sys, 'argv', ['d', 'status']), \
             patch.object(DISPATCH, 'install_signal_handlers'):
            for error, expected in ((DISPATCH.Rejected('release binding invalid'), 'deployment rejected: release binding invalid'),
                                    (OSError('secret path /etc/mnema/runtime.env'), generic),
                                    (RuntimeError('secret value hunter2'), generic),
                                    (KeyError('hunter2'), generic),
                                    (subprocess.TimeoutExpired(['docker', 'secret-argument'], 1), generic)):
                with self.subTest(expected=expected, error=type(error).__name__), \
                     patch.object(DISPATCH, 'main', side_effect=error), \
                     contextlib.redirect_stderr(io.StringIO()) as stderr:
                    self.assertEqual(DISPATCH.entrypoint(), 1)
                self.assertEqual(stderr.getvalue().strip(), expected)
            with patch.object(DISPATCH, 'main'):
                self.assertEqual(DISPATCH.entrypoint(), 0)
            with patch.object(DISPATCH.os, 'geteuid', return_value=1000), contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(DISPATCH.entrypoint(), 1)

    def test_docker_failures_are_value_silent(self):
        failing = types.SimpleNamespace(returncode=1, stdout=b'', stderr=b'password=hunter2')
        with patch.object(DISPATCH.subprocess, 'run', return_value=failing), \
             self.assertRaises(DISPATCH.Rejected) as caught:
            DISPATCH.docker(['inspect', 'x'])
        self.assertNotIn('hunter2', str(caught.exception))

    def test_schema_fingerprint_reads_both_flyway_histories_and_tolerates_a_fresh_database(self):
        answers = iter(['t', '[3,3,"abc"]', 'f'])
        statements = []

        def psql(statement):
            statements.append(statement)
            return next(answers)

        with patch.object(DISPATCH, 'psql', side_effect=psql):
            result = DISPATCH.schema_fingerprint()
        self.assertEqual(result, {'app_identity': {'count': 3, 'max_rank': 3, 'md5': 'abc'}, 'app_learning': None})
        self.assertIn('app_identity.flyway_schema_history', statements[0])
        self.assertIn('WHERE success', statements[1])

    # --- survival of the caller, signals and crash windows -----------------------------------

    def test_hangup_and_closed_pipe_are_ignored_and_sigterm_runs_recovery_handlers(self):
        import signal
        saved = {sig: signal.getsignal(sig) for sig in (signal.SIGHUP, signal.SIGPIPE, signal.SIGTERM)}
        try:
            DISPATCH.install_signal_handlers()
            self.assertEqual(signal.getsignal(signal.SIGHUP), signal.SIG_IGN)
            self.assertEqual(signal.getsignal(signal.SIGPIPE), signal.SIG_IGN)
            with self.assertRaises(SystemExit):
                signal.getsignal(signal.SIGTERM)(signal.SIGTERM, None)
        finally:
            for sig, handler in saved.items():
                signal.signal(sig, handler)

    def test_output_helpers_swallow_a_vanished_ssh_session(self):
        class Broken:
            def write(self, text):
                raise BrokenPipeError()

            def flush(self):
                raise BrokenPipeError()

        with patch.object(DISPATCH.sys, 'stdout', Broken()), patch.object(DISPATCH.sys, 'stderr', Broken()):
            DISPATCH.phase('rollout')
            DISPATCH.output({'ok': True})
            DISPATCH.emit('deployment rejected: x', DISPATCH.sys.stderr)

    def test_a_deployment_completes_and_records_state_when_stdout_and_stderr_are_gone(self):
        host = Host(self, current={'sha': OLD, 'previous': None})

        class Broken:
            def write(self, text):
                raise BrokenPipeError()

            def flush(self):
                raise BrokenPipeError()

        with host.patches(), patch.object(DISPATCH.os, 'geteuid', return_value=0), \
             patch.object(DISPATCH.sys, 'argv', ['dispatcher', 'deploy ' + NEW]), \
             patch.object(DISPATCH.sys, 'stdout', Broken()), patch.object(DISPATCH.sys, 'stderr', Broken()):
            DISPATCH.main()
        self.assertEqual(host.state_file('current.json')['sha'], NEW)
        self.assertFalse((host.state / 'pending.json').exists())

    def test_any_failure_between_stopping_writers_and_the_pending_marker_restarts_the_current_release(self):
        causes = {
            'rejected backup': DISPATCH.Rejected('x'),
            'unexpected exception': RuntimeError('boom'),
            'os error': OSError('disk'),
            'timeout': subprocess.TimeoutExpired(['backup'], 1),
            'caller loss (SIGTERM)': SystemExit(143),
            'interrupt': KeyboardInterrupt(),
        }
        for label, cause in causes.items():
            with self.subTest(label):
                host = Host(self, current={'sha': OLD, 'previous': None}, backup_error=cause)
                expected = DISPATCH.Rejected if isinstance(cause, Exception) else type(cause)
                with self.assertRaises(expected) as caught:
                    host.run('deploy ' + NEW)
                if isinstance(cause, Exception):
                    self.assertIn('current release restarted, nothing migrated', str(caught.exception))
                self.assertEqual([e for e in host.events if e[0] == 'compose'][-2:],
                                 [('compose', NEW, 'stop'), ('compose', OLD, 'deploy')])
                self.assertFalse((host.state / 'pending.json').exists())
                self.assertEqual(host.state_file('current.json')['sha'], OLD)
                self.assertNotIn(('offsite', '20261008T010203Z-abcdef12.dump'), host.events)

    def test_failure_of_the_stop_itself_also_restarts_and_a_failed_restart_is_reported(self):
        host = Host(self, current={'sha': OLD, 'previous': None})
        calls = []

        def compose(data, operation):
            calls.append((data['sha'], operation))
            if operation in ('stop', 'deploy'):
                raise subprocess.TimeoutExpired(['docker'], 1) if operation == 'stop' else DISPATCH.Rejected('x')

        with host.patches(), patch.object(DISPATCH, 'compose', side_effect=compose), \
             patch.object(DISPATCH.os, 'geteuid', return_value=0), contextlib.redirect_stderr(io.StringIO()), \
             patch.object(DISPATCH.sys, 'argv', ['dispatcher', 'deploy ' + NEW]), \
             self.assertRaisesRegex(DISPATCH.Rejected, 'started again, nothing migrated'):
            DISPATCH.main()
        self.assertEqual(calls[-2:], [(NEW, 'stop'), (OLD, 'deploy')])
        self.assertEqual(host.events[-1], ('start', *self.WRITER_CONTAINERS))

    def test_static_backup_tool_checks_run_before_any_writer_is_stopped(self):
        host = Host(self, current={'sha': OLD, 'previous': None})
        with host.patches(), patch.object(DISPATCH, 'check_backup_tool', side_effect=DISPATCH.Rejected('backup tool is not executable')), \
             patch.object(DISPATCH.os, 'geteuid', return_value=0), contextlib.redirect_stderr(io.StringIO()), \
             patch.object(DISPATCH.sys, 'argv', ['dispatcher', 'deploy ' + NEW]), self.assertRaises(DISPATCH.Rejected):
            DISPATCH.main()
        self.assertNotIn(('compose', NEW, 'stop'), host.events)
        with tempfile.TemporaryDirectory() as temporary:
            tool = Path(temporary) / 'tool'
            tool.write_text('x')
            tool.chmod(0o644)
            with patch.object(DISPATCH, 'BACKUP_TOOL', tool), patch.object(DISPATCH, 'protected', side_effect=lambda p: p), \
                 self.assertRaisesRegex(DISPATCH.Rejected, 'not executable'):
                DISPATCH.check_backup_tool()
            tool.chmod(0o755)
            with patch.object(DISPATCH, 'BACKUP_TOOL', tool), patch.object(DISPATCH, 'protected', side_effect=lambda p: p):
                DISPATCH.check_backup_tool()
            with patch.object(DISPATCH, 'BACKUP_TOOL', tool), \
                 patch.object(DISPATCH, 'protected', side_effect=DISPATCH.Rejected('configuration ownership or permissions invalid')), \
                 self.assertRaises(DISPATCH.Rejected):
                DISPATCH.check_backup_tool()

    # --- backup tool integration -------------------------------------------------------------

    def test_pre_deploy_backup_parses_the_tools_last_json_line_and_rejects_everything_else(self):
        name = '20261008T010203Z-abcdef12.dump'
        good = (0, b'noise\n' + json.dumps({'backup': name, 'verified': 'restore'}).encode() + b'\n')
        with patch.object(DISPATCH, 'check_backup_tool'):
            with patch.object(DISPATCH, 'run_bounded', return_value=good) as run:
                self.assertEqual(DISPATCH.pre_deploy_backup(), name)
            self.assertEqual(run.call_args.args[1], 15 * 60)
            self.assertEqual(run.call_args.args[0][1], 'pre-deploy')
            for label, result in {'exit code': (1, good[1]), 'no output': (0, b''), 'not json': (0, b'oops'),
                                  'no name': (0, b'{"x": 1}'), 'wrong type': (0, b'{"backup": 5}'),
                                  'path name': (0, b'{"backup": "../../x.dump"}'),
                                  'metadata name': (0, b'{"backup": "20261008T010203Z-abcdef12.json"}')}.items():
                with self.subTest(label), patch.object(DISPATCH, 'run_bounded', return_value=result), \
                     self.assertRaises(DISPATCH.Rejected):
                    DISPATCH.pre_deploy_backup()
            for error, text in ((subprocess.TimeoutExpired(['b'], 1), 'timed out'), (FileNotFoundError(), 'could not be started')):
                with self.subTest(text), patch.object(DISPATCH, 'run_bounded', side_effect=error), \
                     self.assertRaisesRegex(DISPATCH.Rejected, text):
                    DISPATCH.pre_deploy_backup()
        with patch.object(DISPATCH, 'BACKUP_TOOL', Path('/nonexistent/mnema-local-backup')), \
             patch.object(DISPATCH, 'protected', side_effect=FileNotFoundError()), self.assertRaises(FileNotFoundError):
            DISPATCH.pre_deploy_backup()  # entrypoint turns this into the generic failure, before any writer stops

    def test_run_bounded_terminates_the_whole_process_group_on_timeout(self):
        with tempfile.TemporaryDirectory() as temporary:
            pidfile = Path(temporary) / 'pid'
            script = 'sleep 60 & echo $! > "$0"; wait'
            started = time.monotonic()
            with self.assertRaises(subprocess.TimeoutExpired):
                DISPATCH.run_bounded(['/bin/sh', '-c', script, str(pidfile)], 0.5, grace=2)
            self.assertLess(time.monotonic() - started, 10)
            child = int(pidfile.read_text())
            for _ in range(40):
                try:
                    os.kill(child, 0)
                except ProcessLookupError:
                    break
                time.sleep(0.05)
            else:
                os.kill(child, 9)
                self.fail('background child survived the timeout')

    def test_run_bounded_escalates_to_sigkill_when_the_child_ignores_sigterm(self):
        with tempfile.TemporaryDirectory() as temporary:
            started = time.monotonic()
            with self.assertRaises(subprocess.TimeoutExpired):
                DISPATCH.run_bounded(['/bin/sh', '-c', 'trap "" TERM; sleep 60'], 0.3, grace=0.5)
            self.assertLess(time.monotonic() - started, 10)

    def test_run_bounded_returns_exit_code_and_stdout(self):
        self.assertEqual(DISPATCH.run_bounded(['/bin/sh', '-c', 'echo hi; exit 3'], 5), (3, b'hi\n'))

    def test_offsite_status_comes_from_the_tools_exit_code_and_never_raises(self):
        name = '20261008T010203Z-abcdef12.dump'
        for result, expected in (((0, b''), 'uploaded'), ((3, b''), 'disabled'), ((1, b''), 'failed'), ((143, b''), 'failed'),
                                 (subprocess.TimeoutExpired(['b'], 1), 'failed'), (OSError(), 'failed')):
            with self.subTest(expected=expected, result=result), patch.object(
                    DISPATCH, 'run_bounded', side_effect=result if isinstance(result, Exception) else None,
                    return_value=None if isinstance(result, Exception) else result) as run:
                self.assertEqual(DISPATCH.offsite_upload(name), expected)
            self.assertEqual(run.call_args.args[0][1:], ['offsite', name[:-5]])
            self.assertEqual(run.call_args.args[1], 5 * 60)

    def test_backup_age_comes_from_the_backup_tools_definition_of_complete(self):
        now = time.time()
        with patch.object(DISPATCH, 'check_backup_tool'), \
             patch.object(DISPATCH, 'run_bounded', return_value=(0, json.dumps({'backup': 'x', 'modified': now - 7200}).encode())):
            self.assertEqual(DISPATCH.newest_backup_age_hours(), 2.0)
        # status never runs a tool that fails the root-owned/executable check
        with patch.object(DISPATCH, 'check_backup_tool', side_effect=DISPATCH.Rejected('unsafe')), \
             patch.object(DISPATCH, 'run_bounded') as run:
            self.assertIsNone(DISPATCH.newest_backup_age_hours())
        run.assert_not_called()
        for result in ((0, b'{"backup": null, "modified": null}'), (1, b''), (0, b'garbage')):
            with self.subTest(result=result), patch.object(DISPATCH, 'check_backup_tool'), \
                 patch.object(DISPATCH, 'run_bounded', return_value=result):
                self.assertIsNone(DISPATCH.newest_backup_age_hours())
        with patch.object(DISPATCH, 'check_backup_tool'), patch.object(DISPATCH, 'run_bounded', side_effect=OSError()):
            self.assertIsNone(DISPATCH.newest_backup_age_hours())

    # --- application configuration -----------------------------------------------------------

    def test_the_allowlist_matches_the_ci_key_list_and_the_workflow_environment(self):
        keys = [line.strip() for line in (ROOT / 'deploy/production/app-config.keys').read_text().splitlines()
                if line.strip() and not line.startswith('#')]
        self.assertEqual(list(DISPATCH.APP_ENV_NAMES), keys)
        workflow = (ROOT / '.github/workflows/deploy.yaml').read_text()
        for name in keys:
            self.assertIn(f'          PROD_{name}: ${{{{ secrets.PROD_{name} }}}}\n', workflow)
        self.assertNotIn('toJSON(secrets)', workflow)
        self.assertNotIn('secrets: inherit', workflow)

    def test_configure_writes_a_sorted_root_only_file_and_prints_names_only(self):
        host = Host(self)
        payload = {'MNEMA_PROMO_HASH_SECRET': 'Zm9vYmFy-_~:/+=@.', 'GOOGLE_CLIENT_ID': '123-abc.apps.example', 'YANDEX_CLIENT_ID': ''}
        output = io.StringIO()
        with host.patches(), contextlib.redirect_stdout(output):
            DISPATCH.configure(json.dumps(payload).encode())
        app = host.root / 'app.env.next'
        self.assertFalse((host.root / 'app.env').exists())     # staged only; promotion belongs to the deploy
        self.assertEqual(app.read_text(), 'GOOGLE_CLIENT_ID=123-abc.apps.example\nMNEMA_PROMO_HASH_SECRET=Zm9vYmFy-_~:/+=@.\n')
        self.assertEqual(app.stat().st_mode & 0o777, 0o600)
        result = json.loads(output.getvalue())
        self.assertEqual(result['configured'], ['GOOGLE_CLIENT_ID', 'MNEMA_PROMO_HASH_SECRET'])
        self.assertEqual(set(result), {'target', 'configured', 'names_sha256'})
        self.assertNotIn('Zm9vYmFy', output.getvalue())
        # A new payload replaces the file atomically; an all-empty payload clears it.
        with host.patches(), contextlib.redirect_stdout(io.StringIO()):
            DISPATCH.configure(b'{"GH_CLIENT_ID": "x1"}')
            self.assertEqual(app.read_text(), 'GH_CLIENT_ID=x1\n')
            DISPATCH.configure(b'{}')
        self.assertEqual(app.read_text(), '')
        self.assertEqual(list(host.root.glob('tmp*')), [])

    def test_configure_rejects_unknown_names_and_unsafe_values_without_writing(self):
        host = Host(self)
        (host.root / 'app.env.next').write_text('GH_CLIENT_ID=keep\n')
        bad = {
            'mode typo': {'MNEMA_IDENTITY_TURNSTILE_MODE': 'Required'},
            'mode off': {'MNEMA_IDENTITY_TURNSTILE_MODE': 'disabled'},
            'required without keys': {'MNEMA_IDENTITY_TURNSTILE_MODE': 'required'},
            'required with one key': {'MNEMA_IDENTITY_TURNSTILE_MODE': 'required', 'TURNSTILE_SITE_KEY': 'site-key-1'},
            'unknown name': {'PATH': 'x'},
            'compose override': {'MNEMA_POSTGRES_IMAGE': 'x'},
            'number': {'GH_CLIENT_ID': 5},
            'null': {'GH_CLIENT_ID': None},
            'space': {'GH_CLIENT_ID': 'a b'},
            'newline': {'GH_CLIENT_ID': 'a\nPATH=/x'},
            'quote': {'GH_CLIENT_ID': 'a"b'},
            'apostrophe': {'GH_CLIENT_ID': "a'b"},
            'backslash': {'GH_CLIENT_ID': 'a\\b'},
            'dollar': {'GH_CLIENT_ID': '${HOME}'},
            'backtick': {'GH_CLIENT_ID': '`id`'},
            'comment': {'GH_CLIENT_ID': '#x'},
            'non-ascii': {'GH_CLIENT_ID': 'caf\u00e9'},
            'control': {'GH_CLIENT_ID': 'a\x00b'},
            'too long': {'GH_CLIENT_ID': 'a' * 4097},
        }
        for label, document in bad.items():
            with self.subTest(label), host.patches(), self.assertRaises(DISPATCH.Rejected):
                DISPATCH.configure(json.dumps(document).encode())
        for label, data in (('not json', b'{'), ('array', b'[]'), ('oversize', b' ' * (DISPATCH.MAX_CONFIG_BYTES + 1))):
            with self.subTest(label), host.patches(), self.assertRaises(DISPATCH.Rejected):
                DISPATCH.configure(data)
        self.assertEqual((host.root / 'app.env.next').read_text(), 'GH_CLIENT_ID=keep\n')
        with host.patches(), contextlib.redirect_stdout(io.StringIO()):
            DISPATCH.configure(json.dumps({'GH_CLIENT_ID': 'a' * 4096}).encode())  # boundary is accepted
            DISPATCH.configure(json.dumps({'MNEMA_IDENTITY_TURNSTILE_MODE': 'blocked'}).encode())
            DISPATCH.configure(json.dumps({'MNEMA_IDENTITY_TURNSTILE_MODE': 'required', 'TURNSTILE_SITE_KEY': 'site-key-1',
                                           'TURNSTILE_SECRET_KEY': 'secret-key-1'}).encode())

    def test_realistic_yandex_static_keys_are_accepted(self):
        key_id = 'YCAJE' + 'aB3-_x9Qz7LmN2pR5tUv'          # 25 characters
        secret = 'YCM' + 'a1B2-c3D4_e5F6-g7H8_i9J0kLmNoPqRsTuVw'[:37]   # 40 characters
        self.assertEqual((len(key_id), len(secret)), (25, 40))
        names = ('LEARNING_MEDIA_UPLOAD_ACCESS_KEY', 'LEARNING_MEDIA_UPLOAD_SECRET_KEY',
                 'MNEMA_AVATAR_ACCESS_KEY', 'MNEMA_AVATAR_SECRET_KEY')
        payload = {names[0]: key_id, names[1]: secret, names[2]: key_id[::-1], names[3]: secret[::-1]}
        self.assertEqual(DISPATCH.validate_app_config(json.dumps(payload).encode()), payload)
        for name in names:
            self.assertIn(name, DISPATCH.APP_ENV_NAMES)

    def test_billing_checkout_mode_and_base64_terminal_password_are_validated(self):
        payload = {'MNEMA_BILLING_CHECKOUT': 'TESTERS', 'MNEMA_TBANK_TERMINAL_KEY': '1700000000000DEMO',
                   'MNEMA_TBANK_PASSWORD_BASE64': 'Zml4dHVyZSRQYTU1d29yZA==',
                   'MNEMA_BILLING_TESTER_ACCOUNT_IDS': '0199c7a2-3b4e-7c1d-9a2b-5e6f7a8b9c0d,0199c7a2-3b4e-7c1d-9a2b-5e6f7a8b9c0e'}
        self.assertEqual(DISPATCH.validate_app_config(json.dumps(payload).encode()), payload)
        for mode in ('OFF', 'ON'):
            DISPATCH.validate_app_config(json.dumps({'MNEMA_BILLING_CHECKOUT': mode}).encode())
        for mode in ('on', 'TESTER', 'ENABLED'):
            with self.assertRaises(DISPATCH.Rejected):
                DISPATCH.validate_app_config(json.dumps({'MNEMA_BILLING_CHECKOUT': mode}).encode())
        # A raw bank password may contain '$'; the channel refuses it, which is why the password travels base64-encoded.
        with self.assertRaises(DISPATCH.Rejected):
            DISPATCH.validate_app_config(json.dumps({'MNEMA_TBANK_PASSWORD_BASE64': 'fixture$Pa55word'}).encode())

    def test_npd_receipt_mode_inn_and_base64_password_are_validated(self):
        payload = {'MNEMA_NPD_RECEIPTS': 'ON', 'MNEMA_NPD_INN': '770123456789', 'MNEMA_NPD_PASSWORD_BASE64': 'bnBkJFBhNTV3b3JkLVNlY3JldA=='}
        self.assertEqual(DISPATCH.validate_app_config(json.dumps(payload).encode()), payload)
        DISPATCH.validate_app_config(json.dumps({'MNEMA_NPD_RECEIPTS': 'OFF'}).encode())
        # An empty value means absent, like every other key.
        self.assertEqual(DISPATCH.validate_app_config(json.dumps({'MNEMA_NPD_RECEIPTS': '', 'MNEMA_NPD_INN': ''}).encode()), {})
        for name, value in (('MNEMA_NPD_RECEIPTS', 'on'), ('MNEMA_NPD_RECEIPTS', 'TESTERS'), ('MNEMA_NPD_INN', '77012345678'),
                            ('MNEMA_NPD_INN', '7701234567890'), ('MNEMA_NPD_INN', '77012345678x')):
            with self.assertRaises(DISPATCH.Rejected) as caught:
                DISPATCH.validate_app_config(json.dumps({name: value}).encode())
            self.assertIn(name, str(caught.exception))
            self.assertNotIn('=' + value, str(caught.exception))
        # A raw password may contain '$'; the channel refuses it, which is why the password travels base64-encoded.
        with self.assertRaises(DISPATCH.Rejected):
            DISPATCH.validate_app_config(json.dumps({'MNEMA_NPD_PASSWORD_BASE64': 'npd$Pa55word'}).encode())

    def test_rejection_messages_name_the_key_but_never_the_value(self):
        with self.assertRaises(DISPATCH.Rejected) as caught:
            DISPATCH.validate_app_config(b'{"GH_CLIENT_ID": "hunter 2"}')
        self.assertIn('GH_CLIENT_ID', str(caught.exception))
        self.assertNotIn('hunter', str(caught.exception))

    def compose_command(self, host, app_mode=None, operation='deploy'):
        captured = []
        real = tempfile.NamedTemporaryFile
        if app_mode is not None:
            (host.root / 'app.env').write_text('GH_CLIENT_ID=x\n')
            (host.root / 'app.env').chmod(app_mode)
        (host.root / 'compose.yaml').write_text('services: {}')
        (host.root / 'runtime.env').write_text('A=1\n')
        (host.root / 'runtime.env').chmod(0o600)
        with patch.object(DISPATCH, 'ROOT', host.root), patch.object(DISPATCH, 'protected', side_effect=lambda p: p), \
             patch.object(DISPATCH.tempfile, 'NamedTemporaryFile', side_effect=lambda **kw: real(**{**kw, 'dir': None})), \
             patch.object(DISPATCH.subprocess, 'run', side_effect=lambda command, **kw: captured.append(command) or
                          types.SimpleNamespace(returncode=0)):
            DISPATCH.compose({'sha': NEW, 'images': images('2')}, operation)
        return captured[0]

    def test_compose_env_files_are_ordered_host_then_ci_config_then_release(self):
        command = self.compose_command(Host(self), app_mode=0o600)
        files = [command[i + 1] for i, part in enumerate(command) if part == '--env-file']
        self.assertEqual(len(files), 3)
        self.assertTrue(files[0].endswith('/runtime.env'))
        self.assertTrue(files[1].endswith('/app.env'))
        self.assertIn('mnema-release-', files[2])
        self.assertLess(command.index('--env-file'), command.index('--file'))

    def test_compose_without_app_config_keeps_the_two_original_env_files_and_rejects_loose_modes(self):
        command = self.compose_command(Host(self))
        self.assertEqual(len([part for part in command if part == '--env-file']), 2)
        with self.assertRaisesRegex(DISPATCH.Rejected, 'application configuration requires mode 0600'):
            self.compose_command(Host(self), app_mode=0o644)

    # --- app.env is part of the release transaction ------------------------------------------

    def test_a_staged_configuration_is_promoted_only_after_the_pending_marker_exists(self):
        host = Host(self, current={'sha': OLD, 'previous': None, 'rollback_compatible': True})
        (host.root / 'app.env').write_text('GH_CLIENT_ID=old\n')
        (host.root / 'app.env.next').write_text('GH_CLIENT_ID=new\n')
        seen = {}

        def compose(data, operation):
            host.events.append(('compose', data['sha'], operation))
            if operation == 'deploy':
                seen['app'] = (host.root / 'app.env').read_text()
                seen['pending'] = (host.state / 'pending.json').exists()

        with host.patches(), patch.object(DISPATCH, 'compose', side_effect=compose), \
             patch.object(DISPATCH.os, 'geteuid', return_value=0), contextlib.redirect_stdout(io.StringIO()) as out, \
             contextlib.redirect_stderr(io.StringIO()), patch.object(DISPATCH.sys, 'argv', ['d', 'deploy ' + NEW]):
            DISPATCH.main()
        self.assertEqual(seen, {'app': 'GH_CLIENT_ID=new\n', 'pending': True})
        self.assertEqual((host.root / 'app.env').read_text(), 'GH_CLIENT_ID=new\n')
        self.assertEqual((host.root / 'app.env.previous').read_text(), 'GH_CLIENT_ID=old\n')
        self.assertFalse((host.root / 'app.env.next').exists())
        self.assertEqual((host.root / 'app.env').stat().st_mode & 0o777, 0o600)
        self.assertTrue(host.state_file('current.json')['config_changed'])
        self.assertTrue(json.loads(out.getvalue())['config_changed'])

    def test_an_abort_before_the_pending_marker_restarts_with_the_unchanged_configuration(self):
        host = Host(self, current={'sha': OLD, 'previous': None}, backup_error=DISPATCH.Rejected('x'))
        (host.root / 'app.env').write_text('GH_CLIENT_ID=old\n')
        (host.root / 'app.env.next').write_text('GH_CLIENT_ID=new\n')
        restart = {}

        def compose(data, operation):
            host.events.append(('compose', data['sha'], operation))
            if operation == 'deploy':
                restart['app'] = (host.root / 'app.env').read_text()

        with host.patches(), patch.object(DISPATCH, 'compose', side_effect=compose), \
             patch.object(DISPATCH.os, 'geteuid', return_value=0), contextlib.redirect_stderr(io.StringIO()), \
             patch.object(DISPATCH.sys, 'argv', ['d', 'deploy ' + NEW]), self.assertRaises(DISPATCH.Rejected):
            DISPATCH.main()
        self.assertEqual(restart, {'app': 'GH_CLIENT_ID=old\n'})
        self.assertEqual((host.root / 'app.env').read_text(), 'GH_CLIENT_ID=old\n')
        self.assertEqual((host.root / 'app.env.next').read_text(), 'GH_CLIENT_ID=new\n')   # still staged
        self.assertFalse((host.root / 'app.env.previous').exists())

    def test_a_rollout_failure_after_the_marker_leaves_state_and_the_previous_file_for_the_administrator(self):
        host = Host(self, current={'sha': OLD, 'previous': None})
        (host.root / 'app.env').write_text('GH_CLIENT_ID=old\n')
        (host.root / 'app.env.next').write_text('GH_CLIENT_ID=new\n')

        def compose(data, operation):
            if operation == 'deploy':
                raise DISPATCH.Rejected('compose operation failed; administrator inspection required')

        with host.patches(), patch.object(DISPATCH, 'compose', side_effect=compose), \
             patch.object(DISPATCH.os, 'geteuid', return_value=0), contextlib.redirect_stderr(io.StringIO()), \
             patch.object(DISPATCH.sys, 'argv', ['d', 'deploy ' + NEW]), self.assertRaises(DISPATCH.Rejected):
            DISPATCH.main()
        self.assertTrue((host.state / 'pending.json').exists())
        self.assertEqual((host.root / 'app.env.previous').read_text(), 'GH_CLIENT_ID=old\n')
        self.assertEqual(host.state_file('current.json')['sha'], OLD)

    def test_an_identical_staged_configuration_does_not_clobber_the_previous_file(self):
        host = Host(self, current={'sha': OLD, 'previous': None})
        (host.root / 'app.env').write_text('GH_CLIENT_ID=same\n')
        (host.root / 'app.env.previous').write_text('GH_CLIENT_ID=older\n')
        (host.root / 'app.env.next').write_text('GH_CLIENT_ID=same\n')
        output = json.loads(host.run('deploy ' + NEW))
        self.assertFalse(output['config_changed'])
        self.assertEqual((host.root / 'app.env.previous').read_text(), 'GH_CLIENT_ID=older\n')
        self.assertFalse((host.root / 'app.env.next').exists())

    def test_a_deploy_without_staged_configuration_keeps_app_env(self):
        host = Host(self, current={'sha': OLD, 'previous': None})
        (host.root / 'app.env').write_text('GH_CLIENT_ID=keep\n')
        self.assertFalse(json.loads(host.run('deploy ' + NEW))['config_changed'])
        self.assertEqual((host.root / 'app.env').read_text(), 'GH_CLIENT_ID=keep\n')

    def test_rollback_restores_the_previous_configuration_when_the_left_release_changed_it(self):
        host = Host(self, current={'sha': NEW, 'previous': OLD, 'rollback_compatible': True, 'config_changed': True})
        host.running_image = images('2')['postgres']
        (host.root / 'app.env').write_text('GH_CLIENT_ID=new\n')
        (host.root / 'app.env.previous').write_text('GH_CLIENT_ID=old\n')
        (host.root / 'app.env.next').write_text('GH_CLIENT_ID=staged-later\n')
        output = json.loads(host.run('rollback'))
        self.assertEqual(output['sha'], OLD)
        self.assertEqual((host.root / 'app.env').read_text(), 'GH_CLIENT_ID=old\n')
        self.assertEqual((host.root / 'app.env.previous').read_text(), 'GH_CLIENT_ID=new\n')
        self.assertEqual((host.root / 'app.env.next').read_text(), 'GH_CLIENT_ID=staged-later\n')
        self.assertFalse(output['config_changed'])

    def test_rollback_leaves_the_configuration_alone_when_the_left_release_did_not_change_it(self):
        host = Host(self, current={'sha': NEW, 'previous': OLD, 'rollback_compatible': True, 'config_changed': False})
        host.running_image = images('2')['postgres']
        (host.root / 'app.env').write_text('GH_CLIENT_ID=current\n')
        (host.root / 'app.env.previous').write_text('GH_CLIENT_ID=stale-from-earlier\n')
        host.run('rollback')
        self.assertEqual((host.root / 'app.env').read_text(), 'GH_CLIENT_ID=current\n')

    def test_preflight_validates_the_configuration_about_to_be_promoted(self):
        host = Host(self)
        (host.root / 'app.env').write_text('GH_CLIENT_ID=old\n')
        (host.root / 'app.env.next').write_text('GH_CLIENT_ID=new\n')
        for name in ('app.env', 'app.env.next'):
            (host.root / name).chmod(0o600)
        for operation, expected in (('preflight', 'app.env.next'), ('deploy', 'app.env')):
            command = self.compose_command(host, operation=operation)
            files = [command[i + 1] for i, part in enumerate(command) if part == '--env-file']
            self.assertTrue(files[1].endswith('/' + expected), (operation, files))

    def test_status_lists_staged_names_separately_from_active_names(self):
        host = Host(self)
        (host.root / 'app.env').write_text('GH_CLIENT_ID=a\n')
        (host.root / 'app.env.next').write_text('GH_CLIENT_ID=b\nGOOGLE_CLIENT_ID=c\n')
        output = io.StringIO()
        with host.patches(), patch.object(DISPATCH, 'CONFIG_FILES', {}), contextlib.redirect_stdout(output):
            DISPATCH.status()
        status = json.loads(output.getvalue())
        self.assertEqual((status['app_config_names'], status['app_config_next_names']),
                         (['GH_CLIENT_ID'], ['GH_CLIENT_ID', 'GOOGLE_CLIENT_ID']))
        self.assertNotIn('=b', output.getvalue())

    def test_pruning_stops_at_its_time_budget(self):
        host = Host(self)
        host.listing = ''.join('ghcr.io/mattoyuzuru/mnema/frontend sha256:%s\n' % (str(i) * 64) for i in range(3, 9))
        clock = iter([0, 1, 1000, 1000, 1000, 1000, 1000, 1000])
        with host.patches(), patch.object(DISPATCH.time, 'monotonic', side_effect=lambda: next(clock)):
            removed = DISPATCH.prune_images(list(images('1').values()))
        self.assertEqual(removed, 1)
        self.assertEqual(DISPATCH.PRUNE_BUDGET_SECONDS, 120)


if __name__ == '__main__':
    unittest.main()
