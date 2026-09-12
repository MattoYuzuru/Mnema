import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from frontend_release_assets import hashed_assets, verify_zone_build_config


class FrontendReleaseAssetsTest(unittest.TestCase):
    HTML = '<script src="/app-config.js"></script><script src="main-KPRDJ7K5.js" type="module"></script>' \
           '<link rel="stylesheet" href="styles-VT65WWZU.css"><link rel="modulepreload" href="chunk-CCXBkPB_.js">'

    def test_application_builder_without_separate_runtime(self):
        self.assertEqual(hashed_assets(self.HTML), ["main-KPRDJ7K5.js", "styles-VT65WWZU.css", "chunk-CCXBkPB_.js"])

    def test_external_font_stylesheet_is_not_an_immutable_local_asset(self):
        self.assertEqual(hashed_assets(self.HTML + '<link rel="stylesheet" href="https://fonts.example.test/css">'),
                         hashed_assets(self.HTML))

    def test_unhashed_short_hash_old_builder_and_missing_entry_fail(self):
        for value in ("main.js", "main-abc.js", "main.0123456789abcdef.js", "other-KPRDJ7K5.js"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                hashed_assets(self.HTML.replace("main-KPRDJ7K5.js", value))

    def test_script_and_preload_must_not_escape_build_root_or_use_remote_urls(self):
        for value in ("../chunk-CCXBkPB_.js", "/chunk-CCXBkPB_.js", "https://example.test/chunk-CCXBkPB_.js",
                      "chunk-CCXBkPB_.js?mutable=1", "chunk.js"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                hashed_assets(self.HTML.replace("chunk-CCXBkPB_.js", value))

    def test_additional_unhashed_script_is_rejected_even_with_valid_main(self):
        with self.assertRaises(ValueError):
            hashed_assets(self.HTML + '<script src="other.js"></script>')

    def test_comments_cannot_forge_a_required_entry(self):
        with self.assertRaises(ValueError):
            hashed_assets('<!-- ' + self.HTML + ' -->')

    def test_duplicate_entry_or_missing_url_is_rejected(self):
        for added in ('<script src="main-KPRDJ7K5.js"></script>', '<script src></script>',
                      '<link rel="modulepreload" href>'):
            with self.subTest(added=added), self.assertRaises(ValueError):
                hashed_assets(self.HTML + added)

    def test_imperative_zone_import_cannot_hide_zone_based_build_from_the_builder(self):
        workspace = {"projects": {"frontend": {"architect": {"build": {
            "builder": "@angular/build:application", "options": {"polyfills": []}
        }}}}}
        bootstrap = "import 'zone.js'; bootstrapApplication(App, {providers:[provideZoneChangeDetection()]});"
        with self.assertRaises(ValueError):
            verify_zone_build_config(workspace, bootstrap)
        workspace["projects"]["frontend"]["architect"]["build"]["options"]["polyfills"] = ["zone.js"]
        verify_zone_build_config(workspace, bootstrap)

    def test_future_zoneless_bootstrap_does_not_require_zone(self):
        self.assertFalse(verify_zone_build_config({"projects": {}}, "bootstrapApplication(App);"))

    def test_zone_build_requires_exactly_one_emitted_polyfills_entry(self):
        polyfills = '<script src="polyfills-LVNOU2XZ.js" type="module"></script>'
        for html in (self.HTML, polyfills + polyfills + self.HTML, self.HTML + polyfills):
            with self.subTest(html=html), self.assertRaises(ValueError):
                hashed_assets(html, require_zone_polyfills=True)
        self.assertIn('polyfills-LVNOU2XZ.js', hashed_assets(polyfills + self.HTML, require_zone_polyfills=True))


if __name__ == "__main__":
    unittest.main()
