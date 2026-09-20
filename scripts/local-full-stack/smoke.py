#!/usr/bin/env python3
"""Bounded smoke for the persistent local HTTPS composition; uses stdlib only."""

import argparse
import base64
import hashlib
import http.client
import json
import os
from pathlib import Path
import secrets
import ssl
from http.cookies import SimpleCookie
from urllib.parse import parse_qs, urlencode, urlsplit
import uuid


def require(condition, message):
    if not condition:
        raise AssertionError(message)


class Client:
    def __init__(self, origin, context):
        parsed = urlsplit(origin)
        require(parsed.scheme == "https" and parsed.hostname == "localhost" and parsed.port, "invalid local origin")
        self.host = parsed.hostname
        self.port = parsed.port
        self.context = context
        self.cookies = {}

    def request(self, method, path, payload=None, bearer=None, form=False, csrf=False, headers=None):
        request_headers = dict(headers or {})
        if self.cookies:
            request_headers["Cookie"] = "; ".join(f"{key}={value}" for key, value in self.cookies.items())
        if bearer:
            request_headers["Authorization"] = "Bearer " + bearer
        if csrf:
            status, _, value = self.request("GET", "/api/accounts/csrf")
            require(status == 200 and isinstance(value, dict), "Identity CSRF endpoint unavailable")
            request_headers[value["headerName"]] = value["token"]
            request_headers["Cookie"] = "; ".join(f"{key}={value}" for key, value in self.cookies.items())
        body = None
        if payload is not None:
            body = urlencode(payload) if form else json.dumps(payload, separators=(",", ":"))
            request_headers["Content-Type"] = (
                "application/x-www-form-urlencoded" if form else "application/json"
            )
        connection = http.client.HTTPSConnection(self.host, self.port, context=self.context, timeout=10)
        try:
            connection.request(method, path, body, request_headers)
            response = connection.getresponse()
            raw = response.read(1_048_577)
            require(len(raw) <= 1_048_576, "local response exceeded smoke bound")
            response_headers = response.getheaders()
            for name, value in response_headers:
                if name.lower() == "set-cookie":
                    parsed_cookie = SimpleCookie(value)
                    for key, morsel in parsed_cookie.items():
                        if morsel["max-age"] == "0":
                            self.cookies.pop(key, None)
                        else:
                            self.cookies[key] = morsel.value
            try:
                decoded = json.loads(raw) if raw else None
            except (UnicodeDecodeError, ValueError):
                decoded = raw.decode("utf-8", errors="replace")
            return response.status, {key.lower(): value for key, value in response_headers}, decoded
        finally:
            connection.close()


def load_or_create_account(path):
    if path.exists():
        value = json.loads(path.read_text())
        require(set(value) == {"email", "login", "password", "deckId", "captureId"}, "invalid smoke account state")
        return value, False
    suffix = secrets.token_hex(8)
    return {
        "email": f"local-smoke-{suffix}@example.invalid",
        "login": f"local_smoke_{suffix}",
        "password": secrets.token_urlsafe(32),
        "deckId": None,
        "captureId": None,
    }, True


def token(identity, redirect, account):
    status, _, _ = identity.request("POST", "/api/accounts/login", {
        "login": account["login"], "password": account["password"]
    }, csrf=True)
    require(status == 200, "persistent local smoke account login failed; reset local data and smoke state together")
    verifier = secrets.token_urlsafe(48)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).decode().rstrip("=")
    state = secrets.token_urlsafe(16)
    query = urlencode({
        "response_type": "code", "client_id": "mnema-web", "redirect_uri": redirect,
        "scope": "openid learning.read learning.write", "state": state,
        "code_challenge": challenge, "code_challenge_method": "S256",
    })
    status, headers, _ = identity.request("GET", "/oauth2/authorize?" + query)
    require(status == 302, "PKCE authorization failed")
    callback = urlsplit(headers.get("location", ""))
    values = parse_qs(callback.query)
    require(callback.scheme == "https" and callback.netloc == urlsplit(redirect).netloc,
            "PKCE callback escaped the local frontend origin")
    require(values.get("state") == [state] and len(values.get("code", [])) == 1, "invalid PKCE callback")
    exchange = Client(f"https://localhost:{identity.port}", identity.context)
    status, _, result = exchange.request("POST", "/oauth2/token", {
        "grant_type": "authorization_code", "client_id": "mnema-web", "redirect_uri": redirect,
        "code": values["code"][0], "code_verifier": verifier,
    }, form=True)
    require(status == 200 and isinstance(result, dict) and result.get("access_token"), "PKCE token exchange failed")
    return result["access_token"]


def full_smoke(web, identity, state_file):
    account, fresh = load_or_create_account(state_file)
    if fresh:
        status, _, _ = identity.request("POST", "/api/accounts/register", {
            "email": account["email"], "loginName": account["login"], "password": account["password"],
            "profileUsername": account["login"],
        }, csrf=True)
        require(status == 201, "local smoke account registration failed")
    access = token(identity, f"https://localhost:{web.port}/auth/callback", account)
    status, headers, page = web.request("GET", "/api/decks?limit=20", bearer=access)
    require(status == 200 and isinstance(page, dict) and isinstance(page.get("items"), list),
            "same-origin authenticated Learning route failed")
    require(headers.get("cache-control") == "private, no-store", "private Learning cache boundary missing")
    if fresh:
        status, _, result = web.request("POST", "/api/decks", {
            "commandId": str(uuid.uuid4()),
            "metadata": {"title": "Local launcher smoke", "description": "Persistent HTTPS composition"},
        }, bearer=access)
        require(status == 201 and isinstance(result, dict), "local authoring Deck create failed")
        account["deckId"] = result["deck"]["deckId"]
        status, _, capture = web.request("POST", "/api/capture-notes", {
            "commandId": str(uuid.uuid4()), "deckId": account["deckId"],
            "source": "local-full-stack-smoke", "text": "Persistent authoring smoke",
        }, bearer=access)
        require(status == 201 and isinstance(capture, dict), "local Capture authoring failed")
        account["captureId"] = capture["capture"]["noteId"]
        temporary = state_file.with_suffix(".tmp")
        temporary.write_text(json.dumps(account, separators=(",", ":")))
        os.chmod(temporary, 0o600)
        temporary.replace(state_file)
    status, _, _ = web.request("GET", f"/api/decks/{account['deckId']}", bearer=access)
    require(status == 200, "persistent smoke Deck did not survive restart")
    status, _, _ = web.request("GET", f"/api/capture-notes/{account['captureId']}", bearer=access)
    require(status == 200, "persistent Capture did not survive restart")

    # Temporary fail-closed integration point until #219 provides the Study runtime.
    anonymous, _, _ = web.request("POST", f"/api/decks/{account['deckId']}/study-sessions", {})
    authenticated, _, _ = web.request(
        "POST", f"/api/decks/{account['deckId']}/study-sessions", {}, bearer=access
    )
    require(anonymous == 401 and authenticated == 404,
            "Study dependency probe changed; replace it with the #219 end-to-end smoke before merge")
    print(json.dumps({
        "state": "passed", "https": True, "pkce": True, "sameOriginLearning": True,
        "persistentAuthoring": True, "study": "fail-closed probe only; #219 not integrated",
        "accountReused": not fresh,
    }, sort_keys=True))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--web-origin", required=True)
    parser.add_argument("--identity-origin", required=True)
    parser.add_argument("--ca", type=Path, required=True)
    parser.add_argument("--state-file", type=Path, required=True)
    parser.add_argument("--readiness-only", action="store_true")
    args = parser.parse_args()
    context = ssl.create_default_context(cafile=str(args.ca))
    web, identity = Client(args.web_origin, context), Client(args.identity_origin, context)
    require(web.request("GET", "/api/actuator/health/readiness")[0] == 200, "Learning readiness failed")
    require(identity.request("GET", "/api/actuator/health/readiness")[0] == 200, "Identity readiness failed")
    status, _, body = web.request("GET", "/app-config.js")
    require(status == 200 and isinstance(body, str) and args.identity_origin in body,
            "frontend runtime Identity configuration missing")
    status, headers, _ = web.request("GET", "/api/decks")
    require(status == 401 and "set-cookie" not in headers, "anonymous same-origin Learning must fail closed")
    if args.readiness_only:
        print(json.dumps({"state": "ready", "https": True, "sameOriginLearning": True}, sort_keys=True))
    else:
        args.state_file.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        full_smoke(web, identity, args.state_file)


if __name__ == "__main__":
    try:
        main()
    except (AssertionError, OSError, ssl.SSLError, ValueError, KeyError, json.JSONDecodeError) as error:
        print(json.dumps({"state": "failed", "reason": str(error)}))
        raise SystemExit(1)
