"""Docs-only merges must not rebuild or redeploy; any runtime path must."""
import importlib.util
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('release_scope', ROOT / 'scripts/release_scope.py')
SCOPE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SCOPE)

DEPLOYED = 'a' * 40
HEAD = 'b' * 40


MAIN_CI = '.github/workflows/deploy.yaml'
OPS = '.github/workflows/vps-deploy.yaml'


def history(deployments=None, statuses=None, runs=None):
    """statuses: id -> state; runs: id -> workflow path of the run that produced the status."""
    deployments = [{'id': 3, 'sha': 'c' * 40}, {'id': 2, 'sha': DEPLOYED}] if deployments is None else deployments
    statuses = {3: 'failure', 2: 'success'} if statuses is None else statuses
    runs = {3: MAIN_CI, 2: MAIN_CI} if runs is None else runs

    def gh(path):
        if path.startswith('deployments?'):
            assert 'environment=prod' in path
            return deployments
        if path.startswith('actions/runs/'):
            return {'path': runs[int(path.rsplit('/', 1)[1])]}
        identifier = int(path.split('/')[1])
        if identifier not in statuses:
            return []
        url = f'https://github.com/MattoYuzuru/Mnema/actions/runs/{identifier}/job/9'
        return [{'state': statuses[identifier], 'log_url': url, 'target_url': url}]
    return gh


def git(ancestor=0, exists=0, diff=(0, '')):
    def run(*args):
        if args[0] == 'diff':
            assert '--no-renames' in args
        return {'cat-file': (exists, ''), 'merge-base': (ancestor, ''), 'diff': diff}[args[0]]
    return run


class ReleaseScopeTest(unittest.TestCase):
    def test_manual_dispatch_always_releases_without_reading_history(self):
        def forbidden(*args):
            raise AssertionError('history must not be read')
        self.assertTrue(SCOPE.decide('workflow_dispatch', HEAD, forbidden, forbidden)[0])

    def test_docs_and_non_runtime_paths_do_not_release(self):
        paths = ['docs/operations/production-delivery.md', 'README.md', 'AGENTS.md', 'frontend/README.md',
                 'frontend/docs/notes.md', 'deploy/production/README.md', 'scripts/verify_docs.py',
                 '.github/workflows/pull-request.yaml', '.github/workflows/vps-deploy.yaml', 'design/prototype/x.html',
                 'scripts/smoke/vps_public_smoke.py', 'compose.local-full-stack.yml']
        for path in paths:
            with self.subTest(path=path):
                self.assertFalse(SCOPE.is_runtime_path(path))
        deploy, reason = SCOPE.decide('push', HEAD, history(), git(diff=(0, '\n'.join(paths) + '\n')))
        self.assertFalse(deploy, reason)

    def test_each_runtime_path_releases(self):
        paths = ['backend/services/learning/src/main/java/App.java', 'backend/gradle.properties',
                 'frontend/src/app/app.ts', 'frontend/Dockerfile', 'contracts/openapi/learning.yaml',
                 'deploy/production/compose.yaml', 'deploy/production/Caddyfile', 'deploy/production/mnema-deploy.py',
                 '.github/workflows/deploy.yaml', 'scripts/deploy-vps.sh', 'scripts/render_vps_candidate.py',
                 'scripts/verify_release_security_evidence.py', 'scripts/release_scope.py',
                 'security/trivy-release-ignore']
        for path in paths:
            with self.subTest(path=path):
                deploy, reason = SCOPE.decide('push', HEAD, history(), git(diff=(0, 'docs/a.md\n' + path + '\n')))
                self.assertTrue(deploy, reason)

    def test_the_baseline_is_the_newest_deployment_whose_latest_status_is_success(self):
        self.assertEqual(SCOPE.latest_successful_sha(history()), DEPLOYED)
        self.assertEqual(SCOPE.latest_successful_sha(history(statuses={3: 'success', 2: 'success'})), 'c' * 40)
        self.assertEqual(SCOPE.latest_successful_sha(history(statuses={3: 'in_progress', 2: 'inactive'})), None)

    def test_deployments_created_by_the_operations_workflow_never_move_the_baseline(self):
        # status/verify/rollback in vps-deploy.yaml create successful prod deployments too.
        ops_newest = history(runs={3: OPS, 2: MAIN_CI}, statuses={3: 'success', 2: 'success'})
        self.assertEqual(SCOPE.latest_successful_sha(ops_newest), DEPLOYED)
        self.assertIsNone(SCOPE.latest_successful_sha(history(runs={3: OPS, 2: OPS}, statuses={3: 'success', 2: 'success'})))
        # A rollback run of the ops workflow after a code release must not hide that release.
        deploy, reason = SCOPE.decide('push', HEAD, ops_newest, git(diff=(0, 'backend/x.java\n')))
        self.assertTrue(deploy, reason)
        deploy, reason = SCOPE.decide('push', HEAD, ops_newest, git(diff=(0, 'docs/a.md\n')))
        self.assertFalse(deploy, reason)

    def test_the_producing_run_is_resolved_from_the_status_urls_and_unresolvable_ones_are_ignored(self):
        def gh(path):
            if path.startswith('deployments?'):
                return [{'id': 1, 'sha': DEPLOYED}]
            if path.startswith('actions/runs/'):
                return {'path': MAIN_CI + '@refs/heads/main'}
            return [{'state': 'success', 'log_url': None, 'target_url': 'https://github.com/o/r/actions/runs/55'}]
        self.assertEqual(SCOPE.latest_successful_sha(gh), DEPLOYED)       # target_url fallback, ref suffix tolerated
        for status in ({'state': 'success'}, {'state': 'success', 'log_url': 'https://example.com/elsewhere'}):
            with self.subTest(status=status):
                self.assertIsNone(SCOPE.latest_successful_sha(
                    lambda path, s=status: [{'id': 1, 'sha': DEPLOYED}] if path.startswith('deployments?') else [s]))
        self.assertIsNone(SCOPE.latest_successful_sha(
            lambda path: [{'id': 1, 'sha': DEPLOYED}] if path.startswith('deployments?')
            else {'path': '.github/workflows/other.yaml'} if path.startswith('actions/runs/')
            else [{'state': 'success', 'log_url': 'https://github.com/o/r/actions/runs/9/job/1'}]))

    def test_a_file_moved_out_of_a_runtime_path_still_counts(self):
        # Without --no-renames git reports only the new (non-runtime) name for such a move.
        deploy, reason = SCOPE.decide('push', HEAD, history(), git(diff=(0, 'backend/Old.java\ndocs/moved/Old.md\n')))
        self.assertTrue(deploy, reason)
        self.assertEqual(SCOPE.latest_successful_sha(history(deployments=[])), None)
        self.assertEqual(SCOPE.latest_successful_sha(history(deployments=[{'id': 1, 'sha': 'not-a-sha'}], statuses={1: 'success'}, runs={1: MAIN_CI})), None)

    def test_missing_or_unusable_baseline_releases_conservatively(self):
        cases = {
            'no deployment': (history(deployments=[]), git()),
            'unknown commit': (history(), git(exists=1)),
            'not an ancestor': (history(), git(ancestor=1)),
            'diff failure': (history(), git(diff=(128, ''))),
        }
        for label, (gh, runner) in cases.items():
            with self.subTest(label):
                self.assertTrue(SCOPE.decide('push', HEAD, gh, runner)[0])
        self.assertTrue(SCOPE.decide('schedule', HEAD, history(), git())[0])

    def test_already_deployed_commit_is_not_released_again(self):
        self.assertFalse(SCOPE.decide('push', DEPLOYED, history(), git())[0])


if __name__ == '__main__':
    unittest.main()
