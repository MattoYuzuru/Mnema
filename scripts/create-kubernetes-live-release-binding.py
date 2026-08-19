#!/usr/bin/env python3
"""Bind an approved release to exact live Secret and reconciliation state."""

from __future__ import annotations

import hashlib
import hmac
import json
import os
import re
import subprocess
import sys


SAFE_RESOURCE = re.compile(r"^[a-z0-9]([-a-z0-9.]*[a-z0-9])?/[a-z0-9]([-a-z0-9.]*[a-z0-9])?$")


def get_resource(kind: str, resource: str, allow_missing: bool) -> dict[str, object]:
    namespace, name = resource.split("/", 1)
    try:
        result = subprocess.run(
            [
                "kubectl",
                "-n",
                namespace,
                "get",
                kind,
                name,
                "--ignore-not-found=true",
                "-o",
                "json",
            ],
            check=True,
            capture_output=True,
            text=True,
        )
    except (OSError, subprocess.CalledProcessError):
        raise RuntimeError("Unable to read the live Kubernetes release state") from None

    try:
        value = json.loads(result.stdout) if result.stdout.strip() else None
    except json.JSONDecodeError:
        raise RuntimeError("The Kubernetes API returned invalid release state") from None
    if value is None:
        if allow_missing:
            return {"kind": kind, "namespace": namespace, "name": name, "present": False}
        raise RuntimeError("A required live Kubernetes Secret is missing")

    metadata = value.get("metadata", {})
    if metadata.get("namespace") not in (None, namespace) or metadata.get("name") != name:
        raise RuntimeError("The Kubernetes API returned a different release resource")
    resource_version = metadata.get("resourceVersion")
    uid = metadata.get("uid")
    if not resource_version or not uid:
        raise RuntimeError("The live Kubernetes release resource has no stable identity")

    if kind == "secret":
        if value.get("type") != "Opaque" or not isinstance(value.get("data"), dict):
            raise RuntimeError("A required live Kubernetes Secret is not initialized Opaque data")
        content: object = {"type": value["type"], "data": value["data"]}
    else:
        content = {"data": value.get("data", {})}

    return {
        "kind": kind,
        "namespace": namespace,
        "name": name,
        "present": True,
        "uid": uid,
        "resourceVersion": resource_version,
        "content": content,
    }


def main() -> int:
    if len(sys.argv) != 5:
        print(
            f"usage: {sys.argv[0]} CONTEXT APP_SECRET GRAFANA_SECRET RECONCILIATION_CONFIGMAP",
            file=sys.stderr,
        )
        return 64

    context, app_secret, grafana_secret, reconciliation = sys.argv[1:]
    if not context or any(
        not SAFE_RESOURCE.fullmatch(value)
        for value in (app_secret, grafana_secret, reconciliation)
    ):
        print("Context and namespace/name resource identities are required", file=sys.stderr)
        return 64

    binding_key = os.environ.get("SECRET_SNAPSHOT_BINDING_KEY", "")
    if len(binding_key.encode("utf-8")) < 32:
        print("SECRET_SNAPSHOT_BINDING_KEY must contain at least 32 bytes", file=sys.stderr)
        return 1

    try:
        resources = [
            get_resource("secret", app_secret, False),
            get_resource("secret", grafana_secret, False),
            get_resource("configmap", reconciliation, True),
        ]
    except RuntimeError as error:
        print(str(error), file=sys.stderr)
        return 2

    payload = json.dumps(
        {"context": context, "resources": resources},
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")
    digest = hmac.new(binding_key.encode("utf-8"), payload, hashlib.sha256).hexdigest()
    resource_versions = {
        f"{resource['namespace']}/{resource['name']}": resource["resourceVersion"]
        for resource in resources
        if resource["present"]
    }
    print(
        json.dumps(
            {"hmac": digest, "resourceVersions": resource_versions},
            separators=(",", ":"),
            sort_keys=True,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
