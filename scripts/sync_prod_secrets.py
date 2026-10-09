#!/usr/bin/env python3
"""Copy allowlisted application secrets from a local dotenv file into GitHub Environment prod.

Values go to `gh secret set` on stdin and are never printed; only names and set/skipped.
Run on the owner's machine:  python3 scripts/sync_prod_secrets.py --env-file <dotenv> [--dry-run]
"""
from __future__ import annotations

import argparse
import importlib.util
import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
KEYS = ROOT / "deploy" / "production" / "app-config.keys"
REPOSITORY = "MattoYuzuru/Mnema"
ENVIRONMENT = "prod"
LINE = re.compile(r"(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)")


def load_dispatcher():
    """The dispatcher's own validation is the single rule set; loading it runs nothing."""
    spec = importlib.util.spec_from_file_location(
        "mnema_deploy_rules", ROOT / "deploy" / "production" / "mnema-deploy.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def refused(values: dict[str, str], names: list[str], rules=None) -> list[str]:
    """Names the dispatcher would reject (never values): BOM, inline comments, quotes, whitespace, ..."""
    rules = rules or load_dispatcher()
    bad = []
    for name in names:
        try:
            rules.validate_entry(name, values.get(name, ""))
        except rules.Rejected:
            bad.append(name)
    if not bad:
        try:  # cross-key rules, e.g. Turnstile mode "required" needs both keys
            rules.validate_app_config(json.dumps({n: values.get(n, "") for n in names}).encode())
        except rules.Rejected as error:
            bad.append("(combination) " + str(error))
    return bad


def read_names(path: Path = KEYS) -> list[str]:
    return [line.strip() for line in path.read_text(encoding="utf-8").splitlines()
            if line.strip() and not line.strip().startswith("#")]


def parse_dotenv(text: str) -> dict[str, str]:
    """KEY=VALUE lines only: matching outer quotes are removed, nothing is evaluated."""
    values: dict[str, str] = {}
    for raw in text.splitlines():
        match = None if raw.lstrip().startswith("#") else LINE.fullmatch(raw.strip())
        if not match:
            continue
        value = match.group(2).strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1]
        values[match.group(1)] = value
    return values


def sync(values: dict[str, str], names: list[str], dry_run: bool, run=subprocess.run, out=None, rules=None) -> int:
    out = out or sys.stdout
    bad = refused(values, names, rules)
    if bad:  # all-or-nothing: nothing is sent while any value would be rejected by the dispatcher
        for name in bad:
            print(f"refused {name}", file=out)
        return 1
    failures = 0
    for name in names:
        value = values.get(name, "")
        if not value:
            print(f"skipped {name} (absent or empty)", file=out)
            continue
        if dry_run:
            print(f"would set PROD_{name}", file=out)
            continue
        result = run(["gh", "secret", "set", f"PROD_{name}", "--env", ENVIRONMENT, "--repo", REPOSITORY],
                     input=value.encode(), capture_output=True, timeout=60)
        if result.returncode:
            failures += 1
            print(f"FAILED PROD_{name}", file=out)
        else:
            print(f"set PROD_{name}", file=out)
    return 1 if failures else 0


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", type=Path, required=True)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args(argv)
    try:
        values = parse_dotenv(args.env_file.read_text(encoding="utf-8-sig"))
    except OSError:
        print("cannot read the env file", file=sys.stderr)
        return 2
    return sync(values, read_names(), args.dry_run)


if __name__ == "__main__":
    raise SystemExit(main())
