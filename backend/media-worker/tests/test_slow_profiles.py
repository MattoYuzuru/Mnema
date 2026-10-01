"""Explicit opt-in capacity smoke; synthetic bytes are not a substitute for a phone sample."""

import hashlib
import os
import subprocess
import tempfile
import time
import unittest
import uuid
from pathlib import Path

from mnema_media_worker.process import CommandRunner, MediaProcessor, WorkRequest


@unittest.skipUnless(os.environ.get("MNEMA_MEDIA_SLOW") == "1", "set MNEMA_MEDIA_SLOW=1")
class SlowProfileTests(unittest.TestCase):
    def profile(self, kind, args, max_duration_ms):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "source"
            output = root / "output"
            output.mkdir()
            start = time.monotonic()
            subprocess.run(["ffmpeg", "-hide_banner", "-nostdin", "-v", "error", "-y",
                            *args, str(source)], check=True, capture_output=True, timeout=600)
            data = source.read_bytes()
            request = WorkRequest(uuid.uuid4(), 1, "video", len(data),
                                  hashlib.sha256(data).hexdigest(), max_duration_ms)
            generated_seconds = time.monotonic() - start
            start = time.monotonic()
            result = MediaProcessor(CommandRunner()).process(request, source, output)
            processed_seconds = time.monotonic() - start
            print(f"\n{kind}: source_bytes={len(data)} generate_s={generated_seconds:.1f} "
                  f"process_s={processed_seconds:.1f}", flush=True)
            return result

    def test_4k_hevc_main10_hdr(self):
        result = self.profile("4k_hevc_main10_hdr", [
            "-f", "lavfi", "-i", "testsrc2=s=3840x2160:r=5:d=1", "-an",
            "-c:v", "libx265", "-preset", "ultrafast", "-threads:v", "2",
            "-pix_fmt", "yuv420p10le", "-color_primaries", "bt2020",
            "-color_trc", "smpte2084", "-colorspace", "bt2020nc",
            "-x265-params", "pools=2:frame-threads=1:log-level=error", "-f", "mov"
        ], 300_000)
        self.assertEqual(result["source"]["width"], 3840)
        self.assertEqual(result["source"]["height"], 2160)
        self.assertLessEqual(result["variants"][0]["height"], 1080)

    def test_five_minute_av_duration_boundary(self):
        result = self.profile("five_minute_720p_av", [
            "-f", "lavfi", "-i", "color=c=blue:s=1280x720:r=5:d=300",
            "-f", "lavfi", "-i", "sine=frequency=440:duration=300",
            "-c:v", "libx264", "-preset", "ultrafast", "-threads:v", "2",
            "-pix_fmt", "yuv420p", "-c:a", "aac", "-f", "mp4"
        ], 300_000)
        self.assertLessEqual(result["source"]["durationMs"], 300_000)
        self.assertEqual(result["variants"][0]["mimeType"], "video/mp4")
