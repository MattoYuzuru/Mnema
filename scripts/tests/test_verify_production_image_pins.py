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
            Path("k8s/postgres.yaml"),
            Path("k8s/redis.yaml"),
            Path("k8s/frontend-deploy.yaml"),
            Path("k8s/auth-deploy.yaml"),
            Path("k8s/user-deploy.yaml"),
            Path("k8s/core-deploy.yaml"),
            Path("k8s/media-deploy.yaml"),
            Path("k8s/import-deploy.yaml"),
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
            "gradle:8.10.2-jdk21@sha256:963d59f7f22767da4efbcf46b661361b61af5fb88b0309da1071c4234c647eba",
            "gradle:8.10.2-jdk21",
        )
        self.assertTrue(any("Dockerfile FROM" in finding.message for finding in self.findings()))

    def test_platform_qualified_tag_only_build_image_is_rejected(self):
        self.replace(
            "backend/Dockerfile",
            "FROM gradle:8.10.2-jdk21@sha256:963d59f7f22767da4efbcf46b661361b61af5fb88b0309da1071c4234c647eba AS build",
            "FROM --platform=linux/amd64 gradle:8.10.2-jdk21 AS build",
        )
        self.assertTrue(any("Dockerfile FROM" in finding.message for finding in self.findings()))

    def test_platform_qualified_pinned_build_image_is_accepted(self):
        self.replace(
            "backend/Dockerfile",
            "FROM gradle:8.10.2-jdk21@sha256:963d59f7f22767da4efbcf46b661361b61af5fb88b0309da1071c4234c647eba AS build",
            "FROM --platform=linux/amd64 "
            "gradle:8.10.2-jdk21@sha256:963d59f7f22767da4efbcf46b661361b61af5fb88b0309da1071c4234c647eba "
            "AS build",
        )
        self.assertEqual([], self.findings())

    def test_previously_declared_internal_stage_is_accepted(self):
        self.replace(
            "backend/Dockerfile",
            "FROM backend-runtime AS auth-runtime",
            "FROM backend-runtime AS auth-runtime-copy",
        )
        self.assertEqual([], self.findings())

    def test_undefined_internal_stage_is_rejected(self):
        self.replace(
            "backend/Dockerfile",
            "FROM backend-runtime AS auth-runtime",
            "FROM unknown-runtime AS auth-runtime",
        )
        self.assertTrue(any("unknown-runtime" in finding.message for finding in self.findings()))

    def test_duplicate_internal_stage_alias_is_rejected(self):
        self.replace(
            "backend/Dockerfile",
            "FROM backend-runtime AS auth-runtime",
            "FROM backend-runtime AS backend-runtime",
        )
        self.assertTrue(any("alias is duplicated" in finding.message for finding in self.findings()))

    def test_tag_only_frontend_runtime_image_is_rejected(self):
        self.replace(
            "frontend/Dockerfile",
            "nginx:1.31.4-alpine@sha256:db35bfc6b2951e7f8a72db5db120288c127ffaeeb4a6d4b95a26fead017d5913",
            "nginx:1.31.4-alpine",
        )
        self.assertTrue(any("Dockerfile FROM" in finding.message for finding in self.findings()))

    def test_tag_only_production_database_image_is_rejected(self):
        self.replace(
            "k8s/postgres.yaml",
            "postgres:16.15-alpine3.24@sha256:cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685",
            "postgres:16.15-alpine3.24",
        )
        self.assertTrue(any("production image" in finding.message for finding in self.findings()))

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
