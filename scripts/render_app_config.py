#!/usr/bin/env python3
"""Build the `configure` payload from PROD_<NAME> environment variables; never prints values."""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
from pathlib import Path

NAME = re.compile(r"[A-Z][A-Z0-9_]*")


def read_keys(path: Path) -> list[str]:
    names = []
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        if not NAME.fullmatch(line) or line in names:
            raise ValueError("application config key list is malformed")
        names.append(line)
    return names


def render(names: list[str], environ) -> dict[str, str]:
    """Only allowlisted, non-empty variables; an unset secret is simply absent."""
    return {name: environ["PROD_" + name] for name in names if environ.get("PROD_" + name)}


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--keys", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True, help="private file receiving the JSON object")
    args = parser.parse_args(argv)
    try:
        values = render(read_keys(args.keys), os.environ)
    except (OSError, ValueError) as error:
        print(f"::error::{error}", file=sys.stderr)
        return 1
    descriptor = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
        json.dump(values, handle)
    print(f"application configuration names present: {len(values)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
