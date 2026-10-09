from __future__ import annotations

import shutil
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPTS_DIR = Path(__file__).resolve().parents[1]
REPOSITORY_ROOT = SCRIPTS_DIR.parent
sys.path.insert(0, str(SCRIPTS_DIR))

from verify_production_image_pins import validate_repository  # noqa: E402


class VerifyProductionImagePinsTest(unittest.TestCase):
    def setUp(self):
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.repository = Path(self.temporary_directory.name)
        for relative in (
            Path("backend/Dockerfile"),
            Path("frontend/Dockerfile"),
            Path("deploy/production/compose.yaml"),
            Path("deploy/production/Dockerfile"),
            Path("deploy/production/local-backup.py"),
            Path("backend/media-worker/Dockerfile"),
            Path("k8s/postgres.yaml"),
            Path("k8s/redis.yaml"),
            Path("k8s/identity-account-deploy.yaml"),
            Path("k8s/learning-deploy.yaml"),
            Path(".github/workflows/production-deploy.yaml"),
            Path(".github/dependabot.yml"),
            Path("scripts/render-release-manifest.sh"),
            Path("docs/operations/production-image-inventory.md"),
        ):
            target = self.repository / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(REPOSITORY_ROOT / relative, target)
        shutil.copytree(
            REPOSITORY_ROOT / "k8s/observability",
            self.repository / "k8s/observability",
        )

    def tearDown(self):
        self.temporary_directory.cleanup()

    def findings(self):
        return validate_repository(self.repository)

    def replace(self, relative: str, old: str, new: str):
        path = self.repository / relative
        content = path.read_text(encoding="utf-8")
        self.assertIn(old, content)
        path.write_text(content.replace(old, new, 1), encoding="utf-8")

    def test_repository_policy_is_valid(self):
        self.assertEqual([], validate_repository(REPOSITORY_ROOT))

    def test_tag_only_backend_build_image_is_rejected(self):
        self.replace(
            "backend/Dockerfile",
            "gradle:9.8.0-jdk25@sha256:7086a4cd10d568b35cafd6d5d30323865f3ce1f23ccddc9570ff3e3c9c8cd6e9",
            "gradle:9.8.0-jdk25",
        )
        self.assertTrue(any("Dockerfile FROM" in finding.message for finding in self.findings()))

    def test_platform_qualified_tag_only_build_image_is_rejected(self):
        self.replace(
            "backend/Dockerfile",
            "FROM gradle:9.8.0-jdk25@sha256:7086a4cd10d568b35cafd6d5d30323865f3ce1f23ccddc9570ff3e3c9c8cd6e9 AS build",
            "FROM --platform=linux/amd64 gradle:9.8.0-jdk25 AS build",
        )
        self.assertTrue(any("Dockerfile FROM" in finding.message for finding in self.findings()))

    def test_platform_qualified_pinned_build_image_is_accepted(self):
        self.replace(
            "backend/Dockerfile",
            "FROM gradle:9.8.0-jdk25@sha256:7086a4cd10d568b35cafd6d5d30323865f3ce1f23ccddc9570ff3e3c9c8cd6e9 AS build",
            "FROM --platform=linux/amd64 "
            "gradle:9.8.0-jdk25@sha256:7086a4cd10d568b35cafd6d5d30323865f3ce1f23ccddc9570ff3e3c9c8cd6e9 "
            "AS build",
        )
        self.assertEqual([], self.findings())

    def test_previously_declared_internal_stage_is_accepted(self):
        self.replace(
            "backend/Dockerfile",
            "FROM backend-runtime AS identity-account-runtime",
            "FROM backend-runtime AS identity-account-runtime-copy",
        )
        self.assertEqual([], self.findings())

    def test_undefined_internal_stage_is_rejected(self):
        self.replace(
            "backend/Dockerfile",
            "FROM backend-runtime AS identity-account-runtime",
            "FROM unknown-runtime AS identity-account-runtime",
        )
        self.assertTrue(any("unknown-runtime" in finding.message for finding in self.findings()))

    def test_duplicate_internal_stage_alias_is_rejected(self):
        self.replace(
            "backend/Dockerfile",
            "FROM backend-runtime AS identity-account-runtime",
            "FROM backend-runtime AS backend-runtime",
        )
        self.assertTrue(any("alias is duplicated" in finding.message for finding in self.findings()))

    def test_tag_only_frontend_runtime_image_is_rejected(self):
        self.replace(
            "frontend/Dockerfile",
            "nginx:1.31.6-alpine@sha256:df221db836e1754089190208cee7eeda94f233197056426eda74a43ab1abeac2",
            "nginx:1.31.6-alpine",
        )
        self.assertTrue(any("Dockerfile FROM" in finding.message for finding in self.findings()))

    def test_tag_only_production_database_image_is_rejected(self):
        self.replace(
            "k8s/postgres.yaml",
            "postgres:16.15-alpine3.24@sha256:cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685",
            "postgres:16.15-alpine3.24",
        )
        self.assertTrue(any("production image" in finding.message for finding in self.findings()))

    def test_vps_mutable_database_and_undeclared_application_binding_are_rejected(self):
        path = self.repository / 'deploy/production/compose.yaml'
        content = path.read_text()
        content = content.replace('${MNEMA_POSTGRES_IMAGE:?verified-candidate-required}', 'postgres:latest')
        content = content.replace('${MNEMA_FRONTEND_IMAGE:?verified-candidate-required}', 'example/frontend:latest')
        path.write_text(content)
        messages = [finding.message for finding in self.findings()]
        self.assertTrue(any('four admitted image bindings' in message for message in messages))

    def test_media_worker_base_must_be_pinned_and_the_image_non_root_with_pinned_ffmpeg(self):
        dockerfile = 'backend/media-worker/Dockerfile'
        self.replace(dockerfile, 'ubuntu:24.04@sha256:008173c23f95b170204355c12626cb5a965d779a7e1283b09e9cffbb1bf33ca3',
                     'ubuntu:24.04')
        self.assertTrue(any('media worker base image' in finding.message for finding in self.findings()))
        self.setUp()
        self.replace(dockerfile, 'USER 10002:10002', 'USER root')
        self.assertTrue(any('UID 10002' in finding.message for finding in self.findings()))
        self.setUp()
        self.replace(dockerfile, 'COPY mnema_media_worker ./mnema_media_worker', 'COPY mnema_media_worker ./mnema_media_worker\nCOPY tests ./tests')
        self.assertTrue(any('must not contain the tests' in finding.message for finding in self.findings()))
        self.setUp()
        self.replace(dockerfile, 'libpython3.12-stdlib=${PYTHON312_VERSION}', 'libpython3.12-stdlib')
        self.assertTrue(any('Python interpreter and standard library' in finding.message for finding in self.findings()))
        self.setUp()
        self.replace(dockerfile, 'ffmpeg=${FFMPEG_VERSION}', 'ffmpeg')
        self.assertTrue(any('FFmpeg packages must be pinned' in finding.message for finding in self.findings()))
        self.setUp()
        self.replace(dockerfile, 'org.opencontainers.image.source="https://github.com/MattoYuzuru/Mnema"', 'x="y"')
        self.assertTrue(any('OCI source label' in finding.message for finding in self.findings()))
        self.setUp()
        self.replace(dockerfile, 'FROM ${UBUNTU_BASE}', 'FROM ubuntu:24.04')
        self.assertTrue(any('single FROM' in finding.message for finding in self.findings()))

    def test_the_media_worker_cannot_come_back_as_a_long_lived_compose_service(self):
        path = self.repository / 'deploy/production/compose.yaml'
        path.write_text(path.read_text().replace('\n  frontend:\n', '\n  media-worker:\n    image: ${MNEMA_MEDIA_WORKER_IMAGE:?verified-candidate-required}\n\n  frontend:\n', 1))
        messages = [finding.message for finding in self.findings()]
        self.assertTrue(any('must not be a Compose service' in message for message in messages))

    def test_vps_database_base_cannot_use_a_floating_tag(self):
        self.replace('deploy/production/Dockerfile',
                     'postgres:18.6-alpine3.24@sha256:77f585114c32fbca283dc835b0596f4e52b51b4c6662d7810b2f4084f60a1873',
                     'postgres:18.6-alpine3.24')
        self.assertTrue(any('Dockerfile FROM' in finding.message for finding in self.findings()))

    def test_new_mutable_observability_image_is_rejected(self):
        path = self.repository / "k8s/observability/99-new-component.yaml"
        path.write_text("spec:\n  containers:\n    - image: example/component:1.0\n", encoding="utf-8")
        self.assertTrue(any(path == finding.path for finding in self.findings()))

    def test_noncanonical_image_mapping_cannot_bypass_policy(self):
        self.replace(
            "k8s/redis.yaml",
            "          image: redis:7.4.11-alpine@sha256:ff02b58f971e7d7d156a1267e283fcbbeee91773b6aa36c49dac28ecfe28eadf",
            "          \"image\" : redis:7.4.11-alpine",
        )
        self.assertTrue(any("production image" in finding.message for finding in self.findings()))

    def test_unclassified_production_apply_path_is_rejected(self):
        self.replace(
            ".github/workflows/production-deploy.yaml",
            "          kubectl apply -f k8s/observability/\n",
            "          kubectl apply -f k8s/observability/\n          kubectl apply -f k8s/ai/\n",
        )
        self.assertTrue(any("apply surface" in finding.message for finding in self.findings()))

    def test_long_form_production_apply_cannot_bypass_policy(self):
        self.replace(
            ".github/workflows/production-deploy.yaml",
            "          kubectl apply -f k8s/observability/\n",
            "          kubectl apply -f k8s/observability/\n"
            "          kubectl apply --filename k8s/ai/\n",
        )
        self.assertTrue(any("classified -f target" in finding.message for finding in self.findings()))

    def test_duplicate_stdin_apply_cannot_hide_excluded_manifest(self):
        self.replace(
            ".github/workflows/production-deploy.yaml",
            "          kubectl apply -f k8s/observability/\n",
            "          kubectl apply -f k8s/observability/\n"
            "          cat k8s/ai/ai-deploy.yaml | kubectl apply -f -\n",
        )
        self.assertTrue(any("apply surface" in finding.message for finding in self.findings()))

    def test_nonproduction_manifest_is_outside_the_policy_surface(self):
        path = self.repository / "k8s/ai/ai-deploy.yaml"
        path.parent.mkdir(parents=True)
        path.write_text("spec:\n  containers:\n    - image: local-ai:latest\n", encoding="utf-8")
        self.assertEqual([], self.findings())

    def test_missing_dependabot_production_directory_is_rejected(self):
        self.replace(".github/dependabot.yml", '      - "/k8s/observability"\n', "")
        self.assertTrue(any("Docker coverage" in finding.message for finding in self.findings()))

    def test_stale_inventory_is_rejected(self):
        self.replace(
            "docs/operations/production-image-inventory.md",
            "`sha256:ff02b58f971e7d7d156a1267e283fcbbeee91773b6aa36c49dac28ecfe28eadf`",
            "`sha256:" + "f" * 64 + "`",
        )
        self.assertTrue(any("inventory is missing" in finding.message for finding in self.findings()))


if __name__ == "__main__":
    unittest.main()
