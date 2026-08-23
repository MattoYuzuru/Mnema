#!/usr/bin/env python3
"""Snapshot and conditionally restore one Kubernetes Secret without logging values."""

from __future__ import annotations

import base64
import json
import os
from pathlib import Path
import re
import subprocess
import sys


SAFE_RESOURCE = re.compile(r"^[a-z0-9]([-a-z0-9.]*[a-z0-9])?$")
SAFE_KEY = re.compile(r"^[A-Z][A-Z0-9_]*$")
MAX_SNAPSHOT_BYTES = 4 * 1024 * 1024


class SecretError(RuntimeError):
    pass


def read_live_secret(namespace: str, name: str) -> dict[str, object]:
    try:
        result = subprocess.run(
            ["kubectl", "-n", namespace, "get", "secret", name, "-o", "json"],
            check=True,
            capture_output=True,
            text=True,
        )
        value = json.loads(result.stdout)
    except (OSError, subprocess.CalledProcessError, json.JSONDecodeError):
        raise SecretError("Unable to read the Kubernetes Secret") from None
    validate_secret(value, namespace, name)
    return value


def validate_secret(value: object, namespace: str, name: str) -> None:
    if not isinstance(value, dict):
        raise SecretError("The Kubernetes API returned invalid Secret state")
    metadata = value.get("metadata")
    data = value.get("data")
    if (
        value.get("apiVersion") != "v1"
        or value.get("kind") != "Secret"
        or value.get("type") != "Opaque"
        or not isinstance(metadata, dict)
        or metadata.get("name") != name
        or metadata.get("namespace") not in (None, namespace)
        or not metadata.get("uid")
        or not metadata.get("resourceVersion")
        or not isinstance(data, dict)
    ):
        raise SecretError("The Kubernetes Secret has no stable Opaque identity")
    try:
        for key, encoded in data.items():
            if not isinstance(key, str) or not isinstance(encoded, str):
                raise ValueError
            base64.b64decode(encoded, validate=True)
    except (ValueError, TypeError):
        raise SecretError("The Kubernetes Secret contains invalid data") from None


def snapshot(namespace: str, name: str, expected_version: str, output: Path) -> int:
    current = read_live_secret(namespace, name)
    metadata = current["metadata"]
    assert isinstance(metadata, dict)
    resource_version = str(metadata["resourceVersion"])
    if expected_version != "-" and resource_version != expected_version:
        raise SecretError("The Kubernetes Secret changed before the rollback snapshot")
    retained_metadata = {
        key: metadata[key]
        for key in ("name", "namespace", "uid", "resourceVersion", "labels", "annotations")
        if key in metadata
    }
    retained = {
        "apiVersion": "v1",
        "kind": "Secret",
        "metadata": retained_metadata,
        "type": "Opaque",
        "data": current["data"],
    }
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    try:
        descriptor = os.open(output, flags, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf-8") as target:
            json.dump(retained, target, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
            target.write("\n")
    except OSError:
        raise SecretError("Unable to create the private rollback Secret snapshot") from None
    print(f"resource_version={resource_version}")
    return 0


def load_snapshot(path: Path, namespace: str, name: str) -> dict[str, object]:
    try:
        if path.is_symlink() or path.stat().st_size > MAX_SNAPSHOT_BYTES:
            raise SecretError("The rollback Secret snapshot is unsafe")
        with path.open(encoding="utf-8") as source:
            value = json.load(source)
    except (OSError, json.JSONDecodeError):
        raise SecretError("Unable to read the rollback Secret snapshot") from None
    validate_secret(value, namespace, name)
    return value


def restore(
    namespace: str,
    name: str,
    source: Path,
    preserve_current_keys: list[str],
    keys: list[str],
) -> int:
    if len(keys) != len(set(keys)) or any(not SAFE_KEY.fullmatch(key) for key in keys):
        raise SecretError("Candidate Secret keys must be unique uppercase names")
    if (
        len(preserve_current_keys) != len(set(preserve_current_keys))
        or any(not SAFE_KEY.fullmatch(key) for key in preserve_current_keys)
        or not set(preserve_current_keys).issubset(keys)
    ):
        raise SecretError("Preserved Secret keys must be a unique subset of candidate keys")
    if any(not os.environ.get(key) for key in keys):
        raise SecretError("Required candidate Secret values are missing")

    saved = load_snapshot(source, namespace, name)
    current = read_live_secret(namespace, name)
    saved_metadata = saved["metadata"]
    current_metadata = current["metadata"]
    assert isinstance(saved_metadata, dict) and isinstance(current_metadata, dict)
    if current_metadata["uid"] != saved_metadata["uid"]:
        raise SecretError("The Kubernetes Secret identity changed after the snapshot")

    expected_candidate = {
        key: base64.b64encode(os.environ[key].encode()).decode("ascii") for key in keys
    }
    if current["data"] != expected_candidate:
        raise SecretError("The Kubernetes Secret changed after candidate application")

    replacement_metadata: dict[str, object] = {
        "name": name,
        "namespace": namespace,
        "resourceVersion": current_metadata["resourceVersion"],
    }
    for field in ("labels", "finalizers", "ownerReferences"):
        if field in current_metadata:
            replacement_metadata[field] = current_metadata[field]
    if "annotations" in saved_metadata:
        replacement_metadata["annotations"] = saved_metadata["annotations"]
    restored_data = dict(saved["data"])
    for key in preserve_current_keys:
        restored_data[key] = expected_candidate[key]
    replacement = {
        "apiVersion": "v1",
        "kind": "Secret",
        "metadata": replacement_metadata,
        "type": "Opaque",
        "data": restored_data,
    }
    try:
        subprocess.run(
            ["kubectl", "replace", "-f", "-"],
            input=json.dumps(replacement, ensure_ascii=False, separators=(",", ":")),
            text=True,
            check=True,
            stdout=subprocess.DEVNULL,
        )
    except (OSError, subprocess.CalledProcessError):
        raise SecretError("Conditional Kubernetes Secret restoration failed") from None
    print("secret_restore=ok")
    return 0


def main() -> int:
    try:
        if len(sys.argv) == 6 and sys.argv[1] == "snapshot":
            _, _, namespace, name, expected_version, output = sys.argv
            if not SAFE_RESOURCE.fullmatch(namespace) or not SAFE_RESOURCE.fullmatch(name):
                raise SecretError("A stable Secret namespace and name are required")
            return snapshot(namespace, name, expected_version, Path(output))
        if len(sys.argv) >= 7 and sys.argv[1] == "restore":
            _, _, namespace, name, source, preserve_argument, *keys = sys.argv
            if not SAFE_RESOURCE.fullmatch(namespace) or not SAFE_RESOURCE.fullmatch(name):
                raise SecretError("A stable Secret namespace and name are required")
            preserve_current_keys = (
                [] if preserve_argument == "-" else preserve_argument.split(",")
            )
            return restore(
                namespace,
                name,
                Path(source),
                preserve_current_keys,
                keys,
            )
        print(
            f"usage: {sys.argv[0]} snapshot NAMESPACE SECRET EXPECTED_VERSION OUTPUT | "
            "restore NAMESPACE SECRET SNAPSHOT PRESERVE_CURRENT_KEYS DATA_KEY [DATA_KEY ...]",
            file=sys.stderr,
        )
        return 64
    except SecretError as error:
        print(str(error), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
