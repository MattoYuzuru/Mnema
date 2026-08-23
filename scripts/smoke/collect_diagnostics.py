#!/usr/bin/env python3
"""Collect bounded Kubernetes release diagnostics and redact sensitive values."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
from pathlib import Path
from typing import Any


SERVICES = ("frontend", "auth", "user", "core", "media", "import")
EMAIL_PATTERN = re.compile(r"(?i)\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b")
UUID_PATTERN = re.compile(
    r"(?i)\b[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\b"
)
IPV4_PATTERN = re.compile(r"(?<![0-9.])(?:25[0-5]|2[0-4][0-9]|1?[0-9]{1,2})(?:\.(?:25[0-5]|2[0-4][0-9]|1?[0-9]{1,2})){3}(?![0-9.])")
AUTHORIZATION_PATTERN = re.compile(r"(?i)(authorization\s*[:=]\s*)[^\r\n,;]+")
COOKIE_PATTERN = re.compile(r"(?i)((?:set-)?cookie\s*[:=]\s*)[^\r\n]+")
JWT_PATTERN = re.compile(r"\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\b")
AWS_ACCESS_KEY_PATTERN = re.compile(r"\b(?:AKIA|ASIA)[A-Z0-9]{16}\b")
PRIVATE_KEY_PATTERN = re.compile(
    r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----.*?"
    r"-----END (?:RSA |EC |OPENSSH )?PRIVATE KEY-----",
    re.DOTALL,
)
SENSITIVE_ASSIGNMENT_PATTERN = re.compile(
    r'''(?ix)
    (
      ["']?[a-z0-9_.-]*
      (?:password|passwd|secret|token|api[_-]?key|private[_-]?key|user[_-]?id|username|login)
      [a-z0-9_.-]*["']?\s*[:=]\s*
    )
    (?:"(?:\\.|[^"])*"|'(?:\\.|[^'])*'|[^\s,;]+)
    '''
)


def redact(text: str) -> str:
    text = EMAIL_PATTERN.sub("[REDACTED_EMAIL]", text)
    text = UUID_PATTERN.sub("[REDACTED_UUID]", text)
    text = IPV4_PATTERN.sub("[REDACTED_IP]", text)
    text = PRIVATE_KEY_PATTERN.sub("[REDACTED_PRIVATE_KEY]", text)
    text = AUTHORIZATION_PATTERN.sub(r"\1[REDACTED]", text)
    text = COOKIE_PATTERN.sub(r"\1[REDACTED]", text)
    text = JWT_PATTERN.sub("[REDACTED_JWT]", text)
    text = AWS_ACCESS_KEY_PATTERN.sub("[REDACTED_AWS_ACCESS_KEY]", text)
    return SENSITIVE_ASSIGNMENT_PATTERN.sub(r"\1[REDACTED]", text)


def run(command: list[str]) -> str:
    result = subprocess.run(command, text=True, capture_output=True, check=False)
    if result.returncode == 0:
        return redact(result.stdout)
    return f"command_status=failed exit_code={result.returncode}\n"


def write(path: Path, content: str) -> None:
    path.write_text(content, encoding="utf-8")
    path.chmod(0o600)


def non_ready_services(namespace: str) -> set[str]:
    result = subprocess.run(
        ["kubectl", "-n", namespace, "get", "pods", "-o", "json"],
        text=True,
        capture_output=True,
        check=False,
    )
    if result.returncode != 0:
        return set()
    try:
        payload = json.loads(result.stdout)
    except json.JSONDecodeError:
        return set()
    affected: set[str] = set()
    for item in payload.get("items", []):
        if not isinstance(item, dict):
            continue
        labels = item.get("metadata", {}).get("labels", {})
        app = labels.get("app") if isinstance(labels, dict) else None
        statuses = item.get("status", {}).get("containerStatuses", [])
        ready = bool(statuses) and all(status.get("ready") is True for status in statuses if isinstance(status, dict))
        if not ready and isinstance(app, str) and app.startswith("mnema-"):
            service = app.removeprefix("mnema-")
            if service in SERVICES:
                affected.add(service)
    return affected


def collect(namespace: str, output: Path, failed_service: str | None) -> None:
    output.mkdir(parents=True, exist_ok=True)
    output.chmod(0o700)
    write(
        output / "workloads.txt",
        run(["kubectl", "-n", namespace, "get", "pods,deployments,statefulsets,services,ingresses", "-o", "wide"]),
    )
    write(
        output / "events.txt",
        run(["kubectl", "-n", namespace, "get", "events", "--sort-by=.lastTimestamp"])[-100_000:],
    )
    services = non_ready_services(namespace)
    if failed_service in SERVICES:
        services.add(str(failed_service))
    for service in sorted(services):
        write(
            output / f"{service}-describe.txt",
            run(["kubectl", "-n", namespace, "describe", "deployment", f"mnema-{service}"])[-100_000:],
        )
        write(
            output / f"{service}-logs.txt",
            run(
                [
                    "kubectl", "-n", namespace, "logs", f"deployment/mnema-{service}",
                    "--all-containers=true", "--prefix=true", "--timestamps=true", "--tail=200",
                ]
            )[-100_000:],
        )
    write(
        output / "summary.json",
        json.dumps(
            {"schemaVersion": 1, "namespace": namespace, "affectedServices": sorted(services)},
            indent=2,
            sort_keys=True,
        )
        + "\n",
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--namespace", required=True)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--failed-service", choices=(*SERVICES, "release"))
    parser.add_argument("--smoke-report", type=Path)
    args = parser.parse_args()
    if not re.fullmatch(r"[a-z0-9]([-a-z0-9]*[a-z0-9])?", args.namespace):
        parser.error("namespace must be a lowercase DNS label")
    failed_service = args.failed_service
    if failed_service is None and args.smoke_report and args.smoke_report.is_file():
        try:
            report = json.loads(args.smoke_report.read_text(encoding="utf-8"))
            candidate = report.get("failed_service") if isinstance(report, dict) else None
            if candidate in (*SERVICES, "release"):
                failed_service = candidate
        except (OSError, json.JSONDecodeError):
            pass
    collect(args.namespace, args.output, failed_service)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
