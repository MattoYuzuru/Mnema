from __future__ import annotations

import copy
import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from test_release_state import FakeKubectl, release_state


NAMESPACE = "mnema-staging"
SHA = "b" * 40


def routes(maintenance: bool) -> list[dict]:
    result = []
    for name, host, tls in (
        ("mnema", "staging.mnema.app", "staging-mnema-app-tls"),
        ("mnema-auth", "auth.staging.mnema.app", "auth-staging-mnema-app-tls"),
    ):
        if maintenance:
            paths = [("/api", "learning" if name == "mnema" else "identity-account")]
        elif name == "mnema-auth":
            paths = [("/", "auth")]
        else:
            paths = [("/", "frontend"), ("/api/user", "user"), ("/api/core", "core"),
                     ("/api/media", "media"), ("/api/import", "import")]
        result.append({
            "apiVersion": "networking.k8s.io/v1", "kind": "Ingress",
            "metadata": {"name": name, "namespace": NAMESPACE,
                         "annotations": {"cert-manager.io/cluster-issuer": "letsencrypt-prod"}},
            "spec": {"ingressClassName": "traefik", "tls": [{"hosts": [host], "secretName": tls}],
                     "rules": [{"host": host, "http": {"paths": [
                         {"path": path, "pathType": "Prefix", "backend": {"service": {
                             "name": f"mnema-{service}", "port": {"number": 80}}}}
                         for path, service in paths
                     ]}}]},
        })
    return result


def manifest(maintenance: bool, *, include_routes: bool = True) -> str:
    services = ("identity-account", "learning") if maintenance else (
        "frontend", "auth", "user", "core", "media", "import")
    header = ["apiVersion: v1", "kind: ConfigMap", "metadata:",
              "  name: mnema-release", f"  namespace: {NAMESPACE}", "data:", f'  releaseId: "{SHA}"']
    if maintenance:
        header += ['  releaseTopology: "identity-learning"', '  releaseMode: "maintenance"',
                   '  productionEligible: "false"']
    resources = []
    for index, service in enumerate(services):
        image = f"ghcr.io/mattoyuzuru/mnema/{service}@sha256:{index + 1:064x}"
        key = "identityAccountImage" if service == "identity-account" else f"{service}Image"
        header.append(f'  {key}: "{image}"')
        for kind in ("Deployment", "Service"):
            resource = {"apiVersion": "apps/v1" if kind == "Deployment" else "v1", "kind": kind,
                        "metadata": {"name": f"mnema-{service}", "namespace": NAMESPACE}}
            if kind == "Deployment":
                resource["spec"] = {"template": {"spec": {"containers": [{
                    "name": service, "image": image, "env": [{"name": "MNEMA_BUILD_ID", "value": SHA}]
                }]}}}
            resources.append(resource)
    if include_routes:
        resources += routes(maintenance)
    return "\n".join(header) + "\n---\n" + "---\n".join(json.dumps(r) + "\n" for r in resources)


def record(content: str) -> dict:
    return release_state.build_record(content, environment="staging", deployed_at=None, workflow_run_id="123")


class StagingKubectl(FakeKubectl):
    namespace = NAMESPACE

    def __init__(self, maintenance=False):
        super().__init__()
        self.routes = routes(maintenance)
        self.removed = []

    def get_resource(self, kind, name):
        assert kind == "ingress"
        return copy.deepcopy(next(r for r in self.routes if r["metadata"]["name"] == name))

    def delete_applications(self, services):
        self.removed.append(services)


class MaintenanceReleaseStateTest(unittest.TestCase):
    def test_record_requires_exact_maintenance_contract(self):
        content = manifest(True)
        result = record(content)
        release_state.validate_record(content, result)
        self.assertEqual(2, result["schemaVersion"])
        self.assertEqual({"identity-account", "learning"}, set(result["images"]))
        self.assertFalse(result["productionEligible"])
        self.assertNotIn("authenticatedSmokeVersion", result)
        for key, value in (("productionEligible", True), ("releaseMode", "normal"),
                           ("maintenanceSmokeVersion", 0), ("authenticatedSmokeVersion", 1)):
            with self.subTest(key=key), self.assertRaises(release_state.StateFailure):
                release_state.validate_record(content, {**result, key: value})

    def test_maintenance_is_rejected_for_production(self):
        for environment in ("production", "prod"):
            with self.subTest(environment=environment), self.assertRaisesRegex(
                release_state.StateFailure, "release_not_production_eligible"
            ):
                release_state.build_record(manifest(True), environment=environment,
                                           deployed_at=None, workflow_run_id="123")

    def test_rejects_mixed_image_identity_and_duplicate_release_marker(self):
        for extra in ('  authImage: "ghcr.io/mattoyuzuru/mnema/auth@sha256:' + "f" * 64 + '"\n',
                      f'  releaseId: "{SHA}"\n'):
            with self.subTest(extra=extra), self.assertRaises(release_state.StateFailure):
                record(manifest(True) + extra)

    def test_transition_removes_only_six_exact_apps_and_reverse_only_two(self):
        plan = release_state.build_transition_plan(manifest(False), manifest(True), NAMESPACE)
        self.assertEqual(["frontend", "auth", "user", "core", "media", "import"], plan["removeApplications"])
        self.assertEqual(["identity-account", "learning"], plan["addApplications"])
        self.assertEqual(12, len(plan["removeResources"]))
        self.assertEqual({"Deployment", "Service"}, {r["kind"] for r in plan["removeResources"]})
        reverse = release_state.build_transition_plan(manifest(True), manifest(False), NAMESPACE)
        self.assertEqual(["identity-account", "learning"], reverse["removeApplications"])

    def test_transition_rejects_other_namespace_and_unknown_or_duplicate_resources(self):
        with self.assertRaisesRegex(release_state.StateFailure, "requires_staging"):
            release_state.build_transition_plan(manifest(False), manifest(True), "prod")
        for resource in (
            {"kind": "Secret", "metadata": {"name": "mnema-secrets", "namespace": NAMESPACE}},
            {"kind": "Deployment", "metadata": {"name": "neighbor", "namespace": NAMESPACE}},
            {"kind": "Service", "metadata": {"name": "mnema-learning", "namespace": NAMESPACE}},
        ):
            with self.subTest(resource=resource), self.assertRaisesRegex(
                release_state.StateFailure, "resource_inventory_invalid"
            ):
                release_state.build_transition_plan(manifest(False), manifest(True) + "---\n" + json.dumps(resource), NAMESPACE)

    def test_plan_is_checksum_bound_and_rejects_target_tampering(self):
        source, target = manifest(False), manifest(True)
        plan = release_state.build_transition_plan(source, target, NAMESPACE)
        release_state.validate_transition_plan(plan, source, target, NAMESPACE)
        for altered in (target + "\n", target.replace(SHA, "c" * 40)):
            with self.assertRaisesRegex(release_state.StateFailure, "transition_plan_mismatch"):
                release_state.validate_transition_plan(plan, source, altered, NAMESPACE)
        plan["removeApplications"].append("postgres")
        with self.assertRaisesRegex(release_state.StateFailure, "transition_plan_mismatch"):
            release_state.validate_transition_plan(plan, source, target, NAMESPACE)

    def test_snapshot_augments_old_artifact_without_losing_original_checksum(self):
        original = manifest(False, include_routes=False)
        original_record = record(original)
        kubectl = StagingKubectl()
        kubectl.routes[0]["metadata"]["annotations"]["kubectl.kubernetes.io/last-applied-configuration"] = "drop me"
        augmented, result = release_state.ensure_rollback_ingresses(kubectl, original, original_record)
        release_state.validate_record(augmented, result)
        self.assertEqual(original_record, result["snapshotAugmentation"]["sourceRecord"])
        self.assertEqual(hashlib.sha256(original.encode()).hexdigest(), original_record["manifestSha256"])
        self.assertNotIn("drop me", augmented)
        self.assertEqual(12, len(release_state.build_transition_plan(augmented, manifest(True), NAMESPACE)["removeResources"]))
        with self.assertRaises(release_state.StateFailure):
            release_state.validate_record(augmented.replace("staging.mnema.app", "bad.mnema.app", 1), result)

    def test_snapshot_rejects_mixed_live_routes_even_if_manifest_has_routes(self):
        kubectl = StagingKubectl()
        kubectl.routes[1] = routes(True)[1]
        for content in (manifest(False), manifest(False, include_routes=False)):
            with self.assertRaisesRegex(release_state.StateFailure, "ingress_topology_mismatch"):
                release_state.ensure_rollback_ingresses(kubectl, content, record(content))

    def test_rollback_removes_candidate_only_apps(self):
        kubectl = StagingKubectl()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            previous, saved, candidate = root / "previous.yaml", root / "previous.json", root / "candidate.yaml"
            previous.write_text(manifest(False))
            saved.write_text(json.dumps(record(manifest(False))))
            candidate.write_text(manifest(True))
            result = release_state.ReleaseStateManager(kubectl).rollback(previous, saved, candidate)
            self.assertEqual((SHA, 1, "legacy-six-service", "legacy"), result)
            self.assertEqual([previous], kubectl.applied)
            self.assertEqual([("identity-account", "learning")], kubectl.removed)

    def test_kubectl_removal_is_staging_only_and_exact(self):
        with self.assertRaisesRegex(release_state.StateFailure, "requires_staging"):
            release_state.Kubectl("prod").delete_applications(("auth",))
        kubectl = release_state.Kubectl(NAMESPACE)
        with patch.object(kubectl, "_run") as run:
            with self.assertRaisesRegex(release_state.StateFailure, "not_allowlisted"):
                kubectl.delete_applications(("postgres",))
            run.assert_not_called()
            kubectl.delete_applications(("user",))
            run.assert_called_once_with([
                "kubectl", "-n", NAMESPACE, "delete", "deployment/mnema-user", "service/mnema-user",
                "--ignore-not-found=true", "--cascade=foreground", "--wait=true", "--timeout=180s",
            ])


if __name__ == "__main__":
    unittest.main()
