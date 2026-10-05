"""Execute the CI entrypoint with local command doubles; never contact production."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SHA = 'a' * 40


class DeployTest(unittest.TestCase):
    def run_script(self, overrides=None, main=SHA, fail_preflight=False):
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            commands = base / 'bin'
            commands.mkdir()
            # The doubles record only arguments and credential permissions.
            scripts = {
                'gh': '#!/bin/sh\nprintf "%s\\n" "$FIXTURE_MAIN"\n',
                'ssh-keygen': '#!/bin/sh\nexit 0\n',
                'ssh': '''#!/usr/bin/env python3
import json, os, pathlib, sys
args = sys.argv[1:]
key = pathlib.Path(args[args.index('-i') + 1])
with open(os.environ['FIXTURE_CALLS'], 'a') as output:
    output.write(json.dumps({'args': args, 'mode': key.stat().st_mode & 0o777,
        'secret_env': any(name in os.environ for name in ('MNEMA_DEPLOY_SSH_KEY', 'GH_TOKEN'))}) + '\\n')
if os.environ['FIXTURE_FAIL'] == 'true' and args[-1].startswith('preflight '):
    sys.exit(1)
''',
            }
            for name, content in scripts.items():
                path = commands / name
                path.write_text(content)
                path.chmod(0o755)
            env = {**os.environ, 'PATH': str(commands) + ':' + os.environ['PATH'],
                'GITHUB_EVENT_NAME': 'workflow_dispatch', 'GITHUB_REF': 'refs/heads/main',
                'GITHUB_RUN_ATTEMPT': '1', 'GITHUB_REPOSITORY': 'MattoYuzuru/Mnema',
                'GITHUB_SHA': SHA, 'MNEMA_RELEASE_SHA': SHA,
                'MNEMA_DEPLOY_HOST': '135.106.175.30', 'MNEMA_DEPLOY_USER': 'mnema-deploy',
                'MNEMA_DEPLOY_SSH_KEY': 'dummy-private-marker',
                'MNEMA_DEPLOY_KNOWN_HOSTS': 'dummy-host-marker', 'GH_TOKEN': 'dummy-token-marker',
                'RUNNER_TEMP': temporary, 'FIXTURE_MAIN': main,
                'FIXTURE_CALLS': str(base / 'calls'), 'FIXTURE_FAIL': str(fail_preflight).lower()}
            env.update(overrides or {})
            result = subprocess.run(['bash', str(ROOT / 'scripts/deploy-vps.sh')],
                env=env, capture_output=True, timeout=10)
            self.assertNotIn(b'dummy-', result.stdout + result.stderr)
            self.assertEqual(list(base.glob('mnema-vps-ssh.*')), [])
            calls = [json.loads(line) for line in (base / 'calls').read_text().splitlines()] if (base / 'calls').exists() else []
            return result.returncode, calls

    def test_fixed_target_and_command_sequence_with_private_ephemeral_credentials(self):
        code, calls = self.run_script()
        self.assertEqual(code, 0)
        self.assertEqual([call['args'][-1] for call in calls], ['status', 'preflight ' + SHA, 'deploy ' + SHA, 'status'])
        for call in calls:
            self.assertEqual(call['args'][-2], 'mnema-deploy@135.106.175.30')
            self.assertIn('StrictHostKeyChecking=yes', call['args'])
            self.assertIn('ClearAllForwardings=yes', call['args'])
            self.assertEqual(call['mode'], 0o600)
            self.assertFalse(call['secret_env'])

    def test_untrusted_ref_target_replay_or_shell_input_cannot_reach_ssh(self):
        for override in ({'GITHUB_REF': 'refs/heads/feature'}, {'GITHUB_EVENT_NAME': 'push'},
                         {'GITHUB_RUN_ATTEMPT': '2'}, {'GITHUB_REPOSITORY': 'other/repo'},
                         {'MNEMA_RELEASE_SHA': SHA + ';id'}, {'GITHUB_SHA': 'b' * 40},
                         {'MNEMA_DEPLOY_HOST': '127.0.0.1'}, {'MNEMA_DEPLOY_USER': 'root'},
                         {'MNEMA_DEPLOY_SSH_KEY': ''}):
            with self.subTest(override=override):
                code, calls = self.run_script(override)
                self.assertNotEqual(code, 0)
                self.assertEqual(calls, [])
        code, calls = self.run_script(main='b' * 40)
        self.assertNotEqual(code, 0)
        self.assertEqual(calls, [])

    def test_failed_preflight_never_attempts_deployment(self):
        code, calls = self.run_script(fail_preflight=True)
        self.assertNotEqual(code, 0)
        self.assertEqual([call['args'][-1] for call in calls], ['status', 'preflight ' + SHA])
