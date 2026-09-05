import importlib.util
import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).parents[1] / "purge" / "rehearsal.py"
SPEC = importlib.util.spec_from_file_location("purge_rehearsal", MODULE_PATH)
purge = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(purge)


TARGET_ID = "00000000-0000-4000-8000-000000000145"
LEGACY_DEPLOYMENT_UID = "00000000-0000-4000-8000-000000000001"
FRESH_DEPLOYMENT_UID = "00000000-0000-4000-8000-000000000002"
LEGACY_ROUTE_UID = "00000000-0000-4000-8000-000000000003"
LEGACY_SECRET_UID = "00000000-0000-4000-8000-000000000004"
LEGACY_PVC_UID = "00000000-0000-4000-8000-000000000005"


def completed(command, stdout="", stderr="", returncode=0):
    return subprocess.CompletedProcess(command, returncode, stdout, stderr)


class FixtureRunner(purge.Runner):
    def __init__(self):
        self.schemas = {"legacy_auth": "postgres", "account_only": "postgres"}
        self.preserved_objects = ['["relation", "account_only", "r", "account"]']
        self.cascade_crosses_preserve_boundary = False
        self.redis = {"legacy:session", "fresh:account", purge.OWNERSHIP_KEY}
        self.versions = {
            ("legacy/content", "legacy-version", 6, "legacy-etag"),
            ("legacy/wal", "wal-version", 3, "wal-etag"),
            ("legacy/backup", "backup-version", 6, "backup-etag"),
            ("fresh/avatar", "fresh-version", 5, "fresh-etag"),
        }
        self.markers = {("legacy/deleted", "delete-marker")}
        self.uploads = {("legacy/upload", "multipart-id")}
        self.kubernetes = {
            ("Deployment", "legacy-worker", LEGACY_DEPLOYMENT_UID),
            ("Deployment", "identity-account", FRESH_DEPLOYMENT_UID),
            ("Ingress", "legacy-route", LEGACY_ROUTE_UID),
            ("Secret", "legacy-credential", LEGACY_SECRET_UID),
            ("PersistentVolumeClaim", "legacy-data", LEGACY_PVC_UID),
        }
        self.object_lock = False

    def run(self, command, *, environment=None, input_text=None, allow_failure=False):
        del environment, input_text, allow_failure
        if command[0] == "psql":
            sql = command[-1]
            if sql == "SHOW server_version_num":
                return completed(command, "160000\n")
            if sql.startswith("SELECT COALESCE(obj_description"):
                return completed(command, f"mnema-rehearsal:{TARGET_ID}\n")
            if sql.startswith("SELECT nspname"):
                rows = "".join(f"{name}|{owner}\n" for name, owner in sorted(self.schemas.items()))
                return completed(command, rows)
            if "preserved_objects ORDER BY object_identity" in sql:
                rows = self.preserved_objects
                if sql.startswith("BEGIN;") and self.cascade_crosses_preserve_boundary:
                    rows = []
                return completed(command, "".join(f"{row}\n" for row in rows))
            if sql.startswith("DROP SCHEMA"):
                name = sql.split('"')[1]
                self.schemas.pop(name, None)
                return completed(command)
        if command[0] == "redis-cli":
            if len(command) >= 2 and command[-2:] == ["GET", purge.OWNERSHIP_KEY]:
                return completed(command, TARGET_ID + "\n")
            if command[-1] == "--scan":
                return completed(command, "".join(f"{key}\n" for key in sorted(self.redis)))
            if "UNLINK" in command:
                index = command.index("UNLINK")
                for key in command[index + 1:]:
                    self.redis.discard(key)
                return completed(command, str(len(command[index + 1:])) + "\n")
        if command[0] == "aws":
            operation = command[command.index("s3api") + 1]
            if operation == "get-bucket-versioning":
                return completed(command, '{"Status":"Enabled"}\n')
            if operation == "get-bucket-tagging":
                return completed(command, json.dumps({
                    "TagSet": [{"Key": "mnema-rehearsal-target-id", "Value": TARGET_ID}]
                }))
            if operation == "get-object-lock-configuration":
                if self.object_lock:
                    return completed(command, '{"ObjectLockConfiguration":{"ObjectLockEnabled":"Enabled"}}\n')
                return completed(command, stderr="ObjectLockConfigurationNotFoundError", returncode=1)
            if operation == "list-object-versions":
                return completed(command, json.dumps({
                    "Versions": [
                        {"Key": key, "VersionId": version, "Size": size, "ETag": etag}
                        for key, version, size, etag in sorted(self.versions)
                    ],
                    "DeleteMarkers": [
                        {"Key": key, "VersionId": version} for key, version in sorted(self.markers)
                    ],
                }))
            if operation == "list-multipart-uploads":
                return completed(command, json.dumps({
                    "Uploads": [{"Key": key, "UploadId": upload} for key, upload in sorted(self.uploads)]
                }))
            if operation == "abort-multipart-upload":
                key = command[command.index("--key") + 1]
                upload = command[command.index("--upload-id") + 1]
                self.uploads.discard((key, upload))
                return completed(command)
            if operation == "delete-object":
                key = command[command.index("--key") + 1]
                version = command[command.index("--version-id") + 1]
                self.versions = {item for item in self.versions if item[:2] != (key, version)}
                self.markers.discard((key, version))
                return completed(command, "{}")
        if command[0] == "kubectl":
            if "Namespace" in command:
                return completed(command, json.dumps({
                    "metadata": {"labels": {purge.OWNERSHIP_LABEL: TARGET_ID}}
                }))
            operation = command[command.index("mnema-rehearsal") + 1]
            if operation == "get" and command[-2:] == ["-o", "json"]:
                kind = command[command.index("get") + 1]
                if len(command) == command.index("get") + 4:
                    items = [{"metadata": {"name": name, "uid": uid}}
                             for item_kind, name, uid in sorted(self.kubernetes) if item_kind == kind]
                    return completed(command, json.dumps({"items": items}))
                name = command[command.index("get") + 2]
                found = next((uid for item_kind, item_name, uid in self.kubernetes
                              if item_kind == kind and item_name == name), None)
                if found is None:
                    return completed(command, returncode=1)
                return completed(command, json.dumps({"metadata": {"name": name, "uid": found}}))
            if operation == "delete":
                index = command.index("delete")
                kind, name = command[index + 1:index + 3]
                self.kubernetes = {item for item in self.kubernetes if item[:2] != (kind, name)}
                return completed(command)
        raise AssertionError(f"unexpected command: {command}")


def manifest():
    return {
        "schemaVersion": 1,
        "kind": "mnema-no-snapshot-purge",
        "targetId": TARGET_ID,
        "pointOfNoReturn": "first-delete-roll-forward-only",
        "postgres": [{
            "id": "legacy-postgres",
            "envPrefix": "MNEMA_PURGE_POSTGRES",
            "serverVersionNum": 160000,
            "deleteSchemas": [{"name": "legacy_auth", "owner": "postgres"}],
            "preserveSchemas": [{"name": "account_only", "owner": "postgres"}],
        }],
        "redis": [{
            "id": "legacy-redis",
            "envPrefix": "MNEMA_PURGE_REDIS",
            "database": 0,
            "deleteKeys": ["legacy:session"],
            "preserveKeys": ["fresh:account", purge.OWNERSHIP_KEY],
        }],
        "s3": [{
            "id": "legacy-storage",
            "endpointEnv": "MNEMA_PURGE_S3_ENDPOINT",
            "region": "us-east-1",
            "bucket": "mnema-purge-fixture",
            "deleteVersions": [{
                "category": "object", "key": "legacy/content", "versionId": "legacy-version",
                "size": 6, "etag": "legacy-etag",
            }, {
                "category": "wal", "key": "legacy/wal", "versionId": "wal-version",
                "size": 3, "etag": "wal-etag",
            }, {
                "category": "backup", "key": "legacy/backup", "versionId": "backup-version",
                "size": 6, "etag": "backup-etag",
            }],
            "deleteMarkers": [{"key": "legacy/deleted", "versionId": "delete-marker"}],
            "multipartUploads": [{"key": "legacy/upload", "uploadId": "multipart-id"}],
            "preserveVersions": [{
                "category": "object", "key": "fresh/avatar", "versionId": "fresh-version",
                "size": 5, "etag": "fresh-etag",
            }],
            "preserveDeleteMarkers": [],
            "requireObjectLockDisabled": True,
        }],
        "kubernetes": [{
            "id": "legacy-runtime",
            "kubeconfigEnv": "MNEMA_PURGE_KUBECONFIG",
            "context": "rehearsal",
            "namespace": "mnema-rehearsal",
            "inventoryKinds": sorted(purge.KUBERNETES_KINDS),
            "delete": [
                {"kind": "Deployment", "name": "legacy-worker", "uid": LEGACY_DEPLOYMENT_UID},
                {"kind": "Ingress", "name": "legacy-route", "uid": LEGACY_ROUTE_UID},
                {"kind": "Secret", "name": "legacy-credential", "uid": LEGACY_SECRET_UID},
                {"kind": "PersistentVolumeClaim", "name": "legacy-data", "uid": LEGACY_PVC_UID},
            ],
            "preserve": [{"kind": "Deployment", "name": "identity-account", "uid": FRESH_DEPLOYMENT_UID}],
        }],
        "providerArtifacts": [
            {"category": "database", "provider": "fixture", "resourceId": "legacy-db", "state": "absent",
             "absenceEvidenceSha256": "1" * 64},
            {"category": "wal", "provider": "fixture", "resourceId": "legacy-wal", "state": "absent",
             "absenceEvidenceSha256": "2" * 64},
            {"category": "backup", "provider": "fixture", "resourceId": "legacy-backup", "state": "absent",
             "absenceEvidenceSha256": "3" * 64},
        ],
    }


def environment(root: Path):
    kubeconfig = root / "kubeconfig"
    kubeconfig.write_text("fixture", encoding="utf-8")
    return {
        "APP_ENV": "rehearsal",
        "MNEMA_PURGE_DISPOSABLE_TARGET": "true",
        "MNEMA_PURGE_POSTGRES_HOST": "127.0.0.1",
        "MNEMA_PURGE_POSTGRES_PORT": "5432",
        "MNEMA_PURGE_POSTGRES_USERNAME": "postgres",
        "MNEMA_PURGE_POSTGRES_PASSWORD": "private-postgres-password",
        "MNEMA_PURGE_POSTGRES_DATABASE": "legacy",
        "MNEMA_PURGE_REDIS_HOST": "127.0.0.1",
        "MNEMA_PURGE_REDIS_PORT": "6379",
        "MNEMA_PURGE_REDIS_PASSWORD": "private-redis-password",
        "MNEMA_PURGE_S3_ENDPOINT": "http://127.0.0.1:19000",
        "MNEMA_PURGE_KUBECONFIG": str(kubeconfig),
        "AWS_ACCESS_KEY_ID": "fixture-access-key",
        "AWS_SECRET_ACCESS_KEY": "fixture-secret-key",
    }


class PurgeRehearsalTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.manifest_path = self.root / "manifest.json"
        self.manifest_path.write_text(json.dumps(manifest()), encoding="utf-8")
        self.environment = environment(self.root)
        self.runner = FixtureRunner()

    def tearDown(self):
        self.temporary.cleanup()

    def test_preflight_purge_verify_and_rerun_preserve_neighbours(self):
        plan = self.root / "plan.json"
        preflight_evidence = self.root / "preflight.json"
        purge.preflight(self.manifest_path, plan, preflight_evidence, self.environment, self.runner)
        public = preflight_evidence.read_text(encoding="utf-8")
        self.assertNotIn("legacy_auth", public)
        self.assertNotIn("legacy:session", public)
        self.assertNotIn("legacy/content", public)
        self.assertNotIn("legacy-worker", public)
        self.assertNotIn("private-", public)
        self.assertEqual(0o600, plan.stat().st_mode & 0o777)
        self.assertEqual(0o600, preflight_evidence.stat().st_mode & 0o777)
        categories = json.loads(public)["categories"]
        self.assertEqual(1, categories["database"])
        self.assertEqual(1, categories["schema"])
        self.assertEqual(1, categories["redisKey"])
        self.assertEqual(1, categories["s3ObjectVersion"])
        self.assertEqual(1, categories["s3DeleteMarker"])
        self.assertEqual(1, categories["s3MultipartUpload"])
        self.assertEqual(1, categories["deployable"])
        self.assertEqual(2, categories["wal"])
        self.assertEqual(2, categories["backup"])
        self.assertEqual(1, categories["route"])
        self.assertEqual(1, categories["credential"])
        self.assertEqual(1, categories["pvc"])

        journal = self.root / "journal.json"
        purge_evidence = self.root / "purged.json"
        purge.purge(self.manifest_path, plan, journal, purge_evidence,
                    "first-delete-roll-forward-only", self.environment, self.runner)
        self.assertEqual({"account_only": "postgres"}, self.runner.schemas)
        self.assertEqual({"fresh:account", purge.OWNERSHIP_KEY}, self.runner.redis)
        self.assertEqual({("fresh/avatar", "fresh-version", 5, "fresh-etag")}, self.runner.versions)
        self.assertFalse(self.runner.markers)
        self.assertFalse(self.runner.uploads)
        self.assertEqual({("Deployment", "identity-account", FRESH_DEPLOYMENT_UID)}, self.runner.kubernetes)
        self.assertEqual("purged-and-verified", json.loads(journal.read_text())["status"])

        verify_evidence = self.root / "verified.json"
        purge.verify(self.manifest_path, verify_evidence, self.environment, self.runner)
        second_plan = self.root / "second-plan.json"
        purge.preflight(self.manifest_path, second_plan, self.root / "second-preflight.json",
                        self.environment, self.runner)
        purge.purge(self.manifest_path, second_plan, self.root / "second-journal.json",
                    self.root / "second-purge.json", "first-delete-roll-forward-only",
                    self.environment, self.runner)

    def test_unknown_neighbour_and_object_lock_stop_before_plan(self):
        self.runner.redis.add("neighbour:unknown")
        with self.assertRaisesRegex(purge.PurgeError, "unknown_redis_key"):
            purge.preflight(self.manifest_path, self.root / "plan.json", self.root / "evidence.json",
                            self.environment, self.runner)
        self.assertFalse((self.root / "plan.json").exists())

        self.runner.redis.remove("neighbour:unknown")
        self.runner.object_lock = True
        with self.assertRaisesRegex(purge.PurgeError, "object_lock_enabled"):
            purge.preflight(self.manifest_path, self.root / "plan.json", self.root / "evidence.json",
                            self.environment, self.runner)
        self.assertFalse((self.root / "plan.json").exists())

        self.runner.object_lock = False
        self.runner.kubernetes.add(("Job", "unknown-neighbour", "00000000-0000-4000-8000-000000000099"))
        with self.assertRaisesRegex(purge.PurgeError, "unknown_kubernetes_resource"):
            purge.preflight(self.manifest_path, self.root / "plan.json", self.root / "evidence.json",
                            self.environment, self.runner)
        self.assertFalse((self.root / "plan.json").exists())

    def test_postgres_cascade_crossing_preserved_schema_stops_before_plan(self):
        self.runner.cascade_crosses_preserve_boundary = True
        with self.assertRaisesRegex(purge.PurgeError, "postgres_cascade_crosses_preserve_boundary"):
            purge.preflight(self.manifest_path, self.root / "plan.json", self.root / "evidence.json",
                            self.environment, self.runner)
        self.assertFalse((self.root / "plan.json").exists())

    def test_partial_purge_resumes_only_with_bound_journal(self):
        document = manifest()
        document["redis"][0]["deleteKeys"].append("legacy:late-arrival")
        self.manifest_path.write_text(json.dumps(document), encoding="utf-8")
        plan_path = self.root / "plan.json"
        purge.preflight(self.manifest_path, plan_path, self.root / "preflight.json",
                        self.environment, self.runner)
        planned = purge.load_json(plan_path)
        self.runner.schemas.pop("legacy_auth")
        self.runner.redis.discard("legacy:session")
        journal_path = self.root / "journal.json"
        purge.private_write(journal_path, {
            "schemaVersion": 1,
            "kind": "mnema-no-snapshot-purge-journal",
            "targetId": TARGET_ID,
            "manifestSha256": planned["manifestSha256"],
            "status": "point-of-no-return-entered",
            "rollback": "roll-forward-only",
        })
        self.runner.redis.add("legacy:late-arrival")
        with self.assertRaisesRegex(purge.PurgeError, "inventory_outside_roll_forward_plan"):
            purge.purge(self.manifest_path, plan_path, journal_path, self.root / "rejected.json",
                        "first-delete-roll-forward-only", self.environment, self.runner)
        self.runner.redis.remove("legacy:late-arrival")
        purge.purge(self.manifest_path, plan_path, journal_path, self.root / "purged.json",
                    "first-delete-roll-forward-only", self.environment, self.runner)
        self.assertFalse(self.runner.uploads)
        self.assertEqual({("fresh/avatar", "fresh-version", 5, "fresh-etag")}, self.runner.versions)
        self.assertEqual("purged-and-verified", purge.load_json(journal_path)["status"])

    def test_non_rehearsal_missing_ack_and_changed_inventory_fail_closed(self):
        for app_env in ("", "dev", "staging", "prod", "production"):
            denied = dict(self.environment, APP_ENV=app_env)
            with self.assertRaisesRegex(purge.PurgeError, "rehearsal_required"):
                purge.preflight(self.manifest_path, self.root / f"{app_env or 'missing'}-plan.json",
                                self.root / f"{app_env or 'missing'}-evidence.json", denied, self.runner)

        plan = self.root / "plan.json"
        purge.preflight(self.manifest_path, plan, self.root / "preflight.json", self.environment, self.runner)
        with self.assertRaisesRegex(purge.PurgeError, "irreversible_acknowledgement_required"):
            purge.purge(self.manifest_path, plan, self.root / "journal.json", self.root / "purged.json",
                        "yes", self.environment, self.runner)
        self.runner.redis.add("neighbour:appeared")
        with self.assertRaisesRegex(purge.PurgeError, "unknown_redis_key"):
            purge.purge(self.manifest_path, plan, self.root / "journal.json", self.root / "purged.json",
                        "first-delete-roll-forward-only", self.environment, self.runner)
        self.assertIn("legacy:session", self.runner.redis)

    def test_manifest_rejects_broad_provider_artifact_and_duplicate_fields(self):
        document = manifest()
        document["providerArtifacts"][0]["state"] = "present"
        self.manifest_path.write_text(json.dumps(document), encoding="utf-8")
        with self.assertRaisesRegex(purge.PurgeError, "provider_artifact_not_absent"):
            purge.validate_manifest(purge.load_json(self.manifest_path))

        self.manifest_path.write_text('{"schemaVersion":1,"schemaVersion":1}', encoding="utf-8")
        with self.assertRaisesRegex(purge.PurgeError, "duplicate_json_key"):
            purge.load_json(self.manifest_path)

    def test_provider_command_environments_are_allowlisted(self):
        source = dict(self.environment, AWS_PROFILE="production", REDISCLI_AUTH="wrong",
                      PGSERVICE="production", AWS_SESSION_TOKEN="fixture-session")
        postgres = purge.provider_environment(source, "MNEMA_PURGE_POSTGRES", "postgres")
        redis = purge.provider_environment(source, "MNEMA_PURGE_REDIS", "redis")
        s3 = purge.aws_environment(source)
        for child in (postgres, redis, s3):
            self.assertNotIn("AWS_PROFILE", child)
            self.assertNotIn("PGSERVICE", child)
        self.assertEqual("private-redis-password", redis["REDISCLI_AUTH"])
        self.assertEqual("fixture-session", s3["AWS_SESSION_TOKEN"])
        self.assertNotIn("REDISCLI_AUTH", postgres)


if __name__ == "__main__":
    unittest.main()
