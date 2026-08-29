from __future__ import annotations

import shutil
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPTS_DIR = Path(__file__).resolve().parents[1]
REPOSITORY_ROOT = SCRIPTS_DIR.parent
sys.path.insert(0, str(SCRIPTS_DIR))

from verify_security_automation_policy import validate_repository  # noqa: E402


class VerifySecurityAutomationPolicyTest(unittest.TestCase):
    def setUp(self):
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.repository = Path(self.temporary_directory.name)
        for relative in (
            Path(".github/dependabot.yml"),
            Path(".github/workflows/dependency-review.yaml"),
            Path("docs/operations/security-triage.md"),
        ):
            target = self.repository / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(REPOSITORY_ROOT / relative, target)

    def tearDown(self):
        self.temporary_directory.cleanup()

    def findings(self):
        return validate_repository(self.repository)

    def replace(self, relative: str, old: str, new: str):
        path = self.repository / relative
        content = path.read_text(encoding="utf-8")
        self.assertIn(old, content)
        path.write_text(content.replace(old, new, 1), encoding="utf-8")

    def test_repository_policy_is_valid(self):
        self.assertEqual([], validate_repository(REPOSITORY_ROOT))

    def test_missing_ecosystem_is_rejected(self):
        path = self.repository / ".github/dependabot.yml"
        content = path.read_text(encoding="utf-8")
        start = content.index('  - package-ecosystem: "npm"')
        path.write_text(content[:start], encoding="utf-8")

        self.assertTrue(any("ecosystems" in finding.message for finding in self.findings()))

    def test_routine_npm_pull_requests_are_rejected(self):
        self.replace(
            ".github/dependabot.yml",
            '    open-pull-requests-limit: 0\n    groups:\n      frontend-security:',
            '    open-pull-requests-limit: 1\n    groups:\n      frontend-security:',
        )

        self.assertTrue(any("npm open pull request limit" in finding.message for finding in self.findings()))

    def test_duplicate_npm_pull_request_limit_is_rejected(self):
        self.replace(
            ".github/dependabot.yml",
            "    open-pull-requests-limit: 0\n    groups:\n      frontend-security:",
            "    open-pull-requests-limit: 0\n"
            "    open-pull-requests-limit: 1\n"
            "    groups:\n"
            "      frontend-security:",
        )

        self.assertTrue(any("one open pull request limit" in finding.message for finding in self.findings()))

    def test_missing_production_docker_directory_is_rejected(self):
        self.replace(".github/dependabot.yml", '      - "/k8s/backup"\n', "")

        self.assertTrue(any("docker directories" in finding.message for finding in self.findings()))

    def test_missing_actions_security_group_is_rejected(self):
        path = self.repository / ".github/dependabot.yml"
        content = path.read_text(encoding="utf-8")
        start = content.index("      actions-security:")
        end = content.index('\n\n  - package-ecosystem: "gradle"', start)
        path.write_text(content[:start] + content[end:], encoding="utf-8")

        self.assertTrue(any("catch-all security group" in finding.message for finding in self.findings()))

    def test_mutable_dependency_review_ref_is_rejected(self):
        path = self.repository / ".github/workflows/dependency-review.yaml"
        content = path.read_text(encoding="utf-8")
        content = content.replace(
            "actions/dependency-review-action@a1d282b36b6f3519aa1f3fc636f609c47dddb294 # v5.0.0",
            "actions/dependency-review-action@v5",
        )
        path.write_text(content, encoding="utf-8")

        self.assertTrue(any("immutable release pin" in finding.message for finding in self.findings()))

    def test_lower_dependency_failure_threshold_is_rejected(self):
        self.replace(
            ".github/workflows/dependency-review.yaml",
            '          fail-on-severity: "high"',
            '          fail-on-severity: "critical"',
        )

        self.assertTrue(any("input" in finding.message for finding in self.findings()))

    def test_pull_request_target_is_rejected(self):
        self.replace(
            ".github/workflows/dependency-review.yaml",
            "  pull_request:\n",
            "  pull_request_target:\n",
        )

        self.assertTrue(any("trigger" in finding.message for finding in self.findings()))

    def test_workflow_dispatch_is_rejected(self):
        self.replace(
            ".github/workflows/dependency-review.yaml",
            "on:\n  pull_request:\n    branches:\n      - main\n",
            "on:\n  workflow_dispatch:\n",
        )

        self.assertTrue(any("trigger" in finding.message for finding in self.findings()))

    def test_pull_request_write_permission_is_rejected(self):
        self.replace(
            ".github/workflows/dependency-review.yaml",
            "    permissions:\n      contents: read\n    steps:\n",
            "    permissions:\n      contents: read\n      pull-requests: write\n    steps:\n",
        )

        self.assertTrue(any("permissions" in finding.message for finding in self.findings()))

    def test_warn_only_dependency_review_is_rejected(self):
        self.replace(
            ".github/workflows/dependency-review.yaml",
            '          vulnerability-check: "true"\n',
            '          vulnerability-check: "true"\n          warn-only: "true"\n',
        )

        self.assertTrue(any("input" in finding.message for finding in self.findings()))

    def test_continue_on_error_is_rejected(self):
        self.replace(
            ".github/workflows/dependency-review.yaml",
            '          comment-summary-in-pr: "never"\n',
            '          comment-summary-in-pr: "never"\n        continue-on-error: true\n',
        )

        self.assertTrue(any("step" in finding.message for finding in self.findings()))

    def test_exception_without_expiry_is_rejected(self):
        self.replace("docs/operations/security-triage.md", "- `Expiry`:", "- `Review date`:")

        self.assertTrue(any("requires Expiry" in finding.message for finding in self.findings()))


if __name__ == "__main__":
    unittest.main()
