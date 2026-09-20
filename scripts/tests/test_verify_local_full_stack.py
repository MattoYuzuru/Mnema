"""Contract and negative checks for the persistent local full-stack launcher."""

import hashlib
import importlib.util
import json
import os
from pathlib import Path
import stat
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
LAUNCHER = ROOT / "scripts/mnema-local-full-stack.sh"
COMPOSE = ROOT / "compose.local-full-stack.yml"
SMOKE = ROOT / "scripts/local-full-stack/smoke.py"


def load_smoke_module():
    spec = importlib.util.spec_from_file_location("mnema_local_full_stack_smoke", SMOKE)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class LocalFullStackTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="mnema-local-launcher-test-")
        self.state = Path(self.temp.name) / "state"
        self.environment = {**os.environ, "MNEMA_LOCAL_STATE_DIR": str(self.state)}

    def tearDown(self):
        self.temp.cleanup()

    def run_launcher(self, *arguments, check=True):
        return subprocess.run(
            [str(LAUNCHER), *arguments], cwd=ROOT, env=self.environment,
            text=True, capture_output=True, check=check, timeout=30,
        )

    def bootstrap(self):
        self.run_launcher("bootstrap")

    def test_bootstrap_is_private_and_stable_across_ordinary_restarts(self):
        self.bootstrap()
        private = [
            "runtime.env", "identity-signing-jwk-set.json", "local-ca.key", "localhost.key",
            "learning-truststore.p12",
        ]
        before = {name: hashlib.sha256((self.state / name).read_bytes()).hexdigest() for name in private}
        for name in private:
            self.assertEqual(0, stat.S_IMODE((self.state / name).stat().st_mode) & 0o077, name)
        self.run_launcher("bootstrap")
        after = {name: hashlib.sha256((self.state / name).read_bytes()).hexdigest() for name in private}
        self.assertEqual(before, after)
        subprocess.run([
            "openssl", "verify", "-CAfile", str(self.state / "local-ca.crt"),
            str(self.state / "localhost.crt"),
        ], check=True, capture_output=True, timeout=10)
        ca_text = subprocess.run(
            ["openssl", "x509", "-in", str(self.state / "local-ca.crt"), "-text", "-noout"],
            check=True, capture_output=True, text=True, timeout=10,
        ).stdout
        self.assertIn("CA:TRUE", ca_text)
        self.assertIn("Certificate Sign", ca_text)

    def test_bootstrap_prefers_the_gnu_stat_mode_form(self):
        fake_bin = Path(self.temp.name) / "bin"
        fake_bin.mkdir()
        fake_stat = fake_bin / "stat"
        fake_stat.write_text(
            "#!/bin/sh\n"
            "if [ \"$1\" = -c ]; then printf '600\\n'; exit 0; fi\n"
            "if [ \"$1\" = -f ]; then printf 'File: fixture\\n600\\n'; exit 0; fi\n"
            "exit 2\n"
        )
        fake_stat.chmod(0o700)
        self.environment["PATH"] = f"{fake_bin}{os.pathsep}{os.environ['PATH']}"

        self.bootstrap()

        self.assertTrue((self.state / "runtime.env").is_file())

    def test_compose_contract_renders_without_exposing_plaintext_apps(self):
        self.bootstrap()
        values = dict(line.split("=", 1) for line in (self.state / "runtime.env").read_text().splitlines())
        environment = {
            **os.environ,
            **values,
            "COMPOSE_DISABLE_ENV_FILE": "true",
            "MNEMA_LOCAL_BUILD_ID": "test",
            "MNEMA_LOCAL_IDENTITY_SIGNING_JWK_SET_FILE": str(self.state / "identity-signing-jwk-set.json"),
            "MNEMA_LOCAL_TLS_CERT_FILE": str(self.state / "localhost.crt"),
            "MNEMA_LOCAL_TLS_KEY_FILE": str(self.state / "localhost.key"),
            "MNEMA_LOCAL_TRUSTSTORE_FILE": str(self.state / "learning-truststore.p12"),
        }
        subprocess.run(
            ["docker", "compose", "--file", str(COMPOSE), "config", "--quiet"],
            cwd=ROOT, env=environment, check=True, capture_output=True, timeout=20,
        )
        source = COMPOSE.read_text()
        identity = source.split("  identity-account:\n", 1)[1].split("\n  learning:\n", 1)[0]
        learning = source.split("  learning:\n", 1)[1].split("\n  frontend:\n", 1)[0]
        self.assertNotIn("\n    ports:", identity)
        self.assertNotIn("\n    ports:", learning)
        self.assertIn('"127.0.0.1:${MNEMA_LOCAL_WEB_PORT:-3443}:8443"', source)
        self.assertIn("local_postgres_data:/var/lib/postgresql", source)
        self.assertIn("LEARNING_IDENTITY_TRANSPORT_BASE: https://frontend:8444", source)
        self.assertNotIn("allow-loopback-http", source)
        self.assertNotIn("deploy/local-full-stack/nginx.conf:/etc/nginx", source)
        runtime = (ROOT / "deploy/local-full-stack/backend-runtime.Dockerfile").read_text()
        frontend_runtime = (ROOT / "deploy/local-full-stack/frontend.Dockerfile").read_text()
        release = (ROOT / "backend/Dockerfile").read_text()
        pinned = next(line for line in release.splitlines() if line.startswith("FROM eclipse-temurin:"))
        self.assertIn(pinned.replace(" AS backend-runtime", " AS backend-runtime"), runtime)
        self.assertIn("COPY deploy/local-full-stack/nginx.conf", frontend_runtime)
        self.assertIn(".mnema", (ROOT / ".dockerignore").read_text().splitlines())

    def test_partial_or_corrupt_security_state_fails_before_compose(self):
        self.state.mkdir(mode=0o700)
        (self.state / "runtime.env").write_text("MNEMA_LOCAL_POSTGRES_PASSWORD=partial\n")
        result = self.run_launcher("bootstrap", check=False)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("partial local security state", result.stderr)

        self.temp.cleanup()
        self.temp = tempfile.TemporaryDirectory(prefix="mnema-local-launcher-test-")
        self.state = Path(self.temp.name) / "state"
        self.environment["MNEMA_LOCAL_STATE_DIR"] = str(self.state)
        self.bootstrap()
        (self.state / "localhost.crt").write_text("not a certificate")
        result = self.run_launcher("bootstrap", check=False)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("not signed by the retained local CA", result.stderr)

    def test_permissive_smoke_credentials_fail_before_network(self):
        self.bootstrap()
        smoke_state = self.state / "smoke-account.json"
        smoke_state.write_text("{}")
        smoke_state.chmod(0o644)
        result = self.run_launcher("smoke", check=False)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("must not be accessible", result.stderr)

    def test_data_reset_requires_the_exact_destructive_confirmation(self):
        result = self.run_launcher("reset", check=False)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("--confirm-delete-local-data", result.stderr)
        self.assertFalse(self.state.exists())

    def test_stop_without_bootstrap_fails_without_creating_state(self):
        result = self.run_launcher("stop", check=False)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("run '" + str(LAUNCHER) + " bootstrap'", result.stderr)
        self.assertFalse(self.state.exists())

    def test_certificate_reset_preserves_database_credentials_and_signing_key(self):
        self.bootstrap()
        fake_bin = Path(self.temp.name) / "bin"
        fake_bin.mkdir()
        docker = fake_bin / "docker"
        docker.write_text("#!/bin/sh\nexit 0\n")
        docker.chmod(0o700)
        self.environment["PATH"] = f"{fake_bin}{os.pathsep}{os.environ['PATH']}"
        retained = ["runtime.env", "identity-signing-jwk-set.json"]
        before = {name: hashlib.sha256((self.state / name).read_bytes()).hexdigest() for name in retained}
        old_ca = hashlib.sha256((self.state / "local-ca.crt").read_bytes()).hexdigest()
        self.run_launcher("reset-certificates", "--confirm")
        after = {name: hashlib.sha256((self.state / name).read_bytes()).hexdigest() for name in retained}
        self.assertEqual(before, after)
        self.assertNotEqual(old_ca, hashlib.sha256((self.state / "local-ca.crt").read_bytes()).hexdigest())

    def test_smoke_exercises_real_study_modes_and_canonical_effect_boundary(self):
        smoke = SMOKE.read_text()
        self.assertIn('anonymous == 401', smoke)
        self.assertNotIn('#219 not integrated', smoke)
        self.assertIn('start_session(web, access, deck_id, "SCHEDULED")', smoke)
        self.assertIn('start_session(web, access, deck_id, "REPLAY"', smoke)
        self.assertIn('start_session(web, access, deck_id, "PRACTICE")', smoke)
        self.assertIn('"canonicalEffects"', smoke)
        self.assertIn('"persistentAuthoring": True', smoke)

        module = load_smoke_module()
        module.require_feedback_only({
            "mode": "REPLAY", "status": "ASSESSED", "canonicalEffects": False,
            "evidence": None, "transition": None,
        }, "REPLAY")

    def test_feedback_only_attempt_rejects_canonical_state(self):
        module = load_smoke_module()
        for outcome in ({
            "mode": "PRACTICE", "status": "ASSESSED", "canonicalEffects": True,
            "evidence": None, "transition": None,
        }, {
            "mode": "REPLAY", "status": "ASSESSED", "canonicalEffects": False,
            "evidence": {"result": "CORRECT"}, "transition": None,
        }):
            with self.assertRaisesRegex(AssertionError, "claimed canonical effects"):
                module.require_feedback_only(outcome, outcome["mode"])

    def test_legacy_smoke_state_upgrades_without_rotating_credentials(self):
        module = load_smoke_module()
        state = self.state / "smoke-account.json"
        self.state.mkdir(mode=0o700)
        legacy = {
            "email": "smoke@example.invalid", "login": "smoke", "password": "retained",
            "deckId": "11111111-1111-4111-8111-111111111111",
            "captureId": "22222222-2222-4222-8222-222222222222",
        }
        state.write_text(json.dumps(legacy))
        state.chmod(0o600)

        upgraded, fresh = module.load_or_create_account(state)

        self.assertFalse(fresh)
        self.assertEqual("retained", upgraded["password"])
        self.assertEqual(2, upgraded["schemaVersion"])
        self.assertIsNone(upgraded["studyExerciseId"])

    def test_launcher_keeps_bounded_failure_diagnostics(self):
        launcher = LAUNCHER.read_text()
        self.assertIn("compose logs --tail=80", launcher)
        self.assertIn("compose stop >/dev/null", launcher)


if __name__ == "__main__":
    unittest.main()
