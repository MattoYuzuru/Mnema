#!/usr/bin/env python3
"""Verify Mnema's low-noise dependency and security automation contract."""

from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass
from pathlib import Path


DEPENDENCY_REVIEW_PIN = "a1d282b36b6f3519aa1f3fc636f609c47dddb294"
DEPENDENCY_REVIEW_VERSION = "v5.0.0"
DEPENDENCY_REVIEW_INPUTS = {
    "comment-summary-in-pr": '"never"',
    "fail-on-scopes": '"runtime, development, unknown"',
    "fail-on-severity": '"high"',
    "license-check": '"false"',
    "show-openssf-scorecard": '"false"',
    "show-patched-versions": '"true"',
    "vulnerability-check": '"true"',
}
DOCKER_DIRECTORIES = {
    "/backend",
    "/frontend",
    "/k8s",
    "/k8s/ai",
    "/k8s/backup",
    "/k8s/observability",
    "/k8s/staging",
}
UPDATE_START = re.compile(r'^  - package-ecosystem: "(?P<ecosystem>[^"]+)"$')


@dataclass(frozen=True)
class Finding:
    path: Path
    message: str

    def render(self) -> str:
        return f"{self.path}: {self.message}"


def _update_blocks(content: str) -> dict[str, str]:
    lines = content.splitlines()
    starts = [index for index, line in enumerate(lines) if UPDATE_START.fullmatch(line)]
    blocks: dict[str, str] = {}
    for position, start in enumerate(starts):
        end = starts[position + 1] if position + 1 < len(starts) else len(lines)
        match = UPDATE_START.fullmatch(lines[start])
        assert match is not None
        ecosystem = match.group("ecosystem")
        if ecosystem in blocks:
            blocks[ecosystem] = ""
        else:
            blocks[ecosystem] = "\n".join(lines[start:end])
    return blocks


def _scalar(block: str, key: str) -> str | None:
    match = re.search(rf'^    {re.escape(key)}: (?P<value>.+)$', block, re.MULTILINE)
    if not match:
        return None
    return match.group("value").strip().strip('"')


def _list(block: str, key: str) -> set[str]:
    lines = block.splitlines()
    marker = f"    {key}:"
    try:
        start = lines.index(marker) + 1
    except ValueError:
        return set()

    values: set[str] = set()
    for line in lines[start:]:
        if line.startswith("      - "):
            values.add(line.removeprefix("      - ").strip().strip('"'))
        elif line and not line.startswith("      "):
            break
    return values


def validate_dependabot(path: Path) -> list[Finding]:
    if not path.is_file():
        return [Finding(path, "missing Dependabot configuration")]

    content = path.read_text(encoding="utf-8")
    findings: list[Finding] = []
    if not content.startswith("version: 2\n"):
        findings.append(Finding(path, "configuration must use Dependabot schema version 2"))

    blocks = _update_blocks(content)
    expected = {"github-actions", "gradle", "docker", "npm"}
    if set(blocks) != expected:
        findings.append(
            Finding(path, f"ecosystems must be exactly {sorted(expected)}, got {sorted(blocks)}")
        )
        return findings

    expected_directories = {
        "github-actions": {"/"},
        "gradle": {"/backend"},
        "docker": DOCKER_DIRECTORIES,
        "npm": {"/frontend"},
    }
    expected_limits = {"github-actions": "2", "gradle": "2", "docker": "2", "npm": "0"}

    for ecosystem, block in blocks.items():
        if block.count("    open-pull-requests-limit: ") != 1:
            findings.append(
                Finding(path, f"{ecosystem} must declare one open pull request limit")
            )
        directories = _list(block, "directories")
        if not directories:
            directory = _scalar(block, "directory")
            directories = {directory} if directory else set()
        if directories != expected_directories[ecosystem]:
            findings.append(
                Finding(
                    path,
                    f"{ecosystem} directories must be {sorted(expected_directories[ecosystem])}",
                )
            )
        if '      interval: "weekly"' not in block:
            findings.append(Finding(path, f"{ecosystem} must use a weekly schedule"))
        if _scalar(block, "open-pull-requests-limit") != expected_limits[ecosystem]:
            findings.append(
                Finding(path, f"{ecosystem} open pull request limit must be {expected_limits[ecosystem]}")
            )
        if "    groups:\n" not in block:
            findings.append(Finding(path, f"{ecosystem} updates must be grouped"))

    for ecosystem in ("gradle", "docker"):
        block = blocks[ecosystem]
        if '          - "minor"' not in block or '          - "patch"' not in block:
            findings.append(Finding(path, f"{ecosystem} must group minor and patch updates"))
        if '          - "version-update:semver-major"' not in block:
            findings.append(Finding(path, f"{ecosystem} major version updates must stay excluded"))

    actions = blocks["github-actions"]
    for update_type in ("major", "minor", "patch"):
        if f'          - "{update_type}"' not in actions:
            findings.append(Finding(path, f"github-actions must group {update_type} updates"))

    security_groups = {
        "github-actions": "actions-security",
        "gradle": "gradle-security",
        "docker": "docker-security",
        "npm": "frontend-security",
    }
    for ecosystem, group in security_groups.items():
        expected_group = (
            f"      {group}:\n"
            '        applies-to: "security-updates"\n'
            "        patterns:\n"
            '          - "*"'
        )
        if expected_group not in blocks[ecosystem]:
            findings.append(Finding(path, f"{ecosystem} must retain its catch-all security group"))

    return findings


def validate_dependency_review(path: Path) -> list[Finding]:
    if not path.is_file():
        return [Finding(path, "missing dependency review workflow")]

    content = path.read_text(encoding="utf-8")
    findings: list[Finding] = []
    expected_trigger = "on:\n  pull_request:\n    branches:\n      - main\n\nconcurrency:"
    if content.count(expected_trigger) != 1:
        findings.append(Finding(path, "workflow trigger must be only pull_request to main"))

    if content.count("\npermissions: {}\n") != 1:
        findings.append(Finding(path, "workflow must declare deny-by-default permissions once"))
    expected_job_permissions = "\n    permissions:\n      contents: read\n    steps:\n"
    if content.count(expected_job_permissions) != 1 or content.count("permissions:") != 2:
        findings.append(Finding(path, "dependency review job permissions must be exactly contents: read"))

    expected_action = (
        "        uses: actions/dependency-review-action@"
        f"{DEPENDENCY_REVIEW_PIN} # {DEPENDENCY_REVIEW_VERSION}"
    )
    if expected_action not in content:
        findings.append(Finding(path, "dependency review Action must use the approved immutable release pin"))
    else:
        action_lines = content[content.index(expected_action) :].splitlines()
        step_end = next(
            (index for index, line in enumerate(action_lines[1:], start=1) if line.startswith("      - ")),
            len(action_lines),
        )
        step_keys = [
            match.group("key")
            for line in action_lines[:step_end]
            if (match := re.fullmatch(r"        (?P<key>[a-z][a-z-]*):.*", line))
        ]
        if step_keys != ["uses", "with"]:
            findings.append(
                Finding(path, "dependency review step must contain only the approved uses and with keys")
            )
        if len(action_lines) < 2 or action_lines[1] != "        with:":
            findings.append(Finding(path, "dependency review Action must declare an explicit input contract"))
        else:
            inputs: dict[str, str] = {}
            for line in action_lines[2:]:
                if not line.startswith("          "):
                    break
                key, separator, value = line.strip().partition(": ")
                if not separator or key in inputs:
                    inputs = {}
                    break
                inputs[key] = value
            if inputs != DEPENDENCY_REVIEW_INPUTS:
                findings.append(
                    Finding(path, "dependency review Action inputs must match the fail-closed contract")
                )
    return findings


def validate_triage_contract(path: Path) -> list[Finding]:
    if not path.is_file():
        return [Finding(path, "missing security triage contract")]

    content = path.read_text(encoding="utf-8")
    findings: list[Finding] = []
    for heading in ("## Baseline", "## New regression", "## Temporary exception"):
        if heading not in content:
            findings.append(Finding(path, f"missing {heading} section"))
    for field in ("Owner", "Rationale", "Expiry", "Compensating controls", "Follow-up"):
        if f"- `{field}`:" not in content:
            findings.append(Finding(path, f"temporary exception schema requires {field}"))
    return findings


def validate_repository(repository_root: Path) -> list[Finding]:
    return [
        *validate_dependabot(repository_root / ".github" / "dependabot.yml"),
        *validate_dependency_review(
            repository_root / ".github" / "workflows" / "dependency-review.yaml"
        ),
        *validate_triage_contract(repository_root / "docs" / "operations" / "security-triage.md"),
    ]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository-root", type=Path, default=Path("."))
    return parser.parse_args()


def main() -> int:
    findings = validate_repository(parse_args().repository_root.resolve())
    if findings:
        for finding in findings:
            print(finding.render(), file=sys.stderr)
        return 1
    print("Verified low-noise security automation policy")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
