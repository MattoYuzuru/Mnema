"""Behavioral checks for bounded health, private bootstrap and rendered topology."""
import http.server
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import threading
import time
import types
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]


def load(name, file):
    spec = importlib.util.spec_from_file_location(name, ROOT / file)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


BOOT = load('vps_boot', 'deploy/production/bootstrap-runtime.py')
MONITOR = load('vps_monitor', 'deploy/production/health-monitor.py')


class RuntimeTest(unittest.TestCase):
    def render_compose(self, overrides=None):
        env = {**os.environ, 'MNEMA_POSTGRES_PASSWORD': 'fixture-superuser',
            'MNEMA_IDENTITY_DB_PASSWORD': 'fixture-identity', 'MNEMA_LEARNING_DB_PASSWORD': 'fixture-learning',
            'MNEMA_BUILD_ID': 'a' * 40, 'MNEMA_PRODUCTION_ROOT': '/fixture'}
        for name in ('GOOGLE_CLIENT_ID', 'GOOGLE_CLIENT_SECRET', 'YANDEX_CLIENT_ID', 'YANDEX_CLIENT_SECRET',
                     'GH_CLIENT_ID', 'GH_CLIENT_SECRET', 'TURNSTILE_SITE_KEY', 'TURNSTILE_SECRET_KEY',
                     'MNEMA_IDENTITY_TURNSTILE_MODE', 'MNEMA_POSTBOX_ACCESS_KEY', 'MNEMA_POSTBOX_SECRET_KEY',
                     'MNEMA_AVATAR_ACCESS_KEY', 'MNEMA_AVATAR_SECRET_KEY', 'LEARNING_MEDIA_UPLOAD_BUCKET',
                     'LEARNING_MEDIA_UPLOAD_ACCESS_KEY', 'LEARNING_MEDIA_UPLOAD_SECRET_KEY', 'MNEMA_AVATAR_ENDPOINT',
                     'MNEMA_AVATAR_REGION', 'MNEMA_AVATAR_BUCKET', 'LEARNING_MEDIA_UPLOAD_ENDPOINT',
                     'LEARNING_MEDIA_UPLOAD_REGION'):
            env.pop(name, None)
        for service in ('FRONTEND', 'IDENTITY_ACCOUNT', 'LEARNING', 'POSTGRES'):
            env['MNEMA_' + service + '_IMAGE'] = 'example/fixture@sha256:' + 'b' * 64
        env.update(overrides or {})
        result = subprocess.run(['docker', 'compose', '-f', str(ROOT / 'deploy/production/compose.yaml'),
            'config', '--format', 'json'], env=env, check=True, capture_output=True, timeout=20)
        return json.loads(result.stdout)['services']

    def test_preview_does_not_touch_system_or_generate_keys(self):
        with patch.object(BOOT.sys, 'argv', ['bootstrap', 'preview']), \
             patch.object(BOOT, 'signing_key') as key, patch.object(BOOT, 'create') as create, \
             patch('builtins.print'):
            BOOT.main()
        key.assert_not_called()
        create.assert_not_called()

    def test_unprivileged_apply_is_rejected_before_any_secret(self):
        with patch.object(BOOT.sys, 'argv', ['bootstrap', '--apply']), \
             patch.object(BOOT.os, 'geteuid', return_value=1000), \
             patch.object(BOOT, 'signing_key') as key, self.assertRaises(ValueError):
            BOOT.main()
        key.assert_not_called()

    def test_existing_private_file_is_never_replaced(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'secret'
            path.write_text('retained')
            with self.assertRaises(FileExistsError):
                BOOT.create(path, 'replacement')
            self.assertEqual(path.read_text(), 'retained')

    def test_base64_uint_has_no_sign_byte_or_padding(self):
        self.assertEqual(BOOT.b64uint(65537), 'AQAB')
        self.assertEqual(BOOT.b64uint(128), 'gA')

    def test_actual_compose_has_private_roles_and_no_published_ports_or_socket(self):
        services = self.render_compose()
        self.assertEqual(set(services), {'frontend', 'identity-account', 'learning', 'postgres'})
        for name, service in services.items():
            self.assertEqual(service['network_mode'], 'host')
            self.assertFalse(service.get('ports'))
            self.assertFalse(service.get('privileged'))
            self.assertNotIn('/var/run/docker.sock', json.dumps(service))
        identity = services['identity-account']['environment']
        learning = services['learning']['environment']
        for name in ('identity-account', 'learning'):
            self.assertEqual(len(services[name]['tmpfs']), 1)
            self.assertTrue(services[name]['tmpfs'][0].startswith('/tmp:'))
        self.assertEqual(identity['MNEMA_IDENTITY_TURNSTILE_MODE'], 'required')
        self.assertNotIn('MNEMA_IDENTITY_TURNSTILE_PRIVACY_APPROVED', identity)
        for name in ('MNEMA_POSTBOX_ACCESS_KEY', 'MNEMA_POSTBOX_SECRET_KEY', 'MNEMA_AVATAR_ACCESS_KEY',
                     'MNEMA_AVATAR_SECRET_KEY'):
            self.assertEqual(identity[name], '')
        for name in ('LEARNING_MEDIA_UPLOAD_ACCESS_KEY', 'LEARNING_MEDIA_UPLOAD_SECRET_KEY'):
            self.assertEqual(learning[name], '')
        self.assertEqual(learning['LEARNING_MEDIA_UPLOAD_BUCKET'], 'mnema-prod-media-b1g0dnrijqn8')
        # Spring reads a set-but-empty variable as "", so the non-empty application defaults are passed explicitly.
        self.assertEqual(identity['MNEMA_AVATAR_ENDPOINT'], 'https://storage.yandexcloud.net')
        self.assertEqual(identity['MNEMA_AVATAR_REGION'], 'ru-central1')
        self.assertEqual(identity['MNEMA_AVATAR_BUCKET'], 'mnema-prod-avatars-b1g0dnrijqn8')
        self.assertEqual(learning['LEARNING_MEDIA_UPLOAD_ENDPOINT'], 'https://storage.yandexcloud.net')
        self.assertEqual(learning['LEARNING_MEDIA_UPLOAD_REGION'], 'ru-central1')
        self.assertEqual(identity['SERVER_ADDRESS'], '127.0.0.1')
        self.assertEqual(learning['SERVER_ADDRESS'], '127.0.0.1')
        self.assertNotIn('fixture-superuser', json.dumps(identity))
        self.assertNotIn('fixture-learning', json.dumps(identity))
        self.assertNotIn('fixture-identity', json.dumps(learning))
        self.assertEqual(services['frontend']['environment']['MNEMA_CLIENT_ID'], 'mnema-web')
        self.assertNotIn('MNEMA_FEATURE_FEDERATED_AUTH_ENABLED', services['frontend']['environment'])
        self.assertEqual(identity['SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GITHUB_CLIENT_SECRET'], '')

    def test_no_compose_service_runs_media_and_learning_mounts_only_its_private_spool(self):
        services = self.render_compose({'LEARNING_MEDIA_UPLOAD_ACCESS_KEY': 'private-fixture-access',
                                        'LEARNING_MEDIA_UPLOAD_SECRET_KEY': 'private-fixture-secret'})
        self.assertNotIn('media-worker', services)       # per-job containers are started by the root media runner, not by Compose
        self.assertNotIn('media-runner', services)
        learning = services['learning']
        spool = {'type': 'bind', 'source': '/var/lib/mnema/media-work/spool', 'target': '/var/lib/mnema-media',
                 'bind': {'create_host_path': False}}
        self.assertEqual(learning['volumes'], [spool])
        self.assertEqual(learning['user'], '10001:10001')   # no shared group any more: nothing else touches the spool as non-root
        for name in ('identity-account', 'frontend', 'postgres'):
            self.assertNotIn('/var/lib/mnema', json.dumps(services[name].get('volumes')))
        self.assertNotIn('/var/run/docker.sock', json.dumps(services))
        environment = learning['environment']
        self.assertEqual(environment['LEARNING_MEDIA_PROCESSING_ENABLED'], 'true')
        self.assertEqual(environment['LEARNING_MEDIA_PROCESSING_WORK_ROOT'], '/var/lib/mnema-media')
        self.assertEqual(environment['LEARNING_MEDIA_PROCESSING_MAX_PARALLEL'], '1')
        # a verdict is accepted only from a root-owned file; the development-only override must never reach production
        self.assertNotIn('LEARNING_MEDIA_PROCESSING_VERDICT_UID', environment)
        self.assertEqual((environment['LEARNING_MEDIA_GC_ENABLED'], environment['LEARNING_MEDIA_GC_GRACE']), ('true', 'P7D'))
        self.assertEqual([environment['LEARNING_MEDIA_UPLOAD_MAX_' + kind + '_BYTES'] for kind in ('IMAGE', 'AUDIO', 'VIDEO', 'RESERVED')],
                         ['33554432', '134217728', '1073741824', '2147483648'])
        # the object-store keys stay with Learning; the runner and its containers never receive them
        self.assertNotIn('private-fixture', json.dumps({n: s for n, s in services.items() if n != 'learning'}))
        limits = sum(int(service['mem_limit']) for service in services.values())
        self.assertLessEqual(limits, 7.5 * 1024 ** 3)   # leaves the 3 GiB job container, the runner and the system inside 12 GB

    def test_the_media_runner_unit_and_service_are_hardened_and_the_container_flags_are_fixed(self):
        unit = (ROOT / 'deploy/production/mnema-media-runner.service').read_text()
        for expected in ('Restart=always', 'NoNewPrivileges=yes', 'ProtectSystem=strict', 'RestrictAddressFamilies=AF_UNIX',
                         'ExecStart=/usr/bin/python3 -I /usr/local/sbin/mnema-media-runner', 'StateDirectoryMode=0700',
                         'CapabilityBoundingSet=CAP_CHOWN CAP_DAC_OVERRIDE CAP_FOWNER CAP_FSETID CAP_KILL'):
            self.assertIn(expected, unit)
        self.assertNotIn('Environment=PATH', unit)
        source = (ROOT / 'deploy/production/mnema-media-runner.py').read_text()
        self.assertTrue(source.startswith('#!/usr/bin/python3 -I\n'))
        for expected in ("'--network', 'none'", "'--read-only'", "'--cap-drop', 'ALL'", "'--security-opt', 'no-new-privileges'",
                         "'--pids-limit', '64'", "'--pull', 'never'"):
            self.assertIn(expected, source)

    def test_configured_auth_credentials_reach_only_identity_and_runtime_env_selects_the_kill_switch(self):
        credentials = {name: 'private-fixture-' + name for name in ('GOOGLE_CLIENT_ID', 'GOOGLE_CLIENT_SECRET',
            'YANDEX_CLIENT_ID', 'YANDEX_CLIENT_SECRET', 'GH_CLIENT_ID', 'GH_CLIENT_SECRET',
            'TURNSTILE_SITE_KEY', 'TURNSTILE_SECRET_KEY')}
        services = self.render_compose(credentials)
        identity = services['identity-account']['environment']
        self.assertEqual(identity['SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GITHUB_CLIENT_SECRET'],
                         credentials['GH_CLIENT_SECRET'])
        self.assertEqual(identity['SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_ID'],
                         credentials['GOOGLE_CLIENT_ID'])
        self.assertEqual(identity['SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_YANDEX_CLIENT_SECRET'],
                         credentials['YANDEX_CLIENT_SECRET'])
        self.assertEqual(identity['TURNSTILE_SECRET_KEY'], credentials['TURNSTILE_SECRET_KEY'])
        self.assertEqual(identity['MNEMA_IDENTITY_TURNSTILE_MODE'], 'required')
        for name in ('postgres', 'learning', 'frontend'):
            self.assertNotIn('private-fixture-', json.dumps(services[name]))
        killed = self.render_compose({**credentials, 'MNEMA_IDENTITY_TURNSTILE_MODE': 'blocked'})
        self.assertEqual(killed['identity-account']['environment']['MNEMA_IDENTITY_TURNSTILE_MODE'], 'blocked')

    def test_optional_mail_and_storage_keys_reach_only_their_owning_service(self):
        optional = {name: 'private-fixture-' + name for name in ('MNEMA_POSTBOX_ACCESS_KEY', 'MNEMA_POSTBOX_SECRET_KEY',
            'MNEMA_AVATAR_ACCESS_KEY', 'MNEMA_AVATAR_SECRET_KEY', 'LEARNING_MEDIA_UPLOAD_BUCKET',
            'LEARNING_MEDIA_UPLOAD_ACCESS_KEY', 'LEARNING_MEDIA_UPLOAD_SECRET_KEY')}
        services = self.render_compose(optional)
        identity = services['identity-account']['environment']
        learning = services['learning']['environment']
        for name in ('MNEMA_POSTBOX_ACCESS_KEY', 'MNEMA_POSTBOX_SECRET_KEY', 'MNEMA_AVATAR_ACCESS_KEY', 'MNEMA_AVATAR_SECRET_KEY'):
            self.assertEqual(identity[name], optional[name])
            self.assertNotIn(optional[name], json.dumps(services['learning']))
        for name in ('LEARNING_MEDIA_UPLOAD_BUCKET', 'LEARNING_MEDIA_UPLOAD_ACCESS_KEY', 'LEARNING_MEDIA_UPLOAD_SECRET_KEY'):
            self.assertEqual(learning[name], optional[name])
            self.assertNotIn(optional[name], json.dumps(services['identity-account']))
        for name in ('postgres', 'frontend'):
            self.assertNotIn('private-fixture-', json.dumps(services[name]))

    def test_caddy_pins_one_year_host_only_hsts_for_the_auth_origin(self):
        caddyfile = (ROOT / 'deploy/production/Caddyfile').read_text()
        auth = caddyfile[caddyfile.index('auth.mnema.app {'):caddyfile.index('www.mnema.app {')]
        self.assertIn('header Strict-Transport-Security "max-age=31536000"', auth)
        self.assertNotIn('includeSubDomains', caddyfile)
        self.assertNotIn('preload', caddyfile.lower())

    def test_java_readiness_checks_http_status_and_never_redirects_or_logs_body(self):
        status = {'value': 200}
        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                self.send_response(status['value'])
                self.send_header('Location', '/api/actuator/health/readiness')
                self.end_headers()
                self.wfile.write(b'private-body-never-logged')
            def log_message(self, *args):
                pass
        with tempfile.TemporaryDirectory() as temporary, http.server.ThreadingHTTPServer(('127.0.0.1', 18081), Handler) as server:
            threading.Thread(target=server.serve_forever, daemon=True).start()
            subprocess.run(['javac', '-d', temporary, str(ROOT / 'backend/runtime/MnemaReadiness.java')], check=True, capture_output=True)
            for code in (200, 503, 302):
                status['value'] = code
                result = subprocess.run(['java', '-cp', temporary, 'MnemaReadiness', '18081'], capture_output=True, timeout=6)
                self.assertEqual(result.returncode, 0 if code == 200 else 1)
                self.assertEqual(result.stdout + result.stderr, b'')
            server.shutdown()
            result = subprocess.run(['java', '-cp', temporary, 'MnemaReadiness', '22'], capture_output=True, timeout=5)
            self.assertNotEqual(result.returncode, 0)

    def test_monitor_detects_failure_without_response_data(self):
        class Response:
            status = 503
            def __enter__(self): return self
            def __exit__(self, *args): pass
        opener = types.SimpleNamespace(open=lambda *a, **k: Response())
        with patch.object(MONITOR.urllib.request, 'build_opener', return_value=opener), \
             patch.object(MONITOR, 'URLS', {'fixture': 'http://127.0.0.1:18081'}), \
             patch.object(MONITOR, 'media_worker_expected', return_value=False), \
             patch.object(MONITOR.shutil, 'disk_usage', return_value=types.SimpleNamespace(free=20 * 1024**3)), \
             patch.object(Path, 'exists', return_value=False):
            self.assertEqual(MONITOR.check(), ['fixture'])

    def test_monitor_records_disk_and_uncertain_rollout(self):
        with patch.object(MONITOR, 'URLS', {}), patch.object(MONITOR, 'media_worker_expected', return_value=False), \
             patch.object(MONITOR.shutil, 'disk_usage', return_value=types.SimpleNamespace(free=1)), \
             patch.object(Path, 'exists', return_value=True):
            self.assertEqual(MONITOR.check(), ['disk_reserve', 'rollout_reconciliation'])

    def test_monitor_reports_a_stopped_or_silent_media_runner_by_component_name(self):
        with tempfile.TemporaryDirectory() as temporary:
            heartbeat = Path(temporary) / 'heartbeat'
            heartbeat.write_text('1')
            active = types.SimpleNamespace(returncode=0, stdout=b'active\n')
            with patch.object(MONITOR, 'MEDIA_RUNNER_HEARTBEAT', heartbeat):
                with patch.object(MONITOR.subprocess, 'run', return_value=active) as run:
                    self.assertTrue(MONITOR.media_runner_healthy())
                self.assertEqual(run.call_args.args[0], ['/usr/bin/systemctl', 'is-active', 'mnema-media-runner.service'])
                for result in (types.SimpleNamespace(returncode=3, stdout=b'inactive\n'),
                               types.SimpleNamespace(returncode=0, stdout=b'activating\n'),
                               OSError(), subprocess.TimeoutExpired(['systemctl'], 5)):
                    with self.subTest(result=result), patch.object(MONITOR.subprocess, 'run', side_effect=(
                            result if isinstance(result, Exception) else None), return_value=(
                            None if isinstance(result, Exception) else result)):
                        self.assertFalse(MONITOR.media_runner_healthy())
                with patch.object(MONITOR.subprocess, 'run', return_value=active):
                    self.assertFalse(MONITOR.media_runner_healthy(clock=lambda: time.time() + 120))   # heartbeat too old
                heartbeat.unlink()
                with patch.object(MONITOR.subprocess, 'run', return_value=active):
                    self.assertFalse(MONITOR.media_runner_healthy())                                    # no heartbeat at all

    def test_monitor_alerts_on_a_missing_mount_and_on_low_space_or_inodes_of_the_media_work_filesystem(self):
        def statvfs(blocks, available, files, free_files):
            return types.SimpleNamespace(f_blocks=blocks, f_bavail=available, f_files=files, f_favail=free_files)
        same = types.SimpleNamespace(st_dev=1)
        other = types.SimpleNamespace(st_dev=2)
        with patch.object(MONITOR.os, 'stat', side_effect=lambda path: other if Path(path) == MONITOR.MEDIA_WORK else same):
            with patch.object(MONITOR.os, 'statvfs', return_value=statvfs(1000, 500, 1000, 500)):
                self.assertEqual(MONITOR.media_work_problems(), [])
            with patch.object(MONITOR.os, 'statvfs', return_value=statvfs(1000, 99, 1000, 500)):
                self.assertEqual(MONITOR.media_work_problems(), ['media_work_space'])
            with patch.object(MONITOR.os, 'statvfs', return_value=statvfs(1000, 500, 1000, 99)):
                self.assertEqual(MONITOR.media_work_problems(), ['media_work_space'])
        with patch.object(MONITOR.os, 'stat', return_value=same):
            self.assertEqual(MONITOR.media_work_problems(), ['media_work_mount'])          # not mounted: a plain directory
        with patch.object(MONITOR.os, 'stat', side_effect=OSError()):
            self.assertEqual(MONITOR.media_work_problems(), ['media_work_mount'])

    def test_check_includes_the_media_components_only_when_the_recorded_release_has_a_worker(self):
        with patch.object(MONITOR, 'URLS', {}), patch.object(MONITOR, 'media_worker_expected', return_value=True), \
             patch.object(MONITOR, 'media_runner_healthy', return_value=False), \
             patch.object(MONITOR, 'media_work_problems', return_value=['media_work_space']), \
             patch.object(MONITOR.shutil, 'disk_usage', return_value=types.SimpleNamespace(free=20 * 1024**3)), \
             patch.object(Path, 'exists', return_value=False):
            self.assertEqual(MONITOR.check(), ['media_runner', 'media_work_space'])
            with patch.object(MONITOR, 'media_worker_expected', return_value=False):
                self.assertEqual(MONITOR.check(), [])

    def test_the_worker_is_only_expected_once_the_recorded_release_contains_it(self):
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            (base / 'releases').mkdir()
            sha = 'a' * 40
            with patch.object(MONITOR, 'CURRENT', base / 'current.json'), patch.object(MONITOR, 'RELEASES', base / 'releases'):
                self.assertFalse(MONITOR.media_worker_expected())                         # nothing recorded yet
                (base / 'current.json').write_text(json.dumps({'sha': sha}))
                self.assertFalse(MONITOR.media_worker_expected())                         # manifest missing
                four = {name: 'x' for name in ('frontend', 'identity-account', 'learning', 'postgres')}
                (base / 'releases' / (sha + '.json')).write_text(json.dumps({'sha': sha, 'images': four}))
                self.assertFalse(MONITOR.media_worker_expected())                         # a pre-worker release
                (base / 'releases' / (sha + '.json')).write_text(json.dumps({'sha': sha, 'images': {**four, 'media-worker': 'x'}}))
                self.assertTrue(MONITOR.media_worker_expected())
                (base / 'current.json').write_text(json.dumps({'sha': '../../etc/passwd'}))
                self.assertFalse(MONITOR.media_worker_expected())                         # never a path component from the file
                (base / 'current.json').write_text('garbage')
                self.assertFalse(MONITOR.media_worker_expected())
        # no alert, and no systemctl call, before the first five-image release
        with patch.object(MONITOR, 'URLS', {}), patch.object(MONITOR, 'media_worker_expected', return_value=False), \
             patch.object(MONITOR.subprocess, 'run') as run, \
             patch.object(MONITOR.shutil, 'disk_usage', return_value=types.SimpleNamespace(free=20 * 1024**3)), \
             patch.object(Path, 'exists', return_value=False):
            self.assertEqual(MONITOR.check(), [])
        run.assert_not_called()


if __name__ == '__main__':
    unittest.main()
