"""Object-storage, media-runner and media-smoke contracts for the local full stack."""

import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import stat
import struct
import subprocess
import tempfile
import unittest
import zlib


ROOT = Path(__file__).resolve().parents[2]
LAUNCHER = ROOT / "scripts/mnema-local-full-stack.sh"
COMPOSE = ROOT / "compose.local-full-stack.yml"
RUNTIME_DOCKERFILE = ROOT / "deploy/local-full-stack/backend-runtime.Dockerfile"
MEDIA_SMOKE = ROOT / "scripts/local-full-stack/media_smoke.py"


def load_media_smoke():
    spec = importlib.util.spec_from_file_location("mnema_local_full_stack_media_smoke", MEDIA_SMOKE)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def service_block(source, name):
    """The text of one compose service: its header line and every deeper-indented or blank line."""
    lines = source.splitlines()
    start = lines.index(f"  {name}:")
    block = [lines[start]]
    for line in lines[start + 1:]:
        if line.strip() and not line.startswith("    "):
            if line.startswith("  #"):
                continue
            break
        block.append(line)
    return "\n".join(block)


class LauncherTestCase(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="mnema-local-media-test-")
        self.addCleanup(self.temp.cleanup)
        self.state = Path(self.temp.name) / "state"
        self.environment = {**os.environ, "MNEMA_LOCAL_STATE_DIR": str(self.state)}

    def run_launcher(self, *arguments, check=True):
        return subprocess.run(
            [str(LAUNCHER), *arguments], cwd=ROOT, env=self.environment,
            text=True, capture_output=True, check=check, timeout=60,
        )

    def runtime(self):
        return dict(line.split("=", 1) for line in (self.state / "runtime.env").read_text().splitlines())

    def fake_tools(self, **scripts):
        """Install shell shims first on PATH; each records its argv to <name>.log."""
        fake_bin = Path(self.temp.name) / "bin"
        fake_bin.mkdir(exist_ok=True)
        for name, body in scripts.items():
            tool = fake_bin / name
            tool.write_text(f"#!/bin/sh\nprintf '%s\\n' \"$*\" >> '{fake_bin}/{name}.log'\n{body}\n")
            tool.chmod(0o700)
        self.environment["PATH"] = f"{fake_bin}{os.pathsep}{os.environ['PATH']}"
        return fake_bin

    @staticmethod
    def calls(fake_bin, name):
        log = fake_bin / f"{name}.log"
        return log.read_text().splitlines() if log.exists() else []


class ObjectStorageStateTest(LauncherTestCase):
    def test_bootstrap_creates_private_storage_credentials_and_certificate(self):
        self.run_launcher("bootstrap")
        runtime = self.runtime()
        self.assertRegex(runtime["MNEMA_LOCAL_S3_ACCESS_KEY"], r"^[0-9a-f]{32}$")
        self.assertRegex(runtime["MNEMA_LOCAL_S3_SECRET_KEY"], r"^[0-9a-f]{64}$")
        self.assertEqual("3445", runtime["MNEMA_LOCAL_STORAGE_PORT"])
        self.assertEqual(0o600, stat.S_IMODE((self.state / "storage.key").stat().st_mode))
        self.assertFalse((self.state / "media-processing").exists())  # the work directory is a Compose volume, not host state
        subprocess.run([
            "openssl", "verify", "-CAfile", str(self.state / "local-ca.crt"), str(self.state / "storage.crt"),
        ], check=True, capture_output=True, timeout=10)
        subprocess.run([
            "openssl", "x509", "-checkhost", "storage.mnema.localhost", "-noout", "-in", str(self.state / "storage.crt"),
        ], check=True, capture_output=True, timeout=10)
        before = {name: digest(self.state / name) for name in ("runtime.env", "storage.key", "storage.crt")}
        self.run_launcher("bootstrap")
        self.assertEqual(before, {name: digest(self.state / name) for name in before})

    def test_leaf_certificates_carry_key_identifiers_for_strict_tls_clients(self):
        self.run_launcher("bootstrap")
        for name in ("localhost.crt", "storage.crt"):
            text = subprocess.run(["openssl", "x509", "-noout", "-text", "-in", str(self.state / name)],
                                  check=True, capture_output=True, text=True, timeout=10).stdout
            self.assertIn("Subject Key Identifier", text, name)
            self.assertIn("Authority Key Identifier", text, name)
        ca = subprocess.run(["openssl", "x509", "-noout", "-text", "-in", str(self.state / "local-ca.crt")],
                            check=True, capture_output=True, text=True, timeout=10).stdout
        self.assertIn("Subject Key Identifier", ca)

    def test_certificate_without_authority_key_identifier_fails_and_reset_certificates_repairs_it(self):
        self.run_launcher("bootstrap")
        self.fake_tools(docker="exit 0")
        extension = self.state / "legacy.ext"
        extension.write_text("subjectAltName=DNS:localhost,DNS:frontend\nauthorityKeyIdentifier=none\n")
        request = self.state / "legacy.csr"
        subprocess.run(["openssl", "req", "-new", "-key", str(self.state / "localhost.key"), "-subj", "/CN=localhost",
                        "-out", str(request)], check=True, capture_output=True, timeout=10)
        subprocess.run(["openssl", "x509", "-req", "-in", str(request), "-CA", str(self.state / "local-ca.crt"),
                        "-CAkey", str(self.state / "local-ca.key"), "-CAcreateserial", "-extfile", str(extension),
                        "-out", str(self.state / "localhost.crt")], check=True, capture_output=True, timeout=10)

        stale = self.run_launcher("bootstrap", check=False)
        self.assertNotEqual(0, stale.returncode)
        self.assertIn("Authority Key Identifier", stale.stderr)
        self.assertIn("reset-certificates --confirm", stale.stderr)

        self.run_launcher("reset-certificates", "--confirm")
        self.run_launcher("bootstrap")

    def test_storage_port_is_configurable_and_must_differ_from_other_listeners(self):
        self.environment.update({
            "MNEMA_LOCAL_WEB_PORT": "4443", "MNEMA_LOCAL_IDENTITY_PORT": "4444", "MNEMA_LOCAL_STORAGE_PORT": "4445",
        })
        self.run_launcher("bootstrap")
        self.assertEqual("4445", self.runtime()["MNEMA_LOCAL_STORAGE_PORT"])

        self.state = Path(self.temp.name) / "second-state"
        self.environment.update({"MNEMA_LOCAL_STATE_DIR": str(self.state), "MNEMA_LOCAL_STORAGE_PORT": "4443"})
        result = self.run_launcher("bootstrap", check=False)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("ports must differ", result.stderr)
        self.assertFalse((self.state / "runtime.env").exists())

    def test_state_without_storage_is_upgraded_without_rotating_identity_material(self):
        self.run_launcher("bootstrap")
        retained = ("identity-signing-jwk-set.json", "local-ca.key", "localhost.key", "local-ca.crt")
        before = {name: digest(self.state / name) for name in retained}
        password = self.runtime()["MNEMA_LOCAL_POSTGRES_PASSWORD"]
        runtime = self.state / "runtime.env"
        runtime.write_text("".join(
            line + "\n" for line in runtime.read_text().splitlines()
            if not line.startswith(("MNEMA_LOCAL_S3_", "MNEMA_LOCAL_STORAGE_PORT"))
        ))
        (self.state / "storage.crt").unlink()
        (self.state / "storage.key").unlink()

        stale = self.run_launcher("stop", check=False)
        self.assertNotEqual(0, stale.returncode)
        self.assertIn("run '" + str(LAUNCHER) + " bootstrap'", stale.stderr)

        self.run_launcher("bootstrap")

        self.assertEqual(before, {name: digest(self.state / name) for name in retained})
        self.assertEqual(password, self.runtime()["MNEMA_LOCAL_POSTGRES_PASSWORD"])
        self.assertIn("MNEMA_LOCAL_S3_ACCESS_KEY", self.runtime())
        self.assertEqual(0o600, stat.S_IMODE(runtime.stat().st_mode))
        self.assertTrue((self.state / "storage.crt").is_file())

    def test_invalid_storage_credentials_fail_closed(self):
        self.run_launcher("bootstrap")
        runtime = self.state / "runtime.env"
        runtime.write_text(runtime.read_text().replace(self.runtime()["MNEMA_LOCAL_S3_ACCESS_KEY"], "short"))
        result = self.run_launcher("status", check=False)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("access key state is missing or invalid", result.stderr)

    def test_certificate_reset_reissues_storage_certificate_from_the_new_ca(self):
        self.run_launcher("bootstrap")
        self.fake_tools(docker="exit 0")
        runtime_before = digest(self.state / "runtime.env")
        old_storage = digest(self.state / "storage.crt")
        old_key = digest(self.state / "storage.key")
        self.run_launcher("reset-certificates", "--confirm")
        self.assertEqual(runtime_before, digest(self.state / "runtime.env"))
        self.assertNotEqual(old_storage, digest(self.state / "storage.crt"))
        self.assertNotEqual(old_key, digest(self.state / "storage.key"))
        subprocess.run([
            "openssl", "verify", "-CAfile", str(self.state / "local-ca.crt"), str(self.state / "storage.crt"),
        ], check=True, capture_output=True, timeout=10)

    def test_relative_state_directory_and_bad_project_name_are_rejected(self):
        for name, value in (("MNEMA_LOCAL_STATE_DIR", "relative/state"), ("MNEMA_LOCAL_PROJECT_NAME", "Bad Name")):
            self.environment[name] = value
            result = self.run_launcher("status", check=False)
            self.assertNotEqual(0, result.returncode, name)
            self.assertIn(name, result.stderr)
            self.environment["MNEMA_LOCAL_STATE_DIR"] = str(self.state)
            self.environment.pop("MNEMA_LOCAL_PROJECT_NAME", None)


class LauncherCommandsTest(LauncherTestCase):
    def test_start_builds_the_worker_image_then_the_stack_and_only_checks_readiness(self):
        self.run_launcher("bootstrap")
        tools = self.fake_tools(docker="exit 0", python3="exit 0", curl="exit 0")
        self.environment["MNEMA_LOCAL_PROJECT_NAME"] = "mnema-test-project"
        self.run_launcher("start")

        docker = self.calls(tools, "docker")
        # the runner starts the worker image per job through Docker, so the image is built before the stack comes up
        build = next(index for index, line in enumerate(docker) if "--profile worker-image build media-worker-image" in line)
        up = next(index for index, line in enumerate(docker) if " up --detach --build --remove-orphans --wait" in line)
        self.assertLess(build, up)       # --remove-orphans retires the Docker-socket media-processor of older stacks
        self.assertTrue(all("--project-name mnema-test-project" in line for line in docker if " compose " in f" {line}"
                            and "compose version" not in line))
        python = self.calls(tools, "python3")
        self.assertEqual(1, len(python))
        self.assertIn("smoke.py", python[0])
        self.assertIn("--readiness-only", python[0])

    def test_smoke_runs_media_smoke_after_the_account_smoke_with_the_storage_port(self):
        self.run_launcher("bootstrap")
        tools = self.fake_tools(python3="exit 0")
        self.run_launcher("smoke")
        python = self.calls(tools, "python3")
        self.assertEqual(2, len(python))
        self.assertIn("scripts/local-full-stack/smoke.py", python[0])
        self.assertIn("scripts/local-full-stack/media_smoke.py", python[1])
        self.assertIn("--storage-port 3445", python[1])
        self.assertIn(f"--state-file {self.state}/media-smoke.json", python[1])

    def test_failed_account_smoke_still_runs_media_and_the_command_fails(self):
        self.run_launcher("bootstrap")
        tools = self.fake_tools(python3='case "$*" in *smoke.py*) exit 1;; esac; exit 0')
        self.assertNotEqual(0, self.run_launcher("smoke", check=False).returncode)
        self.assertEqual(2, len(self.calls(tools, "python3")))

    def test_failed_media_smoke_fails_the_command_and_smoke_media_runs_alone(self):
        self.run_launcher("bootstrap")
        tools = self.fake_tools(python3='case "$*" in *media_smoke.py*) exit 1;; esac; exit 0')
        self.assertNotEqual(0, self.run_launcher("smoke", check=False).returncode)
        self.assertNotEqual(0, self.run_launcher("smoke-media", check=False).returncode)
        calls = self.calls(tools, "python3")
        self.assertEqual(3, len(calls))
        self.assertIn("media_smoke.py", calls[2])

    def test_permissive_media_smoke_credentials_fail_before_network(self):
        self.run_launcher("bootstrap")
        tools = self.fake_tools(python3="exit 0")
        state = self.state / "media-smoke.json"
        state.write_text("{}")
        state.chmod(0o644)
        result = self.run_launcher("smoke", check=False)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("must not be accessible", result.stderr)
        self.assertEqual([], self.calls(tools, "python3"))

    def test_reset_removes_only_project_volumes_and_smoke_state_and_keeps_identity_material(self):
        self.run_launcher("bootstrap")
        tools = self.fake_tools(docker="exit 0")
        self.environment["MNEMA_LOCAL_PROJECT_NAME"] = "mnema-test-project"
        for name in ("smoke-account.json", "media-smoke.json"):
            (self.state / name).write_text("{}")
        retained = {name: digest(self.state / name) for name in
                    ("runtime.env", "identity-signing-jwk-set.json", "local-ca.crt", "local-ca.key", "storage.key")}

        refused = self.run_launcher("reset", check=False)
        self.assertNotEqual(0, refused.returncode)
        self.assertIn("mnema-test-project", refused.stderr)

        self.run_launcher("reset", "--confirm-delete-local-data")

        self.assertIn("--project-name mnema-test-project", self.calls(tools, "docker")[-1])
        self.assertIn("down --volumes --remove-orphans", self.calls(tools, "docker")[-1])
        self.assertFalse((self.state / "smoke-account.json").exists())
        self.assertFalse((self.state / "media-smoke.json").exists())
        self.assertEqual(retained, {name: digest(self.state / name) for name in retained})


class ComposeContractTest(unittest.TestCase):
    def setUp(self):
        self.source = COMPOSE.read_text()

    def test_minio_is_the_official_digest_pinned_repository_image(self):
        images = {line.split()[-1] for line in self.source.splitlines() if line.startswith("x-minio-image:")}
        self.assertEqual(1, len(images))
        image = images.pop()
        self.assertRegex(image, r"^quay\.io/minio/minio@sha256:[0-9a-f]{64}$")
        staging = (ROOT / "k8s/staging/data.yaml").read_text()
        self.assertIn("minio/minio@" + image.split("@", 1)[1], staging)
        self.assertNotIn("l33tlamer", self.source)

    def test_only_the_dev_media_runner_holds_the_docker_socket_and_learning_and_job_containers_do_not(self):
        code = "\n".join(line for line in self.source.splitlines() if not line.strip().startswith("#"))
        self.assertEqual(1, code.count("/var/run/docker.sock:/var/run/docker.sock"))
        self.assertEqual(1, len([line for line in code.splitlines() if "docker.sock" in line]))
        self.assertIn("docker.sock", service_block(self.source, "media-runner"))
        for name in ("identity-account", "learning", "frontend", "minio", "postgres", "media-work-init"):
            self.assertNotIn("docker.sock", service_block(self.source, name), name)
        self.assertNotIn("media-processor", code)
        self.assertNotIn("learning-media-processor-runtime", code)
        self.assertIn("target: learning-runtime", service_block(self.source, "learning"))
        # the backend images carry no Docker client; only the dev-only runner image has one
        self.assertNotIn("docker-cli", RUNTIME_DOCKERFILE.read_text())
        runner_image = (ROOT / "deploy/local-full-stack/media-runner.Dockerfile").read_text()
        self.assertIn("DEVELOPMENT ONLY", runner_image)
        self.assertRegex(runner_image, r"FROM docker:[0-9.]+-cli@sha256:[0-9a-f]{64} AS docker-cli")
        self.assertIn("DEVELOPMENT-ONLY EXCEPTION", self.source)

    def test_there_is_no_long_lived_worker_service_and_learning_sees_only_its_spool(self):
        code = "\n".join(line for line in self.source.splitlines() if not line.strip().startswith("#"))
        self.assertNotIn("\n  media-worker:", code)
        learning = service_block(self.source, "learning")
        self.assertIn("subpath: spool", learning)
        self.assertIn("source: local_media_work", learning)
        self.assertNotIn("user:", learning)                      # no shared group any more
        init = service_block(self.source, "media-work-init")
        self.assertIn("mkdir -p /work/spool && chmod 700 /work/spool && chown 10001:10001 /work/spool && chmod 755 /work", init)
        for name in ("learning", "media-runner"):
            self.assertIn("media-work-init: {condition: service_completed_successfully}", service_block(self.source, name))
        self.assertIn("media-runner: {condition: service_healthy}", service_block(self.source, "frontend"))
        for name in ("identity-account", "frontend", "minio", "postgres"):
            self.assertNotIn("local_media_work", service_block(self.source, name), name)

    def test_the_dev_media_runner_is_unprivileged_beyond_what_it_needs_and_has_no_credentials(self):
        runner = service_block(self.source, "media-runner")
        for expected in ("cap_drop: [ALL]", "cap_add: [CHOWN, DAC_OVERRIDE, FOWNER, FSETID, KILL]", "no-new-privileges:true",
                         "read_only: true", "--volume=${MNEMA_LOCAL_MEDIA_VOLUME", "--image=${MNEMA_LOCAL_MEDIA_WORKER_IMAGE"):
            self.assertIn(expected, runner)
        self.assertNotIn("\n    environment:", runner)
        self.assertNotIn("\n    ports:", runner)
        self.assertNotIn("secrets:", runner)
        self.assertNotIn("privileged", runner)
        self.assertEqual(1, runner.count("local_media_work:"))

    def test_learning_alone_processes_media_and_has_no_published_port(self):
        self.assertNotIn("VERDICT_UID", self.source)     # the local runner is root too: Learning keeps the production rule
        self.assertEqual(1, self.source.count('LEARNING_MEDIA_PROCESSING_ENABLED: "true"'))
        self.assertIn("LEARNING_MEDIA_PROCESSING_WORK_ROOT: /var/lib/mnema-media", self.source)
        learning = service_block(self.source, "learning")
        self.assertNotIn("\n    ports:", learning)
        self.assertIn("cap_drop: [ALL]", self.source.split("x-hardened-runtime:", 1)[1].split("\n\n", 1)[0])

    def test_published_ports_are_loopback_only(self):
        ports = [line.strip() for line in self.source.splitlines() if line.strip().startswith('- "127.0.0.1:')]
        self.assertEqual(3, len(ports), ports)
        self.assertFalse([line for line in self.source.splitlines() if line.strip().startswith('- "0.0.0.0')])
        # MinIO publishes one listener and the frontend proxy two; nothing else publishes.
        self.assertEqual({"minio", "frontend"}, {
            name for name in ("postgres", "minio", "minio-init", "identity-account", "learning", "media-work-init",
                              "media-runner", "media-worker-image", "frontend")
            if "\n    ports:" in service_block(self.source, name)})

    def test_buckets_are_created_idempotently_before_the_backends_start(self):
        init = service_block(self.source, "minio-init")
        self.assertIn("mc mb --ignore-existing local/mnema-local-avatars", init)
        self.assertIn("mc mb --ignore-existing local/mnema-local-media", init)
        for name in ("identity-account", "learning"):
            self.assertIn("minio-init: {condition: service_completed_successfully}", service_block(self.source, name))

    def test_secrets_are_not_literal_in_the_compose_file(self):
        for line in self.source.splitlines():
            if any(key in line for key in ("SECRET_KEY", "ACCESS_KEY", "PASSWORD")) and ":" in line:
                self.assertTrue("${" in line or line.strip().startswith("#"), line)

    @unittest.skipUnless(shutil.which("docker"), "docker is required to render the compose file")
    def test_rendered_configuration_matches_the_launcher_contract(self):
        with tempfile.TemporaryDirectory(prefix="mnema-local-media-render-") as directory:
            state = Path(directory) / "state"
            environment = {**os.environ, "MNEMA_LOCAL_STATE_DIR": str(state)}
            subprocess.run([str(LAUNCHER), "bootstrap"], cwd=ROOT, env=environment, check=True,
                           capture_output=True, timeout=60)
            runtime = dict(line.split("=", 1) for line in (state / "runtime.env").read_text().splitlines())
            environment.update(runtime)
            environment.update({
                "COMPOSE_DISABLE_ENV_FILE": "true", "MNEMA_LOCAL_BUILD_ID": "test",
                "MNEMA_LOCAL_IDENTITY_SIGNING_JWK_SET_FILE": str(state / "identity-signing-jwk-set.json"),
                "MNEMA_LOCAL_TLS_CERT_FILE": str(state / "localhost.crt"),
                "MNEMA_LOCAL_TLS_KEY_FILE": str(state / "localhost.key"),
                "MNEMA_LOCAL_TRUSTSTORE_FILE": str(state / "learning-truststore.p12"),
                "MNEMA_LOCAL_STORAGE_TLS_CERT_FILE": str(state / "storage.crt"),
                "MNEMA_LOCAL_STORAGE_TLS_KEY_FILE": str(state / "storage.key"),
                "MNEMA_LOCAL_CA_CERT_FILE": str(state / "local-ca.crt"),
                "MNEMA_LOCAL_MEDIA_VOLUME": "mnema-render-test_local_media_work",
            })
            rendered = json.loads(subprocess.run(
                ["docker", "compose", "--file", str(COMPOSE), "--profile", "worker-image", "config", "--format", "json"],
                cwd=ROOT, env=environment, check=True, capture_output=True, text=True, timeout=30,
            ).stdout)
        services = rendered["services"]
        self.assertEqual({
            "postgres", "minio", "minio-init", "identity-account", "learning", "media-work-init", "media-runner",
            "media-worker-image", "frontend",
        }, set(services))
        endpoint = "https://storage.mnema.localhost:3445"
        self.assertEqual(endpoint, services["learning"]["environment"]["LEARNING_MEDIA_UPLOAD_ENDPOINT"])
        self.assertEqual(endpoint, services["identity-account"]["environment"]["MNEMA_AVATAR_ENDPOINT"])
        learning = services["learning"]["environment"]
        self.assertEqual("true", learning["LEARNING_MEDIA_PROCESSING_ENABLED"])
        self.assertEqual("/var/lib/mnema-media", learning["LEARNING_MEDIA_PROCESSING_WORK_ROOT"])
        self.assertIn("storage.mnema.localhost", services["minio"]["networks"]["default"]["aliases"])
        runner = services["media-runner"]
        self.assertEqual("mnema-render-test_local_media_work", runner["command"][-1].split("=", 1)[1])
        self.assertEqual("--image=mnema-media-worker:local", runner["command"][-2])
        self.assertFalse(runner.get("environment") or runner.get("secrets") or runner.get("ports"))
        self.assertEqual(["/var/lib/mnema/media-work", "/var/run/docker.sock"], sorted(v["target"] for v in runner["volumes"]))
        spool = [v for v in services["learning"]["volumes"] if v["target"] == "/var/lib/mnema-media"]
        self.assertEqual([("volume", "local_media_work", "spool")], [(v["type"], v["source"], v["volume"]["subpath"]) for v in spool])
        self.assertNotIn("/var/run/docker.sock", json.dumps(services["learning"]))
        self.assertEqual(
            {"identity-account", "learning", "frontend"},
            {name for name, service in services.items() if "JAVA_TOOL_OPTIONS" in service.get("environment", {})
             or name == "frontend"})


class MediaSmokeTest(unittest.TestCase):
    def setUp(self):
        self.module = load_media_smoke()
        self.temp = tempfile.TemporaryDirectory(prefix="mnema-media-smoke-test-")
        self.addCleanup(self.temp.cleanup)
        self.state = Path(self.temp.name) / "media-smoke.json"

    def test_png_fixture_is_a_valid_png_with_unique_content(self):
        data = self.module.png_fixture()
        self.assertEqual(b"\x89PNG\r\n\x1a\n", data[:8])
        offset, kinds = 8, []
        while offset < len(data):
            length, kind = struct.unpack(">I4s", data[offset:offset + 8])
            body = data[offset + 8:offset + 8 + length]
            crc = struct.unpack(">I", data[offset + 8 + length:offset + 12 + length])[0]
            self.assertEqual(zlib.crc32(kind + body), crc)
            kinds.append(kind)
            offset += 12 + length
        self.assertEqual([b"IHDR", b"tEXt", b"IDAT", b"IEND"], kinds)
        self.assertNotEqual(data, self.module.png_fixture())
        self.assertLess(len(data), 4096)

    def test_mp3_fixture_is_whole_silent_frames(self):
        data = self.module.mp3_fixture()
        self.assertEqual(0, len(data) % 417)
        self.assertTrue(all(data[offset:offset + 2] == b"\xff\xfb" for offset in range(0, len(data), 417)))
        self.assertLess(len(data), 32 * 1024)

    def test_account_state_is_private_validated_and_reused(self):
        account, fresh = self.module.load_or_create_account(self.state)
        self.assertTrue(fresh)
        self.module.persist_account(self.state, account)
        self.assertEqual(0o600, stat.S_IMODE(self.state.stat().st_mode))
        reused, fresh = self.module.load_or_create_account(self.state)
        self.assertFalse(fresh)
        self.assertEqual(account, reused)
        self.state.write_text(json.dumps({**account, "extra": "value"}))
        with self.assertRaisesRegex(AssertionError, "invalid media smoke account state"):
            self.module.load_or_create_account(self.state)

    def test_signed_urls_must_target_the_local_storage_origin(self):
        for url in (
            "http://storage.mnema.localhost:3445/b/k", "https://evil.example:3445/b/k",
            "https://storage.mnema.localhost:9999/b/k",
        ):
            with self.assertRaisesRegex(AssertionError, "local object-storage origin"):
                self.module.storage_request("PUT", url, 3445, None, b"x")

    def test_upload_waits_for_ready_and_checks_the_processed_variant(self):
        module = self.module
        stored = {}

        class Web:
            polls = 0

            def request(self, method, path, payload=None, bearer=None):
                if path.endswith("/upload-intents"):
                    return 201, {}, {"assetId": "asset", "generation": 1, "method": "SINGLE",
                                    "url": "https://storage.mnema.localhost:3445/put", "headers": {"x": "1"}}
                if path.endswith("/finalize"):
                    return 200, {}, {"assetState": "VERIFYING"}
                if path.endswith("/upload"):
                    Web.polls += 1
                    return 200, {}, {"assetState": "READY" if Web.polls > 1 else "PROCESSING"}
                return 200, {}, {"state": "READY", "playback": {"url": "https://storage.mnema.localhost:3445/get",
                                                                 "mimeType": "image/webp"},
                                 "download": {"url": "https://storage.mnema.localhost:3445/original"}}

        def storage(method, url, port, context, body=None, headers=None):
            if method == "PUT":
                stored["put"] = (body, headers)
                return 200, b""
            return 200, (b"RIFF1234WEBPdata" if url.endswith("/get") else stored["put"][0])

        module.storage_request = storage
        module.time.sleep = lambda seconds: None
        result = module.upload_and_process(Web(), "token", 3445, None, "image", "image/png", b"png",
                                           lambda data: data[:4] == b"RIFF", 30)
        self.assertEqual({"kind": "image", "playbackMime": "image/webp", "playbackBytes": 16}, result)
        self.assertEqual({"x": "1"}, stored["put"][1])
        self.assertEqual(2, Web.polls)

    def test_terminal_processing_states_fail_with_the_state(self):
        module = self.module

        class Web:
            def request(self, method, path, payload=None, bearer=None):
                if path.endswith("/upload-intents"):
                    return 201, {}, {"assetId": "asset", "generation": 1, "method": "SINGLE",
                                    "url": "https://storage.mnema.localhost:3445/put", "headers": {}}
                return 200, {}, {"assetState": "REJECTED"}

        module.storage_request = lambda *arguments, **keywords: (200, b"")
        with self.assertRaisesRegex(AssertionError, "audio processing ended as REJECTED"):
            module.upload_and_process(Web(), "token", 3445, None, "audio", "audio/mpeg", b"x", None, 30)


if __name__ == "__main__":
    unittest.main()
