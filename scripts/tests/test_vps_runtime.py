"""Behavioral checks for bounded health, private bootstrap and rendered topology."""
import http.server
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import threading
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
        for service in services.values():
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
        for name in ('LEARNING_MEDIA_UPLOAD_BUCKET', 'LEARNING_MEDIA_UPLOAD_ACCESS_KEY',
                     'LEARNING_MEDIA_UPLOAD_SECRET_KEY'):
            self.assertEqual(learning[name], '')
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
             patch.object(MONITOR.shutil, 'disk_usage', return_value=types.SimpleNamespace(free=20 * 1024**3)), \
             patch.object(Path, 'exists', return_value=False):
            self.assertEqual(MONITOR.check(), ['fixture'])

    def test_monitor_records_disk_and_uncertain_rollout(self):
        with patch.object(MONITOR, 'URLS', {}), \
             patch.object(MONITOR.shutil, 'disk_usage', return_value=types.SimpleNamespace(free=1)), \
             patch.object(Path, 'exists', return_value=True):
            self.assertEqual(MONITOR.check(), ['disk_reserve', 'rollout_reconciliation'])


if __name__ == '__main__':
    unittest.main()
