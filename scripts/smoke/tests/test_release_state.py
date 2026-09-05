from __future__ import annotations

import hashlib
import importlib.util
import json
import sys
import tempfile
import unittest
from argparse import Namespace
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


def live_deployment(service: str, *, replicas: int = 1, release_prefix: str = "a" * 7) -> dict[str, Any]:
    name = f"mnema-{service}"
    containers = [{"name": service, "image": f"ghcr.io/mattoyuzuru/mnema/{service}:sha-{release_prefix}"}]
    init_containers = [] if service == "frontend" else [{"name": "wait-for-postgres", "image": "postgres:18"}]
    return {
        "apiVersion": "apps/v1",
        "kind": "Deployment",
        "metadata": {
            "name": name,
            "namespace": "prod",
            "generation": 3,
            "resourceVersion": "123",
            "uid": f"uid-{service}",
            "annotations": {
                "deployment.kubernetes.io/revision": "7",
                "kubectl.kubernetes.io/last-applied-configuration": "not-for-rollback",
            },
        },
        "spec": {
            "replicas": replicas,
            "selector": {"matchLabels": {"app": name}},
            "template": {
                "metadata": {"labels": {"app": name}},
                "spec": {"initContainers": init_containers, "containers": containers},
            },
        },
        "status": {
            "observedGeneration": 3,
            "replicas": replicas,
            "updatedReplicas": replicas,
            "readyReplicas": replicas,
            "availableReplicas": replicas,
        },
    }


def live_pod(service: str, replica: int = 0, *, digest: str | None = None) -> dict[str, Any]:
    name = f"mnema-{service}"
    app_digest = digest or format(release_state.SERVICES.index(service) + 1, "064x")
    init_statuses = [] if service == "frontend" else [{
        "name": "wait-for-postgres",
        "imageID": "docker-pullable://docker.io/library/postgres@sha256:" + "f" * 64,
    }]
    return {
        "metadata": {"name": f"{name}-{replica}", "namespace": "prod", "labels": {"app": name}},
        "status": {
            "phase": "Running",
            "conditions": [{"type": "Ready", "status": "True"}],
            "initContainerStatuses": init_statuses,
            "containerStatuses": [{
                "name": service,
                "ready": True,
                "imageID": f"ghcr.io/mattoyuzuru/mnema/{service}@sha256:{app_digest}",
            }],
        },
    }


def live_namespaced_resource(kind: str, name: str) -> dict[str, Any]:
    api_version = "v1" if kind == "Service" else "networking.k8s.io/v1"
    spec: dict[str, Any]
    if kind == "Service":
        spec = {
            "clusterIP": "10.43.0.10",
            "clusterIPs": ["10.43.0.10"],
            "ipFamilies": ["IPv4"],
            "ipFamilyPolicy": "SingleStack",
            "selector": {"app": name},
            "ports": [{"port": 80, "targetPort": 8080}],
        }
    else:
        spec = {"rules": [{"host": "mnema.app", "http": {"paths": []}}]}
    return {
        "apiVersion": api_version,
        "kind": kind,
        "metadata": {
            "name": name,
            "namespace": "prod",
            "resourceVersion": "456",
            "uid": f"uid-{name}",
        },
        "spec": spec,
        "status": {"loadBalancer": {}},
    }


class FakeKubectl:
    namespace = "prod"
    def __init__(
        self,
        configmaps: dict[str, dict[str, Any]] | None = None,
        *,
        application_deployments: bool = False,
    ) -> None:
        self.configmaps = configmaps or {}
        self.applied: list[Path] = []
        self.application_deployments = application_deployments
        self.created_states: list[str] = []

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

    def create_state(self, name: str, release_manifest: str, record: dict[str, Any]) -> None:
        if name in self.configmaps:
            raise release_state.StateFailure("kubectl_command_failed")
        self.created_states.append(name)
        self.persist(name, release_manifest, record)

    def apply_manifest(self, manifest_path: Path) -> None:
        self.applied.append(manifest_path)

    def has_application_deployments(self) -> bool:
        return self.application_deployments


class FakeArtifacts:
    def __init__(self, artifact: Any) -> None:
        self.artifact = artifact

    def fetch(self, name: str, release_sha: str, manifest_filename: str):
        return self.artifact


class FakeLiveKubectl(FakeKubectl):
    namespace = "prod"

    def __init__(self, configmaps: dict[str, dict[str, Any]] | None = None) -> None:
        super().__init__(configmaps)
        self.deployments = {
            service: live_deployment(service, replicas=2 if service == "frontend" else 1)
            for service in release_state.SERVICES
        }
        self.pods = {
            service: [live_pod(service, replica) for replica in range(2 if service == "frontend" else 1)]
            for service in release_state.SERVICES
        }

    def get_resource(self, kind: str, name: str) -> dict[str, Any]:
        if kind == "deployment":
            return self.deployments[name.removeprefix("mnema-")]
        if kind == "service":
            return live_namespaced_resource("Service", name)
        if kind == "ingress":
            return live_namespaced_resource("Ingress", name)
        raise AssertionError((kind, name))

    def get_pods(self, selector: str) -> list[dict[str, Any]]:
        return self.pods[selector.removeprefix("app=mnema-")]


class ReleaseStateTest(unittest.TestCase):
    def test_capture_live_builds_secret_free_digest_pinned_legacy_baseline(self) -> None:
        captured, record = release_state.LiveReleaseCapture(FakeLiveKubectl()).capture(
            "a" * 40,
            "production",
        )

        release_id, images = release_state.parse_manifest(captured)
        self.assertEqual("a" * 40, release_id)
        self.assertEqual(set(release_state.SERVICES), set(images))
        self.assertTrue(record["adopted"])
        self.assertEqual(release_state.READINESS_SMOKE_VERSION, record["authenticatedSmokeVersion"])
        self.assertTrue(record["legacyReadinessOnly"])
        release_state.validate_record(captured, record)
        self.assertNotIn("resourceVersion", captured)
        self.assertNotIn('"status"', captured)
        self.assertNotIn("last-applied-configuration", captured)
        self.assertNotIn('"clusterIP"', captured)
        self.assertNotIn("Secret", captured)
        for service, image in images.items():
            self.assertIn(f'"image": "{image}"', captured)
            self.assertIn(f'  {service}Image: "{image}"', captured)

    def test_capture_live_rejects_existing_release_state(self) -> None:
        for existing in (
            release_state.STATE_CURRENT,
            release_state.STATE_PREVIOUS,
            "mnema-release",
        ):
            kubectl = FakeLiveKubectl({existing: {"data": {}}})
            with self.assertRaisesRegex(release_state.StateFailure, "live_capture_already_initialized"):
                release_state.LiveReleaseCapture(kubectl).capture("a" * 40, "production")

    def test_capture_live_is_fixed_to_the_production_namespace(self) -> None:
        kubectl = FakeLiveKubectl()
        kubectl.namespace = "mnema-staging"
        with self.assertRaisesRegex(release_state.StateFailure, "live_capture_identity_invalid"):
            release_state.LiveReleaseCapture(kubectl).capture("a" * 40, "production")

    def test_capture_live_rejects_tag_or_runtime_digest_disagreement(self) -> None:
        kubectl = FakeLiveKubectl()
        kubectl.deployments["auth"]["spec"]["template"]["spec"]["containers"][0]["image"] = (
            "ghcr.io/mattoyuzuru/mnema/auth:sha-bbbbbbb"
        )
        with self.assertRaisesRegex(release_state.StateFailure, "live_release_tag_mismatch"):
            release_state.LiveReleaseCapture(kubectl).capture("a" * 40, "production")

        kubectl = FakeLiveKubectl()
        kubectl.pods["frontend"][1] = live_pod("frontend", 1, digest="e" * 64)
        with self.assertRaisesRegex(release_state.StateFailure, "live_runtime_image_mixed"):
            release_state.LiveReleaseCapture(kubectl).capture("a" * 40, "production")

    def test_capture_live_rejects_unready_workload(self) -> None:
        kubectl = FakeLiveKubectl()
        kubectl.pods["core"][0]["status"]["conditions"][0]["status"] = "False"
        with self.assertRaisesRegex(release_state.StateFailure, "live_deployment_pods_not_stable"):
            release_state.LiveReleaseCapture(kubectl).capture("a" * 40, "production")

    def test_seed_live_is_create_only_and_preserves_the_capture_record(self) -> None:
        captured, record = release_state.LiveReleaseCapture(FakeLiveKubectl()).capture(
            "a" * 40,
            "production",
        )
        kubectl = FakeLiveKubectl()
        manager = release_state.ReleaseStateManager(kubectl)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest_path = root / "captured.yaml"
            record_path = root / "captured.json"
            manifest_path.write_text(captured)
            record_path.write_text(json.dumps(record))

            release_id = manager.seed_live(manifest_path, record_path)

        self.assertEqual("a" * 40, release_id)
        self.assertEqual([release_state.STATE_CURRENT], kubectl.created_states)
        _, persisted = release_state.state_from_configmap(kubectl.configmaps[release_state.STATE_CURRENT])
        self.assertEqual(record, persisted)
        self.assertNotIn(release_state.STATE_PREVIOUS, kubectl.configmaps)

    def test_seed_live_cli_initializes_the_manager_before_use(self) -> None:
        args = Namespace(
            command="seed-live",
            namespace="prod",
            manifest=Path("captured.yaml"),
            record=Path("captured.json"),
        )
        kubectl = MagicMock()
        manager = MagicMock()
        manager.seed_live.return_value = "a" * 40

        with (
            patch.object(release_state, "parse_args", return_value=args),
            patch.object(release_state, "Kubectl", return_value=kubectl),
            patch.object(release_state, "ReleaseStateManager", return_value=manager) as manager_type,
            patch.object(release_state, "emit_result") as emit_result,
        ):
            self.assertEqual(0, release_state.main())

        manager_type.assert_called_once_with(kubectl)
        manager.seed_live.assert_called_once_with(Path("captured.yaml"), Path("captured.json"))
        emit_result.assert_called_once_with("a" * 40, "live_state_seeded", False, False)

    def test_seed_live_refuses_any_existing_release_marker(self) -> None:
        captured, record = release_state.LiveReleaseCapture(FakeLiveKubectl()).capture(
            "a" * 40,
            "production",
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest_path = root / "captured.yaml"
            record_path = root / "captured.json"
            manifest_path.write_text(captured)
            record_path.write_text(json.dumps(record))
            for existing in (release_state.STATE_CURRENT, release_state.STATE_PREVIOUS, "mnema-release"):
                kubectl = FakeLiveKubectl({existing: {"data": {}}})
                with self.assertRaisesRegex(
                    release_state.StateFailure,
                    "live_seed_already_initialized",
                ):
                    release_state.ReleaseStateManager(kubectl).seed_live(manifest_path, record_path)

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
        self.assertEqual(1, record["authenticatedSmokeVersion"])
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
        with self.assertRaisesRegex(release_state.StateFailure, "broken_drill_application_image_incomplete"):
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
            rollback_record = json.loads((root / "rollback.json").read_text())
            self.assertEqual(0, rollback_record["authenticatedSmokeVersion"])
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

    def test_rollback_reports_authenticated_smoke_capability(self) -> None:
        content = manifest()
        capable = release_state.build_record(
            content,
            environment="prod",
            deployed_at="2026-08-19T00:00:00Z",
            workflow_run_id="1",
        )
        adopted = {**capable, "authenticatedSmokeVersion": 0, "adopted": True}
        manager = release_state.ReleaseStateManager(FakeKubectl())

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest_path = root / "rollback.yaml"
            record_path = root / "rollback.json"
            manifest_path.write_text(content)
            record_path.write_text(json.dumps(adopted))
            release_id, smoke_version, topology, mode, maintenance_smoke_version = manager.rollback(
                manifest_path, record_path
            )
            self.assertEqual("a" * 40, release_id)
            self.assertEqual(release_state.IDENTITY_SMOKE_VERSION, smoke_version)
            self.assertEqual(release_state.LEGACY_TOPOLOGY, topology)
            self.assertEqual("legacy", mode)
            self.assertIsNone(maintenance_smoke_version)
            record_path.write_text(json.dumps(capable))
            _, smoke_version, _, _, _ = manager.rollback(manifest_path, record_path)
            self.assertEqual(release_state.AUTHENTICATED_SMOKE_VERSION, smoke_version)

    def test_non_adopted_record_cannot_downgrade_smoke_capability(self) -> None:
        content = manifest()
        record = release_state.build_record(
            content,
            environment="prod",
            deployed_at="2026-08-19T00:00:00Z",
            workflow_run_id="1",
        )
        record["authenticatedSmokeVersion"] = 0

        with self.assertRaisesRegex(
            release_state.StateFailure,
            "record_smoke_capability_downgrade",
        ):
            release_state.validate_record(content, record)

        record = {
            **record,
            "authenticatedSmokeVersion": release_state.READINESS_SMOKE_VERSION,
            "legacyReadinessOnly": True,
        }
        with self.assertRaisesRegex(
            release_state.StateFailure,
            "record_smoke_capability_downgrade",
        ):
            release_state.validate_record(content, record)

    def test_readiness_capability_requires_explicit_legacy_marker(self) -> None:
        content = manifest()
        record = release_state.build_record(
            content,
            environment="prod",
            deployed_at="2026-08-19T00:00:00Z",
            workflow_run_id=None,
            adopted=True,
        )
        record["authenticatedSmokeVersion"] = release_state.READINESS_SMOKE_VERSION

        with self.assertRaisesRegex(
            release_state.StateFailure,
            "record_readiness_capability_invalid",
        ):
            release_state.validate_record(content, record)


if __name__ == "__main__":
    unittest.main()
