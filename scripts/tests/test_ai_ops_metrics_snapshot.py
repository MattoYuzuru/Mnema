"""The metrics snapshot reads the actuator's drill-down documents and prints one compact table."""

from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlsplit
import importlib.util
import json
import threading
import unittest


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("metrics_snapshot", ROOT / "scripts/ai-ops/metrics_snapshot.py")
SNAPSHOT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SNAPSHOT)

# (capability, provider, model, outcome) -> count of mnema_ai_calls_total
CALLS = {("TEXT", "deepseek", "flash", "OK"): 90.0, ("TEXT", "deepseek", "flash", "TIMEOUT"): 10.0, ("TEXT", "openrouter", "flash", "OK"): 5.0,
         ("TTS", "google", "lite", "OK"): 20.0}
COST = {("TEXT", "deepseek", "flash"): 1_500_000.0, ("TTS", "google", "lite"): 250_000.0}
P95 = {("TEXT", "deepseek", "flash"): 4.25, ("TEXT", "deepseek", "pro"): 9.0, ("TTS", "google", "lite"): 1.5}


def document(tags, filters, table, statistic):
    """What /actuator/metrics/<name>?tag=k:v answers for a table keyed by tuples of the tag values."""
    rows = [key for key in table if all(key[tags.index(name)] == value for name, value in filters.items() if name in tags)]
    if not rows:
        return None
    available = [{"tag": name, "values": sorted({row[index] for row in rows})} for index, name in enumerate(tags) if name not in filters]
    total = sum(table[row] for row in rows) if statistic == "COUNT" else max(table[row] for row in rows)
    return {"measurements": [{"statistic": statistic, "value": total}], "availableTags": available}


class Actuator(BaseHTTPRequestHandler):
    def do_GET(self):  # noqa: N802 - the http.server hook name
        url = urlsplit(self.path)
        filters = dict(item.split(":", 1) for item in parse_qs(url.query).get("tag", []))
        name = url.path.removeprefix("/actuator/metrics/")
        body = None
        if name == "mnema_ai_calls_total":
            body = document(["capability", "provider", "model", "outcome"], filters, CALLS, "COUNT")
        elif name == "mnema_ai_cost_micros_total":
            body = document(["capability", "provider", "model"], filters, COST, "COUNT")
        elif name == "mnema_ai_call_seconds.percentile":
            phi = filters.pop("phi", None)
            body = document(["capability", "provider", "model"], filters, P95, "VALUE") if phi == "0.95" else None
            if body is not None:
                body["availableTags"].append({"tag": "phi", "values": ["0.95"]})
        elif name == "mnema_usage_reserved_credits":
            body = {"measurements": [{"statistic": "VALUE", "value": 14.0}], "availableTags": []}
        self.send_response(200 if body is not None else 404)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(json.dumps(body or {}).encode())

    def log_message(self, *args):
        pass


class MetricsSnapshotTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = HTTPServer(("127.0.0.1", 0), Actuator)
        threading.Thread(target=cls.server.serve_forever, daemon=True).start()
        cls.base = f"http://127.0.0.1:{cls.server.server_port}/actuator"

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()

    def test_rows_sum_calls_and_costs_and_take_the_worst_p95_of_a_provider(self):
        rows = {(row["capability"], row["provider"]): row for row in SNAPSHOT.ai_rows(self.base)}
        text = rows[("TEXT", "deepseek")]
        self.assertEqual((text["calls"], text["errors"], text["micros"]), (100.0, 10.0, 1_500_000.0))
        self.assertEqual(text["p95"], 9.0)
        self.assertEqual(rows[("TEXT", "openrouter")]["errors"], 0.0)
        self.assertIsNone(rows[("TEXT", "openrouter")]["p95"])
        self.assertEqual(rows[("TTS", "google")]["micros"], 250_000.0)

    def test_the_table_is_compact_and_a_missing_series_is_not_an_error(self):
        output = SNAPSHOT.render(SNAPSHOT.ai_rows(self.base))
        self.assertIn("TEXT", output)
        self.assertRegex(output, r"deepseek\s+100\s+10\.0\s+9\.00\s+1\.5000")
        notes = SNAPSHOT.extras(self.base)
        self.assertIn("credits held by active reservations: 14", notes)
        self.assertIn("oldest due generation step: n/a", notes)

    def test_an_unreachable_port_is_reported_not_traced(self):
        self.assertEqual(SNAPSHOT.main(["--url", "http://127.0.0.1:9/actuator"]), 2)


if __name__ == "__main__":
    unittest.main()
