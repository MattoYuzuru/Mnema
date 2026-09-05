#!/usr/bin/env python3
"""Verify that every build and production image is immutable and inventoried."""

from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass
from pathlib import Path


RELEASE_SERVICES = ("identity-account", "learning")
EXPECTED_PRODUCTION_APPLY_TARGETS = (
    "k8s/namespace.yaml",
    "k8s/observability/00-namespace.yaml",
    "-",
    "k8s/cluster-issuers.yaml",
    "k8s/postgres.yaml",
    "k8s/redis.yaml",
    "k8s/observability/",
    '"$RELEASE_MANIFEST"',
)
REQUIRED_DOCKER_DIRECTORIES = {"/backend", "/frontend", "/k8s", "/k8s/observability"}
PINNED_IMAGE = re.compile(
    r"^(?P<repository>[^\s@:]+(?:/[^\s@:]+)*):(?P<tag>[^\s@]+)"
    r"@sha256:(?P<digest>[0-9a-f]{64})$"
)
IMAGE_LINE = re.compile(
    r'^\s*(?:-\s*)?(?:image|"image"|\'image\')\s*:\s*(?P<image>\S+)\s*(?:#.*)?$'
)
IMAGE_KEY = re.compile(r'(?<![A-Za-z0-9_-])(?:image|"image"|\'image\')\s*:')
FROM_INSTRUCTION = re.compile(r"^\s*FROM\b", re.IGNORECASE)
FROM_LINE = re.compile(
    r"^\s*FROM(?:\s+--platform=\S+)?\s+(?P<image>\S+)"
    r"(?:\s+AS\s+(?P<alias>[A-Za-z0-9_.-]+))?\s*$",
    re.IGNORECASE,
)
APPLY_TARGET = re.compile(r"\bapply\s+-f\s+(?P<target>\S+)")
KUBECTL_APPLY = re.compile(r"\bkubectl\b[^\n]*\bapply\b")


@dataclass(frozen=True)
class Finding:
    path: Path
    message: str

    def render(self) -> str:
        return f"{self.path}: {self.message}"


def _read(path: Path) -> tuple[str | None, list[Finding]]:
    if not path.is_file():
        return None, [Finding(path, "required production image source is missing")]
    return path.read_text(encoding="utf-8"), []


def _validate_pinned_image(path: Path, image: str, context: str) -> list[Finding]:
    match = PINNED_IMAGE.fullmatch(image)
    if match is None:
        return [
            Finding(
                path,
                f"{context} must use a readable version tag and immutable sha256 digest: {image}",
            )
        ]
    if match.group("tag") in {"latest", "release-placeholder"}:
        return [Finding(path, f"{context} uses a mutable or placeholder tag: {image}")]
    return []


def validate_dockerfile(path: Path) -> list[Finding]:
    content, findings = _read(path)
    if content is None:
        return findings

    from_lines = [line for line in content.splitlines() if FROM_INSTRUCTION.match(line)]
    stages = [match for line in from_lines if (match := FROM_LINE.fullmatch(line))]
    if not from_lines:
        return [Finding(path, "Dockerfile must declare at least one FROM image")]
    if len(stages) != len(from_lines):
        findings.append(
            Finding(path, "every FROM instruction must use the supported, verifiable syntax")
        )
    aliases: set[str] = set()
    for stage in stages:
        image = stage.group("image")
        if image not in aliases:
            findings.extend(_validate_pinned_image(path, image, "Dockerfile FROM image"))
        alias = stage.group("alias")
        if alias:
            if alias in aliases:
                findings.append(Finding(path, f"Dockerfile stage alias is duplicated: {alias}"))
            aliases.add(alias)
    return findings


def _images(content: str) -> list[str]:
    return [match.group("image") for line in content.splitlines() if (match := IMAGE_LINE.fullmatch(line))]


def _validate_image_mapping_shape(path: Path, content: str, images: list[str]) -> list[Finding]:
    if len(IMAGE_KEY.findall(content)) != len(images):
        return [
            Finding(
                path,
                "every image key must use a verifiable one-line YAML scalar",
            )
        ]
    return []


def validate_static_manifests(repository_root: Path) -> list[Finding]:
    findings: list[Finding] = []
    fixed_manifests = (repository_root / "k8s/postgres.yaml", repository_root / "k8s/redis.yaml")
    observability_root = repository_root / "k8s/observability"
    if not observability_root.is_dir():
        findings.append(Finding(observability_root, "production observability directory is missing"))
        observability_manifests: tuple[Path, ...] = ()
    else:
        observability_manifests = tuple(sorted(observability_root.glob("*.y*ml")))

    for path in (*fixed_manifests, *observability_manifests):
        content, read_findings = _read(path)
        findings.extend(read_findings)
        if content is None:
            continue
        images = _images(content)
        findings.extend(_validate_image_mapping_shape(path, content, images))
        if path in fixed_manifests and len(images) != 1:
            findings.append(Finding(path, "production data manifest must declare exactly one image"))
        for image in images:
            findings.extend(_validate_pinned_image(path, image, "production image"))
    return findings


def validate_release_templates(repository_root: Path) -> list[Finding]:
    findings: list[Finding] = []
    fake_digest = "sha256:" + "0" * 64
    for service in RELEASE_SERVICES:
        path = repository_root / "k8s" / f"{service}-deploy.yaml"
        content, read_findings = _read(path)
        findings.extend(read_findings)
        if content is None:
            continue

        placeholder = f"ghcr.io/mattoyuzuru/mnema/{service}:release-placeholder"
        if content.count(placeholder) != 1:
            findings.append(
                Finding(path, f"must contain exactly one renderer-owned {service} image placeholder")
            )
        images = _images(content)
        findings.extend(_validate_image_mapping_shape(path, content, images))
        for image in images:
            if image == placeholder:
                rendered = f"ghcr.io/mattoyuzuru/mnema/{service}@{fake_digest}"
                if not re.fullmatch(r"[^\s@]+@sha256:[0-9a-f]{64}", rendered):
                    findings.append(Finding(path, "rendered application image is not digest pinned"))
            else:
                findings.extend(_validate_pinned_image(path, image, "release support image"))
    return findings


def validate_production_workflow(path: Path) -> list[Finding]:
    content, findings = _read(path)
    if content is None:
        return findings

    apply_commands = KUBECTL_APPLY.findall(content)
    target_matches = list(APPLY_TARGET.finditer(content))
    targets = tuple(match.group("target") for match in target_matches)
    if len(apply_commands) != len(target_matches):
        findings.append(Finding(path, "every kubectl apply command must use a classified -f target"))
    if targets != EXPECTED_PRODUCTION_APPLY_TARGETS:
        findings.append(
            Finding(
                path,
                "production apply surface must be explicitly classified; "
                f"expected {list(EXPECTED_PRODUCTION_APPLY_TARGETS)}, got {list(targets)}",
            )
        )
    return findings


def validate_renderer(path: Path) -> list[Finding]:
    content, findings = _read(path)
    if content is None:
        return findings

    expected_services = f'services="{" ".join(RELEASE_SERVICES)}"'
    if content.count(expected_services) != 1:
        findings.append(Finding(path, "release service set must match the verified production templates"))
    required_guard = "Rendered manifest must contain exactly two sha256-pinned release images"
    if content.count(required_guard) != 1:
        findings.append(Finding(path, "renderer must retain its final digest-only image guard"))
    return findings


def validate_dependabot(path: Path) -> list[Finding]:
    content, findings = _read(path)
    if content is None:
        return findings

    missing = sorted(
        directory
        for directory in REQUIRED_DOCKER_DIRECTORIES
        if content.count(f'      - "{directory}"') != 1
    )
    if missing:
        findings.append(Finding(path, f"Dependabot Docker coverage is missing {missing}"))
    return findings


def validate_inventory_document(repository_root: Path, path: Path) -> list[Finding]:
    content, findings = _read(path)
    if content is None:
        return findings

    source_images: set[str] = set()
    for dockerfile in (repository_root / "backend/Dockerfile", repository_root / "frontend/Dockerfile"):
        dockerfile_content, _ = _read(dockerfile)
        if dockerfile_content is not None:
            source_images.update(
                match.group("image")
                for line in dockerfile_content.splitlines()
                if FROM_INSTRUCTION.match(line) and (match := FROM_LINE.fullmatch(line))
            )

    manifest_paths = [repository_root / "k8s/postgres.yaml", repository_root / "k8s/redis.yaml"]
    manifest_paths.extend(sorted((repository_root / "k8s/observability").glob("*.y*ml")))
    manifest_paths.extend(
        repository_root / "k8s" / f"{service}-deploy.yaml" for service in RELEASE_SERVICES
    )
    for manifest in manifest_paths:
        manifest_content, _ = _read(manifest)
        if manifest_content is not None:
            source_images.update(
                image for image in _images(manifest_content) if "release-placeholder" not in image
            )

    for image in sorted(source_images):
        match = PINNED_IMAGE.fullmatch(image)
        if match is None:
            continue
        readable = image.rsplit("@", maxsplit=1)[0]
        digest = f"sha256:{match.group('digest')}"
        if f"`{readable}`" not in content or f"`{digest}`" not in content:
            findings.append(Finding(path, f"inventory is missing source image {image}"))

    for section in ("## Enforced surface", "## Intentional exclusions", "## Update and rollback"):
        if content.count(section) != 1:
            findings.append(Finding(path, f"inventory must contain exactly one {section} section"))
    return findings


def validate_repository(repository_root: Path) -> list[Finding]:
    return [
        *validate_dockerfile(repository_root / "backend/Dockerfile"),
        *validate_dockerfile(repository_root / "frontend/Dockerfile"),
        *validate_static_manifests(repository_root),
        *validate_release_templates(repository_root),
        *validate_production_workflow(
            repository_root / ".github/workflows/production-deploy.yaml"
        ),
        *validate_renderer(repository_root / "scripts/render-release-manifest.sh"),
        *validate_dependabot(repository_root / ".github/dependabot.yml"),
        *validate_inventory_document(
            repository_root,
            repository_root / "docs/operations/production-image-inventory.md",
        ),
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
    print("Verified immutable build and production image policy")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
