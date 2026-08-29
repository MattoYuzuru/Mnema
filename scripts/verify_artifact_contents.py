#!/usr/bin/env python3
"""Reject known secret-bearing files and values before a CI artifact upload."""

from __future__ import annotations

import glob
import json
import os
import re
import sys
from dataclasses import dataclass
from pathlib import Path


CHUNK_SIZE = 64 * 1024
OVERLAP_SIZE = 4096

PRIVATE_KEY_MARKER = re.compile(
    rb"-----BEGIN (?:ENCRYPTED |RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----",
    re.IGNORECASE,
)
AWS_ACCESS_KEY_MARKER = re.compile(rb"\b(?:AKIA|ASIA)[A-Z0-9]{16}\b")
JWT_MARKER = re.compile(
    rb"\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\b"
)
SYNTHETIC_CREDENTIAL_MARKER = re.compile(
    rb"MNEMA_DUMMY_CREDENTIAL_DO_NOT_UPLOAD\s*[:=]",
    re.IGNORECASE,
)
YAML_SECRET_KIND = re.compile(rb"(?im)^\s*kind\s*:\s*Secret\s*(?:#.*)?$")
YAML_SECRET_VALUES = re.compile(rb"(?im)^\s*(?:data|stringData)\s*:\s*(?:#.*)?$")
JSON_SECRET_KIND = re.compile(rb'"kind"\s*:\s*"Secret"', re.IGNORECASE)
JSON_SECRET_VALUES = re.compile(rb'"(?:data|stringData)"\s*:', re.IGNORECASE)
KUBECONFIG_CLUSTERS = re.compile(rb"(?im)^\s*clusters\s*:")
KUBECONFIG_USERS = re.compile(rb"(?im)^\s*users\s*:")
KUBECONFIG_CREDENTIAL = re.compile(
    rb"(?im)^\s*(?:client-key-data|token)\s*:\s*(?!<redacted>|\[redacted\]|redacted\s*$)\S+"
)
JSON_KUBECONFIG_CLUSTERS = re.compile(rb'"clusters"\s*:', re.IGNORECASE)
JSON_KUBECONFIG_USERS = re.compile(rb'"users"\s*:', re.IGNORECASE)
JSON_KUBECONFIG_CREDENTIAL = re.compile(
    rb'"(?:client-key-data|token)"\s*:\s*'
    rb'(?P<value>"(?:\\.|[^"\\])*"|[^,}\r\n]+)',
    re.IGNORECASE,
)
SENSITIVE_HEADER = re.compile(
    rb"(?im)^\s*(?:authorization|proxy-authorization|cookie|set-cookie)\s*[:=]\s*(?P<value>\S.*)$"
)
SENSITIVE_ASSIGNMENT = re.compile(
    rb"(?im)^\s*[\"']?(?:password|passwd|client[_-]secret|api[_-]key|access[_-]token|"
    rb"refresh[_-]token|aws_secret_access_key|private[_-]key)[\"']?\s*[:=]\s*"
    rb"(?P<value>\S.*)$"
)
JSON_SENSITIVE_ASSIGNMENT = re.compile(
    rb'"(?:password|passwd|client[_-]secret|api[_-]key|access[_-]token|refresh[_-]token|'
    rb'aws_secret_access_key|private[_-]key)"\s*:\s*'
    rb'(?P<value>"(?:\\.|[^"\\])*"|[^,}\r\n]+)',
    re.IGNORECASE,
)

KNOWN_SECRET_FILENAMES = {
    "application-default-credentials.json",
    ".netrc",
    ".npmrc",
    ".pypirc",
    "credentials",
    "credentials.json",
    "dockerconfigjson",
    "id_dsa",
    "id_ecdsa",
    "id_ed25519",
    "id_rsa",
    "production-rollback-application-secret.json",
    "service-account.json",
    "staging-application-secret.yaml",
    "staging-rollback-application-secret.json",
}
SENSITIVE_SUFFIXES = (".key", ".p12", ".pfx", ".pkcs12")


@dataclass(frozen=True)
class ArtifactViolation:
    path: Path
    reason: str


class ArtifactBoundaryError(RuntimeError):
    def __init__(self, violations: list[ArtifactViolation]):
        self.violations = violations
        super().__init__("artifact boundary rejected generated content")


def _is_within(path: Path, roots: tuple[Path, ...]) -> bool:
    return any(path == root or root in path.parents for root in roots)


def _filename_reason(path: Path) -> str | None:
    name = path.name.lower()
    components = tuple(component.lower() for component in path.parts)
    if name == ".env" or name.startswith(".env."):
        return "environment file names are forbidden"
    if any(re.match(r"^kubeconfig(?:[-.]|$)", component) for component in components):
        return "kubeconfig paths are forbidden"
    if name in KNOWN_SECRET_FILENAMES:
        return "known credential or secret snapshot path is forbidden"
    if name.startswith("service-account-") and name.endswith(".json"):
        return "service-account credential files are forbidden"
    if "credential" in name and name.endswith((".json", ".yaml", ".yml", ".toml")):
        return "credential files are forbidden"
    if ("private-key" in name or "private_key" in name) and name.endswith(
        (".pem", ".json", ".yaml", ".yml")
    ):
        return "private-key files are forbidden"
    if name.endswith(SENSITIVE_SUFFIXES):
        return "private-key or credential container files are forbidden"
    return None


def _is_redacted(value: bytes) -> bool:
    normalized = value.strip().rstrip(b",").strip().strip(b"\"'").lower()
    return normalized in {b"<redacted>", b"[redacted]", b"redacted", b"removed", b"masked"}


def _content_reason(path: Path) -> str | None:
    secret_kind = False
    secret_values = False
    kube_clusters = False
    kube_users = False
    kube_credential = False
    overlap = b""

    with path.open("rb") as artifact:
        while chunk := artifact.read(CHUNK_SIZE):
            sample = overlap + chunk
            if PRIVATE_KEY_MARKER.search(sample):
                return "private key material marker"
            if AWS_ACCESS_KEY_MARKER.search(sample):
                return "AWS access key marker"
            if JWT_MARKER.search(sample):
                return "JWT credential marker"
            if SYNTHETIC_CREDENTIAL_MARKER.search(sample):
                return "synthetic credential marker"
            for match in SENSITIVE_HEADER.finditer(sample):
                if not _is_redacted(match.group("value")):
                    return "unredacted authentication header or cookie marker"
            for match in SENSITIVE_ASSIGNMENT.finditer(sample):
                if not _is_redacted(match.group("value")):
                    return "unredacted credential assignment"
            for match in JSON_SENSITIVE_ASSIGNMENT.finditer(sample):
                if not _is_redacted(match.group("value")):
                    return "unredacted credential assignment"

            secret_kind = secret_kind or bool(
                YAML_SECRET_KIND.search(sample) or JSON_SECRET_KIND.search(sample)
            )
            secret_values = secret_values or bool(
                YAML_SECRET_VALUES.search(sample) or JSON_SECRET_VALUES.search(sample)
            )
            kube_clusters = kube_clusters or bool(
                KUBECONFIG_CLUSTERS.search(sample) or JSON_KUBECONFIG_CLUSTERS.search(sample)
            )
            kube_users = kube_users or bool(
                KUBECONFIG_USERS.search(sample) or JSON_KUBECONFIG_USERS.search(sample)
            )
            kube_credential = kube_credential or bool(KUBECONFIG_CREDENTIAL.search(sample))
            for match in JSON_KUBECONFIG_CREDENTIAL.finditer(sample):
                if not _is_redacted(match.group("value")):
                    kube_credential = True
            overlap = sample[-OVERLAP_SIZE:]

    if secret_kind and secret_values:
        return "raw Kubernetes Secret resource"
    if kube_clusters and kube_users and kube_credential:
        return "embedded kubeconfig credential"
    return None


def _expand_paths(
    path_specs: list[str],
    workspace: Path,
    runner_temp: Path,
) -> tuple[list[Path], list[ArtifactViolation]]:
    roots = (workspace.resolve(), runner_temp.resolve())
    files: set[Path] = set()
    violations: list[ArtifactViolation] = []

    for path_spec in path_specs:
        candidate = Path(path_spec)
        pattern = candidate if candidate.is_absolute() else workspace / candidate
        matches = [Path(match) for match in glob.glob(str(pattern), recursive=True)]
        for match in matches:
            if match.is_symlink():
                violations.append(ArtifactViolation(match, "symbolic links are forbidden"))
                continue
            resolved = match.resolve()
            if not _is_within(resolved, roots):
                violations.append(ArtifactViolation(match, "path escapes approved runner roots"))
                continue
            if resolved.is_dir():
                for descendant in resolved.rglob("*"):
                    if descendant.is_symlink():
                        violations.append(
                            ArtifactViolation(descendant, "symbolic links are forbidden")
                        )
                    elif descendant.is_file():
                        files.add(descendant.resolve())
            elif resolved.is_file():
                files.add(resolved)

    return sorted(files), violations


def verify_artifact_paths(
    path_specs: list[str],
    *,
    workspace: Path,
    runner_temp: Path,
    if_no_files_found: str = "error",
) -> list[Path]:
    """Return verified files or raise without exposing file contents."""
    files, violations = _expand_paths(path_specs, workspace, runner_temp)
    if not files and if_no_files_found == "error":
        violations.append(ArtifactViolation(workspace, "no files matched the inventoried paths"))

    for path in files:
        reason = _filename_reason(path) or _content_reason(path)
        if reason:
            violations.append(ArtifactViolation(path, reason))

    if violations:
        raise ArtifactBoundaryError(violations)
    return files


def _display_path(path: Path, workspace: Path, runner_temp: Path) -> str:
    for label, root in (("workspace", workspace), ("runner-temp", runner_temp)):
        try:
            return f"{label}/{path.resolve().relative_to(root.resolve())}"
        except ValueError:
            continue
    return path.name


def main() -> int:
    policy_id = os.environ.get("MNEMA_ARTIFACT_POLICY_ID", "")
    path_specs = [
        line.strip()
        for line in os.environ.get("MNEMA_ARTIFACT_PATHS", "").splitlines()
        if line.strip()
    ]
    if_no_files_found = os.environ.get("MNEMA_ARTIFACT_IF_NO_FILES_FOUND", "error")
    workspace = Path(os.environ.get("GITHUB_WORKSPACE", os.getcwd()))
    runner_temp = Path(os.environ.get("RUNNER_TEMP", workspace / ".runner-temp"))

    if not policy_id or not path_specs or if_no_files_found not in {"error", "warn", "ignore"}:
        print("artifact_boundary=invalid reason=input_contract", file=sys.stderr)
        return 2

    try:
        files = verify_artifact_paths(
            path_specs,
            workspace=workspace,
            runner_temp=runner_temp,
            if_no_files_found=if_no_files_found,
        )
    except ArtifactBoundaryError as error:
        for violation in error.violations:
            display_path = _display_path(violation.path, workspace, runner_temp)
            print(
                "artifact_boundary=blocked "
                f"policy_id={json.dumps(policy_id)} "
                f"path={json.dumps(display_path)} "
                f"reason={json.dumps(violation.reason)}",
                file=sys.stderr,
            )
        return 1

    print(f"artifact_boundary=ok policy_id={json.dumps(policy_id)} files={len(files)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
