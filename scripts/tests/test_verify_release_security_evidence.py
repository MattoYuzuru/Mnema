from __future__ import annotations

import argparse
import json
import tempfile
import unittest
from pathlib import Path


SCRIPTS_DIR = Path(__file__).resolve().parents[1]
import sys

sys.path.insert(0, str(SCRIPTS_DIR))

from verify_release_security_evidence import (  # noqa: E402
    EvidenceFailure,
    SERVICES,
    aggregate,
    evaluate,
    validate_workflow_contract,
    verify_release,
)


COMMIT = "1" * 40
RUN_ID = 123456
RUN_ATTEMPT = 2
REPOSITORY = "mattoyuzuru/mnema"


class ReleaseSecurityEvidenceTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary_directory.name)
        self.evidence_dir = self.root / "evidence"
        self.digests_dir = self.root / "digests"
        self.evidence_dir.mkdir()
        self.digests_dir.mkdir()
        self.exceptions = self.root / "exceptions.json"
        self.write_json(self.exceptions, {"schemaVersion": 1, "exceptions": []})
        self.trivy_ignore = self.root / "trivy-release-ignore"
        self.trivy_ignore.write_text("# Intentionally empty.\n", encoding="utf-8")

    def tearDown(self) -> None:
        self.temporary_directory.cleanup()

    @staticmethod
    def write_json(path: Path, payload: object) -> None:
        path.write_text(json.dumps(payload), encoding="utf-8")

    @staticmethod
    def digest(service: str) -> str:
        value = SERVICES.index(service) + 1
        return "sha256:" + f"{value:064x}"

    def image(self, service: str) -> str:
        return f"ghcr.io/{REPOSITORY}/{service}@{self.digest(service)}"

    def fixture(
        self,
        service: str,
        vulnerabilities: list[dict[str, str]] | None = None,
    ) -> argparse.Namespace:
        image = self.image(service)
        image_name, digest = image.rsplit("@", 1)
        digest_hex = digest.removeprefix("sha256:")
        directory = self.root / service
        directory.mkdir(exist_ok=True)
        report = directory / "report.json"
        sarif = directory / "report.sarif"
        metadata = directory / "metadata.json"
        sbom = directory / "sbom.json"
        provenance = directory / "provenance.json"
        sbom_verification = directory / "sbom-verification.json"
        output = self.evidence_dir / f"{service}-security-evidence.json"
        self.write_json(
            report,
            {
                "SchemaVersion": 2,
                "ArtifactName": image,
                "Metadata": {"RepoDigests": [image]},
                "Results": [
                    {
                        "Target": "runtime",
                        "Vulnerabilities": vulnerabilities or [],
                    }
                ],
            },
        )
        self.write_json(
            metadata,
            {
                "Version": "0.70.0",
                "VulnerabilityDB": {
                    "Version": 2,
                    "UpdatedAt": "2026-08-29T10:00:00Z",
                    "DownloadedAt": "2026-08-29T10:05:00Z",
                },
            },
        )
        self.write_json(
            sarif,
            {
                "version": "2.1.0",
                "runs": [
                    {
                        "tool": {
                            "driver": {
                                "name": "Trivy",
                                "fullName": "Trivy Vulnerability Scanner",
                            }
                        },
                        "properties": {"imageName": image, "repoDigests": [image]},
                        "results": [],
                    }
                ],
            },
        )
        self.write_json(
            sbom,
            {
                "spdxVersion": "SPDX-2.3",
                "SPDXID": "SPDXRef-DOCUMENT",
                "packages": [{"name": service, "SPDXID": "SPDXRef-Package"}],
            },
        )

        def attestation(predicate_type: str) -> list[dict[str, object]]:
            return [
                {
                    "verificationResult": {
                        "statement": {
                            "predicateType": predicate_type,
                            "subject": [
                                {"name": image_name, "digest": {"sha256": digest_hex}}
                            ],
                        }
                    }
                }
            ]

        self.write_json(provenance, attestation("https://slsa.dev/provenance/v1"))
        self.write_json(sbom_verification, attestation("https://spdx.dev/Document/v2.3"))
        return argparse.Namespace(
            service=service,
            image=image,
            repository=REPOSITORY,
            source_commit=COMMIT,
            build_run_id=RUN_ID,
            build_run_attempt=RUN_ATTEMPT,
            report=report,
            sarif=sarif,
            scanner_metadata=metadata,
            exceptions=self.exceptions,
            trivy_ignore=self.trivy_ignore,
            sbom=sbom,
            provenance_verification=provenance,
            sbom_verification=sbom_verification,
            output=output,
            now="2026-08-29",
        )

    @staticmethod
    def vulnerability(severity: str = "HIGH") -> dict[str, str]:
        return {
            "VulnerabilityID": "CVE-2026-12345",
            "PkgName": "openssl",
            "Severity": severity,
        }

    def set_exception(
        self,
        service: str,
        *,
        expires: str = "2026-09-10",
        packages: list[str] | None = None,
    ) -> None:
        self.write_json(
            self.exceptions,
            {
                "schemaVersion": 1,
                "exceptions": [
                    {
                        "finding": "CVE-2026-12345",
                        "image": self.image(service),
                        "packages": packages or ["openssl"],
                        "rationale": "No fixed runtime package exists; network policy limits exposure.",
                        "owner": "@MattoYuzuru",
                        "created": "2026-08-29",
                        "expires": expires,
                    }
                ],
            },
        )

    def evaluate_all(self) -> None:
        for service in SERVICES:
            arguments = self.fixture(service)
            evaluate(arguments)
            (self.digests_dir / f"{service}.digest").write_text(
                self.digest(service) + "\n", encoding="utf-8"
            )

    def aggregate_arguments(self) -> argparse.Namespace:
        return argparse.Namespace(
            evidence_dir=self.evidence_dir,
            digests_dir=self.digests_dir,
            exceptions=self.exceptions,
            trivy_ignore=self.trivy_ignore,
            repository=REPOSITORY,
            source_commit=COMMIT,
            build_run_id=RUN_ID,
            build_run_attempt=RUN_ATTEMPT,
            output=self.root / "release-security-evidence.json",
            now="2026-08-29",
        )

    def manifest(self) -> Path:
        path = self.root / "release.yaml"
        images = "\n".join(f"      image: {self.image(service)}" for service in SERVICES)
        path.write_text(
            f'apiVersion: v1\nkind: ConfigMap\ndata:\n  releaseId: "{COMMIT}"\n{images}\n',
            encoding="utf-8",
        )
        return path

    def test_low_and_medium_findings_are_reported_without_blocking(self) -> None:
        arguments = self.fixture(
            "learning",
            [self.vulnerability("LOW"), {**self.vulnerability("MEDIUM"), "PkgName": "zlib"}],
        )

        evaluate(arguments)

        evidence = json.loads(arguments.output.read_text(encoding="utf-8"))
        self.assertEqual(1, evidence["scanner"]["counts"]["LOW"])
        self.assertEqual(1, evidence["scanner"]["counts"]["MEDIUM"])
        self.assertEqual("passed", evidence["policy"]["outcome"])

    def test_unexcepted_high_finding_blocks_release(self) -> None:
        arguments = self.fixture("identity-account", [self.vulnerability("HIGH")])

        with self.assertRaisesRegex(EvidenceFailure, "blocking vulnerabilities"):
            evaluate(arguments)

        self.assertFalse(arguments.output.exists())

    def test_exact_time_bounded_exception_allows_named_finding(self) -> None:
        self.set_exception("identity-account")
        arguments = self.fixture("identity-account", [self.vulnerability("CRITICAL")])

        evaluate(arguments)

        evidence = json.loads(arguments.output.read_text(encoding="utf-8"))
        self.assertEqual("CVE-2026-12345", evidence["exceptions"][0]["finding"])
        self.assertEqual("openssl", evidence["exceptions"][0]["package"])

    def test_expired_exception_is_rejected(self) -> None:
        self.set_exception("identity-account", expires="2026-08-28")

        with self.assertRaisesRegex(EvidenceFailure, "expired"):
            evaluate(self.fixture("identity-account", [self.vulnerability()]))

    def test_exception_longer_than_thirty_days_is_rejected(self) -> None:
        self.set_exception("identity-account", expires="2026-10-01")

        with self.assertRaisesRegex(EvidenceFailure, "30-day"):
            evaluate(self.fixture("identity-account", [self.vulnerability()]))

    def test_wildcard_exception_scope_is_rejected(self) -> None:
        self.set_exception("identity-account", packages=["open*"])

        with self.assertRaisesRegex(EvidenceFailure, "without wildcards"):
            evaluate(self.fixture("identity-account", [self.vulnerability()]))

    def test_unused_exception_scope_is_rejected(self) -> None:
        self.set_exception("identity-account")

        with self.assertRaisesRegex(EvidenceFailure, "unused exception"):
            evaluate(self.fixture("identity-account"))

    def test_digest_mismatched_scan_is_rejected(self) -> None:
        arguments = self.fixture("identity-account")
        report = json.loads(arguments.report.read_text(encoding="utf-8"))
        report["ArtifactName"] = self.image("learning")
        report["Metadata"]["RepoDigests"] = [self.image("learning")]
        self.write_json(arguments.report, report)

        with self.assertRaisesRegex(EvidenceFailure, "not bound"):
            evaluate(arguments)

    def test_missing_attestation_is_rejected(self) -> None:
        arguments = self.fixture("learning")
        self.write_json(arguments.provenance_verification, [])

        with self.assertRaisesRegex(EvidenceFailure, "no verified attestations"):
            evaluate(arguments)

    def test_malformed_sarif_is_rejected(self) -> None:
        arguments = self.fixture("learning")
        self.write_json(arguments.sarif, {"version": "2.1.0", "runs": []})

        with self.assertRaisesRegex(EvidenceFailure, "at least one run"):
            evaluate(arguments)

    def test_aggregate_binds_exactly_two_digests(self) -> None:
        self.evaluate_all()
        arguments = self.aggregate_arguments()

        aggregate(arguments)

        release = json.loads(arguments.output.read_text(encoding="utf-8"))
        self.assertEqual(2, release["summary"]["imageCount"])
        self.assertEqual(COMMIT, release["source"]["commit"])

    def test_aggregate_rejects_missing_image_evidence(self) -> None:
        self.evaluate_all()
        (self.evidence_dir / "learning-security-evidence.json").unlink()

        with self.assertRaisesRegex(EvidenceFailure, "invalid JSON file"):
            aggregate(self.aggregate_arguments())

    def test_aggregate_rejects_digest_mismatch(self) -> None:
        self.evaluate_all()
        (self.digests_dir / "learning.digest").write_text("sha256:" + "f" * 64, encoding="utf-8")

        with self.assertRaisesRegex(EvidenceFailure, "does not match"):
            aggregate(self.aggregate_arguments())

    def test_verify_release_accepts_matching_manifest(self) -> None:
        self.evaluate_all()
        arguments = self.aggregate_arguments()
        aggregate(arguments)

        verify_release(
            argparse.Namespace(
                evidence=arguments.output,
                manifest=self.manifest(),
                expected_repository=REPOSITORY,
                expected_commit=COMMIT,
                expected_run_id=RUN_ID,
                trivy_ignore=self.trivy_ignore,
                now="2026-08-29",
            )
        )

    def test_verify_release_rejects_manifest_digest_mismatch(self) -> None:
        self.evaluate_all()
        arguments = self.aggregate_arguments()
        aggregate(arguments)
        manifest = self.manifest()
        content = manifest.read_text(encoding="utf-8").replace(self.image("learning"), self.image("identity-account"), 1)
        manifest.write_text(content, encoding="utf-8")

        with self.assertRaisesRegex(EvidenceFailure, "do not match"):
            verify_release(
                argparse.Namespace(
                    evidence=arguments.output,
                    manifest=manifest,
                    expected_repository=REPOSITORY,
                    expected_commit=COMMIT,
                    expected_run_id=RUN_ID,
                    trivy_ignore=self.trivy_ignore,
                    now="2026-08-29",
                )
            )

    def test_verify_release_rejects_missing_sbom_status(self) -> None:
        self.evaluate_all()
        arguments = self.aggregate_arguments()
        aggregate(arguments)
        payload = json.loads(arguments.output.read_text(encoding="utf-8"))
        payload["images"][0]["sbom"]["githubAttestationVerified"] = False
        self.write_json(arguments.output, payload)

        with self.assertRaisesRegex(EvidenceFailure, "SBOM attestation"):
            verify_release(
                argparse.Namespace(
                    evidence=arguments.output,
                    manifest=self.manifest(),
                    expected_repository=REPOSITORY,
                    expected_commit=COMMIT,
                    expected_run_id=RUN_ID,
                    trivy_ignore=self.trivy_ignore,
                    now="2026-08-29",
                )
            )

    def test_active_trivy_ignore_entry_is_rejected(self) -> None:
        self.trivy_ignore.write_text("CVE-2026-12345\n", encoding="utf-8")

        with self.assertRaisesRegex(EvidenceFailure, "comments only"):
            evaluate(self.fixture("identity-account"))

    def test_expired_exception_is_rechecked_during_promotion(self) -> None:
        self.set_exception("identity-account", expires="2026-09-10")
        for service in SERVICES:
            vulnerabilities = [self.vulnerability("HIGH")] if service == "identity-account" else None
            arguments = self.fixture(service, vulnerabilities)
            evaluate(arguments)
            (self.digests_dir / f"{service}.digest").write_text(
                self.digest(service) + "\n", encoding="utf-8"
            )
        aggregate_arguments = self.aggregate_arguments()
        aggregate(aggregate_arguments)

        with self.assertRaisesRegex(EvidenceFailure, "not active on the promotion date"):
            verify_release(
                argparse.Namespace(
                    evidence=aggregate_arguments.output,
                    manifest=self.manifest(),
                    expected_repository=REPOSITORY,
                    expected_commit=COMMIT,
                    expected_run_id=RUN_ID,
                    trivy_ignore=self.trivy_ignore,
                    now="2026-09-11",
                )
            )

    def test_workflow_continue_on_error_bypass_is_rejected(self) -> None:
        repository_root = SCRIPTS_DIR.parent
        content = (repository_root / ".github/workflows/deploy.yaml").read_text(encoding="utf-8")
        content = content.replace(
            "      - name: Enforce release vulnerability policy\n        env:\n",
            "      - name: Enforce release vulnerability policy\n"
            "        continue-on-error: true\n"
            "        env:\n",
            1,
        )
        workflow = self.root / "deploy.yaml"
        workflow.write_text(content, encoding="utf-8")

        with self.assertRaisesRegex(EvidenceFailure, "step keys"):
            validate_workflow_contract(workflow)

    def test_workflow_quoted_continue_on_error_bypass_is_rejected(self) -> None:
        repository_root = SCRIPTS_DIR.parent
        content = (repository_root / ".github/workflows/deploy.yaml").read_text(encoding="utf-8")
        content = content.replace(
            "      - name: Enforce release vulnerability policy\n        env:\n",
            "      - name: Enforce release vulnerability policy\n"
            '        "continue-on-error": true\n'
            "        env:\n",
            1,
        )
        workflow = self.root / "deploy.yaml"
        workflow.write_text(content, encoding="utf-8")

        with self.assertRaisesRegex(EvidenceFailure, "step keys"):
            validate_workflow_contract(workflow)


if __name__ == "__main__":
    unittest.main()
