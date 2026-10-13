"""Privacy and reproducibility boundaries of Recs/4's offline comparison."""
import importlib.util
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
EVIDENCE = ROOT / "docs/engineering/evidence/community-decks/epic-4"
spec = importlib.util.spec_from_file_location("ranker_evaluation", EVIDENCE / "evaluate-rankers.py")
EVALUATION = importlib.util.module_from_spec(spec)
spec.loader.exec_module(EVALUATION)


class RankerEvaluationTest(unittest.TestCase):
    def test_seeded_fixture_reproduces_committed_logs_and_comparison(self):
        fixture = json.loads((EVIDENCE / "ranker-fixture.json").read_text())
        logs = EVALUATION.synthetic(fixture)
        self.assertEqual(json.loads((EVIDENCE / "synthetic-logs.json").read_text()), logs)
        self.assertEqual(json.loads((EVIDENCE / "ranker-comparison.json").read_text()), EVALUATION.evaluate(logs))
        self.assertEqual(36000, EVALUATION.evaluate(logs)["versions"]["recs-v1"]["impressions"])

    def test_rejects_account_and_search_text_before_any_result(self):
        for private_key in ("accountId", "query", "deckText", "weight", "score"):
            logs = {"exposures": [{"day": "2026-10-13", "rankVersion": "recs-v1", "surface": "for-you", "count": 20,
                                    private_key: "private-value"}], "events": []}
            with self.subTest(private_key=private_key), self.assertRaises(ValueError):
                EVALUATION.evaluate(logs)

    def test_zero_denominator_stays_unknown_and_actions_are_not_clamped(self):
        event = {"day": "2026-10-13", "rankVersion": "recs-v1", "surface": "copy", "kind": "FORK", "count": 5}
        self.assertIsNone(EVALUATION.evaluate({"exposures": [], "events": [event]})["versions"]["recs-v1"]["actionsPer1000Impressions"]["FORK"])
        exposure = {"day": "2026-10-13", "rankVersion": "recs-v1", "surface": "for-you", "count": 2}
        self.assertEqual(2500, EVALUATION.evaluate({"exposures": [exposure], "events": [event]})["versions"]["recs-v1"]["actionsPer1000Impressions"]["FORK"])

    def test_unknown_context_and_invalid_counts_fail_closed(self):
        good = {"day": "2026-10-13", "rankVersion": "recs-v1", "surface": "for-you", "count": 1}
        for change in ({"rankVersion": "unbounded"}, {"surface": "query text"}, {"count": -1}, {"count": True}, {"day": "invalid"}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                EVALUATION.evaluate({"exposures": [{**good, **change}], "events": []})

    def test_confident_language_penalizes_missing_target_and_sparse_keeps_strict_limits(self):
        deck = {"code": "a", "topic": "spanish", "author": "a", "language": None, "score": 1, "new": True}
        self.assertEqual(.3, EVALUATION.language_factor(deck, "es"))
        self.assertEqual(1, EVALUATION.language_factor(deck, None))
        scarce = [{**deck, "code": str(i), "author": str(i)} for i in range(3)]
        self.assertEqual([], EVALUATION.model_rank(scarce, "spanish", "es"))
        # Four matching candidates still cannot make a four-card shelf of one topic under first-ten diversity.
        self.assertEqual([], EVALUATION.model_rank(scarce + [{**deck, "code": "4", "author": "4"}], "spanish", "es"))

    def test_exploration_pair_keeps_a_feasible_twelve_instead_of_greedy_ten(self):
        kinds = ["spanish"] * 2 + ["english"] * 4 + ["sql-data"] * 2 + ["math"] * 2 + ["law"] * 2
        decks = [{"code": str(i).zfill(2), "topic": topic, "author": str(i), "language": "es" if topic == "spanish" else None,
                  "score": 10 if topic == "spanish" else 1, "new": True} for i, topic in enumerate(kinds)]
        # The simulation has a single explicit interest; all fresh cards are exploration eligible.
        result = EVALUATION.model_rank(decks, "english", None)
        self.assertEqual(12, len(result))
        from collections import Counter
        self.assertTrue(all(n <= 2 for n in Counter(d["topic"] for d in result[:10]).values()))
        self.assertTrue(all(n <= 2 for n in Counter(d["author"] for d in result[:10]).values()))
        self.assertTrue(all(d["new"] for d in result[10:]))

    def test_empty_report_is_descriptive_and_has_no_causal_claim(self):
        # Report shape deliberately precludes a causal percentage-lift claim from anonymous counters.
        result = EVALUATION.evaluate({"exposures": [], "events": []})
        self.assertEqual({}, result["versions"])
        self.assertEqual("descriptive-comparison", result["kind"])
        self.assertTrue(any("causal" in value for value in result["limits"]))


if __name__ == "__main__":
    unittest.main()
