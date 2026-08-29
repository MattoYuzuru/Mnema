#!/usr/bin/env python3
"""Verify CI artifact inventory, upload boundary and least-privilege permissions."""

from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any


SAFE_UPLOAD_ACTION = "./.github/actions/safe-upload-artifact"
UPLOAD_ARTIFACT_PIN = "043fb46d1a93c77aae656e7c1c64a875d1fc6a0a"
UPLOAD_ARTIFACT_VERSION = "v7.0.1"
WORKFLOW_SUFFIXES = {".yaml", ".yml"}
REQUIRED_UPLOAD_INPUTS = {
    "policy-id",
    "name",
    "path",
    "retention-days",
    "if-no-files-found",
    "classification",
}
FORBIDDEN_PATH_PATTERNS = (
    re.compile(r"(^|/)\.env(?:\.|$)", re.IGNORECASE),
    re.compile(r"(^|/)kubeconfig(?:[-./]|$)", re.IGNORECASE),
    re.compile(r"(?:^|/)(?:id_rsa|id_ed25519|id_ecdsa|id_dsa)$", re.IGNORECASE),
    re.compile(r"(?:^|/)[^/]+\.(?:key|p12|pfx|pkcs12)$", re.IGNORECASE),
    re.compile(r"(?:staging|production)-rollback-application-secret\.json", re.IGNORECASE),
    re.compile(r"staging-application-secret\.ya?ml", re.IGNORECASE),
)
RUN_LOG_HAZARDS = (
    (re.compile(r"(?:^|[;&|])\s*set\s+(?:-x|-o\s+xtrace)\b", re.MULTILINE), "shell xtrace"),
    (re.compile(r"\bbash\s+-x\b"), "bash xtrace"),
    (re.compile(r"\bACTIONS_STEP_DEBUG\b"), "Actions debug output"),
    (re.compile(r"\bprintenv\b"), "complete environment dump"),
    (re.compile(r"(?m)^\s*(?:command\s+)?env(?:\s+-0)?\s*$"), "complete environment dump"),
    (re.compile(r"(?m)^\s*(?:export\s+-p|declare\s+-x)\s*$"), "exported environment dump"),
    (re.compile(r"\btoJSON\s*\(\s*secrets\s*\)", re.IGNORECASE), "secrets context dump"),
    (re.compile(r"\$\{\{\s*secrets\."), "direct secret interpolation in run script"),
)
NONCANONICAL_QUOTED_KEY = re.compile(
    r'''(?m)^\s*(?:-\s*)?["'][^"']+["']\s*:'''
)
SHORTHAND_STRUCTURAL_STEP = re.compile(
    r"(?m)^\s*-\s+(?:uses|run|with|permissions)\s*:"
)
NONCANONICAL_STRUCTURAL_SYNTAX = (
    re.compile(r"(?m)^\s*(?:-\s*)?(?:jobs|permissions|uses|run|with)\s+:"),
    re.compile(r"(?m)^\s*-\s*\{"),
    re.compile(r"(?m)^\s*(?:-\s*)?(?:<<\s*:|!!)"),
)
COMPOSITE_RUNS_CONTRACT = f'''runs:
  using: composite
  steps:
    - name: Verify resolved artifact boundary
      shell: bash
      env:
        MNEMA_ARTIFACT_POLICY_ID: ${{{{ inputs.policy-id }}}}
        MNEMA_ARTIFACT_PATHS: ${{{{ inputs.path }}}}
        MNEMA_ARTIFACT_IF_NO_FILES_FOUND: ${{{{ inputs.if-no-files-found }}}}
      run: python3 "$GITHUB_WORKSPACE/scripts/verify_artifact_contents.py"

    - name: Upload verified artifact
      id: upload
      uses: actions/upload-artifact@{UPLOAD_ARTIFACT_PIN} # {UPLOAD_ARTIFACT_VERSION}
      with:
        name: ${{{{ inputs.name }}}}
        path: ${{{{ inputs.path }}}}
        retention-days: ${{{{ inputs.retention-days }}}}
        if-no-files-found: ${{{{ inputs.if-no-files-found }}}}
        include-hidden-files: false
'''


@dataclass(frozen=True)
class Finding:
    path: Path
    message: str

    def render(self) -> str:
        return f"{self.path}: {self.message}"


def _workflow_files(repository_root: Path) -> dict[str, Path]:
    workflow_root = repository_root / ".github" / "workflows"
    return {
        path.relative_to(repository_root).as_posix(): path
        for path in sorted(workflow_root.iterdir())
        if path.is_file() and path.suffix in WORKFLOW_SUFFIXES
    }


def _job_blocks(content: str) -> dict[str, list[str]]:
    lines = content.splitlines()
    try:
        jobs_index = lines.index("jobs:")
    except ValueError:
        return {}

    starts: list[tuple[str, int]] = []
    for index, line in enumerate(lines[jobs_index + 1 :], start=jobs_index + 1):
        match = re.fullmatch(r"  (?P<job>[a-zA-Z0-9_-]+):", line)
        if match:
            starts.append((match.group("job"), index))

    blocks: dict[str, list[str]] = {}
    for position, (job, start) in enumerate(starts):
        end = starts[position + 1][1] if position + 1 < len(starts) else len(lines)
        blocks[job] = lines[start:end]
    return blocks


def _job_declaration_lines(content: str) -> list[str]:
    lines = content.splitlines()
    try:
        jobs_index = lines.index("jobs:")
    except ValueError:
        return []
    return [
        line
        for line in lines[jobs_index + 1 :]
        if line.startswith("  ") and not line.startswith("   ") and line.strip()
    ]


def _job_permissions(block: list[str]) -> dict[str, str] | None:
    try:
        start = block.index("    permissions:") + 1
    except ValueError:
        return None
    permissions: dict[str, str] = {}
    for line in block[start:]:
        match = re.fullmatch(r"      (?P<scope>[a-z-]+): (?P<access>read|write|none)", line)
        if not match:
            break
        permissions[match.group("scope")] = match.group("access")
    return permissions


def _parse_upload_inputs(block: list[str], uses_index: int) -> dict[str, Any] | None:
    if uses_index + 1 >= len(block) or block[uses_index + 1] != "        with:":
        return None
    inputs: dict[str, Any] = {}
    index = uses_index + 2
    while index < len(block):
        match = re.fullmatch(r"          (?P<key>[a-z-]+):(?: (?P<value>.*))?", block[index])
        if not match:
            break
        key = match.group("key")
        value = (match.group("value") or "").strip().strip('"\'')
        if key in inputs:
            return None
        if value == "|":
            values: list[str] = []
            index += 1
            while index < len(block) and block[index].startswith("            "):
                values.append(block[index].strip())
                index += 1
            inputs[key] = values
            continue
        inputs[key] = value
        index += 1
    return inputs


def _discovered_uploads(workflow: str, content: str) -> tuple[list[dict[str, Any]], list[str]]:
    uploads: list[dict[str, Any]] = []
    errors: list[str] = []
    for job, block in _job_blocks(content).items():
        for index, line in enumerate(block):
            if "actions/upload-artifact@" in line:
                errors.append(f"{job} bypasses the repository safe upload action")
            if line != f"        uses: {SAFE_UPLOAD_ACTION}":
                continue
            inputs = _parse_upload_inputs(block, index)
            if inputs is None or set(inputs) != REQUIRED_UPLOAD_INPUTS:
                errors.append(f"{job} safe upload inputs must be exactly {sorted(REQUIRED_UPLOAD_INPUTS)}")
                continue
            paths = inputs["path"] if isinstance(inputs["path"], list) else [inputs["path"]]
            try:
                retention_days = int(inputs["retention-days"])
            except ValueError:
                errors.append(f"{job} artifact retention-days must be a literal integer")
                continue
            uploads.append(
                {
                    "policyId": inputs["policy-id"],
                    "workflow": workflow,
                    "job": job,
                    "name": inputs["name"],
                    "paths": paths,
                    "retentionDays": retention_days,
                    "ifNoFilesFound": inputs["if-no-files-found"],
                    "classification": inputs["classification"],
                }
            )
    return uploads, errors


def _run_blocks(content: str) -> list[str]:
    lines = content.splitlines()
    blocks: list[str] = []
    index = 0
    while index < len(lines):
        scalar = re.fullmatch(r"        run: (?P<command>.+)", lines[index])
        if scalar and scalar.group("command") not in {"|", ">", "|-", ">-"}:
            blocks.append(scalar.group("command"))
            index += 1
            continue
        if lines[index] not in {"        run: |", "        run: >", "        run: |-", "        run: >-"}:
            index += 1
            continue
        start = index + 1
        index = start
        while index < len(lines) and (not lines[index] or lines[index].startswith("          ")):
            index += 1
        blocks.append("\n".join(lines[start:index]))
    return blocks


def _validate_workflow(
    relative: str,
    path: Path,
    expected_jobs: dict[str, dict[str, str]],
) -> tuple[list[Finding], list[dict[str, Any]]]:
    content = path.read_text(encoding="utf-8")
    findings: list[Finding] = []
    if content.count("\npermissions: {}\n") != 1:
        findings.append(Finding(path, "workflow must declare top-level permissions: {} exactly once"))
    if re.search(r"(?m)^permissions:\s+(?:read-all|write-all)\s*$", content):
        findings.append(Finding(path, "workflow cannot inherit read-all or write-all permissions"))
    if NONCANONICAL_QUOTED_KEY.search(content):
        findings.append(Finding(path, "quoted YAML mapping keys are outside the canonical policy syntax"))
    if SHORTHAND_STRUCTURAL_STEP.search(content):
        findings.append(Finding(path, "shorthand structural workflow steps are outside policy syntax"))
    if any(pattern.search(content) for pattern in NONCANONICAL_STRUCTURAL_SYNTAX):
        findings.append(Finding(path, "alternate YAML structural syntax is outside policy syntax"))

    job_declarations = _job_declaration_lines(content)
    if any(not re.fullmatch(r"  [a-zA-Z0-9_-]+:", line) for line in job_declarations):
        findings.append(Finding(path, "jobs must use canonical block mapping declarations"))
    declared_job_names = [line.strip().removesuffix(":") for line in job_declarations]
    if len(declared_job_names) != len(set(declared_job_names)):
        findings.append(Finding(path, "duplicate job declarations are forbidden"))

    jobs = _job_blocks(content)
    if set(jobs) != set(expected_jobs):
        findings.append(
            Finding(path, f"jobs must match the permission inventory: {sorted(expected_jobs)}")
        )
    for job, expected_permissions in expected_jobs.items():
        block = jobs.get(job)
        if block is None:
            continue
        actual_permissions = _job_permissions(block)
        if actual_permissions != expected_permissions:
            findings.append(
                Finding(path, f"{job} permissions must be exactly {expected_permissions}")
            )

    uploads, upload_errors = _discovered_uploads(relative, content)
    findings.extend(Finding(path, message) for message in upload_errors)
    for upload in uploads:
        for artifact_path in upload["paths"]:
            if any(pattern.search(artifact_path) for pattern in FORBIDDEN_PATH_PATTERNS):
                findings.append(
                    Finding(path, f"{upload['policyId']} inventories a known secret-bearing path")
                )

    for run_block in _run_blocks(content):
        for pattern, label in RUN_LOG_HAZARDS:
            if pattern.search(run_block):
                findings.append(Finding(path, f"run step contains forbidden {label}"))
    return findings, uploads


def _validate_composite_action(repository_root: Path) -> list[Finding]:
    action_path = repository_root / ".github" / "actions" / "safe-upload-artifact" / "action.yml"
    if not action_path.is_file():
        return [Finding(action_path, "missing safe upload composite action")]
    content = action_path.read_text(encoding="utf-8")
    findings: list[Finding] = []
    expected_upload = (
        f"uses: actions/upload-artifact@{UPLOAD_ARTIFACT_PIN} # {UPLOAD_ARTIFACT_VERSION}"
    )
    if content.count("actions/upload-artifact@") != 1 or content.count(expected_upload) != 1:
        findings.append(Finding(action_path, "upload-artifact must use the approved immutable pin"))
    runs_index = content.find("runs:\n")
    if runs_index < 0 or content[runs_index:] != COMPOSITE_RUNS_CONTRACT:
        findings.append(
            Finding(
                action_path,
                "composite runs contract must scan and upload the same exact inputs with no extra steps",
            )
        )
    for output in ("artifact-id", "artifact-url", "artifact-digest"):
        expected = f"value: ${{{{ steps.upload.outputs.{output} }}}}"
        if expected not in content:
            findings.append(Finding(action_path, f"composite action must preserve {output} output"))

    all_upload_references: list[Path] = []
    for yaml_path in (repository_root / ".github").rglob("*"):
        if yaml_path.is_file() and yaml_path.suffix in WORKFLOW_SUFFIXES:
            if "actions/upload-artifact@" in yaml_path.read_text(encoding="utf-8"):
                all_upload_references.append(yaml_path)
    if all_upload_references != [action_path]:
        findings.append(
            Finding(action_path, "upload-artifact may appear only once inside the safe upload action")
        )
    return findings


def validate_repository(repository_root: Path) -> list[Finding]:
    policy_path = repository_root / ".github" / "artifact-policy.json"
    if not policy_path.is_file():
        return [Finding(policy_path, "missing artifact policy inventory")]
    try:
        policy = json.loads(policy_path.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, OSError) as error:
        return [Finding(policy_path, f"invalid artifact policy: {error}")]

    findings: list[Finding] = []
    if policy.get("schemaVersion") != 1:
        findings.append(Finding(policy_path, "schemaVersion must be 1"))
    workflows = _workflow_files(repository_root)
    expected_workflows = policy.get("workflows", {})
    if set(workflows) != set(expected_workflows):
        findings.append(
            Finding(policy_path, "workflow permission inventory must cover every workflow exactly")
        )

    discovered_uploads: list[dict[str, Any]] = []
    for relative, path in workflows.items():
        expected_jobs = expected_workflows.get(relative, {}).get("jobs", {})
        workflow_findings, uploads = _validate_workflow(relative, path, expected_jobs)
        findings.extend(workflow_findings)
        discovered_uploads.extend(uploads)

    artifacts = policy.get("artifacts")
    if not isinstance(artifacts, list):
        findings.append(Finding(policy_path, "artifacts must be a list"))
        artifacts = []
    policy_ids = [entry.get("policyId") for entry in artifacts if isinstance(entry, dict)]
    if len(policy_ids) != len(set(policy_ids)) or any(not value for value in policy_ids):
        findings.append(Finding(policy_path, "artifact policy IDs must be unique and non-empty"))

    expected_uploads: list[dict[str, Any]] = []
    for entry in artifacts:
        if not isinstance(entry, dict) or set(entry) != {
            "policyId",
            "workflow",
            "job",
            "name",
            "paths",
            "retentionDays",
            "ifNoFilesFound",
            "classification",
            "source",
        }:
            findings.append(Finding(policy_path, "each artifact must use the exact inventory schema"))
            continue
        if not entry["source"] or not entry["classification"]:
            findings.append(Finding(policy_path, "artifact source and classification are required"))
        expected_uploads.append({key: value for key, value in entry.items() if key != "source"})

    sort_key = lambda entry: (entry.get("workflow", ""), entry.get("job", ""), entry.get("policyId", ""))
    if sorted(discovered_uploads, key=sort_key) != sorted(expected_uploads, key=sort_key):
        findings.append(
            Finding(policy_path, "artifact inventory must exactly match every safe upload step")
        )

    findings.extend(_validate_composite_action(repository_root))
    return findings


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository-root", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    findings = validate_repository(args.repository_root.resolve())
    if findings:
        for finding in findings:
            print(finding.render(), file=sys.stderr)
        return 1
    print("artifact_security_policy=ok")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
