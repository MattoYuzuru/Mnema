"""The bootstrap must be harmless before explicit host-scoped administration."""
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[2] / 'deploy/production/install-origin.sh'


class OriginBootstrapTest(unittest.TestCase):
    def test_preview_requires_no_system_commands_or_effects(self):
        result = subprocess.run(['/bin/sh', str(SCRIPT), 'preview'], capture_output=True,
                                text=True, env={'PATH': '/nonexistent'})
        self.assertEqual(0, result.returncode)
        self.assertIn('135.106.175.30', result.stdout)
        self.assertIn('No application, DB, DNS, firewall, CI credential or keykomi mutation.', result.stdout)

    def test_unknown_operation_rejected(self):
        result = subprocess.run(['/bin/sh', str(SCRIPT), 'deploy'], capture_output=True,
                                env={'PATH': '/nonexistent'})
        self.assertEqual(2, result.returncode)

    def test_non_root_apply_stops_before_any_system_command(self):
        with tempfile.TemporaryDirectory() as directory:
            identity = Path(directory) / 'id'
            identity.write_text('#!/bin/sh\nprintf "1001\\n"\n')
            identity.chmod(0o755)
            result = subprocess.run(['/bin/sh', str(SCRIPT), '--apply'], capture_output=True,
                                    text=True, env={'PATH': directory})
            self.assertEqual(1, result.returncode)
            self.assertEqual('root required\n', result.stderr)

    def test_shell_syntax(self):
        result = subprocess.run(['/bin/sh', '-n', str(SCRIPT)], capture_output=True)
        self.assertEqual(0, result.returncode)

    def listener_probe(self, script, path_suffix='/usr/bin:/bin'):
        with tempfile.TemporaryDirectory() as directory:
            if script is not None:
                command = Path(directory) / 'ss'
                command.write_text('#!/bin/sh\n' + script + '\n')
                command.chmod(0o755)
            # Source only the actual pure inventory function, never the bootstrap.
            function = SCRIPT.read_text().split('case "${1:-preview}"')[0]
            return subprocess.run(['/bin/sh', '-c', function + '\norigin_ports_free'],
                                  capture_output=True, text=True,
                                  env={'PATH': directory + ':' + path_suffix})

    def test_failed_or_missing_listener_inventory_blocks_bootstrap(self):
        for script in ('exit 1', None):
            with self.subTest(script=script):
                result = self.listener_probe(script, '/nonexistent')
                self.assertNotEqual(0, result.returncode)
                self.assertIn('listener inventory failed', result.stderr)

    def test_occupied_listener_blocks_and_ssh_only_listener_passes(self):
        for port in (80, 443, 2019, 22):
            with self.subTest(port=port):
                result = self.listener_probe(f'printf "LISTEN 0 4096 0.0.0.0:{port} 0.0.0.0:*\\n"')
                self.assertEqual(0 if port == 22 else 1, result.returncode)


if __name__ == '__main__':
    unittest.main()
