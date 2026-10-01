#!/usr/bin/env python3
"""Bounded media smoke for the local full stack; stdlib only.

Uploads a generated PNG and MP3 through the real Learning API and signed object-storage
URLs, waits for the processor to mark each asset READY, then downloads the playback
variant. It keeps its own synthetic account (separate from smoke.py) in a private file.
"""

import argparse
import base64
import hashlib
import http.client
import json
import os
from pathlib import Path
import secrets
import socket
import ssl
import struct
import time
from http.cookies import SimpleCookie
from urllib.parse import parse_qs, urlencode, urlsplit
import uuid
import zlib


STORAGE_HOST = "storage.mnema.localhost"
ACCOUNT_KEYS = {"schemaVersion", "email", "login", "password"}
POLL_TIMEOUT_SECONDS = 240
MAX_BODY_BYTES = 1_048_576


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def png_fixture():
    """A valid 32x32 RGB gradient PNG with a random text chunk so every run is unique content."""
    def chunk(kind, data):
        body = kind + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body))

    rows = b"".join(
        b"\x00" + b"".join(bytes((x * 8, y * 8, 160)) for x in range(32)) for y in range(32)
    )
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 32, 32, 8, 2, 0, 0, 0))
            + chunk(b"tEXt", b"Comment\x00" + secrets.token_hex(8).encode())
            + chunk(b"IDAT", zlib.compress(rows)) + chunk(b"IEND", b""))


def mp3_fixture(frames=40):
    """About one second of silence: MPEG-1 Layer III, 128 kbit/s, 44.1 kHz, mono, all-zero payload."""
    frame = b"\xff\xfb\x90\xc4" + bytes(413)
    return frame * frames


FIXTURES = (
    # kind, MIME declared at upload, bytes, signature check on the processed variant
    ("image", "image/png", png_fixture, lambda data: data[:4] == b"RIFF" and data[8:12] == b"WEBP"),
    ("audio", "audio/mpeg", mp3_fixture, lambda data: data[4:8] == b"ftyp"),
)


class Client:
    """HTTPS JSON client for one local origin with a tiny cookie jar."""

    def __init__(self, origin, context):
        parsed = urlsplit(origin)
        require(parsed.scheme == "https" and parsed.hostname == "localhost" and parsed.port, "invalid local origin")
        self.port = parsed.port
        self.context = context
        self.cookies = {}

    def request(self, method, path, payload=None, bearer=None, form=False, csrf=False, headers=None,
                raw_response=False):
        headers = dict(headers or {})
        if csrf:
            status, _, value = self.request("GET", "/api/accounts/csrf")
            require(status == 200 and isinstance(value, dict), "Identity CSRF endpoint unavailable")
            headers[value["headerName"]] = value["token"]
        if self.cookies:
            headers["Cookie"] = "; ".join(f"{key}={value}" for key, value in self.cookies.items())
        if bearer:
            headers["Authorization"] = "Bearer " + bearer
        body = payload if isinstance(payload, bytes) else None
        if payload is not None and body is None:
            body = urlencode(payload) if form else json.dumps(payload, separators=(",", ":"))
            headers.setdefault("Content-Type", "application/x-www-form-urlencoded" if form else "application/json")
        connection = http.client.HTTPSConnection("localhost", self.port, context=self.context, timeout=15)
        try:
            connection.request(method, path, body, headers)
            response = connection.getresponse()
            raw = response.read(MAX_BODY_BYTES + 1)
            require(len(raw) <= MAX_BODY_BYTES, "local response exceeded smoke bound")
            response_headers = response.getheaders()
            for name, value in response_headers:
                if name.lower() == "set-cookie":
                    for key, morsel in SimpleCookie(value).items():
                        if morsel["max-age"] == "0":
                            self.cookies.pop(key, None)
                        else:
                            self.cookies[key] = morsel.value
            if raw_response:
                return response.status, {key.lower(): value for key, value in response_headers}, raw
            try:
                decoded = json.loads(raw) if raw else None
            except (UnicodeDecodeError, ValueError):
                decoded = raw.decode("utf-8", errors="replace")
            return response.status, {key.lower(): value for key, value in response_headers}, decoded
        finally:
            connection.close()


class StorageConnection(http.client.HTTPSConnection):
    """Dials loopback but validates the certificate for the storage name, as a browser would."""

    def __init__(self, port, context):
        super().__init__(STORAGE_HOST, port, context=context, timeout=30)
        self.storage_context = context

    def connect(self):
        raw = socket.create_connection(("127.0.0.1", self.port), timeout=self.timeout)
        self.sock = self.storage_context.wrap_socket(raw, server_hostname=STORAGE_HOST)


def storage_request(method, signed_url, storage_port, context, body=None, headers=None):
    target = urlsplit(signed_url)
    require(target.scheme == "https" and target.hostname == STORAGE_HOST and target.port == storage_port,
            "signed URL does not target the local object-storage origin")
    connection = StorageConnection(storage_port, context)
    try:
        connection.request(method, target.path + ("?" + target.query if target.query else ""), body, headers or {})
        response = connection.getresponse()
        data = response.read(64 * 1024 * 1024)
        return response.status, data
    finally:
        connection.close()


def load_or_create_account(path):
    if path.exists():
        value = json.loads(path.read_text())
        require(isinstance(value, dict) and set(value) == ACCOUNT_KEYS and value["schemaVersion"] == 1
                and all(isinstance(value[key], str) and value[key] for key in ACCOUNT_KEYS - {"schemaVersion"}),
                "invalid media smoke account state")
        return value, False
    suffix = secrets.token_hex(8)
    return {
        "schemaVersion": 1,
        "email": f"local-media-smoke-{suffix}@example.invalid",
        "login": f"local_media_smoke_{suffix}",
        "password": secrets.token_urlsafe(32),
    }, True


def persist_account(path, account):
    temporary = path.with_suffix(".tmp")
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(descriptor, "w") as handle:
        handle.write(json.dumps(account, separators=(",", ":")))
    os.chmod(temporary, 0o600)
    temporary.replace(path)


def sign_in(identity, redirect, account):
    status, _, _ = identity.request("POST", "/api/accounts/login", {
        "login": account["login"], "password": account["password"]
    }, csrf=True)
    require(status == 200, "media smoke account login failed; reset local data and smoke state together")
    verifier = secrets.token_urlsafe(48)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).decode().rstrip("=")
    state = secrets.token_urlsafe(16)
    status, headers, _ = identity.request("GET", "/oauth2/authorize?" + urlencode({
        "response_type": "code", "client_id": "mnema-web", "redirect_uri": redirect,
        "scope": "openid account.read account.write learning.read learning.write", "state": state,
        "code_challenge": challenge, "code_challenge_method": "S256",
    }))
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


def avatar_round_trip(identity, access):
    """Identity writes the avatar to its bucket and serves the same bytes back."""
    image = png_fixture()
    boundary = "mnema-local-smoke-" + secrets.token_hex(12)
    body = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename=smoke.png\r\n'
            "Content-Type: image/png\r\n\r\n").encode() + image + f"\r\n--{boundary}--\r\n".encode()
    status, _, _ = identity.request("PUT", "/api/accounts/me/avatar", body, bearer=access,
                                    headers={"Content-Type": f"multipart/form-data; boundary={boundary}"})
    require(status == 204, f"avatar upload failed ({status}); check Identity object-storage settings")
    status, _, profile = identity.request("GET", "/api/accounts/me", bearer=access)
    require(status == 200 and isinstance(profile, dict) and profile.get("avatarPresent") is True,
            "avatar profile state missing")
    status, headers, received = identity.request(
        "GET", f"/api/accounts/profiles/{profile['accountId']}/avatar", raw_response=True)
    require(status == 200 and headers.get("content-type") == "image/png" and received == image,
            "avatar was not served back from object storage")
    return {"avatarBytes": len(image)}


def upload_and_process(web, access, storage_port, context, kind, mime, data, signature, timeout):
    base = "/api/media-assets"
    status, _, upload = web.request("POST", base + "/upload-intents", {
        "intentId": str(uuid.uuid4()), "origin": "UPLOAD", "kind": kind, "mime": mime, "byteLength": len(data),
    }, bearer=access)
    require(status == 201 and isinstance(upload, dict), f"{kind} upload intent failed ({status})")
    asset, generation = upload["assetId"], upload["generation"]
    require(upload.get("method") == "SINGLE", f"unexpected {kind} upload method")
    if not upload.get("url"):
        status, _, upload = web.request("POST", f"{base}/{asset}/upload/url", {"generation": generation}, bearer=access)
        require(status == 200 and upload.get("url"), f"{kind} signed upload URL unavailable")
    headers = {str(key): str(value) for key, value in (upload.get("headers") or {}).items()}
    status, _ = storage_request("PUT", upload["url"], storage_port, context, data, headers)
    require(status in (200, 201), f"{kind} object-storage PUT failed ({status}); is the local CA trusted by storage?")
    status, _, sealed = web.request("POST", f"{base}/{asset}/upload/finalize", {
        "commandId": str(uuid.uuid4()), "generation": generation,
    }, bearer=access)
    require(status == 200, f"{kind} upload finalize failed ({status})")
    deadline = time.monotonic() + timeout
    state = sealed.get("assetState")
    while state != "READY":
        require(state not in ("REJECTED", "FAILED_RETRYABLE"), f"{kind} processing ended as {state}")
        require(time.monotonic() < deadline,
                f"{kind} asset still {state} after {timeout}s; check the media-processor logs")
        time.sleep(3)
        status, _, current = web.request("GET", f"{base}/{asset}/upload", bearer=access)
        require(status == 200 and isinstance(current, dict), f"{kind} status poll failed ({status})")
        state = current.get("assetState")
    status, _, playback = web.request("GET", f"{base}/{asset}/playback", bearer=access)
    require(status == 200 and playback.get("state") == "READY" and playback.get("playback"),
            f"{kind} playback descriptor missing")
    status, variant = storage_request("GET", playback["playback"]["url"], storage_port, context)
    require(status == 200 and variant and signature(variant), f"{kind} playback variant is not the processed format")
    status, original = storage_request("GET", playback["download"]["url"], storage_port, context)
    require(status == 200 and original == data, f"{kind} original download does not match the upload")
    return {"kind": kind, "playbackMime": playback["playback"].get("mimeType"), "playbackBytes": len(variant)}


def media_smoke(web, identity, storage_port, context, state_file, timeout=POLL_TIMEOUT_SECONDS):
    account, fresh = load_or_create_account(state_file)
    if fresh:
        status, _, _ = identity.request("POST", "/api/accounts/register", {
            "email": account["email"], "loginName": account["login"], "password": account["password"],
            "profileUsername": account["login"],
        }, csrf=True)
        require(status == 201, "media smoke account registration failed")
        persist_account(state_file, account)
    access = sign_in(identity, f"https://localhost:{web.port}/auth/callback", account)
    avatar = avatar_round_trip(identity, access)
    results = [upload_and_process(web, access, storage_port, context, kind, mime, build(), signature, timeout)
               for kind, mime, build, signature in FIXTURES]
    print(json.dumps({"state": "passed", "avatarStorage": True, "avatar": avatar,
                      "mediaProcessing": True, "assets": results, "accountReused": not fresh},
                     sort_keys=True))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--web-origin", required=True)
    parser.add_argument("--identity-origin", required=True)
    parser.add_argument("--storage-port", type=int, required=True)
    parser.add_argument("--ca", type=Path, required=True)
    parser.add_argument("--state-file", type=Path, required=True)
    parser.add_argument("--timeout", type=int, default=POLL_TIMEOUT_SECONDS)
    args = parser.parse_args()
    require(1024 <= args.storage_port <= 65535, "invalid storage port")
    context = ssl.create_default_context(cafile=str(args.ca))
    web, identity = Client(args.web_origin, context), Client(args.identity_origin, context)
    args.state_file.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    media_smoke(web, identity, args.storage_port, context, args.state_file, args.timeout)


if __name__ == "__main__":
    try:
        main()
    except (AssertionError, OSError, ssl.SSLError, ValueError, KeyError, json.JSONDecodeError) as error:
        print(json.dumps({"state": "failed", "reason": str(error)}))
        raise SystemExit(1)
