"""Verifier cleanup units plus explicitly enabled real outer-cancellation checks."""
import hashlib
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
from types import SimpleNamespace
import unittest
from unittest.mock import patch

SCRIPTS = Path(__file__).parents[1]
sys.path.insert(0, str(SCRIPTS))
import verify_cancellation as verifier


class VerifierCleanupTests(unittest.TestCase):
    def setUp(self):
        verifier.CANCELLATION.clear()

    def test_startup_ownership_and_repeated_signals_allow_child_graceful_cleanup(self):
        with tempfile.TemporaryDirectory() as root:
            parent = Path.home() if sys.platform == "darwin" else Path(os.environ.get("RUNNER_TEMP", tempfile.gettempdir()))
            private = Path(tempfile.mkdtemp(prefix="mnema-learning-security-", dir=parent))
            control = Path(root) / "control.json"
            control.write_text(json.dumps({"phase": "allocated", "container": "mnema-r74-auth-0123456789",
                                           "private_directory": str(private), "pids": []}))
            actions = []
            class Process:
                pid, status = 123456789, None
                def poll(self):
                    return self.status
                def send_signal(self, number):
                    actions.append(("signal", number))
                def wait(self, timeout):
                    actions.append(("wait", timeout))
                    os.kill(os.getpid(), signal.SIGINT)
                    os.kill(os.getpid(), signal.SIGTERM)
                    self.status = 1
            with patch.object(verifier.subprocess, "run", return_value=SimpleNamespace(returncode=1, stderr=b"No such container")), \
                    patch.object(verifier.os, "killpg") as kill:
                self.assertEqual([], verifier.cleanup_child(Process(), control, {}))
            self.assertEqual([("signal", signal.SIGTERM), ("wait", 4)], actions)
            self.assertTrue(verifier.CANCELLATION.is_set())
            kill.assert_not_called()
            self.assertFalse(private.exists())

    def test_stuck_child_gets_grace_then_scoped_force_and_failure_isolation(self):
        with tempfile.TemporaryDirectory() as root:
            parent = Path.home() if sys.platform == "darwin" else Path(os.environ.get("RUNNER_TEMP", tempfile.gettempdir()))
            private = Path(tempfile.mkdtemp(prefix="mnema-learning-security-", dir=parent))
            control = Path(root) / "control.json"
            resources = {"phase": "paced_wait", "container": "mnema-r74-auth-0123456789",
                         "private_directory": str(private), "pids": []}
            actions = []
            class Process:
                pid, status = 123456789, None
                def poll(self):
                    return self.status
                def send_signal(self, number):
                    actions.append("signal")
                def wait(self, timeout):
                    actions.append(("wait", timeout))
                    if timeout == 4:
                        raise subprocess.TimeoutExpired("child", 4)
                    self.status = -9
            with patch.object(verifier.os, "killpg", side_effect=lambda *_: actions.append("kill_group")), \
                    patch.object(verifier.subprocess, "run", side_effect=subprocess.TimeoutExpired("docker", 2)):
                self.assertEqual(["container_remove"], verifier.cleanup_child(Process(), control, resources))
            self.assertEqual(["signal", ("wait", 4), "kill_group", ("wait", 0.5)], actions)
            self.assertFalse(private.exists())

    def test_both_outer_signals_raise_once_then_ignore_repeated_interrupt(self):
        for number in (signal.SIGINT, signal.SIGTERM):
            verifier.CANCELLATION.clear()
            with self.assertRaises(KeyboardInterrupt):
                verifier.interrupted(number, None)
            verifier.interrupted(number, None)
            self.assertTrue(verifier.CANCELLATION.is_set())


@unittest.skipUnless(os.environ.get("MNEMA_RUN_CANCELLATION_INTEGRATION") == "1", "explicit real-service cancellation envelope required")
class ActualOuterVerifierTests(unittest.TestCase):
    def setUp(self):
        self.cancelled = False
        def interrupted(_number, _frame):
            if not self.cancelled:
                self.cancelled = True
                raise KeyboardInterrupt()
        self.previous_handlers = {number: signal.signal(number, interrupted)
                                  for number in (signal.SIGINT, signal.SIGTERM)}

    def tearDown(self):
        for number, handler in self.previous_handlers.items():
            signal.signal(number, handler)

    def check_stage(self, stage, number):
        with tempfile.TemporaryDirectory(prefix="mnema-outer-proof-") as root:
            environment = dict(os.environ, TMPDIR=root)
            process = subprocess.Popen([sys.executable, str(SCRIPTS / "verify_cancellation.py")],
                                       env=environment, stdout=subprocess.PIPE, stderr=subprocess.PIPE, start_new_session=True)
            resources = {}
            try:
                deadline = time.monotonic() + 90
                while time.monotonic() < deadline:
                    controls = list(Path(root).glob("mnema-cancellation-proof-*/control.json"))
                    if controls:
                        resources = json.loads(controls[0].read_text())
                    phase = resources.get("phase")
                    if (stage == "startup" and phase == "app_starting") or phase == stage:
                        break
                    self.assertIsNone(process.poll(), "outer verifier exited before signal stage")
                    time.sleep(0.02)
                self.assertIn("runner_pid", resources)
                self.assertEqual("app_starting" if stage == "startup" else "paced_wait", resources["phase"])
                self.assertTrue(resources["pids"] and all(verifier.alive(pid) for pid in resources["pids"]))
                started = time.monotonic()
                process.send_signal(number)
                output, _ = process.communicate(timeout=7.5)
                elapsed = time.monotonic() - started
                self.assertLess(elapsed, 7.5)
                self.assertEqual(1, process.returncode)
                self.assertIn({"verification": "interrupted"}, [json.loads(line) for line in output.decode().splitlines()])
                self.assertFalse(verifier.alive(resources["runner_pid"]))
                self.assertFalse(any(verifier.alive(pid) for pid in resources["pids"]))
                with self.assertRaises(ProcessLookupError):
                    os.killpg(resources["runner_pid"], 0)
                self.assertFalse(Path(resources["private_directory"]).exists())
                remaining = subprocess.run(["docker", "ps", "-a", "--filter", "name=^/" + resources["container"] + "$",
                                            "--format", "{{.Names}}"], check=True, capture_output=True, timeout=2)
                self.assertFalse(remaining.stdout.strip())
                print(json.dumps({"scenario": "outer_" + signal.Signals(number).name + "_" + stage,
                                  "state": "passed", "observed_phase": resources["phase"],
                                  "cancellation_to_exit_ms": round(elapsed * 1000, 2),
                                  "harness_sha256": hashlib.sha256((SCRIPTS / "run.py").read_bytes()).hexdigest(),
                                  "verification_sha256": hashlib.sha256((SCRIPTS / "verify_cancellation.py").read_bytes()).hexdigest(),
                                  "owned_resources_removed": True}), flush=True)
            finally:
                with verifier.shield_cleanup_signals():
                    for number in (signal.SIGINT, signal.SIGTERM):
                        signal.signal(number, lambda *_: setattr(self, "cancelled", True))
                    issues = []
                    def attempt(label, operation):
                        try:
                            operation()
                        except Exception:
                            issues.append(label)
                    if process.poll() is None:
                        attempt("outer_signal", lambda: process.send_signal(signal.SIGTERM))
                        try:
                            process.wait(timeout=4.5)
                        except subprocess.TimeoutExpired:
                            attempt("outer_kill", lambda: os.killpg(process.pid, signal.SIGKILL))
                            attempt("outer_reap", lambda: process.wait(timeout=0.25))
                        except Exception:
                            issues.append("outer_wait")
                    controls = list(Path(root).glob("mnema-cancellation-proof-*/control.json"))
                    if controls:
                        try:
                            resources = json.loads(controls[0].read_text())
                        except Exception:
                            issues.append("ownership_read")
                    if resources:
                        if verifier.alive(resources["runner_pid"]):
                            attempt("runner_group", lambda: os.killpg(resources["runner_pid"], signal.SIGKILL))
                        def remove_container():
                            result = subprocess.run(["docker", "rm", "--force", "--volumes", resources["container"]],
                                                    capture_output=True, timeout=1.5)
                            if result.returncode and b"No such container" not in result.stderr:
                                raise RuntimeError("owned container cleanup failed")
                        attempt("container", remove_container)
                        private = Path(resources["private_directory"])
                        parent = Path.home() if sys.platform == "darwin" else Path(os.environ.get("RUNNER_TEMP", root))
                        if private.parent == parent and private.name.startswith("mnema-learning-security-") and private.exists():
                            attempt("private_directory", lambda: verifier.shutil.rmtree(private))
                    self.assertEqual([], issues, "outer-test fixture cleanup incomplete")
                if self.cancelled:
                    raise KeyboardInterrupt()

    def test_outer_sigterm_during_startup(self):
        self.check_stage("startup", signal.SIGTERM)

    def test_outer_sigint_during_paced_wait(self):
        self.check_stage("paced_wait", signal.SIGINT)


if __name__ == "__main__":
    unittest.main()
