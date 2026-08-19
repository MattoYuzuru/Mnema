#!/usr/bin/env python3
"""Create a keyed, value-silent binding for an exact desired Secret snapshot."""

from __future__ import annotations

import hashlib
import hmac
import json
import os
import re
import sys


SAFE_NAME = re.compile(r"^[A-Z][A-Z0-9_]*$")


def main() -> int:
    if len(sys.argv) < 3:
        print(f"usage: {sys.argv[0]} CONTEXT DATA_KEY [DATA_KEY ...]", file=sys.stderr)
        return 64

    context, *names = sys.argv[1:]
    if not context or len(names) != len(set(names)) or any(
        not SAFE_NAME.fullmatch(name) for name in names
    ):
        print("Context must be non-empty and Secret keys must be unique uppercase names", file=sys.stderr)
        return 64

    binding_key = os.environ.get("SECRET_SNAPSHOT_BINDING_KEY", "")
    if len(binding_key.encode("utf-8")) < 32:
        print("SECRET_SNAPSHOT_BINDING_KEY must contain at least 32 bytes", file=sys.stderr)
        return 1

    missing = [name for name in names if not os.environ.get(name)]
    if missing:
        print("Required desired Secret values are missing", file=sys.stderr)
        return 1

    payload = json.dumps(
        {
            "context": context,
            "values": {name: os.environ[name] for name in sorted(names)},
        },
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")
    print(hmac.new(binding_key.encode("utf-8"), payload, hashlib.sha256).hexdigest())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
