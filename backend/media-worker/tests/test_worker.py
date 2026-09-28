"""Real encoded-byte contract tests; run inside Dockerfile.local on each target architecture."""

import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
import uuid
from pathlib import Path
from unittest.mock import patch

from mnema_media_worker.process import (CommandRunner, MediaProcessor, MediaRejected,
                                        MediaRetryable, WorkRequest)


def ffmpeg(*args):
    subprocess.run(["ffmpeg", "-hide_banner", "-nostdin", "-v", "error", "-y", *map(str, args)],
                   check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=120)


def fixture(path, kind):
    if kind in ("jpeg", "png", "webp"):
        codec = {"jpeg": "mjpeg", "png": "png", "webp": "libwebp"}[kind]
        ffmpeg("-f", "lavfi", "-i", "testsrc2=s=96x72:r=1", "-frames:v", "1",
               "-c:v", codec, "-f", "image2", path)
    elif kind == "gif":
        ffmpeg("-f", "lavfi", "-i", "testsrc2=s=96x72:r=5:d=0.4", "-c:v", "gif", "-f", "gif", path)
    elif kind == "mp3_cover":
        ffmpeg("-f", "lavfi", "-i", "sine=frequency=440:duration=0.5",
               "-f", "lavfi", "-i", "color=c=red:s=32x24:r=1",
               "-map", "0:a:0", "-map", "1:v:0", "-frames:v", "1",
               "-c:a", "libmp3lame", "-c:v", "mjpeg", "-disposition:v:0", "attached_pic",
               "-id3v2_version", "3", "-f", "mp3", path)
    elif kind in ("mp3", "m4a", "audio_webm"):
        codec = {"mp3": "libmp3lame", "m4a": "aac", "audio_webm": "libopus"}[kind]
        fmt = {"mp3": "mp3", "m4a": "ipod", "audio_webm": "webm"}[kind]
        ffmpeg("-f", "lavfi", "-i", "sine=frequency=440:duration=0.5", "-c:a", codec,
               "-f", fmt, path)
    else:
        codec = {"mp4": "libx264", "mov_hevc": "libx265", "mov_main10": "libx265",
                 "mov_timecode": "libx265",
                 "webm_vp8": "libvpx", "webm_vp9": "libvpx-vp9"}[kind]
        args = ["-f", "lavfi", "-i", "testsrc2=s=96x72:r=5:d=0.6",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=0.6", "-shortest",
                "-c:v", codec, "-threads:v", "2"]
        if kind == "mov_main10":
            args += ["-pix_fmt", "yuv420p10le", "-color_primaries", "bt2020",
                     "-color_trc", "smpte2084", "-colorspace", "bt2020nc",
                     "-x265-params", "log-level=error"]
        elif kind == "mov_hevc":
            args += ["-pix_fmt", "yuv420p", "-x265-params", "log-level=error"]
        elif kind == "mov_timecode":
            args += ["-pix_fmt", "yuv420p", "-x265-params", "log-level=error",
                     "-timecode", "00:00:00:00"]
        else:
            args += ["-pix_fmt", "yuv420p"]
        args += ["-c:a", "libopus" if kind.startswith("webm") else "aac",
                 "-f", "webm" if kind.startswith("webm") else "mov" if kind.startswith("mov") else "mp4", path]
        ffmpeg(*args)


class WorkerTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.runner = CommandRunner()

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / "source"
        self.output = self.root / "output"
        self.output.mkdir()

    def request(self, kind, duration=None):
        data = self.source.read_bytes()
        return WorkRequest(uuid.uuid4(), 7, kind, len(data), hashlib.sha256(data).hexdigest(), duration)

    def test_real_codec_matrix(self):
        matrix = {
            "jpeg": ("image", "jpg"), "png": ("image", "png"),
            "webp": ("image", "webp"), "gif": ("image", "gif"),
            "mp3": ("audio", "mp3"), "m4a": ("audio", "m4a"),
            "mp3_cover": ("audio", "mp3"),
            "audio_webm": ("audio", "webm"),
            "mp4": ("video", "mp4"), "mov_hevc": ("video", "mov"),
            "mov_timecode": ("video", "mov"),
            "mov_main10": ("video", "mov"), "webm_vp8": ("video", "webm"),
            "webm_vp9": ("video", "webm"),
        }
        for name, (kind, extension) in matrix.items():
            with self.subTest(name=name):
                path = self.root / f"input.{extension}"
                fixture(path, name)
                path.rename(self.source)
                result = MediaProcessor(self.runner).process(
                    self.request(kind, None if kind == "image" else 300_000), self.source, self.output)
                self.assertEqual(result["source"]["byteLength"], self.source.stat().st_size)
                if kind != "image":
                    self.assertIsNotNone(result["source"]["durationMs"])
                self.assertEqual(len(result["variants"]), 1 if kind == "audio" else 2)
                self.assertEqual(json.loads((self.output / "result.json").read_text()), result)
                for variant in result["variants"]:
                    target = self.output / variant["path"]
                    self.assertEqual(hashlib.sha256(target.read_bytes()).hexdigest(), variant["sha256"])
                    self.assertEqual(target.stat().st_size, variant["byteLength"])
                    if kind == "image" and variant["purpose"] == "thumbnail":
                        self.assertLessEqual(max(variant["width"], variant["height"]), 320)
                    if kind == "video" and variant["purpose"] == "poster":
                        self.assertLessEqual(max(variant["width"], variant["height"]), 960)
                self.source.unlink()
                for child in self.output.iterdir():
                    child.unlink()

    def test_hash_mismatch_rejects_without_output(self):
        fixture(self.source, "png")
        request = self.request("image")
        changed = bytearray(self.source.read_bytes())
        changed[-1] ^= 1
        self.source.write_bytes(changed)
        with self.assertRaisesRegex(MediaRejected, "source_hash_mismatch"):
            MediaProcessor(self.runner).process(request, self.source, self.output)
        self.assertEqual(list(self.output.iterdir()), [])

    def test_kind_spoofing_rejects(self):
        fixture(self.source, "png")
        with self.assertRaisesRegex(MediaRejected, "unsupported_audio"):
            MediaProcessor(self.runner).process(self.request("audio", 300_000), self.source, self.output)
        self.assertEqual(list(self.output.iterdir()), [])

    def test_duration_limit_rejects(self):
        fixture(self.source, "mp4")
        with self.assertRaisesRegex(MediaRejected, "duration_limit"):
            MediaProcessor(self.runner).process(self.request("video", 100), self.source, self.output)

    def test_manifest_validation(self):
        manifest = self.root / "request.json"
        manifest.write_text(json.dumps({"formatVersion": 1, "assetId": str(uuid.uuid4()),
                                        "generation": 1, "kind": "video", "expectedByteLength": 0,
                                        "expectedSha256": "a" * 64, "maxDurationMs": 300000}))
        with self.assertRaisesRegex(MediaRejected, "invalid_manifest"):
            WorkRequest.read(manifest)

    def test_many_gif_frames_rejected(self):
        ffmpeg("-f", "lavfi", "-i", "testsrc2=s=32x24:r=60", "-frames:v", "601",
               "-c:v", "gif", "-f", "gif", self.source)
        with self.assertRaisesRegex(MediaRejected, "frame_count_limit"):
            MediaProcessor(self.runner).process(self.request("image"), self.source, self.output)

    def test_animated_webp_rejected_without_silent_first_frame(self):
        ffmpeg("-f", "lavfi", "-i", "testsrc2=s=32x24:r=5:d=1", "-c:v", "libwebp_anim",
               "-loop", "0", "-f", "webp", self.source)
        with self.assertRaisesRegex(MediaRejected, "animated_webp_unsupported"):
            MediaProcessor(self.runner).process(self.request("image"), self.source, self.output)

    def test_cli_success_and_rejection_codes(self):
        fixture(self.source, "png")
        request = self.request("image")
        manifest = self.root / "request.json"
        manifest.write_text(json.dumps({"formatVersion": 1, "assetId": str(request.asset_id),
                                        "generation": request.generation, "kind": request.kind,
                                        "expectedByteLength": request.expected_byte_length,
                                        "expectedSha256": request.expected_sha256,
                                        "maxDurationMs": None}))
        command = [sys.executable, "-m", "mnema_media_worker", "--manifest", str(manifest),
                   "--source", str(self.source), "--output", str(self.output)]
        success = subprocess.run(command, capture_output=True, timeout=120)
        self.assertEqual(success.returncode, 0, success.stderr)
        for child in self.output.iterdir():
            child.unlink()
        self.source.write_bytes(self.source.read_bytes() + b"tamper")
        rejected = subprocess.run(command, capture_output=True, timeout=120)
        self.assertEqual(rejected.returncode, 2)
        self.assertEqual(json.loads(rejected.stderr)["code"], "source_size_mismatch")

    def test_command_runner_bounds_and_resource_failure(self):
        with self.assertRaisesRegex(MediaRejected, "codec_output_limit"):
            self.runner.run([sys.executable, "-c", "print('too much output')"], 5, 2)
        with self.assertRaisesRegex(MediaRetryable, "codec_resource_failure"):
            self.runner.run([sys.executable, "-c",
                             "import sys; sys.stderr.write('No space left on device'); sys.exit(1)"],
                            5, 0)
        with self.assertRaisesRegex(MediaRetryable, "codec_timeout"):
            self.runner.run([sys.executable, "-c", "import time; time.sleep(2)"], 0.1, 0)

    def test_failed_second_variant_does_not_publish_partial_result(self):
        fixture(self.source, "png")
        processor = MediaProcessor(self.runner)
        original = processor._encode
        calls = 0

        def fail_second(*args):
            nonlocal calls
            calls += 1
            if calls == 2:
                raise MediaRetryable("injected_failure")
            return original(*args)

        with patch.object(processor, "_encode", side_effect=fail_second):
            with self.assertRaisesRegex(MediaRetryable, "injected_failure"):
                processor.process(self.request("image"), self.source, self.output)
        self.assertEqual(list(self.output.iterdir()), [])


if __name__ == "__main__":
    unittest.main()
