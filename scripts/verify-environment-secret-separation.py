#!/usr/bin/env python3
"""Fail on forbidden staging/production credential reuse without exposing values."""

from __future__ import annotations

import base64
import json
import os
import subprocess
import sys


FORBIDDEN_EQUAL_KEYS = (
    "AUTH_JWT_PUBLIC_KEY",
    "AUTH_JWT_PRIVATE_KEY",
    "TURNSTILE_SECRET_KEY",
    "GOOGLE_CLIENT_SECRET",
    "GITHUB_CLIENT_SECRET",
    "YANDEX_CLIENT_SECRET",
    "POSTGRES_PASSWORD",
    "AWS_ACCESS_KEY_ID",
    "AWS_SECRET_ACCESS_KEY",
    "AWS_BUCKET_NAME",
    "MEDIA_INTERNAL_TOKEN",
    "CORE_INTERNAL_TOKEN",
    "USER_INTERNAL_TOKEN",
)


def desired_values(prefix: str) -> dict[str, str]:
    values = {key: os.environ.get(f"{prefix}_{key}", "") for key in FORBIDDEN_EQUAL_KEYS}
    if any(not value for value in values.values()):
        raise ValueError(f"Required {prefix} comparison inputs are missing")
    return values


def live_values(namespace: str) -> dict[str, str]:
    try:
        result = subprocess.run(
            ["kubectl", "get", "secret", "mnema-secrets", "-n", namespace, "-o", "json"],
            check=True,
            capture_output=True,
            text=True,
        )
        data = json.loads(result.stdout).get("data", {})
        values = {
            key: base64.b64decode(data[key], validate=True).decode()
            for key in FORBIDDEN_EQUAL_KEYS
        }
    except (OSError, subprocess.CalledProcessError, json.JSONDecodeError, KeyError, ValueError, UnicodeDecodeError):
        raise ValueError(f"Unable to read required {namespace} Secret fields") from None
    if any(not value for value in values.values()):
        raise ValueError(f"Required {namespace} Secret fields are empty")
    return values


def main() -> int:
    if sys.argv[1:] == ["--desired"]:
        try:
            staging = desired_values("STAGING")
            production = desired_values("PROD")
        except ValueError as error:
            print(error, file=sys.stderr)
            return 1
    elif sys.argv[1:] == ["--live"]:
        try:
            staging = live_values("mnema-staging")
            production = live_values("prod")
        except ValueError as error:
            print(error, file=sys.stderr)
            return 1
    else:
        print(f"usage: {sys.argv[0]} --desired|--live", file=sys.stderr)
        return 64

    duplicates = [key for key in FORBIDDEN_EQUAL_KEYS if staging[key] == production[key]]
    if duplicates:
        for key in duplicates:
            print(f"forbidden_duplicate={key}", file=sys.stderr)
        return 1

    print("environment_secret_separation=ok")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
