#!/usr/bin/env python3
"""Add an immutable fresh Identity signing set to the existing staging Secret."""

from __future__ import annotations

import argparse
import base64
import binascii
import json
import os
import re
import subprocess
import sys
from typing import Any


NAMESPACE = "mnema-staging"
SECRET = "mnema-secrets"
JWK_KEY = "IDENTITY_SIGNING_JWK_SET"
KID_KEY = "IDENTITY_SIGNING_ACTIVE_KID"
BASE64URL = re.compile(r"[A-Za-z0-9_-]+")
PRIVATE_RSA_PARAMETERS = ("d", "p", "q", "dp", "dq", "qi")


class BootstrapFailure(RuntimeError):
    pass


def rsa_parameter(key: dict[str, Any], name: str) -> bytes:
    value = key.get(name)
    if not isinstance(value, str) or not BASE64URL.fullmatch(value):
        raise ValueError
    try:
        return base64.b64decode(value + "=" * (-len(value) % 4), altchars=b"-_", validate=True)
    except binascii.Error:
        raise ValueError from None


def desired() -> dict[str, str]:
    raw = os.environ.get(JWK_KEY, "")
    kid = os.environ.get(KID_KEY, "")
    if not raw or not kid:
        raise BootstrapFailure("Fresh staging Identity signing inputs are required")
    try:
        document = json.loads(raw)
        keys = document["keys"]
        if not isinstance(keys, list) or not keys:
            raise ValueError
        ids: list[str] = []
        for key in keys:
            if (not isinstance(key, dict) or key.get("kty") != "RSA"
                    or key.get("use") not in (None, "sig") or key.get("alg") not in (None, "RS256")):
                raise ValueError
            key_id = key.get("kid")
            if not isinstance(key_id, str) or not key_id:
                raise ValueError
            modulus = rsa_parameter(key, "n")
            exponent = int.from_bytes(rsa_parameter(key, "e"))
            if int.from_bytes(modulus).bit_length() < 2048 or exponent < 3 or exponent % 2 == 0:
                raise ValueError
            ids.append(key_id)
        if len(ids) != len(set(ids)) or ids.count(kid) != 1:
            raise ValueError
        active = next(key for key in keys if key["kid"] == kid)
        for parameter in PRIVATE_RSA_PARAMETERS:
            rsa_parameter(active, parameter)
    except (KeyError, StopIteration, TypeError, ValueError, json.JSONDecodeError):
        raise BootstrapFailure("Fresh staging Identity JWKSet is invalid") from None
    canonical = json.dumps(document, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
    return {
        JWK_KEY: base64.b64encode(canonical.encode()).decode("ascii"),
        KID_KEY: base64.b64encode(kid.encode()).decode("ascii"),
    }


def kubectl(arguments: list[str], payload: dict[str, Any] | None = None) -> subprocess.CompletedProcess[str]:
    try:
        return subprocess.run(
            ["kubectl", "-n", NAMESPACE, *arguments],
            input=None if payload is None else json.dumps(payload, separators=(",", ":")),
            text=True,
            capture_output=True,
            check=False,
            timeout=30,
        )
    except (OSError, subprocess.TimeoutExpired):
        raise BootstrapFailure("Staging Kubernetes API is unavailable") from None


def read_secret() -> dict[str, Any]:
    result = kubectl(["get", "secret", SECRET, "-o", "json"])
    if result.returncode:
        raise BootstrapFailure("Unable to read the staging application Secret")
    try:
        secret = json.loads(result.stdout)
    except json.JSONDecodeError:
        raise BootstrapFailure("Staging application Secret state is invalid") from None
    metadata = secret.get("metadata", {})
    data = secret.get("data", {})
    annotations = metadata.get("annotations")
    if (secret.get("apiVersion") != "v1" or secret.get("kind") != "Secret" or secret.get("type") != "Opaque"
            or metadata.get("name") != SECRET or metadata.get("namespace") not in (None, NAMESPACE)
            or not metadata.get("uid") or not metadata.get("resourceVersion") or not isinstance(data, dict)
            or not isinstance(annotations, dict)
            or annotations.get("mnema.app/bootstrap-state") != "initialized"):
        raise BootstrapFailure("Staging application Secret identity is invalid")
    return secret


def run(dry_run: bool) -> int:
    expected = desired()
    current = read_secret()
    current_data = current["data"]
    present = {key for key in expected if key in current_data}
    if present:
        if present != set(expected) or any(current_data[key] != value for key, value in expected.items()):
            raise BootstrapFailure("Staging Identity signing state differs; use the explicit rotation procedure")
        print("identity_signing_secret=present")
        return 0

    metadata = current["metadata"]
    replacement = {
        "apiVersion": "v1",
        "kind": "Secret",
        "metadata": {
            "name": SECRET,
            "namespace": NAMESPACE,
            "uid": metadata["uid"],
            "resourceVersion": metadata["resourceVersion"],
            "labels": metadata.get("labels", {}),
            "annotations": metadata["annotations"],
        },
        "type": "Opaque",
        "data": {**current_data, **expected},
    }
    if "immutable" in current:
        replacement["immutable"] = current["immutable"]
    arguments = ["replace", "-f", "-"]
    if dry_run:
        arguments.extend(["--dry-run=server", "-o", "name"])
    result = kubectl(arguments, replacement)
    if result.returncode:
        raise BootstrapFailure("Staging Identity signing bootstrap was rejected")
    print("identity_signing_secret=previewed" if dry_run else "identity_signing_secret=initialized")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    try:
        return run(args.dry_run)
    except BootstrapFailure as error:
        print(str(error), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
