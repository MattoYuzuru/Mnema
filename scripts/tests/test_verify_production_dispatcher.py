"""The CI identity cannot select arbitrary commands, images or writable config."""

import importlib.util
from pathlib import Path
import stat
import types
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('production_dispatcher', ROOT / 'deploy/production/mnema-deploy.py')
DISPATCH = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(DISPATCH)


class ProductionDispatcherTest(unittest.TestCase):
    def test_state_rename_is_followed_by_directory_durability(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(DISPATCH, 'STATE', Path(temporary)), \
             patch.object(DISPATCH, 'protected', side_effect=lambda p:p), \
             patch.object(DISPATCH, 'sync_directory') as sync:
            DISPATCH.write_state('pending.json', {'sha':'b'*40})
            self.assertTrue((Path(temporary)/'pending.json').exists())
            self.assertEqual([c.args[0] for c in sync.call_args_list], [Path(temporary).parent, Path(temporary)])

    def test_repeated_deploy_preserves_the_prior_verified_rollback_sha(self):
        with patch.object(DISPATCH.os, 'geteuid', return_value=0), \
             patch.object(DISPATCH.sys, 'argv', ['dispatcher','deploy '+'b'*40]), \
             patch.object(DISPATCH, 'pending', return_value=None), \
             patch.object(DISPATCH, 'admitted'), patch.object(DISPATCH, 'load_release', return_value={'sha':'b'*40}), \
             patch.object(DISPATCH.shutil, 'disk_usage', return_value=types.SimpleNamespace(free=20*1024**3)), \
             patch.object(DISPATCH, 'current', return_value={'sha':'b'*40,'previous':'a'*40}), \
             patch.object(DISPATCH, 'compose'), patch.object(DISPATCH, 'write_state') as write, \
             patch.object(Path, 'unlink'), patch.object(DISPATCH, 'sync_directory'), patch('builtins.print'):
            DISPATCH.main()
        self.assertEqual(write.call_args_list[-1].args,
                         ('current.json', {'sha':'b'*40,'previous':'a'*40}))

    def test_historical_deploy_cannot_bypass_the_current_admission_pointer(self):
        import json
        with patch.object(DISPATCH.os, 'geteuid', return_value=0), \
             patch.object(DISPATCH.sys, 'argv', ['dispatcher', 'deploy ' + 'b'*40]), \
             patch.object(DISPATCH, 'pending', return_value=None), \
             patch.object(DISPATCH, 'protected', side_effect=lambda p:p), \
             patch.object(Path, 'read_text', return_value=json.dumps({'sha':'a'*40})), \
             patch.object(DISPATCH, 'compose') as compose, self.assertRaises(DISPATCH.Rejected):
            DISPATCH.main()
        compose.assert_not_called()

    def test_uncertain_rollout_blocks_deploy_preflight_and_rollback(self):
        for command in ('deploy '+'a'*40, 'preflight '+'a'*40, 'rollback'):
            with self.subTest(command=command), patch.object(DISPATCH.os, 'geteuid', return_value=0), \
                 patch.object(DISPATCH.sys, 'argv', ['dispatcher', command]), \
                 patch.object(DISPATCH, 'pending', return_value={'sha':'b'*40}), \
                 patch.object(DISPATCH, 'compose') as compose, self.assertRaises(DISPATCH.Rejected):
                DISPATCH.main()
            compose.assert_not_called()

    def test_failed_mutation_preserves_the_pending_boundary_without_recording_success(self):
        data={'sha':'b'*40}
        with patch.object(DISPATCH.os, 'geteuid', return_value=0), \
             patch.object(DISPATCH.sys, 'argv', ['dispatcher','deploy '+'b'*40]), \
             patch.object(DISPATCH, 'pending', return_value=None), \
             patch.object(DISPATCH, 'admitted'), patch.object(DISPATCH, 'load_release', return_value=data), \
             patch.object(DISPATCH.shutil, 'disk_usage', return_value=types.SimpleNamespace(free=20*1024**3)), \
             patch.object(DISPATCH, 'current', return_value={'sha':'a'*40}), \
             patch.object(DISPATCH, 'compose', side_effect=[None, DISPATCH.Rejected('readiness failed')]), \
             patch.object(DISPATCH, 'write_state') as write, self.assertRaises(DISPATCH.Rejected):
            DISPATCH.main()
        write.assert_called_once_with('pending.json', {'sha':'b'*40,'baseline':'a'*40})

    def test_rollback_checks_compatibility_on_the_current_release(self):
        with patch.object(DISPATCH.os, 'geteuid', return_value=0), \
             patch.object(DISPATCH.sys, 'argv', ['dispatcher','rollback']), \
             patch.object(DISPATCH, 'pending', return_value=None), \
             patch.object(DISPATCH, 'current', return_value={'sha':'b'*40,'previous':'a'*40}), \
             patch.object(DISPATCH, 'load_release', side_effect=[{'sha':'a'*40,'rollback_compatible':True},
                                                               {'sha':'b'*40,'rollback_compatible':False}]) as load, \
             patch.object(DISPATCH, 'compose') as compose, self.assertRaises(DISPATCH.Rejected):
            DISPATCH.main()
        self.assertEqual([c.args[0] for c in load.call_args_list], ['a'*40,'b'*40])
        compose.assert_not_called()

    def test_shell_and_argument_injection_are_rejected(self):
        for command in ('', 'sh', 'status; id', 'status\nid', 'deploy ../../etc/passwd',
                        'preflight ' + 'a'*40 + ' --file evil.yml', 'deploy ' + 'A'*40,
                        'sudo id', 'internal-sftp'):
            with self.subTest(command=command), self.assertRaises(DISPATCH.Rejected):
                DISPATCH.parse_command(command)
        self.assertEqual(DISPATCH.parse_command('deploy ' + 'a'*40), ['deploy', 'a'*40])

    def test_untrusted_parent_or_symlink_cannot_bless_root_owned_config(self):
        good = types.SimpleNamespace(st_mode=stat.S_IFDIR | 0o755, st_uid=0)
        writable = types.SimpleNamespace(st_mode=stat.S_IFDIR | 0o777, st_uid=0)
        with patch.object(Path, 'lstat', side_effect=[good, writable]), self.assertRaises(DISPATCH.Rejected):
            DISPATCH.protected(Path('/etc/mnema/production/compose.yaml'))
        link = types.SimpleNamespace(st_mode=stat.S_IFLNK | 0o777, st_uid=0)
        with patch.object(Path, 'lstat', return_value=link), self.assertRaises(DISPATCH.Rejected):
            DISPATCH.protected(Path('/etc/mnema/production/compose.yaml'))

    def test_no_release_can_skip_acceptance_or_use_a_floating_or_foreign_image(self):
        import json
        sha = 'a'*40
        release = {'sha': sha, 'images': {s:'ghcr.io/mattoyuzuru/mnema/'+s+'@sha256:'+'b'*64 for s in DISPATCH.SERVICES},
                   **{g:True for g in DISPATCH.GATES}}
        info = types.SimpleNamespace(st_size=2000)
        with patch.object(DISPATCH, 'protected', side_effect=lambda p:p), patch.object(Path, 'stat', return_value=info):
            with patch.object(Path, 'read_text', return_value=json.dumps(release)):
                self.assertEqual(DISPATCH.load_release(sha)['sha'], sha)
            for bad in ('ghcr.io/mattoyuzuru/mnema/learning:latest', 'ghcr.io/other/learning@sha256:'+'b'*64):
                changed = {**release, 'images':{**release['images'], 'learning':bad}}
                with patch.object(Path, 'read_text', return_value=json.dumps(changed)), self.assertRaises(DISPATCH.Rejected):
                    DISPATCH.load_release(sha)
            changed = {**release, 'backup_restore_verified':False}
            with patch.object(Path, 'read_text', return_value=json.dumps(changed)), self.assertRaises(DISPATCH.Rejected):
                DISPATCH.load_release(sha)

    def test_ssh_and_sudo_restrictions_are_scoped(self):
        config = (ROOT/'deploy/production/60-mnema-deploy.conf').read_text()
        self.assertIn('Match User mnema-deploy', config)
        self.assertIn('DisableForwarding yes', config)
        self.assertIn('ForceCommand /usr/local/bin/mnema-deploy-ssh', config)
        sudo = (ROOT/'deploy/production/mnema-deploy.sudoers').read_text()
        self.assertNotIn('NOPASSWD: ALL', sudo)
        self.assertNotIn('/usr/bin/docker', sudo)
        self.assertIn('/usr/local/sbin/mnema-deploy', sudo)


if __name__ == '__main__':
    unittest.main()
