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
            Path(".github/dependabot.yml"),
            Path("docs/operations/production-image-inventory.md"),
        ):
            target = self.repository / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(REPOSITORY_ROOT / relative, target)

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

    def test_vps_mutable_database_and_undeclared_application_binding_are_rejected(self):
        path = self.repository / 'deploy/production/compose.yaml'
        content = path.read_text()
        content = content.replace('${MNEMA_POSTGRES_IMAGE:?verified-candidate-required}', 'postgres:latest')
        content = content.replace('${MNEMA_FRONTEND_IMAGE:?verified-candidate-required}', 'example/frontend:latest')
        path.write_text(content)
        messages = [finding.message for finding in self.findings()]
        self.assertTrue(any('four admitted image bindings' in message for message in messages))

    def append_compose(self, text: str):
        path = self.repository / "deploy/production/compose.yaml"
        path.write_text(path.read_text(encoding="utf-8").rstrip("\n") + "\n" + text, encoding="utf-8")

    def test_compose_flow_mapping_image_cannot_bypass_policy(self):
        self.append_compose("  sidecar: {image: example/sidecar:latest}\n")
        self.assertTrue(any("one-line YAML scalar" in finding.message for finding in self.findings()))

    def test_compose_extra_mutable_or_undigested_image_is_rejected(self):
        for image in ("example/sidecar:latest", "example/sidecar:1.0", "example/sidecar"):
            with self.subTest(image=image):
                self.tearDown()
                self.setUp()
                self.append_compose(f"  sidecar:\n    image: {image}\n")
                self.assertTrue(any("four admitted image bindings" in finding.message for finding in self.findings()))

    def test_vps_database_base_cannot_use_a_floating_tag(self):
        self.replace('deploy/production/Dockerfile',
                     'postgres:18.6-alpine3.24@sha256:77f585114c32fbca283dc835b0596f4e52b51b4c6662d7810b2f4084f60a1873',
                     'postgres:18.6-alpine3.24')
        self.assertTrue(any('Dockerfile FROM' in finding.message for finding in self.findings()))

    def test_missing_dependabot_production_directory_is_rejected(self):
        self.replace(".github/dependabot.yml", '      - "/deploy/production"\n', "")
        self.assertTrue(any("Docker coverage" in finding.message for finding in self.findings()))

    def test_stale_inventory_is_rejected(self):
        self.replace(
            "docs/operations/production-image-inventory.md",
            "`sha256:77f585114c32fbca283dc835b0596f4e52b51b4c6662d7810b2f4084f60a1873`",
            "`sha256:" + "f" * 64 + "`",
        )
        self.assertTrue(any("inventory is missing" in finding.message for finding in self.findings()))


if __name__ == "__main__":
    unittest.main()
