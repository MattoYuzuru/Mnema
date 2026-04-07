#!/usr/bin/env python3

import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
BASELINE_PATH = ROOT / "coverage-baseline.json"
REPORT_GLOB = ROOT.glob("services/*/build/reports/jacoco/test/jacocoTestReport.xml")


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
    for report_path in sorted(REPORT_GLOB):
        service = report_path.parts[-6]
        actual = load_line_coverage(report_path)
        required = baselines.get(service)
        if required is None:
            failures.append(f"{service}: missing baseline threshold")
            continue
        print(f"- {service}: actual={actual:.2%} required={required:.2%}")
        if actual + 1e-9 < required:
            failures.append(f"{service}: {actual:.2%} < {required:.2%}")

    if failures:
        print("\nCoverage check failed:", file=sys.stderr)
        for failure in failures:
            print(f"  * {failure}", file=sys.stderr)
        return 1

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
