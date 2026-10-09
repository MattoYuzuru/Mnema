"""Application configuration moves from GitHub Environment prod to the host without leaking values."""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import types
import unittest
import unittest.mock

ROOT = Path(__file__).resolve().parents[2]


def load(name, file):
    spec = importlib.util.spec_from_file_location(name, ROOT / file)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


RENDER = load('render_app_config', 'scripts/render_app_config.py')
SYNC = load('sync_prod_secrets', 'scripts/sync_prod_secrets.py')
KEYS = ROOT / 'deploy/production/app-config.keys'


class RenderTest(unittest.TestCase):
    def test_only_allowlisted_non_empty_variables_are_rendered(self):
        names = RENDER.read_keys(KEYS)
        environ = {'PROD_GOOGLE_CLIENT_ID': 'g-id', 'PROD_GH_CLIENT_SECRET': '', 'PROD_UNLISTED': 'x',
                   'GOOGLE_CLIENT_SECRET': 'unprefixed', 'PROD_DEPLOY_SSH_KEY': 'must-never-travel', 'PATH': '/bin'}
        self.assertEqual(RENDER.render(names, environ), {'GOOGLE_CLIENT_ID': 'g-id'})
        self.assertEqual(RENDER.render(names, {}), {})

    def test_key_list_is_strict(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'keys'
            for text in ('lowercase\n', 'A B\n', 'GOOD\nGOOD\n', 'a=b\n'):
                path.write_text(text)
                with self.subTest(text=text), self.assertRaises(ValueError):
                    RENDER.read_keys(path)
            path.write_text('# comment\n\nGOOD_ONE\n')
            self.assertEqual(RENDER.read_keys(path), ['GOOD_ONE'])

    def test_main_writes_a_private_file_and_prints_only_a_count(self):
        with tempfile.TemporaryDirectory() as temporary, contextlib.redirect_stdout(io.StringIO()) as out:
            target = Path(temporary) / 'config.json'
            with unittest.mock.patch.dict(RENDER.os.environ, {'PROD_MNEMA_PROMO_HASH_SECRET': 'super-secret-value'}, clear=True):
                self.assertEqual(RENDER.main(['--keys', str(KEYS), '--output', str(target)]), 0)
            self.assertEqual(json.loads(target.read_text()), {'MNEMA_PROMO_HASH_SECRET': 'super-secret-value'})
            self.assertEqual(target.stat().st_mode & 0o777, 0o600)
        self.assertNotIn('super-secret-value', out.getvalue())
        self.assertIn('names present: 1', out.getvalue())


class SyncTest(unittest.TestCase):
    def test_dotenv_parsing_never_evaluates_anything(self):
        values = SYNC.parse_dotenv('\n'.join((
            '# comment', 'GOOGLE_CLIENT_ID=abc', 'export GH_CLIENT_ID = "quoted value"', "YANDEX_CLIENT_ID='single'",
            'MNEMA_PROMO_HASH_SECRET=a$HOME`id`', 'not a line', '  TURNSTILE_SITE_KEY=  padded  ', '#COMMENTED=1')))
        self.assertEqual(values, {'GOOGLE_CLIENT_ID': 'abc', 'GH_CLIENT_ID': 'quoted value', 'YANDEX_CLIENT_ID': 'single',
                                  'MNEMA_PROMO_HASH_SECRET': 'a$HOME`id`', 'TURNSTILE_SITE_KEY': 'padded'})

    def test_values_go_to_gh_on_stdin_only_and_output_has_names_only(self):
        calls = []

        def run(command, **kwargs):
            calls.append((command, kwargs))
            return types.SimpleNamespace(returncode=0)

        out = io.StringIO()
        values = {'GOOGLE_CLIENT_ID': 'visible-secret-1', 'GH_CLIENT_ID': '', 'DEPLOY_TOKEN': 'not-allowlisted'}
        names = SYNC.read_names()
        self.assertEqual(SYNC.sync(values, names, False, run=run, out=out), 0)
        self.assertEqual(len(calls), 1)
        command, kwargs = calls[0]
        self.assertEqual(command, ['gh', 'secret', 'set', 'PROD_GOOGLE_CLIENT_ID', '--env', 'prod', '--repo', 'MattoYuzuru/Mnema'])
        self.assertEqual(kwargs['input'], b'visible-secret-1')
        self.assertNotIn('visible-secret-1', ' '.join(command) + out.getvalue())
        self.assertIn('set PROD_GOOGLE_CLIENT_ID', out.getvalue())
        self.assertIn('skipped GH_CLIENT_ID (absent or empty)', out.getvalue())
        self.assertNotIn('DEPLOY_TOKEN', out.getvalue())

    def test_dry_run_calls_nothing_and_a_failed_set_is_reported_without_the_value(self):
        def forbidden(*args, **kwargs):
            raise AssertionError('dry run must not call gh')

        out = io.StringIO()
        self.assertEqual(SYNC.sync({'GH_CLIENT_ID': 'v'}, ['GH_CLIENT_ID'], True, run=forbidden, out=out), 0)
        self.assertIn('would set PROD_GH_CLIENT_ID', out.getvalue())
        out = io.StringIO()
        failing = lambda command, **kwargs: types.SimpleNamespace(returncode=1, stderr=b'leaky-detail')
        self.assertEqual(SYNC.sync({'GH_CLIENT_ID': 'v'}, ['GH_CLIENT_ID'], False, run=failing, out=out), 1)
        self.assertIn('FAILED PROD_GH_CLIENT_ID', out.getvalue())
        self.assertNotIn('leaky-detail', out.getvalue())

    def test_main_reads_the_file_and_reports_unreadable_input(self):
        with tempfile.TemporaryDirectory() as temporary, contextlib.redirect_stdout(io.StringIO()) as out:
            path = Path(temporary) / 'values'
            path.write_text('MNEMA_EXPERIMENT_SECRET=exp\nGH_CLIENT_ID=\n')
            self.assertEqual(SYNC.main(['--env-file', str(path), '--dry-run']), 0)
            self.assertEqual(SYNC.main(['--env-file', str(path.with_name('missing')), '--dry-run']), 2)
        self.assertIn('would set PROD_MNEMA_EXPERIMENT_SECRET', out.getvalue())
        self.assertNotIn('exp\n', out.getvalue())

    def test_values_the_dispatcher_would_reject_are_refused_locally_by_name_before_anything_is_sent(self):
        def forbidden(*args, **kwargs):
            raise AssertionError('nothing may be sent when any value is refused')

        names = SYNC.read_names()
        text = '\n'.join((
            'GOOGLE_CLIENT_ID=ok-value',
            'YANDEX_CLIENT_ID=value # inline comment',        # whitespace
            'GH_CLIENT_ID="a"b"',                              # inner quote
            "MNEMA_PROMO_HASH_SECRET='it''s'",                 # apostrophes
            'MNEMA_EXPERIMENT_SECRET=${HOME}',                 # expansion
            'TURNSTILE_SITE_KEY=#leading-hash',
            'MNEMA_POSTBOX_ACCESS_KEY=caf\u00e9',             # non-ASCII
            'MNEMA_IDENTITY_TURNSTILE_MODE=Required'))
        values = SYNC.parse_dotenv(text)
        out = io.StringIO()
        self.assertEqual(SYNC.sync(values, names, False, run=forbidden, out=out), 1)
        refused = [line.split()[1] for line in out.getvalue().splitlines()]
        self.assertEqual(sorted(refused), sorted([
            'YANDEX_CLIENT_ID', 'GH_CLIENT_ID', 'MNEMA_PROMO_HASH_SECRET', 'MNEMA_EXPERIMENT_SECRET',
            'TURNSTILE_SITE_KEY', 'MNEMA_POSTBOX_ACCESS_KEY', 'MNEMA_IDENTITY_TURNSTILE_MODE']))
        for value in ('ok-value', 'inline comment', 'HOME', 'caf'):
            self.assertNotIn(value, out.getvalue())          # names only, never values

    def test_a_byte_order_mark_is_stripped_and_a_required_mode_needs_both_turnstile_keys(self):
        with tempfile.TemporaryDirectory() as temporary, contextlib.redirect_stdout(io.StringIO()) as out:
            path = Path(temporary) / 'values'
            path.write_bytes('\ufeffGOOGLE_CLIENT_ID=first-line\n'.encode('utf-8'))
            self.assertEqual(SYNC.main(['--env-file', str(path), '--dry-run']), 0)
            self.assertIn('would set PROD_GOOGLE_CLIENT_ID', out.getvalue())
            path.write_text('MNEMA_IDENTITY_TURNSTILE_MODE=required\nTURNSTILE_SITE_KEY=site-key-1\n')
            self.assertEqual(SYNC.main(['--env-file', str(path), '--dry-run']), 1)
            self.assertIn('refused (combination)', out.getvalue())
            path.write_text('MNEMA_IDENTITY_TURNSTILE_MODE=required\nTURNSTILE_SITE_KEY=site-key-1\n'
                            'TURNSTILE_SECRET_KEY=secret-key-1\n')
            self.assertEqual(SYNC.main(['--env-file', str(path), '--dry-run']), 0)

    def test_every_delivered_name_is_actually_interpolated_by_compose(self):
        # Depends on the compose.yaml that interpolates these names with ${NAME:-} defaults.
        compose = (ROOT / 'deploy/production/compose.yaml').read_text()
        missing = [name for name in RENDER.read_keys(KEYS) if '${' + name not in compose]
        self.assertEqual(missing, [], 'delivered but never consumed by compose.yaml')

    def test_the_synced_names_are_exactly_the_dispatcher_allowlist(self):
        dispatcher = load('dispatcher_for_keys', 'deploy/production/mnema-deploy.py')
        self.assertEqual(SYNC.read_names(), list(dispatcher.APP_ENV_NAMES))


if __name__ == '__main__':
    unittest.main()
