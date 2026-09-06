#!/usr/bin/env python3
"""Actual two-service cancellation proof: two sequential, disposable signal cases."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import tempfile
import threading
import time

from run import shield_cleanup_signals

RUNNER = Path(__file__).with_name("run.py")
CANCELLATION = threading.Event()


def require(condition, label):
    if not condition:
        raise AssertionError(label)


def alive(pid):
    try:
        os.kill(pid, 0)
        return True
    except ProcessLookupError:
        return False


def interrupted(_number, _frame):
    if not CANCELLATION.is_set():
        CANCELLATION.set()
        raise KeyboardInterrupt()


def cleanup_child(process, control, resources):
    """Give the fixture its own cleanup grace before exact-ownership fallback."""
    issues = []
    with shield_cleanup_signals():
        # Remember cancellation during ordinary cleanup, so the next case is never started.
        for number in (signal.SIGINT, signal.SIGTERM):
            signal.signal(number, lambda *_: CANCELLATION.set())
        def attempt(label, operation):
            try:
                return operation()
            except Exception:
                issues.append(label)
                return None
        if process.poll() is None:
            attempt("child_signal", lambda: process.send_signal(signal.SIGTERM))
            try:
                process.wait(timeout=4)
            except subprocess.TimeoutExpired:
                pass  # The scoped force fallback below is now permitted.
            except Exception:
                issues.append("child_wait")
        if control.exists():
            updated = attempt("ownership_read", lambda: json.loads(control.read_text()))
            if updated is not None:
                resources = updated
        if process.poll() is None or any(alive(pid) for pid in resources.get("pids", [])):
            def kill_group():
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
            attempt("child_group_kill", kill_group)
            attempt("child_reap", lambda: process.wait(timeout=0.5))
        name = resources.get("container", "")
        if re.fullmatch(r"mnema-r74-auth-[0-9a-f]{10}", name):
            def remove_container():
                result = subprocess.run(["docker", "rm", "--force", "--volumes", name], capture_output=True, timeout=2)
                if result.returncode and b"No such container" not in result.stderr:
                    raise RuntimeError("owned container removal failed")
            attempt("container_remove", remove_container)
        private = Path(resources.get("private_directory", "/nonexistent-fixture-directory"))
        parent = Path.home() if sys.platform == "darwin" else Path(os.environ.get("RUNNER_TEMP", tempfile.gettempdir()))
        if private.parent == parent and private.name.startswith("mnema-learning-security-") and private.is_dir():
            attempt("private_directory_remove", lambda: shutil.rmtree(private))
    return issues


def verify(number):
    with tempfile.TemporaryDirectory(prefix="mnema-cancellation-proof-") as directory:
        control = Path(directory) / "control.json"
        process = subprocess.Popen([sys.executable, str(RUNNER), "--clients", "2", "--requests", "2",
                                    "--duration-seconds", "120", "--control-file", str(control)],
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, start_new_session=True)
        resources = {}
        try:
            deadline = time.monotonic() + 90
            while time.monotonic() < deadline:
                if control.exists():
                    resources = json.loads(control.read_text())
                if resources.get("phase") == "paced_wait":
                    break
                require(process.poll() is None, "runner exited before paced workload")
                time.sleep(0.05)
            require(resources.get("phase") == "paced_wait", "paced workload readiness exceeded 90 seconds")
            require(len(resources["pids"]) == 2 and all(alive(pid) for pid in resources["pids"]),
                    "both actual service processes must be running")
            time.sleep(0.1)
            started = time.monotonic()
            process.send_signal(number)
            time.sleep(0.05)
            repeated = signal.SIGTERM if number == signal.SIGINT else signal.SIGINT
            repeated_sent = process.poll() is None
            if repeated_sent:
                process.send_signal(repeated)
            output, _ = process.communicate(timeout=max(0.01, 7.5 - (time.monotonic() - started)))
            elapsed = time.monotonic() - started
            require(elapsed < 7.5, "cleanup exceeded first Actions cancellation grace")
            require(process.returncode == 1, "cancelled runner must exit nonzero normally")
            rows = [json.loads(line) for line in output.decode().splitlines()]
            inputs = next(row for row in rows if row.get("scenario") == "both_real_apps_ready")
            require({"suite": "interrupted"} in rows, "runner did not acknowledge cancellation")
            require(rows[-1] == {"cleanup": "complete", "private_files_retained": False}, "runner cleanup incomplete")
            require(not any(alive(pid) for pid in resources["pids"]), "owned service process survived")
            require(not Path(resources["private_directory"]).exists(), "private fixture directory survived")
            remaining = subprocess.run(["docker", "ps", "-a", "--filter", "name=^/" + resources["container"] + "$",
                                        "--format", "{{.Names}}"], check=True, capture_output=True, timeout=2)
            require(not remaining.stdout.strip(), "owned container survived")
            print(json.dumps({"scenario": signal.Signals(number).name + "_mid_paced_real_services",
                              "state": "passed", "clients": 2, "requests": 2, "scheduled_seconds": 120,
                              "repeated_signal": signal.Signals(repeated).name,
                              "repeated_signal_sent": repeated_sent,
                              "cancellation_to_exit_ms": round(elapsed * 1000, 2),
                              "processes_removed": 2, "container_removed": True, "private_directory_removed": True,
                              "harness_sha256": inputs["harness_sha256"],
                              "identity_jar_sha256": inputs["identity_jar_sha256"],
                              "learning_jar_sha256": inputs["learning_jar_sha256"],
                              "verification_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest()}), flush=True)
        finally:
            issues = cleanup_child(process, control, resources)
            if issues:
                print(json.dumps({"verification_cleanup": "incomplete", "failed_steps": issues}), flush=True)
                raise AssertionError("verifier resource cleanup incomplete")


def main():
    os.umask(0o077)
    CANCELLATION.clear()
    for number in (signal.SIGINT, signal.SIGTERM):
        signal.signal(number, interrupted)
    try:
        for number in (signal.SIGINT, signal.SIGTERM):
            verify(number)
            if CANCELLATION.is_set():
                raise KeyboardInterrupt()
    except KeyboardInterrupt:
        print(json.dumps({"verification": "interrupted"}), flush=True)
        raise SystemExit(1)
    except Exception as error:
        print(json.dumps({"verification": "failed", "reason": str(error) if isinstance(error, AssertionError) else type(error).__name__}), flush=True)
        raise SystemExit(1)


if __name__ == "__main__":
    main()
