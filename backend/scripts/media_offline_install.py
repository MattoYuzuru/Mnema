"""Synthetic native-download acceptance adapter for the immutable media manifest.

Inputs are already downloaded blobs named by blobId. This deliberately does not
transport bytes or store expiring signed URLs. A native client can implement the
same verify-stage-publish contract with its platform filesystem APIs.
"""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import shutil
import tempfile
from uuid import UUID


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as reader:
        for chunk in iter(lambda: reader.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def install(manifest: dict, downloads: Path, library: Path) -> Path:
    manifest_id = manifest["manifestId"]
    UUID(manifest_id)
    library.mkdir(parents=True, exist_ok=True)
    destination = library / manifest_id
    stage = Path(tempfile.mkdtemp(prefix=".media-stage-", dir=library))
    try:
        for asset in manifest["assets"]:
            if asset["state"] != "READY":
                continue
            for variant in asset["variants"]:
                blob_id = variant["blobId"]
                UUID(blob_id)
                expected_hash = variant["sha256"]
                expected_size = variant["byteLength"]
                if len(expected_hash) != 64 or not isinstance(expected_size, int) or expected_size < 1:
                    raise ValueError("invalid blob identity")
                source = downloads / blob_id
                if not source.is_file() or source.is_symlink():
                    raise ValueError(f"missing download: {blob_id}")
                target = stage / blob_id
                digest = hashlib.sha256()
                size = 0
                with source.open("rb") as reader, target.open("wb") as writer:
                    for chunk in iter(lambda: reader.read(1024 * 1024), b""):
                        digest.update(chunk)
                        size += len(chunk)
                        writer.write(chunk)
                    writer.flush()
                    os.fsync(writer.fileno())
                if size != expected_size or digest.hexdigest() != expected_hash:
                    raise ValueError(f"corrupt download: {blob_id}")
        with (stage / "manifest.json").open("w", encoding="utf-8") as writer:
            json.dump(manifest, writer, separators=(",", ":"), ensure_ascii=False)
            writer.flush()
            os.fsync(writer.fileno())
        if destination.is_symlink():
            raise ValueError("unsafe installed snapshot")
        if destination.exists():
            if json.loads((destination / "manifest.json").read_text(encoding="utf-8")) != manifest:
                raise ValueError("manifest identity reused with different content")
            for asset in manifest["assets"]:
                if asset["state"] != "READY":
                    continue
                for variant in asset["variants"]:
                    installed = destination / variant["blobId"]
                    if installed.is_symlink() or not installed.is_file() or installed.stat().st_size != variant["byteLength"]:
                        raise ValueError("installed snapshot is incomplete")
                    if _sha256(installed) != variant["sha256"]:
                        raise ValueError("installed snapshot is corrupt")
            shutil.rmtree(stage)
        else:
            os.rename(stage, destination)
        pointer = library / ".current.tmp"
        with pointer.open("w", encoding="utf-8") as writer:
            writer.write(manifest_id)
            writer.flush()
            os.fsync(writer.fileno())
        os.replace(pointer, library / "current")
        return destination
    finally:
        if stage.exists():
            shutil.rmtree(stage)
