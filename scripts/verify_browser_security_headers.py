#!/usr/bin/env python3
"""Verify Mnema's generated and hosted browser response-security contract."""

from __future__ import annotations

import argparse
import json
from html.parser import HTMLParser
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urljoin, urlparse
from urllib.request import HTTPRedirectHandler, Request, build_opener

from frontend_release_assets import hashed_assets


BASELINE_CSP = "base-uri 'self'; object-src 'none'; frame-ancestors 'none'"
COMMON_HEADERS = {
    "x-content-type-options": "nosniff",
    "referrer-policy": "strict-origin-when-cross-origin",
    "permissions-policy": (
        "accelerometer=(), camera=(self), geolocation=(), gyroscope=(), magnetometer=(), "
        "microphone=(self), payment=(), usb=()"
    ),
}


class ContractError(RuntimeError):
    pass


@dataclass(frozen=True)
class Response:
    status: int
    headers: dict[str, str]
    body: bytes


def full_policy(auth_origin: str, storage_origin: str) -> str:
    return (
        "default-src 'self'; base-uri 'self'; object-src 'none'; frame-ancestors 'none'; "
        "form-action 'self'; "
        "script-src 'self' https://challenges.cloudflare.com; "
        "script-src-attr 'none'; "
        "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; "
        "font-src 'self' https://fonts.gstatic.com; "
        f"img-src 'self' data: blob: {auth_origin} {storage_origin} https://lh3.googleusercontent.com "
        "https://avatars.githubusercontent.com https://github.com https://avatars.yandex.net; "
        f"media-src 'self' blob: {storage_origin}; "
        f"connect-src 'self' {auth_origin} {storage_origin} https://challenges.cloudflare.com; "
        "frame-src https://challenges.cloudflare.com https://www.youtube-nocookie.com; "
        "worker-src 'self' blob:; manifest-src 'self'"
    )


def expected_headers(mode: str, auth_origin: str, storage_origin: str) -> dict[str, str]:
    expected = dict(COMMON_HEADERS)
    if mode == "development":
        expected["content-security-policy"] = BASELINE_CSP
    elif mode == "staging":
        expected["content-security-policy"] = BASELINE_CSP
        expected["content-security-policy-report-only"] = full_policy(auth_origin, storage_origin)
    elif mode == "prod":
        expected["content-security-policy"] = full_policy(auth_origin, storage_origin)
        expected["strict-transport-security"] = "max-age=300"
    else:
        raise ContractError(f"unsupported mode: {mode}")
    return expected


def parse_nginx_headers(path: Path) -> dict[str, str]:
    text = path.read_text(encoding="utf-8")
    matches = re.findall(r'^add_header\s+([A-Za-z0-9-]+)\s+"([^"]*)"\s+always;$', text, re.MULTILINE)
    if not matches:
        raise ContractError(f"no generated always headers found in {path}")
    headers: dict[str, str] = {}
    for name, value in matches:
        normalized = name.lower()
        if normalized in headers:
            raise ContractError(f"duplicate generated header: {name}")
        headers[normalized] = value
    return headers


def verify_header_values(
    actual: dict[str, str],
    *,
    mode: str,
    auth_origin: str,
    storage_origin: str,
    context: str,
) -> None:
    expected = expected_headers(mode, auth_origin, storage_origin)
    for name, value in expected.items():
        if actual.get(name) != value:
            raise ContractError(f"{context}: unexpected {name} header")

    if mode != "staging" and "content-security-policy-report-only" in actual:
        raise ContractError(f"{context}: report-only CSP is allowed only in staging")
    if mode != "prod" and "strict-transport-security" in actual:
        raise ContractError(f"{context}: HSTS is allowed only in production")

    policy_name = (
        "content-security-policy-report-only" if mode == "staging" else "content-security-policy"
    )
    policy = actual[policy_name]
    if "'unsafe-eval'" in policy or " *" in policy or "https:" in policy.replace("https://", ""):
        raise ContractError(f"{context}: CSP contains a broad or executable source")
    if "'unsafe-inline'" in policy.replace("style-src 'self' 'unsafe-inline'", ""):
        raise ContractError(f"{context}: unsafe-inline escaped the documented style-src exception")


class ScriptInventory(HTMLParser):
    def __init__(self, context):
        super().__init__()
        self.context = context
        self.scripts = []
        self.current = None
        self.noindex = False

    def handle_starttag(self, tag, attrs):
        if any(name.startswith("on") for name, _ in attrs):
            raise ContractError(f"{self.context}: inline event handler would violate script-src-attr 'none'")
        values = dict(attrs)
        if tag == "meta" and values.get("name") == "robots":
            self.noindex = "noindex" in (values.get("content") or "").split(",")
        if tag == "script":
            if len(values) != len(attrs):
                raise ContractError(f"{self.context}: duplicate script attribute")
            self.current = {"attrs": values, "body": ""}

    def handle_data(self, data):
        if self.current is not None:
            self.current["body"] += data

    def handle_endtag(self, tag):
        if tag == "script" and self.current is not None:
            self.scripts.append(self.current)
            self.current = None


def verify_index(index_html: str, context: str) -> None:
    # HTML's application/ld+json and application/json are inert data blocks, not executable JS.
    # Restrict their identity and parse JSON; all other inline script types stay forbidden.
    parser = ScriptInventory(context)
    parser.feed(index_html)
    if parser.current is not None:
        raise ContractError(f"{context}: unclosed script element")
    data_ids = set()
    for script in parser.scripts:
        attrs = script["attrs"]
        if "src" in attrs:
            if not attrs["src"] or script["body"].strip():
                raise ContractError(f"{context}: invalid external script")
            continue
        script_type = attrs.get("type", "").lower()
        identifier = attrs.get("id")
        allowed = {"application/ld+json": "mnema-structured-data", "application/json": "ng-state"}
        if script_type not in allowed or identifier != allowed[script_type] or identifier in data_ids:
            raise ContractError(f"{context}: unexpected inline executable script or data block")
        try:
            value = json.loads(script["body"])
        except (ValueError, TypeError) as error:
            raise ContractError(f"{context}: invalid inline JSON") from error
        if not isinstance(value, dict):
            raise ContractError(f"{context}: inline data must be a JSON object")
        data_ids.add(identifier)
    if not parser.noindex and "mnema-structured-data" not in data_ids:
        raise ContractError(f"{context}: public HTML needs its structured-data block")


def verify_config(args: argparse.Namespace) -> None:
    headers = parse_nginx_headers(args.headers)
    verify_header_values(
        headers,
        mode=args.mode,
        auth_origin=args.auth_origin,
        storage_origin=args.storage_origin,
        context=str(args.headers),
    )
    verify_index(args.index.read_text(encoding="utf-8"), str(args.index))


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def fetch(url: str, expected_status: int) -> Response:
    request = Request(url, headers={"User-Agent": "mnema-browser-security-contract/1"})
    try:
        with build_opener(NoRedirect()).open(request, timeout=20) as response:
            status = response.status
            headers = normalized_http_headers(response.headers)
            body = response.read(2_097_153)
    except HTTPError as error:
        with error:
            status = error.code
            headers = normalized_http_headers(error.headers)
            body = error.read(2_097_153)
    except (TimeoutError, URLError) as error:
        raise ContractError(f"request unavailable: {url}") from error
    if status != expected_status:
        raise ContractError(f"{url}: expected HTTP {expected_status}, got {status}")
    if len(body) > 2_097_152:
        raise ContractError(f"{url}: response is unexpectedly large")
    return Response(status, headers, body)


def normalized_http_headers(message: object) -> dict[str, str]:
    keys = getattr(message, "keys")()
    get_all = getattr(message, "get_all")
    return {
        name.lower(): ", ".join(get_all(name))
        for name in dict.fromkeys(keys)
    }


def verify_hosted(args: argparse.Namespace) -> None:
    parsed = urlparse(args.base_url)
    if parsed.scheme == "http" and parsed.hostname not in {"127.0.0.1", "localhost"}:
        raise ContractError("plain HTTP is allowed only for the loopback container test")
    if parsed.scheme not in {"http", "https"} or not parsed.netloc or parsed.path not in {"", "/"}:
        raise ContractError("base URL must be an HTTP(S) origin")

    mode = args.mode
    for alias, canonical in [("/index.html", "/"), ("/ai/index.html", "/ai")]:
        url = args.base_url.rstrip("/") + alias + "?ref=seo"
        response = fetch(url, 308)
        expected = args.base_url.rstrip("/") + canonical + "?ref=seo"
        if urljoin(url, response.headers.get("location", "")) != expected:
            raise ContractError(f"{url}: generated filename did not redirect to its canonical route")
    cases: list[tuple[str, int, str | None]] = [
        ("/", 200, "public, max-age=0, must-revalidate"),
        ("/login", 200, "no-store"),
        ("/ai", 200, "public, max-age=0, must-revalidate"),
        ("/events", 200, "public, max-age=0, must-revalidate"),
        ("/missing-public-page", 404, "no-store"),
        ("/app-config.js", 200, "no-store"),
        ("/api/ai", 404, "no-store"),
        ("/missing-browser-security-contract.js", 404, None),
    ]

    index_response: Response | None = None
    for path, status, cache_control in cases:
        url = urljoin(args.base_url.rstrip("/") + "/", path.lstrip("/"))
        response = fetch(url, status)
        verify_header_values(
            response.headers,
            mode=mode,
            auth_origin=args.auth_origin,
            storage_origin=args.storage_origin,
            context=url,
        )
        server = response.headers.get("server", "")
        if re.search(r"nginx[/ ]\d", server, re.IGNORECASE):
            raise ContractError(f"{url}: nginx version token is exposed")
        if re.search(rb"nginx[/ ]\d", response.body, re.IGNORECASE):
            raise ContractError(f"{url}: nginx version token is exposed in the response body")
        if cache_control is not None and response.headers.get("cache-control") != cache_control:
            raise ContractError(f"{url}: unexpected cache-control header {response.headers.get('cache-control')!r}")
        if path in ("/", "/ai", "/events", "/login", "/missing-public-page"):
            verify_index(response.body.decode("utf-8"), url)
        if path in ("/login", "/missing-public-page") and response.headers.get("x-robots-tag") != "noindex, follow":
            raise ContractError(f"{url}: missing private/error noindex header")
        if path == "/":
            index_response = response

    if index_response is None:
        raise ContractError("public index was not checked")
    index_html = index_response.body.decode("utf-8")
    verify_index(index_html, args.base_url)
    try:
        assets = hashed_assets(index_html)
    except ValueError as error:
        raise ContractError(f"{args.base_url}: {error}") from error
    for asset in assets:
        asset_url = urljoin(args.base_url.rstrip("/") + "/", asset)
        asset_response = fetch(asset_url, 200)
        verify_header_values(
            asset_response.headers,
            mode=mode,
            auth_origin=args.auth_origin,
            storage_origin=args.storage_origin,
            context=asset_url,
        )
        if "public, immutable" not in asset_response.headers.get("cache-control", ""):
            raise ContractError(f"{asset_url}: hashed asset is not immutable")


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser(description=__doc__)
    subparsers = root.add_subparsers(dest="command", required=True)

    config = subparsers.add_parser("config", help="verify a generated nginx include and built index")
    config.add_argument("--headers", type=Path, required=True)
    config.add_argument("--index", type=Path, required=True)
    config.add_argument("--mode", choices=("development", "staging", "prod"), required=True)
    config.add_argument("--auth-origin", default="https://auth.example.test")
    config.add_argument("--storage-origin", default="https://storage.example.test")
    config.set_defaults(handler=verify_config)

    hosted = subparsers.add_parser("hosted", help="verify representative live frontend responses")
    hosted.add_argument("--base-url", required=True)
    hosted.add_argument("--mode", choices=("staging", "prod"), required=True)
    hosted.add_argument("--auth-origin", required=True)
    hosted.add_argument("--storage-origin", required=True)
    hosted.set_defaults(handler=verify_hosted)
    return root


def main() -> int:
    args = parser().parse_args()
    try:
        args.handler(args)
    except (ContractError, OSError, UnicodeError) as error:
        print(f"browser_security_contract=failed detail={error}", file=sys.stderr)
        return 1
    print(f"browser_security_contract=ok command={args.command} mode={args.mode}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
