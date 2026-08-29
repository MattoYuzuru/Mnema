#!/usr/bin/env python3
"""Verify Mnema's generated and hosted browser response-security contract."""

from __future__ import annotations

import argparse
import base64
import hashlib
import re
import sys
from dataclasses import dataclass
from html.parser import HTMLParser
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urljoin, urlparse
from urllib.request import Request, urlopen


JSON_LD_HASH = "sha256-VR45d+4Tpmsv5J0dHbmYAic5u7F3Ttjk763rpC0sZHI="
BASELINE_CSP = "base-uri 'self'; object-src 'none'; frame-ancestors 'none'"
COMMON_HEADERS = {
    "x-content-type-options": "nosniff",
    "referrer-policy": "strict-origin-when-cross-origin",
    "permissions-policy": (
        "accelerometer=(), camera=(), geolocation=(), gyroscope=(), magnetometer=(), "
        "microphone=(), payment=(), usb=()"
    ),
}


class ContractError(RuntimeError):
    pass


class ScriptCollector(HTMLParser):
    def __init__(self) -> None:
        super().__init__()
        self.sources: list[str] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        if tag == "script":
            source = dict(attrs).get("src")
            if source:
                self.sources.append(source)


@dataclass(frozen=True)
class Response:
    status: int
    headers: dict[str, str]
    body: bytes


def full_policy(auth_origin: str, storage_origin: str) -> str:
    return (
        "default-src 'self'; base-uri 'self'; object-src 'none'; frame-ancestors 'none'; "
        "form-action 'self'; "
        f"script-src 'self' '{JSON_LD_HASH}' https://challenges.cloudflare.com; "
        "script-src-attr 'none'; "
        "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; "
        "font-src 'self' https://fonts.gstatic.com; "
        f"img-src 'self' data: blob: {storage_origin} https://lh3.googleusercontent.com "
        "https://avatars.githubusercontent.com https://github.com https://avatars.yandex.net; "
        f"media-src 'self' blob: {storage_origin}; "
        f"connect-src 'self' {auth_origin} {storage_origin} https://challenges.cloudflare.com; "
        "frame-src https://challenges.cloudflare.com; worker-src 'self' blob:; manifest-src 'self'"
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


def verify_index(index_html: str, context: str) -> None:
    if re.search(r"<[^>]+\son[a-z]+\s*=", index_html, re.IGNORECASE):
        raise ContractError(f"{context}: inline event handler would violate script-src-attr 'none'")
    inline_scripts = re.findall(
        r'<script\s+type="application/ld\+json">(.*?)</script>', index_html, re.DOTALL
    )
    if len(inline_scripts) != 1:
        raise ContractError(f"{context}: expected exactly one JSON-LD inline script")
    digest = base64.b64encode(hashlib.sha256(inline_scripts[0].encode()).digest()).decode()
    if f"sha256-{digest}" != JSON_LD_HASH:
        raise ContractError(f"{context}: JSON-LD hash does not match the CSP allowlist")

    without_json_ld = re.sub(
        r'<script\s+type="application/ld\+json">.*?</script>', "", index_html, flags=re.DOTALL
    )
    remaining_scripts = re.findall(r"<script[^>]*>", without_json_ld, re.IGNORECASE)
    if any(not re.search(r"\ssrc=", tag, re.IGNORECASE) for tag in remaining_scripts):
        raise ContractError(f"{context}: unexpected inline executable script")


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


def fetch(url: str, expected_status: int) -> Response:
    request = Request(url, headers={"User-Agent": "mnema-browser-security-contract/1"})
    try:
        with urlopen(request, timeout=20) as response:
            status = response.status
            headers = normalized_http_headers(response.headers)
            body = response.read(2_097_153)
    except HTTPError as error:
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
    cases: list[tuple[str, int, str | None]] = [
        ("/", 200, "public, max-age=0, must-revalidate"),
        ("/login", 200, "public, max-age=0, must-revalidate"),
        ("/app-config.js", 200, "no-store"),
        ("/api/ai", 503, "no-store"),
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
            raise ContractError(f"{url}: unexpected cache-control header")
        if path == "/":
            index_response = response

    if index_response is None:
        raise ContractError("public index was not checked")
    index_html = index_response.body.decode("utf-8")
    verify_index(index_html, args.base_url)
    collector = ScriptCollector()
    collector.feed(index_html)
    main_sources = [source for source in collector.sources if re.search(r"(?:^|/)main\.[0-9a-f]+\.js$", source)]
    if len(main_sources) != 1:
        raise ContractError(f"{args.base_url}: hashed main bundle was not found")
    asset_url = urljoin(args.base_url.rstrip("/") + "/", main_sources[0])
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
