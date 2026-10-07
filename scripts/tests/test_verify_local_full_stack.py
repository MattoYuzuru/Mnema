"""Contract and negative checks for the persistent local full-stack launcher."""

import hashlib
import importlib.util
import itertools
import json
import os
from pathlib import Path
import stat
import subprocess
import tempfile
import unittest
import uuid


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

    def test_legacy_local_only_truststore_retains_identity_material_and_adds_public_roots(self):
        self.bootstrap()
        protected = ["runtime.env", "identity-signing-jwk-set.json", "local-ca.key", "local-ca.crt", "localhost.crt"]
        before = {name: (self.state / name).read_bytes() for name in protected}
        truststore = self.state / "learning-truststore.p12"
        truststore.unlink()
        subprocess.run(["keytool", "-importcert", "-noprompt", "-storetype", "PKCS12",
                        "-alias", "mnema-local-ca", "-file", str(self.state / "local-ca.crt"),
                        "-keystore", str(truststore), "-storepass", "changeit"],
                       check=True, capture_output=True, timeout=10)
        truststore.chmod(0o600)
        self.run_launcher("bootstrap")
        listing = subprocess.run(["keytool", "-list", "-rfc", "-keystore", str(truststore),
                                  "-storetype", "PKCS12", "-storepass", "changeit"],
                                 check=True, capture_output=True, text=True, timeout=10).stdout
        self.assertGreater(listing.count("BEGIN CERTIFICATE"), 1)
        self.assertIn("mnema-local-ca", listing)
        self.assertEqual(before, {name: (self.state / name).read_bytes() for name in protected})
        upgraded = truststore.read_bytes()
        self.run_launcher("bootstrap")
        self.assertEqual(upgraded, truststore.read_bytes())

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
            "MNEMA_LOCAL_STORAGE_TLS_CERT_FILE": str(self.state / "storage.crt"),
            "MNEMA_LOCAL_STORAGE_TLS_KEY_FILE": str(self.state / "storage.key"),
            "MNEMA_LOCAL_CA_CERT_FILE": str(self.state / "local-ca.crt"),
            "MNEMA_LOCAL_MEDIA_WORK_ROOT": str(self.state / "media-processing"),
        }
        for configuration in ("production", "development"):
            build_environment = {**environment, "MNEMA_LOCAL_FRONTEND_CONFIGURATION": configuration}
            if configuration == "production":
                build_environment.pop("MNEMA_LOCAL_FRONTEND_CONFIGURATION")
            rendered = subprocess.run(
                ["docker", "compose", "--file", str(COMPOSE), "config", "--format", "json"],
                cwd=ROOT, env=build_environment, check=True, capture_output=True, text=True, timeout=20,
            )
            frontend_build = json.loads(rendered.stdout)["services"]["frontend"]["build"]
            self.assertEqual(configuration, frontend_build["args"]["MNEMA_FRONTEND_CONFIGURATION"])
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

    def test_optional_oauth_env_is_data_not_shell_and_missing_explicit_file_fails(self):
        self.bootstrap()
        fake_bin = Path(self.temp.name) / "bin"
        fake_bin.mkdir()
        arguments = Path(self.temp.name) / "compose-arguments"
        revision = Path(self.temp.name) / "truststore-revision"
        fake_docker = fake_bin / "docker"
        fake_docker.write_text("#!/bin/sh\nif [ \"$2\" = version ]; then exit 0; fi\nprintf '%s\\n' \"$@\" > \"$MNEMA_OAUTH_ARGUMENTS\"\nprintf '%s' \"$MNEMA_LOCAL_TRUSTSTORE_REVISION\" > \"$MNEMA_OAUTH_REVISION\"\n")
        fake_docker.chmod(0o700)
        oauth_file = Path(self.temp.name) / "oauth.env"
        marker = Path(self.temp.name) / "must-not-execute"
        oauth_file.write_text(f"GH_CLIENT_ID=synthetic\nGH_CLIENT_SECRET=synthetic\nOTHER=$(touch {marker})\n")
        self.environment.update(PATH=f"{fake_bin}{os.pathsep}{os.environ['PATH']}",
                                MNEMA_LOCAL_OAUTH_ENV_FILE=str(oauth_file), MNEMA_OAUTH_ARGUMENTS=str(arguments),
                                MNEMA_OAUTH_REVISION=str(revision))
        self.run_launcher("status")
        args = arguments.read_text().splitlines()
        self.assertEqual(["compose", "--env-file", str(oauth_file)], args[:3])
        self.assertFalse(marker.exists())
        self.assertEqual(hashlib.sha256((self.state / "learning-truststore.p12").read_bytes()).hexdigest(), revision.read_text())
        self.assertIn("MNEMA_LOCAL_TRUSTSTORE_REVISION: ${MNEMA_LOCAL_TRUSTSTORE_REVISION:-initial}", COMPOSE.read_text())
        self.environment["MNEMA_LOCAL_OAUTH_ENV_FILE"] = str(oauth_file) + "-missing"
        result = self.run_launcher("status", check=False)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("OAuth env file is not readable", result.stderr)

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
        self.assertIn('payload["budget"]["maxNewObjectives"] = 5', smoke)
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
        self.assertEqual(4, upgraded["schemaVersion"])
        self.assertIsNone(upgraded["studyExerciseId"])
        self.assertEqual({}, upgraded["mechanics"])

    def test_retired_mechanic_fixtures_are_dropped_but_credentials_survive(self):
        module = load_smoke_module()
        state = self.state / "smoke-account.json"
        self.state.mkdir(mode=0o700)
        state.write_text(json.dumps({
            "schemaVersion": 3, "email": "smoke@example.invalid", "login": "smoke",
            "password": "retained", "deckId": "deck", "captureId": "capture",
            "studyMemberKey": "member", "studyItemRevisionId": "revision",
            "studyAnswerNodeId": "node", "studyExerciseId": "exercise",
            "p0Mechanics": {"SINGLE_CHOICE": {}},
        }))

        upgraded, fresh = module.load_or_create_account(state)

        self.assertFalse(fresh)
        self.assertEqual(4, upgraded["schemaVersion"])
        self.assertEqual(("retained", "deck", "capture"),
                         (upgraded["password"], upgraded["deckId"], upgraded["captureId"]))
        self.assertIsNone(upgraded["studyExerciseId"])
        self.assertEqual({}, upgraded["mechanics"])
        self.assertNotIn("p0Mechanics", upgraded)

    def test_smoke_covers_all_seven_mechanics_and_fail_closed_capabilities(self):
        module = load_smoke_module()
        self.assertEqual(("SELF_CHECK", "CLOZE", "CHOICE", "MATCH", "ORDER", "CATEGORIZE"),
                         module.ADDITIONAL_MECHANICS)
        smoke = SMOKE.read_text()
        self.assertIn('"/api/capabilities"', smoke)
        self.assertIn('"CAPABILITY_UNAVAILABLE"', smoke)
        self.assertIn('/hints"', smoke)
        self.assertIn('"PAIR_RETRY"', smoke)
        self.assertNotIn('hintsUsed', smoke)

    def test_order_and_categorize_fixtures_follow_the_contract_shape_and_the_agreed_content(self):
        module = load_smoke_module()
        mechanics = json.loads((ROOT / "contracts/study/mechanics.json").read_text())
        for mechanic, create in (("ORDER", "createOrder"), ("CATEGORIZE", "createCategorize")):
            ids = module.mechanic_ids(mechanic)
            exercise = module.mechanic_exercise(mechanic, "member", "revision", "answer", "distractor", ids)
            contract = mechanics[create]["exercise"]
            self.assertEqual(set(contract), set(exercise))
            self.assertEqual(set(contract["content"]), set(exercise["content"]))
            self.assertEqual(contract["answerKey"]["kind"], exercise["answerKey"]["kind"])
            self.assertEqual(set(contract["answerKey"]), set(exercise["answerKey"]))
            self.assertEqual(contract["evaluatorPolicy"], exercise["evaluatorPolicy"])
            self.assertEqual(mechanic, exercise["type"])
            self.assertEqual(2, exercise["schemaVersion"])

        order = module.mechanic_exercise("ORDER", "m", "r", "a", "d", module.mechanic_ids("ORDER"))
        texts = [item["blocks"][0]["text"] for item in order["content"]["items"]]
        self.assertEqual(list(module.ORDER_TILES), texts)
        self.assertEqual({"очень"}, {text for text in texts if texts.count(text) > 1}, "two identical tiles")
        self.assertTrue(any(text.endswith(",") for text in texts), "punctuation must stay attached")
        self.assertTrue(any(ord(character) > 0x2E80 for text in texts for character in text), "CJK tile expected")
        self.assertEqual([item["itemId"] for item in order["content"]["items"]], order["answerKey"]["sequence"])

        ids = module.mechanic_ids("CATEGORIZE")
        groups = module.mechanic_exercise("CATEGORIZE", "m", "r", "a", "d", ids)
        categories = {category["categoryId"] for category in groups["content"]["categories"]}
        assigned = [assignment["categoryId"] for assignment in groups["answerKey"]["assignments"]]
        self.assertEqual(4, len(groups["answerKey"]["assignments"]))
        self.assertTrue(set(assigned) < categories, "one distractor group stays empty")
        self.assertEqual(1, len(categories - set(assigned)))
        self.assertTrue(any(assigned.count(category) >= 2 for category in set(assigned)),
                        "several items must share a group")

    def test_choice_response_accepts_every_issued_shuffle_without_losing_ids_or_resolved_content(self):
        module = load_smoke_module()
        ids = module.mechanic_ids("CHOICE")
        options = [{"optionId": identifier, "blocks": [module.text_block(word)]}
                   for identifier, word in zip(ids["options"], ("memory", "forgetting", "recall"))]
        for shuffled in itertools.permutations(options):
            with self.subTest(order=[option["optionId"] for option in shuffled]):
                shown = {"content": {"selectionMode": "MULTIPLE", "options": list(shuffled)}}
                response = module.mechanic_response(None, "token", "deck", "session", "CHOICE", shown, ids)
                self.assertEqual({"kind": "CHOICE", "optionIds": [ids["options"][2], ids["options"][0]]}, response)

        invalid = [options[:-1], [options[0], options[0], options[2]],
                   [{**option, "blocks": [module.text_block("wrong")]} for option in options]]
        for malformed in invalid:
            with self.subTest(malformed=malformed), self.assertRaisesRegex(AssertionError, "choice options were not resolved"):
                module.mechanic_response(None, "token", "deck", "session", "CHOICE",
                                        {"content": {"selectionMode": "MULTIPLE", "options": malformed}}, ids)

    def test_new_mechanic_responses_are_strict_idempotent_and_keep_the_issued_board(self):
        module = load_smoke_module()

        class FakeWeb:
            """Serves one issued presentation and rejects every malformed attempt like the Learning API."""

            def __init__(self, shown):
                self.shown = shown
                self.attempts = []

            def request(self, method, path, body=None, bearer=None, headers=None):
                if method == "GET":
                    return 200, {"cache-control": "private, no-store"}, {"presentations": [self.shown]}
                self.attempts.append(body["response"])
                return 400, {"cache-control": "private, no-store"}, {"code": "INVALID_REQUEST"}

        for mechanic in ("ORDER", "CATEGORIZE"):
            ids = module.mechanic_ids(mechanic)
            exercise = module.mechanic_exercise(mechanic, "m", "r", "a", "d", ids)
            content = json.loads(json.dumps(exercise["content"]))
            content["items"].reverse()  # a shuffled board; categories keep their authored order
            shown = {"presentationId": str(uuid.uuid4()), "nonce": "n" * 24, "content": content}
            web = FakeWeb(shown)
            response = module.mechanic_response(web, "token", "deck", "session", mechanic, shown, ids)
            self.assertEqual(mechanic, response["kind"])
            self.assertEqual(2, len(web.attempts), "two malformed answers are refused first")
            if mechanic == "ORDER":
                sequence = response["sequence"]
                self.assertEqual(sorted(ids["items"]), sorted(sequence))
                self.assertEqual([ids["items"][i] for i in (0, 2, 1, 3, 4)], sequence)
            else:
                self.assertEqual(sorted(ids["items"]), sorted(a["itemId"] for a in response["assignments"]))
                self.assertEqual({a["itemId"]: a["categoryId"] for a in exercise["answerKey"]["assignments"]},
                                 {a["itemId"]: a["categoryId"] for a in response["assignments"]})

        # a board that changes between reads, or tiles that lost their text, are failures
        ids = module.mechanic_ids("ORDER")
        exercise = module.mechanic_exercise("ORDER", "m", "r", "a", "d", ids)
        shown = {"presentationId": str(uuid.uuid4()), "nonce": "n" * 24, "content": exercise["content"]}
        web = FakeWeb(dict(shown, content=dict(exercise["content"], items=exercise["content"]["items"][::-1])))
        with self.assertRaisesRegex(AssertionError, "board changed"):
            module.mechanic_response(web, "token", "deck", "session", "ORDER", shown, ids)
        lossy = json.loads(json.dumps(shown))
        lossy["content"]["items"][3]["blocks"][0]["text"] = "важно"
        with self.assertRaisesRegex(AssertionError, "lost punctuation"):
            module.mechanic_response(FakeWeb(lossy), "token", "deck", "session", "ORDER", lossy, ids)

    def test_presentation_leak_guard_covers_the_new_mechanic_keys(self):
        module = load_smoke_module()
        self.assertEqual([".ORDER.content.sequence"], module.private_keys({"ORDER": {"content": {"sequence": []}}}))
        self.assertEqual([".CATEGORIZE.content.assignments"],
                         module.private_keys({"CATEGORIZE": {"content": {"assignments": []}}}))
        self.assertEqual([], module.private_keys({"ORDER": {"content": {"items": [], "prompt": []}}}))

    def test_presentation_leak_guard_allows_only_self_check_reference(self):
        module = load_smoke_module()
        self.assertEqual([], module.private_keys({"SELF_CHECK": {"content": {"reference": []}}}))
        self.assertEqual([".FREE_RESPONSE.reference"],
                         module.private_keys({"FREE_RESPONSE": {"reference": "memory"}}))
        self.assertEqual([".CHOICE.content.options.correctOptionIds"], module.private_keys(
            {"CHOICE": {"content": {"options": [{"correctOptionIds": []}]}}}))

    def test_progress_diff_reports_only_changed_fields(self):
        module = load_smoke_module()
        before = {"memberKey": "m", "state": "DUE", "nextDue": "2026-10-01T00:00:00Z", "title": "t"}
        after = dict(before, state="LEARNING")
        self.assertEqual({}, module.progress_diff(before, dict(before)))
        self.assertEqual({"state": {"before": "DUE", "after": "LEARNING"}}, module.progress_diff(before, after))

    def test_launcher_keeps_bounded_failure_diagnostics(self):
        launcher = LAUNCHER.read_text()
        self.assertIn("compose logs --tail=80", launcher)
        self.assertIn("compose stop >/dev/null", launcher)


if __name__ == "__main__":
    unittest.main()
