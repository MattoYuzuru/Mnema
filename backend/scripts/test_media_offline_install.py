import hashlib
from pathlib import Path
import tempfile
import unittest
from uuid import uuid4

from media_offline_install import install


class OfflineInstallTest(unittest.TestCase):
    def test_retry_and_corruption_never_replace_last_verified_snapshot(self):
        with tempfile.TemporaryDirectory() as root:
            downloads = Path(root) / "downloads"
            library = Path(root) / "library"
            downloads.mkdir()
            blob = str(uuid4())
            content = b"verified media"
            (downloads / blob).write_bytes(content)
            manifest = self._manifest(blob, content)

            first = install(manifest, downloads, library)
            self.assertEqual(first, install(manifest, downloads, library))
            self.assertEqual(manifest["manifestId"], (library / "current").read_text())
            self.assertEqual(1, len(list(library.glob("*/manifest.json"))))

            next_manifest = self._manifest(blob, content)
            (downloads / blob).write_bytes(b"corrupt")
            with self.assertRaisesRegex(ValueError, "corrupt download"):
                install(next_manifest, downloads, library)
            self.assertEqual(manifest["manifestId"], (library / "current").read_text())
            self.assertFalse((library / next_manifest["manifestId"]).exists())

            (downloads / blob).write_bytes(content)
            install(next_manifest, downloads, library)
            self.assertEqual(next_manifest["manifestId"], (library / "current").read_text())
            self.assertEqual(2, len(list(library.glob("*/manifest.json"))))

    def test_missing_and_foreign_blob_identity_fail_closed(self):
        with tempfile.TemporaryDirectory() as root:
            downloads = Path(root) / "downloads"
            library = Path(root) / "library"
            downloads.mkdir()
            blob = str(uuid4())
            manifest = self._manifest(blob, b"expected")
            with self.assertRaisesRegex(ValueError, "missing download"):
                install(manifest, downloads, library)
            manifest["assets"][0]["variants"][0]["blobId"] = "../../foreign"
            with self.assertRaises(ValueError):
                install(manifest, downloads, library)
            self.assertFalse((library / "current").exists())

    @staticmethod
    def _manifest(blob: str, content: bytes) -> dict:
        return {
            "manifestId": str(uuid4()),
            "assets": [{"state": "READY", "variants": [{
                "blobId": blob, "sha256": hashlib.sha256(content).hexdigest(),
                "byteLength": len(content),
            }]}],
        }


if __name__ == "__main__":
    unittest.main()
