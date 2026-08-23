from __future__ import annotations

import importlib.util
import sys
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).parents[1] / "collect_diagnostics.py"
SPEC = importlib.util.spec_from_file_location("collect_diagnostics", MODULE_PATH)
assert SPEC and SPEC.loader
collect_diagnostics = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = collect_diagnostics
SPEC.loader.exec_module(collect_diagnostics)


class CollectDiagnosticsTest(unittest.TestCase):
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
