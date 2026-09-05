#!/usr/bin/env python3
"""Prove the owner-installed staging route boundary using reads and dry-runs only.

Run with the owner's admin kubectl context before granting application route access.
The checked-in policy is the source of truth; kubectl parses its YAML locally so
this verifier needs only Python's standard library. No Secret object is requested.
"""
from __future__ import annotations

import argparse
from copy import deepcopy
from dataclasses import dataclass
import json
from pathlib import Path
import re
import subprocess
import sys
from typing import Any

NAMESPACE = "mnema-staging"
BOUNDARY = "mnema-staging-application-route-boundary"
API = "admissionregistration.k8s.io/v1"
POLICY = "ValidatingAdmissionPolicy"
BINDING = "ValidatingAdmissionPolicyBinding"
MANIFEST = Path(__file__).resolve().parents[1] / "k8s/staging/application-route-boundary.yaml"
MESSAGES = {
    "annotations": "Application route annotations are restricted to the fixed issuer and inert apply metadata",
    "class": "Application routes must use the fixed Traefik class without a default backend",
    "host": "Application routes must retain their single exact staging host",
    "tls": "Application TLS hosts and Secrets are fixed",
    "topology": "Application paths and backends must match one complete allowed topology",
}


class VerificationFailure(Exception):
    """A safe diagnostic, deliberately excluding raw kubectl output."""


class Kubectl:
    def __init__(self, context: str | None = None):
        self.command = ["kubectl", "--request-timeout=20s"]
        if context:
            self.command.extend(["--context", context])

    def run(self, arguments: list[str], document: dict | None = None) -> subprocess.CompletedProcess:
        try:
            return subprocess.run(
                self.command + arguments, input=json.dumps(document) if document else None,
                text=True, capture_output=True, check=False, timeout=30,
            )
        except (OSError, subprocess.TimeoutExpired) as error:
            raise VerificationFailure("kubectl unavailable or timed out; check the admin context") from error

    def read_json(self, arguments: list[str], purpose: str) -> dict:
        result = self.run(arguments)
        if result.returncode:
            raise VerificationFailure(f"{purpose}: kubectl read failed; check resource existence and admin access")
        try:
            document = json.loads(result.stdout)
        except (ValueError, TypeError) as error:
            raise VerificationFailure(f"{purpose}: kubectl returned invalid JSON") from error
        if not isinstance(document, dict):
            raise VerificationFailure(f"{purpose}: kubectl returned a non-object")
        return document

    def boundary(self, kind: str) -> dict:
        resource = "validatingadmissionpolicies" if kind == POLICY else "validatingadmissionpolicybindings"
        return self.read_json(["get", f"{resource}.admissionregistration.k8s.io", BOUNDARY, "-o", "json"], kind)


def canonical_spec(document: dict) -> dict:
    """Apply only documented Kubernetes admission API defaults before comparison."""
    spec = deepcopy(document["spec"])
    if document["kind"] == POLICY:
        spec.setdefault("failurePolicy", "Fail")
        matches = spec.get("matchConstraints")
    else:
        matches = spec.get("matchResources")
    if isinstance(matches, dict):
        matches.setdefault("matchPolicy", "Equivalent")
        matches.setdefault("namespaceSelector", {})
        matches.setdefault("objectSelector", {})
        for key in ("resourceRules", "excludeResourceRules"):
            for rule in matches.get(key, []):
                rule.setdefault("scope", "*")
    return spec


def expected_boundary(kubectl: Kubectl) -> dict[str, dict]:
    result = kubectl.run([
        "create", "--dry-run=client", "--validate=false", "-f", str(MANIFEST), "-o", "json",
    ])
    if result.returncode:
        raise VerificationFailure("repository boundary parsing failed; check manifest and admin context")
    # kubectl create prints each document separately, not a Kubernetes List.
    decoder = json.JSONDecoder()
    remaining = result.stdout.strip()
    items = []
    try:
        while remaining:
            item, end = decoder.raw_decode(remaining)
            items.append(item)
            remaining = remaining[end:].lstrip()
    except ValueError as error:
        raise VerificationFailure("repository boundary parsing returned invalid JSON") from error
    if len(items) != 2:
        raise VerificationFailure("repository boundary must contain exactly one policy and binding")
    expected = {}
    for item in items:
        if (not isinstance(item, dict) or item.get("apiVersion") != API
                or item.get("kind") not in (POLICY, BINDING)
                or item.get("metadata", {}).get("name") != BOUNDARY
                or not isinstance(item.get("spec"), dict)):
            raise VerificationFailure("repository boundary identity is invalid")
        expected[item["kind"]] = item
    if set(expected) != {POLICY, BINDING}:
        raise VerificationFailure("repository boundary must contain exactly one policy and binding")
    if (expected[POLICY]["spec"].get("failurePolicy") != "Fail"
            or expected[BINDING]["spec"].get("validationActions") != ["Deny"]
            or expected[BINDING]["spec"].get("policyName") != BOUNDARY):
        raise VerificationFailure("repository boundary must fail closed with Deny")
    return expected


def validate_boundary(installed: dict, expected: dict) -> None:
    kind = expected["kind"]
    metadata = installed.get("metadata", {})
    if (installed.get("kind") != kind or installed.get("apiVersion") != API
            or metadata.get("name") != BOUNDARY or metadata.get("deletionTimestamp")
            or not isinstance(metadata.get("generation"), int) or metadata["generation"] < 1
            or not metadata.get("uid") or not metadata.get("resourceVersion")
            or not isinstance(installed.get("spec"), dict)):
        raise VerificationFailure(f"{kind}: installed identity/generation is invalid")
    if canonical_spec(installed) != canonical_spec(expected):
        raise VerificationFailure(f"{kind}: installed spec differs from the repository boundary")
    if kind == POLICY:
        status = installed.get("status", {})
        if status.get("observedGeneration") != metadata["generation"]:
            raise VerificationFailure("policy type checking has not observed its current generation; rerun after reconciliation")
        if not isinstance(status.get("typeChecking"), dict):
            raise VerificationFailure("policy type checking is incomplete")
        if status["typeChecking"].get("expressionWarnings"):
            raise VerificationFailure("policy contains CEL type-checking warnings")


def route(name: str, topology: str, resource_version: str) -> dict:
    host = "staging.mnema.app" if name == "mnema" else "auth.staging.mnema.app"
    secret = "staging-mnema-app-tls" if name == "mnema" else "auth-staging-mnema-app-tls"
    if topology == "replacement":
        paths = [("/api", "learning")] if name == "mnema" else [("/", "identity-account")]
    elif name == "mnema-auth":
        paths = [("/", "auth")]
    else:
        paths = [("/", "frontend"), ("/api/user", "user"), ("/api/core", "core"),
                 ("/api/media", "media"), ("/api/import", "import")]
    return {
        "apiVersion": "networking.k8s.io/v1", "kind": "Ingress",
        "metadata": {"name": name, "namespace": NAMESPACE, "resourceVersion": resource_version,
                     "annotations": {"cert-manager.io/cluster-issuer": "letsencrypt-prod"}},
        "spec": {"ingressClassName": "traefik", "tls": [{"hosts": [host], "secretName": secret}],
                 "rules": [{"host": host, "http": {"paths": [
                     {"path": path, "pathType": "Prefix", "backend": {
                         "service": {"name": f"mnema-{service}", "port": {"number": 80}}}}
                     for path, service in paths
                 ]}}]},
    }


@dataclass(frozen=True)
class Probe:
    name: str
    document: dict
    rejection: str | None = None


def probes(name: str, resource_version: str) -> list[Probe]:
    replacement = route(name, "replacement", resource_version)
    legacy = route(name, "legacy", resource_version)
    result = [Probe(f"{name}/replacement", replacement), Probe(f"{name}/legacy", legacy)]

    def changed(label: str, category: str, path: tuple, value: Any, base: dict = replacement) -> None:
        document = deepcopy(base)
        target = document
        for key in path[:-1]:
            target = target[key]
        target[path[-1]] = value
        result.append(Probe(f"{name}/{label}", document, MESSAGES[category]))

    spec = ("spec",)
    rule = spec + ("rules", 0)
    tls = spec + ("tls", 0)
    paths = rule + ("http", "paths")
    first = paths + (0,)
    service = first + ("backend", "service")
    changed("production-host", "host", rule + ("host",), "mnema.app" if name == "mnema" else "auth.mnema.app")
    changed("extra-host", "host", spec + ("rules",), [*replacement["spec"]["rules"], {
        "host": "extra.staging.mnema.app", "http": deepcopy(replacement["spec"]["rules"][0]["http"])}])
    changed("tls-secret", "tls", tls + ("secretName",), "production-mnema-app-tls")
    changed("tls-host", "tls", tls + ("hosts",), ["mnema.app"])
    changed("extra-tls-host", "tls", tls + ("hosts",), [replacement["spec"]["rules"][0]["host"], "mnema.app"])
    changed("extra-tls-entry", "tls", spec + ("tls",), replacement["spec"]["tls"] * 2)
    changed("routing-annotation", "annotations", ("metadata", "annotations", "traefik.ingress.kubernetes.io/router.middlewares"), "production-admin@kubernetescrd")
    changed("legacy-class-annotation", "annotations", ("metadata", "annotations", "kubernetes.io/ingress.class"), "nginx")
    changed("issuer", "annotations", ("metadata", "annotations", "cert-manager.io/cluster-issuer"), "other-issuer")
    changed("class", "class", spec + ("ingressClassName",), "nginx")
    changed("default-backend", "class", spec + ("defaultBackend",), {"service": {"name": "mnema-learning", "port": {"number": 80}}})
    changed("wrong-backend", "topology", service + ("name",), "minio")
    changed("wrong-port", "topology", service + ("port",), {"number": 8080})
    changed("named-port", "topology", service + ("port",), {"name": "http"})
    changed("wrong-path", "topology", first + ("path",), "/admin")
    changed("wrong-path-type", "topology", first + ("pathType",), "Exact")
    changed("resource-backend", "topology", first + ("backend",), {"resource": {"apiGroup": "example.com", "kind": "StorageBucket", "name": "assets"}})
    legacy_paths = legacy["spec"]["rules"][0]["http"]["paths"]
    replacement_paths = replacement["spec"]["rules"][0]["http"]["paths"]
    changed("mixed-topology", "topology", paths, replacement_paths + legacy_paths)
    changed("duplicate-path", "topology", paths, replacement_paths * 2)
    if name == "mnema":
        # Keep probes schema-valid: an empty paths array can fail before admission.
        changed("incomplete-legacy", "topology", paths, legacy_paths[:-1], legacy)
        changed("extra-legacy-path", "topology", paths, legacy_paths + legacy_paths[:1], legacy)
    return result


def run_probe(kubectl: Kubectl, probe: Probe) -> None:
    result = kubectl.run(["replace", "--dry-run=server", "--validate=true", "-n", NAMESPACE,
                          "-f", "-", "-o", "json"], probe.document)
    if probe.rejection is None:
        if result.returncode:
            raise VerificationFailure(f"{probe.name}: allowed route was rejected; check live immutable fields and admission")
    else:
        marker = rf"ValidatingAdmissionPolicy ['\"]{re.escape(BOUNDARY)}['\"] with binding ['\"]{re.escape(BOUNDARY)}['\"] denied request:\s*"
        if result.returncode == 0:
            raise VerificationFailure(f"{probe.name}: forbidden route was accepted")
        if not re.search(marker + re.escape(probe.rejection), result.stderr):
            raise VerificationFailure(f"{probe.name}: rejection did not prove the expected route policy validation")


def verify(kubectl: Kubectl) -> int:
    expected = expected_boundary(kubectl)
    installed = {}
    for kind in (POLICY, BINDING):
        installed[kind] = kubectl.boundary(kind)
        validate_boundary(installed[kind], expected[kind])
    namespace = kubectl.read_json(["get", "namespace", NAMESPACE, "-o", "json"], "staging namespace")
    labels = namespace.get("metadata", {}).get("labels", {})
    if labels.get("kubernetes.io/metadata.name") != NAMESPACE or labels.get("mnema.app/environment") != "staging":
        raise VerificationFailure("staging namespace labels do not activate the policy binding")
    count = 0
    for name in ("mnema", "mnema-auth"):
        current = kubectl.read_json(["get", "ingress", name, "-n", NAMESPACE, "-o", "json"], f"Ingress/{name}")
        metadata = current.get("metadata", {})
        if (metadata.get("name") != name or metadata.get("namespace") != NAMESPACE
                or not metadata.get("resourceVersion") or metadata.get("deletionTimestamp")):
            raise VerificationFailure(f"Ingress/{name}: missing or invalid current resource version")
        for probe in probes(name, metadata["resourceVersion"]):
            run_probe(kubectl, probe)
            count += 1
    # Bindings have no observedGeneration/status API. Verify the same UID and
    # generation still exist after exercising their behavior through admission.
    for kind in (POLICY, BINDING):
        final = kubectl.boundary(kind)
        validate_boundary(final, expected[kind])
        if any(final["metadata"][field] != installed[kind]["metadata"][field] for field in ("uid", "generation")):
            raise VerificationFailure(f"{kind}: changed during probes; rerun against a stable boundary")
    return count


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--context", help="admin kubectl context (defaults to current context)")
    arguments = parser.parse_args(argv)
    try:
        count = verify(Kubectl(arguments.context))
    except (VerificationFailure, KeyError, TypeError, AttributeError) as error:
        diagnostic = str(error) if isinstance(error, VerificationFailure) else "malformed boundary response; verification stopped"
        print(f"FAIL: {diagnostic}", file=sys.stderr)
        return 1
    print(f"PASS: staging route boundary matches the repository; {count} server dry-run probes passed; no cluster changes")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
