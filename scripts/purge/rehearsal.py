#!/usr/bin/env python3
"""Manifest-bound, rehearsal-only purge orchestration.

The private manifest and plan may contain exact resource locators. Public evidence
contains only category counts and hashes. Every provider inventory is compared
with the manifest before the first mutation; undeclared neighbours fail closed.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Any, Callable, Iterable


SCHEMA_VERSION = 1
KIND = "mnema-no-snapshot-purge"
POINT_OF_NO_RETURN = "first-delete-roll-forward-only"
OWNERSHIP_KEY = "mnema:rehearsal:target-id"
OWNERSHIP_LABEL = "mnema.app/rehearsal-target-id"
IDENTIFIER = re.compile(r"[a-z][a-z0-9_]{0,62}")
ENV_NAME = re.compile(r"MNEMA_PURGE_[A-Z0-9_]{1,80}")
UUID = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
HEX_64 = re.compile(r"[0-9a-f]{64}")
KUBERNETES_KINDS = {
    "Deployment": "deployable",
    "StatefulSet": "deployable",
    "CronJob": "deployable",
    "Job": "deployable",
    "Ingress": "route",
    "HTTPRoute": "route",
    "Secret": "credential",
    "PersistentVolumeClaim": "pvc",
}


class PurgeError(RuntimeError):
    def __init__(self, code: str):
        super().__init__(code)
        self.code = code


class Runner:
    def run(
        self,
        command: list[str],
        *,
        environment: dict[str, str] | None = None,
        input_text: str | None = None,
        allow_failure: bool = False,
    ) -> subprocess.CompletedProcess[str]:
        completed = subprocess.run(
            command,
            check=False,
            text=True,
            input=input_text,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            env=environment,
            timeout=60,
        )
        if completed.returncode != 0 and not allow_failure:
            raise PurgeError("provider_command_failed")
        return completed


def _pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise PurgeError("duplicate_json_key")
        result[key] = value
    return result


def load_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=_pairs)
    except PurgeError:
        raise
    except (OSError, UnicodeError, json.JSONDecodeError):
        raise PurgeError("invalid_json") from None
    require(isinstance(value, dict), "invalid_document")
    return value


def require(condition: bool, code: str) -> None:
    if not condition:
        raise PurgeError(code)


def exact(value: dict[str, Any], keys: set[str], code: str) -> None:
    require(set(value) == keys, code)


def string(value: Any, code: str, *, maximum: int = 512) -> str:
    require(isinstance(value, str) and value.strip() == value and 0 < len(value) <= maximum, code)
    require("\x00" not in value and "\n" not in value and "\r" not in value, code)
    return value


def env_name(value: Any) -> str:
    name = string(value, "invalid_environment_reference", maximum=96)
    require(ENV_NAME.fullmatch(name) is not None, "invalid_environment_reference")
    return name


def unique(values: Iterable[Any], key: Callable[[Any], Any], code: str) -> None:
    seen: set[Any] = set()
    for value in values:
        require(key(value) not in seen, code)
        seen.add(key(value))


def canonical(value: Any) -> bytes:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode()


def digest(value: Any) -> str:
    return hashlib.sha256(canonical(value)).hexdigest()


def validate_manifest(manifest: dict[str, Any]) -> dict[str, Any]:
    exact(
        manifest,
        {
            "schemaVersion",
            "kind",
            "targetId",
            "pointOfNoReturn",
            "postgres",
            "redis",
            "s3",
            "kubernetes",
            "providerArtifacts",
        },
        "manifest_fields_invalid",
    )
    require(manifest["schemaVersion"] == SCHEMA_VERSION, "manifest_version_invalid")
    require(manifest["kind"] == KIND, "manifest_kind_invalid")
    require(isinstance(manifest["targetId"], str) and UUID.fullmatch(manifest["targetId"]) is not None,
            "target_id_invalid")
    require(manifest["pointOfNoReturn"] == POINT_OF_NO_RETURN, "point_of_no_return_invalid")
    for name in ("postgres", "redis", "s3", "kubernetes", "providerArtifacts"):
        require(isinstance(manifest[name], list), "manifest_category_invalid")

    for target in manifest["postgres"]:
        exact(target, {"id", "envPrefix", "serverVersionNum", "deleteSchemas", "preserveSchemas"},
              "postgres_target_fields_invalid")
        string(target["id"], "target_label_invalid", maximum=80)
        prefix = string(target["envPrefix"], "invalid_environment_reference", maximum=80)
        env_name(prefix + "_HOST")
        require(isinstance(target["serverVersionNum"], int)
                and 100000 <= target["serverVersionNum"] <= 999999, "postgres_version_invalid")
        _validate_schemas(target["deleteSchemas"])
        _validate_schemas(target["preserveSchemas"])
        unique(target["deleteSchemas"] + target["preserveSchemas"], lambda item: item["name"],
               "duplicate_postgres_schema")

    for target in manifest["redis"]:
        exact(target, {"id", "envPrefix", "database", "deleteKeys", "preserveKeys"},
              "redis_target_fields_invalid")
        string(target["id"], "target_label_invalid", maximum=80)
        prefix = string(target["envPrefix"], "invalid_environment_reference", maximum=80)
        env_name(prefix + "_HOST")
        require(isinstance(target["database"], int) and 0 <= target["database"] <= 1024,
                "redis_database_invalid")
        require(isinstance(target["deleteKeys"], list) and isinstance(target["preserveKeys"], list),
                "redis_key_list_invalid")
        for key in target["deleteKeys"] + target["preserveKeys"]:
            string(key, "redis_key_invalid", maximum=1024)
        unique(target["deleteKeys"] + target["preserveKeys"], lambda value: value,
               "duplicate_redis_key")
        require(OWNERSHIP_KEY in target["preserveKeys"], "redis_ownership_key_missing")

    for target in manifest["s3"]:
        exact(target, {
            "id", "endpointEnv", "region", "bucket", "deleteVersions", "deleteMarkers",
            "multipartUploads", "preserveVersions", "preserveDeleteMarkers", "requireObjectLockDisabled",
        }, "s3_target_fields_invalid")
        string(target["id"], "target_label_invalid", maximum=80)
        env_name(target["endpointEnv"])
        string(target["region"], "s3_region_invalid", maximum=80)
        bucket = string(target["bucket"], "s3_bucket_invalid", maximum=63)
        require(re.fullmatch(r"[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]", bucket) is not None,
                "s3_bucket_invalid")
        require(target["requireObjectLockDisabled"] is True, "object_lock_policy_invalid")
        _validate_s3_versions(target["deleteVersions"])
        _validate_s3_versions(target["preserveVersions"])
        _validate_s3_markers(target["deleteMarkers"])
        _validate_s3_markers(target["preserveDeleteMarkers"])
        _validate_s3_uploads(target["multipartUploads"])
        unique(target["deleteVersions"] + target["preserveVersions"],
               lambda item: (item["key"], item["versionId"]), "duplicate_s3_version")
        unique(target["deleteMarkers"] + target["preserveDeleteMarkers"],
               lambda item: (item["key"], item["versionId"]), "duplicate_s3_delete_marker")
        unique(target["multipartUploads"], lambda item: (item["key"], item["uploadId"]),
               "duplicate_s3_multipart")

    for target in manifest["kubernetes"]:
        exact(target, {"id", "kubeconfigEnv", "context", "namespace", "inventoryKinds", "delete", "preserve"},
              "kubernetes_target_fields_invalid")
        string(target["id"], "target_label_invalid", maximum=80)
        env_name(target["kubeconfigEnv"])
        string(target["context"], "kubernetes_context_invalid", maximum=253)
        string(target["namespace"], "kubernetes_namespace_invalid", maximum=63)
        require(isinstance(target["inventoryKinds"], list)
                and all(isinstance(kind, str) for kind in target["inventoryKinds"])
                and set(target["inventoryKinds"]) == set(KUBERNETES_KINDS)
                and len(target["inventoryKinds"]) == len(KUBERNETES_KINDS),
                "kubernetes_inventory_kinds_invalid")
        _validate_kubernetes(target["delete"])
        _validate_kubernetes(target["preserve"])
        unique(target["delete"] + target["preserve"],
               lambda item: (item["kind"], item["name"]), "duplicate_kubernetes_resource")

    for artifact in manifest["providerArtifacts"]:
        exact(artifact, {"category", "provider", "resourceId", "state", "absenceEvidenceSha256"},
              "provider_artifact_fields_invalid")
        require(artifact["category"] in {"database", "wal", "backup"},
                "provider_artifact_category_invalid")
        string(artifact["provider"], "provider_invalid", maximum=80)
        string(artifact["resourceId"], "provider_resource_id_invalid", maximum=160)
        require(artifact["state"] == "absent", "provider_artifact_not_absent")
        require(isinstance(artifact["absenceEvidenceSha256"], str)
                and HEX_64.fullmatch(artifact["absenceEvidenceSha256"]) is not None,
                "provider_absence_evidence_invalid")
    unique(manifest["providerArtifacts"], lambda item: (item["provider"], item["resourceId"]),
           "duplicate_provider_artifact")

    unique(
        manifest["postgres"] + manifest["redis"] + manifest["s3"] + manifest["kubernetes"],
        lambda item: item["id"],
        "duplicate_target_label",
    )
    return manifest


def _validate_schemas(values: Any) -> None:
    require(isinstance(values, list), "postgres_schema_list_invalid")
    for item in values:
        require(isinstance(item, dict), "postgres_schema_invalid")
        exact(item, {"name", "owner"}, "postgres_schema_fields_invalid")
        require(IDENTIFIER.fullmatch(string(item["name"], "postgres_schema_name_invalid")) is not None,
                "postgres_schema_name_invalid")
        require(IDENTIFIER.fullmatch(string(item["owner"], "postgres_owner_invalid")) is not None,
                "postgres_owner_invalid")


def _validate_s3_versions(values: Any) -> None:
    require(isinstance(values, list), "s3_version_list_invalid")
    for item in values:
        require(isinstance(item, dict), "s3_version_invalid")
        exact(item, {"category", "key", "versionId", "size", "etag"}, "s3_version_fields_invalid")
        require(item["category"] in {"object", "wal", "backup"}, "s3_version_category_invalid")
        string(item["key"], "s3_key_invalid", maximum=1024)
        string(item["versionId"], "s3_version_id_invalid", maximum=1024)
        require(isinstance(item["size"], int) and item["size"] >= 0, "s3_size_invalid")
        string(item["etag"], "s3_etag_invalid", maximum=160)


def _validate_s3_markers(values: Any) -> None:
    require(isinstance(values, list), "s3_marker_list_invalid")
    for item in values:
        require(isinstance(item, dict), "s3_marker_invalid")
        exact(item, {"key", "versionId"}, "s3_marker_fields_invalid")
        string(item["key"], "s3_key_invalid", maximum=1024)
        string(item["versionId"], "s3_version_id_invalid", maximum=1024)


def _validate_s3_uploads(values: Any) -> None:
    require(isinstance(values, list), "s3_multipart_list_invalid")
    for item in values:
        require(isinstance(item, dict), "s3_multipart_invalid")
        exact(item, {"key", "uploadId"}, "s3_multipart_fields_invalid")
        string(item["key"], "s3_key_invalid", maximum=1024)
        string(item["uploadId"], "s3_upload_id_invalid", maximum=2048)


def _validate_kubernetes(values: Any) -> None:
    require(isinstance(values, list), "kubernetes_resource_list_invalid")
    for item in values:
        require(isinstance(item, dict), "kubernetes_resource_invalid")
        exact(item, {"kind", "name", "uid"}, "kubernetes_resource_fields_invalid")
        require(item["kind"] in KUBERNETES_KINDS, "kubernetes_kind_invalid")
        string(item["name"], "kubernetes_name_invalid", maximum=253)
        require(isinstance(item["uid"], str) and UUID.fullmatch(item["uid"]) is not None,
                "kubernetes_uid_invalid")


def require_rehearsal(environment: dict[str, str]) -> None:
    require(environment.get("APP_ENV", "").strip().lower() == "rehearsal", "rehearsal_required")
    require(environment.get("MNEMA_PURGE_DISPOSABLE_TARGET") == "true", "disposable_target_required")


def required_environment(environment: dict[str, str], name: str) -> str:
    value = environment.get(name)
    require(value is not None and value.strip() == value and value != "", "configuration_missing")
    return value


def command_environment(environment: dict[str, str]) -> dict[str, str]:
    return {
        "PATH": environment.get("PATH", os.defpath),
        "LANG": "C",
        "LC_ALL": "C",
    }


def provider_environment(environment: dict[str, str], prefix: str, provider: str) -> dict[str, str]:
    child = command_environment(environment)
    if provider == "postgres":
        mapping = {
            "PGHOST": "_HOST", "PGPORT": "_PORT", "PGUSER": "_USERNAME",
            "PGPASSWORD": "_PASSWORD", "PGDATABASE": "_DATABASE",
        }
        for target, suffix in mapping.items():
            child[target] = required_environment(environment, prefix + suffix)
        child["PGCONNECT_TIMEOUT"] = "10"
    elif provider == "redis":
        password = environment.get(prefix + "_PASSWORD", "")
        if password:
            child["REDISCLI_AUTH"] = password
    return child


def aws_environment(environment: dict[str, str]) -> dict[str, str]:
    child = command_environment(environment)
    child["AWS_ACCESS_KEY_ID"] = required_environment(environment, "AWS_ACCESS_KEY_ID")
    child["AWS_SECRET_ACCESS_KEY"] = required_environment(environment, "AWS_SECRET_ACCESS_KEY")
    session_token = environment.get("AWS_SESSION_TOKEN", "")
    if session_token:
        child["AWS_SESSION_TOKEN"] = session_token
    ca_bundle = environment.get("AWS_CA_BUNDLE", "")
    if ca_bundle:
        child["AWS_CA_BUNDLE"] = ca_bundle
    child["AWS_EC2_METADATA_DISABLED"] = "true"
    child["AWS_PAGER"] = ""
    return child


def postgres_preserved_objects(
    schemas: list[str],
    child: dict[str, str],
    runner: Runner,
    *,
    simulated_drops: list[str] | None = None,
) -> list[str]:
    literals = ",".join(f"'{schema}'" for schema in schemas)
    if schemas:
        object_query = f"""
SELECT object_identity FROM (
  SELECT json_build_array('relation',n.nspname,c.relkind::text,c.relname)::text AS object_identity
    FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
   WHERE n.nspname IN ({literals})
  UNION ALL
  SELECT json_build_array('routine',n.nspname,p.prokind::text,p.proname,
                          pg_get_function_identity_arguments(p.oid))::text
    FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
   WHERE n.nspname IN ({literals})
  UNION ALL
  SELECT json_build_array('type',n.nspname,t.typtype::text,t.typname)::text
    FROM pg_type t JOIN pg_namespace n ON n.oid=t.typnamespace
   WHERE n.nspname IN ({literals})
  UNION ALL
  SELECT json_build_array('constraint',n.nspname,c.contype::text,c.conname)::text
    FROM pg_constraint c JOIN pg_namespace n ON n.oid=c.connamespace
   WHERE n.nspname IN ({literals})
  UNION ALL
  SELECT json_build_array('trigger',n.nspname,c.relname,t.tgname)::text
    FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid
         JOIN pg_namespace n ON n.oid=c.relnamespace
   WHERE n.nspname IN ({literals}) AND NOT t.tgisinternal
  UNION ALL
  SELECT json_build_array('rule',n.nspname,c.relname,r.rulename)::text
    FROM pg_rewrite r JOIN pg_class c ON c.oid=r.ev_class
         JOIN pg_namespace n ON n.oid=c.relnamespace
   WHERE n.nspname IN ({literals}) AND r.rulename<>'_RETURN'
  UNION ALL
  SELECT json_build_array('policy',n.nspname,c.relname,p.polname)::text
    FROM pg_policy p JOIN pg_class c ON c.oid=p.polrelid
         JOIN pg_namespace n ON n.oid=c.relnamespace
   WHERE n.nspname IN ({literals})
  UNION ALL
  SELECT json_build_array('default',n.nspname,c.relname,a.attname,
                          pg_get_expr(d.adbin,d.adrelid))::text
    FROM pg_attrdef d JOIN pg_class c ON c.oid=d.adrelid
         JOIN pg_namespace n ON n.oid=c.relnamespace
         JOIN pg_attribute a ON a.attrelid=d.adrelid AND a.attnum=d.adnum
   WHERE n.nspname IN ({literals})
) preserved_objects ORDER BY object_identity
""".strip()
    else:
        object_query = "SELECT NULL::text WHERE false"
    statements: list[str] = []
    if simulated_drops is not None:
        statements.extend(["BEGIN", "SET LOCAL client_min_messages=warning"])
        statements.extend(f'DROP SCHEMA IF EXISTS "{schema}" CASCADE' for schema in simulated_drops)
    statements.append(object_query)
    if simulated_drops is not None:
        statements.append("ROLLBACK")
    completed = runner.run([
        "psql", "-XAtq", "-v", "ON_ERROR_STOP=1", "-c", ";".join(statements),
    ], environment=child)
    return sorted(completed.stdout.splitlines())


def postgres_inventory(target: dict[str, Any], target_id: str,
                       environment: dict[str, str], runner: Runner) -> dict[str, Any]:
    prefix = target["envPrefix"]
    child = provider_environment(environment, prefix, "postgres")
    version = runner.run(["psql", "-XAt", "-v", "ON_ERROR_STOP=1", "-c", "SHOW server_version_num"],
                         environment=child).stdout.strip()
    require(version.isdigit() and int(version) == target["serverVersionNum"], "postgres_version_mismatch")
    ownership = runner.run([
        "psql", "-XAt", "-v", "ON_ERROR_STOP=1", "-c",
        "SELECT COALESCE(obj_description(oid,'pg_database'),'') FROM pg_database WHERE datname=current_database()",
    ], environment=child).stdout.strip()
    require(ownership == f"mnema-rehearsal:{target_id}", "postgres_ownership_mismatch")
    rows = runner.run([
        "psql", "-XAt", "-v", "ON_ERROR_STOP=1", "-F", "|", "-c",
        "SELECT nspname,pg_get_userbyid(nspowner) FROM pg_namespace "
        "WHERE nspname NOT LIKE 'pg_%' AND nspname<>'information_schema' ORDER BY 1",
    ], environment=child).stdout.splitlines()
    actual: dict[str, str] = {}
    for row in rows:
        parts = row.split("|")
        require(len(parts) == 2 and parts[0] not in actual, "postgres_inventory_invalid")
        actual[parts[0]] = parts[1]
    declared = {item["name"]: item["owner"] for item in target["deleteSchemas"] + target["preserveSchemas"]}
    require(set(actual).issubset(declared), "unknown_postgres_schema")
    for item in target["preserveSchemas"]:
        require(actual.get(item["name"]) == item["owner"], "preserved_postgres_schema_mismatch")
    for name, owner in actual.items():
        require(declared[name] == owner, "postgres_schema_owner_mismatch")
    deletable = sorted(item["name"] for item in target["deleteSchemas"] if item["name"] in actual)
    preserved_schemas = sorted(item["name"] for item in target["preserveSchemas"])
    preserved_objects = postgres_preserved_objects(preserved_schemas, child, runner)
    simulated_objects = postgres_preserved_objects(
        preserved_schemas, child, runner, simulated_drops=deletable,
    )
    require(simulated_objects == preserved_objects, "postgres_cascade_crosses_preserve_boundary")
    return {
        "id": target["id"],
        "delete": deletable,
        "preserve": preserved_schemas,
        "preservedObjects": {"count": len(preserved_objects), "sha256": digest(preserved_objects)},
    }


def redis_inventory(target: dict[str, Any], target_id: str,
                    environment: dict[str, str], runner: Runner) -> dict[str, Any]:
    prefix = target["envPrefix"]
    host = required_environment(environment, prefix + "_HOST")
    port = required_environment(environment, prefix + "_PORT")
    require(port.isdigit(), "redis_port_invalid")
    child = provider_environment(environment, prefix, "redis")
    ownership = runner.run(["redis-cli", "--raw", "-h", host, "-p", port, "-n",
                            str(target["database"]), "GET", OWNERSHIP_KEY], environment=child).stdout.rstrip("\n")
    require(ownership == target_id, "redis_ownership_mismatch")
    command = ["redis-cli", "--raw", "-h", host, "-p", port, "-n", str(target["database"]), "--scan"]
    actual = set(filter(None, runner.run(command, environment=child).stdout.splitlines()))
    delete = set(target["deleteKeys"])
    preserve = set(target["preserveKeys"])
    require(actual.issubset(delete | preserve), "unknown_redis_key")
    require(preserve.issubset(actual), "preserved_redis_key_missing")
    return {"id": target["id"], "delete": sorted(actual & delete), "preserve": sorted(preserve)}


def aws_command(target: dict[str, Any], environment: dict[str, str], *arguments: str) -> list[str]:
    endpoint = required_environment(environment, target["endpointEnv"])
    require(endpoint.startswith("http://127.0.0.1:") or endpoint.startswith("http://localhost:")
            or endpoint.startswith("https://"), "s3_endpoint_invalid")
    return ["aws", "--no-cli-pager", "--cli-connect-timeout", "10", "--cli-read-timeout", "30",
            "--endpoint-url", endpoint, "--region", target["region"], "s3api", *arguments]


def json_output(completed: subprocess.CompletedProcess[str], code: str) -> dict[str, Any]:
    try:
        result = json.loads(completed.stdout or "{}", object_pairs_hook=_pairs)
    except (json.JSONDecodeError, PurgeError):
        raise PurgeError(code) from None
    require(isinstance(result, dict), code)
    return result


def s3_inventory(target: dict[str, Any], target_id: str,
                 environment: dict[str, str], runner: Runner) -> dict[str, Any]:
    child = aws_environment(environment)
    tagging = json_output(runner.run(aws_command(target, environment, "get-bucket-tagging",
                                                 "--bucket", target["bucket"], "--output", "json"),
                                     environment=child),
                          "s3_ownership_inventory_invalid")
    tags = {item.get("Key"): item.get("Value") for item in tagging.get("TagSet", [])}
    require(tags.get("mnema-rehearsal-target-id") == target_id, "s3_ownership_mismatch")
    versioning = json_output(runner.run(aws_command(target, environment, "get-bucket-versioning",
                                                    "--bucket", target["bucket"], "--output", "json"),
                                        environment=child),
                             "s3_versioning_inventory_invalid")
    require(versioning.get("Status") == "Enabled", "s3_versioning_not_enabled")
    lock = runner.run(aws_command(target, environment, "get-object-lock-configuration", "--bucket",
                                  target["bucket"], "--output", "json"), environment=child,
                      allow_failure=True)
    if lock.returncode == 0:
        lock_value = json_output(lock, "object_lock_inventory_invalid")
        require(lock_value.get("ObjectLockConfiguration", {}).get("ObjectLockEnabled") != "Enabled",
                "object_lock_enabled")
    else:
        require("ObjectLockConfigurationNotFound" in lock.stderr,
                "object_lock_inventory_unavailable")

    listed = json_output(runner.run(aws_command(target, environment, "list-object-versions", "--bucket",
                                                target["bucket"], "--output", "json"),
                                    environment=child),
                         "s3_inventory_invalid")
    actual_versions = {
        (item["Key"], item["VersionId"], int(item["Size"]), str(item["ETag"]).strip('"'))
        for item in listed.get("Versions", [])
    }
    declared_versions = {
        (item["key"], item["versionId"], item["size"], item["etag"].strip('"'))
        for item in target["deleteVersions"] + target["preserveVersions"]
    }
    require(actual_versions.issubset(declared_versions), "unknown_s3_version")
    preserve_versions = {
        (item["key"], item["versionId"], item["size"], item["etag"].strip('"'))
        for item in target["preserveVersions"]
    }
    require(preserve_versions.issubset(actual_versions), "preserved_s3_version_missing")

    actual_markers = {(item["Key"], item["VersionId"]) for item in listed.get("DeleteMarkers", [])}
    declared_markers = {(item["key"], item["versionId"])
                        for item in target["deleteMarkers"] + target["preserveDeleteMarkers"]}
    preserve_markers = {(item["key"], item["versionId"]) for item in target["preserveDeleteMarkers"]}
    require(actual_markers.issubset(declared_markers), "unknown_s3_delete_marker")
    require(preserve_markers.issubset(actual_markers), "preserved_s3_delete_marker_missing")

    uploads = json_output(runner.run(aws_command(target, environment, "list-multipart-uploads", "--bucket",
                                                 target["bucket"], "--output", "json"),
                                     environment=child),
                          "s3_multipart_inventory_invalid")
    actual_uploads = {(item["Key"], item["UploadId"]) for item in uploads.get("Uploads", [])}
    declared_uploads = {(item["key"], item["uploadId"]) for item in target["multipartUploads"]}
    require(actual_uploads.issubset(declared_uploads), "unknown_s3_multipart")

    delete_versions = sorted(
        {value for value in actual_versions if value not in preserve_versions}, key=lambda value: (value[0], value[1]))
    return {
        "id": target["id"],
        "deleteVersions": [{"key": key, "versionId": version} for key, version, _, _ in delete_versions],
        "deleteMarkers": [{"key": key, "versionId": version}
                          for key, version in sorted(actual_markers - preserve_markers)],
        "multipartUploads": [{"key": key, "uploadId": upload}
                             for key, upload in sorted(actual_uploads)],
        "preserveVersionCount": len(preserve_versions),
        "preserveDeleteMarkerCount": len(preserve_markers),
    }


def kubernetes_inventory(target: dict[str, Any], target_id: str,
                         environment: dict[str, str], runner: Runner) -> dict[str, Any]:
    kubeconfig = required_environment(environment, target["kubeconfigEnv"])
    require(Path(kubeconfig).is_file(), "kubeconfig_missing")
    child = command_environment(environment)
    namespace = json_output(runner.run([
        "kubectl", "--kubeconfig", kubeconfig, "--context", target["context"],
        "get", "Namespace", target["namespace"], "-o", "json",
    ], environment=child), "kubernetes_namespace_inventory_invalid")
    labels = namespace.get("metadata", {}).get("labels", {})
    require(labels.get(OWNERSHIP_LABEL) == target_id, "kubernetes_ownership_mismatch")
    declared = target["delete"] + target["preserve"]
    delete_keys = {(item["kind"], item["name"], item["uid"]) for item in target["delete"]}
    preserve_keys = {(item["kind"], item["name"], item["uid"]) for item in target["preserve"]}
    actual: set[tuple[str, str, str]] = set()
    for kind in sorted(target["inventoryKinds"]):
        command = ["kubectl", "--kubeconfig", kubeconfig, "--context", target["context"], "-n",
                   target["namespace"], "get", kind, "-o", "json"]
        document = json_output(runner.run(command, environment=child), "kubernetes_inventory_invalid")
        for item in document.get("items", []):
            metadata = item.get("metadata", {})
            actual.add((kind, metadata.get("name", ""), metadata.get("uid", "")))
    require(actual.issubset(delete_keys | preserve_keys), "unknown_kubernetes_resource")
    require(preserve_keys.issubset(actual), "preserved_kubernetes_resource_missing")
    return {
        "id": target["id"],
        "delete": [{"kind": kind, "name": name, "uid": uid}
                   for kind, name, uid in sorted(actual & delete_keys)],
        "preserve": len(preserve_keys),
    }


def collect(manifest: dict[str, Any], environment: dict[str, str], runner: Runner) -> dict[str, Any]:
    return {
        "postgres": [postgres_inventory(target, manifest["targetId"], environment, runner)
                     for target in manifest["postgres"]],
        "redis": [redis_inventory(target, manifest["targetId"], environment, runner)
                  for target in manifest["redis"]],
        "s3": [s3_inventory(target, manifest["targetId"], environment, runner)
               for target in manifest["s3"]],
        "kubernetes": [kubernetes_inventory(target, manifest["targetId"], environment, runner)
                      for target in manifest["kubernetes"]],
    }


def category_counts(manifest: dict[str, Any], inventory: dict[str, Any]) -> dict[str, int]:
    counts = {name: 0 for name in (
        "database", "schema", "pvc", "wal", "backup", "redisKey", "s3ObjectVersion",
        "s3DeleteMarker", "s3MultipartUpload", "deployable", "route", "credential",
    )}
    counts["schema"] = sum(len(item["delete"]) for item in inventory["postgres"])
    counts["redisKey"] = sum(len(item["delete"]) for item in inventory["redis"])
    s3_targets = {target["id"]: target for target in manifest["s3"]}
    for item in inventory["s3"]:
        target = s3_targets[item["id"]]
        by_identity = {(entry["key"], entry["versionId"]): entry["category"]
                       for entry in target["deleteVersions"]}
        for version in item["deleteVersions"]:
            category = by_identity[(version["key"], version["versionId"])]
            counts[{"object": "s3ObjectVersion", "wal": "wal", "backup": "backup"}[category]] += 1
        counts["s3DeleteMarker"] += len(item["deleteMarkers"])
        counts["s3MultipartUpload"] += len(item["multipartUploads"])
    kubernetes_targets = {target["id"]: target for target in manifest["kubernetes"]}
    for item in inventory["kubernetes"]:
        target = kubernetes_targets[item["id"]]
        identities = {(entry["kind"], entry["name"], entry["uid"]): entry["kind"]
                      for entry in target["delete"]}
        for resource in item["delete"]:
            counts[KUBERNETES_KINDS[identities[(resource["kind"], resource["name"], resource["uid"])]]] += 1
    for artifact in manifest["providerArtifacts"]:
        counts[artifact["category"]] += 1
    return counts


def private_write(path: Path, value: dict[str, Any], *, overwrite: bool = False) -> None:
    destination = path.absolute()
    require(destination.parent.is_dir(), "output_parent_missing")
    if not overwrite:
        require(not destination.exists(), "output_exists")
    descriptor, temporary_name = tempfile.mkstemp(prefix=".mnema-purge-", suffix=".json", dir=destination.parent)
    temporary = Path(temporary_name)
    try:
        os.fchmod(descriptor, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf-8") as output:
            json.dump(value, output, sort_keys=True, separators=(",", ":"))
            output.write("\n")
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, destination)
        directory_descriptor = os.open(destination.parent, os.O_RDONLY)
        try:
            os.fsync(directory_descriptor)
        finally:
            os.close(directory_descriptor)
    except Exception:
        temporary.unlink(missing_ok=True)
        raise


def evidence(status: str, manifest_sha: str, inventory: dict[str, Any], counts: dict[str, int]) -> dict[str, Any]:
    return {
        "schemaVersion": SCHEMA_VERSION,
        "kind": "mnema-no-snapshot-purge-evidence",
        "status": status,
        "manifestSha256": manifest_sha,
        "inventorySha256": digest(inventory),
        "categories": counts,
        "rollback": "roll-forward-only-after-first-delete",
    }


def preflight(manifest_path: Path, plan_path: Path, evidence_path: Path,
              environment: dict[str, str], runner: Runner) -> None:
    require_rehearsal(environment)
    manifest = validate_manifest(load_json(manifest_path))
    inventory = collect(manifest, environment, runner)
    manifest_sha = digest(manifest)
    plan = {
        "schemaVersion": SCHEMA_VERSION,
        "kind": "mnema-no-snapshot-purge-plan",
        "targetId": manifest["targetId"],
        "manifestSha256": manifest_sha,
        "inventorySha256": digest(inventory),
        "pointOfNoReturn": POINT_OF_NO_RETURN,
        "inventory": inventory,
    }
    private_write(plan_path, plan)
    private_write(evidence_path, evidence("preflighted", manifest_sha, inventory,
                                          category_counts(manifest, inventory)))


def validate_plan(manifest: dict[str, Any], plan: dict[str, Any]) -> dict[str, Any]:
    exact(plan, {"schemaVersion", "kind", "targetId", "manifestSha256", "inventorySha256",
                 "pointOfNoReturn", "inventory"}, "plan_fields_invalid")
    require(plan["schemaVersion"] == SCHEMA_VERSION and plan["kind"] == "mnema-no-snapshot-purge-plan",
            "plan_identity_invalid")
    require(plan["targetId"] == manifest["targetId"] and plan["manifestSha256"] == digest(manifest),
            "plan_manifest_mismatch")
    require(plan["pointOfNoReturn"] == POINT_OF_NO_RETURN, "plan_point_of_no_return_invalid")
    require(plan["inventorySha256"] == digest(plan["inventory"]), "plan_inventory_mismatch")
    return plan


def target_by_id(manifest: dict[str, Any], category: str) -> dict[str, dict[str, Any]]:
    return {item["id"]: item for item in manifest[category]}


def _item_set(items: list[Any]) -> set[bytes]:
    return {canonical(item) for item in items}


def roll_forward_inventory_matches(planned: dict[str, Any], current: dict[str, Any]) -> bool:
    if set(planned) != {"postgres", "redis", "s3", "kubernetes"} or set(current) != set(planned):
        return False
    for category in ("postgres", "redis", "kubernetes"):
        planned_targets = {item["id"]: item for item in planned[category]}
        current_targets = {item["id"]: item for item in current[category]}
        if set(planned_targets) != set(current_targets):
            return False
        for target_id, current_target in current_targets.items():
            planned_target = planned_targets[target_id]
            if not _item_set(current_target["delete"]) <= _item_set(planned_target["delete"]):
                return False
            if {key: value for key, value in current_target.items() if key != "delete"} != {
                key: value for key, value in planned_target.items() if key != "delete"
            }:
                return False
    planned_s3 = {item["id"]: item for item in planned["s3"]}
    current_s3 = {item["id"]: item for item in current["s3"]}
    if set(planned_s3) != set(current_s3):
        return False
    for target_id, current_target in current_s3.items():
        planned_target = planned_s3[target_id]
        for field in ("deleteVersions", "deleteMarkers", "multipartUploads"):
            if not _item_set(current_target[field]) <= _item_set(planned_target[field]):
                return False
        ignored = {"deleteVersions", "deleteMarkers", "multipartUploads"}
        if {key: value for key, value in current_target.items() if key not in ignored} != {
            key: value for key, value in planned_target.items() if key not in ignored
        }:
            return False
    return True


def validate_journal(journal: dict[str, Any], manifest: dict[str, Any]) -> None:
    exact(journal, {"schemaVersion", "kind", "targetId", "manifestSha256", "status", "rollback"},
          "journal_fields_invalid")
    require(journal["schemaVersion"] == SCHEMA_VERSION
            and journal["kind"] == "mnema-no-snapshot-purge-journal", "journal_identity_invalid")
    require(journal["targetId"] == manifest["targetId"]
            and journal["manifestSha256"] == digest(manifest), "journal_manifest_mismatch")
    require(journal["status"] in {"point-of-no-return-entered", "purged-and-verified"},
            "journal_status_invalid")
    require(journal["rollback"] == "roll-forward-only", "journal_rollback_invalid")


def purge(manifest_path: Path, plan_path: Path, journal_path: Path, evidence_path: Path, acknowledgement: str,
          environment: dict[str, str], runner: Runner) -> None:
    require_rehearsal(environment)
    require(acknowledgement == POINT_OF_NO_RETURN, "irreversible_acknowledgement_required")
    manifest = validate_manifest(load_json(manifest_path))
    plan = validate_plan(manifest, load_json(plan_path))
    current = collect(manifest, environment, runner)
    if journal_path.exists():
        validate_journal(load_json(journal_path), manifest)
        require(roll_forward_inventory_matches(plan["inventory"], current),
                "inventory_outside_roll_forward_plan")
    else:
        require(digest(current) == plan["inventorySha256"], "inventory_changed_after_preflight")
        private_write(journal_path, {
            "schemaVersion": SCHEMA_VERSION,
            "kind": "mnema-no-snapshot-purge-journal",
            "targetId": manifest["targetId"],
            "manifestSha256": digest(manifest),
            "status": "point-of-no-return-entered",
            "rollback": "roll-forward-only",
        })

    postgres_targets = target_by_id(manifest, "postgres")
    for inventory in plan["inventory"]["postgres"]:
        target = postgres_targets[inventory["id"]]
        child = provider_environment(environment, target["envPrefix"], "postgres")
        current_target = next(item for item in current["postgres"] if item["id"] == inventory["id"])
        for schema in current_target["delete"]:
            require(IDENTIFIER.fullmatch(schema) is not None, "postgres_schema_name_invalid")
            runner.run(["psql", "-XAt", "-v", "ON_ERROR_STOP=1", "-c",
                        f'DROP SCHEMA IF EXISTS "{schema}" CASCADE'], environment=child)

    redis_targets = target_by_id(manifest, "redis")
    for inventory in plan["inventory"]["redis"]:
        target = redis_targets[inventory["id"]]
        prefix = target["envPrefix"]
        child = provider_environment(environment, prefix, "redis")
        host = required_environment(environment, prefix + "_HOST")
        port = required_environment(environment, prefix + "_PORT")
        current_target = next(item for item in current["redis"] if item["id"] == inventory["id"])
        keys = current_target["delete"]
        for offset in range(0, len(keys), 100):
            runner.run(["redis-cli", "--raw", "-h", host, "-p", port, "-n", str(target["database"]),
                        "UNLINK", *keys[offset:offset + 100]], environment=child)

    s3_targets = target_by_id(manifest, "s3")
    for inventory in plan["inventory"]["s3"]:
        target = s3_targets[inventory["id"]]
        child = aws_environment(environment)
        current_target = next(item for item in current["s3"] if item["id"] == inventory["id"])
        for item in current_target["multipartUploads"]:
            runner.run(aws_command(target, environment, "abort-multipart-upload", "--bucket", target["bucket"],
                                   "--key", item["key"], "--upload-id", item["uploadId"]), environment=child)
        for item in current_target["deleteVersions"] + current_target["deleteMarkers"]:
            runner.run(aws_command(target, environment, "delete-object", "--bucket", target["bucket"],
                                   "--key", item["key"], "--version-id", item["versionId"]), environment=child)

    kubernetes_targets = target_by_id(manifest, "kubernetes")
    for inventory in plan["inventory"]["kubernetes"]:
        target = kubernetes_targets[inventory["id"]]
        kubeconfig = required_environment(environment, target["kubeconfigEnv"])
        child = command_environment(environment)
        current_target = next(item for item in current["kubernetes"] if item["id"] == inventory["id"])
        for item in current_target["delete"]:
            get = ["kubectl", "--kubeconfig", kubeconfig, "--context", target["context"], "-n",
                   target["namespace"], "get", item["kind"], item["name"], "-o", "json"]
            current_resource = json_output(runner.run(get, environment=child, allow_failure=True),
                                           "kubernetes_resource_invalid")
            if current_resource:
                require(current_resource.get("metadata", {}).get("uid") == item["uid"],
                        "kubernetes_uid_changed")
                runner.run(["kubectl", "--kubeconfig", kubeconfig, "--context", target["context"], "-n",
                            target["namespace"], "delete", item["kind"], item["name"], "--wait=true",
                            "--timeout=60s"], environment=child)

    after = collect(manifest, environment, runner)
    require(all(not item["delete"] for item in after["postgres"]), "postgres_absence_failed")
    before_postgres = {item["id"]: item["preservedObjects"] for item in plan["inventory"]["postgres"]}
    require(all(before_postgres.get(item["id"]) == item["preservedObjects"] for item in after["postgres"]),
            "postgres_preserved_objects_changed")
    require(all(not item["delete"] for item in after["redis"]), "redis_absence_failed")
    require(all(not item["deleteVersions"] and not item["deleteMarkers"] and not item["multipartUploads"]
                for item in after["s3"]), "s3_absence_failed")
    require(all(not item["delete"] for item in after["kubernetes"]), "kubernetes_absence_failed")
    counts = category_counts(manifest, plan["inventory"])
    private_write(evidence_path, evidence("purged-and-verified", digest(manifest), after, counts))
    private_write(journal_path, {
        "schemaVersion": SCHEMA_VERSION,
        "kind": "mnema-no-snapshot-purge-journal",
        "targetId": manifest["targetId"],
        "manifestSha256": digest(manifest),
        "status": "purged-and-verified",
        "rollback": "roll-forward-only",
    }, overwrite=True)


def verify(manifest_path: Path, evidence_path: Path, environment: dict[str, str], runner: Runner) -> None:
    require_rehearsal(environment)
    manifest = validate_manifest(load_json(manifest_path))
    inventory = collect(manifest, environment, runner)
    require(all(not item["delete"] for item in inventory["postgres"]), "postgres_absence_failed")
    require(all(not item["delete"] for item in inventory["redis"]), "redis_absence_failed")
    require(all(not item["deleteVersions"] and not item["deleteMarkers"] and not item["multipartUploads"]
                for item in inventory["s3"]), "s3_absence_failed")
    require(all(not item["delete"] for item in inventory["kubernetes"]), "kubernetes_absence_failed")
    private_write(evidence_path, evidence("absence-verified", digest(manifest), inventory,
                                          category_counts(manifest, inventory)))


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(add_help=True)
    commands = result.add_subparsers(dest="command", required=True)
    preflight_parser = commands.add_parser("preflight")
    preflight_parser.add_argument("--manifest", required=True, type=Path)
    preflight_parser.add_argument("--plan", required=True, type=Path)
    preflight_parser.add_argument("--evidence", required=True, type=Path)
    purge_parser = commands.add_parser("purge")
    purge_parser.add_argument("--manifest", required=True, type=Path)
    purge_parser.add_argument("--plan", required=True, type=Path)
    purge_parser.add_argument("--journal", required=True, type=Path)
    purge_parser.add_argument("--evidence", required=True, type=Path)
    purge_parser.add_argument("--ack", required=True)
    verify_parser = commands.add_parser("verify")
    verify_parser.add_argument("--manifest", required=True, type=Path)
    verify_parser.add_argument("--evidence", required=True, type=Path)
    return result


def main() -> int:
    arguments = parser().parse_args()
    try:
        if arguments.command == "preflight":
            preflight(arguments.manifest, arguments.plan, arguments.evidence, dict(os.environ), Runner())
        elif arguments.command == "purge":
            purge(arguments.manifest, arguments.plan, arguments.journal, arguments.evidence, arguments.ack,
                  dict(os.environ), Runner())
        else:
            verify(arguments.manifest, arguments.evidence, dict(os.environ), Runner())
        print(f"purge_status={arguments.command}_complete")
        return 0
    except PurgeError as failure:
        print(f"purge_error={failure.code}", file=sys.stderr)
        return 2
    except Exception:
        print("purge_error=internal_failure", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
