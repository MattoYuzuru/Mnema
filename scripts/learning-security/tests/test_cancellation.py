"""Deterministic stdlib regression tests; no Java, Docker or network required."""
import concurrent.futures
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import signal
import subprocess
import threading
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

SPEC = importlib.util.spec_from_file_location("learning_security_fixture", Path(__file__).parents[1] / "run.py")
harness = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(harness)


class CancellationTests(unittest.TestCase):
    def fixture(self):
        fixture = harness.Fixture(SimpleNamespace(clients=2, requests=2, duration_seconds=120,
                                                 keep_on_failure=False, control_file=None))
        self.addCleanup(lambda: fixture.close(True))
        return fixture

    def test_paced_120_second_wait_stops_when_event_is_cancelled(self):
        fixture = self.fixture()
        entered = threading.Event()
        finished = threading.Event()
        errors = []
        fixture.control = lambda phase: entered.set()
        fixture.learning = lambda token: 404
        def execute():
            try:
                fixture.paced_samples("not-a-real-token")
            except concurrent.futures.CancelledError:
                pass
            except BaseException as error:
                errors.append(type(error).__name__)
            finally:
                finished.set()
        thread = threading.Thread(target=execute)
        thread.start()
        try:
            self.assertTrue(entered.wait(1), "paced worker did not enter")
            fixture.cancellation.set()
            self.assertTrue(finished.wait(1), "cancel waited for the 120-second schedule")
            self.assertEqual([], errors)
        finally:
            fixture.cancellation.set()
            thread.join(timeout=2)

    def test_interrupted_executor_never_waits_and_cancels_queued_futures(self):
        fixture = self.fixture()
        future = Mock()
        future.result.side_effect = KeyboardInterrupt
        executor = Mock()
        executor.submit.return_value = future
        with patch.object(harness.concurrent.futures, "ThreadPoolExecutor", return_value=executor):
            with self.assertRaises(KeyboardInterrupt):
                fixture.parallel(lambda value: value, range(20))
        executor.shutdown.assert_called_once_with(wait=False, cancel_futures=True)
        self.assertTrue(fixture.cancellation.is_set())
        self.assertEqual([], fixture.executors)

    def test_cleanup_is_idempotent_and_repeated_signals_cannot_abort_it(self):
        fixture = self.fixture()
        received = []
        previous = {number: signal.signal(number, lambda *_: received.append(True))
                    for number in (signal.SIGINT, signal.SIGTERM)}
        log = Mock()
        def second_signals():
            os.kill(os.getpid(), signal.SIGINT)
            os.kill(os.getpid(), signal.SIGTERM)
        log.close.side_effect = second_signals
        fixture.logs = [log]
        try:
            with contextlib.redirect_stdout(io.StringIO()) as output:
                self.assertTrue(fixture.close(True))
                self.assertTrue(fixture.close(True))
            self.assertEqual([], received)
            log.close.assert_called_once()
            self.assertFalse(fixture.tmp.exists())
            self.assertEqual(1, len(output.getvalue().splitlines()))
        finally:
            for number, handler in previous.items():
                signal.signal(number, handler)

    def test_cleanup_failure_does_not_skip_other_resources_or_report_success(self):
        fixture = self.fixture()
        fixture.container_attempted = True
        broken = Mock()
        broken.close.side_effect = OSError("private detail must not be printed")
        healthy = Mock()
        fixture.logs = [broken, healthy]
        with patch.object(harness.subprocess, "run", side_effect=subprocess.TimeoutExpired("docker", 2.5)) as remove:
            with contextlib.redirect_stdout(io.StringIO()) as output:
                self.assertFalse(fixture.close(True))
                self.assertFalse(fixture.close(True))
        healthy.close.assert_called_once()
        self.assertFalse(fixture.tmp.exists())
        remove.assert_called_once()
        self.assertEqual(2.5, remove.call_args.kwargs["timeout"])
        result = json.loads(output.getvalue())
        self.assertEqual("incomplete", result["cleanup"])
        self.assertEqual(["log_close", "container_remove"], result["failed_steps"])
        self.assertNotIn("private detail", output.getvalue())

    def test_all_processes_terminate_before_any_reap_wait(self):
        fixture = self.fixture()
        actions = []
        class Process:
            def __init__(self, pid):
                self.pid, self.status = pid, None
            def poll(self):
                return self.status
            def send_signal(self, number):
                actions.append(("resume", self.pid))
            def terminate(self):
                actions.append(("terminate", self.pid))
                self.status = 0
        fixture.processes = [Process(1), Process(2)]
        self.assertTrue(fixture.close(True))
        self.assertEqual([("resume", 1), ("terminate", 1), ("resume", 2), ("terminate", 2)], actions)

    def test_failed_directory_removal_reports_actual_retained_files(self):
        fixture = self.fixture()
        with patch.object(harness.shutil, "rmtree", side_effect=OSError("private failure")):
            with contextlib.redirect_stdout(io.StringIO()) as output:
                self.assertFalse(fixture.close(True))
        result = json.loads(output.getvalue())
        self.assertEqual("incomplete", result["cleanup"])
        self.assertTrue(result["private_files_retained"])
        harness.shutil.rmtree(fixture.tmp)

    def test_stubborn_processes_are_killed_after_shared_grace_not_sequential_waits(self):
        fixture = self.fixture()
        actions = []
        class Process:
            def __init__(self, pid):
                self.pid, self.status = pid, None
            def poll(self):
                return self.status
            def send_signal(self, number):
                pass
            def terminate(self):
                actions.append(("terminate", self.pid))
            def kill(self):
                actions.append(("kill", self.pid))
                self.status = -9
        fixture.processes = [Process(1), Process(2)]
        with patch.object(harness.time, "monotonic", side_effect=[0, 1, 2]), patch.object(harness.time, "sleep"):
            self.assertTrue(fixture.close(True))
        self.assertEqual([("terminate", 1), ("terminate", 2), ("kill", 1), ("kill", 2)], actions)

    def test_uncertain_docker_run_failure_still_removes_exact_owned_name(self):
        fixture = self.fixture()
        fixture.control = Mock()
        def launch(arguments, **_):
            if arguments[:2] == ["docker", "run"]:
                raise subprocess.CalledProcessError(1, "docker")
        with patch.object(harness, "command", side_effect=launch), patch.object(harness.os, "chmod"):
            with self.assertRaises(subprocess.CalledProcessError):
                fixture.start()
        self.assertTrue(fixture.container_attempted)
        self.assertFalse(fixture.started_container)
        self.assertEqual(["allocated", "container_starting"], [call.args[0] for call in fixture.control.call_args_list])
        with patch.object(harness.subprocess, "run", return_value=SimpleNamespace(returncode=1, stderr=b"No such container")) as remove:
            self.assertTrue(fixture.close(True))
        self.assertEqual(["docker", "rm", "--force", "--volumes", fixture.container], remove.call_args.args[0])

    def test_postgres_readiness_command_is_bounded(self):
        fixture = self.fixture()
        with patch.object(harness, "command", return_value=SimpleNamespace(stdout=b"127.0.0.1:15499")), \
                patch.object(harness.os, "chmod"), \
                patch.object(harness.subprocess, "run", side_effect=subprocess.TimeoutExpired("pg_isready", 2)) as ready:
            with self.assertRaises(subprocess.TimeoutExpired):
                fixture.start()
        self.assertEqual(2, ready.call_args.kwargs["timeout"])
        fixture.container_attempted = False  # All Docker operations in this unit test were mocked.


if __name__ == "__main__":
    unittest.main()
