import unittest

from validate_report import validate_report


HASH = "a" * 64


class ValidateReportTest(unittest.TestCase):
    def test_accepts_backup_report_with_effective_retention(self) -> None:
        report = {
            "schemaVersion": 1,
            "kind": "backup",
            "status": "uploaded",
            "backupId": "20260819T020304Z-00000000-0000-4000-8000-000000000001",
            "snapshotEpoch": 100,
            "dumpCompletedEpoch": 110,
            "uploadedEpoch": 120,
            "sourceServerVersionNum": 160010,
            "accountCount": 3,
            "dumpBytes": 4096,
            "retentionDays": 30,
            "latestPointerUpdated": True,
            "dumpSha256": HASH,
            "reconciliationSha256": HASH,
            "capacitySha256": HASH,
        }

        validate_report(report, "backup")

    def test_accepts_measured_restore_report(self) -> None:
        report = {
            "schemaVersion": 1,
            "kind": "restore-drill",
            "status": "reconciled",
            "namespace": "mnema-restore-drill",
            "backupId": "20260819T020304Z-00000000-0000-4000-8000-000000000001",
            "snapshotEpoch": 100,
            "drillStartedEpoch": 160,
            "restoredEpoch": 190,
            "rpoSeconds": 60,
            "rtoSeconds": 30,
            "sourceServerVersionNum": 160010,
            "targetServerVersionNum": 180006,
            "accountCount": 3,
            "dumpSha256": HASH,
            "reconciliationSha256": HASH,
        }

        validate_report(report, "restore-drill")

    def test_rejects_unmeasured_rpo(self) -> None:
        report = {
            "schemaVersion": 1,
            "kind": "restore-drill",
            "status": "reconciled",
            "namespace": "mnema-restore-drill",
            "backupId": "20260819T020304Z-00000000-0000-4000-8000-000000000001",
            "snapshotEpoch": 100,
            "drillStartedEpoch": 160,
            "restoredEpoch": 190,
            "rpoSeconds": 59,
            "rtoSeconds": 30,
            "sourceServerVersionNum": 160010,
            "targetServerVersionNum": 180006,
            "accountCount": 3,
            "dumpSha256": HASH,
            "reconciliationSha256": HASH,
        }

        with self.assertRaisesRegex(ValueError, "rpoSeconds"):
            validate_report(report, "restore-drill")

    def test_rejects_additional_pii_field(self) -> None:
        report = {
            "schemaVersion": 1,
            "kind": "backup",
            "status": "uploaded",
            "backupId": "20260819T020304Z-00000000-0000-4000-8000-000000000001",
            "snapshotEpoch": 100,
            "dumpCompletedEpoch": 110,
            "uploadedEpoch": 120,
            "sourceServerVersionNum": 160010,
            "accountCount": 3,
            "dumpBytes": 4096,
            "retentionDays": 30,
            "latestPointerUpdated": False,
            "dumpSha256": HASH,
            "reconciliationSha256": HASH,
            "capacitySha256": HASH,
            "email": "must-not-appear@example.test",
        }

        with self.assertRaisesRegex(ValueError, "safe evidence schema"):
            validate_report(report, "backup")


if __name__ == "__main__":
    unittest.main()
