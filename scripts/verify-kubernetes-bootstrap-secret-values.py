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
    if len(sys.argv) < 5:
        print(
            f"usage: {sys.argv[0]} MODE NAMESPACE SECRET_NAME PROTECTED_KEY [PROTECTED_KEY ...]",
            file=sys.stderr,
        )
        return 64

    mode, namespace, secret_name, *keys = sys.argv[1:]
    if mode not in {"production", "staging"}:
        print("MODE must be production or staging", file=sys.stderr)
        return 64
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

    if live is None:
        print("The required Kubernetes application Secret is missing", file=sys.stderr)
        return 1
    if live.get("type") != "Opaque":
        print("The live application Secret is not Opaque", file=sys.stderr)
        return 1

    if not live.get("data"):
        if mode == "production":
            print("The production application Secret is missing initialized data", file=sys.stderr)
            return 1
        annotations = live.get("metadata", {}).get("annotations", {})
        if annotations.get("mnema.app/bootstrap-state") != "uninitialized":
            print("The empty staging Secret lacks the uninitialized bootstrap marker", file=sys.stderr)
            return 1
        try:
            for resource in ("statefulsets.apps", "persistentvolumeclaims"):
                result = subprocess.run(
                    ["kubectl", "-n", namespace, "get", resource, "-o", "json"],
                    check=True,
                    capture_output=True,
                    text=True,
                )
                inventory = json.loads(result.stdout)
                if inventory.get("items"):
                    print(
                        "Empty staging Secret cannot initialize after durable resources exist",
                        file=sys.stderr,
                    )
                    return 1
        except (OSError, subprocess.CalledProcessError, json.JSONDecodeError):
            print("Unable to prove that staging has no durable data", file=sys.stderr)
            return 2
        print("initial")
        return 0

    if mode == "staging" and live.get("metadata", {}).get("annotations", {}).get(
        "mnema.app/bootstrap-state"
    ) != "initialized":
        print("The initialized staging Secret lacks its durable bootstrap marker", file=sys.stderr)
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
