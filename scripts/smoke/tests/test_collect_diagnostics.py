from __future__ import annotations

import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


MODULE_PATH = Path(__file__).parents[1] / "collect_diagnostics.py"
SPEC = importlib.util.spec_from_file_location("collect_diagnostics", MODULE_PATH)
assert SPEC and SPEC.loader
collect_diagnostics = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = collect_diagnostics
SPEC.loader.exec_module(collect_diagnostics)


class CollectDiagnosticsTest(unittest.TestCase):
    def test_workloads_survive_denied_route_read_without_ingress_list_permission(self) -> None:
        calls = []

        def run(command):
            calls.append(command)
            if command[4] == "ingress":
                return "command_status=failed\n"
            if command[4] == "pods,deployments,statefulsets,services":
                return "mnema-learning 1/1\n"
            return ""

        with tempfile.TemporaryDirectory() as directory, patch.object(
            collect_diagnostics, "run", side_effect=run
        ), patch.object(collect_diagnostics, "non_ready_services", return_value=set()):
            output = Path(directory)
            collect_diagnostics.collect("mnema-staging", output, None)
            self.assertEqual("mnema-learning 1/1\n", (output / "workloads.txt").read_text())
            self.assertEqual("command_status=failed\n", (output / "mnema-ingress.txt").read_text())
            route_reads = [command for command in calls if command[4] == "ingress"]
            self.assertEqual(["mnema", "mnema-auth"], [command[5] for command in route_reads])
            self.assertFalse(any("ingresses" in part for command in calls for part in command))

    def test_redacts_credentials_and_email_without_removing_operational_context(self) -> None:
        raw = (
            "level=ERROR service=auth email=user@example.com "
            "Authorization: Bearer eyJhbGciOi.secret.signature "
            "password=hunter2 api_key=abcdef username=actual-user "
            "userId=123e4567-e89b-42d3-a456-426614174000 remote=203.0.113.42 "
            "pod=mnema-auth-123\n"
            '{"client_secret":"json-secret","access_token":"eyJhbGciOiJIUzI1NiJ9.'
            'eyJzdWIiOiJzbW9rZSJ9.signature123"}\n'
            "oauth.refresh_token=refresh-value Set-Cookie: session=raw-cookie; Secure\n"
            "aws=AKIAABCDEFGHIJKLMNOP\n"
            "-----BEGIN PRIVATE KEY-----\nprivate-material\n-----END PRIVATE KEY-----\n"
        )

        sanitized = collect_diagnostics.redact(raw)

        self.assertNotIn("user@example.com", sanitized)
        self.assertNotIn("hunter2", sanitized)
        self.assertNotIn("abcdef", sanitized)
        self.assertNotIn("eyJhbGciOi", sanitized)
        self.assertNotIn("actual-user", sanitized)
        self.assertNotIn("123e4567", sanitized)
        self.assertNotIn("203.0.113.42", sanitized)
        self.assertNotIn("json-secret", sanitized)
        self.assertNotIn("refresh-value", sanitized)
        self.assertNotIn("raw-cookie", sanitized)
        self.assertNotIn("AKIAABCDEFGHIJKLMNOP", sanitized)
        self.assertNotIn("private-material", sanitized)
        self.assertNotIn("eyJzdWIiOiJzbW9rZSJ9", sanitized)
        self.assertIn("service=auth", sanitized)
        self.assertIn("pod=mnema-auth-123", sanitized)


if __name__ == "__main__":
    unittest.main()
