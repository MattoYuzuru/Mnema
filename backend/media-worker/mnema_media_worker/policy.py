"""Worker safety ceilings. Upload policy may be stricter, never broader."""

from dataclasses import dataclass


MIB = 1024 * 1024
GIB = 1024 * MIB


@dataclass(frozen=True)
class KindCeiling:
    source_bytes: int
    duration_ms: int | None
    pixels: int | None


KIND_CEILINGS = {
    "image": KindCeiling(64 * MIB, 60_000, 40_000_000),
    "audio": KindCeiling(512 * MIB, 3_600_000, None),
    "video": KindCeiling(4 * GIB, 300_000, 4096 * 2160),
}

MAX_STREAMS = 4
MAX_INPUT_VIDEO_FPS = 120
MAX_IMAGE_FRAMES = 600
MAX_OUTPUT_BYTES = GIB
MAX_TOTAL_OUTPUT_BYTES = GIB
PROBE_TIMEOUT_SECONDS = 20
PROCESS_TIMEOUT_SECONDS = 30 * 60
MAX_PROBE_OUTPUT_BYTES = 256 * 1024
MAX_PROCESS_LOG_BYTES = 32 * 1024

# Changing an encoder parameter changes the bytes of a stored derivative. Bump the
# corresponding profile name in process.py with any change to these settings.
IMAGE_PLAYBACK_MAX_EDGE = 2048
IMAGE_THUMBNAIL_MAX_EDGE = 320
IMAGE_WEBP_QUALITY = 82
AUDIO_PLAYBACK_BITRATE = "128k"
AUDIO_PLAYBACK_SAMPLE_RATE = "48000"
VIDEO_PLAYBACK_MAX_WIDTH = 1920
VIDEO_PLAYBACK_MAX_HEIGHT = 1080
VIDEO_PLAYBACK_MAX_FPS = 30
VIDEO_POSTER_MAX_EDGE = 960
VIDEO_H264_PRESET = "veryfast"
VIDEO_H264_CRF = "23"
VIDEO_H264_MAX_RATE = "8M"
VIDEO_H264_BUFFER_SIZE = "16M"
CODEC_THREADS = "2"
