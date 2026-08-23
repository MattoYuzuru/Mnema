#!/usr/bin/env python3
"""Require fresh production telemetry and Metrics API evidence."""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
from datetime import UTC, datetime
from typing import Any


PROMETHEUS_TARGETS_PATH = (
    "/api/v1/namespaces/observability/services/http:prometheus:9090/"
    "proxy/api/v1/targets"
)
METRICS_NODES_PATH = "/apis/metrics.k8s.io/v1beta1/nodes"
REQUIRED_JOBS = {"node-exporter", "kubelet", "cadvisor"}


class BoundaryFailure(RuntimeError):
    pass


def kubectl_json(path: str) -> dict[str, Any]:
    try:
        result = subprocess.run(
            ["kubectl", "get", "--raw", path],
            check=True,
            capture_output=True,
            text=True,
        )
        value = json.loads(result.stdout)
    except (OSError, subprocess.CalledProcessError, json.JSONDecodeError):
        raise BoundaryFailure("telemetry_api_unavailable") from None
    if not isinstance(value, dict):
        raise BoundaryFailure("telemetry_response_invalid")
    return value


def timestamp_epoch(value: Any) -> float:
    if not isinstance(value, str) or not value:
        raise BoundaryFailure("telemetry_timestamp_missing")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        raise BoundaryFailure("telemetry_timestamp_invalid") from None
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=UTC)
    return parsed.timestamp()


def verify_prometheus(payload: dict[str, Any], not_before_epoch: float) -> None:
    targets = payload.get("data", {}).get("activeTargets", [])
    if not isinstance(targets, list):
        raise BoundaryFailure("prometheus_targets_invalid")
    by_job: dict[str, list[dict[str, Any]]] = {}
    for target in targets:
        if not isinstance(target, dict):
            continue
        labels = target.get("labels", {})
        job = labels.get("job") if isinstance(labels, dict) else None
        if job in REQUIRED_JOBS:
            by_job.setdefault(str(job), []).append(target)
    for job in REQUIRED_JOBS:
        job_targets = by_job.get(job, [])
        if not job_targets:
            raise BoundaryFailure("prometheus_target_missing")
        if any(target.get("health") != "up" for target in job_targets):
            raise BoundaryFailure("prometheus_target_unhealthy")
        if any(timestamp_epoch(target.get("lastScrape")) <= not_before_epoch for target in job_targets):
            raise BoundaryFailure("prometheus_target_stale")


def verify_metrics_api(payload: dict[str, Any], not_before_epoch: float) -> None:
    items = payload.get("items")
    if not isinstance(items, list) or not items:
        raise BoundaryFailure("metrics_nodes_missing")
    if any(
        not isinstance(item, dict) or timestamp_epoch(item.get("timestamp")) <= not_before_epoch
        for item in items
    ):
        raise BoundaryFailure("metrics_nodes_stale")


def verify_once(not_before_epoch: float) -> None:
    verify_prometheus(kubectl_json(PROMETHEUS_TARGETS_PATH), not_before_epoch)
    verify_metrics_api(kubectl_json(METRICS_NODES_PATH), not_before_epoch)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--not-before-epoch", type=float, default=0)
    parser.add_argument("--timeout-seconds", type=int, default=60)
    args = parser.parse_args()
    if args.not_before_epoch < 0:
        parser.error("--not-before-epoch must be non-negative")
    if not 0 <= args.timeout_seconds <= 300:
        parser.error("--timeout-seconds must be between 0 and 300")
    return args


def main() -> int:
    args = parse_args()
    deadline = time.monotonic() + args.timeout_seconds
    last_error = "telemetry_unverified"
    while True:
        try:
            verify_once(args.not_before_epoch)
        except BoundaryFailure as error:
            last_error = str(error)
        else:
            print("production_telemetry_boundary=ok")
            return 0
        if time.monotonic() >= deadline:
            print(f"Production telemetry boundary failed: {last_error}", file=sys.stderr)
            return 1
        time.sleep(min(2.0, max(0.0, deadline - time.monotonic())))


if __name__ == "__main__":
    raise SystemExit(main())
