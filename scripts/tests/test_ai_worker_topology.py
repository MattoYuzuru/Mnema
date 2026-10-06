"""Render both optional split topologies with synthetic credentials; no deployment or owner environment is read."""
import json
import os
from pathlib import Path
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[2]
KEYS = ('MNEMA_AI_DEEPSEEK_API_KEY', 'MNEMA_AI_GIGACHAT_AUTH_KEY', 'MNEMA_AI_OPENROUTER_API_KEY',
        'MNEMA_AI_PIXABAY_API_KEY', 'MNEMA_AI_OPENVERSE_CLIENT_SECRET', 'MNEMA_AI_GOOGLE_API_KEY',
        'MNEMA_AI_TTS_API_KEY', 'MNEMA_AI_STT_API_KEY', 'MNEMA_AI_YANDEX_SEARCH_API_KEY',
        'MNEMA_AI_PERPLEXITY_API_KEY', 'MNEMA_AI_USER_KEY_SECRET', 'MNEMA_AI_EGRESS_PROXY_PASSWORD')


class WorkerTopologyTest(unittest.TestCase):
    def render(self, base, overlay, production=False):
        env = {'PATH': os.environ['PATH'], 'COMPOSE_DISABLE_ENV_FILE': 'true'}
        for name in KEYS:
            env[name] = 'private-fixture-' + name
        env.update({
            'MNEMA_LOCAL_POSTGRES_PASSWORD': 'fixture-db', 'MNEMA_LOCAL_S3_ACCESS_KEY': 'fixture-s3',
            'MNEMA_LOCAL_S3_SECRET_KEY': 'fixture-s3-secret', 'MNEMA_LOCAL_BUILD_ID': 'a' * 40,
            'MNEMA_LOCAL_MEDIA_WORK_ROOT': '/fixture/media', 'MNEMA_LOCAL_IDENTITY_SIGNING_JWK_SET_FILE': '/fixture/jwk',
            'MNEMA_LOCAL_TLS_CERT_FILE': '/fixture/tls-cert', 'MNEMA_LOCAL_TLS_KEY_FILE': '/fixture/tls-key',
            'MNEMA_LOCAL_STORAGE_TLS_CERT_FILE': '/fixture/storage-cert', 'MNEMA_LOCAL_STORAGE_TLS_KEY_FILE': '/fixture/storage-key',
            'MNEMA_LOCAL_CA_CERT_FILE': '/fixture/ca', 'MNEMA_LOCAL_TRUSTSTORE_FILE': '/fixture/truststore',
            'MNEMA_POSTGRES_PASSWORD': 'fixture-superuser', 'MNEMA_IDENTITY_DB_PASSWORD': 'fixture-identity',
            'MNEMA_LEARNING_DB_PASSWORD': 'fixture-learning', 'MNEMA_BUILD_ID': 'a' * 40,
            'LEARNING_FEATURES_AI_GENERATION_ENABLED': 'true', 'MNEMA_AI_EGRESS_PROXY_URL': 'http://127.0.0.1:3128',
            'MNEMA_AI_EGRESS_PROXY_USER': 'fixture-proxy-user',
        })
        for service in ('FRONTEND', 'IDENTITY_ACCOUNT', 'LEARNING', 'POSTGRES'):
            env['MNEMA_' + service + '_IMAGE'] = 'example/fixture@sha256:' + 'b' * 64
        run = subprocess.run(['docker', 'compose', '--env-file', '/dev/null', '-f', str(ROOT / base), '-f', str(ROOT / overlay),
                              'config', '--format', 'json'], env=env, capture_output=True, timeout=20)
        self.assertEqual(run.returncode, 0, 'Compose rejected the split topology')
        return json.loads(run.stdout)['services']

    def test_local_api_has_no_provider_secrets_and_worker_inherits_configuration(self):
        services = self.render('compose.local-full-stack.yml', 'compose.local-ai-worker.yml')
        api = services['learning']['environment']
        worker = services['learning-ai-worker']['environment']
        self.assertEqual(api['MNEMA_RUNTIME_ROLES'], 'api')
        self.assertEqual(api['MNEMA_PROVIDER_CREDENTIALS'], 'worker')
        self.assertEqual(worker['MNEMA_RUNTIME_ROLES'], 'worker')
        self.assertEqual(worker['SERVER_ADDRESS'], '127.0.0.1')
        for name in KEYS:
            self.assertEqual(api[name], '', name + ' must not reach the API')
            self.assertEqual(worker[name], 'private-fixture-' + name)
        self.assertEqual(api['LEARNING_FEATURES_AI_GENERATION_ENABLED'], worker['LEARNING_FEATURES_AI_GENERATION_ENABLED'])
        self.assertFalse(services['learning-ai-worker'].get('ports'))
        self.assertNotIn('/var/run/docker.sock', json.dumps(services['learning-ai-worker']))

    def test_production_overlay_is_private_reuses_learning_image_and_keeps_ai_disabled(self):
        services = self.render('deploy/production/compose.yaml', 'deploy/production/compose.ai-worker.yaml', True)
        api = services['learning']['environment']
        worker = services['learning-ai-worker']['environment']
        self.assertEqual(api['MNEMA_RUNTIME_ROLES'], 'api')
        self.assertEqual(worker['MNEMA_RUNTIME_ROLES'], 'worker')
        self.assertEqual(worker['PORT'], '18084')
        self.assertEqual(worker['MANAGEMENT_SERVER_ADDRESS'], '127.0.0.1')
        self.assertEqual(worker['LEARNING_FEATURES_AI_GENERATION_ENABLED'], 'false')
        for name in KEYS:
            self.assertFalse(api.get(name), name + ' must not reach the API')
            self.assertEqual(worker[name], 'private-fixture-' + name)
        self.assertEqual(services['learning']['image'], services['learning-ai-worker']['image'])
        self.assertFalse(services['learning-ai-worker'].get('ports'))
        self.assertEqual(services['learning-ai-worker']['healthcheck']['test'][-1], '/actuator/health/readiness')


if __name__ == '__main__':
    unittest.main()
