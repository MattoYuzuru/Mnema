#!/usr/bin/env python3
"""Verify what a Mnema web server does with link previews (#436, docs/operations/link-previews.md) against the stand-in Learning of
scripts/tests/link-previews-fake-learning.conf: a PUBLIC deck gets its tags, LINK a generic noindex card, every failure serves the plain index.html,
a wrong or missing slug is a 301, and assets, other routes and the security/cache headers are untouched."""

from __future__ import annotations

import argparse
import gzip
import http.client
import ssl
import sys
import time
from dataclasses import dataclass
from urllib.parse import urlparse

import verify_browser_security_headers as security


class ContractError(RuntimeError):
    pass


@dataclass(frozen=True)
class Reply:
    status: int
    headers: dict[str, str]
    body: str
    seconds: float


INDEX_CACHE = "public, max-age=0, must-revalidate"
PUBLIC = "/d/Kq7xT3mNpR/ispanskie-glagoly"
HOME_CANONICAL = '<link rel="canonical" href="https://mnema.app/">'
HOME_TITLE = "<title>Mnema</title>"
BLOCK = '<!--# block name="mnema_head" -->'


class Client:
    def __init__(self, base_url: str, insecure: bool) -> None:
        parsed = urlparse(base_url)
        self.https = parsed.scheme == "https"
        self.host = parsed.hostname or ""
        self.port = parsed.port or (443 if self.https else 80)
        self.context = ssl._create_unverified_context() if insecure else ssl.create_default_context()

    def get(self, path: str, headers: dict[str, str] | None = None, method: str = "GET") -> Reply:
        connection = (
            http.client.HTTPSConnection(self.host, self.port, timeout=15, context=self.context)
            if self.https
            else http.client.HTTPConnection(self.host, self.port, timeout=15)
        )
        started = time.monotonic()
        try:
            connection.request(method, path, headers={"User-Agent": "mnema-link-preview-contract/1", **(headers or {})})
            response = connection.getresponse()
            raw = response.read(2_097_153)
            if response.getheader("Content-Encoding") == "gzip":
                raw = gzip.decompress(raw)
            body = raw.decode("utf-8", errors="replace")
            names = {name.lower() for name, _ in response.getheaders()}
            merged = {name: ", ".join(value for key, value in response.getheaders() if key.lower() == name) for name in names}
            return Reply(response.status, merged, body, time.monotonic() - started)
        except (OSError, http.client.HTTPException) as error:
            raise ContractError(f"{method} {path}: request failed: {error}") from error
        finally:
            connection.close()


def expect(condition: bool, message: str) -> None:
    if not condition:
        raise ContractError(message)


def head_of(reply: Reply) -> str:
    return reply.body.split("</head>", 1)[0]


def check_envelope(label: str, reply: Reply, args: argparse.Namespace, cache: str | None = INDEX_CACHE) -> None:
    security.verify_header_values(
        reply.headers, mode="prod", auth_origin=args.auth_origin, storage_origin=args.storage_origin, context=label
    )
    expect("nginx/" not in reply.headers.get("server", ""), f"{label}: nginx version token is exposed")
    if cache is not None:
        expect(reply.headers.get("cache-control") == cache, f"{label}: cache-control is {reply.headers.get('cache-control')!r}")


def check_preview(label: str, reply: Reply, expected: str | None) -> None:
    """X-Mnema-Preview names what the page is, never an id; routes outside /d/ do not carry it."""
    actual = reply.headers.get("x-mnema-preview")
    expect(actual == expected, f"{label}: X-Mnema-Preview is {actual!r}, expected {expected!r}")


def check_untouched_shell(label: str, reply: Reply) -> None:
    """Whatever the head is, the rest of index.html is the built one: scripts, base, JSON-LD and the application root."""
    for needle in ('<script src="/app-config.js"></script>', '<base href="/">', "<app-root></app-root>", 'type="application/ld+json"'):
        expect(needle in reply.body, f"{label}: index.html lost {needle}")
    expect("<!--# include" not in reply.body or BLOCK in reply.body, f"{label}: a bare SSI directive reached the browser")


def check_plain(label: str, reply: Reply, args: argparse.Namespace) -> None:
    """The unchanged index.html: HTTP 200, the defaults of the page, no foreign tags."""
    expect(reply.status == 200, f"{label}: expected 200, got {reply.status}")
    check_envelope(label, reply, args)
    expect("www-authenticate" not in reply.headers, f"{label}: a challenge header reached the page")
    check_untouched_shell(label, reply)
    expect(HOME_TITLE in reply.body and HOME_CANONICAL in reply.body, f"{label}: the default head is missing")
    expect("x-robots-tag" not in reply.headers, f"{label}: a plain page must not carry X-Robots-Tag")
    expect("Испанские глаголы" not in reply.body and "noindex" not in reply.body, f"{label}: a card leaked into a plain page")


def check_default_head_outside_comments(label: str, reply: Reply) -> None:
    expect('<meta name="robots" content="index,follow">' in reply.body, f"{label}: the default robots tag is missing")


def verify(args: argparse.Namespace) -> None:
    client = Client(args.base_url, args.insecure)

    # 1. Other routes: the unchanged index.html (SSI is not enabled there, the block and the include stay inert comments).
    for path in ("/", "/login", "/decks", "/d"):
        reply = client.get(path)
        check_plain(path, reply, args)
        check_preview(path, reply, None)
        expect(BLOCK in reply.body, f"{path}: the block markers are expected in the untouched shell")
        check_default_head_outside_comments(path, reply)

    # 2. A PUBLIC deck: its tags replace the defaults, everything else is the shell, headers and cache are the page's.
    for path in (PUBLIC, PUBLIC + "/", PUBLIC + "?utm_source=telegram", PUBLIC + "?material=3f2a&utm_source=telegram"):
        reply = client.get(path)
        expect(reply.status == 200, f"{path}: expected 200, got {reply.status}")
        check_envelope(path, reply, args)
        check_untouched_shell(path, reply)
        check_preview(path, reply, "card")
        expect("<title>Испанские глаголы — Mnema</title>" in reply.body, f"{path}: the title was not substituted")
        expect('<link rel="canonical" href="https://mnema.app/d/Kq7xT3mNpR/ispanskie-glagoly">' in reply.body, f"{path}: no canonical")
        expect('<meta property="og:title" content="Испанские глаголы">' in reply.body, f"{path}: no og:title")
        expect(HOME_TITLE not in reply.body and HOME_CANONICAL not in reply.body, f"{path}: the defaults are still there next to the card")
        expect(BLOCK not in reply.body and "<!--# include" not in reply.body, f"{path}: SSI markers reached the browser")
        expect(reply.body.count('name="robots"') == 1, f"{path}: exactly one robots tag is expected")
        expect("etag" not in reply.headers and "last-modified" not in reply.headers, f"{path}: validators of the static file describe other bytes")
        expect("x-robots-tag" not in reply.headers, f"{path}: a public deck must not carry X-Robots-Tag")
    head = client.get(PUBLIC, method="HEAD")
    expect(head.status == 200 and head.body == "", "HEAD of a deck page must be 200 without a body")

    # 3. A wrong or missing slug is one 301 to the canonical path, with the query string kept and the security headers on it.
    redirects = (
        ("/d/Kq7xT3mNpR", PUBLIC),
        ("/d/Kq7xT3mNpR/", PUBLIC),
        ("/d/Kq7xT3mNpR/wrong-slug", PUBLIC),
        ("/d/Kq7xT3mNpR/%D0%BD%D0%B5", PUBLIC),
        ("/d/Kq7xT3mNpR/ispanskie-glagoly-2", PUBLIC),
        ("/d/Kq7xT3mNpR/Ispanskie-Glagoly?x=1", PUBLIC + "?x=1"),
        ("/d/Kq7xT3mNpR?material=3f2a&utm_source=telegram", PUBLIC + "?material=3f2a&utm_source=telegram"),
        ("/d/Kq7xT3mNpR/wrong?material=3f2a&utm_source=telegram", PUBLIC + "?material=3f2a&utm_source=telegram"),
    )
    for path, target in redirects:
        reply = client.get(path)
        expect(reply.status == 301, f"{path}: expected 301, got {reply.status}")
        expect(reply.headers.get("location") == target, f"{path}: Location is {reply.headers.get('location')!r}, expected {target!r}")
        check_envelope(path, reply, args, cache=None)
        check_preview(path, reply, "redirect")

    # 4. LINK, INVITE, PRIVATE, unknown: the generic noindex card and no redirect, whatever the slug in the address.
    for path in ("/d/Lk7xT3mNpR", "/d/Lk7xT3mNpR/anything", "/d/Lk7xT3mNpR?material=1"):
        reply = client.get(path)
        expect(reply.status == 200, f"{path}: expected 200, got {reply.status}")
        check_envelope(path, reply, args)
        check_untouched_shell(path, reply)
        check_preview(path, reply, "generic")
        expect('<meta name="robots" content="noindex">' in reply.body, f"{path}: no noindex")
        expect(reply.headers.get("x-robots-tag") == "noindex", f"{path}: X-Robots-Tag is {reply.headers.get('x-robots-tag')!r}")
        expect("canonical" not in head_of(reply).replace(HOME_CANONICAL, ""), f"{path}: a generic card must not carry a canonical")
        expect("index,follow" not in reply.body, f"{path}: the indexable default is still there")

    # 5. Every failure of the gate serves the plain page: 200, defaults, the page's own headers, no challenge, and no long wait.
    for code, why in (("Gt5xT3mNpR", "503"), ("Rk7xT3mNpR", "429"), ("Nf7xT3mNpR", "404, routes disabled"), ("Fb7xT3mNpR", "403"),
                      ("Au7xT3mNpR", "401 with a challenge"), ("Bh7xT3mNpR", "no answer in time")):
        reply = client.get(f"/d/{code}/some-slug")
        check_plain(why, reply, args)
        check_preview(why, reply, "fallback")
        expect(reply.seconds < 2.0, f"{why}: the page waited {reply.seconds:.2f}s for Learning")
        expect("etag" in reply.headers, f"{why}: the fallback is the static page and keeps its validators")
    # A good gate answer and a failing card: the stub (the defaults), not the error page.
    for code, why, label in (("Hd5xT3mNpR", "the card fails after the gate", None), ("Em7xT3mNpR", "empty card", "fallback")):
        reply = client.get(f"/d/{code}")
        expect(reply.status == 200, f"{why}: expected 200, got {reply.status}")
        check_envelope(why, reply, args)
        check_untouched_shell(why, reply)
        if label is not None:
            check_preview(why, reply, label)
        expect(BLOCK not in reply.body and "<!--# include" not in reply.body, f"{why}: SSI markers reached the browser")
        expect(reply.body.count("<title>") == 1, f"{why}: exactly one title is expected")
        expect("500 Internal Server Error" not in reply.body and "<center>" not in reply.body, f"{why}: an error page reached <head>")

    # 6. Shapes that are not a deck address never reach Learning (a card for them would be the fake's 404 anyway).
    for path in ("/d/", "/d/short", "/d/Kq7xT3mNpR/a/b", "/d/0OIlO0IlO0", "/d/Kq7xT3mNpRx"):
        reply = client.get(path)
        check_plain(path, reply, args)
        check_preview(path, reply, "fallback")

    # 7. What the web server sends to Learning: no cookie, no credential, no compression, the visitor's address last, GET for the card.
    reply = client.get(
        "/d/Ec7xT3mNpR",
        headers={"Cookie": "session=SECRET", "Authorization": "Bearer SECRET", "X-Forwarded-For": "198.51.100.7", "Accept-Encoding": "gzip"},
    )
    seen = [line for line in reply.body.splitlines() if 'name="seen"' in line]
    expect(len(seen) == 1, "the stand-in did not report what it received")
    expect("SECRET" not in reply.body, "a credential reached Learning")
    expect("cookie=[]" in seen[0] and "authorization=[]" in seen[0], f"credentials were forwarded: {seen[0]}")
    expect("xff=[198.51.100.7, " in seen[0], f"the visitor's address is not in X-Forwarded-For: {seen[0]}")
    expect("method=GET" in seen[0] and "accept-encoding=[]" in seen[0], f"unexpected request to Learning: {seen[0]}")

    # 8. Assets, runtime config and non-HTML requests are untouched.
    asset = client.get("/d/Kq7xT3mNpR/ispanskie-glagoly.js")
    expect(asset.status == 404, f"a file extension under /d/ must stay a 404, got {asset.status}")
    config = client.get("/app-config.js")
    expect(config.status == 200 and config.headers.get("cache-control") == "no-store", "app-config.js lost no-store")
    security.verify_header_values(config.headers, mode="prod", auth_origin=args.auth_origin, storage_origin=args.storage_origin, context="/app-config.js")
    if args.api_404:
        manifest = client.get("/manifest.webmanifest")
        expect(manifest.status == 200 and "max-age=3600" in manifest.headers.get("cache-control", ""), "the manifest lost its cache policy")
        api = client.get("/api/public/meta/decks/Kq7xT3mNpR")
        expect(api.status == 404 and api.headers.get("cache-control") == "no-store", "/api must stay a 404 in the standalone image")
    print(f"link_previews_contract={args.label}:ok")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--label", required=True)
    parser.add_argument("--insecure", action="store_true", help="accept the self-signed certificate of the local full stack")
    parser.add_argument("--api-404", action="store_true", help="the production host file and the standalone image: /api is a 404 of their own and the manifest has its cache policy")
    parser.add_argument("--auth-origin", default="https://auth.example.test")
    parser.add_argument("--storage-origin", default="https://storage.example.test")
    args = parser.parse_args()
    try:
        verify(args)
    except (ContractError, security.ContractError) as error:
        print(f"link_previews_contract={args.label}:FAILED {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
