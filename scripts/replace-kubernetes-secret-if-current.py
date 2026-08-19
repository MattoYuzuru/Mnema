#!/usr/bin/env python3
"""Replace one Secret only while its approved resourceVersion is current."""

from __future__ import annotations

import base64
import json
import os
import re
import subprocess
import sys


SAFE_NAME = re.compile(r"^[A-Z][A-Z0-9_]*$")


def main() -> int:
    if len(sys.argv) < 5:
        print(
            f"usage: {sys.argv[0]} NAMESPACE SECRET_NAME RESOURCE_VERSION DATA_KEY [DATA_KEY ...]",
            file=sys.stderr,
        )
        return 64
    namespace, secret_name, approved_version, *keys = sys.argv[1:]
    if not namespace or not secret_name or not approved_version or len(keys) != len(set(keys)) or any(
        not SAFE_NAME.fullmatch(key) for key in keys
    ):
        print("A stable Secret identity, version and unique uppercase keys are required", file=sys.stderr)
        return 64
    if any(not os.environ.get(key) for key in keys):
        print("Required desired Secret values are missing", file=sys.stderr)
        return 1

    try:
        current_result = subprocess.run(
            ["kubectl", "-n", namespace, "get", "secret", secret_name, "-o", "json"],
            check=True,
            capture_output=True,
            text=True,
        )
        current = json.loads(current_result.stdout)
    except (OSError, subprocess.CalledProcessError, json.JSONDecodeError):
        print("Unable to read the current Kubernetes Secret", file=sys.stderr)
        return 2

    metadata = current.get("metadata", {})
    if (
        metadata.get("name") != secret_name
        or metadata.get("namespace") not in (None, namespace)
        or metadata.get("resourceVersion") != approved_version
    ):
        print("The Kubernetes Secret changed after approval", file=sys.stderr)
        return 1

    replacement_metadata: dict[str, object] = {
        "name": secret_name,
        "namespace": namespace,
        "resourceVersion": approved_version,
    }
    for field in ("labels", "annotations", "finalizers", "ownerReferences"):
        if field in metadata:
            replacement_metadata[field] = metadata[field]
    replacement = {
        "apiVersion": "v1",
        "kind": "Secret",
        "metadata": replacement_metadata,
        "type": "Opaque",
        "data": {
            key: base64.b64encode(os.environ[key].encode()).decode("ascii") for key in keys
        },
    }
    try:
        subprocess.run(
            ["kubectl", "replace", "-f", "-"],
            input=json.dumps(replacement, separators=(",", ":")),
            text=True,
            check=True,
            stdout=subprocess.DEVNULL,
        )
    except (OSError, subprocess.CalledProcessError):
        print("Conditional Kubernetes Secret replacement failed", file=sys.stderr)
        return 2
    print("secret_replace=ok")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
