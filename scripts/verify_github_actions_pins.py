#!/usr/bin/env python3
"""Reject mutable external GitHub Actions references in workflow files."""

from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass
from pathlib import Path


USES_LINE = re.compile(r"^\s*(?:-\s*)?uses:\s*(?P<value>.+?)\s*$")
USES_KEY = re.compile(
    r"(?:^\s*(?:-\s*)?|[{,]\s*)(?:uses|['\"]uses['\"])\s*:"
)
FULL_COMMIT_SHA = re.compile(r"[0-9a-f]{40}")
RELEASE_COMMENT = re.compile(r"v\d+\.\d+\.\d+")
WORKFLOW_SUFFIXES = {".yaml", ".yml"}


@dataclass(frozen=True)
class Finding:
    path: Path
    line: int
    message: str

    def render(self) -> str:
        return f"{self.path}:{self.line}: {self.message}"


def workflow_files(roots: list[Path]) -> list[Path]:
    files: set[Path] = set()
    for root in roots:
        if root.is_file() and root.suffix in WORKFLOW_SUFFIXES:
            files.add(root)
        elif root.is_dir():
            files.update(
                path
                for path in root.rglob("*")
                if path.is_file() and path.suffix in WORKFLOW_SUFFIXES
            )
    return sorted(files)


def validate_workflows(roots: list[Path]) -> list[Finding]:
    findings: list[Finding] = []
    pins_by_release: dict[tuple[str, str], tuple[str, Path, int]] = {}

    for path in workflow_files(roots):
        for line_number, line in enumerate(
            path.read_text(encoding="utf-8").splitlines(), start=1
        ):
            match = USES_LINE.match(line)
            if not match:
                if USES_KEY.search(line):
                    findings.append(
                        Finding(
                            path,
                            line_number,
                            "uses must use the canonical 'uses: owner/repository@SHA # vX.Y.Z' line form",
                        )
                    )
                continue

            value = match.group("value")
            reference, separator, comment = value.partition(" # ")
            reference = reference.strip().strip("'\"")

            if reference.startswith("./"):
                continue
            if reference.startswith("docker://"):
                findings.append(
                    Finding(
                        path,
                        line_number,
                        "container actions require a dedicated digest policy before use",
                    )
                )
                continue

            action, at, revision = reference.rpartition("@")
            if not at or len(action.split("/")) < 2:
                findings.append(
                    Finding(path, line_number, f"unsupported external uses reference: {reference}")
                )
                continue
            if not FULL_COMMIT_SHA.fullmatch(revision):
                findings.append(
                    Finding(
                        path,
                        line_number,
                        f"{action} must use a full 40-character commit SHA",
                    )
                )
                continue
            if not separator or not RELEASE_COMMENT.fullmatch(comment.strip()):
                findings.append(
                    Finding(
                        path,
                        line_number,
                        f"{action}@{revision} must have an adjacent '# vX.Y.Z' release comment",
                    )
                )
                continue

            version = comment.strip()
            key = (action, version)
            previous = pins_by_release.get(key)
            if previous and previous[0] != revision:
                findings.append(
                    Finding(
                        path,
                        line_number,
                        (
                            f"{action} {version} conflicts with {previous[0]} pinned at "
                            f"{previous[1]}:{previous[2]}"
                        ),
                    )
                )
            else:
                pins_by_release[key] = (revision, path, line_number)

    return findings


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Verify immutable external GitHub Actions references."
    )
    parser.add_argument(
        "roots",
        nargs="*",
        type=Path,
        default=[Path(".github/workflows")],
        help="Workflow file or directory (default: .github/workflows)",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    files = workflow_files(args.roots)
    if not files:
        print("No workflow files found", file=sys.stderr)
        return 2

    findings = validate_workflows(args.roots)
    if findings:
        for finding in findings:
            print(finding.render(), file=sys.stderr)
        return 1

    print(f"Verified immutable action pins in {len(files)} workflow files")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
