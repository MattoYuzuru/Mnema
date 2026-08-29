from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path


SCRIPTS_DIR = Path(__file__).resolve().parents[1]
REPOSITORY_ROOT = SCRIPTS_DIR.parent
sys.path.insert(0, str(SCRIPTS_DIR))

from verify_github_actions_pins import validate_workflows  # noqa: E402


PIN = "0123456789abcdef0123456789abcdef01234567"


class VerifyGithubActionsPinsTest(unittest.TestCase):
    def validate_fixture(self, content: str):
        with tempfile.TemporaryDirectory() as directory:
            workflow = Path(directory) / "fixture.yaml"
            workflow.write_text(content, encoding="utf-8")
            return validate_workflows([workflow])

    def test_repository_workflows_are_immutable(self):
        findings = validate_workflows([REPOSITORY_ROOT / ".github" / "workflows"])

        self.assertEqual([], findings, [finding.render() for finding in findings])

    def test_full_sha_with_release_comment_is_accepted(self):
        findings = self.validate_fixture(
            f"steps:\n  - uses: actions/example@{PIN} # v1.2.3\n"
        )

        self.assertEqual([], findings)

    def test_repository_local_action_is_accepted(self):
        findings = self.validate_fixture("steps:\n  - uses: ./.github/actions/example\n")

        self.assertEqual([], findings)

    def test_mutable_release_ref_is_rejected(self):
        findings = self.validate_fixture("steps:\n  - uses: actions/example@v1\n")

        self.assertEqual(1, len(findings))
        self.assertIn("full 40-character commit SHA", findings[0].message)

    def test_inline_mapping_cannot_bypass_policy(self):
        findings = self.validate_fixture(
            "steps:\n  - { name: Checkout, uses: actions/example@v1 }\n"
        )

        self.assertEqual(1, len(findings))
        self.assertIn("canonical", findings[0].message)

    def test_whitespace_before_mapping_separator_cannot_bypass_policy(self):
        findings = self.validate_fixture("steps:\n  - uses : actions/example@v1\n")

        self.assertEqual(1, len(findings))
        self.assertIn("canonical", findings[0].message)

    def test_quoted_uses_key_cannot_bypass_policy(self):
        findings = self.validate_fixture("steps:\n  - 'uses': actions/example@v1\n")

        self.assertEqual(1, len(findings))
        self.assertIn("canonical", findings[0].message)

    def test_missing_release_comment_is_rejected(self):
        findings = self.validate_fixture(f"steps:\n  - uses: actions/example@{PIN}\n")

        self.assertEqual(1, len(findings))
        self.assertIn("release comment", findings[0].message)

    def test_conflicting_pin_for_same_release_is_rejected(self):
        other_pin = "89abcdef0123456789abcdef0123456789abcdef"
        findings = self.validate_fixture(
            "steps:\n"
            f"  - uses: actions/example@{PIN} # v1.2.3\n"
            f"  - uses: actions/example@{other_pin} # v1.2.3\n"
        )

        self.assertEqual(1, len(findings))
        self.assertIn("conflicts", findings[0].message)


if __name__ == "__main__":
    unittest.main()
