"""Execute the CI entrypoint with command doubles; never contact production."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SHA = 'a' * 40
FILES = {
    'compose.yaml': 'deploy/production/compose.yaml',
    'nginx.conf': 'deploy/production/nginx.conf',
    'Caddyfile': 'deploy/production/Caddyfile',
    'mnema-deploy': 'deploy/production/mnema-deploy.py',
    'mnema-local-backup': 'deploy/production/local-backup.py',
    'mnema-health': 'deploy/production/health-monitor.py',
    'mnema-health.service': 'deploy/production/mnema-health.service',
    'mnema-health.timer': 'deploy/production/mnema-health.timer',
    'mnema-local-backup.service': 'deploy/production/mnema-local-backup.service',
    'mnema-local-backup.timer': 'deploy/production/mnema-local-backup.timer',
    'mnema-deploy-ssh': 'deploy/production/mnema-deploy-ssh',
    'mnema-deploy.sudoers': 'deploy/production/mnema-deploy.sudoers',
    '60-mnema-deploy.conf': 'deploy/production/60-mnema-deploy.conf',
}
SERVICES = ('frontend', 'identity-account', 'learning', 'postgres')


def installed_config():
    return {name: hashlib.sha256((ROOT / path).read_bytes()).hexdigest() for name, path in FILES.items()}


def candidate(sha=SHA):
    return {'schemaVersion': 1, 'sha': sha,
            'images': {s: 'ghcr.io/mattoyuzuru/mnema/' + s + '@sha256:' + 'b' * 64 for s in SERVICES},
            'source': {'repository': 'mattoyuzuru/mnema', 'commit': sha,
                       'workflow': '.github/workflows/deploy.yaml', 'runId': 7, 'runAttempt': 1},
            'securityEvidenceSha256': 'c' * 64}


def status(**changes):
    return {'target': 'mnema-prod', 'rollout_state': 'no_pending_operation', 'config': installed_config(),
            **changes}


class DeployTest(unittest.TestCase):
    def run_script(self, overrides=None, main=SHA, fail=None, arguments=None, state=None, document=None):
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            commands = base / 'bin'
            commands.mkdir()
            summary = base / 'summary.md'
            candidate_path = base / 'vps-candidate.json'
            candidate_path.write_text(json.dumps(document or candidate()))
            # The doubles record only arguments, stdin and credential permissions.
            scripts = {
                'gh': '#!/bin/sh\nprintf "%s\\n" "$FIXTURE_MAIN"\n',
                'ssh-keygen': '#!/bin/sh\nexit 0\n',
                'ssh': '''#!/usr/bin/env python3
import json, os, pathlib, sys
args = sys.argv[1:]
key = pathlib.Path(args[args.index('-i') + 1])
command = args[-1]
stdin = None if '-n' in args else sys.stdin.read()
with open(os.environ['FIXTURE_CALLS'], 'a') as output:
    output.write(json.dumps({'args': args, 'stdin': stdin, 'mode': key.stat().st_mode & 0o777,
        'secret_env': any(name in os.environ for name in ('MNEMA_DEPLOY_SSH_KEY', 'GH_TOKEN'))}) + '\\n')
if command.startswith(os.environ['FIXTURE_FAIL']) and os.environ['FIXTURE_FAIL']:
    sys.exit(1)
if command == 'status':
    print(os.environ['FIXTURE_STATUS'])
elif command.startswith('deploy '):
    print(json.dumps({'sha': command.split()[1], 'readiness': 'passed', 'backup': '20261008T010203Z-abcdef12.dump',
                      'schema_changed': False, 'rollback_compatible': True, 'pruned_images': 2, 'offsite': 'uploaded'}))
elif command == 'verify':
    print(json.dumps({'sha': 'x', 'images_match': True, 'readiness': 'passed', 'pending': False}))
elif command == 'configure':
    print(json.dumps({'configured': sorted(json.loads(stdin)), 'names_sha256': 'x'}))
elif command.startswith('admit '):
    print(json.dumps({'admitted': True}))
else:
    print(json.dumps({'operation': command}))
''',
            }
            for name, content in scripts.items():
                path = commands / name
                path.write_text(content)
                path.chmod(0o755)
            env = {**os.environ, 'PATH': str(commands) + ':' + os.environ['PATH'],
                'GITHUB_EVENT_NAME': 'push', 'GITHUB_REF': 'refs/heads/main',
                'GITHUB_RUN_ATTEMPT': '1', 'GITHUB_REPOSITORY': 'MattoYuzuru/Mnema',
                'GITHUB_SHA': SHA, 'GITHUB_STEP_SUMMARY': str(summary),
                'MNEMA_DEPLOY_HOST': '135.106.175.30', 'MNEMA_DEPLOY_USER': 'mnema-deploy',
                'MNEMA_DEPLOY_SSH_KEY': 'dummy-private-marker',
                'MNEMA_DEPLOY_KNOWN_HOSTS': 'dummy-host-marker', 'GH_TOKEN': 'dummy-token-marker',
                'PROD_GOOGLE_CLIENT_ID': 'dummy-config-google', 'PROD_MNEMA_PROMO_HASH_SECRET': 'dummy-config-promo',
                'PROD_GH_CLIENT_ID': '', 'PROD_DEPLOY_SSH_KEY': 'dummy-must-not-travel',
                'RUNNER_TEMP': temporary, 'FIXTURE_MAIN': main,
                'FIXTURE_CALLS': str(base / 'calls'), 'FIXTURE_FAIL': fail or '',
                'FIXTURE_STATUS': json.dumps(state if state is not None else status())}
            env.update(overrides or {})
            result = subprocess.run(['bash', str(ROOT / 'scripts/deploy-vps.sh'),
                *(arguments if arguments is not None else [str(candidate_path)])],
                env=env, capture_output=True, timeout=20)
            self.assertNotIn(b'dummy-', result.stdout + result.stderr)
            self.assertEqual(list(base.glob('mnema-vps-ssh.*')), [])
            calls = [json.loads(line) for line in (base / 'calls').read_text().splitlines()] if (base / 'calls').exists() else []
            text = summary.read_text() if summary.exists() else ''
            return result, calls, text

    def test_release_sequence_sends_the_candidate_on_stdin_with_private_ephemeral_credentials(self):
        result, calls, summary = self.run_script()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual([call['args'][-1] for call in calls],
                         ['status', 'admit ' + SHA, 'configure', 'deploy ' + SHA, 'verify'])
        for call in calls:
            self.assertEqual(call['args'][-2], 'mnema-deploy@135.106.175.30')
            self.assertIn('StrictHostKeyChecking=yes', call['args'])
            self.assertIn('ClearAllForwardings=yes', call['args'])
            self.assertIn('BatchMode=yes', call['args'])
            self.assertEqual(call['mode'], 0o600)
            self.assertFalse(call['secret_env'])
        # Only configuration and admission carry stdin, so only they must not use -n.
        self.assertEqual([('-n' in call['args']) for call in calls], [True, False, False, True, True])
        # Allowlisted, non-empty PROD_ secrets only; other PROD_ variables (the SSH key) never travel.
        self.assertEqual(json.loads(calls[2]['stdin']),
                         {'GOOGLE_CLIENT_ID': 'dummy-config-google', 'MNEMA_PROMO_HASH_SECRET': 'dummy-config-promo'})
        self.assertEqual(json.loads(calls[1]['stdin'])['sha'], SHA)   # admission first: a rebuild cannot slip in config
        self.assertIn('| configured | GOOGLE_CLIENT_ID, MNEMA_PROMO_HASH_SECRET |', summary)
        self.assertNotIn('dummy-', summary)
        self.assertIn('| offsite | uploaded |', summary)
        self.assertIn('"readiness": "passed"', result.stdout.decode())
        self.assertIn('| commit | ' + SHA + ' |', summary)
        for service in SERVICES:
            self.assertIn('| image ' + service + ' | ghcr.io/mattoyuzuru/mnema/' + service + '@sha256:', summary)
        for expected in ('20261008T010203Z-abcdef12.dump', '| schema_changed | False |', '| rollback_compatible | True |'):
            self.assertIn(expected, summary)

    def test_manual_dispatch_and_a_rerun_attempt_are_accepted(self):
        for override in ({'GITHUB_EVENT_NAME': 'workflow_dispatch'}, {'GITHUB_RUN_ATTEMPT': '3'}):
            with self.subTest(override=override):
                result, calls, _ = self.run_script(override)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(len(calls), 5)

    def test_config_drift_stops_before_admission_and_names_the_files(self):
        drifted = installed_config()
        drifted['compose.yaml'] = '0' * 64
        drifted['mnema-deploy'] = None
        result, calls, _ = self.run_script(state=status(config=drifted))
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual([call['args'][-1] for call in calls], ['status'])
        message = result.stderr.decode()
        self.assertIn('::error::Host configuration drift: compose.yaml mnema-deploy.', message)
        self.assertIn('administrator must install', message)

    def test_status_without_config_or_with_a_pending_rollout_stops_before_admission(self):
        legacy = {'target': 'mnema-prod', 'rollout_state': 'no_pending_operation'}
        result, calls, _ = self.run_script(state=legacy)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(len(calls), 1)
        self.assertIn('Host configuration drift', result.stderr.decode())
        result, calls, _ = self.run_script(state=status(rollout_state='needs_admin_reconciliation'))
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(len(calls), 1)
        self.assertIn('uncertain rollout is pending', result.stderr.decode())

    def test_untrusted_ref_target_trigger_or_candidate_cannot_reach_ssh(self):
        for override in ({'GITHUB_REF': 'refs/heads/feature'}, {'GITHUB_EVENT_NAME': 'pull_request'},
                         {'GITHUB_REPOSITORY': 'other/repo'}, {'GITHUB_SHA': 'b' * 40},
                         {'MNEMA_DEPLOY_HOST': '127.0.0.1'}, {'MNEMA_DEPLOY_USER': 'root'},
                         {'MNEMA_DEPLOY_SSH_KEY': ''}):
            with self.subTest(override=override):
                result, calls, _ = self.run_script(override)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(calls, [])
        for document in (candidate('b' * 40), {**candidate(), 'sha': SHA + ';id'}):
            with self.subTest(document=document['sha']):
                result, calls, _ = self.run_script(document=document)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(calls, [])
        result, calls, _ = self.run_script(main='b' * 40)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(calls, [])
        self.assertIn('no longer the current main', result.stderr.decode())
        result, calls, _ = self.run_script(arguments=[])
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(calls, [])

    def test_failed_admission_or_deployment_stops_the_sequence(self):
        result, calls, _ = self.run_script(fail='admit ')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual([call['args'][-1] for call in calls], ['status', 'admit ' + SHA])
        result, calls, summary = self.run_script(fail='deploy ')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual([call['args'][-1] for call in calls], ['status', 'admit ' + SHA, 'configure', 'deploy ' + SHA])
        self.assertIn('Production deployment', summary)

    def test_failed_verification_fails_the_job_after_deployment(self):
        result, calls, _ = self.run_script(fail='verify')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(calls[-1]['args'][-1], 'verify')

    def test_a_rejected_configuration_stops_before_the_deploy_and_after_admission(self):
        result, calls, _ = self.run_script(fail='configure')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual([call['args'][-1] for call in calls], ['status', 'admit ' + SHA, 'configure'])

    def test_the_configuration_object_is_always_sent_so_the_environment_stays_the_source_of_truth(self):
        blank = {name: '' for name in ('PROD_GOOGLE_CLIENT_ID', 'PROD_MNEMA_PROMO_HASH_SECRET', 'PROD_GH_CLIENT_ID')}
        result, calls, summary = self.run_script(blank)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual([call['args'][-1] for call in calls],
                         ['status', 'admit ' + SHA, 'configure', 'deploy ' + SHA, 'verify'])
        self.assertEqual(json.loads(calls[2]['stdin']), {})
        self.assertIn('| configured | none |', summary)

    def test_config_drift_is_detected_for_every_installed_security_file(self):
        for name in FILES:
            with self.subTest(name=name):
                drifted = installed_config()
                drifted[name] = 'unprotected' if name.endswith('.sudoers') else '0' * 64
                result, calls, _ = self.run_script(state=status(config=drifted))
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(len(calls), 1)
                self.assertIn('drift: ' + name + '.', result.stderr.decode())

    def test_manual_operations_use_fixed_names_and_need_a_dispatch(self):
        for operation in ('status', 'verify', 'rollback'):
            with self.subTest(operation=operation):
                result, calls, _ = self.run_script({'GITHUB_EVENT_NAME': 'workflow_dispatch'},
                                                   arguments=['--operation', operation])
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual([call['args'][-1] for call in calls], [operation])
                self.assertIn('-n', calls[0]['args'])
        for arguments, override in ((['--operation', 'deploy'], {}), (['--operation', 'status; id'], {}),
                                    (['--operation'], {}), (['--operation', 'status'], {'GITHUB_EVENT_NAME': 'push'})):
            with self.subTest(arguments=arguments, override=override):
                result, calls, _ = self.run_script({'GITHUB_EVENT_NAME': 'workflow_dispatch', **override},
                                                   arguments=arguments)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(calls, [])


if __name__ == '__main__':
    unittest.main()
