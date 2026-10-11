"""Real encoded-byte contract tests; run inside Dockerfile.local on each target architecture."""

import hashlib
import json
import struct
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
    elif kind == "wav":
        ffmpeg("-f", "lavfi", "-i", "sine=frequency=440:duration=0.5:sample_rate=24000", "-ac", "1",
               "-c:a", "pcm_s16le", "-f", "wav", path)
    elif kind == "wav_float":
        ffmpeg("-f", "lavfi", "-i", "sine=frequency=440:duration=0.5", "-c:a", "pcm_f32le", "-f", "wav", path)
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


CANARY = "MNEMA-PRIVATE-CANARY-7731"
GPS_LATITUDE = (55, 45, 21)
GPS_LONGITUDE = (37, 37, 4)


def exif_app1(canary=CANARY):
    """An APP1 segment with a real EXIF block: Make and Software carrying a canary, Orientation 6 and a GPS IFD (latitude/longitude)."""
    make = canary.encode() + b"\0"
    software = (canary + "-SOFTWARE").encode() + b"\0"
    entries0 = 4
    gps_entries = 4
    ifd0_size = 2 + entries0 * 12 + 4
    gps_offset = 8 + ifd0_size
    gps_size = 2 + gps_entries * 12 + 4
    data = 8 + ifd0_size + gps_size
    make_offset = data
    software_offset = make_offset + len(make)
    latitude_offset = software_offset + len(software)
    longitude_offset = latitude_offset + 24

    def entry(tag, kind, count, value):
        return struct.pack("<HHII", tag, kind, count, value)

    ifd0 = struct.pack("<H", entries0)
    ifd0 += entry(0x010F, 2, len(make), make_offset)            # Make
    ifd0 += entry(0x0112, 3, 1, 6)                              # Orientation: rotate 90
    ifd0 += entry(0x0131, 2, len(software), software_offset)    # Software
    ifd0 += entry(0x8825, 4, 1, gps_offset)                     # GPS IFD pointer
    ifd0 += struct.pack("<I", 0)
    gps = struct.pack("<H", gps_entries)
    gps += entry(0x0001, 2, 2, int.from_bytes(b"N\0\0\0", "little"))
    gps += entry(0x0002, 5, 3, latitude_offset)
    gps += entry(0x0003, 2, 2, int.from_bytes(b"E\0\0\0", "little"))
    gps += entry(0x0004, 5, 3, longitude_offset)
    gps += struct.pack("<I", 0)
    rational = lambda parts: b"".join(struct.pack("<II", value, 1) for value in parts)
    tiff = b"II*\0" + struct.pack("<I", 8) + ifd0 + gps + make + software + rational(GPS_LATITUDE) + rational(GPS_LONGITUDE)
    payload = b"Exif\0\0" + tiff
    return b"\xff\xe1" + struct.pack(">H", len(payload) + 2) + payload


def jpeg_with_exif(path):
    plain = path.with_suffix(".plain.jpg")
    fixture(plain, "jpeg")
    data = plain.read_bytes()
    plain.unlink()
    assert data[:2] == b"\xff\xd8"
    path.write_bytes(data[:2] + exif_app1() + data[2:])


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
            "wav": ("audio", "wav"),
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
                        if name == "gif":
                            self.assertEqual(variant["profile"], "image_gif_poster_webp_320_v1")
                            self.assertEqual(variant["mimeType"], "image/webp")
                            frames = subprocess.run(["ffprobe", "-v", "error", "-count_frames",
                                                     "-select_streams", "v:0", "-show_entries",
                                                     "stream=nb_read_frames", "-of", "csv=p=0", str(target)],
                                                    check=True, capture_output=True, text=True).stdout.strip()
                            self.assertEqual(frames, "1")
                    if kind == "video" and variant["purpose"] == "poster":
                        self.assertLessEqual(max(variant["width"], variant["height"]), 960)
                self.source.unlink()
                for child in self.output.iterdir():
                    child.unlink()


    def _all_output_bytes(self, result):
        return [(variant["profile"], (self.output / variant["path"]).read_bytes()) for variant in result["variants"]]

    def _assert_no_private_metadata(self, result, markers):
        for profile, data in self._all_output_bytes(result):
            for marker in markers:
                self.assertNotIn(marker, data, f"{profile} carries {marker!r}")

    def test_derived_image_variants_carry_no_exif_or_gps(self):
        """Shared media is served to strangers as derived variants: a photo's EXIF (camera, GPS) must not survive the transcode."""
        jpeg_with_exif(self.source)
        source = self.source.read_bytes()
        # the fixture really carries what we claim to strip
        self.assertIn(CANARY.encode(), source)
        self.assertIn(b"Exif\0\0", source)
        result = MediaProcessor(self.runner).process(self.request("image"), self.source, self.output)
        self.assertEqual([variant["purpose"] for variant in result["variants"]], ["playback", "thumbnail"])
        self._assert_no_private_metadata(result, [CANARY.encode(), b"Exif", b"EXIF", b"XMP ", b"ICCP", b"GPS", struct.pack("<II", 55, 1)])
        # and the metadata is not merely hidden from ffprobe: no tags of any kind on the stream or the container
        for variant in result["variants"]:
            probe = json.loads(subprocess.run(["ffprobe", "-v", "error", "-show_format", "-show_streams", "-of", "json", str(self.output / variant["path"])],
                                              check=True, capture_output=True, text=True).stdout)
            self.assertEqual(probe["format"].get("tags", {}), {})
            for stream in probe["streams"]:
                self.assertEqual(stream.get("tags", {}), {})

    def test_derived_video_and_audio_variants_carry_no_location_or_tags(self):
        location = "+55.7558+037.6173/"
        ffmpeg("-f", "lavfi", "-i", "testsrc2=s=96x72:r=5:d=0.6", "-f", "lavfi", "-i", "sine=frequency=440:duration=0.6", "-shortest",
               "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-metadata", f"location={location}", "-metadata", f"title={CANARY}",
               "-metadata", f"comment={CANARY}", "-metadata:s:v:0", f"handler_name={CANARY}", "-f", "mp4", self.source)
        source = self.source.read_bytes()
        self.assertIn(CANARY.encode(), source)
        result = MediaProcessor(self.runner).process(self.request("video", 300_000), self.source, self.output)
        self._assert_no_private_metadata(result, [CANARY.encode(), b"+55.7558", b"\xa9xyz", b"location"])
        for child in self.output.iterdir():
            child.unlink()
        self.source.unlink()
        ffmpeg("-f", "lavfi", "-i", "sine=frequency=440:duration=0.5", "-c:a", "libmp3lame", "-metadata", f"title={CANARY}",
               "-metadata", f"artist={CANARY}", "-metadata", f"comment={location}", "-id3v2_version", "3", "-f", "mp3", self.source)
        self.assertIn(CANARY.encode(), self.source.read_bytes())
        result = MediaProcessor(self.runner).process(self.request("audio", 300_000), self.source, self.output)
        self._assert_no_private_metadata(result, [CANARY.encode(), b"ID3", b"+55.7558"])

    def test_wav_pcm_source_is_accepted_and_transcoded_to_the_playback_variant(self):
        fixture(self.source, "wav")
        result = MediaProcessor(self.runner).process(self.request("audio", 300_000), self.source, self.output)
        self.assertEqual(result["source"]["mimeType"], "audio/wav")
        self.assertEqual([variant["profile"] for variant in result["variants"]], ["audio_aac_m4a_v1"])
        self.assertEqual(result["variants"][0]["mimeType"], "audio/mp4")

    def test_wav_that_is_not_pcm_s16_and_garbage_with_a_wav_header_stay_rejected(self):
        fixture(self.source, "wav_float")
        with self.assertRaisesRegex(MediaRejected, "unsupported_audio"):
            MediaProcessor(self.runner).process(self.request("audio", 300_000), self.source, self.output)
        self.source.write_bytes(b"RIFF\x24\x00\x00\x00WAVE" + b"not audio at all" * 8)
        with self.assertRaises(MediaRejected):
            MediaProcessor(self.runner).process(self.request("audio", 300_000), self.source, self.output)
        self.assertEqual(list(self.output.iterdir()), [])

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
