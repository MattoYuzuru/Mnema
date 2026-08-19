#!/usr/bin/env python3
"""Report Kubernetes Secret drift without emitting values or value-derived hashes."""

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
            f"usage: {sys.argv[0]} NAMESPACE SECRET_NAME DATA_KEY [DATA_KEY ...]",
            file=sys.stderr,
        )
        return 64

    namespace, secret_name, *keys = sys.argv[1:]
    if len(keys) != len(set(keys)) or any(not SAFE_NAME.fullmatch(key) for key in keys):
        print("Secret data keys must be unique uppercase environment names", file=sys.stderr)
        return 64

    missing = [key for key in keys if not os.environ.get(key)]
    if missing:
        print("Required desired Secret values are missing", file=sys.stderr)
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

    desired = {
        key: base64.b64encode(os.environ[key].encode()).decode("ascii") for key in keys
    }
    try:
        live = json.loads(result.stdout) if result.stdout.strip() else None
    except json.JSONDecodeError:
        print("The Kubernetes API returned invalid Secret metadata", file=sys.stderr)
        return 2

    drift = (
        live is None
        or live.get("type") != "Opaque"
        or live.get("data", {}) != desired
    )
    print("true" if drift else "false")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
