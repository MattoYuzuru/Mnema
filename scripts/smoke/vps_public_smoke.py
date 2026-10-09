#!/usr/bin/env python3
"""Public post-deploy smoke for https://mnema.app; stdlib only, no credentials, no bodies printed."""
from __future__ import annotations

import argparse
import json
import re
import ssl
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

SITE = "https://mnema.app"
WWW = "https://www.mnema.app/"
AUTH = "https://auth.mnema.app"
MAX_BODY = 1024 * 1024
SHA_PATTERN = re.compile(r"[0-9a-f]{40}")
BUILD_ID = re.compile(r'window\.MNEMA_APP_CONFIG\.buildId = "([^"\\]*)";')


@dataclass(frozen=True)
class Response:
    status: int
    headers: dict[str, str]
    body: bytes


Fetch = Callable[[str], Response]


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def build_fetch() -> Fetch:
    """Verified TLS, no proxies, no redirects (a redirect is itself an observable result)."""
    opener = urllib.request.build_opener(
        urllib.request.ProxyHandler({}),
        urllib.request.HTTPSHandler(context=ssl.create_default_context()),
        _NoRedirect(),
    )

    def fetch(url: str) -> Response:
        request = urllib.request.Request(url, headers={"User-Agent": "mnema-release-smoke", "Accept": "*/*"})
        try:
            with opener.open(request, timeout=10) as response:
                return Response(response.status, {k.lower(): v for k, v in response.headers.items()},
                                response.read(MAX_BODY))
        except urllib.error.HTTPError as error:
            with error:
                return Response(error.code, {k.lower(): v for k, v in error.headers.items()},
                                error.read(MAX_BODY))

    return fetch


class CheckFailed(Exception):
    """Static, body-free reason."""


def expect_status(response: Response, *expected: int) -> None:
    if response.status not in expected:
        raise CheckFailed(f"status {response.status}")


def check_root(fetch: Fetch, sha: str) -> None:
    response = fetch(SITE + "/")
    expect_status(response, 200)
    if "text/html" not in response.headers.get("content-type", ""):
        raise CheckFailed("not HTML")


def check_www_redirect(fetch: Fetch, sha: str) -> None:
    response = fetch(WWW)
    expect_status(response, 301, 302, 307, 308)
    if not response.headers.get("location", "").startswith(SITE + "/"):
        raise CheckFailed("redirect target differs")


def check_build_id(fetch: Fetch, sha: str) -> None:
    response = fetch(SITE + "/app-config.js")
    expect_status(response, 200)
    match = BUILD_ID.search(response.body.decode("utf-8", "replace"))
    if not match:
        raise CheckFailed("buildId missing")
    if match.group(1) != sha:
        raise CheckFailed("buildId differs from the released commit")


def check_oidc_issuer(fetch: Fetch, sha: str) -> None:
    response = fetch(AUTH + "/.well-known/openid-configuration")
    expect_status(response, 200)
    try:
        issuer = json.loads(response.body).get("issuer")
    except (ValueError, AttributeError):
        raise CheckFailed("not a JSON object") from None
    if issuer != AUTH:
        raise CheckFailed("issuer differs")


HSTS_ONE_YEAR = re.compile(r"(?:^|;)\s*max-age=31536000\s*(?:;|$)", re.IGNORECASE)


def require_hsts(response: Response) -> None:
    if not HSTS_ONE_YEAR.search(response.headers.get("strict-transport-security", "")):
        raise CheckFailed("Strict-Transport-Security max-age=31536000 missing")


def check_frontend_headers(fetch: Fetch, sha: str) -> None:
    response = fetch(SITE + "/")
    expect_status(response, 200)
    require_hsts(response)
    if response.headers.get("x-content-type-options", "").strip().lower() != "nosniff":
        raise CheckFailed("X-Content-Type-Options nosniff missing")
    if not response.headers.get("content-security-policy", "").strip():
        raise CheckFailed("Content-Security-Policy missing")


def check_auth_hsts(fetch: Fetch, sha: str) -> None:
    response = fetch(AUTH + "/.well-known/openid-configuration")
    expect_status(response, 200)
    require_hsts(response)


def check_api_protected(fetch: Fetch, sha: str) -> None:
    expect_status(fetch(SITE + "/api/decks"), 401)


def check_actuator_blocked(fetch: Fetch, sha: str) -> None:
    expect_status(fetch(SITE + "/api/actuator/health"), 404)


CHECKS: tuple[tuple[str, Callable[[Fetch, str], None]], ...] = (
    ("frontend serves HTML", check_root),
    ("www redirects to apex", check_www_redirect),
    ("build identity equals released commit", check_build_id),
    ("OIDC issuer", check_oidc_issuer),
    ("frontend HSTS, nosniff and CSP headers", check_frontend_headers),
    ("auth origin HSTS", check_auth_hsts),
    ("protected API requires a session", check_api_protected),
    ("public actuator is blocked", check_actuator_blocked),
)


def run_check(check, fetch: Fetch, sha: str, deadline: float, clock, sleep) -> str | None:
    """Return None on success or the last static failure reason; retry with backoff until the deadline."""
    delay = 2.0
    while True:
        try:
            check(fetch, sha)
            return None
        except CheckFailed as error:
            reason = str(error)
        except (OSError, urllib.error.URLError, ssl.SSLError):
            reason = "unreachable or TLS failure"
        if clock() + delay > deadline:
            return reason
        sleep(delay)
        delay = min(delay * 2, 15.0)


def run(sha: str, fetch: Fetch, budget: float = 180.0, clock=time.monotonic, sleep=time.sleep):
    if not SHA_PATTERN.fullmatch(sha):
        raise ValueError("sha must be a full lowercase commit SHA")
    deadline = clock() + budget
    return [(name, run_check(check, fetch, sha, deadline, clock, sleep)) for name, check in CHECKS]


def render_markdown(results) -> str:
    lines = ["| Check | Result |", "| --- | --- |"]
    lines += [f"| {name} | {'pass' if reason is None else 'FAIL: ' + reason} |" for name, reason in results]
    return "\n".join(lines) + "\n"


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sha", required=True, help="commit SHA that must be reported as the build identity")
    parser.add_argument("--budget-seconds", type=float, default=180.0)
    parser.add_argument("--summary", type=Path, help="append a Markdown result table (for GITHUB_STEP_SUMMARY)")
    args = parser.parse_args(argv)
    try:
        results = run(args.sha, build_fetch(), args.budget_seconds)
    except ValueError as error:
        print(f"::error::{error}", file=sys.stderr)
        return 2
    for name, reason in results:
        print(("PASS " if reason is None else "FAIL ") + name + ("" if reason is None else f": {reason}"))
    if args.summary:
        with args.summary.open("a", encoding="utf-8") as handle:
            handle.write("### Public smoke\n\n" + render_markdown(results))
    return 1 if any(reason is not None for _, reason in results) else 0


if __name__ == "__main__":
    raise SystemExit(main())
