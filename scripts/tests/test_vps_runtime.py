"""Behavioral checks for bounded health, private bootstrap and rendered topology."""
import contextlib
import http.server
import importlib.util
import json
import os
from pathlib import Path
import shutil
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
        env = {**os.environ, 'MNEMA_POSTGRES_PASSWORD': 'fixture-superuser',
            'MNEMA_IDENTITY_DB_PASSWORD': 'fixture-identity', 'MNEMA_LEARNING_DB_PASSWORD': 'fixture-learning',
            'MNEMA_BUILD_ID': 'a' * 40, 'MNEMA_PRODUCTION_ROOT': '/fixture'}
        for service in ('FRONTEND', 'IDENTITY_ACCOUNT', 'LEARNING'):
            env['MNEMA_' + service + '_IMAGE'] = 'example/fixture@sha256:' + 'b' * 64
        result = subprocess.run(['docker', 'compose', '-f', str(ROOT / 'deploy/production/compose.yaml'),
            'config', '--format', 'json'], env=env, check=True, capture_output=True, timeout=20)
        services = json.loads(result.stdout)['services']
        self.assertEqual(set(services), {'frontend', 'identity-account', 'learning', 'postgres'})
        for service in services.values():
            self.assertEqual(service['network_mode'], 'host')
            self.assertFalse(service.get('ports'))
            self.assertFalse(service.get('privileged'))
            self.assertNotIn('/var/run/docker.sock', json.dumps(service))
        identity = services['identity-account']['environment']
        learning = services['learning']['environment']
        self.assertEqual(identity['MNEMA_IDENTITY_TURNSTILE_MODE'], 'blocked')
        self.assertEqual(identity['MNEMA_IDENTITY_TURNSTILE_PRIVACY_APPROVED'], 'false')
        self.assertEqual(identity['SERVER_ADDRESS'], '127.0.0.1')
        self.assertEqual(learning['SERVER_ADDRESS'], '127.0.0.1')
        self.assertNotIn('fixture-superuser', json.dumps(identity))
        self.assertNotIn('fixture-learning', json.dumps(identity))
        self.assertNotIn('fixture-identity', json.dumps(learning))
        self.assertEqual(services['frontend']['environment']['MNEMA_CLIENT_ID'], 'mnema-web')

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
