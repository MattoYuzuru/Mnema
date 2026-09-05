#!/usr/bin/env python3
"""Deterministic black-box release smoke with disposable content cleanup."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
import time
import uuid
from dataclasses import asdict, dataclass, field
from datetime import UTC, datetime
from html.parser import HTMLParser
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import urljoin
from urllib.request import Request, urlopen


LEGACY_SERVICES = ("auth", "user", "core", "media", "import")
MAINTENANCE_SERVICES = ("identity-account", "learning")
MAINTENANCE_TOPOLOGY = "identity-learning"
MAINTENANCE_MODE = "maintenance"
SHA_PATTERN = re.compile(r"^[0-9a-f]{40}$")
MAIN_BUNDLE_PATTERN = re.compile(r"(?:^|/)main\.[0-9a-f]+\.js$")
READINESS_MAIN_BUNDLE_PATTERN = re.compile(r"(?:^|/)main(?:\.[0-9a-f]+)?\.js$")
SMOKE_EMAIL_PATTERN = re.compile(r"^[^\s@]+@[^\s@]+\.[^\s@]+$")


class SmokeFailure(RuntimeError):
    def __init__(self, code: str, service: str, safe_detail: str = "") -> None:
        super().__init__(safe_detail)
        self.code = code
        self.service = service
        self.safe_detail = safe_detail


class ScriptCollector(HTMLParser):
    def __init__(self) -> None:
        super().__init__()
        self.sources: list[str] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        if tag != "script":
            return
        source = dict(attrs).get("src")
        if source:
            self.sources.append(source)


@dataclass(frozen=True)
class SmokeConfig:
    environment: str
    public_url: str
    auth_url: str
    expected_release_sha: str
    login: str
    password: str
    turnstile_bypass_key: str
    timeout_seconds: int
    request_timeout_seconds: int
    report_path: Path
    force_failure_step: str | None = None
    identity_only: bool = False
    readiness_only: bool = False
    mode: str = "legacy"
    identity_url: str = ""
    learning_url: str = ""

    def validate(self) -> None:
        if not SHA_PATTERN.fullmatch(self.expected_release_sha):
            raise ValueError("expected release SHA must contain 40 lowercase hexadecimal characters")
        if self.timeout_seconds < 30 or self.timeout_seconds > 900:
            raise ValueError("timeout must be between 30 and 900 seconds")
        if self.request_timeout_seconds < 1 or self.request_timeout_seconds > 60:
            raise ValueError("request timeout must be between 1 and 60 seconds")
        if self.mode not in ("legacy", MAINTENANCE_MODE):
            raise ValueError("mode must be legacy or maintenance")
        if self.mode == MAINTENANCE_MODE:
            if self.identity_only or self.readiness_only:
                raise ValueError("maintenance mode does not accept legacy smoke selectors")
            for name, value in (
                ("identity URL", self.identity_url),
                ("learning URL", self.learning_url),
            ):
                if not value.startswith("https://"):
                    raise ValueError(f"{name} must use https")
            return
        for name, value in (("public URL", self.public_url), ("auth URL", self.auth_url)):
            if not value.startswith("https://"):
                raise ValueError(f"{name} must use https")
        if self.identity_only and self.readiness_only:
            raise ValueError("identity-only and readiness-only modes are mutually exclusive")
        if not self.identity_only and not self.readiness_only:
            for name, value in (
                ("smoke login", self.login),
                ("smoke password", self.password),
                ("Turnstile bypass key", self.turnstile_bypass_key),
            ):
                if not value:
                    raise ValueError(f"{name} is required")
            if len(self.turnstile_bypass_key) < 32:
                raise ValueError("Turnstile bypass key must contain at least 32 characters")


@dataclass
class StepResult:
    name: str
    service: str
    status: str
    duration_ms: int
    error_code: str | None = None
    detail: str | None = None


@dataclass
class SmokeReport:
    schema_version: int
    environment: str
    release_id: str
    started_at: str
    completed_at: str | None = None
    status: str = "running"
    failed_service: str | None = None
    steps: list[StepResult] = field(default_factory=list)
    release_topology: str | None = None
    release_mode: str | None = None
    production_eligible: bool | None = None

    def write(self, path: Path) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        payload = asdict(self)
        path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        path.chmod(0o600)


class HttpClient:
    def __init__(self, deadline: float, request_timeout_seconds: int) -> None:
        self.deadline = deadline
        self.request_timeout_seconds = request_timeout_seconds

    def request(
        self,
        method: str,
        url: str,
        *,
        headers: dict[str, str] | None = None,
        json_body: dict[str, Any] | None = None,
        expected_statuses: tuple[int, ...] = (200,),
        max_body_bytes: int = 1_048_576,
        service: str,
    ) -> bytes:
        remaining = self.deadline - time.monotonic()
        if remaining <= 0:
            raise SmokeFailure("smoke_timeout", service)
        timeout = min(float(self.request_timeout_seconds), remaining)
        body = None
        request_headers = {"Accept": "application/json", **(headers or {})}
        if json_body is not None:
            body = json.dumps(json_body, separators=(",", ":")).encode("utf-8")
            request_headers["Content-Type"] = "application/json"
        request = Request(url, data=body, headers=request_headers, method=method)
        try:
            with urlopen(request, timeout=timeout) as response:
                status = response.status
                if status not in expected_statuses:
                    raise SmokeFailure("unexpected_http_status", service, f"status={status}")
                if method == "HEAD":
                    return b""
                data = response.read(max_body_bytes + 1)
        except HTTPError as error:
            raise SmokeFailure("unexpected_http_status", service, f"status={error.code}") from None
        except (TimeoutError, URLError):
            raise SmokeFailure("http_unavailable", service) from None
        if len(data) > max_body_bytes:
            raise SmokeFailure("response_too_large", service)
        return data

    def json(self, *args: Any, **kwargs: Any) -> dict[str, Any]:
        service = str(kwargs["service"])
        raw = self.request(*args, **kwargs)
        try:
            value = json.loads(raw)
        except (UnicodeDecodeError, json.JSONDecodeError):
            raise SmokeFailure("invalid_json", service) from None
        if not isinstance(value, dict):
            raise SmokeFailure("invalid_json_shape", service)
        return value


class ReleaseSmoke:
    def __init__(self, config: SmokeConfig, client: HttpClient) -> None:
        self.config = config
        self.client = client
        self.report = SmokeReport(
            schema_version=2 if config.mode == MAINTENANCE_MODE else 1,
            environment=config.environment,
            release_id=config.expected_release_sha,
            started_at=utc_now(),
            release_topology=MAINTENANCE_TOPOLOGY if config.mode == MAINTENANCE_MODE else None,
            release_mode=config.mode,
            production_eligible=False if config.mode == MAINTENANCE_MODE else None,
        )
        self.access_token: str | None = None
        self.template_id: str | None = None
        self.deck_id: str | None = None

    def run(self) -> SmokeReport:
        failure: SmokeFailure | None = None
        try:
            if self.config.mode == MAINTENANCE_MODE:
                self.step(
                    "identity_account_readiness",
                    "identity-account",
                    lambda: self.verify_maintenance_readiness(
                        "identity-account", self.config.identity_url
                    ),
                )
                self.step(
                    "identity_account_identity",
                    "identity-account",
                    lambda: self.verify_maintenance_identity(
                        "identity-account", self.config.identity_url
                    ),
                )
                self.step(
                    "learning_readiness",
                    "learning",
                    lambda: self.verify_maintenance_readiness(
                        "learning", self.config.learning_url
                    ),
                )
                self.step(
                    "learning_identity",
                    "learning",
                    lambda: self.verify_maintenance_identity(
                        "learning", self.config.learning_url
                    ),
                )
            elif self.config.readiness_only:
                self.step("public_readiness", "frontend", self.verify_public_readiness)
                self.step("service_readiness", "release", self.verify_service_readiness)
            else:
                self.step("public_identity", "frontend", self.verify_public_identity)
                self.step("service_identities", "release", self.verify_service_identities)
            if (
                self.config.mode == "legacy"
                and not self.config.identity_only
                and not self.config.readiness_only
            ):
                self.step("authentication", "auth", self.authenticate)
                self.step("token_renewal", "auth", self.renew_token)
                self.step("content_and_review", "core", self.exercise_content_and_review)
        except SmokeFailure as error:
            failure = error
        finally:
            if self.deck_id is not None or self.template_id is not None:
                try:
                    self.step("fixture_cleanup", "core", self.cleanup_fixture)
                except SmokeFailure as cleanup_error:
                    failure = failure or cleanup_error

        self.report.completed_at = utc_now()
        if failure is None:
            self.report.status = "passed"
        else:
            self.report.status = "failed"
            self.report.failed_service = failure.service
        self.report.write(self.config.report_path)
        if failure is not None:
            raise failure
        return self.report

    def step(self, name: str, service: str, operation: Any) -> None:
        started = time.monotonic()
        try:
            if self.config.force_failure_step == name:
                raise SmokeFailure("forced_failure_drill", service)
            operation()
        except SmokeFailure as error:
            duration = int((time.monotonic() - started) * 1000)
            self.report.steps.append(
                StepResult(name, error.service, "failed", duration, error.code, error.safe_detail or None)
            )
            print(f"smoke_step={name} service={error.service} status=failed code={error.code}")
            raise
        duration = int((time.monotonic() - started) * 1000)
        self.report.steps.append(StepResult(name, service, "passed", duration))
        print(f"smoke_step={name} service={service} status=passed duration_ms={duration}")

    def verify_public_identity(self) -> None:
        self.verify_public_reachability(MAIN_BUNDLE_PATTERN)
        runtime_config = self.client.request(
            "GET", self.url("/app-config.js"), service="frontend", max_body_bytes=131_072
        ).decode("utf-8")
        if self.config.expected_release_sha not in runtime_config:
            raise SmokeFailure("frontend_release_mismatch", "frontend")
        if not re.search(r'MNEMA_APP_CONFIG\.features\.aiEnabled\s*=\s*false\s*;', runtime_config):
            raise SmokeFailure("hosted_ai_not_disabled", "frontend")

    def verify_public_readiness(self) -> None:
        self.verify_public_reachability(READINESS_MAIN_BUNDLE_PATTERN)

    def verify_public_reachability(self, bundle_pattern: re.Pattern[str]) -> list[str]:
        html = self.client.request("GET", self.url("/"), service="frontend").decode("utf-8")
        collector = ScriptCollector()
        collector.feed(html)
        main_sources = [source for source in collector.sources if bundle_pattern.search(source)]
        if len(main_sources) != 1:
            raise SmokeFailure("main_bundle_identity_missing", "frontend")
        self.client.request("HEAD", urljoin(self.config.public_url + "/", main_sources[0]), service="frontend")
        return main_sources

    def verify_service_readiness(self) -> None:
        endpoints = {
            "auth": f"{self.config.auth_url.rstrip('/')}/actuator/health/readiness",
            "user": self.url("/api/user/actuator/health/readiness"),
            "core": self.url("/api/core/actuator/health/readiness"),
            "media": self.url("/api/media/actuator/health/readiness"),
            "import": self.url("/api/import/actuator/health/readiness"),
        }
        for service, endpoint in endpoints.items():
            self.client.request("GET", endpoint, service=service, max_body_bytes=131_072)

    def verify_service_identities(self) -> None:
        endpoints = {
            "auth": f"{self.config.auth_url.rstrip('/')}/actuator/info",
            "user": self.url("/api/user/actuator/info"),
            "core": self.url("/api/core/actuator/info"),
            "media": self.url("/api/media/actuator/info"),
            "import": self.url("/api/import/actuator/info"),
        }
        for service, endpoint in endpoints.items():
            payload = self.client.json("GET", endpoint, service=service)
            build = payload.get("build")
            if not isinstance(build, dict) or build.get("id") != self.config.expected_release_sha:
                raise SmokeFailure("service_release_mismatch", service)

    def verify_maintenance_readiness(self, service: str, base_url: str) -> None:
        self.client.request(
            "GET",
            f"{base_url.rstrip('/')}/api/actuator/health/readiness",
            service=service,
            max_body_bytes=131_072,
        )

    def verify_maintenance_identity(self, service: str, base_url: str) -> None:
        payload = self.client.json(
            "GET",
            f"{base_url.rstrip('/')}/api/actuator/info",
            service=service,
        )
        release = payload.get("release")
        if not isinstance(release, dict) or release.get("id") != self.config.expected_release_sha:
            raise SmokeFailure("service_release_mismatch", service)
        if release.get("mode") != MAINTENANCE_MODE:
            raise SmokeFailure("service_release_mode_mismatch", service)
        if release.get("topology") != MAINTENANCE_TOPOLOGY:
            raise SmokeFailure("service_release_topology_mismatch", service)
        expected_runtime = {"identity-account": "identity-account", "learning": "learning-api"}[service]
        if release.get("runtime") != expected_runtime:
            raise SmokeFailure("service_runtime_mismatch", service)

    def authenticate(self) -> None:
        try:
            self.access_token = self.login()
        except SmokeFailure as error:
            if not is_missing_smoke_account(error):
                raise
            self.bootstrap_smoke_account()
            self.access_token = self.login()

    def renew_token(self) -> None:
        previous = self.require_token()
        renewed = self.login()
        if renewed == previous:
            raise SmokeFailure("token_not_renewed", "auth")
        self.access_token = renewed

    def login(self) -> str:
        payload = self.client.json(
            "POST",
            f"{self.config.auth_url.rstrip('/')}/auth/login",
            headers={"X-Mnema-Smoke-Key": self.config.turnstile_bypass_key},
            json_body={"login": self.config.login, "password": self.config.password},
            service="auth",
        )
        token = payload.get("access_token")
        if not isinstance(token, str) or not token:
            raise SmokeFailure("access_token_missing", "auth")
        if str(payload.get("token_type", "")).lower() != "bearer":
            raise SmokeFailure("token_type_invalid", "auth")
        return token

    def bootstrap_smoke_account(self) -> None:
        email = self.config.login.strip().lower()
        if not SMOKE_EMAIL_PATTERN.fullmatch(email):
            raise SmokeFailure("smoke_account_bootstrap_requires_email", "auth")
        self.client.json(
            "POST",
            f"{self.config.auth_url.rstrip('/')}/auth/register",
            headers={"X-Mnema-Smoke-Key": self.config.turnstile_bypass_key},
            json_body={
                "email": email,
                "username": smoke_username(email),
                "password": self.config.password,
            },
            expected_statuses=(201,),
            service="auth",
        )
        print("smoke_account_bootstrap=created")

    def exercise_content_and_review(self) -> None:
        fixture_id = uuid.uuid4().hex
        template = self.client.json(
            "POST",
            self.url("/api/core/templates"),
            headers=self.auth_headers(),
            json_body={
                "name": f"Release smoke template {fixture_id[:12]}",
                "description": "Disposable release verification fixture",
                "isPublic": False,
                "layout": {"front": ["front"], "back": ["back"]},
                "fields": [
                    {
                        "name": "front",
                        "label": "Front",
                        "fieldType": "text",
                        "isRequired": True,
                        "isOnFront": True,
                        "orderIndex": 0,
                        "defaultValue": None,
                        "helpText": None,
                    },
                    {
                        "name": "back",
                        "label": "Back",
                        "fieldType": "text",
                        "isRequired": True,
                        "isOnFront": False,
                        "orderIndex": 0,
                        "defaultValue": None,
                        "helpText": None,
                    },
                ],
            },
            service="core",
        )
        self.template_id = required_uuid(template, "templateId", "template_id_missing")
        deck = self.client.json(
            "POST",
            self.url("/api/core/decks"),
            headers=self.auth_headers(),
            json_body={
                "name": f"Release smoke {fixture_id[:12]}",
                "description": "Disposable release verification fixture",
                "templateId": self.template_id,
                "isPublic": False,
                "isListed": False,
                "language": "en",
                "tags": ["release-smoke"],
            },
            expected_statuses=(201,),
            service="core",
        )
        self.deck_id = required_uuid(deck, "userDeckId", "deck_id_missing")
        card = self.client.json(
            "POST",
            self.url(f"/api/core/decks/{self.deck_id}/cards"),
            headers=self.auth_headers(),
            json_body={
                "content": {"front": "release-smoke-question", "back": "release-smoke-answer"},
                "orderIndex": 1,
                "tags": ["release-smoke"],
                "personalNote": None,
                "contentOverride": None,
                "checksum": fixture_id,
            },
            expected_statuses=(201,),
            service="core",
        )
        card_id = required_uuid(card, "userCardId", "card_id_missing")
        next_card = self.client.json(
            "GET",
            self.url(f"/api/core/review/decks/{self.deck_id}/next"),
            headers=self.auth_headers(),
            service="core",
        )
        if next_card.get("userCardId") != card_id:
            raise SmokeFailure("review_card_mismatch", "core")
        answer = self.client.json(
            "POST",
            self.url(f"/api/core/review/decks/{self.deck_id}/cards/{card_id}/answer"),
            headers=self.auth_headers(),
            json_body={
                "rating": "GOOD",
                "responseMs": 1,
                "source": "api",
                "features": {"releaseSmoke": True},
            },
            service="core",
        )
        if answer.get("answeredCardId") != card_id or str(answer.get("rating", "")).upper() != "GOOD":
            raise SmokeFailure("review_answer_mismatch", "core")

    def cleanup_fixture(self) -> None:
        failure: SmokeFailure | None = None
        if self.deck_id is not None:
            try:
                self.client.request(
                    "DELETE",
                    self.url(f"/api/core/decks/{self.deck_id}"),
                    headers=self.auth_headers(),
                    expected_statuses=(204,),
                    service="core",
                )
                self.client.request(
                    "DELETE",
                    self.url(f"/api/core/decks/{self.deck_id}/hard"),
                    headers=self.auth_headers(),
                    expected_statuses=(204,),
                    service="core",
                )
                self.deck_id = None
            except SmokeFailure as error:
                failure = error

        if self.template_id is not None:
            try:
                self.client.request(
                    "DELETE",
                    self.url(f"/api/core/templates/{self.template_id}"),
                    headers=self.auth_headers(),
                    service="core",
                )
                self.template_id = None
            except SmokeFailure as error:
                failure = failure or error

        if failure is not None:
            raise failure

    def auth_headers(self) -> dict[str, str]:
        return {"Authorization": f"Bearer {self.require_token()}"}

    def require_token(self) -> str:
        if not self.access_token:
            raise SmokeFailure("access_token_missing", "auth")
        return self.access_token

    def url(self, path: str) -> str:
        return f"{self.config.public_url.rstrip('/')}{path}"


def required_uuid(payload: dict[str, Any], key: str, error_code: str) -> str:
    value = payload.get(key)
    try:
        return str(uuid.UUID(str(value)))
    except (ValueError, TypeError, AttributeError):
        raise SmokeFailure(error_code, "core") from None


def is_missing_smoke_account(error: SmokeFailure) -> bool:
    return (
        error.code == "unexpected_http_status"
        and error.service == "auth"
        and error.safe_detail == "status=401"
    )


def smoke_username(email: str) -> str:
    digest = hashlib.sha256(email.casefold().encode("utf-8")).hexdigest()[:16]
    return f"mnema-smoke-{digest}"


def utc_now() -> str:
    return datetime.now(UTC).isoformat().replace("+00:00", "Z")


def parse_args() -> SmokeConfig:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--environment", required=True)
    parser.add_argument("--mode", choices=("legacy", MAINTENANCE_MODE), default="legacy")
    parser.add_argument("--public-url", default="")
    parser.add_argument("--auth-url", default="")
    parser.add_argument("--identity-url", default="")
    parser.add_argument("--learning-url", default="")
    parser.add_argument("--expected-release-sha", required=True)
    parser.add_argument("--report", required=True, type=Path)
    parser.add_argument("--timeout-seconds", type=int, default=300)
    parser.add_argument("--request-timeout-seconds", type=int, default=15)
    parser.add_argument("--force-failure-step")
    parser.add_argument("--identity-only", action="store_true")
    parser.add_argument("--readiness-only", action="store_true")
    args = parser.parse_args()
    config = SmokeConfig(
        environment=args.environment,
        public_url=args.public_url,
        auth_url=args.auth_url,
        expected_release_sha=args.expected_release_sha,
        login=os.environ.get("SMOKE_LOGIN", ""),
        password=os.environ.get("SMOKE_PASSWORD", ""),
        turnstile_bypass_key=os.environ.get("SMOKE_TURNSTILE_BYPASS_KEY", ""),
        timeout_seconds=args.timeout_seconds,
        request_timeout_seconds=args.request_timeout_seconds,
        report_path=args.report,
        force_failure_step=args.force_failure_step,
        identity_only=args.identity_only,
        readiness_only=args.readiness_only,
        mode=args.mode,
        identity_url=args.identity_url,
        learning_url=args.learning_url,
    )
    config.validate()
    return config


def main() -> int:
    try:
        config = parse_args()
        deadline = time.monotonic() + config.timeout_seconds
        ReleaseSmoke(config, HttpClient(deadline, config.request_timeout_seconds)).run()
    except (SmokeFailure, ValueError) as error:
        code = error.code if isinstance(error, SmokeFailure) else "invalid_configuration"
        print(f"::error::release smoke failed code={code}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
