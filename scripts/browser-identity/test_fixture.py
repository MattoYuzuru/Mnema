"""Fixture safety regressions; not a substitute for the real browser composition."""
import contextlib
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
import io
import json
import os
from pathlib import Path
import signal
import shutil
import subprocess
import sys
import tempfile
import threading
import time
from types import SimpleNamespace
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("browser_fixture", Path(__file__).with_name("run.py"))
HARNESS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(HARNESS)


class FixtureSafety(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="mnema-browser-unit-")
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name).resolve()
        self.dist = self.directory / "dist"
        self.dist.mkdir()
        (self.dist / "index.html").write_text("fixture index")
        (self.dist / "main.js").write_text("fixture asset")

    def fixture(self):
        fixture = HARNESS.Fixture(SimpleNamespace(dist=self.dist, clients=1, keep_on_failure=False, control_file=None))
        def cleanup():
            with contextlib.redirect_stdout(io.StringIO()):
                fixture.close(True)
        self.addCleanup(cleanup)
        return fixture

    def test_browser_values_are_passed_as_cdp_arguments(self):
        source = Path(__file__).with_name("browser.mjs").read_text()
        self.assertIn("Runtime.callFunctionOn", source)
        self.assertNotIn("${JSON.stringify", source)

    def test_static_assets_and_spa_are_confined(self):
        self.assertEqual(self.dist / "main.js", HARNESS.static_path(self.dist, "/main.js?version=1"))
        self.assertEqual(self.dist / "index.html", HARNESS.static_path(self.dist, "/auth/callback?code=private"))
        for path in ("/../outside.txt", "/%2e%2e/outside.txt", "/%00.js"):
            with self.subTest(path=path), self.assertRaises(ValueError):
                HARNESS.static_path(self.dist, path)
        (self.dist / "escape.js").symlink_to(self.directory / "outside.js")
        with self.assertRaises(ValueError):
            HARNESS.static_path(self.dist, "/escape.js")

    def test_instrumentation_and_proxy_environment_not_inherited(self):
        with patch.dict(os.environ, {"NODE_OPTIONS": "private", "SSLKEYLOGFILE": "private", "HTTPS_PROXY": "private",
                                     "JAVA_TOOL_OPTIONS": "private", "MNEMA_KEY": "private", "DOCKER_HOST": "local"}):
            environment = HARNESS.child_environment()
        self.assertFalse({"NODE_OPTIONS", "SSLKEYLOGFILE", "HTTPS_PROXY", "JAVA_TOOL_OPTIONS", "MNEMA_KEY"} & environment.keys())
        self.assertEqual("local", environment["DOCKER_HOST"])

    def test_failed_logout_wire_proof_cannot_export_passing_browser_evidence(self):
        valid = {"bearer": True, "cookie": False, "csrf": False}
        for requests in ([], [valid], [valid, {**valid, "bearer": False}],
                         [valid, {**valid, "cookie": True}], [valid, {**valid, "csrf": True}]):
            result = HARNESS.complete_browser_evidence({"state": "passed"}, requests)
            self.assertEqual("failed", result["state"])
        self.assertEqual("passed", HARNESS.complete_browser_evidence({"state": "passed"}, [valid, valid])["state"])
        self.assertEqual("failed", HARNESS.complete_browser_evidence({"state": "failed"}, [valid, valid])["state"])

    def test_partial_origin_startup_is_owned_and_cleaned(self):
        fixture = self.fixture()
        original = HARNESS.Proxy
        calls = 0
        def create(owner, identity):
            nonlocal calls
            calls += 1
            if calls == 2:
                raise OSError("synthetic bind failure")
            return original(owner, identity)
        with patch.object(HARNESS, "Proxy", create), self.assertRaises(OSError):
            fixture.prepare_origins()
        self.assertEqual(1, len(fixture.servers))
        self.assertEqual("127.0.0.1", fixture.servers[0].server_address[0])
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertTrue(fixture.close(True))
        self.assertEqual(-1, fixture.servers[0].fileno())
        self.assertFalse(fixture.tmp.exists())

    def test_process_group_and_private_profile_removed_even_if_child_is_stopped(self):
        fixture = self.fixture()
        unrelated = self.directory / "unrelated"
        unrelated.write_text("preserve")
        process = fixture.launch_group([sys.executable, "-c", "import time; time.sleep(60)"], "owned-child")
        os.killpg(process.pid, signal.SIGSTOP)
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertTrue(fixture.close(True))
        self.assertIsNotNone(process.poll())
        self.assertFalse(fixture.tmp.exists())
        self.assertEqual("preserve", unrelated.read_text())

    def test_cli_sigterm_and_deadline_cleanup_real_owned_children(self):
        # Stub only product startup: exercise real CLI signal/finally and OS process cleanup.
        source = """
import importlib.util, os, signal, sys, time
spec = importlib.util.spec_from_file_location('fixture', sys.argv[1])
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
def fixture_run(self):
    self.prepare_origins()
    self.launch_group([sys.executable, '-c', 'import time; time.sleep(60)'], 'owned')
    self.control('cancellation_ready')
    if os.environ['FIXTURE_TEST_SIGNAL'] == 'alarm':
        signal.alarm(1)
    while True:
        time.sleep(.05)
module.Fixture.run = fixture_run
sys.argv = ['run.py'] + sys.argv[2:]
module.main()
"""
        for mode in ("term", "alarm"):
            with self.subTest(mode=mode):
                control = self.directory / (mode + ".json")
                environment = dict(os.environ, FIXTURE_TEST_SIGNAL=mode)
                runner = subprocess.Popen([sys.executable, "-c", source, str(Path(__file__).with_name("run.py")),
                                           "--dist", str(self.dist), "--control-file", str(control)],
                                          env=environment, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                owned = None
                try:
                    deadline = time.monotonic() + 5
                    while not control.exists() and runner.poll() is None and time.monotonic() < deadline:
                        time.sleep(.02)
                    self.assertTrue(control.exists(), "fixture did not become cancellable")
                    owned = json.loads(control.read_text())
                    if mode == "term":
                        runner.send_signal(signal.SIGTERM)
                    output, _ = runner.communicate(timeout=8)
                    self.assertEqual(1, runner.returncode)
                    evidence = [json.loads(line) for line in output.splitlines()]
                    self.assertTrue(any(item.get("cleanup") == "complete" for item in evidence))
                    self.assertFalse(Path(owned["private_directory"]).exists())
                    for pid in owned["pids"]:
                        with self.assertRaises(ProcessLookupError):
                            os.kill(pid, 0)
                    location = Path(evidence[-1]["evidence_directory"])
                    self.assertTrue(location.name.startswith("mnema-browser-evidence-"))
                    self.assertFalse(evidence[-1]["passed"])
                    shutil.rmtree(location)  # Exact directory returned by this disposable test invocation.
                finally:
                    if runner.poll() is None:
                        runner.kill()
                        runner.communicate(timeout=3)
                    if owned:
                        for pid in owned["pids"]:
                            try:
                                os.killpg(pid, signal.SIGKILL)
                            except ProcessLookupError:
                                pass

    def proxy(self, identity=False):
        fixture = self.fixture()
        fixture.prepare_origins()
        server = fixture.identity if identity else fixture.frontend
        threading.Thread(target=server.serve_forever, kwargs={"poll_interval": 0.01}, daemon=True).start()
        self.addCleanup(server.shutdown)
        return fixture, server

    def request(self, server, method, path, body=None, headers=None):
        connection = http.client.HTTPConnection("127.0.0.1", server.server_port, timeout=2)
        try:
            connection.request(method, path, body, headers or {})
            response = connection.getresponse()
            return response.status, dict(response.getheaders()), response.read()
        finally:
            connection.close()

    def test_runtime_configuration_uses_exact_separate_https_origins(self):
        fixture, server = self.proxy()
        status, headers, body = self.request(server, "GET", "/app-config.js")
        self.assertEqual(200, status)
        config = json.loads(body.decode().removeprefix("window.MNEMA_APP_CONFIG=").removesuffix(";"))
        self.assertEqual(fixture.identity_origin, config["authServerUrl"])
        self.assertEqual(fixture.frontend_origin + "/auth/callback", config["identityRedirectUri"])
        self.assertEqual("/api", config["learningApiBaseUrl"])
        self.assertNotEqual(fixture.identity_origin, fixture.frontend_origin)
        self.assertEqual("no-store", headers["Cache-Control"])

    def test_ambiguous_oversized_and_absolute_targets_fail_closed(self):
        _, server = self.proxy(identity=True)
        cases = [({"Transfer-Encoding": "chunked"}, 400), ({"Content-Length": "invalid"}, 400),
                 ({"Content-Length": str(HARNESS.MAX_BODY + 1)}, 413)]
        for headers, expected in cases:
            self.assertEqual(expected, self.request(server, "POST", "/api/accounts/login", headers=headers)[0])
        self.assertEqual(400, self.request(server, "GET", "https://unrelated.invalid/")[0])
        connection = http.client.HTTPConnection("127.0.0.1", server.server_port, timeout=2)
        try:
            connection.putrequest("POST", "/api/accounts/login")
            connection.putheader("Content-Length", "0")
            connection.putheader("Content-Length", "0")
            connection.endheaders()
            self.assertEqual(400, connection.getresponse().status)
        finally:
            connection.close()

    def test_learning_proxy_strips_identity_cookie_and_preserves_bearer(self):
        received = []
        class Backend(BaseHTTPRequestHandler):
            def log_message(self, *_args):
                pass
            def do_GET(self):
                received.append(dict(self.headers))
                self.send_response(204)
                self.end_headers()
        backend = ThreadingHTTPServer(("127.0.0.1", 0), Backend)
        self.addCleanup(backend.server_close)
        threading.Thread(target=backend.serve_forever, kwargs={"poll_interval": 0.01}, daemon=True).start()
        self.addCleanup(backend.shutdown)
        fixture, server = self.proxy()
        fixture.learning_port = backend.server_port
        self.assertEqual(204, self.request(server, "GET", "/api/decks",
                                         headers={"Cookie": "JSESSIONID=synthetic", "Authorization": "Bearer synthetic"})[0])
        self.assertNotIn("Cookie", received[0])
        self.assertEqual("Bearer synthetic", received[0]["Authorization"])

    def test_identity_proxy_audits_only_logout_header_presence(self):
        class Backend(BaseHTTPRequestHandler):
            def log_message(self, *_args):
                pass
            def do_POST(self):
                self.send_response(204)
                self.end_headers()
        backend = ThreadingHTTPServer(("127.0.0.1", 0), Backend)
        self.addCleanup(backend.server_close)
        threading.Thread(target=backend.serve_forever, kwargs={"poll_interval": 0.01}, daemon=True).start()
        self.addCleanup(backend.shutdown)
        fixture, server = self.proxy(identity=True)
        fixture.identity_port = backend.server_port
        for headers in ({"Authorization": "Bearer synthetic"}, {"Cookie": "synthetic", "X-CSRF-TOKEN": "synthetic"}):
            self.assertEqual(204, self.request(server, "POST", "/api/accounts/logout", headers=headers)[0])
        self.assertEqual([{"bearer": True, "cookie": False, "csrf": False},
                          {"bearer": False, "cookie": True, "csrf": True}], fixture.logout_requests)
        self.assertNotIn("synthetic", json.dumps(fixture.logout_requests))

    def test_mechanics_flag_is_wired_and_requires_authoring_and_media(self):
        for arguments in (["--mechanics"], ["--authoring", "--mechanics"]):
            with self.subTest(arguments=arguments), patch.object(sys, "argv", ["run.py", "--dist", str(self.dist), *arguments]), \
                    contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as exit_code:
                HARNESS.main()
            self.assertEqual(2, exit_code.exception.code)
        runner = Path(__file__).with_name("run.py").read_text()
        driver = Path(__file__).with_name("browser.mjs").read_text()
        self.assertIn('"mechanics": self.args.mechanics', runner)
        self.assertIn("import { runMechanics } from './mechanics.mjs'", driver)
        self.assertIn("if (config.mechanics)", driver)
        self.assertTrue(Path(__file__).with_name("mechanics.mjs").is_file())

    def test_synthetic_microphone_flags_only_for_mechanics_baseline(self):
        profile, spki = self.directory / "profile", "spki"
        default = HARNESS.chrome_arguments("chrome", profile, spki, False)
        mechanics = HARNESS.chrome_arguments("chrome", profile, spki, True)
        fake = {"--use-fake-ui-for-media-stream", "--use-fake-device-for-media-stream"}
        self.assertIn("--mute-audio", default)
        self.assertIn("--mute-audio", mechanics)
        self.assertFalse(fake & set(default))
        self.assertTrue(fake <= set(mechanics))
        self.assertEqual("about:blank", mechanics[-1])

    def test_mechanics_driver_is_syntactically_valid_and_never_claims_a_real_microphone(self):
        source = Path(__file__).with_name("mechanics.mjs").read_text()
        self.assertNotIn("${JSON.stringify", source)
        self.assertIn("syntheticMicrophone: true", source)
        self.assertIn("realDeviceMicrophone: false", source)
        self.assertNotIn("realDeviceMicrophone: true", source)
        node = shutil.which("node")
        if node is not None:
            result = subprocess.run([node, "--check", str(Path(__file__).with_name("mechanics.mjs"))], capture_output=True)
            self.assertEqual(0, result.returncode)

    def test_notifications_scenario_is_wired_and_syntactically_valid(self):
        driver = Path(__file__).with_name("browser.mjs").read_text()
        source = Path(__file__).with_name("notifications.mjs").read_text()
        self.assertIn("import { runNotifications } from './notifications.mjs'", driver)
        self.assertIn("record('notifications_center_real_media_failure'", source)
        # The producer is the real worker rejecting a corrupt upload: nothing is stubbed or injected.
        self.assertNotIn("Fetch.fulfillRequest", source)
        self.assertNotIn("route.fulfill", source)
        self.assertIn('"notifications.mjs"', Path(__file__).with_name("run.py").read_text())
        node = shutil.which("node")
        if node is not None:
            result = subprocess.run([node, "--check", str(Path(__file__).with_name("notifications.mjs"))], capture_output=True)
            self.assertEqual(0, result.returncode)

    def test_deck_hub_scenario_is_wired_stubs_nothing_and_is_syntactically_valid(self):
        driver = Path(__file__).with_name("browser.mjs").read_text()
        source = Path(__file__).with_name("hub.mjs").read_text()
        self.assertIn("import { runHub } from './hub.mjs'", driver)
        self.assertIn("hub_overview_sort_star_select_delete", source)
        # Real API, real editor, real 3 s hold: no interception, mocked route or synthetic click on the hold button.
        self.assertNotIn("Fetch.fulfillRequest", source)
        self.assertNotIn("route.fulfill", source)
        self.assertIn("heldMs >= 3000", source)
        self.assertIn('"hub.mjs"', Path(__file__).with_name("run.py").read_text())
        node = shutil.which("node")
        if node is not None:
            result = subprocess.run([node, "--check", str(Path(__file__).with_name("hub.mjs"))], capture_output=True)
            self.assertEqual(0, result.returncode)

    def test_code_block_scenario_is_wired_real_input_and_syntactically_valid(self):
        driver = Path(__file__).with_name("browser.mjs").read_text()
        source = Path(__file__).with_name("code-block.mjs").read_text()
        self.assertIn("import { runCodeBlock } from './code-block.mjs'", driver)
        self.assertIn("record('code_block_real_editor_publish_browse_roundtrip'", source)
        # Real keyboard events and the real toolbar; no stubbed responses and no direct value injection into the editor.
        self.assertIn("Input.dispatchKeyEvent", source)
        self.assertNotIn("Fetch.fulfillRequest", source)
        self.assertNotIn("Object.getOwnPropertyDescriptor", source)
        self.assertIn('"code-block.mjs"', Path(__file__).with_name("run.py").read_text())
        node = shutil.which("node")
        if node is not None:
            result = subprocess.run([node, "--check", str(Path(__file__).with_name("code-block.mjs"))], capture_output=True)
            self.assertEqual(0, result.returncode)

    def test_usage_scenario_is_wired_reads_the_real_api_and_is_syntactically_valid(self):
        driver = Path(__file__).with_name("browser.mjs").read_text()
        source = Path(__file__).with_name("usage.mjs").read_text()
        self.assertIn("import { runUsage } from './usage.mjs'", driver)
        self.assertIn("record('usage_profile_ai_budget_real_api'", source)
        # The text is compared with the real API answer fetched with the page's bearer; nothing is stubbed or injected.
        self.assertIn("/api/usage", source)
        self.assertNotIn("Fetch.fulfillRequest", source)
        self.assertNotIn("route.fulfill", source)
        self.assertNotIn("createElement('a')", source)
        self.assertIn('"usage.mjs"', Path(__file__).with_name("run.py").read_text())
        node = shutil.which("node")
        if node is not None:
            result = subprocess.run([node, "--check", str(Path(__file__).with_name("usage.mjs"))], capture_output=True)
            self.assertEqual(0, result.returncode)

    def test_generation_scenario_is_wired_uses_only_the_stub_and_is_syntactically_valid(self):
        for arguments in (["--generation"],):
            with self.subTest(arguments=arguments), patch.object(sys, "argv", ["run.py", "--dist", str(self.dist), *arguments]), \
                    contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as exit_code:
                HARNESS.main()
            self.assertEqual(2, exit_code.exception.code)
        runner = Path(__file__).with_name("run.py").read_text()
        driver = Path(__file__).with_name("browser.mjs").read_text()
        source = Path(__file__).with_name("workshop.mjs").read_text()
        self.assertIn("import { runWorkshop } from './workshop.mjs'", driver)
        self.assertIn("record('workshop_composer_stub_real_api'", source)
        self.assertIn('"workshop.mjs"', runner)
        # Stub provider only, a per-run random secret, and the ordinary Learning has no step dispatcher.
        self.assertIn('"LEARNING_AI_PROVIDER": "stub"', runner)
        self.assertIn('"MNEMA_RUNTIME_ROLES"] = "api"', runner)
        self.assertNotIn("DEEPSEEK", runner)
        self.assertNotIn(".env", source)
        self.assertNotIn("Fetch.fulfillRequest", source)
        self.assertNotIn("route.fulfill", source)
        self.assertIn("Input.imeSetComposition", source)
        # Part B: approval, rejection and undo, retry, hand-off, bulk approval and the real 3 s hold, no placeholder left.
        self.assertIn("export async function runWorkshopApproval", source)
        self.assertNotIn("skipped: true", source)
        for step in ("approve_one", "reject_undo_retry", "handoff_publish", "approve_all", "stale_retry", "last_reject_undo_delete"):
            self.assertIn(step, source)
        node = shutil.which("node")
        if node is not None:
            result = subprocess.run([node, "--check", str(Path(__file__).with_name("workshop.mjs"))], capture_output=True)
            self.assertEqual(0, result.returncode)


if __name__ == "__main__":
    unittest.main()
