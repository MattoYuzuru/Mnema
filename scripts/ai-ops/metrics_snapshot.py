#!/usr/bin/env python3
"""Compact table of the AI metrics of one Learning process, read from its management port.

The Learning service exposes /actuator/metrics on a separate private port (docs/operations/ai-runbook.md, "Metrics"):

    python3 scripts/ai-ops/metrics_snapshot.py --url http://127.0.0.1:8081/actuator

Counters and timers are those of the process since it started (a restart resets them); the authoritative spend of the
Moscow day is the call journal behind the capability budget, which this script does not read. Python standard library only.
"""

from __future__ import annotations

import argparse
import json
import sys
import urllib.error
import urllib.parse
import urllib.request
from collections.abc import Iterator

DEFAULT_URL = "http://127.0.0.1:8081/actuator"
CALL_TAGS = ("capability", "provider", "model", "outcome")
SUCCESS = "OK"


def fetch(base: str, name: str, filters: dict[str, str] | None = None, timeout: float = 5.0) -> dict | None:
    """One /metrics/<name> document, or None when the process has no such meter yet (a 404 is normal for a series nothing has touched)."""
    query = "".join("&tag=" + urllib.parse.quote(f"{key}:{value}", safe="") for key, value in (filters or {}).items())
    url = f"{base.rstrip('/')}/metrics/{urllib.parse.quote(name, safe='._')}" + ("?" + query[1:] if query else "")
    try:
        with urllib.request.urlopen(url, timeout=timeout) as response:  # noqa: S310 - the operator names the URL
            return json.load(response)
    except urllib.error.HTTPError as error:
        error.close()
        if error.code == 404:
            return None
        raise


def series(base: str, name: str, tags: tuple[str, ...], filters: dict[str, str] | None = None) -> Iterator[tuple[dict[str, str], dict[str, float]]]:
    """Every combination of the given tags of one meter with its statistics, by drilling down one tag at a time."""
    document = fetch(base, name, filters)
    if document is None:
        return
    filters = filters or {}
    remaining = [entry for entry in document.get("availableTags", []) if entry["tag"] in tags and entry["tag"] not in filters]
    if not remaining:
        yield filters, {item["statistic"]: item["value"] for item in document.get("measurements", [])}
        return
    entry = remaining[0]
    for value in entry["values"]:
        yield from series(base, name, tags, {**filters, entry["tag"]: value})


def gauge(base: str, name: str) -> float | None:
    document = fetch(base, name)
    if document is None:
        return None
    for item in document.get("measurements", []):
        if item["statistic"] in ("VALUE", "COUNT"):
            return item["value"]
    return None


def ai_rows(base: str) -> list[dict]:
    """One row per (capability, provider): calls, error rate, p95 latency and cost since start."""
    rows: dict[tuple[str, str], dict] = {}

    def row(capability: str, provider: str) -> dict:
        return rows.setdefault((capability, provider), {"calls": 0.0, "errors": 0.0, "p95": None, "micros": 0.0})

    for tags, stats in series(base, "mnema_ai_calls_total", CALL_TAGS):
        entry = row(tags["capability"], tags["provider"])
        count = stats.get("COUNT", 0.0)
        entry["calls"] += count
        if tags["outcome"] != SUCCESS:
            entry["errors"] += count
    for tags, stats in series(base, "mnema_ai_call_seconds.percentile", ("capability", "provider", "model", "phi"), {"phi": "0.95"}):
        value = stats.get("VALUE")
        if value is not None and "capability" in tags:
            entry = row(tags["capability"], tags["provider"])
            entry["p95"] = max(entry["p95"] or 0.0, value)
    for tags, stats in series(base, "mnema_ai_cost_micros_total", ("capability", "provider", "model")):
        row(tags["capability"], tags["provider"])["micros"] += stats.get("COUNT", 0.0)
    return [{"capability": key[0], "provider": key[1], **value} for key, value in sorted(rows.items())]


def render(rows: list[dict]) -> str:
    header = ("capability", "provider", "calls", "error %", "p95 s", "cost USD")
    lines = [header]
    for entry in rows:
        rate = 100.0 * entry["errors"] / entry["calls"] if entry["calls"] else 0.0
        p95 = "-" if entry["p95"] is None else f"{entry['p95']:.2f}"
        lines.append((entry["capability"], entry["provider"], f"{entry['calls']:.0f}", f"{rate:.1f}", p95, f"{entry['micros'] / 1_000_000:.4f}"))
    widths = [max(len(str(line[index])) for line in lines) for index in range(len(header))]
    return "\n".join("  ".join(str(cell).ljust(width) if index < 2 else str(cell).rjust(width)
                               for index, (cell, width) in enumerate(zip(line, widths))).rstrip() for line in lines)


def extras(base: str) -> list[str]:
    out = []
    for label, name, unit in (("oldest due generation step", "mnema_generation_step_queue_age_seconds", "s"),
                              ("credits held by active reservations", "mnema_usage_reserved_credits", "")):
        value = gauge(base, name)
        out.append(f"{label}: {'n/a' if value is None else f'{value:.0f}{unit}'}")
    for label, name, key in (("speech inputs", "mnema_stt_inputs_total", "outcome"), ("generation steps", "mnema_generation_steps_total", "outcome"),
                             ("answer checks", "mnema_assessment_total", "outcome"), ("repairs", "mnema_generation_repairs_total", "route"),
                             ("speech cache", "mnema_tts_cache_total", "outcome")):
        counts = {tags[key]: stats.get("COUNT", 0.0) for tags, stats in series(base, name, (key,))}
        if counts:
            out.append(f"{label}: " + ", ".join(f"{name}={value:.0f}" for name, value in sorted(counts.items())))
    return out


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--url", default=DEFAULT_URL, help=f"actuator base URL of the management port (default {DEFAULT_URL})")
    args = parser.parse_args(argv)
    try:
        rows = ai_rows(args.url)
        notes = extras(args.url)
    except (urllib.error.URLError, OSError) as error:
        print(f"cannot read {args.url}: {error}", file=sys.stderr)
        return 2
    print(render(rows) if rows else "no AI calls since this process started")
    print()
    print("\n".join(notes))
    print("\nSince the process started; the daily spend that the budget guards is the journal's (docs/operations/ai-runbook.md).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
