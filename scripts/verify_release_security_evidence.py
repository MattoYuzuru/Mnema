#!/usr/bin/env python3
"""Create and verify fail-closed security evidence for immutable release images."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from collections import Counter
from datetime import UTC, date, datetime
from pathlib import Path
from typing import Any


SERVICES = ("frontend", "auth", "user", "core", "media", "import")
BLOCKING_SEVERITIES = {"HIGH", "CRITICAL"}
SEVERITIES = {"UNKNOWN", "LOW", "MEDIUM", "HIGH", "CRITICAL"}
MAX_EXCEPTION_DAYS = 30
SHA_PATTERN = re.compile(r"^[0-9a-f]{40}$")
DIGEST_PATTERN = re.compile(r"^sha256:[0-9a-f]{64}$")
IMAGE_PATTERN = re.compile(
    r"^ghcr\.io/(?P<repository>[a-z0-9_.-]+/[a-z0-9_.-]+)/"
    r"(?P<service>frontend|auth|user|core|media|import)@(?P<digest>sha256:[0-9a-f]{64})$"
)
OWNER_PATTERN = re.compile(r"^@[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?$")
FINDING_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:+-]{2,127}$")
PACKAGE_PATTERN = re.compile(r"^[^*?\[\]\s][^*?\[\]\r\n]{0,199}$")
IMAGE_LINE = re.compile(r'^\s*image:\s*["\']?(?P<image>[^\s"\']+)["\']?\s*$')
STEP_KEY = re.compile(
    r"^        (?:(?P<quote>['\"])(?P<quoted_key>[a-z][a-z-]*)(?P=quote)|"
    r"(?P<plain_key>[a-z][a-z-]*))\s*:"
)


class EvidenceFailure(RuntimeError):
    """A stable, non-sensitive release evidence failure."""


def load_json(path: Path) -> Any:
    try:
        with path.open(encoding="utf-8") as source:
            return json.load(source)
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise EvidenceFailure(f"invalid JSON file: {path}") from error


def write_json(path: Path, payload: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    try:
        with path.open("rb") as source:
            for chunk in iter(lambda: source.read(1024 * 1024), b""):
                digest.update(chunk)
    except OSError as error:
        raise EvidenceFailure(f"cannot read evidence file: {path}") from error
    return digest.hexdigest()


def validate_trivy_ignore(path: Path) -> str:
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeDecodeError) as error:
        raise EvidenceFailure("controlled Trivy ignore file is missing or unreadable") from error
    if any(line.strip() and not line.lstrip().startswith("#") for line in lines):
        raise EvidenceFailure("release Trivy ignore file must contain comments only")
    return file_sha256(path)


def parse_day(value: Any, field: str) -> date:
    if not isinstance(value, str):
        raise EvidenceFailure(f"{field} must be an ISO date")
    try:
        parsed = date.fromisoformat(value)
    except ValueError as error:
        raise EvidenceFailure(f"{field} must be an ISO date") from error
    if parsed.isoformat() != value:
        raise EvidenceFailure(f"{field} must use canonical YYYY-MM-DD form")
    return parsed


def parse_timestamp(value: Any, field: str) -> datetime:
    if not isinstance(value, str) or not value:
        raise EvidenceFailure(f"{field} must be an ISO timestamp")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as error:
        raise EvidenceFailure(f"{field} must be an ISO timestamp") from error
    if parsed.tzinfo is None:
        raise EvidenceFailure(f"{field} must include a timezone")
    return parsed.astimezone(UTC)


def require_nonempty(value: Any, field: str, *, maximum: int = 500) -> str:
    if not isinstance(value, str) or not value.strip() or value != value.strip():
        raise EvidenceFailure(f"{field} must be a trimmed non-empty string")
    if len(value) > maximum or "\n" in value or "\r" in value:
        raise EvidenceFailure(f"{field} is too long or multiline")
    return value


def validate_image(image: str, *, service: str | None = None, repository: str | None = None) -> re.Match[str]:
    match = IMAGE_PATTERN.fullmatch(image)
    if not match:
        raise EvidenceFailure("image must be an exact lowercase GHCR sha256 reference")
    if service is not None and match.group("service") != service:
        raise EvidenceFailure("image service does not match the evidence service")
    if repository is not None and match.group("repository") != repository.lower():
        raise EvidenceFailure("image repository does not match the expected repository")
    return match


def load_exceptions(path: Path, today: date) -> list[dict[str, Any]]:
    payload = load_json(path)
    if not isinstance(payload, dict) or set(payload) != {"schemaVersion", "exceptions"}:
        raise EvidenceFailure("exception file must contain only schemaVersion and exceptions")
    if payload["schemaVersion"] != 1 or not isinstance(payload["exceptions"], list):
        raise EvidenceFailure("unsupported exception schema")

    normalized: list[dict[str, Any]] = []
    identities: set[tuple[str, str, str]] = set()
    expected_fields = {
        "finding",
        "image",
        "packages",
        "rationale",
        "owner",
        "created",
        "expires",
    }
    for index, item in enumerate(payload["exceptions"]):
        prefix = f"exceptions[{index}]"
        if not isinstance(item, dict) or set(item) != expected_fields:
            raise EvidenceFailure(f"{prefix} has missing or unsupported fields")
        finding = require_nonempty(item["finding"], f"{prefix}.finding", maximum=128)
        if not FINDING_PATTERN.fullmatch(finding) or "*" in finding or "?" in finding:
            raise EvidenceFailure(f"{prefix}.finding is not an exact finding identifier")
        image = require_nonempty(item["image"], f"{prefix}.image", maximum=300)
        validate_image(image)
        packages = item["packages"]
        if not isinstance(packages, list) or not packages or len(packages) > 20:
            raise EvidenceFailure(f"{prefix}.packages must be a bounded non-empty list")
        if any(
            not isinstance(package, str) or not PACKAGE_PATTERN.fullmatch(package)
            for package in packages
        ):
            raise EvidenceFailure(f"{prefix}.packages must contain exact package names without wildcards")
        if len(set(packages)) != len(packages):
            raise EvidenceFailure(f"{prefix}.packages contains duplicates")
        rationale = require_nonempty(item["rationale"], f"{prefix}.rationale")
        if len(rationale) < 20:
            raise EvidenceFailure(f"{prefix}.rationale must explain the bounded risk")
        owner = require_nonempty(item["owner"], f"{prefix}.owner", maximum=40)
        if not OWNER_PATTERN.fullmatch(owner):
            raise EvidenceFailure(f"{prefix}.owner must be one GitHub handle")
        created = parse_day(item["created"], f"{prefix}.created")
        expires = parse_day(item["expires"], f"{prefix}.expires")
        if created > today:
            raise EvidenceFailure(f"{prefix} is not active yet")
        if expires < today:
            raise EvidenceFailure(f"{prefix} has expired")
        if expires < created or (expires - created).days > MAX_EXCEPTION_DAYS:
            raise EvidenceFailure(f"{prefix} exceeds the {MAX_EXCEPTION_DAYS}-day lifetime")
        for package in packages:
            identity = (image, finding, package)
            if identity in identities:
                raise EvidenceFailure(f"{prefix} duplicates an existing exception scope")
            identities.add(identity)
        normalized.append(
            {
                "finding": finding,
                "image": image,
                "packages": sorted(packages),
                "rationale": rationale,
                "owner": owner,
                "created": created.isoformat(),
                "expires": expires.isoformat(),
            }
        )
    return normalized


def validate_attestation(path: Path, image: str, predicate_type: str) -> None:
    payload = load_json(path)
    if not isinstance(payload, list) or not payload:
        raise EvidenceFailure(f"{path.name} has no verified attestations")
    image_name, digest = image.rsplit("@", 1)
    digest_hex = digest.removeprefix("sha256:")
    for item in payload:
        if not isinstance(item, dict):
            continue
        result = item.get("verificationResult")
        statement = result.get("statement") if isinstance(result, dict) else None
        if not isinstance(statement, dict) or statement.get("predicateType") != predicate_type:
            continue
        subjects = statement.get("subject")
        if not isinstance(subjects, list):
            continue
        if any(
            isinstance(subject, dict)
            and subject.get("name") == image_name
            and isinstance(subject.get("digest"), dict)
            and subject["digest"].get("sha256") == digest_hex
            for subject in subjects
        ):
            return
    raise EvidenceFailure(f"{path.name} is not bound to the expected image digest")


def validate_sbom(path: Path) -> dict[str, Any]:
    payload = load_json(path)
    if not isinstance(payload, dict) or payload.get("spdxVersion") != "SPDX-2.3":
        raise EvidenceFailure("BuildKit SBOM must be an SPDX 2.3 JSON document")
    if not isinstance(payload.get("packages"), list) or not payload["packages"]:
        raise EvidenceFailure("BuildKit SBOM must contain package inventory")
    return {"sha256": file_sha256(path), "packageCount": len(payload["packages"])}


def validate_scanner_metadata(path: Path) -> dict[str, Any]:
    payload = load_json(path)
    if not isinstance(payload, dict):
        raise EvidenceFailure("scanner metadata must be an object")
    version = require_nonempty(payload.get("Version"), "scanner Version", maximum=40)
    database = payload.get("VulnerabilityDB")
    if not isinstance(database, dict) or not isinstance(database.get("Version"), int):
        raise EvidenceFailure("scanner VulnerabilityDB metadata is missing")
    updated = parse_timestamp(database.get("UpdatedAt"), "VulnerabilityDB.UpdatedAt")
    downloaded = parse_timestamp(database.get("DownloadedAt"), "VulnerabilityDB.DownloadedAt")
    return {
        "name": "Trivy",
        "version": version,
        "database": {
            "schemaVersion": database["Version"],
            "updatedAt": updated.isoformat().replace("+00:00", "Z"),
            "downloadedAt": downloaded.isoformat().replace("+00:00", "Z"),
        },
    }


def validate_sarif(path: Path, image: str) -> dict[str, Any]:
    payload = load_json(path)
    if not isinstance(payload, dict) or payload.get("version") != "2.1.0":
        raise EvidenceFailure("Trivy SARIF must use schema version 2.1.0")
    runs = payload.get("runs")
    if not isinstance(runs, list) or not runs:
        raise EvidenceFailure("Trivy SARIF must contain at least one run")
    result_count = 0
    image_bound = False
    for run in runs:
        if not isinstance(run, dict):
            raise EvidenceFailure("Trivy SARIF contains an invalid run")
        driver = run.get("tool", {}).get("driver") if isinstance(run.get("tool"), dict) else None
        if (
            not isinstance(driver, dict)
            or driver.get("name") != "Trivy"
            or driver.get("fullName") != "Trivy Vulnerability Scanner"
        ):
            raise EvidenceFailure("Trivy SARIF tool identity is invalid")
        results = run.get("results", [])
        if not isinstance(results, list):
            raise EvidenceFailure("Trivy SARIF results are invalid")
        result_count += len(results)
        properties = run.get("properties")
        if isinstance(properties, dict):
            repo_digests = properties.get("repoDigests")
            if properties.get("imageName") == image or (
                isinstance(repo_digests, list) and image in repo_digests
            ):
                image_bound = True
    if not image_bound:
        raise EvidenceFailure("Trivy SARIF is not bound to the expected image digest")
    return {"sha256": file_sha256(path), "resultCount": result_count}


def vulnerability_records(report: Any, image: str) -> list[dict[str, str]]:
    if not isinstance(report, dict) or not isinstance(report.get("SchemaVersion"), int):
        raise EvidenceFailure("Trivy report schema is missing")
    artifact_name = report.get("ArtifactName")
    metadata = report.get("Metadata")
    repo_digests = metadata.get("RepoDigests", []) if isinstance(metadata, dict) else []
    if artifact_name != image and image not in repo_digests:
        raise EvidenceFailure("Trivy report is not bound to the expected image digest")
    results = report.get("Results")
    if not isinstance(results, list):
        raise EvidenceFailure("Trivy report Results must be a list")
    records: list[dict[str, str]] = []
    seen: set[tuple[str, str, str]] = set()
    for result in results:
        if not isinstance(result, dict):
            raise EvidenceFailure("Trivy report contains an invalid result")
        target = require_nonempty(result.get("Target", "unknown"), "Trivy target", maximum=500)
        vulnerabilities = result.get("Vulnerabilities") or []
        if not isinstance(vulnerabilities, list):
            raise EvidenceFailure("Trivy vulnerabilities must be a list")
        for vulnerability in vulnerabilities:
            if not isinstance(vulnerability, dict):
                raise EvidenceFailure("Trivy report contains an invalid vulnerability")
            finding = require_nonempty(vulnerability.get("VulnerabilityID"), "VulnerabilityID", maximum=128)
            package = require_nonempty(vulnerability.get("PkgName"), "PkgName", maximum=200)
            severity = require_nonempty(vulnerability.get("Severity"), "Severity", maximum=16).upper()
            if severity not in SEVERITIES:
                raise EvidenceFailure(f"unsupported vulnerability severity: {severity}")
            identity = (target, finding, package)
            if identity in seen:
                continue
            seen.add(identity)
            records.append(
                {"target": target, "finding": finding, "package": package, "severity": severity}
            )
    return records


def evaluate(args: argparse.Namespace) -> None:
    if args.service not in SERVICES:
        raise EvidenceFailure("unsupported service")
    if not SHA_PATTERN.fullmatch(args.source_commit):
        raise EvidenceFailure("source commit must be a full SHA")
    repository = require_nonempty(args.repository, "repository", maximum=120).lower()
    image_match = validate_image(args.image, service=args.service, repository=repository)
    if args.build_run_id <= 0 or args.build_run_attempt <= 0:
        raise EvidenceFailure("build run identity must be positive")
    today = parse_day(args.now, "now") if args.now else datetime.now(UTC).date()
    ignore_policy_sha256 = validate_trivy_ignore(args.trivy_ignore)
    exceptions = load_exceptions(args.exceptions, today)
    validate_attestation(args.provenance_verification, args.image, "https://slsa.dev/provenance/v1")
    validate_attestation(args.sbom_verification, args.image, "https://spdx.dev/Document/v2.3")
    sbom = validate_sbom(args.sbom)
    scanner = validate_scanner_metadata(args.scanner_metadata)
    sarif = validate_sarif(args.sarif, args.image)
    records = vulnerability_records(load_json(args.report), args.image)

    relevant_exceptions = [item for item in exceptions if item["image"] == args.image]
    used_scopes: set[tuple[str, str]] = set()
    applied: list[dict[str, Any]] = []
    unexcepted: list[dict[str, str]] = []
    for record in records:
        if record["severity"] not in BLOCKING_SEVERITIES:
            continue
        match = next(
            (
                item
                for item in relevant_exceptions
                if item["finding"] == record["finding"] and record["package"] in item["packages"]
            ),
            None,
        )
        if match is None:
            unexcepted.append(record)
            continue
        scope = (record["finding"], record["package"])
        used_scopes.add(scope)
        applied.append(
            {
                "finding": record["finding"],
                "package": record["package"],
                "severity": record["severity"],
                "owner": match["owner"],
                "rationale": match["rationale"],
                "created": match["created"],
                "expires": match["expires"],
            }
        )
    declared_scopes = {
        (item["finding"], package)
        for item in relevant_exceptions
        for package in item["packages"]
    }
    unused = sorted(declared_scopes - used_scopes)
    if unused:
        raise EvidenceFailure(f"release has unused exception scopes for {args.service}: {unused}")
    if unexcepted:
        summary = ", ".join(
            f"{item['severity']}:{item['finding']}:{item['package']}" for item in unexcepted[:20]
        )
        raise EvidenceFailure(f"blocking vulnerabilities without a valid exception: {summary}")

    counts = Counter(record["severity"] for record in records)
    evidence = {
        "schemaVersion": 1,
        "service": args.service,
        "image": args.image,
        "digest": image_match.group("digest"),
        "source": {
            "repository": repository,
            "commit": args.source_commit,
            "workflow": ".github/workflows/deploy.yaml",
            "runId": args.build_run_id,
            "runAttempt": args.build_run_attempt,
        },
        "sbom": {
            "generator": "BuildKit",
            "format": "SPDX-2.3",
            "sha256": sbom["sha256"],
            "packageCount": sbom["packageCount"],
            "githubAttestationVerified": True,
        },
        "provenance": {"buildkitMode": "max", "githubAttestationVerified": True},
        "scanner": {
            **scanner,
            "reportSha256": file_sha256(args.report),
            "sarifSha256": sarif["sha256"],
            "sarifResultCount": sarif["resultCount"],
            "ignorePolicySha256": ignore_policy_sha256,
            "counts": {severity: counts.get(severity, 0) for severity in sorted(SEVERITIES)},
        },
        "exceptions": sorted(applied, key=lambda item: (item["finding"], item["package"])),
        "policy": {"blockedSeverities": sorted(BLOCKING_SEVERITIES), "outcome": "passed"},
        "evaluatedOn": today.isoformat(),
    }
    write_json(args.output, evidence)


def validate_evidence(
    payload: Any,
    expected_service: str,
    repository: str,
    source_commit: str,
    run_id: int,
    run_attempt: int,
    ignore_policy_sha256: str,
) -> dict[str, Any]:
    if not isinstance(payload, dict) or payload.get("schemaVersion") != 1:
        raise EvidenceFailure(f"{expected_service} evidence schema is invalid")
    if payload.get("service") != expected_service:
        raise EvidenceFailure(f"{expected_service} evidence has a service mismatch")
    image = payload.get("image")
    if not isinstance(image, str):
        raise EvidenceFailure(f"{expected_service} evidence image is missing")
    match = validate_image(image, service=expected_service, repository=repository)
    if payload.get("digest") != match.group("digest"):
        raise EvidenceFailure(f"{expected_service} evidence digest is inconsistent")
    source = payload.get("source")
    expected_source = {
        "repository": repository.lower(),
        "commit": source_commit,
        "workflow": ".github/workflows/deploy.yaml",
        "runId": run_id,
        "runAttempt": run_attempt,
    }
    if source != expected_source:
        raise EvidenceFailure(f"{expected_service} evidence source identity is invalid")
    if payload.get("policy") != {"blockedSeverities": ["CRITICAL", "HIGH"], "outcome": "passed"}:
        raise EvidenceFailure(f"{expected_service} evidence did not pass the blocking policy")
    sbom = payload.get("sbom")
    provenance = payload.get("provenance")
    scanner = payload.get("scanner")
    if not isinstance(sbom, dict) or sbom.get("githubAttestationVerified") is not True:
        raise EvidenceFailure(f"{expected_service} SBOM attestation is not verified")
    if not isinstance(provenance, dict) or provenance != {
        "buildkitMode": "max",
        "githubAttestationVerified": True,
    }:
        raise EvidenceFailure(f"{expected_service} provenance is not verified")
    if not isinstance(scanner, dict) or not isinstance(scanner.get("counts"), dict):
        raise EvidenceFailure(f"{expected_service} scanner evidence is invalid")
    if scanner.get("ignorePolicySha256") != ignore_policy_sha256:
        raise EvidenceFailure(f"{expected_service} scanner ignore policy is invalid")
    evaluated_on = parse_day(payload.get("evaluatedOn"), f"{expected_service}.evaluatedOn")
    validate_applied_exceptions(payload.get("exceptions"), evaluated_on, expected_service)
    return payload


def validate_applied_exceptions(payload: Any, today: date, service: str) -> set[tuple[str, str]]:
    if not isinstance(payload, list):
        raise EvidenceFailure(f"{service} exception evidence is invalid")
    expected_fields = {
        "finding",
        "package",
        "severity",
        "owner",
        "rationale",
        "created",
        "expires",
    }
    identities: set[tuple[str, str]] = set()
    for index, item in enumerate(payload):
        prefix = f"{service}.exceptions[{index}]"
        if not isinstance(item, dict) or set(item) != expected_fields:
            raise EvidenceFailure(f"{prefix} has missing or unsupported fields")
        finding = require_nonempty(item["finding"], f"{prefix}.finding", maximum=128)
        package = require_nonempty(item["package"], f"{prefix}.package", maximum=200)
        if not FINDING_PATTERN.fullmatch(finding) or not PACKAGE_PATTERN.fullmatch(package):
            raise EvidenceFailure(f"{prefix} is not an exact finding and package scope")
        if item["severity"] not in BLOCKING_SEVERITIES:
            raise EvidenceFailure(f"{prefix} does not describe a blocking severity")
        owner = require_nonempty(item["owner"], f"{prefix}.owner", maximum=40)
        if not OWNER_PATTERN.fullmatch(owner):
            raise EvidenceFailure(f"{prefix}.owner must be one GitHub handle")
        rationale = require_nonempty(item["rationale"], f"{prefix}.rationale")
        if len(rationale) < 20:
            raise EvidenceFailure(f"{prefix}.rationale must explain the bounded risk")
        created = parse_day(item["created"], f"{prefix}.created")
        expires = parse_day(item["expires"], f"{prefix}.expires")
        if created > today or expires < today:
            raise EvidenceFailure(f"{prefix} is not active on the promotion date")
        if expires < created or (expires - created).days > MAX_EXCEPTION_DAYS:
            raise EvidenceFailure(f"{prefix} exceeds the {MAX_EXCEPTION_DAYS}-day lifetime")
        identity = (finding, package)
        if identity in identities:
            raise EvidenceFailure(f"{prefix} duplicates an applied exception")
        identities.add(identity)
    return identities


def workflow_step(content: str, name: str, next_name: str) -> str:
    start_marker = f"      - name: {name}\n"
    end_marker = f"      - name: {next_name}\n"
    if content.count(start_marker) != 1:
        raise EvidenceFailure(f"workflow must contain exactly one {name} step")
    start = content.index(start_marker)
    try:
        end = content.index(end_marker, start + len(start_marker))
    except ValueError as error:
        raise EvidenceFailure(f"workflow step after {name} is missing") from error
    return content[start:end]


def workflow_step_keys(block: str) -> list[str]:
    return [
        match.group("quoted_key") or match.group("plain_key")
        for line in block.splitlines()
        if (match := STEP_KEY.match(line))
    ]


def validate_workflow_contract(path: Path) -> None:
    try:
        content = path.read_text(encoding="utf-8")
    except (OSError, UnicodeDecodeError) as error:
        raise EvidenceFailure("release workflow cannot be read") from error

    enforce = workflow_step(
        content,
        "Enforce release vulnerability policy",
        "Upload full per-image security evidence",
    )
    enforce_keys = workflow_step_keys(enforce)
    if enforce_keys != ["env", "run"]:
        raise EvidenceFailure("vulnerability enforcement step keys must be exactly env and run")

    digest_upload = workflow_step(
        content,
        "Upload immutable image digest",
        "Checkout release revision",
    )
    digest_keys = workflow_step_keys(digest_upload)
    if digest_keys != ["uses", "with"]:
        raise EvidenceFailure("digest upload step keys must be exactly uses and with")

    scan = workflow_step(
        content,
        "Scan exact release digest with Trivy",
        "Record scanner and vulnerability database identity",
    )
    scan_keys = workflow_step_keys(scan)
    if scan_keys != ["uses", "with"]:
        raise EvidenceFailure("Trivy scan step keys must be exactly uses and with")
    if "          trivyignores: security/trivy-release-ignore\n" not in scan:
        raise EvidenceFailure("Trivy Action must use the controlled release ignore file")
    scanner = workflow_step(
        content,
        "Record scanner and vulnerability database identity",
        "Enforce release vulnerability policy",
    )
    if "            --ignorefile security/trivy-release-ignore \\\n" not in scanner:
        raise EvidenceFailure("Trivy SARIF scan must use the controlled release ignore file")


def validate_workflow(args: argparse.Namespace) -> None:
    validate_workflow_contract(args.workflow)


def aggregate(args: argparse.Namespace) -> None:
    if not SHA_PATTERN.fullmatch(args.source_commit):
        raise EvidenceFailure("source commit must be a full SHA")
    if args.build_run_id <= 0 or args.build_run_attempt <= 0:
        raise EvidenceFailure("build run identity must be positive")
    repository = require_nonempty(args.repository, "repository", maximum=120).lower()
    today = parse_day(args.now, "now") if args.now else datetime.now(UTC).date()
    ignore_policy_sha256 = validate_trivy_ignore(args.trivy_ignore)
    exceptions = load_exceptions(args.exceptions, today)
    images: list[dict[str, Any]] = []
    digests: dict[str, str] = {}
    for service in SERVICES:
        digest_path = args.digests_dir / f"{service}.digest"
        try:
            digest = digest_path.read_text(encoding="utf-8").strip()
        except OSError as error:
            raise EvidenceFailure(f"missing digest for {service}") from error
        if not DIGEST_PATTERN.fullmatch(digest):
            raise EvidenceFailure(f"invalid digest for {service}")
        evidence_path = args.evidence_dir / f"{service}-security-evidence.json"
        payload = validate_evidence(
            load_json(evidence_path),
            service,
            repository,
            args.source_commit,
            args.build_run_id,
            args.build_run_attempt,
            ignore_policy_sha256,
        )
        if payload["digest"] != digest:
            raise EvidenceFailure(f"{service} scan digest does not match the release digest")
        digests[service] = digest
        images.append(payload)

    release_images = {item["image"] for item in images}
    applied_scopes = {
        (item["image"], exception["finding"], exception["package"])
        for item in images
        for exception in item["exceptions"]
    }
    declared_scopes = {
        (exception["image"], exception["finding"], package)
        for exception in exceptions
        for package in exception["packages"]
    }
    if any(exception["image"] not in release_images for exception in exceptions):
        raise EvidenceFailure("exception file contains a digest outside the current release")
    if declared_scopes != applied_scopes:
        raise EvidenceFailure("exception evidence does not exactly match the declared release scopes")

    totals = Counter()
    for item in images:
        totals.update(item["scanner"]["counts"])
    output = {
        "schemaVersion": 1,
        "source": {
            "repository": repository,
            "commit": args.source_commit,
            "workflow": ".github/workflows/deploy.yaml",
            "runId": args.build_run_id,
            "runAttempt": args.build_run_attempt,
        },
        "policy": {
            "blockedSeverities": sorted(BLOCKING_SEVERITIES),
            "exceptionMaximumLifetimeDays": MAX_EXCEPTION_DAYS,
            "outcome": "passed",
        },
        "images": images,
        "summary": {
            "imageCount": len(images),
            "vulnerabilities": {severity: totals.get(severity, 0) for severity in sorted(SEVERITIES)},
            "exceptionCount": len(applied_scopes),
        },
        "evaluatedOn": today.isoformat(),
    }
    write_json(args.output, output)


def verify_release(args: argparse.Namespace) -> None:
    today = parse_day(args.now, "now") if args.now else datetime.now(UTC).date()
    ignore_policy_sha256 = validate_trivy_ignore(args.trivy_ignore)
    payload = load_json(args.evidence)
    if not isinstance(payload, dict) or payload.get("schemaVersion") != 1:
        raise EvidenceFailure("release security evidence schema is invalid")
    source = payload.get("source")
    if not isinstance(source, dict):
        raise EvidenceFailure("release security source identity is missing")
    if source.get("repository") != args.expected_repository.lower():
        raise EvidenceFailure("release security repository identity does not match")
    if source.get("commit") != args.expected_commit or not SHA_PATTERN.fullmatch(args.expected_commit):
        raise EvidenceFailure("release security commit does not match")
    if source.get("workflow") != ".github/workflows/deploy.yaml":
        raise EvidenceFailure("release security signer workflow does not match")
    if args.expected_run_id is not None and source.get("runId") != args.expected_run_id:
        raise EvidenceFailure("release security build run does not match")
    if payload.get("policy") != {
        "blockedSeverities": ["CRITICAL", "HIGH"],
        "exceptionMaximumLifetimeDays": MAX_EXCEPTION_DAYS,
        "outcome": "passed",
    }:
        raise EvidenceFailure("release security policy did not pass")
    images = payload.get("images")
    if not isinstance(images, list) or len(images) != len(SERVICES):
        raise EvidenceFailure("release security evidence must contain six images")
    evidence_images: dict[str, str] = {}
    applied_exception_count = 0
    for item in images:
        if not isinstance(item, dict) or item.get("service") not in SERVICES:
            raise EvidenceFailure("release security evidence contains an invalid image")
        service = item["service"]
        if service in evidence_images or item.get("policy", {}).get("outcome") != "passed":
            raise EvidenceFailure("release security image evidence is duplicated or failed")
        image = item.get("image")
        if not isinstance(image, str):
            raise EvidenceFailure("release security image reference is missing")
        validate_image(image, service=service, repository=args.expected_repository)
        if item.get("digest") != image.rsplit("@", 1)[1]:
            raise EvidenceFailure("release security image digest is inconsistent")
        if item.get("sbom", {}).get("githubAttestationVerified") is not True:
            raise EvidenceFailure("release security SBOM attestation is missing")
        if item.get("provenance", {}).get("githubAttestationVerified") is not True:
            raise EvidenceFailure("release security provenance attestation is missing")
        scanner = item.get("scanner")
        if not isinstance(scanner, dict) or scanner.get("ignorePolicySha256") != ignore_policy_sha256:
            raise EvidenceFailure("release security scanner ignore policy is invalid")
        applied_exception_count += len(
            validate_applied_exceptions(item.get("exceptions"), today, service)
        )
        evidence_images[service] = image

    summary = payload.get("summary")
    if (
        not isinstance(summary, dict)
        or summary.get("imageCount") != len(SERVICES)
        or summary.get("exceptionCount") != applied_exception_count
    ):
        raise EvidenceFailure("release security summary is inconsistent")

    try:
        manifest = args.manifest.read_text(encoding="utf-8")
    except (OSError, UnicodeDecodeError) as error:
        raise EvidenceFailure("release manifest cannot be read") from error
    manifest_images = {
        match.group("image")
        for line in manifest.splitlines()
        if (match := IMAGE_LINE.fullmatch(line)) and IMAGE_PATTERN.fullmatch(match.group("image"))
    }
    if manifest_images != set(evidence_images.values()):
        raise EvidenceFailure("release manifest images do not match verified security evidence")
    release_marker = f'releaseId: "{args.expected_commit}"'
    if manifest.count(release_marker) != 1:
        raise EvidenceFailure("release manifest commit marker does not match security evidence")


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser(description=__doc__)
    commands = root.add_subparsers(dest="command", required=True)

    evaluate_parser = commands.add_parser("evaluate", help="gate one exact image scan")
    evaluate_parser.add_argument("--service", required=True)
    evaluate_parser.add_argument("--image", required=True)
    evaluate_parser.add_argument("--repository", required=True)
    evaluate_parser.add_argument("--source-commit", required=True)
    evaluate_parser.add_argument("--build-run-id", required=True, type=int)
    evaluate_parser.add_argument("--build-run-attempt", required=True, type=int)
    evaluate_parser.add_argument("--report", required=True, type=Path)
    evaluate_parser.add_argument("--sarif", required=True, type=Path)
    evaluate_parser.add_argument("--scanner-metadata", required=True, type=Path)
    evaluate_parser.add_argument("--exceptions", required=True, type=Path)
    evaluate_parser.add_argument("--trivy-ignore", required=True, type=Path)
    evaluate_parser.add_argument("--sbom", required=True, type=Path)
    evaluate_parser.add_argument("--provenance-verification", required=True, type=Path)
    evaluate_parser.add_argument("--sbom-verification", required=True, type=Path)
    evaluate_parser.add_argument("--output", required=True, type=Path)
    evaluate_parser.add_argument("--now")
    evaluate_parser.set_defaults(handler=evaluate)

    aggregate_parser = commands.add_parser("aggregate", help="bind all image evidence to one release")
    aggregate_parser.add_argument("--evidence-dir", required=True, type=Path)
    aggregate_parser.add_argument("--digests-dir", required=True, type=Path)
    aggregate_parser.add_argument("--exceptions", required=True, type=Path)
    aggregate_parser.add_argument("--trivy-ignore", required=True, type=Path)
    aggregate_parser.add_argument("--repository", required=True)
    aggregate_parser.add_argument("--source-commit", required=True)
    aggregate_parser.add_argument("--build-run-id", required=True, type=int)
    aggregate_parser.add_argument("--build-run-attempt", required=True, type=int)
    aggregate_parser.add_argument("--output", required=True, type=Path)
    aggregate_parser.add_argument("--now")
    aggregate_parser.set_defaults(handler=aggregate)

    verify_parser = commands.add_parser("verify-release", help="verify evidence against a manifest")
    verify_parser.add_argument("--evidence", required=True, type=Path)
    verify_parser.add_argument("--manifest", required=True, type=Path)
    verify_parser.add_argument("--expected-repository", required=True)
    verify_parser.add_argument("--expected-commit", required=True)
    verify_parser.add_argument("--expected-run-id", type=int)
    verify_parser.add_argument("--trivy-ignore", required=True, type=Path)
    verify_parser.add_argument("--now")
    verify_parser.set_defaults(handler=verify_release)

    workflow_parser = commands.add_parser(
        "validate-workflow", help="verify the fail-closed Main CI step contract"
    )
    workflow_parser.add_argument("--workflow", required=True, type=Path)
    workflow_parser.set_defaults(handler=validate_workflow)
    return root


def main() -> int:
    args = parser().parse_args()
    try:
        args.handler(args)
    except EvidenceFailure as error:
        print(f"release security evidence rejected: {error}", file=sys.stderr)
        return 1
    print(f"release security evidence {args.command} passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
