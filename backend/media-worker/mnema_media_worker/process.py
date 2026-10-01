"""FFmpeg adapter with a sealed-file contract and fail-closed output publication."""

from __future__ import annotations

import hashlib
import json
import os
import re
import selectors
import shutil
import signal
import stat
import subprocess
import time
import uuid
from dataclasses import dataclass
from fractions import Fraction
from pathlib import Path
from typing import Any

from .policy import (
    AUDIO_PLAYBACK_BITRATE,
    AUDIO_PLAYBACK_SAMPLE_RATE,
    CODEC_THREADS,
    IMAGE_PLAYBACK_MAX_EDGE,
    IMAGE_THUMBNAIL_MAX_EDGE,
    IMAGE_WEBP_QUALITY,
    KIND_CEILINGS,
    MAX_IMAGE_FRAMES,
    MAX_INPUT_VIDEO_FPS,
    MAX_OUTPUT_BYTES,
    MAX_PROBE_OUTPUT_BYTES,
    MAX_PROCESS_LOG_BYTES,
    MAX_STREAMS,
    MAX_TOTAL_OUTPUT_BYTES,
    PROBE_TIMEOUT_SECONDS,
    PROCESS_TIMEOUT_SECONDS,
    VIDEO_H264_BUFFER_SIZE,
    VIDEO_H264_CRF,
    VIDEO_H264_MAX_RATE,
    VIDEO_H264_PRESET,
    VIDEO_PLAYBACK_MAX_FPS,
    VIDEO_PLAYBACK_MAX_HEIGHT,
    VIDEO_PLAYBACK_MAX_WIDTH,
    VIDEO_POSTER_MAX_EDGE,
)


class MediaRejected(Exception):
    """A source cannot safely satisfy the accepted media profile."""


class MediaRetryable(Exception):
    """The worker runtime or a bounded resource failed; bytes may be retried."""


@dataclass(frozen=True)
class WorkRequest:
    asset_id: uuid.UUID
    generation: int
    kind: str
    expected_byte_length: int
    expected_sha256: str
    max_duration_ms: int | None

    @classmethod
    def read(cls, path: Path) -> WorkRequest:
        if path.stat().st_size > 4096:
            raise MediaRejected("invalid_manifest")
        try:
            raw = json.loads(path.read_text(encoding="utf-8"))
            if not isinstance(raw, dict) or set(raw) != {
                "formatVersion", "assetId", "generation", "kind", "expectedByteLength",
                "expectedSha256", "maxDurationMs"
            } or raw["formatVersion"] != 1:
                raise ValueError()
            asset_id = uuid.UUID(raw["assetId"])
            if asset_id.version != 4 or raw["kind"] not in KIND_CEILINGS:
                raise ValueError()
            if type(raw["generation"]) is not int or raw["generation"] < 0:
                raise ValueError()
            ceiling = KIND_CEILINGS[raw["kind"]]
            size = raw["expectedByteLength"]
            if type(size) is not int or not 0 < size <= ceiling.source_bytes:
                raise ValueError()
            digest = raw["expectedSha256"]
            if not isinstance(digest, str) or not re.fullmatch(r"[0-9a-f]{64}", digest):
                raise ValueError()
            duration = raw["maxDurationMs"]
            if duration is not None and (type(duration) is not int or ceiling.duration_ms is None
                                         or not 0 < duration <= ceiling.duration_ms):
                raise ValueError()
            if raw["kind"] in ("audio", "video") and duration is None:
                raise ValueError()
            if raw["kind"] == "image" and duration is not None:
                raise ValueError()
            return cls(asset_id, raw["generation"], raw["kind"], size, digest, duration)
        except (OSError, UnicodeError, TypeError, ValueError, KeyError) as exc:
            raise MediaRejected("invalid_manifest") from exc


@dataclass(frozen=True)
class SourceProfile:
    mime_type: str
    video: dict[str, Any] | None
    audio: dict[str, Any] | None
    duration_ms: int | None
    width: int | None
    height: int | None
    animated: bool
    hdr: bool
    fps: Fraction | None


class CommandRunner:
    def __init__(self, ffmpeg: str = "ffmpeg", ffprobe: str = "ffprobe") -> None:
        self.ffmpeg = shutil.which(ffmpeg)
        self.ffprobe = shutil.which(ffprobe)
        if self.ffmpeg is None or self.ffprobe is None:
            raise MediaRetryable("codec_runtime_missing")

    def run(self, args: list[str], timeout_seconds: int, output_limit: int) -> bytes:
        """Read child output with a byte cap; kill its entire process group on bounds."""
        process = subprocess.Popen(
            args, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            start_new_session=True, env={"LC_ALL": "C", "HOME": "/nonexistent"},
        )
        selector = selectors.DefaultSelector()
        assert process.stdout is not None and process.stderr is not None
        selector.register(process.stdout, selectors.EVENT_READ)
        selector.register(process.stderr, selectors.EVENT_READ)
        stdout = bytearray()
        stderr = bytearray()
        deadline = time.monotonic() + timeout_seconds
        try:
            while selector.get_map():
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise MediaRetryable("codec_timeout")
                for key, _ in selector.select(min(remaining, 0.25)):
                    chunk = os.read(key.fd, 8192)
                    if not chunk:
                        selector.unregister(key.fileobj)
                        continue
                    target = stdout if key.fileobj is process.stdout else stderr
                    target.extend(chunk)
                    if len(stdout) > output_limit or len(stderr) > MAX_PROCESS_LOG_BYTES:
                        raise MediaRejected("codec_output_limit")
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise MediaRetryable("codec_timeout")
            if process.wait(timeout=remaining) != 0:
                if any(marker in stderr for marker in (b"No space left on device",
                                                      b"Cannot allocate memory",
                                                      b"Resource temporarily unavailable")):
                    raise MediaRetryable("codec_resource_failure")
                raise MediaRejected("codec_failure")
            return bytes(stdout)
        except subprocess.TimeoutExpired as exc:
            raise MediaRetryable("codec_timeout") from exc
        finally:
            selector.close()
            if process.poll() is None:
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                process.wait()
            process.stdout.close()
            process.stderr.close()


class MediaProcessor:
    def __init__(self, runner: CommandRunner) -> None:
        self.runner = runner

    def process(self, request: WorkRequest, source: Path, output: Path) -> dict[str, Any]:
        self._check_paths(source, output)
        source_hash = self._hash_file(source, request.expected_byte_length)
        if source_hash != request.expected_sha256:
            raise MediaRejected("source_hash_mismatch")
        profile = self._inspect_source(request, source)
        decoded_duration_ms = self._verify_source_decode(request, profile, source)
        created: list[Path] = []
        try:
            variants = self._make_variants(request, profile, source, output, created)
            if self._hash_file(source, request.expected_byte_length) != request.expected_sha256:
                raise MediaRejected("source_changed")
            result = {
                "formatVersion": 1, "assetId": str(request.asset_id),
                "generation": request.generation, "kind": request.kind,
                "source": {
                    "sha256": source_hash, "byteLength": request.expected_byte_length,
                    "mimeType": profile.mime_type,
                    "durationMs": profile.duration_ms if profile.duration_ms is not None else (
                        decoded_duration_ms if request.kind != "image" or profile.animated else None),
                    "width": profile.width, "height": profile.height,
                },
                "variants": variants,
            }
            manifest = output / "result.json"
            temporary = output / ".result.part"
            temporary.write_text(json.dumps(result, sort_keys=True, separators=(",", ":")), encoding="utf-8")
            created.append(temporary)
            os.replace(temporary, manifest)
            created.remove(temporary)
            return result
        except BaseException:
            for path in created:
                path.unlink(missing_ok=True)
            raise

    @staticmethod
    def _check_paths(source: Path, output: Path) -> None:
        if source.is_symlink() or not source.is_file() or not output.is_dir() or output.is_symlink():
            raise MediaRejected("invalid_paths")
        if source.resolve().is_relative_to(output.resolve()) or output.resolve().is_relative_to(source.resolve()):
            raise MediaRejected("invalid_paths")
        if any(output.iterdir()):
            raise MediaRejected("output_not_empty")
        if not stat.S_ISREG(source.stat().st_mode):
            raise MediaRejected("invalid_source")

    @staticmethod
    def _hash_file(path: Path, expected_size: int) -> str:
        digest = hashlib.sha256()
        size = 0
        try:
            with path.open("rb", buffering=0) as stream:
                while chunk := stream.read(1024 * 1024):
                    size += len(chunk)
                    if size > expected_size:
                        raise MediaRejected("source_size_mismatch")
                    digest.update(chunk)
        except OSError as exc:
            raise MediaRetryable("source_read_failure") from exc
        if size != expected_size:
            raise MediaRejected("source_size_mismatch")
        return digest.hexdigest()

    def _probe(self, path: Path) -> dict[str, Any]:
        args = [self.runner.ffprobe, "-v", "error", "-protocol_whitelist", "file,pipe",
                "-probesize", str(5 * 1024 * 1024), "-analyzeduration", "5000000",
                "-show_entries", "format=format_name,duration,size:format_tags=major_brand:"
                "stream=index,codec_type,codec_name,profile,width,height,pix_fmt,avg_frame_rate,"
                "duration,nb_frames,color_primaries,color_transfer,color_space,channels,sample_rate:"
                "stream_side_data=rotation:stream_disposition=attached_pic", "-of", "json", "-i", str(path)]
        try:
            data = self.runner.run(args, PROBE_TIMEOUT_SECONDS, MAX_PROBE_OUTPUT_BYTES)
            parsed = json.loads(data)
            if (not isinstance(parsed, dict) or not isinstance(parsed.get("format"), dict)
                    or not isinstance(parsed.get("streams"), list)):
                raise ValueError()
            return parsed
        except (ValueError, UnicodeError) as exc:
            raise MediaRejected("invalid_probe") from exc

    def _inspect_source(self, request: WorkRequest, source: Path) -> SourceProfile:
        with source.open("rb") as stream:
            header = stream.read(4096)
        data = self._probe(source)
        streams = data["streams"]
        if not 1 <= len(streams) <= MAX_STREAMS or any(not isinstance(item, dict) for item in streams):
            raise MediaRejected("unsupported_streams")
        pictures = [item for item in streams if item.get("codec_type") == "video"
                    and item.get("disposition", {}).get("attached_pic") == 1]
        video = [item for item in streams if item.get("codec_type") == "video" and item not in pictures]
        audio = [item for item in streams if item.get("codec_type") == "audio"]
        if len(video) > 1 or len(audio) > 1:
            raise MediaRejected("unsupported_streams")
        fmt = data["format"]
        names = set(str(fmt.get("format_name", "")).split(","))
        duration = self._duration_ms(fmt.get("duration"))
        if duration is None:
            duration = self._duration_ms((video or audio)[0].get("duration") if video or audio else None)
        ceiling = KIND_CEILINGS[request.kind]
        max_duration = request.max_duration_ms or ceiling.duration_ms
        if duration is not None and max_duration is not None and duration > max_duration:
            raise MediaRejected("duration_limit")
        if (request.kind == "image" and header[:4] == b"RIFF" and header[8:16] == b"WEBPVP8X"
                and header[20] & 0x02):
            raise MediaRejected("animated_webp_unsupported")
        width = height = None
        fps = None
        hdr = False
        if video:
            width, height = video[0].get("width"), video[0].get("height")
            if type(width) is not int or type(height) is not int or min(width, height) < 1:
                raise MediaRejected("invalid_dimensions")
            if ceiling.pixels is not None and (width * height > ceiling.pixels
                                               or max(width, height) > 32768):
                raise MediaRejected("pixel_limit")
            fps = self._fps(video[0].get("avg_frame_rate"))
            if fps is not None and fps > MAX_INPUT_VIDEO_FPS:
                raise MediaRejected("frame_rate_limit")
            hdr = video[0].get("color_transfer") in ("smpte2084", "arib-std-b67")
        if request.kind == "image":
            if len(video) != 1 or audio or any(item.get("codec_type") != "video" for item in streams):
                raise MediaRejected("unsupported_image")
            mime = self._image_mime(header, names, video[0])
            frames = self._positive_int(video[0].get("nb_frames"))
            animated = mime == "image/gif" and (frames is None or frames > 1)
            if animated and frames is not None and frames > MAX_IMAGE_FRAMES:
                raise MediaRejected("frame_count_limit")
            if animated and duration is None:
                raise MediaRejected("duration_missing")
            return SourceProfile(mime, video[0], None, duration, width, height, animated, False, fps)
        if request.kind == "audio":
            if video or len(audio) != 1 or any(item.get("codec_type") not in ("audio", "data")
                                            and item not in pictures for item in streams):
                raise MediaRejected("unsupported_audio")
            mime = self._audio_mime(header, names, audio[0])
            self._check_audio_stream(audio[0])
            return SourceProfile(mime, None, audio[0], duration, None, None, False, False, None)
        if pictures:
            raise MediaRejected("unsupported_streams")
        if len(video) != 1 or any(item.get("codec_type") not in ("video", "audio", "data")
                                  for item in streams):
            raise MediaRejected("unsupported_video")
        mime = self._video_mime(header, names, video[0], audio[0] if audio else None, fmt)
        if audio:
            self._check_audio_stream(audio[0])
        if max(width, height) > 4096:
            raise MediaRejected("pixel_limit")
        return SourceProfile(mime, video[0], audio[0] if audio else None, duration,
                             width, height, False, hdr, fps)

    def _verify_source_decode(self, request: WorkRequest, profile: SourceProfile, source: Path) -> int:
        # Metadata alone is insufficient: WebM may omit duration, and image files can
        # contain later corrupt frames. Decode every selected stream before publication.
        selected = (["-map", "0:a:0"] if request.kind == "audio" else ["-map", "0:v:0"])
        if request.kind == "video" and profile.audio:
            selected += ["-map", "0:a:0"]
        args = [self.runner.ffmpeg, "-hide_banner", "-nostdin", "-v", "error", "-xerror",
                "-protocol_whitelist", "file,pipe", "-threads", CODEC_THREADS, "-i", str(source),
                *selected,
                "-stats_period", "60", "-progress", "pipe:1", "-f", "null", "-"]
        progress = self.runner.run(args, PROCESS_TIMEOUT_SECONDS, MAX_PROBE_OUTPUT_BYTES)
        values = {}
        for line in progress.decode("ascii", errors="replace").splitlines():
            if "=" in line:
                key, value = line.split("=", 1)
                values[key] = value
        try:
            frames = int(values.get("frame", "0")) if profile.video else 0
            hours, minutes, seconds = values["out_time"].split(":")
            duration_ms = round((int(hours) * 3600 + int(minutes) * 60 + float(seconds)) * 1000)
        except (ValueError, KeyError) as exc:
            raise MediaRejected("decode_progress_missing") from exc
        if profile.video and frames < 1:
            raise MediaRejected("empty_video")
        if request.kind == "image" and frames > (MAX_IMAGE_FRAMES if profile.animated else 1):
            raise MediaRejected("frame_count_limit")
        if request.kind != "image" and duration_ms > request.max_duration_ms + 50:
            raise MediaRejected("duration_limit")
        if profile.animated and duration_ms > KIND_CEILINGS["image"].duration_ms + 50:
            raise MediaRejected("duration_limit")
        return duration_ms

    @staticmethod
    def _duration_ms(value: Any) -> int | None:
        try:
            duration = float(value)
            if duration < 0 or duration > 86_400 or not (duration < float("inf")):
                raise ValueError()
            return round(duration * 1000)
        except (TypeError, ValueError):
            return None

    @staticmethod
    def _positive_int(value: Any) -> int | None:
        try:
            parsed = int(value)
            return parsed if parsed > 0 else None
        except (ValueError, TypeError):
            return None

    @staticmethod
    def _fps(value: Any) -> Fraction | None:
        try:
            rate = Fraction(value)
            return rate if rate > 0 else None
        except (TypeError, ValueError, ZeroDivisionError):
            return None

    @staticmethod
    def _image_mime(header: bytes, names: set[str], stream: dict[str, Any]) -> str:
        codec = stream.get("codec_name")
        if header.startswith(b"\xff\xd8\xff") and codec == "mjpeg" and "jpeg_pipe" in names:
            return "image/jpeg"
        if header.startswith(b"\x89PNG\r\n\x1a\n") and codec == "png" and "png_pipe" in names:
            return "image/png"
        if header[:4] == b"RIFF" and header[8:12] == b"WEBP" and codec in ("webp", "webp_anim"):
            if codec == "webp_anim" or (header[12:16] == b"VP8X" and header[20] & 0x02):
                raise MediaRejected("animated_webp_unsupported")
            return "image/webp"
        if header[:6] in (b"GIF87a", b"GIF89a") and codec == "gif" and "gif" in names:
            return "image/gif"
        raise MediaRejected("unsupported_image")

    @staticmethod
    def _audio_mime(header: bytes, names: set[str], stream: dict[str, Any]) -> str:
        codec = stream.get("codec_name")
        if (header.startswith(b"ID3") or header[:1] == b"\xff") and "mp3" in names and codec == "mp3":
            return "audio/mpeg"
        if header[4:8] == b"ftyp" and "mov" in names and codec == "aac":
            return "audio/mp4"
        if header.startswith(b"\x1a\x45\xdf\xa3") and b"webm" in header[:256] and "webm" in names and codec == "opus":
            return "audio/webm"
        raise MediaRejected("unsupported_audio")

    @staticmethod
    def _video_mime(header: bytes, names: set[str], video: dict[str, Any],
                    audio: dict[str, Any] | None, fmt: dict[str, Any]) -> str:
        codec = video.get("codec_name")
        sound = audio.get("codec_name") if audio else None
        if (header[4:8] == b"ftyp" and "mov" in names and codec in ("h264", "hevc")
                and sound in (None, "aac")):
            if codec == "hevc" and video.get("profile") not in ("Main", "Main 10"):
                raise MediaRejected("unsupported_hevc_profile")
            brand = str(fmt.get("tags", {}).get("major_brand", "")).strip().lower()
            return "video/quicktime" if brand == "qt" else "video/mp4"
        if (header.startswith(b"\x1a\x45\xdf\xa3") and b"webm" in header[:256]
                and "webm" in names and codec in ("vp8", "vp9") and sound in (None, "opus")):
            return "video/webm"
        raise MediaRejected("unsupported_video")

    @staticmethod
    def _check_audio_stream(stream: dict[str, Any]) -> None:
        channels = MediaProcessor._positive_int(stream.get("channels"))
        sample_rate = MediaProcessor._positive_int(stream.get("sample_rate"))
        if channels is None or channels > 8 or sample_rate is None or sample_rate > 192000:
            raise MediaRejected("unsupported_audio_stream")

    def _make_variants(self, request: WorkRequest, profile: SourceProfile, source: Path,
                       output: Path, created: list[Path]) -> list[dict[str, Any]]:
        variants: list[dict[str, Any]] = []
        if request.kind == "image":
            if profile.animated:
                variants.append(self._encode(source, output, created, "playback", "image_gif_2048_v1", "gif",
                    "image/gif", "gif", self._image_args(IMAGE_PLAYBACK_MAX_EDGE, animated=True), request))
                thumbnail_profile = "image_gif_poster_webp_320_v1"
            else:
                variants.append(self._encode(source, output, created, "playback", "image_webp_2048_v1", "webp",
                    "image/webp", "webp", self._image_args(IMAGE_PLAYBACK_MAX_EDGE), request))
                thumbnail_profile = "image_webp_320_v1"
            variants.append(self._encode(source, output, created, "thumbnail", thumbnail_profile, "webp",
                "image/webp", "webp", self._image_args(IMAGE_THUMBNAIL_MAX_EDGE), request))
        elif request.kind == "audio":
            variants.append(self._encode(source, output, created, "playback", "audio_aac_m4a_v1", "m4a",
                "audio/mp4", "aac", ["-map", "0:a:0", "-vn", "-c:a", "aac",
                "-b:a", AUDIO_PLAYBACK_BITRATE,
                "-ar", AUDIO_PLAYBACK_SAMPLE_RATE, "-ac", "2", "-movflags", "+faststart", "-f", "ipod"], request))
        else:
            video_filter = self._video_filter(profile, VIDEO_PLAYBACK_MAX_WIDTH, VIDEO_PLAYBACK_MAX_HEIGHT)
            variants.append(self._encode(source, output, created, "playback", "video_h264_aac_sdr_1080_v1", "mp4",
                "video/mp4", "h264", ["-map", "0:v:0", "-map", "0:a:0?", "-vf", video_filter,
                "-c:v", "libx264", "-preset", VIDEO_H264_PRESET, "-crf", VIDEO_H264_CRF,
                "-maxrate", VIDEO_H264_MAX_RATE, "-bufsize", VIDEO_H264_BUFFER_SIZE,
                "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", AUDIO_PLAYBACK_BITRATE,
                "-ar", AUDIO_PLAYBACK_SAMPLE_RATE, "-ac", "2", "-color_primaries", "bt709", "-color_trc", "bt709",
                "-colorspace", "bt709", "-movflags", "+faststart", "-f", "mp4"], request))
            variants.append(self._encode(source, output, created, "poster", "video_poster_webp_960_v1", "webp",
                "image/webp", "webp", ["-map", "0:v:0", "-vf", self._video_filter(profile, VIDEO_POSTER_MAX_EDGE, VIDEO_POSTER_MAX_EDGE),
                "-frames:v", "1", "-an", "-c:v", "libwebp", "-q:v", str(IMAGE_WEBP_QUALITY), "-f", "webp"], request))
        if sum(variant["byteLength"] for variant in variants) > MAX_TOTAL_OUTPUT_BYTES:
            raise MediaRejected("total_output_limit")
        return variants

    @staticmethod
    def _image_args(edge: int, animated: bool = False) -> list[str]:
        scale = f"scale=w='min({edge},iw)':h='min({edge},ih)':force_original_aspect_ratio=decrease:flags=lanczos"
        if animated:
            return ["-map", "0:v:0", "-vf", scale, "-an", "-frames:v", str(MAX_IMAGE_FRAMES + 1),
                    "-c:v", "gif", "-f", "gif"]
        return ["-map", "0:v:0", "-vf", scale, "-frames:v", "1", "-an",
                "-c:v", "libwebp", "-q:v", str(IMAGE_WEBP_QUALITY), "-f", "webp"]

    @staticmethod
    def _video_filter(profile: SourceProfile, max_width: int, max_height: int) -> str:
        filters: list[str] = []
        if profile.hdr:
            filters += ["zscale=t=linear:npl=100", "format=gbrpf32le", "tonemap=hable:desat=0",
                        "zscale=t=bt709:m=bt709:p=bt709"]
        filters.append(f"scale=w='min({max_width},iw)':h='min({max_height},ih)':"
                       "force_original_aspect_ratio=decrease:force_divisible_by=2:flags=lanczos")
        if profile.fps is not None and profile.fps > VIDEO_PLAYBACK_MAX_FPS:
            filters.append(f"fps={VIDEO_PLAYBACK_MAX_FPS}")
        filters.append("format=yuv420p")
        return ",".join(filters)

    def _encode(self, source: Path, output: Path, created: list[Path], purpose: str, profile_name: str,
                extension: str, mime: str, codec: str, options: list[str], request: WorkRequest) -> dict[str, Any]:
        temporary = output / f".{profile_name}.part"
        target = output / f"{profile_name}.{extension}"
        created.append(temporary)
        args = [self.runner.ffmpeg, "-hide_banner", "-nostdin", "-v", "error", "-xerror",
                "-max_error_rate", "0", "-protocol_whitelist", "file,pipe", "-threads", CODEC_THREADS,
                "-filter_threads", CODEC_THREADS, "-i", str(source), "-map_metadata", "-1", "-map_chapters", "-1",
                "-sn", "-dn", *options]
        if request.max_duration_ms is not None:
            args += ["-t", str(request.max_duration_ms / 1000 + 1)]
        args += ["-fs", str(MAX_OUTPUT_BYTES), str(temporary)]
        self.runner.run(args, PROCESS_TIMEOUT_SECONDS, 0)
        if not temporary.is_file() or temporary.stat().st_size == 0 or temporary.stat().st_size >= MAX_OUTPUT_BYTES:
            raise MediaRejected("output_limit")
        inspected = self._probe(temporary)
        output_streams = inspected["streams"]
        if not any(item.get("codec_name") == codec for item in output_streams):
            raise MediaRejected("output_codec_mismatch")
        if mime == "video/mp4":
            video = next(item for item in output_streams if item.get("codec_type") == "video")
            if (video.get("pix_fmt") != "yuv420p"
                    or any(video.get(field) != "bt709" for field in
                           ("color_primaries", "color_transfer", "color_space"))):
                raise MediaRejected("output_profile_mismatch")
            if any(item.get("codec_type") == "audio" and item.get("codec_name") != "aac"
                   for item in output_streams):
                raise MediaRejected("output_codec_mismatch")
        if mime == "audio/mp4" and any(item.get("codec_type") == "video" for item in output_streams):
            raise MediaRejected("output_codec_mismatch")
        duration = self._duration_ms(inspected["format"].get("duration"))
        if request.max_duration_ms is not None and duration is not None and duration > request.max_duration_ms + 50:
            raise MediaRejected("duration_limit")
        if request.kind == "image" and duration is not None and duration > KIND_CEILINGS["image"].duration_ms + 50:
            raise MediaRejected("duration_limit")
        self.runner.run([self.runner.ffmpeg, "-hide_banner", "-nostdin", "-v", "error", "-xerror",
                         "-protocol_whitelist", "file,pipe", "-i", str(temporary), "-f", "null", "-"],
                        PROCESS_TIMEOUT_SECONDS, 0)
        byte_length = temporary.stat().st_size
        digest = self._hash_file(temporary, byte_length)
        os.replace(temporary, target)
        created.remove(temporary)
        created.append(target)
        visual = next((item for item in output_streams if item.get("codec_type") == "video"), None)
        return {"purpose": purpose, "profile": profile_name, "path": target.name,
                "sha256": digest, "byteLength": byte_length, "mimeType": mime,
                "durationMs": duration, "width": visual.get("width") if visual else None,
                "height": visual.get("height") if visual else None}
