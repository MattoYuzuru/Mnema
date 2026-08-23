from __future__ import annotations

import importlib.util
import json
import sys
import tempfile
import unittest
import uuid
from pathlib import Path
from typing import Any


MODULE_PATH = Path(__file__).parents[1] / "release_smoke.py"
SPEC = importlib.util.spec_from_file_location("release_smoke", MODULE_PATH)
assert SPEC and SPEC.loader
release_smoke = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = release_smoke
SPEC.loader.exec_module(release_smoke)


class FakeClient:
    def __init__(self, release_id: str) -> None:
        self.release_id = release_id
        self.login_count = 0
        self.deck_id = str(uuid.uuid4())
        self.card_id = str(uuid.uuid4())
        self.archived = False
        self.hard_deleted = False

    def request(self, method: str, url: str, **kwargs: Any) -> bytes:
        if url.endswith("/"):
            return b'<html><script src="/main.0123abcd.js"></script></html>'
        if url.endswith("main.0123abcd.js") and method == "HEAD":
            return b""
        if url.endswith("/app-config.js"):
            return (
                f'window.MNEMA_APP_CONFIG.buildId = "{self.release_id}";\n'
                'window.MNEMA_APP_CONFIG.features.aiEnabled = false;\n'
            ).encode()
        if method == "DELETE" and url.endswith(f"/decks/{self.deck_id}"):
            self.archived = True
            return b""
        if method == "DELETE" and url.endswith(f"/decks/{self.deck_id}/hard"):
            if not self.archived:
                raise AssertionError("hard delete happened before archive")
            self.hard_deleted = True
            return b""
        raise AssertionError(f"unexpected request {method} {url}")

    def json(self, method: str, url: str, **kwargs: Any) -> dict[str, Any]:
        if url.endswith("/actuator/info"):
            return {"build": {"id": self.release_id}}
        if url.endswith("/auth/login"):
            self.login_count += 1
            return {
                "access_token": f"token-{self.login_count}",
                "token_type": "Bearer",
                "expires_in": 3600,
            }
        if method == "POST" and url.endswith("/api/core/decks"):
            return {"userDeckId": self.deck_id}
        if method == "POST" and url.endswith(f"/decks/{self.deck_id}/cards"):
            return {"userCardId": self.card_id}
        if method == "GET" and url.endswith(f"/review/decks/{self.deck_id}/next"):
            return {"userCardId": self.card_id}
        if method == "POST" and url.endswith(f"/cards/{self.card_id}/answer"):
            return {"answeredCardId": self.card_id, "rating": "GOOD"}
        raise AssertionError(f"unexpected JSON request {method} {url}")


class BootstrapClient(FakeClient):
    def __init__(self, release_id: str, registration_failure: release_smoke.SmokeFailure | None = None) -> None:
        super().__init__(release_id)
        self.account_exists = False
        self.registration_count = 0
        self.registration_failure = registration_failure
        self.registration_headers: dict[str, str] | None = None
        self.registration_body: dict[str, Any] | None = None

    def json(self, method: str, url: str, **kwargs: Any) -> dict[str, Any]:
        if url.endswith("/auth/login"):
            self.login_count += 1
            if not self.account_exists:
                raise release_smoke.SmokeFailure("unexpected_http_status", "auth", "status=401")
            return {
                "access_token": f"token-{self.login_count}",
                "token_type": "Bearer",
                "expires_in": 3600,
            }
        if url.endswith("/auth/register"):
            self.registration_count += 1
            self.registration_headers = kwargs.get("headers")
            self.registration_body = kwargs.get("json_body")
            if self.registration_failure is not None:
                raise self.registration_failure
            self.account_exists = True
            return {
                "access_token": "registration-token",
                "token_type": "Bearer",
                "expires_in": 3600,
            }
        return super().json(method, url, **kwargs)


class ReleaseSmokeTest(unittest.TestCase):
    release_id = "a" * 40

    def config(self, report: Path, **overrides: Any):
        values = {
            "environment": "test",
            "public_url": "https://mnema.example",
            "auth_url": "https://auth.mnema.example",
            "expected_release_sha": self.release_id,
            "login": "mnema-smoke",
            "password": "secret-password",
            "turnstile_bypass_key": "x" * 32,
            "timeout_seconds": 300,
            "request_timeout_seconds": 15,
            "report_path": report,
        }
        values.update(overrides)
        return release_smoke.SmokeConfig(**values)

    def test_full_smoke_renews_token_and_always_removes_fixture(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "report.json"
            client = FakeClient(self.release_id)
            result = release_smoke.ReleaseSmoke(self.config(report), client).run()

            self.assertEqual("passed", result.status)
            self.assertEqual(2, client.login_count)
            self.assertTrue(client.archived)
            self.assertTrue(client.hard_deleted)
            persisted = json.loads(report.read_text())
            self.assertEqual("passed", persisted["status"])
            self.assertNotIn("mnema-smoke", report.read_text())
            self.assertNotIn("secret-password", report.read_text())

    def test_forced_failure_still_cleans_fixture_and_records_safe_error(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "report.json"
            client = FakeClient(self.release_id)
            config = self.config(report, force_failure_step="content_and_review")
            smoke = release_smoke.ReleaseSmoke(config, client)
            smoke.deck_id = client.deck_id

            with self.assertRaises(release_smoke.SmokeFailure):
                smoke.run()

            persisted = json.loads(report.read_text())
            self.assertEqual("failed", persisted["status"])
            self.assertEqual("core", persisted["failed_service"])
            self.assertTrue(client.archived)
            self.assertTrue(client.hard_deleted)

    def test_missing_smoke_account_is_registered_once_without_secret_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "report.json"
            client = BootstrapClient(self.release_id)
            config = self.config(report, login="mnema-smoke@example.com")

            result = release_smoke.ReleaseSmoke(config, client).run()

            self.assertEqual("passed", result.status)
            self.assertEqual(3, client.login_count)
            self.assertEqual(1, client.registration_count)
            self.assertEqual(
                {"X-Mnema-Smoke-Key": config.turnstile_bypass_key},
                client.registration_headers,
            )
            self.assertEqual("mnema-smoke@example.com", client.registration_body["email"])
            self.assertRegex(client.registration_body["username"], r"^mnema-smoke-[0-9a-f]{16}$")
            self.assertEqual(config.password, client.registration_body["password"])
            report_text = report.read_text()
            self.assertNotIn(config.login, report_text)
            self.assertNotIn(config.password, report_text)
            self.assertNotIn(config.turnstile_bypass_key, report_text)

    def test_existing_account_with_wrong_password_fails_closed_on_registration_conflict(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "report.json"
            client = BootstrapClient(
                self.release_id,
                release_smoke.SmokeFailure("unexpected_http_status", "auth", "status=409"),
            )

            with self.assertRaises(release_smoke.SmokeFailure) as raised:
                release_smoke.ReleaseSmoke(
                    self.config(report, login="mnema-smoke@example.com"),
                    client,
                ).run()

            self.assertEqual("status=409", raised.exception.safe_detail)
            self.assertEqual(1, client.login_count)
            self.assertEqual(1, client.registration_count)
            persisted = json.loads(report.read_text())
            self.assertEqual("failed", persisted["status"])
            self.assertEqual("auth", persisted["failed_service"])

    def test_bootstrap_requires_an_email_login(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "report.json"
            client = BootstrapClient(self.release_id)

            with self.assertRaises(release_smoke.SmokeFailure) as raised:
                release_smoke.ReleaseSmoke(self.config(report), client).run()

            self.assertEqual("smoke_account_bootstrap_requires_email", raised.exception.code)
            self.assertEqual(0, client.registration_count)

    def test_identity_only_does_not_require_credentials(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            config = self.config(
                Path(directory) / "report.json",
                login="",
                password="",
                turnstile_bypass_key="",
                identity_only=True,
            )
            config.validate()
            client = FakeClient(self.release_id)

            release_smoke.ReleaseSmoke(config, client).run()

            self.assertEqual(0, client.login_count)

    def test_configuration_rejects_non_https_and_weak_bypass_key(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(ValueError):
                self.config(Path(directory) / "report.json", public_url="http://mnema.example").validate()
            with self.assertRaises(ValueError):
                self.config(Path(directory) / "report.json", turnstile_bypass_key="weak").validate()


if __name__ == "__main__":
    unittest.main()
