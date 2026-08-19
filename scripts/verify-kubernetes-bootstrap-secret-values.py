#!/usr/bin/env python3
"""Reject unsafe in-place changes to bootstrap-only Kubernetes Secret fields."""

from __future__ import annotations

import base64
import json
import os
import re
import subprocess
import sys


SAFE_NAME = re.compile(r"^[A-Z][A-Z0-9_]*$")


def main() -> int:
    if len(sys.argv) < 4:
        print(
            f"usage: {sys.argv[0]} NAMESPACE SECRET_NAME PROTECTED_KEY [PROTECTED_KEY ...]",
            file=sys.stderr,
        )
        return 64

    namespace, secret_name, *keys = sys.argv[1:]
    if len(keys) != len(set(keys)) or any(not SAFE_NAME.fullmatch(key) for key in keys):
        print("Protected Secret keys must be unique uppercase names", file=sys.stderr)
        return 64

    if any(not os.environ.get(key) for key in keys):
        print("Required desired bootstrap Secret values are missing", file=sys.stderr)
        return 1

    try:
        result = subprocess.run(
            [
                "kubectl",
                "get",
                "secret",
                secret_name,
                "-n",
                namespace,
                "--ignore-not-found=true",
                "-o",
                "json",
            ],
            check=True,
            capture_output=True,
            text=True,
        )
    except (OSError, subprocess.CalledProcessError):
        print("Unable to read the live Kubernetes Secret", file=sys.stderr)
        return 2

    try:
        live = json.loads(result.stdout) if result.stdout.strip() else None
    except json.JSONDecodeError:
        print("The Kubernetes API returned invalid Secret metadata", file=sys.stderr)
        return 2

    if live is None or not live.get("data"):
        print("initial")
        return 0
    if live.get("type") != "Opaque":
        print("The live application Secret is not Opaque", file=sys.stderr)
        return 1

    data = live.get("data", {})
    missing_live = sorted(key for key in keys if key not in data)
    if missing_live:
        print(
            "The initialized Secret is missing protected bootstrap keys: "
            + ", ".join(missing_live),
            file=sys.stderr,
        )
        return 1

    changed = sorted(
        key
        for key in keys
        if data[key] != base64.b64encode(os.environ[key].encode()).decode("ascii")
    )
    if changed:
        print(
            "Protected bootstrap values require a documented two-phase migration: "
            + ", ".join(changed),
            file=sys.stderr,
        )
        return 1

    print("unchanged")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
