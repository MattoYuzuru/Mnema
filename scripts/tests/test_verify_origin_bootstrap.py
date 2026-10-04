"""The bootstrap must be harmless before explicit host-scoped administration."""
import os
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


if __name__ == '__main__':
    unittest.main()
