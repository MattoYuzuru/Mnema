#!/usr/bin/env python3
"""Require production node and kubelet scrape targets to remain healthy."""

from __future__ import annotations

import json
import subprocess
import sys


REQUIRED_JOBS = {"node-exporter", "kubelet", "cadvisor"}


def main() -> int:
    try:
        result = subprocess.run(
            [
                "kubectl",
                "get",
                "--raw",
                "/api/v1/namespaces/observability/services/http:prometheus:9090/proxy/api/v1/targets",
            ],
            check=True,
            capture_output=True,
            text=True,
        )
        response = json.loads(result.stdout)
    except (OSError, subprocess.CalledProcessError, json.JSONDecodeError):
        print("Unable to read production Prometheus target health", file=sys.stderr)
        return 2
    targets = response.get("data", {}).get("activeTargets", [])
    health_by_job: dict[str, list[str]] = {}
    for target in targets:
        job = target.get("labels", {}).get("job")
        if job in REQUIRED_JOBS:
            health_by_job.setdefault(job, []).append(target.get("health", ""))
    unhealthy = sorted(
        job
        for job in REQUIRED_JOBS
        if not health_by_job.get(job) or any(health != "up" for health in health_by_job[job])
    )
    if unhealthy:
        print("Production telemetry targets are unavailable: " + ", ".join(unhealthy), file=sys.stderr)
        return 1
    print("production_telemetry_boundary=ok")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
