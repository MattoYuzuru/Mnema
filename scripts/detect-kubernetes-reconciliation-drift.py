#!/usr/bin/env python3
"""Report whether a non-secret reconciliation generation marker is stale."""

from __future__ import annotations

import json
import re
import subprocess
import sys


SAFE_KEY = re.compile(r"^[A-Za-z][A-Za-z0-9._-]*$")


def main() -> int:
    if len(sys.argv) != 5:
        print(f"usage: {sys.argv[0]} NAMESPACE CONFIGMAP KEY EXPECTED", file=sys.stderr)
        return 64
    namespace, name, key, expected = sys.argv[1:]
    if not namespace or not name or not SAFE_KEY.fullmatch(key) or not expected:
        print("A valid ConfigMap identity, key and expected generation are required", file=sys.stderr)
        return 64
    try:
        result = subprocess.run(
            [
                "kubectl",
                "-n",
                namespace,
                "get",
                "configmap",
                name,
                "--ignore-not-found=true",
                "-o",
                "json",
            ],
            check=True,
            capture_output=True,
            text=True,
        )
        current = json.loads(result.stdout) if result.stdout.strip() else None
    except (OSError, subprocess.CalledProcessError, json.JSONDecodeError):
        print("Unable to read the Kubernetes reconciliation marker", file=sys.stderr)
        return 2
    print("true" if current is None or current.get("data", {}).get(key) != expected else "false")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
