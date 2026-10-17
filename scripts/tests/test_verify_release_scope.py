"""Docs-only merges must not rebuild or redeploy; any runtime path must."""
import importlib.util
from pathlib import Path
import subprocess
import tempfile
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
                 'security/trivy-release-ignore',
                 # Packaged Markdown and anything a test-looking name does not prove to be test-only.
                 'backend/services/learning/src/main/resources/ai/prompts/v2/system.md',
                 'frontend/src/assets/brand/README.md', 'frontend/src/app/testing/fixtures.ts',
                 'frontend/src/app/app.spec.ts.orig', 'frontend/tsconfig.app.json', 'frontend/vitest.config.mts',
                 'backend/services/learning/src/testFixtures/x.java', 'backend/media-worker/mnema_media_worker/process.py',
                 'backend/build.gradle.kts', 'contracts/decks/README.md',
                 'backend/media-worker/mnema_media_worker/NOTES.md', 'frontend/src/assets/demo/x.spec.ts']
        for path in paths:
            with self.subTest(path=path):
                deploy, reason = SCOPE.decide('push', HEAD, history(), git(diff=(0, 'docs/a.md\n' + path + '\n')))
                self.assertTrue(deploy, reason)

    def test_tests_and_their_inputs_do_not_release(self):
        paths = ['backend/services/learning/src/test/java/app/mnema/AppTest.java',
                 'backend/services/identity-account/src/test/resources/fixture.json',
                 'backend/test-support/java/app/mnema/testing/TestInventory.java', 'backend/test-durations.tsv',
                 'backend/test-opt-ins.json', 'backend/coverage-baseline.json', 'backend/scripts/check_coverage.py',
                 'backend/media-worker/tests/test_worker.py', 'backend/media-worker/README.md',
                 'backend/services/learning/guide.md', 'backend/AGENTS.md', 'frontend/AGENTS.md',
                 'frontend/src/app/app.spec.ts', 'frontend/src/app/features/study/study-page.component.spec.ts']
        for path in paths:
            with self.subTest(path=path):
                self.assertFalse(SCOPE.is_runtime_path(path))
        deploy, reason = SCOPE.decide('push', HEAD, history(), git(diff=(0, '\n'.join(paths) + '\n')))
        self.assertFalse(deploy, reason)

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


class ReleaseScopeHistoryTest(unittest.TestCase):
    """Real Git history: the decision is cumulative from the deployed commit, not from the previous main commit."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.cmd('init', '-q', '-b', 'main')
        self.cmd('config', 'user.email', 'scope@example.invalid')
        self.cmd('config', 'user.name', 'scope')
        self.deployed = self.commit({'backend/services/learning/src/main/java/App.java': 'v1', 'docs/a.md': 'a'})

    def cmd(self, *args):
        return subprocess.run(['git', '-C', str(self.root), *args], check=True, capture_output=True, text=True).stdout.strip()

    def git(self, *args):
        result = subprocess.run(['git', '-C', str(self.root), *args], capture_output=True, text=True)
        return result.returncode, result.stdout

    def commit(self, files, delete=(), move=()):
        for name, text in files.items():
            (self.root / name).parent.mkdir(parents=True, exist_ok=True)
            (self.root / name).write_text(text)
        for name in delete:
            self.cmd('rm', '-q', name)
        for old, new in move:
            (self.root / new).parent.mkdir(parents=True, exist_ok=True)
            self.cmd('mv', old, new)
        self.cmd('add', '-A')
        self.cmd('commit', '-q', '-m', 'change')
        return self.cmd('rev-parse', 'HEAD')

    def decide(self, head):
        return SCOPE.decide('push', head, history(deployments=[{'id': 2, 'sha': self.deployed}]), self.git)

    def test_test_only_change_after_the_deployment_does_not_release(self):
        head = self.commit({'backend/services/learning/src/test/java/AppTest.java': 't', 'docs/b.md': 'b'})
        self.assertFalse(self.decide(head)[0])

    def test_an_unreleased_runtime_change_survives_a_later_test_only_commit(self):
        self.commit({'backend/services/learning/src/main/java/App.java': 'v2'})        # A, never deployed
        head = self.commit({'frontend/src/app/app.spec.ts': 'spec'})                   # B, test-only
        deploy, reason = self.decide(head)
        self.assertTrue(deploy, reason)
        self.assertIn('backend/services/learning/src/main/java/App.java', reason)

    def test_deleting_or_moving_runtime_source_into_tests_releases(self):
        moved = self.commit({}, move=[('backend/services/learning/src/main/java/App.java',
                                       'backend/services/learning/src/test/java/App.java')])
        self.assertTrue(self.decide(moved)[0])
        self.setUp()
        deleted = self.commit({}, delete=['backend/services/learning/src/main/java/App.java'])
        self.assertTrue(self.decide(deleted)[0])

    def test_pull_request_mode_reports_image_inputs_and_fails_closed(self):
        base = self.deployed
        docs = self.commit({'docs/d.md': 'd', 'backend/services/learning/src/test/java/T.java': 't'})
        self.assertFalse(SCOPE.changed_runtime(base, docs, self.git)[0])
        runtime = self.commit({'frontend/src/app/app.ts': 'x'})
        changed, reason = SCOPE.changed_runtime(base, runtime, self.git)
        self.assertTrue(changed)
        self.assertIn('frontend/src/app/app.ts', reason)
        self.assertTrue(SCOPE.changed_runtime('f' * 40, runtime, self.git)[0])     # unknown base

    def test_a_deployed_commit_outside_the_history_releases(self):
        head = self.commit({'docs/c.md': 'c'})
        gh = history(deployments=[{'id': 2, 'sha': 'f' * 40}])
        deploy, reason = SCOPE.decide('push', head, gh, self.git)
        self.assertTrue(deploy, reason)
        shallow = SCOPE.decide('push', head, history(deployments=[{'id': 2, 'sha': self.deployed}]),
                               lambda *args: (128, '') if args[0] == 'diff' else self.git(*args))
        self.assertTrue(shallow[0], shallow[1])


if __name__ == '__main__':
    unittest.main()
