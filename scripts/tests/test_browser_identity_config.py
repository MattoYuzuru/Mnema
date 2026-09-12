"""Value-silent runtime config checks; all files and inputs are synthetic/disposable."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


GENERATOR = Path(__file__).resolve().parents[2] / "frontend/docker/40-gen-app-config.sh"


class BrowserIdentityConfigTests(unittest.TestCase):
    def generate(self, **overrides):
        with tempfile.TemporaryDirectory(prefix="mnema-identity-config-") as directory:
            root = Path(directory)
            env = {key: value for key, value in os.environ.items() if not key.startswith("MNEMA_")}
            env.update(MNEMA_APP_CONFIG_OUT=str(root / "app.js"), MNEMA_AI_ROUTE_OUT=str(root / "ai.inc"),
                       MNEMA_SECURITY_HEADERS_OUT=str(root / "headers.inc"), MNEMA_APP_ENV="development",
                       MNEMA_PUBLIC_ORIGIN="https://localhost:8443", MNEMA_AUTH_SERVER_URL="https://localhost:9443")
            env.update(overrides)
            result = subprocess.run([str(GENERATOR)], env=env, capture_output=True, text=True, timeout=5)
            content = (root / "app.js").read_text() if (root / "app.js").exists() else ""
            return result.returncode, content, result.stderr

    def test_canonical_callback_and_learning_defaults(self):
        code, content, _ = self.generate()
        self.assertEqual(code, 0)
        self.assertIn('identityRedirectUri = "https://localhost:8443/auth/callback"', content)
        self.assertIn('learningApiBaseUrl = "/api"', content)

    def test_rejects_same_origin_identity(self):
        code, _, _ = self.generate(MNEMA_AUTH_SERVER_URL="https://localhost:8443")
        self.assertNotEqual(code, 0)

    def test_rejects_unsafe_callback_and_learning_overrides(self):
        for callback in ["http://localhost:8443/auth/callback", "https://user@localhost/auth/callback",
                         "https://localhost/auth/callback?x=1", "https://localhost/path/auth/callback",
                         "https://localhost/auth/callback#x", "https://localhost/auth/callback\n"]:
            with self.subTest(callback=callback):
                self.assertNotEqual(self.generate(MNEMA_IDENTITY_REDIRECT_URI=callback)[0], 0)
        for base in ["https://other.test/api", "/api/core", "/api\n"]:
            with self.subTest(base=base):
                self.assertNotEqual(self.generate(MNEMA_LEARNING_API_BASE_URL=base)[0], 0)

    def test_runtime_strings_reject_controls_without_echoing_the_value(self):
        for control in ["\n", "\r", "\t", "\x7f"]:
            code, _, error = self.generate(MNEMA_CLIENT_ID=f"private-sentinel{control}injection")
            self.assertNotEqual(code, 0)
            self.assertNotIn("private-sentinel", error)

    def test_hosted_callback_must_match_the_frontend(self):
        code, _, _ = self.generate(MNEMA_APP_ENV="prod", MNEMA_PUBLIC_ORIGIN="https://app.example.test",
                                  MNEMA_AUTH_SERVER_URL="https://auth.example.test", MNEMA_STORAGE_ORIGIN="https://storage.example.test",
                                  MNEMA_IDENTITY_REDIRECT_URI="https://other.example.test/auth/callback")
        self.assertNotEqual(code, 0)


if __name__ == "__main__":
    unittest.main()
