import subprocess
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / 'require_no_skips.py'


class RequireNoSkipsTest(unittest.TestCase):
    def run_module(self, body):
        with tempfile.TemporaryDirectory() as temporary:
            Path(temporary, 'sample_tests.py').write_text(textwrap.dedent(body))
            return subprocess.run([sys.executable, str(SCRIPT), '--path', temporary, 'sample_tests'],
                                  capture_output=True, text=True, timeout=60)

    def test_passing_tests_pass(self):
        self.assertEqual(0, self.run_module('''
            import unittest
            class T(unittest.TestCase):
                def test_ok(self): pass
        ''').returncode)

    def test_a_skipped_test_fails_the_gate_and_names_it(self):
        result = self.run_module('''
            import unittest
            class T(unittest.TestCase):
                @unittest.skip("tool missing")
                def test_skipped(self): pass
                def test_ok(self): pass
        ''')
        self.assertEqual(1, result.returncode)
        self.assertIn('SKIPPED (not allowed here)', result.stderr)
        self.assertIn('tool missing', result.stderr)

    def test_a_failure_or_an_empty_module_fails(self):
        self.assertEqual(1, self.run_module('''
            import unittest
            class T(unittest.TestCase):
                def test_bad(self): self.fail()
        ''').returncode)
        self.assertEqual(1, self.run_module('x = 1\n').returncode)


if __name__ == '__main__':
    unittest.main()
