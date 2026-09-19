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


def static_path(dist, request_path):
    """Resolve only checked-in build assets; SPA routes use the one index document."""
    name = unquote(urlsplit(request_path).path)
    candidate = (dist / name.lstrip("/")).resolve()
    if not candidate.is_relative_to(dist) or "\x00" in name:
        raise ValueError("invalid asset path")
    return candidate if candidate.suffix else dist / "index.html"


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
            if self.server.identity or urlsplit(self.path).path.startswith("/api/"):
                return self.forward()
            if self.command not in ("GET", "HEAD"):
                return self.reply(405)
            fixture = self.server.fixture
            if urlsplit(self.path).path == "/app-config.js":
                config = {"authServerUrl": fixture.identity_origin, "identityRedirectUri": fixture.frontend_origin + "/auth/callback",
                          "learningApiBaseUrl": "/api",
                          "features": {"aiEnabled": False, "federatedAuthEnabled": False}}
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
        port = fixture.identity_port if self.server.identity else fixture.learning_port
        headers = {name: value for name, value in self.headers.items()
                   if name.lower() not in {"host", "connection", "content-length", "transfer-encoding", "accept-encoding"}}
        headers["Host"] = urlsplit(self.server.origin).netloc
        # Identity cookies are meaningful only at the Identity proxy; do not pass them to Learning.
        if not self.server.identity:
            headers = {name: value for name, value in headers.items() if name.lower() != "cookie"}
        connection = http.client.HTTPConnection("127.0.0.1", port, timeout=8)
        try:
            connection.request(self.command, self.path, body if length else None, headers)
            response = connection.getresponse()
            content = response.read(MAX_BODY + 1)
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

    def prepare_origins(self):
        # Called after the caller owns this fixture, so partial listener startup is cleaned up.
        self.frontend = Proxy(self, False)
        self.servers.append(self.frontend)
        self.identity = Proxy(self, True)
        self.servers.append(self.identity)
        self.frontend_origin, self.identity_origin = self.frontend.origin, self.identity.origin
        BASE.ISSUER = self.identity_origin
        BASE.REDIRECT = self.frontend_origin + "/auth/callback"

    def boot(self, module, port, username):
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
        if module == "learning":
            arguments += [f"--learning.identity.transport-base=http://127.0.0.1:{self.identity_port}",
                          "--learning.identity.allow-loopback-http=true"]
        log = (self.tmp / (module + ".log")).open("wb")
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
        self.launch_group([self.args.chrome, "--headless=new", "--no-first-run", "--no-default-browser-check",
                           "--disable-background-networking", "--disable-component-update", "--disable-sync",
                           "--no-proxy-server",
                           "--remote-debugging-address=127.0.0.1", "--remote-debugging-port=0",
                           "--user-data-dir=" + str(profile), "--ignore-certificate-errors-spki-list=" + spki,
                           "about:blank"], "chrome")
        active = profile / "DevToolsActivePort"
        deadline = time.monotonic() + 15
        while not active.is_file():
            BASE.require(time.monotonic() < deadline, "Chrome startup timeout")
            self.cancellation.wait(0.1)
        port = int(active.read_text().splitlines()[0])
        config = {"debugPort": port, "frontend": self.frontend_origin, "identity": self.identity_origin,
                  "output": str(self.args.output), "login": "browser_fixture", "email": "browser_fixture@example.invalid",
                  "password": BASE.PASSWORD, "readySelector": self.args.ready_selector,
                  "logoutSelector": self.args.logout_selector, "errorSelector": self.args.error_selector}
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
                                for name in ("run.py", "browser.mjs")}}
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
    parser.add_argument("--timeout", type=int, choices=range(30, 301), default=180)
    parser.add_argument("--keep-on-failure", action="store_true",
                        help="keep mode-0700 private logs/keys for local debugging")
    parser.add_argument("--control-file", type=Path, help=argparse.SUPPRESS)
    args = parser.parse_args()
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
