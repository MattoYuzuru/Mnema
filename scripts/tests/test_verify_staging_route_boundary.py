from __future__ import annotations

from copy import deepcopy
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import unittest
from unittest.mock import patch

PATH = Path(__file__).resolve().parents[1] / "verify-staging-route-boundary.py"
SPEC = importlib.util.spec_from_file_location("verify_staging_route_boundary", PATH)
verifier = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = verifier
SPEC.loader.exec_module(verifier)


def boundary(kind: str) -> dict:
    if kind == verifier.POLICY:
        spec = {
            "failurePolicy": "Fail",
            "matchConstraints": {"matchPolicy": "Exact", "resourceRules": [{
                "apiGroups": ["networking.k8s.io"], "apiVersions": ["v1"],
                "operations": ["CREATE", "UPDATE"], "resources": ["ingresses"],
            }]},
            "matchConditions": [{"name": "route", "expression": "object.metadata.namespace == 'mnema-staging'"}],
            "validations": [{"expression": "object.spec.rules.size() == 1", "message": verifier.MESSAGES["host"]}],
        }
    else:
        spec = {"policyName": verifier.BOUNDARY, "validationActions": ["Deny"],
                "matchResources": {"namespaceSelector": {"matchLabels": {
                    "kubernetes.io/metadata.name": "mnema-staging", "mnema.app/environment": "staging",
                }}}}
    return {"apiVersion": verifier.API, "kind": kind,
            "metadata": {"name": verifier.BOUNDARY, "uid": kind + "-uid", "generation": 2,
                         "resourceVersion": "20"}, "spec": spec,
            **({"status": {"observedGeneration": 2, "typeChecking": {}}} if kind == verifier.POLICY else {})}


def denial(message: str, policy: str = verifier.BOUNDARY, binding: str = verifier.BOUNDARY) -> str:
    return (f"Error from server (Forbidden): ValidatingAdmissionPolicy '{policy}' "
            f"with binding '{binding}' denied request: {message}\n")


class FakeKubectl:
    """Transport fake with independent admission outcomes for route fixtures."""
    def __init__(self):
        self.expected = {kind: boundary(kind) for kind in (verifier.POLICY, verifier.BINDING)}
        self.installed = deepcopy(self.expected)
        self.labels = {"kubernetes.io/metadata.name": "mnema-staging", "mnema.app/environment": "staging"}
        self.calls = []
        self.boundary_reads = 0
        self.on_final_read = None

    def __call__(self, command, **kwargs):
        self.calls.append((command, kwargs))
        self.assert_safe(command, kwargs)
        if "create" in command:
            return subprocess.CompletedProcess(command, 0, "\n".join(json.dumps(item) for item in self.expected.values()), "")
        if "get" in command:
            resource = command[command.index("get") + 1]
            if "admissionregistration" in resource:
                self.boundary_reads += 1
                if self.boundary_reads == 3 and self.on_final_read:
                    self.on_final_read(self.installed)
                kind = verifier.BINDING if "bindings" in resource else verifier.POLICY
                return self.result(self.installed[kind])
            if resource == "namespace":
                return self.result({"metadata": {"labels": self.labels}})
            name = command[command.index("get") + 2]
            return self.result({"metadata": {"name": name, "namespace": "mnema-staging", "resourceVersion": "50"}})
        document = json.loads(kwargs["input"])
        reason = self.admission_reason(document)
        return self.result(document) if reason is None else subprocess.CompletedProcess(command, 1, "", denial(reason))

    @staticmethod
    def result(document):
        return subprocess.CompletedProcess([], 0, json.dumps(document), "")

    @staticmethod
    def assert_safe(command, kwargs):
        if "create" in command:
            assert "--dry-run=client" in command
        elif "replace" in command:
            assert "--dry-run=server" in command
            assert command[command.index("-f") + 1] == "-"
            assert json.loads(kwargs["input"])["kind"] == "Ingress"
        else:
            assert "get" in command
            assert command[command.index("get") + 1] in (
                "namespace", "ingress", "validatingadmissionpolicies.admissionregistration.k8s.io",
                "validatingadmissionpolicybindings.admissionregistration.k8s.io",
            )
        assert kwargs["timeout"] == 30
        assert kwargs["capture_output"]

    @staticmethod
    def admission_reason(document):
        annotations = document["metadata"]["annotations"]
        spec = document["spec"]
        name = document["metadata"]["name"]
        host = "staging.mnema.app" if name == "mnema" else "auth.staging.mnema.app"
        secret = "staging-mnema-app-tls" if name == "mnema" else "auth-staging-mnema-app-tls"
        if (set(annotations) - {"cert-manager.io/cluster-issuer", "kubectl.kubernetes.io/last-applied-configuration"}
                or annotations.get("cert-manager.io/cluster-issuer") != "letsencrypt-prod"):
            return verifier.MESSAGES["annotations"]
        if spec["ingressClassName"] != "traefik" or "defaultBackend" in spec:
            return verifier.MESSAGES["class"]
        if len(spec["rules"]) != 1 or spec["rules"][0]["host"] != host:
            return verifier.MESSAGES["host"]
        if spec["tls"] != [{"hosts": [host], "secretName": secret}]:
            return verifier.MESSAGES["tls"]
        signatures = []
        for path in spec["rules"][0]["http"]["paths"]:
            backend = path["backend"].get("service", {})
            signatures.append((path["path"], path["pathType"], backend.get("name"), backend.get("port", {}).get("number")))
        if name == "mnema":
            allowed = [
                [("/api", "Prefix", "mnema-learning", 80)],
                [("/", "Prefix", "mnema-frontend", 80), ("/api/user", "Prefix", "mnema-user", 80),
                 ("/api/core", "Prefix", "mnema-core", 80), ("/api/media", "Prefix", "mnema-media", 80),
                 ("/api/import", "Prefix", "mnema-import", 80)],
            ]
        else:
            allowed = [[("/api", "Prefix", "mnema-identity-account", 80)], [("/", "Prefix", "mnema-auth", 80)]]
        return None if signatures in allowed else verifier.MESSAGES["topology"]


class StagingRouteBoundaryTest(unittest.TestCase):
    def test_all_probes_only_read_or_dry_run_with_admin_context(self):
        fake = FakeKubectl()
        with patch.object(verifier.subprocess, "run", side_effect=fake):
            count = verifier.verify(verifier.Kubectl("owner-admin"))
        self.assertEqual(count, 44)
        replacements = [kwargs for command, kwargs in fake.calls if "replace" in command]
        self.assertEqual(len(replacements), 44)
        for command, _ in fake.calls:
            self.assertEqual(command[command.index("--context") + 1], "owner-admin")
        self.assertEqual(fake.boundary_reads, 4)

    def test_invalid_or_incomplete_local_policy_stream_fails_closed(self):
        for output in ("{}", "{}\n{}\n{}", "invalid", json.dumps(boundary(verifier.POLICY)) + "\ninvalid"):
            with self.subTest(output=output), patch.object(verifier.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, output, "")):
                with self.assertRaises(verifier.VerificationFailure):
                    verifier.expected_boundary(verifier.Kubectl())

    def test_missing_policy_or_binding_stops_before_probes(self):
        for unavailable in ("validatingadmissionpolicies", "validatingadmissionpolicybindings"):
            fake = FakeKubectl()
            def missing(command, **kwargs):
                if any(value.startswith(unavailable + ".") for value in command):
                    return subprocess.CompletedProcess(command, 1, "", "NotFound sensitive response")
                return fake(command, **kwargs)
            with self.subTest(unavailable=unavailable), patch.object(verifier.subprocess, "run", side_effect=missing):
                with self.assertRaisesRegex(verifier.VerificationFailure, "kubectl read failed"):
                    verifier.verify(verifier.Kubectl())
            self.assertFalse(any("replace" in command for command, _ in fake.calls))

    def test_stale_missing_and_failed_type_checking_are_rejected(self):
        statuses = [{}, {"observedGeneration": 1, "typeChecking": {}},
                    {"observedGeneration": 2}, {"observedGeneration": 2, "typeChecking": None},
                    {"observedGeneration": 2, "typeChecking": {"expressionWarnings": [{"warning": "bad field"}]}}]
        for status in statuses:
            with self.subTest(status=status):
                installed = boundary(verifier.POLICY)
                installed["status"] = status
                with self.assertRaises(verifier.VerificationFailure):
                    verifier.validate_boundary(installed, boundary(verifier.POLICY))

    def test_weakened_policy_or_binding_specs_are_rejected(self):
        changes = [
            (verifier.POLICY, lambda spec: spec.update(failurePolicy="Ignore")),
            (verifier.POLICY, lambda spec: spec["validations"][0].update(expression="true")),
            (verifier.POLICY, lambda spec: spec["matchConditions"][0].update(expression="false")),
            (verifier.POLICY, lambda spec: spec["matchConstraints"].update(objectSelector={"matchLabels": {"bypass": "true"}})),
            (verifier.POLICY, lambda spec: spec["matchConstraints"]["resourceRules"][0].update(operations=["CREATE"])),
            (verifier.BINDING, lambda spec: spec.update(validationActions=["Audit"])),
            (verifier.BINDING, lambda spec: spec.update(policyName="other-policy")),
            (verifier.BINDING, lambda spec: spec["matchResources"]["namespaceSelector"]["matchLabels"].update({"mnema.app/environment": "production"})),
        ]
        for kind, mutate in changes:
            with self.subTest(kind=kind, mutate=mutate):
                installed = boundary(kind)
                mutate(installed["spec"])
                with self.assertRaisesRegex(verifier.VerificationFailure, "spec differs"):
                    verifier.validate_boundary(installed, boundary(kind))

    def test_only_documented_server_defaults_are_normalized(self):
        for kind in (verifier.POLICY, verifier.BINDING):
            expected = boundary(kind)
            installed = deepcopy(expected)
            installed["spec"] = verifier.canonical_spec(expected)
            verifier.validate_boundary(installed, expected)

    def test_binding_must_remain_the_same_generation_and_identity(self):
        for field, value in (("generation", 3), ("uid", "replacement-uid")):
            fake = FakeKubectl()
            fake.on_final_read = lambda installed: installed[verifier.BINDING]["metadata"].update({field: value})
            with self.subTest(field=field), patch.object(verifier.subprocess, "run", side_effect=fake):
                with self.assertRaisesRegex(verifier.VerificationFailure, "changed during probes"):
                    verifier.verify(verifier.Kubectl())

    def test_namespace_label_drift_stops_before_probes(self):
        fake = FakeKubectl()
        fake.labels.pop("mnema.app/environment")
        with patch.object(verifier.subprocess, "run", side_effect=fake):
            with self.assertRaisesRegex(verifier.VerificationFailure, "namespace labels"):
                verifier.verify(verifier.Kubectl())
        self.assertFalse(any("replace" in command for command, _ in fake.calls))

    def test_wrong_rejection_reason_never_passes(self):
        probe = next(probe for probe in verifier.probes("mnema", "50") if probe.name.endswith("/wrong-backend"))
        wrong_errors = [
            "Forbidden: User cannot update ingresses", "connection refused",
            denial(probe.rejection, policy="another-policy"),
            denial(probe.rejection, binding="another-binding"),
            denial("expression evaluation failed: no such field"),
            denial(verifier.MESSAGES["tls"]),
        ]
        for error in wrong_errors:
            with self.subTest(error=error), patch.object(verifier.subprocess, "run", return_value=subprocess.CompletedProcess([], 1, "", error)):
                with self.assertRaisesRegex(verifier.VerificationFailure, "did not prove"):
                    verifier.run_probe(verifier.Kubectl(), probe)

    def test_admitted_forbidden_route_fails(self):
        probe = verifier.probes("mnema", "50")[2]
        with patch.object(verifier.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, "{}", "")):
            with self.assertRaisesRegex(verifier.VerificationFailure, "forbidden route was accepted"):
                verifier.run_probe(verifier.Kubectl(), probe)

    def test_denied_allowed_route_fails(self):
        probe = verifier.probes("mnema", "50")[0]
        with patch.object(verifier.subprocess, "run", return_value=subprocess.CompletedProcess([], 1, "", denial(verifier.MESSAGES["topology"]))):
            with self.assertRaisesRegex(verifier.VerificationFailure, "allowed route was rejected"):
                verifier.run_probe(verifier.Kubectl(), probe)

    def test_timeout_and_raw_server_output_are_not_printed(self):
        for result in (subprocess.TimeoutExpired("kubectl sensitive-data", 30),
                       subprocess.CompletedProcess([], 1, "sensitive-output", "sensitive-error")):
            with self.subTest(result=result), patch.object(verifier.subprocess, "run", side_effect=[result]), patch("sys.stderr", new_callable=io.StringIO) as stderr:
                self.assertEqual(verifier.main([]), 1)
                self.assertNotIn("sensitive", stderr.getvalue())
                self.assertIn("FAIL:", stderr.getvalue())

    def test_incomplete_probe_remains_schema_valid_and_fixtures_are_independent(self):
        probes = {probe.name: probe for probe in verifier.probes("mnema", "50")}
        incomplete = probes["mnema/incomplete-legacy"].document["spec"]["rules"][0]["http"]["paths"]
        self.assertEqual(len(incomplete), 4)
        legacy = probes["mnema/legacy"].document["spec"]["rules"][0]["http"]["paths"]
        self.assertEqual(len(legacy), 5)
        replacement = probes["mnema/replacement"].document
        self.assertEqual(replacement["spec"]["rules"][0]["host"], "staging.mnema.app")
        self.assertEqual(len(replacement["spec"]["rules"][0]["http"]["paths"]), 1)


if __name__ == "__main__":
    unittest.main()
