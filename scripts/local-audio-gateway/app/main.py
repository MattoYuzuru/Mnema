from __future__ import annotations

import asyncio
import os
import subprocess
import tempfile
from pathlib import Path
from typing import Any

from fastapi import FastAPI, HTTPException
from fastapi.responses import JSONResponse, Response
from pydantic import BaseModel

app = FastAPI(title="Mnema Local Audio Gateway", version="1.0.0")

PIPER_DATA_DIR = Path(os.getenv("PIPER_DATA_DIR", "/models/piper"))
PIPER_DOWNLOAD_DIR = Path(os.getenv("PIPER_DOWNLOAD_DIR", str(PIPER_DATA_DIR)))
PRELOAD_MODELS = os.getenv("LOCAL_AUDIO_PRELOAD", "true").strip().lower() in {"1", "true", "yes", "on"}

DEFAULT_MODELS = [
    "ru_RU-irina-medium",
    "en_US-lessac-medium",
]
CONFIGURED_MODELS = [
    item.strip()
    for item in os.getenv("LOCAL_AUDIO_MODELS", ",".join(DEFAULT_MODELS)).split(",")
    if item.strip()
]
if not CONFIGURED_MODELS:
    CONFIGURED_MODELS = DEFAULT_MODELS

DEFAULT_MODEL = os.getenv("LOCAL_AUDIO_DEFAULT_MODEL", CONFIGURED_MODELS[0]).strip() or CONFIGURED_MODELS[0]
DEFAULT_VOICE = os.getenv("LOCAL_AUDIO_DEFAULT_VOICE", DEFAULT_MODEL).strip() or DEFAULT_MODEL

_piper_lock = asyncio.Lock()
_preload_error: str | None = None


class SpeechRequest(BaseModel):
    model: str | None = None
    input: str
    voice: str | None = None
    response_format: str | None = "wav"


def _known_models() -> list[str]:
    result: list[str] = []
    for model in [DEFAULT_MODEL, DEFAULT_VOICE, *CONFIGURED_MODELS]:
        if model and model not in result:
            result.append(model)
    return result


def _resolve_voice(model: str | None, voice: str | None) -> str:
    if voice and voice.strip():
        return voice.strip()
    if model and model.strip():
        return model.strip()
    return DEFAULT_VOICE


def _content_type(audio_format: str) -> str:
    normalized = audio_format.lower()
    if normalized == "wav":
        return "audio/wav"
    if normalized == "ogg":
        return "audio/ogg"
    if normalized == "mp3":
        return "audio/mpeg"
    raise HTTPException(status_code=400, detail=f"Unsupported response_format: {audio_format}")


def _run_piper(text: str, voice: str, target_path: Path) -> None:
    PIPER_DATA_DIR.mkdir(parents=True, exist_ok=True)
    PIPER_DOWNLOAD_DIR.mkdir(parents=True, exist_ok=True)

    cmd = [
        "piper",
        "--model",
        voice,
        "--data-dir",
        str(PIPER_DATA_DIR),
        "--download-dir",
        str(PIPER_DOWNLOAD_DIR),
        "--update-voices",
        "--output_file",
        str(target_path),
    ]
    try:
        proc = subprocess.run(
            cmd,
            input=text,
            check=False,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            timeout=180,
        )
    except subprocess.TimeoutExpired as exc:
        raise HTTPException(status_code=504, detail=f"Piper synthesis timed out for {voice}") from exc

    if proc.returncode != 0:
        stderr = proc.stderr.strip() or proc.stdout.strip() or "unknown piper error"
        raise HTTPException(status_code=502, detail=f"Piper synthesis failed for {voice}: {stderr[-1000:]}")
    if not target_path.exists() or target_path.stat().st_size == 0:
        raise HTTPException(status_code=502, detail=f"Piper synthesis produced empty audio for {voice}")


def _convert_audio(source: Path, target: Path, target_format: str) -> None:
    normalized = target_format.lower()
    if normalized == "wav":
        return

    codec_args: list[str]
    if normalized == "ogg":
        codec_args = ["-acodec", "libvorbis"]
    elif normalized == "mp3":
        codec_args = ["-acodec", "libmp3lame"]
    else:
        raise HTTPException(status_code=400, detail=f"Unsupported response_format: {target_format}")

    cmd = [
        "ffmpeg",
        "-y",
        "-i",
        str(source),
        *codec_args,
        str(target),
    ]
    try:
        subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, timeout=60)
    except subprocess.TimeoutExpired as exc:
        raise HTTPException(status_code=504, detail=f"Audio conversion timed out for {target_format}") from exc
    except subprocess.CalledProcessError as exc:
        stderr = exc.stderr.decode("utf-8", errors="ignore")
        raise HTTPException(status_code=500, detail=f"Audio conversion failed: {stderr.strip()}") from exc


async def _synthesize(text: str, voice: str, response_format: str) -> bytes:
    with tempfile.TemporaryDirectory(prefix="mnema-piper-") as temp_dir:
        temp_path = Path(temp_dir)
        wav_path = temp_path / "speech.wav"
        out_path = temp_path / f"speech.{response_format}"

        async with _piper_lock:
            await asyncio.to_thread(_run_piper, text, voice, wav_path)

        if response_format == "wav":
            return wav_path.read_bytes()

        await asyncio.to_thread(_convert_audio, wav_path, out_path, response_format)
        return out_path.read_bytes()


async def _preload_selected_models() -> None:
    global _preload_error
    if not PRELOAD_MODELS:
        return

    errors: list[str] = []
    for voice in _known_models():
        try:
            with tempfile.TemporaryDirectory(prefix="mnema-piper-preload-") as temp_dir:
                await asyncio.to_thread(_run_piper, "Mnema local audio is ready.", voice, Path(temp_dir) / "ready.wav")
        except Exception as exc:
            errors.append(f"{voice}: {exc}")

    _preload_error = "; ".join(errors) if errors else None


@app.on_event("startup")
async def startup() -> None:
    await _preload_selected_models()


@app.get("/health")
async def health() -> dict[str, Any]:
    return {
        "status": "degraded" if _preload_error else "ok",
        "engine": "piper",
        "default_model": DEFAULT_MODEL,
        "default_voice": DEFAULT_VOICE,
        "models": _known_models(),
        "preload_error": _preload_error,
    }


@app.get("/v1/models")
async def list_models() -> dict[str, Any]:
    return {
        "object": "list",
        "data": [
            {
                "id": model,
                "object": "model",
                "owned_by": "local-audio-gateway",
                "metadata": {
                    "engine": "piper",
                    "capabilities": ["tts"],
                },
            }
            for model in _known_models()
        ],
    }


@app.get("/v1/audio/voices")
async def list_audio_voices() -> dict[str, Any]:
    return {
        "object": "list",
        "data": [
            {
                "id": model,
                "object": "voice",
                "owned_by": "local-audio-gateway",
                "metadata": {
                    "engine": "piper",
                    "model": model,
                },
            }
            for model in _known_models()
        ],
    }


@app.post("/v1/audio/speech")
async def create_speech(payload: SpeechRequest) -> Response:
    text = payload.input.strip()
    if not text:
        raise HTTPException(status_code=400, detail="input is required")

    response_format = (payload.response_format or "wav").strip().lower()
    _content_type(response_format)
    voice = _resolve_voice(payload.model, payload.voice)
    audio = await _synthesize(text, voice, response_format)

    headers = {
        "x-model": payload.model or DEFAULT_MODEL,
        "x-voice": voice,
        "x-engine": "piper",
    }
    return Response(content=audio, media_type=_content_type(response_format), headers=headers)


@app.post("/v1/audio/transcriptions")
async def create_transcription() -> JSONResponse:
    return JSONResponse(
        status_code=501,
        content={"error": {"message": "Transcriptions are not configured in local-audio-gateway", "type": "not_implemented"}},
    )
