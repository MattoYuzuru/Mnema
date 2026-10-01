"""One-shot worker entry point. Caller owns S3, database transactions, and job leases."""

import argparse
import json
import sys
from pathlib import Path

from .process import CommandRunner, MediaProcessor, MediaRejected, MediaRetryable, WorkRequest


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--ffmpeg", default="ffmpeg")
    parser.add_argument("--ffprobe", default="ffprobe")
    args = parser.parse_args()
    try:
        request = WorkRequest.read(args.manifest)
        MediaProcessor(CommandRunner(args.ffmpeg, args.ffprobe)).process(request, args.source, args.output)
        return 0
    except MediaRejected as error:
        print(json.dumps({"status": "rejected", "code": str(error)}), file=sys.stderr)
        return 2
    except (MediaRetryable, OSError) as error:
        code = str(error) if isinstance(error, MediaRetryable) else "worker_io_failure"
        print(json.dumps({"status": "retryable", "code": code}), file=sys.stderr)
        return 3


if __name__ == "__main__":
    raise SystemExit(main())
