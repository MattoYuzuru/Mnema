from __future__ import annotations

import importlib.util
import json
import sys
import tempfile
import time
import unittest
import uuid
from pathlib import Path
from typing import Any
from unittest.mock import patch
from urllib.error import HTTPError, URLError


MODULE_PATH = Path(__file__).parents[1] / "release_smoke.py"
SPEC = importlib.util.spec_from_file_location("release_smoke", MODULE_PATH)
assert SPEC and SPEC.loader
release_smoke = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = release_smoke
SPEC.loader.exec_module(release_smoke)


class StubResponse:
    def __init__(self, body: bytes = b'{}', status: int = 200) -> None:
        self.body = body
        self.status = status

    def __enter__(self):
        return self

    def __exit__(self, *args: Any) -> None:
        return None

    def read(self, _: int) -> bytes:
        return self.body


class FakeClient:
    def __init__(self, release_id: str) -> None:
        self.release_id = release_id
        self.login_count = 0
        self.template_id = str(uuid.uuid4())
        self.deck_id = str(uuid.uuid4())
        self.card_id = str(uuid.uuid4())
        self.template_body: dict[str, Any] | None = None
        self.template_deleted = False
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
        if url.endswith("/actuator/health/readiness"):
            return b'{"status":"UP"}'
        if method == "DELETE" and url.endswith(f"/decks/{self.deck_id}"):
            self.archived = True
            return b""
        if method == "DELETE" and url.endswith(f"/decks/{self.deck_id}/hard"):
            if not self.archived:
                raise AssertionError("hard delete happened before archive")
            self.hard_deleted = True
            return b""
        if method == "DELETE" and url.endswith(f"/templates/{self.template_id}"):
            if self.archived and not self.hard_deleted:
                raise AssertionError("template delete happened before deck hard delete")
            self.template_deleted = True
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
        if method == "POST" and url.endswith("/api/core/templates"):
            self.template_body = kwargs.get("json_body")
            return {"templateId": self.template_id, "version": 1}
        if method == "POST" and url.endswith("/api/core/decks"):
            if kwargs.get("json_body", {}).get("templateId") != self.template_id:
                raise AssertionError("deck was not linked to the disposable template")
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


class DeckCreationFailureClient(FakeClient):
    def json(self, method: str, url: str, **kwargs: Any) -> dict[str, Any]:
        if method == "POST" and url.endswith("/api/core/decks"):
            raise release_smoke.SmokeFailure("unexpected_http_status", "core", "status=500")
        return super().json(method, url, **kwargs)


class MaintenanceClient:
    def __init__(self, release_id: str, *, learning_release_id: str | None = None) -> None:
        self.release_id = release_id
        self.learning_release_id = learning_release_id or release_id
        self.requests: list[tuple[str, str, str]] = []

    def request(self, method: str, url: str, **kwargs: Any) -> bytes:
        self.requests.append((method, url, str(kwargs["service"])))
        if url.endswith("/api/actuator/health/readiness"):
            return b'{"status":"UP"}'
        raise AssertionError(f"unexpected request {method} {url}")

    def json(self, method: str, url: str, **kwargs: Any) -> dict[str, Any]:
        service = str(kwargs["service"])
        self.requests.append((method, url, service))
        if not url.endswith("/api/actuator/info"):
            raise AssertionError(f"unexpected JSON request {method} {url}")
        release_id = self.learning_release_id if service == "learning" else self.release_id
        return {
            "release": {
                "id": release_id,
                "mode": "maintenance",
                "topology": "identity-learning",
                "runtime": "learning-api" if service == "learning" else "identity-account",
            }
        }


class ReleaseSmokeTest(unittest.TestCase):
    release_id = "a" * 40

    def test_safe_request_retries_transient_gateway_and_transport_failures(self) -> None:
        client = release_smoke.HttpClient(time.monotonic() + 30, 5)
        failures = [
            HTTPError("https://mnema.example", 502, "Bad Gateway", {}, None),
            URLError("endpoint update pending"),
            StubResponse(b'{"status":"UP"}'),
        ]

        with (
            patch.object(release_smoke, "urlopen", side_effect=failures) as urlopen_mock,
            patch.object(release_smoke.time, "sleep") as sleep_mock,
        ):
            result = client.request("GET", "https://mnema.example", service="learning")

        self.assertEqual(b'{"status":"UP"}', result)
        self.assertEqual(3, urlopen_mock.call_count)
        self.assertEqual(2, sleep_mock.call_count)
        failures[0].close()

    def test_safe_request_does_not_retry_non_transient_http_status(self) -> None:
        client = release_smoke.HttpClient(time.monotonic() + 30, 5)
        failure = HTTPError("https://mnema.example", 500, "Internal Server Error", {}, None)

        with (
            patch.object(release_smoke, "urlopen", side_effect=failure) as urlopen_mock,
            patch.object(release_smoke.time, "sleep") as sleep_mock,
            self.assertRaises(release_smoke.SmokeFailure) as raised,
        ):
            client.request("GET", "https://mnema.example", service="learning")

        self.assertEqual("unexpected_http_status", raised.exception.code)
        self.assertEqual("status=500", raised.exception.safe_detail)
        urlopen_mock.assert_called_once()
        sleep_mock.assert_not_called()
        failure.close()

    def test_mutating_request_never_retries_transient_http_status(self) -> None:
        client = release_smoke.HttpClient(time.monotonic() + 30, 5)
        failure = HTTPError("https://mnema.example", 503, "Service Unavailable", {}, None)

        with (
            patch.object(release_smoke, "urlopen", side_effect=failure) as urlopen_mock,
            patch.object(release_smoke.time, "sleep") as sleep_mock,
            self.assertRaises(release_smoke.SmokeFailure) as raised,
        ):
            client.request("POST", "https://mnema.example", service="auth")

        self.assertEqual("unexpected_http_status", raised.exception.code)
        self.assertEqual("status=503", raised.exception.safe_detail)
        urlopen_mock.assert_called_once()
        sleep_mock.assert_not_called()
        failure.close()

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
            self.assertTrue(client.template_deleted)
            self.assertTrue(client.archived)
            self.assertTrue(client.hard_deleted)
            self.assertEqual(["front"], client.template_body["layout"]["front"])
            self.assertEqual(["back"], client.template_body["layout"]["back"])
            self.assertEqual(2, len(client.template_body["fields"]))
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

    def test_deck_creation_failure_still_removes_created_template(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "report.json"
            client = DeckCreationFailureClient(self.release_id)

            with self.assertRaises(release_smoke.SmokeFailure):
                release_smoke.ReleaseSmoke(self.config(report), client).run()

            self.assertTrue(client.template_deleted)
            self.assertFalse(client.archived)
            self.assertFalse(client.hard_deleted)
            persisted = json.loads(report.read_text())
            self.assertEqual("failed", persisted["status"])
            self.assertEqual("core", persisted["failed_service"])

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

    def test_readiness_only_supports_a_pre_identity_rollback_without_credentials(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "report.json"
            config = self.config(
                report,
                login="",
                password="",
                turnstile_bypass_key="",
                readiness_only=True,
            )
            config.validate()
            client = FakeClient(self.release_id)

            result = release_smoke.ReleaseSmoke(config, client).run()

            self.assertEqual("passed", result.status)
            self.assertEqual(0, client.login_count)
            self.assertEqual(
                ["public_readiness", "service_readiness"],
                [step.name for step in result.steps],
            )

    def test_readiness_only_and_identity_only_are_mutually_exclusive(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(ValueError):
                self.config(
                    Path(directory) / "report.json",
                    identity_only=True,
                    readiness_only=True,
                ).validate()

    def test_maintenance_smoke_checks_both_shell_health_and_exact_release_identity(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "report.json"
            config = self.config(
                report,
                public_url="",
                auth_url="",
                login="",
                password="",
                turnstile_bypass_key="",
                mode="maintenance",
                identity_url="https://auth.staging.mnema.app",
                learning_url="https://staging.mnema.app",
            )
            config.validate()
            client = MaintenanceClient(self.release_id)

            result = release_smoke.ReleaseSmoke(config, client).run()

            self.assertEqual("passed", result.status)
            self.assertEqual(
                [
                    "identity_account_readiness",
                    "identity_account_identity",
                    "learning_readiness",
                    "learning_identity",
                ],
                [step.name for step in result.steps],
            )
            self.assertTrue(
                all(url.endswith(("/api/actuator/health/readiness", "/api/actuator/info"))
                    for _, url, _ in client.requests)
            )
            persisted = json.loads(report.read_text())
            self.assertEqual(2, persisted["schema_version"])
            self.assertEqual("identity-learning", persisted["release_topology"])
            self.assertEqual("maintenance", persisted["release_mode"])
            self.assertFalse(persisted["production_eligible"])

    def test_maintenance_smoke_fails_if_one_shell_does_not_bind_the_full_sha(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            config = self.config(
                Path(directory) / "report.json",
                public_url="",
                auth_url="",
                mode="maintenance",
                identity_url="https://auth.staging.mnema.app",
                learning_url="https://staging.mnema.app",
            )
            client = MaintenanceClient(self.release_id, learning_release_id="b" * 40)

            with self.assertRaises(release_smoke.SmokeFailure) as raised:
                release_smoke.ReleaseSmoke(config, client).run()

            self.assertEqual("service_release_mismatch", raised.exception.code)
            self.assertEqual("learning", raised.exception.service)

    def test_maintenance_smoke_rejects_wrong_or_missing_runtime(self) -> None:
        for service, unexpected_runtime in [
            ("identity-account", "learning-api"),
            ("learning", "identity-account"),
            ("identity-account", None),
            ("learning", None),
        ]:
            with self.subTest(service=service, runtime=unexpected_runtime), tempfile.TemporaryDirectory() as directory:
                class WrongRuntimeClient(MaintenanceClient):
                    def json(self, method: str, url: str, **kwargs: Any) -> dict[str, Any]:
                        result = super().json(method, url, **kwargs)
                        result["release"]["runtime"] = unexpected_runtime
                        return result

                smoke = release_smoke.ReleaseSmoke(
                    self.config(Path(directory) / "report.json"), WrongRuntimeClient(self.release_id)
                )
                with self.assertRaises(release_smoke.SmokeFailure) as raised:
                    smoke.verify_maintenance_identity(service, "https://staging.mnema.app")
                self.assertEqual("service_runtime_mismatch", raised.exception.code)
                self.assertEqual(service, raised.exception.service)

    def test_maintenance_smoke_rejects_legacy_selectors_and_non_https_shell_urls(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "report.json"
            with self.assertRaises(ValueError):
                self.config(
                    report,
                    mode="maintenance",
                    identity_url="http://identity.example",
                    learning_url="https://learning.example",
                ).validate()
            with self.assertRaises(ValueError):
                self.config(
                    report,
                    mode="maintenance",
                    identity_url="https://identity.example",
                    learning_url="https://learning.example",
                    identity_only=True,
                ).validate()

    def test_configuration_rejects_non_https_and_weak_bypass_key(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(ValueError):
                self.config(Path(directory) / "report.json", public_url="http://mnema.example").validate()
            with self.assertRaises(ValueError):
                self.config(Path(directory) / "report.json", turnstile_bypass_key="weak").validate()


if __name__ == "__main__":
    unittest.main()
