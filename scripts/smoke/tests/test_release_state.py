from __future__ import annotations

import hashlib
import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path
from typing import Any
from unittest.mock import MagicMock, patch


MODULE_PATH = Path(__file__).parents[1] / "release_state.py"
SPEC = importlib.util.spec_from_file_location("release_state", MODULE_PATH)
assert SPEC and SPEC.loader
release_state = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = release_state
SPEC.loader.exec_module(release_state)


def manifest(release_id: str = "a" * 40) -> str:
    lines = [
        "apiVersion: v1",
        "kind: ConfigMap",
        "data:",
        f'  releaseId: "{release_id}"',
    ]
    for index, service in enumerate(release_state.SERVICES):
        digest = format(index + 1, "064x")
        lines.append(f'  {service}Image: "ghcr.io/mattoyuzuru/mnema/{service}@sha256:{digest}"')
    return "\n".join(lines) + "\n"


class FakeKubectl:
    def __init__(
        self,
        configmaps: dict[str, dict[str, Any]] | None = None,
        *,
        application_deployments: bool = False,
    ) -> None:
        self.configmaps = configmaps or {}
        self.applied: list[Path] = []
        self.application_deployments = application_deployments

    def get_configmap(self, name: str, *, required: bool = True):
        value = self.configmaps.get(name)
        if value is None and required:
            raise release_state.StateFailure("configmap_read_failed")
        return value

    def persist(self, name: str, release_manifest: str, record: dict[str, Any]) -> None:
        self.configmaps[name] = {
            "data": {
                "manifest.yaml": release_manifest,
                "record.json": json.dumps(record),
            }
        }

    def apply_manifest(self, manifest_path: Path) -> None:
        self.applied.append(manifest_path)

    def has_application_deployments(self) -> bool:
        return self.application_deployments


class FakeArtifacts:
    def __init__(self, artifact: Any) -> None:
        self.artifact = artifact

    def fetch(self, name: str, release_sha: str, manifest_filename: str):
        return self.artifact


class ReleaseStateTest(unittest.TestCase):
    def test_artifact_lookup_requires_exact_release_sha(self) -> None:
        client = release_state.GitHubArtifactClient("MattoYuzuru/Mnema", "token")
        payload = {
            "artifacts": [
                {
                    "id": 1,
                    "name": "production-release-manifest",
                    "expired": False,
                    "created_at": "2026-08-19T00:00:00Z",
                    "workflow_run": {"head_sha": "b" * 40},
                },
                {
                    "id": 2,
                    "name": "production-release-manifest",
                    "expired": False,
                    "created_at": "2026-08-19T00:01:00Z",
                    "workflow_run": {"head_sha": "a" * 40},
                },
            ]
        }

        with patch.object(client, "_json", return_value=payload):
            selected = client._find("production-release-manifest", "a" * 40)

        self.assertEqual(2, selected["id"])

    def test_signed_artifact_download_does_not_forward_github_token(self) -> None:
        client = release_state.GitHubArtifactClient("MattoYuzuru/Mnema", "sensitive-token")
        redirect = release_state.HTTPError(
            "https://api.github.com/artifact",
            302,
            "Found",
            {"Location": "https://objects.example/signed-artifact"},
            None,
        )
        opener = MagicMock()
        opener.open.side_effect = redirect
        response = MagicMock()
        response.__enter__.return_value = response
        response.read.return_value = b"archive"

        with (
            patch.object(release_state, "build_opener", return_value=opener),
            patch.object(release_state, "urlopen", return_value=response) as urlopen_mock,
        ):
            result = client._download(123)

        signed_request = urlopen_mock.call_args.args[0]
        self.assertEqual(b"archive", result)
        self.assertEqual("https://objects.example/signed-artifact", signed_request.full_url)
        self.assertIsNone(signed_request.get_header("Authorization"))

    def test_record_contains_all_digests_checksum_and_risks(self) -> None:
        content = manifest()
        record = release_state.build_record(
            content,
            environment="staging",
            deployed_at="2026-08-19T00:00:00Z",
            workflow_run_id="123",
        )

        self.assertEqual("a" * 40, record["releaseId"])
        self.assertEqual(set(release_state.SERVICES), set(record["images"]))
        self.assertEqual(hashlib.sha256(content.encode()).hexdigest(), record["manifestSha256"])
        self.assertGreaterEqual(len(record["knownRisks"]), 2)
        release_state.validate_record(content, record)

    def test_successful_record_rotates_current_to_previous(self) -> None:
        old_manifest = manifest("a" * 40)
        new_manifest = manifest("b" * 40)
        old_record = release_state.build_record(
            old_manifest, environment="prod", deployed_at="2026-08-18T00:00:00Z", workflow_run_id="1"
        )
        new_record = release_state.build_record(
            new_manifest, environment="prod", deployed_at="2026-08-19T00:00:00Z", workflow_run_id="2"
        )
        kubectl = FakeKubectl(
            {
                release_state.STATE_CURRENT: {
                    "data": {"manifest.yaml": old_manifest, "record.json": json.dumps(old_record)}
                }
            }
        )
        manager = release_state.ReleaseStateManager(kubectl)

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest_path = root / "new.yaml"
            record_path = root / "new.json"
            manifest_path.write_text(new_manifest)
            record_path.write_text(json.dumps(new_record))
            with patch.object(release_state, "utc_now", return_value="2026-08-19T01:02:03Z"):
                manager.record(manifest_path, record_path)
            persisted_record = json.loads(record_path.read_text())

        previous_manifest, previous_record = release_state.state_from_configmap(
            kubectl.configmaps[release_state.STATE_PREVIOUS]
        )
        current_manifest, current_record = release_state.state_from_configmap(
            kubectl.configmaps[release_state.STATE_CURRENT]
        )
        self.assertEqual(old_manifest, previous_manifest)
        self.assertEqual(old_record["releaseId"], previous_record["releaseId"])
        self.assertEqual(new_manifest, current_manifest)
        self.assertEqual(new_record["releaseId"], current_record["releaseId"])
        self.assertEqual("2026-08-19T01:02:03Z", current_record["deployedAt"])
        self.assertEqual("2026-08-19T01:02:03Z", persisted_record["deployedAt"])

    def test_broken_staging_manifest_changes_identity_and_frontend_only(self) -> None:
        content = manifest() + (
            "---\n"
            "spec:\n"
            f"  release: {'a' * 40}\n"
            f"  image: ghcr.io/mattoyuzuru/mnema/frontend@sha256:{'1'.zfill(64)}\n"
        )

        broken, release_id = release_state.create_broken_staging_manifest(content)

        self.assertEqual("0" * 40, release_id)
        self.assertNotIn("a" * 40, broken)
        self.assertEqual(2, broken.count("ghcr.io/mattoyuzuru/mnema/frontend@sha256:" + "0" * 64))
        _, images = release_state.parse_manifest(broken)
        self.assertEqual("0" * 64, images["frontend"].rsplit(":", 1)[1])
        for service in release_state.SERVICES[1:]:
            self.assertIn(f"/{service}@sha256:", images[service])

    def test_broken_staging_manifest_requires_deployment_occurrences(self) -> None:
        with self.assertRaisesRegex(release_state.StateFailure, "broken_drill_frontend_image_incomplete"):
            release_state.create_broken_staging_manifest(manifest())

    def test_first_snapshot_adopts_only_matching_live_artifact(self) -> None:
        content = manifest()
        _, images = release_state.parse_manifest(content)
        live = {"releaseId": "a" * 40}
        live.update({f"{service}Image": image for service, image in images.items()})
        kubectl = FakeKubectl({"mnema-release": {"data": live}})
        artifact = release_state.ReleaseArtifact(
            content,
            hashlib.sha256(content.encode()).hexdigest(),
            "2026-08-19T00:00:00Z",
        )
        manager = release_state.ReleaseStateManager(kubectl, FakeArtifacts(artifact))

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            release_id = manager.snapshot(
                environment="staging",
                artifact_name="staging-release-manifest",
                artifact_filename="staging-release.yaml",
                rollback_manifest=root / "rollback.yaml",
                rollback_record=root / "rollback.json",
            )
            self.assertEqual("a" * 40, release_id)
            self.assertEqual(content, (root / "rollback.yaml").read_text())
        self.assertNotIn(release_state.STATE_CURRENT, kubectl.configmaps)

    def test_first_staging_snapshot_accepts_only_a_truly_empty_application_boundary(self) -> None:
        manager = release_state.ReleaseStateManager(
            FakeKubectl(application_deployments=False),
            FakeArtifacts(None),
        )

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            release_id = manager.snapshot(
                environment="staging",
                artifact_name="staging-release-manifest",
                artifact_filename="staging-release.yaml",
                rollback_manifest=root / "rollback.yaml",
                rollback_record=root / "rollback.json",
                allow_empty=True,
            )
            self.assertIsNone(release_id)
            self.assertFalse((root / "rollback.yaml").exists())

        manager = release_state.ReleaseStateManager(
            FakeKubectl(application_deployments=True),
            FakeArtifacts(None),
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with self.assertRaisesRegex(release_state.StateFailure, "live_release_state_missing"):
                manager.snapshot(
                    environment="staging",
                    artifact_name="staging-release-manifest",
                    artifact_filename="staging-release.yaml",
                    rollback_manifest=root / "rollback.yaml",
                    rollback_record=root / "rollback.json",
                    allow_empty=True,
                )

    def test_tampered_record_is_rejected_before_rollback_apply(self) -> None:
        content = manifest()
        record = release_state.build_record(
            content, environment="prod", deployed_at="2026-08-19T00:00:00Z", workflow_run_id="1"
        )
        record["manifestSha256"] = "0" * 64
        kubectl = FakeKubectl()
        manager = release_state.ReleaseStateManager(kubectl)

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest_path = root / "rollback.yaml"
            record_path = root / "rollback.json"
            manifest_path.write_text(content)
            record_path.write_text(json.dumps(record))
            with self.assertRaises(release_state.StateFailure):
                manager.rollback(manifest_path, record_path)
        self.assertEqual([], kubectl.applied)


if __name__ == "__main__":
    unittest.main()
