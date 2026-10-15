#!/usr/bin/env python3
"""Aggregate recommendation logs without account IDs, query text or private content.

Actions per 1,000 aggregate impressions are descriptive rates, not causal effects or
unique-user conversion. Exposure rows carry no account. A seeded synthetic generator
proves reproducibility of this pipeline; only a controlled production comparison can
establish usefulness of a ranker.
"""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
from datetime import date
import json
from pathlib import Path
import random

KINDS = ("DETAIL_VIEW", "SEARCH_CLICK", "FORK", "STUDY_START", "DROP")
VERSIONS = ("catalog-v1", "recs-v1")
SURFACES = ("catalog", "search", "for-you", "author", "detail", "copy", "study", "decks")


def exact(row, fields):
    if not isinstance(row, dict) or set(row) != set(fields):
        raise ValueError("unknown or missing log fields (personal data is not accepted)")


def positive(value):
    if type(value) is not int or value < 0:
        raise ValueError("count must be a nonnegative integer")
    return value


def validate_context(row):
    if row["rankVersion"] not in VERSIONS or row["surface"] not in SURFACES:
        raise ValueError("unknown rank version or surface")
    date.fromisoformat(row["day"])


def evaluate(data):
    exact(data, ("exposures", "events"))
    if not isinstance(data["exposures"], list) or not isinstance(data["events"], list):
        raise ValueError("logs must be lists")
    impressions = Counter()
    events = defaultdict(Counter)
    for row in data["exposures"]:
        exact(row, ("day", "rankVersion", "surface", "count"))
        validate_context(row)
        impressions[row["rankVersion"]] += positive(row["count"])
    for row in data["events"]:
        exact(row, ("day", "rankVersion", "surface", "kind", "count"))
        validate_context(row)
        if row["kind"] not in KINDS:
            raise ValueError("unknown event kind")
        events[row["rankVersion"]][row["kind"]] += positive(row["count"])
    result = {}
    for version in sorted(impressions.keys() | events.keys()):
        denominator = impressions[version]
        result[version] = {"impressions": denominator,
                           "events": {kind: events[version][kind] for kind in KINDS},
                           "actionsPer1000Impressions": {kind: round(events[version][kind] * 1000 / denominator, 3)
                                                        if denominator else None for kind in KINDS}}
    return {"kind": "descriptive-comparison", "versions": result,
            "limits": ["Anonymous exposure aggregates cannot identify unique-user conversions.",
                       "Rates include repeat actions and may exceed 1000; no clamping or causal lift is claimed.",
                       "Compare equal periods, eligible cohorts and surface mixes; retain ranker attribution on lifecycle events.",
                       "A synthetic preference/response model is assumed, not measured user behaviour."]}


def language_factor(deck, preference):
    return 1 if preference is None or deck["language"] == preference else .3


def matching_capacity(decks, authors=None, topics=None, stop=10):
    """Bipartite author/topic capacities, parallel deck edges; same bounded feasibility as production."""
    authors, topics = authors or Counter(), topics or Counter()
    source, sink = ("source", ""), ("sink", "")
    residual = defaultdict(dict)

    def connect(a, b, capacity):
        residual[a][b] = residual[a].get(b, 0) + max(0, capacity)
        residual[b].setdefault(a, 0)

    for author in {d["author"] for d in decks}:
        connect(source, ("author", author), 2 - authors[author])
    for topic in {d["topic"] for d in decks}:
        connect(("topic", topic), sink, 2 - topics[topic])
    for deck in decks:
        connect(("author", deck["author"]), ("topic", deck["topic"]), 1)
    flow = 0
    while flow < stop:
        parents, queue = {source: None}, [source]
        for node in queue:
            for other, capacity in residual[node].items():
                if capacity > 0 and other not in parents:
                    parents[other] = node; queue.append(other)
            if sink in parents:
                break
        if sink not in parents:
            break
        node = sink
        while parents[node] is not None:
            previous = parents[node]
            residual[previous][node] -= 1
            residual[node][previous] += 1
            node = previous
        flow += 1
    return flow


def preferred_subset(ordered, target):
    selected, authors, topics = [], Counter(), Counter()
    for index, deck in enumerate(ordered):
        if authors[deck["author"]] == 2 or topics[deck["topic"]] == 2:
            continue
        authors[deck["author"]] += 1; topics[deck["topic"]] += 1
        needed = target - len(selected) - 1
        if matching_capacity(ordered[index + 1:], authors, topics, needed) == needed:
            selected.append(deck)
            if len(selected) == target:
                break
        else:
            authors[deck["author"]] -= 1; topics[deck["topic"]] -= 1
    return selected


def model_rank(decks, interest, preference):
    if sum(d["topic"] == interest for d in decks) < 4:
        return []
    ordered = sorted(decks, key=lambda d: (-d["score"] * (1 + .8 * (d["topic"] == interest)) * language_factor(d, preference),
                                          -(d["topic"] == interest), -language_factor(d, preference), -int(d["new"]), d["code"]))
    explorers = [d for d in ordered if d["topic"] != interest or d["new"]]
    if len(ordered) >= 12:
        for index, first in enumerate(explorers):
            for second in explorers[index + 1:]:
                remainder = [d for d in ordered if d["code"] not in (first["code"], second["code"])]
                if matching_capacity(remainder) == 10:
                    return preferred_subset(remainder, 10) + [first, second]
    target = matching_capacity(ordered)
    return preferred_subset(ordered, target) if target >= 4 else []


def synthetic(spec):
    exact(spec, ("seed", "sessions", "day", "decks", "responses"))
    if type(spec["seed"]) is not int or type(spec["sessions"]) is not int or not 1 <= spec["sessions"] <= 100000:
        raise ValueError("invalid simulation size")
    date.fromisoformat(spec["day"])
    rng = random.Random(spec["seed"])
    decks = spec["decks"]
    for deck in decks:
        exact(deck, ("code", "topic", "author", "language", "score", "new"))
        if type(deck["score"]) not in (float, int) or deck["score"] < 0:
            raise ValueError("invalid score")
    counts = Counter()
    for session in range(spec["sessions"]):
        interest = ("spanish", "sql-data", "anatomy")[session % 3]
        language = "es" if interest == "spanish" else None
        # Common random response draws across the two rankers hold the response model fixed.
        draws = [rng.random() for _ in range(12)]
        catalog = sorted(decks, key=lambda d: (-d["score"], d["code"]))[:12]
        selected = model_rank(decks, interest, language)
        for version, rows in (("catalog-v1", catalog), ("recs-v1", selected)):
            surface = "catalog" if version == "catalog-v1" else "for-you"
            for position, (deck, draw) in enumerate(zip(rows, draws), 1):
                counts[(version, surface, "EXPOSURE")] += 1
                probability = spec["responses"]["matchingTopicOpen"] if deck["topic"] == interest else spec["responses"]["otherTopicOpen"]
                probability *= 1 / (1 + .06 * (position - 1))
                if draw < probability:
                    counts[(version, surface, "DETAIL_VIEW")] += 1
                    if draw < probability * .35:
                        counts[(version, "copy", "FORK")] += 1
                    if draw < probability * .2:
                        counts[(version, "study", "STUDY_START")] += 1
                    if probability * .30 <= draw < probability * .35:
                        counts[(version, "decks", "DROP")] += 1
    exposures, events = [], []
    for (version, surface, kind), count in sorted(counts.items()):
        row = {"day": spec["day"], "rankVersion": version, "surface": surface, "count": count}
        if kind == "EXPOSURE":
            exposures.append(row)
        else:
            events.append({**row, "kind": kind})
    return {"exposures": exposures, "events": events}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    choice = parser.add_mutually_exclusive_group(required=True)
    choice.add_argument("--logs", type=Path)
    choice.add_argument("--synthetic", type=Path)
    parser.add_argument("--write-logs", type=Path)
    args = parser.parse_args()
    try:
        data = json.loads((args.logs or args.synthetic).read_text())
        logs = synthetic(data) if args.synthetic else data
        result = evaluate(logs)
        if args.write_logs:
            args.write_logs.write_text(json.dumps(logs, ensure_ascii=False, indent=2) + "\n")
        print(json.dumps(result, ensure_ascii=False, indent=2))
    except (ValueError, KeyError, TypeError, OSError):
        parser.exit(2, "invalid anonymized evaluation input\n")


if __name__ == "__main__":
    main()
