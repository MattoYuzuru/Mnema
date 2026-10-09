#!/usr/bin/env python3
"""Verify that every build and production image is immutable and inventoried."""

from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass
from pathlib import Path


REQUIRED_DOCKER_DIRECTORIES = {"/backend", "/frontend", "/deploy/production"}
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
    if match.group("tag") in {"latest"}:
        return [Finding(path, f"{context} uses a mutable tag: {image}")]
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


MEDIA_WORKER_BASE_ARG = re.compile(r"^ARG UBUNTU_BASE=(?P<image>\S+)\s*$", re.MULTILINE)


def media_worker_base(content: str) -> str | None:
    matches = MEDIA_WORKER_BASE_ARG.findall(content)
    return matches[0] if len(matches) == 1 else None


def validate_media_worker_dockerfile(path: Path) -> list[Finding]:
    """The worker image takes its base through one build argument; keep that argument pinned, every package that decides
    what runs pinned exactly, and the image non-root."""
    content, findings = _read(path)
    if content is None:
        return findings
    base = media_worker_base(content)
    if base is None:
        return [Finding(path, "media worker image must declare exactly one ARG UBUNTU_BASE base image")]
    findings.extend(_validate_pinned_image(path, base, "media worker base image"))
    from_lines = [line.strip() for line in content.splitlines() if FROM_INSTRUCTION.match(line)]
    if from_lines != ["FROM ${UBUNTU_BASE}"]:
        findings.append(Finding(path, "media worker image must have a single FROM ${UBUNTU_BASE} stage"))
    if "ffmpeg=${FFMPEG_VERSION}" not in content or not re.search(r"^ARG FFMPEG_VERSION=\S+$", content, re.MULTILINE):
        findings.append(Finding(path, "media worker FFmpeg packages must be pinned to an explicit version"))
    if "python3.12=${PYTHON312_VERSION}" not in content or "libpython3.12-stdlib=${PYTHON312_VERSION}" not in content:
        findings.append(Finding(path, "media worker Python interpreter and standard library must be pinned exactly"))
    if 'org.opencontainers.image.source="https://github.com/MattoYuzuru/Mnema"' not in content:
        findings.append(Finding(path, "media worker image must carry the OCI source label that links its private package to the repository"))
    lines = [line.strip() for line in content.splitlines()]
    if "USER 10002:10002" not in lines or not any(line.startswith('ENTRYPOINT ["python3"') for line in lines):
        findings.append(Finding(path, "media worker image must run as UID 10002 (Learning is 10001) with an exec-form Python entrypoint"))
    if any(line.startswith("COPY tests") for line in lines):
        findings.append(Finding(path, "media worker image must not contain the tests"))
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


def validate_vps_images(repository_root: Path) -> list[Finding]:
    path = repository_root / 'deploy/production/compose.yaml'
    content, findings = _read(path)
    if content is None:
        return findings
    images = _images(content)
    findings.extend(_validate_image_mapping_shape(path, content, images))
    placeholders = {f'${{MNEMA_{service}_IMAGE:?verified-candidate-required}}'
                    for service in ('FRONTEND', 'IDENTITY_ACCOUNT', 'LEARNING', 'POSTGRES')}
    if len(images) != 4 or any(images.count(value) != 1 for value in placeholders):
        findings.append(Finding(path, 'VPS Compose requires exactly four admitted image bindings (the media worker image is run by the media runner, not by Compose)'))
    if 'MNEMA_MEDIA_WORKER_IMAGE' in content or '\n  media-worker:' in content:
        findings.append(Finding(path, 'the media worker must not be a Compose service: per-job containers are started by the media runner'))
    findings.extend(validate_dockerfile(repository_root / 'deploy/production/Dockerfile'))
    findings.extend(validate_media_worker_dockerfile(repository_root / 'backend/media-worker/Dockerfile'))
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
    for dockerfile in (repository_root / "backend/Dockerfile", repository_root / "frontend/Dockerfile",
                       repository_root / 'deploy/production/Dockerfile'):
        dockerfile_content, _ = _read(dockerfile)
        if dockerfile_content is not None:
            source_images.update(
                match.group("image")
                for line in dockerfile_content.splitlines()
                if FROM_INSTRUCTION.match(line) and (match := FROM_LINE.fullmatch(line))
            )

    worker_content, _ = _read(repository_root / 'backend/media-worker/Dockerfile')
    worker_base = media_worker_base(worker_content) if worker_content is not None else None
    if worker_base is not None:
        source_images.add(worker_base)

    compose_content, _ = _read(repository_root / 'deploy/production/compose.yaml')
    if compose_content is not None:
        source_images.update(_images(compose_content))

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
        *validate_vps_images(repository_root),
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
