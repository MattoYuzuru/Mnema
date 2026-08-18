#!/usr/bin/env python3
"""Validate the deliberately PII-free evidence emitted by backup and restore jobs."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Any


BACKUP_FIELDS = {
    "schemaVersion",
    "kind",
    "status",
    "backupId",
    "snapshotEpoch",
    "dumpCompletedEpoch",
    "uploadedEpoch",
    "sourceServerVersionNum",
    "accountCount",
    "dumpBytes",
    "retentionDays",
    "latestPointerUpdated",
    "dumpSha256",
    "reconciliationSha256",
    "capacitySha256",
}
RESTORE_FIELDS = {
    "schemaVersion",
    "kind",
    "status",
    "namespace",
    "backupId",
    "snapshotEpoch",
    "drillStartedEpoch",
    "restoredEpoch",
    "rpoSeconds",
    "rtoSeconds",
    "sourceServerVersionNum",
    "targetServerVersionNum",
    "accountCount",
    "dumpSha256",
    "reconciliationSha256",
}
BACKUP_ID = re.compile(
    r"^[0-9]{8}T[0-9]{6}Z-[0-9a-f]{8}-[0-9a-f]{4}-"
    r"[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
)
SHA256 = re.compile(r"^[0-9a-f]{64}$")


def require_non_negative_integer(report: dict[str, Any], field: str) -> int:
    value = report[field]
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise ValueError(f"{field} must be a non-negative integer")
    return value


def validate_report(report: dict[str, Any], expected_kind: str) -> None:
    expected_fields = BACKUP_FIELDS if expected_kind == "backup" else RESTORE_FIELDS
    if set(report) != expected_fields:
        raise ValueError("report fields do not match the safe evidence schema")
    if report["schemaVersion"] != 1 or report["kind"] != expected_kind:
        raise ValueError("unsupported report identity")
    expected_status = "uploaded" if expected_kind == "backup" else "reconciled"
    if report["status"] != expected_status:
        raise ValueError("report does not describe a successful operation")
    if not isinstance(report["backupId"], str) or not BACKUP_ID.fullmatch(report["backupId"]):
        raise ValueError("backupId is invalid")

    hashes = ["dumpSha256", "reconciliationSha256"]
    if expected_kind == "backup":
        hashes.append("capacitySha256")
    for field in hashes:
        value = report[field]
        if not isinstance(value, str) or not SHA256.fullmatch(value):
            raise ValueError(f"{field} is invalid")

    integer_fields = expected_fields - {
        "kind",
        "status",
        "namespace",
        "backupId",
        "dumpSha256",
        "reconciliationSha256",
        "capacitySha256",
        "latestPointerUpdated",
    }
    for field in integer_fields:
        require_non_negative_integer(report, field)

    if expected_kind == "backup":
        if not isinstance(report["latestPointerUpdated"], bool):
            raise ValueError("latestPointerUpdated must be a boolean")
        if not (
            report["snapshotEpoch"]
            <= report["dumpCompletedEpoch"]
            <= report["uploadedEpoch"]
        ):
            raise ValueError("backup timestamps are not monotonic")
        if report["dumpBytes"] == 0:
            raise ValueError("dumpBytes must be positive")
        if report["retentionDays"] == 0:
            raise ValueError("retentionDays must be positive")
    else:
        if report["namespace"] != "mnema-restore-drill":
            raise ValueError("restore target is not the fixed isolated namespace")
        if not (
            report["snapshotEpoch"]
            <= report["drillStartedEpoch"]
            <= report["restoredEpoch"]
        ):
            raise ValueError("restore timestamps are not monotonic")
        if report["rpoSeconds"] != report["drillStartedEpoch"] - report["snapshotEpoch"]:
            raise ValueError("rpoSeconds does not match measured timestamps")
        if report["rtoSeconds"] != report["restoredEpoch"] - report["drillStartedEpoch"]:
            raise ValueError("rtoSeconds does not match measured timestamps")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--kind", choices=("backup", "restore-drill"), required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()

    try:
        report = json.loads(args.report.read_text(encoding="utf-8"))
        if not isinstance(report, dict):
            raise ValueError("report root must be an object")
        validate_report(report, args.kind)
    except (OSError, UnicodeError, json.JSONDecodeError, ValueError) as error:
        parser.error(str(error))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
