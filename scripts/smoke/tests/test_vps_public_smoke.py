"""Public VPS smoke checks run against a fake fetcher; no network access."""
import contextlib
import io
import sys
import tempfile
import unittest
import urllib.error
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import vps_public_smoke as smoke
from vps_public_smoke import Response

SHA = "a" * 40
SECURITY_HEADERS = {
    "strict-transport-security": "max-age=31536000",
    "x-content-type-options": "nosniff",
    "content-security-policy": "default-src 'self'; frame-ancestors 'none'",
}


def healthy():
    return {
        "https://mnema.app/": Response(200, {"content-type": "text/html", **SECURITY_HEADERS}, b"<html></html>"),
        "https://www.mnema.app/": Response(308, {"location": "https://mnema.app/"}, b""),
        "https://mnema.app/app-config.js": Response(
            200, {}, ('window.MNEMA_APP_CONFIG = window.MNEMA_APP_CONFIG || {};\n'
                      f'window.MNEMA_APP_CONFIG.buildId = "{SHA}";\n').encode()),
        "https://auth.mnema.app/.well-known/openid-configuration": Response(
            200, {"strict-transport-security": "max-age=31536000"}, b'{"issuer": "https://auth.mnema.app"}'),
        "https://mnema.app/api/decks": Response(401, {}, b""),
        "https://mnema.app/api/actuator/health": Response(404, {}, b""),
    }


class Clock:
    def __init__(self):
        self.now = 0.0
        self.sleeps = []

    def __call__(self):
        return self.now

    def sleep(self, seconds):
        self.sleeps.append(seconds)
        self.now += seconds


def execute(responses, budget=180.0):
    clock = Clock()
    calls = []

    def fetch(url):
        calls.append(url)
        value = responses[url]
        if isinstance(value, Exception):
            raise value
        return value(len(calls)) if callable(value) else value

    return smoke.run(SHA, fetch, budget, clock, clock.sleep), calls, clock


class PublicSmokeTest(unittest.TestCase):
    def test_all_checks_pass_for_the_released_build(self):
        results, calls, clock = execute(healthy())
        self.assertEqual([reason for _, reason in results], [None] * 8)
        self.assertEqual(len(calls), 8)
        self.assertEqual(clock.sleeps, [])

    def test_each_failed_expectation_is_reported_and_retried_until_the_budget_ends(self):
        mutations = {
            "https://mnema.app/": Response(200, {"content-type": "application/json", **SECURITY_HEADERS}, b"{}"),
            "https://www.mnema.app/": Response(200, {}, b""),
            "https://mnema.app/app-config.js": Response(
                200, {}, b'window.MNEMA_APP_CONFIG.buildId = "' + b"b" * 40 + b'";'),
            "https://auth.mnema.app/.well-known/openid-configuration": Response(
                200, {"strict-transport-security": "max-age=31536000"}, b'{"issuer": "https://other.example"}'),
            "https://mnema.app/api/decks": Response(200, {}, b"[]"),
            "https://mnema.app/api/actuator/health": Response(200, {}, b"UP"),
        }
        for url, response in mutations.items():
            with self.subTest(url=url):
                responses = {**healthy(), url: response}
                results, calls, clock = execute(responses, budget=30)
                failed = [name for name, reason in results if reason is not None]
                self.assertEqual(len(failed), 1)
                self.assertGreater(calls.count(url), 1)
                self.assertLessEqual(clock.now, 30)

    def test_missing_or_weak_security_headers_fail_exactly_their_check(self):
        root = "https://mnema.app/"
        auth = "https://auth.mnema.app/.well-known/openid-configuration"
        html = {"content-type": "text/html"}
        cases = [
            (root, {**html, **SECURITY_HEADERS, "strict-transport-security": "max-age=300"},
             "frontend HSTS, nosniff and CSP headers"),
            (root, {**html, **{k: v for k, v in SECURITY_HEADERS.items() if k != "strict-transport-security"}},
             "frontend HSTS, nosniff and CSP headers"),
            (root, {**html, **SECURITY_HEADERS, "x-content-type-options": "sniff"},
             "frontend HSTS, nosniff and CSP headers"),
            (root, {**html, **{k: v for k, v in SECURITY_HEADERS.items() if k != "content-security-policy"}},
             "frontend HSTS, nosniff and CSP headers"),
            (root, {**html, **SECURITY_HEADERS, "content-security-policy": "  "},
             "frontend HSTS, nosniff and CSP headers"),
            (auth, {}, "auth origin HSTS"),
            (auth, {"strict-transport-security": "max-age=315360000"}, "auth origin HSTS"),
        ]
        for url, headers, check in cases:
            with self.subTest(url=url, headers=headers):
                body = healthy()[url].body
                results, _, _ = execute({**healthy(), url: Response(200, headers, body)}, budget=5)
                self.assertEqual([name for name, reason in results if reason is not None], [check])

    def test_hsts_accepts_additional_directives(self):
        responses = healthy()
        responses["https://mnema.app/"] = Response(
            200, {"content-type": "text/html", **SECURITY_HEADERS,
                  "strict-transport-security": "max-age=31536000; includeSubDomains"}, b"")
        results, _, _ = execute(responses)
        self.assertEqual([reason for _, reason in results], [None] * 8)

    def test_transient_failure_recovers_within_the_budget(self):
        responses = healthy()
        responses["https://mnema.app/"] = lambda attempt: (
            Response(502, {}, b"") if attempt < 3 else healthy()["https://mnema.app/"])
        results, _, clock = execute(responses)
        self.assertEqual([reason for _, reason in results], [None] * 8)
        self.assertEqual(clock.sleeps, [2.0, 4.0])

    def test_network_and_tls_errors_do_not_leak_details(self):
        responses = {**healthy(), "https://mnema.app/": urllib.error.URLError("secret-host-detail")}
        results, _, _ = execute(responses, budget=10)
        reason = dict(results)["frontend serves HTML"]
        self.assertEqual(reason, "unreachable or TLS failure")

    def test_failure_reasons_never_contain_response_bodies(self):
        responses = {**healthy(), "https://mnema.app/api/decks": Response(500, {}, b"SECRET-BODY")}
        results, _, _ = execute(responses, budget=5)
        self.assertNotIn("SECRET-BODY", smoke.render_markdown(results))

    def test_sha_must_be_a_full_commit(self):
        with self.assertRaises(ValueError):
            smoke.run("abc", lambda url: None)

    def test_main_writes_summary_and_exit_status(self):
        with tempfile.TemporaryDirectory() as temporary, contextlib.redirect_stdout(io.StringIO()):
            summary = Path(temporary) / "summary.md"
            original = smoke.build_fetch
            try:
                smoke.build_fetch = lambda: (lambda url: healthy()[url])
                self.assertEqual(smoke.main(["--sha", SHA, "--summary", str(summary)]), 0)
                smoke.build_fetch = lambda: (lambda url: Response(503, {}, b""))
                self.assertEqual(smoke.main(["--sha", SHA, "--budget-seconds", "0", "--summary", str(summary)]), 1)
            finally:
                smoke.build_fetch = original
            text = summary.read_text()
            self.assertIn("### Public smoke", text)
            self.assertIn("FAIL: status 503", text)


if __name__ == "__main__":
    unittest.main()
