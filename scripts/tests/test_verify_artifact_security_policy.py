from __future__ import annotations

import shutil
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPTS_DIR = Path(__file__).resolve().parents[1]
REPOSITORY_ROOT = SCRIPTS_DIR.parent
sys.path.insert(0, str(SCRIPTS_DIR))

from verify_artifact_contents import ArtifactBoundaryError, verify_artifact_paths  # noqa: E402
from verify_artifact_security_policy import validate_repository  # noqa: E402


class ArtifactContentsTest(unittest.TestCase):
    def setUp(self):
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.workspace = Path(self.temporary_directory.name) / "workspace"
        self.runner_temp = Path(self.temporary_directory.name) / "runner-temp"
        self.workspace.mkdir()
        self.runner_temp.mkdir()

    def tearDown(self):
        self.temporary_directory.cleanup()

    def verify(self, path: Path):
        return verify_artifact_paths(
            [str(path)],
            workspace=self.workspace,
            runner_temp=self.runner_temp,
        )

    def assert_rejected(self, path: Path, expected_reason: str):
        with self.assertRaises(ArtifactBoundaryError) as context:
            self.verify(path)
        self.assertTrue(
            any(expected_reason in violation.reason for violation in context.exception.violations),
            context.exception.violations,
        )

    def test_synthetic_safe_artifact_passes(self):
        report = self.workspace / "report.json"
        report.write_text('{"status":"ok","authorization":"<redacted>"}\n', encoding="utf-8")

        self.assertEqual([report.resolve()], self.verify(report))

    def test_warn_policy_allows_missing_partial_evidence(self):
        verified = verify_artifact_paths(
            [str(self.workspace / "missing-report.json")],
            workspace=self.workspace,
            runner_temp=self.runner_temp,
            if_no_files_found="warn",
        )

        self.assertEqual([], verified)

    def test_environment_filename_is_rejected(self):
        secret = self.workspace / ".env.staging"
        secret.write_text("DUMMY=not-a-secret\n", encoding="utf-8")

        self.assert_rejected(secret, "environment file")

    def test_kubeconfig_and_private_key_filenames_are_rejected(self):
        kubeconfig = self.workspace / "kubeconfig-staging"
        kubeconfig.write_text("synthetic\n", encoding="utf-8")
        private_key = self.workspace / "client.key"
        private_key.write_text("synthetic\n", encoding="utf-8")

        self.assert_rejected(kubeconfig, "kubeconfig")
        self.assert_rejected(private_key, "private-key")

    def test_kubeconfig_json_inside_uploaded_directory_is_rejected(self):
        evidence = self.workspace / "evidence"
        evidence.mkdir()
        kubeconfig = evidence / "kubeconfig.json"
        kubeconfig.write_text(
            '{"clusters":[],"users":[{"user":{"token":"SYNTHETIC-ONLY"}}]}\n',
            encoding="utf-8",
        )

        self.assert_rejected(evidence, "kubeconfig")

    def test_embedded_json_kubeconfig_is_rejected_by_content(self):
        report = self.workspace / "cluster-client.json"
        report.write_text(
            '{"clusters":[],"users":[{"user":{"token":"SYNTHETIC-ONLY"}}]}\n',
            encoding="utf-8",
        )

        self.assert_rejected(report, "embedded kubeconfig")

    def test_common_credential_filename_is_rejected(self):
        credentials = self.workspace / "service-credentials.json"
        credentials.write_text('{"synthetic":true}\n', encoding="utf-8")

        self.assert_rejected(credentials, "credential files")

    def test_private_key_content_marker_is_rejected(self):
        report = self.workspace / "report.txt"
        report.write_text(
            "-----BEGIN PRIVATE KEY-----\nSYNTHETIC-DUMMY-ONLY\n",
            encoding="utf-8",
        )

        self.assert_rejected(report, "private key material")

    def test_encrypted_private_key_marker_is_rejected(self):
        report = self.workspace / "tls.pem"
        report.write_text(
            "-----BEGIN ENCRYPTED PRIVATE KEY-----\nSYNTHETIC-DUMMY-ONLY\n",
            encoding="utf-8",
        )

        self.assert_rejected(report, "private key material")

    def test_synthetic_credential_marker_is_rejected(self):
        report = self.workspace / "report.txt"
        report.write_text(
            "MNEMA_DUMMY_CREDENTIAL_DO_NOT_UPLOAD=synthetic-only\n",
            encoding="utf-8",
        )

        self.assert_rejected(report, "synthetic credential")

    def test_unredacted_synthetic_credential_assignment_is_rejected(self):
        report = self.workspace / "report.txt"
        report.write_text("client_secret=synthetic-placeholder-only\n", encoding="utf-8")

        self.assert_rejected(report, "credential assignment")

    def test_unredacted_synthetic_json_credential_is_rejected(self):
        report = self.workspace / "report.json"
        report.write_text('{"client_secret":"synthetic-placeholder-only"}\n', encoding="utf-8")

        self.assert_rejected(report, "credential assignment")

    def test_raw_kubernetes_secret_fixture_is_rejected(self):
        manifest = self.workspace / "manifest.yaml"
        manifest.write_text(
            "apiVersion: v1\n"
            "kind: Secret\n"
            "metadata:\n"
            "  name: synthetic-only\n"
            "stringData:\n"
            "  dummy: synthetic-placeholder\n",
            encoding="utf-8",
        )

        self.assert_rejected(manifest, "raw Kubernetes Secret")

    def test_sanitized_release_diff_and_restore_evidence_pass(self):
        release_diff = self.runner_temp / "production-release.diff"
        release_diff.write_text(
            "diff -u live desired\n"
            "+kind: Deployment\n"
            "+mnema.app/release: abc123\n"
            "+authorization: <redacted>\n",
            encoding="utf-8",
        )
        restore_report = self.workspace / "restore-drill.json"
        restore_report.write_text(
            '{"schemaVersion":1,"status":"verified","credentialValues":"<redacted>"}\n',
            encoding="utf-8",
        )

        verified = verify_artifact_paths(
            [str(release_diff), str(restore_report)],
            workspace=self.workspace,
            runner_temp=self.runner_temp,
        )
        self.assertEqual(sorted((restore_report.resolve(), release_diff.resolve())), verified)


class ArtifactSecurityPolicyTest(unittest.TestCase):
    def setUp(self):
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.repository = Path(self.temporary_directory.name)
        for source in (REPOSITORY_ROOT / ".github" / "workflows").glob("*.yaml"):
            target = self.repository / source.relative_to(REPOSITORY_ROOT)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, target)
        for relative in (
            Path(".github/artifact-policy.json"),
            Path(".github/actions/safe-upload-artifact/action.yml"),
        ):
            target = self.repository / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(REPOSITORY_ROOT / relative, target)

    def tearDown(self):
        self.temporary_directory.cleanup()

    def replace(self, relative: str, old: str, new: str):
        path = self.repository / relative
        content = path.read_text(encoding="utf-8")
        self.assertIn(old, content)
        path.write_text(content.replace(old, new, 1), encoding="utf-8")

    def findings(self):
        return validate_repository(self.repository)

    def test_repository_policy_is_valid(self):
        self.assertEqual([], validate_repository(REPOSITORY_ROOT))

    def test_direct_upload_artifact_bypass_is_rejected(self):
        self.replace(
            ".github/workflows/pull-request.yaml",
            "uses: ./.github/actions/safe-upload-artifact",
            "uses: actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a",
        )

        self.assertTrue(any("bypasses" in finding.message for finding in self.findings()))

    def test_missing_top_level_deny_by_default_is_rejected(self):
        self.replace(".github/workflows/pull-request.yaml", "permissions: {}\n", "")

        self.assertTrue(any("top-level permissions" in finding.message for finding in self.findings()))

    def test_inherited_write_permission_is_rejected(self):
        self.replace(
            ".github/workflows/pull-request.yaml",
            "    permissions:\n      contents: read\n",
            "    permissions:\n      contents: read\n      packages: write\n",
        )

        self.assertTrue(any("permissions must be exactly" in finding.message for finding in self.findings()))

    def test_stale_artifact_inventory_is_rejected(self):
        self.replace(
            ".github/workflows/pull-request.yaml",
            "name: backend-jacoco-report",
            "name: backend-jacoco-report-renamed",
        )

        self.assertTrue(any("exactly match" in finding.message for finding in self.findings()))

    def test_sensitive_inventory_path_is_rejected(self):
        self.replace(
            ".github/workflows/pull-request.yaml",
            "path: backend/build/reports/jacoco/jacocoRootReport/",
            "path: .env.staging",
        )

        self.assertTrue(any("secret-bearing path" in finding.message for finding in self.findings()))

    def test_mutable_or_changed_upload_action_pin_is_rejected(self):
        self.replace(
            ".github/actions/safe-upload-artifact/action.yml",
            "actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a # v7.0.1",
            "actions/upload-artifact@v7",
        )

        self.assertTrue(any("immutable pin" in finding.message for finding in self.findings()))

    def test_missing_pre_upload_scanner_is_rejected(self):
        self.replace(
            ".github/actions/safe-upload-artifact/action.yml",
            'run: python3 "$GITHUB_WORKSPACE/scripts/verify_artifact_contents.py"',
            "run: echo skipped",
        )

        self.assertTrue(any("same exact inputs" in finding.message for finding in self.findings()))

    def test_upload_path_must_be_the_scanned_input(self):
        self.replace(
            ".github/actions/safe-upload-artifact/action.yml",
            "        path: ${{ inputs.path }}",
            "        path: ${{ github.workspace }}",
        )

        self.assertTrue(any("same exact inputs" in finding.message for finding in self.findings()))

    def test_second_upload_step_is_rejected(self):
        action = self.repository / ".github/actions/safe-upload-artifact/action.yml"
        content = action.read_text(encoding="utf-8")
        content += (
            "\n    - name: Bypass\n"
            "      uses: actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a\n"
        )
        action.write_text(content, encoding="utf-8")

        self.assertTrue(any("immutable pin" in finding.message for finding in self.findings()))

    def test_secret_interpolation_in_run_script_is_rejected(self):
        self.replace(
            ".github/workflows/pull-request.yaml",
            "run: ./gradlew quality",
            "run: |\n          echo '${{ secrets.SYNTHETIC_ONLY }}'",
        )

        self.assertTrue(any("direct secret interpolation" in finding.message for finding in self.findings()))

    def test_scalar_environment_dump_is_rejected(self):
        self.replace(
            ".github/workflows/pull-request.yaml",
            "run: ./gradlew quality",
            "run: printenv",
        )

        self.assertTrue(any("environment dump" in finding.message for finding in self.findings()))

    def test_quoted_job_and_run_keys_are_rejected_fail_closed(self):
        workflow = self.repository / ".github/workflows/pull-request.yaml"
        content = workflow.read_text(encoding="utf-8")
        insertion = (
            '  "untracked-write-and-upload":\n'
            "    permissions: write-all\n"
            "    steps:\n"
            '      - "run": printenv\n\n'
        )
        workflow.write_text(content.replace("jobs:\n", "jobs:\n" + insertion, 1), encoding="utf-8")

        findings = self.findings()
        self.assertTrue(any("quoted YAML mapping keys" in finding.message for finding in findings))
        self.assertTrue(any("canonical block mapping" in finding.message for finding in findings))

    def test_flow_mapping_step_is_rejected_fail_closed(self):
        workflow = self.repository / ".github/workflows/pull-request.yaml"
        content = workflow.read_text(encoding="utf-8")
        content = content.replace(
            "    steps:\n",
            "    steps:\n      - {run: printenv}\n",
            1,
        )
        workflow.write_text(content, encoding="utf-8")

        self.assertTrue(any("alternate YAML structural syntax" in finding.message for finding in self.findings()))


if __name__ == "__main__":
    unittest.main()
