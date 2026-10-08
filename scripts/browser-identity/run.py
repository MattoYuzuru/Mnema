#!/usr/bin/env python3
"""Disposable real HTTPS browser Identity composition; no installed test dependencies."""
import argparse
import base64
import hashlib
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
import json
import mimetypes
import os
from pathlib import Path
import signal
import ssl
import subprocess
import tempfile
import threading
import time
import uuid
from urllib.parse import unquote, urlsplit

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("learning_fixture", ROOT / "scripts/learning-security/run.py")
BASE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BASE)
MAX_BODY = 1_048_576


def child_environment():
    """Do not inherit optional instrumentation or proxy settings into private fixture children."""
    return {key: value for key, value in os.environ.items()
            if not key.startswith(("SPRING_", "MNEMA_", "LEARNING_", "IDENTITY_"))
            and key not in {"JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "NODE_OPTIONS",
                            "NODE_EXTRA_CA_CERTS", "SSLKEYLOGFILE"}
            and key.lower() not in {"http_proxy", "https_proxy", "all_proxy"}}


def cdp_timeout_ms():
    """Default wait for a plain CDP command: 10 s, or `MNEMA_HARNESS_CDP_TIMEOUT_MS` (1-120 s) read here, because the children do not
    inherit MNEMA_ variables. Calls that wait for the page itself keep their own, longer limit in browser.mjs."""
    value = os.environ.get("MNEMA_HARNESS_CDP_TIMEOUT_MS", "")
    return int(value) if value.isdigit() and 1000 <= int(value) <= 120_000 else 10_000


def static_path(dist, request_path):
    """Resolve only checked-in build assets; SPA routes use the one index document."""
    name = unquote(urlsplit(request_path).path)
    candidate = (dist / name.lstrip("/")).resolve()
    if not candidate.is_relative_to(dist) or "\x00" in name:
        raise ValueError("invalid asset path")
    return candidate if candidate.suffix else dist / "index.html"


def chrome_arguments(chrome, profile, spki, mechanics, microphone=False):
    """Headless Chrome flags. Chrome is always muted: audio is verified through media element state, not sound.
    Only the mechanics baseline and the voice input scenario (`--generation`, #298) add Chrome's SYNTHETIC media-stream devices.

    The fake device and auto-accepted permission prompt exercise the recording UI state machine; they are
    never a real-microphone test.
    """
    arguments = [chrome, "--headless=new", "--no-first-run", "--no-default-browser-check",
                 "--disable-background-networking", "--disable-component-update", "--disable-sync",
                 "--no-proxy-server", "--mute-audio",
                 "--remote-debugging-address=127.0.0.1", "--remote-debugging-port=0",
                 "--user-data-dir=" + str(profile), "--ignore-certificate-errors-spki-list=" + spki]
    if mechanics or microphone:
        arguments += ["--use-fake-ui-for-media-stream", "--use-fake-device-for-media-stream"]
    return arguments + ["about:blank"]


def complete_browser_evidence(result, requests):
    """A passing browser driver alone cannot overrule failed real-wire assertions."""
    result = {**result, "logoutWire": {"requests": len(requests),
              "allBearer": bool(requests) and all(request["bearer"] for request in requests),
              "anyCookie": any(request["cookie"] for request in requests),
              "anyCsrf": any(request["csrf"] for request in requests)}}
    if len(requests) < 2 or any(request != {"bearer": True, "cookie": False, "csrf": False} for request in requests):
        result.update(state="failed", reason="logout wire authentication was not exclusively bearer")
    return result


class Proxy(ThreadingHTTPServer):
    daemon_threads = True
    block_on_close = False

    def __init__(self, fixture, identity):
        self.fixture = fixture
        self.identity = identity
        super().__init__(("127.0.0.1", 0), Handler)
        self.origin = "https://127.0.0.1:" + str(self.server_port)

    def handle_error(self, _request, _client_address):
        # Handler exceptions cannot leak request/cookie data through a default traceback.
        pass


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *_args):
        pass  # Callback query strings, credentials and cookies must never enter logs.

    def setup(self):
        super().setup()
        self.connection.settimeout(8)

    def reply(self, status, body=b"", content_type="text/plain"):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def do_GET(self):
        try:
            target = urlsplit(self.path)
            if target.scheme or target.netloc or not self.path.startswith("/"):
                return self.reply(400)
            if not self.server.identity and urlsplit(self.path).path.startswith("/__fixture/learning-"):
                return self.switch_learning(urlsplit(self.path).path)
            if not self.server.identity and urlsplit(self.path).path.startswith("/__fixture/promo-"):
                return self.promo_fixture(urlsplit(self.path).path)
            if self.server.identity or urlsplit(self.path).path.startswith("/api/"):
                return self.forward()
            if self.command not in ("GET", "HEAD"):
                return self.reply(405)
            fixture = self.server.fixture
            if urlsplit(self.path).path == "/app-config.js":
                config = {"authServerUrl": fixture.identity_origin, "identityRedirectUri": fixture.frontend_origin + "/auth/callback",
                          "learningApiBaseUrl": "/api",
                          "features": {"aiEnabled": False}}
                return self.reply(200, ("window.MNEMA_APP_CONFIG=" + json.dumps(config) + ";").encode(), "text/javascript")
            asset = static_path(fixture.args.dist, self.path)
            if not asset.is_file() or asset.stat().st_size > 16 * MAX_BODY:
                return self.reply(404)
            self.reply(200, asset.read_bytes(), mimetypes.guess_type(asset.name)[0] or "application/octet-stream")
        except (OSError, ValueError, http.client.HTTPException):
            self.close_connection = True
            try:
                self.reply(502)
            except OSError:
                pass

    def switch_learning(self, path):
        """`--generation` only: choose which of the two loopback Learning instances the /api proxy forwards to."""
        fixture = self.server.fixture
        if self.command != "POST" or not (fixture.generation_port or fixture.promo_port):
            return self.reply(404)
        if path == "/__fixture/learning-generation" and fixture.generation_port:
            fixture.active_learning_port = fixture.generation_port
        elif path == "/__fixture/learning-promo" and fixture.promo_port:
            fixture.active_learning_port = fixture.promo_port
        elif path == "/__fixture/learning-default":
            fixture.active_learning_port = None
        else:
            return self.reply(404)
        self.reply(204)

    def promo_fixture(self, path):
        """`--only-promo` only: the two things a browser scenario cannot do through the product, both on the disposable database.

        `promo-account-verified-admin` verifies the email of the scenario's second account and makes it an administrator (Identity has no
        endpoint that verifies an email without a mailbox, and the first administrator of an installation is bootstrapped, never self-granted);
        `promo-popup-reset` forgets every account's popup state, so one account can be walked through dismiss and decline in a single run."""
        fixture = self.server.fixture
        if self.command != "POST" or not fixture.promo_port:
            return self.reply(404)
        if path == "/__fixture/promo-account-verified-admin":
            fixture.sql("UPDATE app_identity.account SET email_verified=true, is_admin=true WHERE account_id="
                        "(SELECT account_id FROM app_identity.local_credential WHERE normalized_login_name='browser_fixture_second')")
        elif path == "/__fixture/promo-popup-reset":
            fixture.sql("DELETE FROM app_learning.promo_popup_state")
        else:
            return self.reply(404)
        self.reply(204)

    do_POST = do_GET
    do_PUT = do_GET
    do_PATCH = do_GET
    do_DELETE = do_GET
    do_OPTIONS = do_GET
    do_HEAD = do_GET

    def forward(self):
        # Chrome sends known-length JSON/form bodies. Reject ambiguous/chunked fixture input.
        lengths = self.headers.get_all("Content-Length", [])
        if self.headers.get("Transfer-Encoding") or len(lengths) > 1:
            return self.reply(400)
        if lengths and not lengths[0].isdigit():
            return self.reply(400)
        length = int(lengths[0]) if lengths else 0
        if length < 0 or length > MAX_BODY:
            return self.reply(413)
        body = self.rfile.read(length)
        if len(body) != length:
            return self.reply(400)
        fixture = self.server.fixture
        if self.server.identity and self.command == "POST" and urlsplit(self.path).path == "/api/accounts/logout":
            fixture.logout_requests.append({"bearer": self.headers.get("Authorization", "").startswith("Bearer "),
                                            "cookie": "Cookie" in self.headers,
                                            "csrf": "X-CSRF-TOKEN" in self.headers})
        port = fixture.identity_port if self.server.identity else (fixture.active_learning_port or fixture.learning_port)
        headers = {name: value for name, value in self.headers.items()
                   if name.lower() not in {"host", "connection", "content-length", "transfer-encoding", "accept-encoding"}}
        headers["Host"] = urlsplit(self.server.origin).netloc
        # Identity cookies are meaningful only at the Identity proxy; do not pass them to Learning.
        if not self.server.identity:
            headers = {name: value for name, value in headers.items() if name.lower() != "cookie"}
        connection = http.client.HTTPConnection("127.0.0.1", port, timeout=8)
        started = time.monotonic()
        try:
            connection.request(self.command, self.path, body if length else None, headers)
            response = connection.getresponse()
            content = response.read(MAX_BODY + 1)
            fixture.note_slow(self.command, self.path, response.status, time.monotonic() - started)
            if len(content) > MAX_BODY:
                return self.reply(502)
            self.send_response(response.status)
            for name, value in response.getheaders():
                if name.lower() not in {"connection", "content-length", "transfer-encoding"}:
                    self.send_header(name, value)
            self.send_header("Content-Length", str(len(content)))
            self.end_headers()
            if self.command != "HEAD":
                self.wfile.write(content)
        finally:
            connection.close()


class Fixture(BASE.Fixture):
    def __init__(self, args):
        super().__init__(args)
        self.servers = []
        self.running_servers = []
        self.groups = []
        self.logout_requests = []
        self.browser_cleanup_result = None
        self.identity_port = self.learning_port = 0
        self.generation_port = 0
        self.promo_port = 0
        self.active_learning_port = None
        self.media_container = None
        self.media_origin = None

    def note_slow(self, method, path, status, seconds):
        """A proxied request that took two seconds or more: method, path without its query, status and time (kept with the private logs)."""
        if seconds >= 2:
            with (self.tmp / "slow-requests.log").open("a") as log:
                log.write(f"{time.strftime('%H:%M:%S')} {method} {urlsplit(path).path} {status} {seconds:.1f}s\n")

    def start(self):
        if self.args.media:
            self.start_media_store()
        super().start()
        if self.stub_instance():
            # A second Learning on the same database with the Stub provider. Every ordinary scenario keeps running against the
            # first one (generation and AI assessment off, as shipped); the Workshop and assessment scenarios flip the proxy
            # with POST /__fixture/learning-generation.
            self.generation_port = BASE.free_port()
            self.boot("learning", self.generation_port, "learning_fixture", generation=True)

        if getattr(self.args, "only_promo", False):
            # A second Learning with the promo popup campaign on: the popup would cover the base flow's own pages, so the base flow runs on the
            # first (shipped) instance and the promo scenario switches the proxy with POST /__fixture/learning-promo. The address limit of
            # redemption attempts is raised because every request of this fixture comes from one loopback address.
            self.promo_port = BASE.free_port()
            self.boot("learning", self.promo_port, "learning_fixture", promo=True)

    def stub_instance(self):
        """`--generation` and `--assessment` share one second Learning: Stub provider, both AI features on."""
        return bool(getattr(self.args, "generation", False) or getattr(self.args, "assessment", False))

    def start_media_store(self):
        image = ("ghcr.io/l33tlamer/minio-backup@sha256:"
                 "a1ea29fa28355559ef137d71fc570e508a214ec84ff8083e39bc5428980b015e")
        self.media_container = "mnema-browser-media-" + uuid.uuid4().hex[:10]
        BASE.command(["docker", "image", "inspect", image])
        BASE.command(["docker", "run", "--detach", "--name", self.media_container,
                      "--publish", "127.0.0.1::9000", "--env", "MINIO_ROOT_USER=mnema-browser-access",
                      "--env", "MINIO_ROOT_PASSWORD=mnema-browser-secret-key",
                      "--env", "MINIO_API_CORS_ALLOW_ORIGIN=" + self.frontend_origin,
                      image, "server", "/data"])
        mapped = BASE.command(["docker", "port", self.media_container, "9000/tcp"]).stdout.decode().strip()
        port = int(mapped.rsplit(":", 1)[1])
        self.media_origin = f"http://127.0.0.1:{port}"
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            try:
                connection = http.client.HTTPConnection("127.0.0.1", port, timeout=2)
                connection.request("GET", "/minio/health/ready")
                healthy = connection.getresponse().status == 200
                connection.close()
                if healthy:
                    break
            except (OSError, http.client.HTTPException):
                pass
            self.cancellation.wait(0.2)
        else:
            raise AssertionError("local media store readiness timeout")
        aws_env = {**os.environ, "AWS_ACCESS_KEY_ID": "mnema-browser-access",
                   "AWS_SECRET_ACCESS_KEY": "mnema-browser-secret-key",
                   "AWS_DEFAULT_REGION": "us-east-1", "AWS_EC2_METADATA_DISABLED": "true"}
        def aws(*arguments):
            result = subprocess.run(["aws", "--endpoint-url", self.media_origin,
                                     "s3api", *arguments], env=aws_env, capture_output=True, timeout=20)
            if result.returncode:
                (self.tmp / "media-store-setup.log").write_bytes(result.stderr)
            BASE.require(result.returncode == 0, "local media store " + arguments[0] + " setup failed")
        aws("create-bucket", "--bucket", "mnema-browser-media")

    def prepare_origins(self):
        # Called after the caller owns this fixture, so partial listener startup is cleaned up.
        self.frontend = Proxy(self, False)
        self.servers.append(self.frontend)
        self.identity = Proxy(self, True)
        self.servers.append(self.identity)
        self.frontend_origin, self.identity_origin = self.frontend.origin, self.identity.origin
        BASE.ISSUER = self.identity_origin
        BASE.REDIRECT = self.frontend_origin + "/auth/callback"

    def boot(self, module, port, username, generation=False, promo=False):
        jar = ROOT / f"backend/services/{module}/build/libs/{module}-0.0.1-SNAPSHOT.jar"
        BASE.require(jar.is_file(), "missing built " + module + " jar")
        environment = child_environment()
        environment.update({"PORT": str(port), "SPRING_DATASOURCE_URL": self.jdbc,
                            "SPRING_DATASOURCE_USERNAME": username, "SPRING_DATASOURCE_PASSWORD": self.db_password,
                            "MNEMA_IDENTITY_ISSUER": self.identity_origin,
                            "MNEMA_IDENTITY_FRONTEND_ORIGIN": self.frontend_origin,
                            "MNEMA_IDENTITY_REDIRECT_URI": self.frontend_origin + "/auth/callback",
                            "MNEMA_IDENTITY_SIGNING_JWK_SET_FILE": str(self.tmp / "signing.json"),
                            "MNEMA_IDENTITY_SIGNING_ACTIVE_KID": "blackbox", "APP_ENV": "local-browser-fixture"})
        arguments = ["java", "-Xms64m", "-Xmx384m", "-jar", str(jar), "--server.address=127.0.0.1"]
        if module == "identity-account" and self.args.media:
            environment.update({"MNEMA_AVATAR_ENDPOINT": self.media_origin,
                                "MNEMA_AVATAR_REGION": "us-east-1",
                                "MNEMA_AVATAR_BUCKET": "mnema-browser-media",
                                "MNEMA_AVATAR_ACCESS_KEY": "mnema-browser-access",
                                "MNEMA_AVATAR_SECRET_KEY": "mnema-browser-secret-key"})
            arguments += ["--identity.avatar.allow-loopback-http=true"]
        if module == "learning":
            if not hasattr(self, "events_owner_id"):
                # Disposable, real Identity account for the editorial browser flow. Existing learner accounts remain outsiders.
                _, self.events_owner_id = self.account("events_fixture")
            environment["MNEMA_EVENTS_OWNER_ACCOUNT_ID"] = self.events_owner_id
            if self.args.media:
                environment.update({"LEARNING_MEDIA_UPLOAD_ENDPOINT": self.media_origin,
                                    "LEARNING_MEDIA_UPLOAD_ALLOW_LOOPBACK_HTTP": "true",
                                    "LEARNING_MEDIA_UPLOAD_REGION": "us-east-1",
                                    "LEARNING_MEDIA_UPLOAD_BUCKET": "mnema-browser-media",
                                    "LEARNING_MEDIA_UPLOAD_ACCESS_KEY": "mnema-browser-access",
                                    "LEARNING_MEDIA_UPLOAD_SECRET_KEY": "mnema-browser-secret-key",
                                    "LEARNING_MEDIA_PROCESSING_ENABLED": "true",
                                    "LEARNING_MEDIA_PROCESSING_INITIAL_DELAY": "PT1S",
                                    "LEARNING_MEDIA_PROCESSING_SCAN_INTERVAL": "PT2S"})
            if generation:
                # Stub provider only: deterministic text, no network, no real key. The user-key secret is a per-run
                # random value that only has to exist (HMAC of the opaque account id sent to a provider); MAX gives the
                # fixture account room for several materials (the Free plan opens 13 credits a week) and for the smart plans of the planner
                # scenario (#295: Pro has one a week, Max eight a month).
                environment.update({"LEARNING_FEATURES_AI_GENERATION_ENABLED": "true", "LEARNING_FEATURES_AI_ASSESSMENT_ENABLED": "true",
                                    "LEARNING_AI_PROVIDER": "stub",
                                    "MNEMA_AI_USER_KEY_SECRET": uuid.uuid4().hex + uuid.uuid4().hex,
                                    "LEARNING_USAGE_ENTITLEMENTS_DEFAULT_PLAN": "MAX",
                                    # #296: image search runs on the Stub image source (no network); with `--media` the found
                                    # files go through the real media pipeline as untrusted uploads.
                                    "LEARNING_FEATURES_IMAGE_SEARCH_ENABLED": "true",
                                    # #297: speech synthesis runs on the Stub speech port (a deterministic WAV tone, no network).
                                    "LEARNING_FEATURES_TEXT_TO_SPEECH_ENABLED": "true",
                                    # #298: dictation and spoken answers run on the Stub transcription (a fixed text, no network); the harness may
                                    # script it with the `X-Stub-Transcript` header that only the Stub reads.
                                    "LEARNING_FEATURES_SPEECH_TO_TEXT_ENABLED": "true",
                                    # #299: web research and «Источники» run on the Stub web search (results on example.org, no network).
                                    "LEARNING_FEATURES_WEB_SEARCH_ENABLED": "true"})
            elif promo:
                # ASCII copy only: a process environment is decoded with the platform charset, and the fixture must not depend on it.
                environment.update({"MNEMA_RUNTIME_ROLES": "api", "MNEMA_PROMO_POPUP_ENABLED": "true",
                                    "MNEMA_PROMO_POPUP_ID": "browser-fixture-autumn",
                                    "MNEMA_PROMO_POPUP_TITLE": "Autumn offer",
                                    "MNEMA_PROMO_POPUP_BODY": "Plus costs less until the end of October. Nothing is switched on for you.",
                                    "MNEMA_PROMO_POPUP_CTA": "See the plans",
                                    "MNEMA_PROMO_IP_ATTEMPTS_PER_HOUR": "100",
                                    "MNEMA_PROMO_HASH_SECRET": uuid.uuid4().hex, "MNEMA_EXPERIMENT_SECRET": uuid.uuid4().hex})
            elif self.stub_instance():
                # The ordinary instance of a `--generation` or `--assessment` run has the AI features off; as an `api` process it has no step
                # dispatcher, so it can never claim a step of the second (Stub) instance that shares the database.
                environment["MNEMA_RUNTIME_ROLES"] = "api"
            if generation and self.args.assessment:
                # Stub-only fault fixture: let one capped call reach the unchanged 20 s delivery deadline. The real route's 8 s
                # attempt cap/defaults stay unchanged; this override cannot reach the ordinary or promo instance or any live provider.
                arguments += ["--learning.ai.routes.assess-attempt-cap=PT25S"]
            arguments += [f"--learning.identity.transport-base=http://127.0.0.1:{self.identity_port}",
                          "--learning.identity.allow-loopback-http=true"]
        log = (self.tmp / (module + ("-generation" if generation else "") + ("-promo" if promo else "") + ".log")).open("wb")
        self.logs.append(log)
        process = subprocess.Popen(arguments, env=environment, stdout=log, stderr=subprocess.STDOUT)
        self.processes.append(process)
        self.control("app_starting")
        deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            BASE.require(process.poll() is None, module + " failed before readiness")
            try:
                if BASE.Client(port).request("GET", "/api/actuator/health/readiness")[0] == 200:
                    return process, hashlib.sha256(jar.read_bytes()).hexdigest()
            except (OSError, http.client.HTTPException):
                pass
            self.cancellation.wait(0.2)
        raise AssertionError(module + " readiness timeout")

    def run(self):
        self.prepare_origins()
        self.start()
        media_clips = None
        if self.args.media:
            audio = self.tmp / "browser-audio.mp3"
            video = self.tmp / "browser-video.mp4"
            for command in (["ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i",
                             "sine=frequency=440:duration=2", "-c:a", "libmp3lame", str(audio)],
                            ["ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i",
                             "testsrc2=s=160x90:r=12:d=2", "-f", "lavfi", "-i",
                             "sine=frequency=440:duration=2", "-c:v", "libx264",
                             "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", str(video)]):
                result = subprocess.run(command, capture_output=True, timeout=20)
                BASE.require(result.returncode == 0, "local media fixture generation failed")
            media_clips = {"audio": base64.b64encode(audio.read_bytes()).decode(),
                           "video": base64.b64encode(video.read_bytes()).decode()}
        cert, key = self.tmp / "tls.crt", self.tmp / "tls.key"
        BASE.command(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1",
                      "-subj", "/CN=127.0.0.1", "-addext", "subjectAltName=IP:127.0.0.1",
                      "-keyout", str(key), "-out", str(cert)])
        public = BASE.command(["openssl", "x509", "-in", str(cert), "-pubkey", "-noout"]).stdout
        der = BASE.command(["openssl", "pkey", "-pubin", "-outform", "DER"], input=public).stdout
        spki = base64.b64encode(hashlib.sha256(der).digest()).decode()
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        context.load_cert_chain(cert, key)
        for server in self.servers:
            # Handshake happens in the bounded-timeout handler, not on the single accept thread.
            server.socket = context.wrap_socket(server.socket, server_side=True, do_handshake_on_connect=False)
            threading.Thread(target=server.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True).start()
            self.running_servers.append(server)
        profile = self.tmp / "chrome-profile"
        self.launch_group(chrome_arguments(self.args.chrome, profile, spki, self.args.mechanics, self.args.generation), "chrome")
        active = profile / "DevToolsActivePort"
        deadline = time.monotonic() + 15
        while not active.is_file():
            BASE.require(time.monotonic() < deadline, "Chrome startup timeout")
            self.cancellation.wait(0.1)
        port = int(active.read_text().splitlines()[0])
        config = {"debugPort": port, "frontend": self.frontend_origin, "identity": self.identity_origin,
                  "output": str(self.args.output), "login": "browser_fixture", "email": "browser_fixture@example.invalid",
                  "password": BASE.PASSWORD, "readySelector": self.args.ready_selector,
                  "eventsLogin": "events_fixture",
                  "logoutSelector": self.args.logout_selector, "errorSelector": self.args.error_selector,
                  "authoring": self.args.authoring, "media": self.args.media, "mechanics": self.args.mechanics,
                  "generation": self.args.generation, "assessment": self.args.assessment,
                  "onlyEdits": self.args.only_edits, "onlyImages": self.args.only_images, "onlySpeech": self.args.only_speech, "onlyVoice": self.args.only_voice, "onlyResearch": self.args.only_research, "onlyAsk": self.args.only_ask, "onlyPlan": self.args.only_plan, "onlyPlans": self.args.only_plans, "onlyPromo": self.args.only_promo, "cdpTimeoutMs": cdp_timeout_ms(),
                  "diagnosticsDir": str(self.tmp) if self.args.mechanics and self.args.keep_on_failure else None,
                  "mediaOrigin": self.media_origin, "mediaClips": media_clips}
        private_config = self.tmp / "browser.json"
        private_config.write_text(json.dumps(config))
        digest = hashlib.sha256()
        for asset in sorted(self.args.dist.rglob("*")):
            if asset.is_file():
                digest.update(str(asset.relative_to(self.args.dist)).encode())
                digest.update(b"\x00")
                digest.update(hashlib.sha256(asset.read_bytes()).digest())
        evidence = {"fixture": self.results, "frontend_tree_sha256": digest.hexdigest(),
                    "scripts": {name: hashlib.sha256(Path(__file__).with_name(name).read_bytes()).hexdigest()
                                for name in ("run.py", "browser.mjs", "mechanics.mjs", "notifications.mjs", "hub.mjs",
                                             "code-block.mjs", "usage.mjs", "workshop.mjs", "exercises.mjs", "selection-edits.mjs",
                                             "image-search.mjs", "speech.mjs", "voice.mjs", "research.mjs", "ask-mnema.mjs", "assessment.mjs", "planner.mjs", "plans.mjs", "promo.mjs", "events.mjs")}}
        (self.args.output / "fixture.json").write_text(json.dumps(evidence, indent=2))
        runner = self.launch_group([self.args.node, str(Path(__file__).with_name("browser.mjs")), str(private_config)], "browser")
        self.control("browser_running")
        while runner.poll() is None:
            self.cancellation.wait(0.1)
        BASE.require(runner.returncode == 0, "browser assertions failed (private diagnostics removed)")
        # The child writes only the explicitly sanitized result, never URLs/headers/token bodies.
        result = json.loads((self.args.output / "browser.json").read_text())
        BASE.require(result.get("state") == "passed", "browser did not produce passing evidence")
        result = complete_browser_evidence(result, self.logout_requests)
        (self.args.output / "browser.json").write_text(json.dumps(result, indent=2))
        BASE.require(result["state"] == "passed", "logout wire assertions failed")
        self.record("real_https_browser_identity", **result)

    def launch_group(self, arguments, label):
        log = (self.tmp / (label + ".log")).open("wb")
        self.logs.append(log)
        process = subprocess.Popen(arguments, env=child_environment(), stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        self.processes.append(process)
        self.groups.append(process.pid)
        return process

    def close(self, failed):
        # Kill only process groups created by this fixture, including Chrome renderer children.
        if self.browser_cleanup_result is not None:
            return self.browser_cleanup_result
        with BASE.shield_cleanup_signals():
            issues = []
            group_errors = {}
            for group in self.groups:
                try:
                    os.killpg(group, signal.SIGCONT)
                    os.killpg(group, signal.SIGTERM)
                except ProcessLookupError:
                    pass
                except OSError as error:
                    group_errors[group] = error.errno
            # Force remaining descendants before the parent fixture removes the profile/keys.
            deadline = time.monotonic() + 0.3
            while any(process.poll() is None for process in self.processes) and time.monotonic() < deadline:
                time.sleep(0.02)
            for group in self.groups:
                try:
                    os.killpg(group, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                except OSError as error:
                    group_errors[group] = error.errno
            for server in self.servers:
                try:
                    if server in self.running_servers:
                        server.shutdown()
                    server.server_close()
                except OSError:
                    issues.append("listener_close")
            result = super().close(failed)
            if self.media_container is not None:
                removed = subprocess.run(["docker", "rm", "--force", "--volumes", self.media_container],
                                         capture_output=True, timeout=5)
                if removed.returncode and b"No such container" not in removed.stderr:
                    issues.append("media_container_remove")
            # macOS can transiently refuse a signal while a Chrome helper exits. Final ownership
            # verification distinguishes that race from a genuinely surviving process group.
            surviving = list(self.groups)
            deadline = time.monotonic() + 1
            while surviving and time.monotonic() < deadline:
                for group in list(surviving):
                    try:
                        os.killpg(group, 0)
                    except ProcessLookupError:
                        surviving.remove(group)
                    except OSError as error:
                        group_errors[group] = error.errno
                if surviving:
                    time.sleep(0.02)
            issues.extend("group_survived_errno_" + str(group_errors.get(group, 0)) for group in surviving)
            if issues:
                print(json.dumps({"browser_cleanup": "incomplete", "failed_steps": issues,
                                  "owned_process_groups": self.groups}), flush=True)
            self.browser_cleanup_result = result and not issues
            return self.browser_cleanup_result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dist", type=Path, required=True)
    parser.add_argument("--node", default="node")
    parser.add_argument("--chrome", default="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome")
    parser.add_argument("--ready-selector", default='[data-testid="identity-profile"]')
    parser.add_argument("--logout-selector", default='[data-testid="logout"]')
    parser.add_argument("--error-selector", default='[role="alert"]')
    parser.add_argument("--authoring", action="store_true",
                        help="also verify the real Deck, Capture, draft, publication and Browse loop")
    parser.add_argument("--media", action="store_true",
                        help="local MinIO and worker proof for browser media upload (implies --authoring)")
    parser.add_argument("--mechanics", action="store_true",
                        help="after authoring, create/save/reopen/study all five exercise mechanics through the real UI "
                             "(requires --authoring --media; uses Chrome's synthetic microphone, not a real device)")
    parser.add_argument("--generation", action="store_true",
                        help="boot Learning with the Stub generation provider (never a real one) and drive the composer and "
                             "the Workshop through the real UI after the authoring scenarios (requires --authoring)")
    parser.add_argument("--assessment", action="store_true",
                        help="boot Learning with the Stub provider and AI assessment on (never a real provider) and drive the rubric editor "
                             "and the learner's check of an explanation (waiting, «Оценить себя», result, self-check, dispute) through "
                             "the real UI (requires --authoring)")
    parser.add_argument("--only-edits", action="store_true",
                        help="development aid: after the base flow run only the Workshop selection-edit scenario (requires --generation); "
                             "never a substitute for the full run")
    parser.add_argument("--only-images", action="store_true",
                        help="development aid: after the base flow run only the Workshop image search scenario (requires --generation "
                             "and --media); never a substitute for the full run")
    parser.add_argument("--only-speech", action="store_true",
                        help="development aid: after the base flow run only the Workshop speech synthesis scenario (requires --generation "
                             "and --media); never a substitute for the full run")
    parser.add_argument("--only-voice", action="store_true",
                        help="development aid: after the base flow run only the voice input scenario, dictation and spoken answers (requires "
                             "--generation); never a substitute for the full run")
    parser.add_argument("--only-research", action="store_true",
                        help="development aid: after the base flow run only the web research and «Источники» scenario (requires --generation); "
                             "never a substitute for the full run")
    parser.add_argument("--only-ask", action="store_true",
                        help="development aid: after the base flow run only the «Попросить Мнему…» scenario (requires --generation); "
                             "never a substitute for the full run")
    parser.add_argument("--only-plan", action="store_true",
                        help="development aid: after the base flow run only the planner «Сначала показать план» scenario (requires --generation); "
                             "never a substitute for the full run")
    parser.add_argument("--only-plans", action="store_true",
                        help="development aid: after the base flow run only the paywall, goal question and public /ai scenario "
                             "(requires --authoring); skips the code block, usage, Workshop and assessment scenarios; never a "
                             "substitute for the full run")
    parser.add_argument("--only-promo", action="store_true",
                        help="development aid: after the base flow run only the promo codes, A/B assignment and promo popup scenario "
                             "(requires --authoring); boots a second Learning with the popup campaign on, verifies and promotes the scenario's "
                             "second account on the disposable database, skips every other scenario; never a substitute for the full run")
    parser.add_argument("--timeout", type=int, default=None, metavar="SECONDS",
                        help="global deadline, 30-900 seconds (default 180, or 600 with --mechanics)")
    parser.add_argument("--keep-on-failure", action="store_true",
                        help="keep mode-0700 private logs/keys for local debugging")
    parser.add_argument("--control-file", type=Path, help=argparse.SUPPRESS)
    args = parser.parse_args()
    if args.media and not args.authoring:
        parser.error("--media requires --authoring")
    if args.mechanics and not (args.authoring and args.media):
        parser.error("--mechanics requires --authoring --media")
    if args.generation and not args.authoring:
        parser.error("--generation requires --authoring")
    if args.assessment and not args.authoring:
        parser.error("--assessment requires --authoring")
    if args.only_edits and not args.generation:
        parser.error("--only-edits requires --generation")
    if args.only_ask and not args.generation:
        parser.error("--only-ask requires --generation")
    if args.only_plan and not args.generation:
        parser.error("--only-plan requires --generation")
    if args.only_plans and not args.authoring:
        parser.error("--only-plans requires --authoring")
    if args.only_promo and not args.authoring:
        parser.error("--only-promo requires --authoring")
    if args.only_images and not (args.generation and args.media):
        parser.error("--only-images requires --generation and --media")
    if args.only_voice and not args.generation:
        parser.error("--only-voice requires --generation")
    if args.only_research and not args.generation:
        parser.error("--only-research requires --generation")
    if args.only_speech and not (args.generation and args.media):
        parser.error("--only-speech requires --generation and --media")
    if sum(1 for aid in (args.only_ask, args.only_edits, args.only_plan, args.only_plans, args.only_promo, args.only_images, args.only_speech, args.only_voice, args.only_research) if aid) > 1:
        parser.error("--only-ask, --only-edits, --only-plan, --only-plans, --only-promo, --only-images, --only-speech, --only-voice and --only-research are separate development aids: choose one")
    if args.timeout is None:
        args.timeout = 600 if args.mechanics else 180
        if args.generation or args.assessment:
            args.timeout = max(args.timeout, 840)
        if args.only_promo:
            args.timeout = max(args.timeout, 420)
    if not 30 <= args.timeout <= 900:
        parser.error("--timeout must be between 30 and 900 seconds")
    args.dist = args.dist.resolve()
    BASE.require((args.dist / "index.html").is_file(), "missing built frontend index")
    args.output = Path(tempfile.mkdtemp(prefix="mnema-browser-evidence-"))
    args.clients = 1
    os.umask(0o077)
    fixture = None
    failed = True
    def interrupt(_number, _frame):
        raise KeyboardInterrupt()
    for number in (signal.SIGINT, signal.SIGTERM, signal.SIGALRM):
        signal.signal(number, interrupt)
    signal.alarm(args.timeout)
    try:
        fixture = Fixture(args)
        fixture.run()
        failed = False
    except KeyboardInterrupt:
        print(json.dumps({"suite": "interrupted_or_timed_out"}), flush=True)
    except Exception as error:
        diagnostic = {"suite": "failed", "kind": type(error).__name__}
        if isinstance(error, AssertionError):
            diagnostic["reason"] = str(error)
        elif isinstance(error, subprocess.CalledProcessError):
            command = error.cmd if isinstance(error.cmd, (list, tuple)) else []
            diagnostic.update(returncode=error.returncode, command=[str(value) for value in command[:2]])
        print(json.dumps(diagnostic), flush=True)
    finally:
        signal.alarm(0)
        if fixture is not None and not fixture.close(failed):
            failed = True
        print(json.dumps({"evidence_directory": str(args.output), "passed": not failed}), flush=True)
    raise SystemExit(1 if failed else 0)


if __name__ == "__main__":
    main()
