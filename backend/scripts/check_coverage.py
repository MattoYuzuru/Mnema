#!/usr/bin/env python3

import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
BASELINE_PATH = ROOT / "coverage-baseline.json"
SERVICES_DIR = ROOT / "services"


def service_dirs() -> list[Path]:
    return sorted(
        path for path in SERVICES_DIR.iterdir()
        if path.is_dir() and (path / "src/main").exists()
    )


def load_line_coverage(report_path: Path) -> float:
    root = ET.parse(report_path).getroot()
    for counter in root.findall("counter"):
        if counter.attrib["type"] == "LINE":
            missed = int(counter.attrib["missed"])
            covered = int(counter.attrib["covered"])
            total = missed + covered
            return (covered / total) if total else 1.0
    raise RuntimeError(f"LINE counter not found in {report_path}")


def main() -> int:
    baselines = json.loads(BASELINE_PATH.read_text())
    failures: list[str] = []

    print("Backend coverage baseline check")
    discovered_services: set[str] = set()

    for service, required in sorted(baselines.items()):
        report_path = SERVICES_DIR / service / "build/reports/jacoco/test/jacocoTestReport.xml"
        discovered_services.add(service)
        if not report_path.exists():
            failures.append(f"{service}: missing coverage report at {report_path}")
            continue
        actual = load_line_coverage(report_path)
        print(f"- {service}: actual={actual:.2%} required={required:.2%}")
        if actual + 1e-9 < required:
            failures.append(f"{service}: {actual:.2%} < {required:.2%}")

    for service_dir in service_dirs():
        if service_dir.name not in discovered_services:
            failures.append(f"{service_dir.name}: missing baseline threshold")

    if failures:
        print("\nCoverage check failed:", file=sys.stderr)
        for failure in failures:
            print(f"  * {failure}", file=sys.stderr)
        return 1

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
