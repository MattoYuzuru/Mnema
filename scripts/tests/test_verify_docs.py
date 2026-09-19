from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from scripts.verify_docs import validate


FRONT_MATTER = """---
artifact:
  status: current
---
"""


class VerifyDocsTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        for relative in (
            Path("docs/README.md"),
            Path("docs/system-overview.md"),
            Path("docs/engineering/repository-guide.md"),
        ):
            path = self.root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(FRONT_MATTER + "\n# Canonical\n", encoding="utf-8")

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def test_accepts_existing_files_anchors_and_external_links(self) -> None:
        target = self.root / "docs/target.md"
        target.write_text("# Принятый контракт\n\n<h2 id=\"explicit\">Named</h2>\n", encoding="utf-8")
        source = self.root / "README.md"
        source.write_text(
            "[contract](docs/target.md#принятый-контракт) "
            "[named](docs/target.md#explicit) [web](https://example.com)\n",
            encoding="utf-8",
        )

        self.assertEqual([], validate(self.root))

    def test_reports_missing_file_anchor_and_wrong_canonical_status(self) -> None:
        (self.root / "docs/README.md").write_text(
            FRONT_MATTER.replace("current", "proposed") + "\n# Docs\n[missing](absent.md)\n",
            encoding="utf-8",
        )
        (self.root / "README.md").write_text(
            "[bad anchor](docs/system-overview.md#absent)\n", encoding="utf-8"
        )

        errors = validate(self.root)

        self.assertTrue(any("expected status 'current'" in error for error in errors))
        self.assertTrue(any("missing target absent.md" in error for error in errors))
        self.assertTrue(any("missing anchor" in error for error in errors))

    def test_rejects_unsupported_declared_status(self) -> None:
        (self.root / "docs/other.md").write_text(
            FRONT_MATTER.replace("current", "reviewed-handoff") + "\n# Other\n",
            encoding="utf-8",
        )

        errors = validate(self.root)

        self.assertTrue(any("unsupported documentation status" in error for error in errors))

    def test_accepts_reference_image_encoded_and_titled_links(self) -> None:
        assets = self.root / "docs/assets"
        assets.mkdir()
        (assets / "result image.png").write_bytes(b"png")
        (self.root / "docs/reference.md").write_text("# Result\n", encoding="utf-8")
        (self.root / "docs/file(name).md").write_text("# Nested\n", encoding="utf-8")
        (self.root / "README.md").write_text(
            "![result](docs/assets/result%20image.png \"evidence\")\n"
            "[reference][contract] [contract][] [contract] "
            "[nested](docs/file(name).md)\n\n"
            "[contract]: docs/reference.md#result\n",
            encoding="utf-8",
        )

        self.assertEqual([], validate(self.root))

    def test_ignores_code_fences_and_generated_directories(self) -> None:
        (self.root / "README.md").write_text(
            "```markdown\n[example](missing.md)\n```\n\n"
            "`[inline](also-missing.md)`\n\n"
            "    [indented](still-missing.md)\n",
            encoding="utf-8",
        )
        generated = self.root / "node_modules/package/README.md"
        generated.parent.mkdir(parents=True)
        generated.write_text("[missing](absent.md)\n", encoding="utf-8")

        self.assertEqual([], validate(self.root))

    def test_reports_missing_full_reference_definition(self) -> None:
        (self.root / "README.md").write_text(
            "[broken][missing-definition]\n", encoding="utf-8"
        )

        errors = validate(self.root)

        self.assertTrue(any("missing reference definition" in error for error in errors))

    def test_resolves_collision_safe_heading_suffixes(self) -> None:
        (self.root / "docs/headings.md").write_text(
            "# A\n\n# A-1\n\n# A\n", encoding="utf-8"
        )
        (self.root / "README.md").write_text(
            "[third duplicate](docs/headings.md#a-2)\n", encoding="utf-8"
        )

        self.assertEqual([], validate(self.root))


if __name__ == "__main__":
    unittest.main()
