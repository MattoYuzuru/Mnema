from __future__ import annotations

import asyncio
import json
import logging
import os
import re
import subprocess
import tempfile
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from fastapi import FastAPI, HTTPException
from fastapi.responses import JSONResponse, Response
from pydantic import BaseModel

app = FastAPI(title="Mnema Local Audio Gateway", version="2.0.0")
logger = logging.getLogger("mnema.local_audio_gateway")

DATA_ROOT = Path(os.getenv("LOCAL_AUDIO_DATA_DIR", "/models/audio"))
STATE_PATH = DATA_ROOT / "state.json"
HF_HOME = Path(os.getenv("HF_HOME", str(DATA_ROOT / "hf")))
PIPER_DATA_DIR = Path(os.getenv("PIPER_DATA_DIR", str(DATA_ROOT / "piper")))
PIPER_DOWNLOAD_DIR = Path(os.getenv("PIPER_DOWNLOAD_DIR", str(PIPER_DATA_DIR)))
PRELOAD_MODELS = os.getenv("LOCAL_AUDIO_PRELOAD", "true").strip().lower() in {"1", "true", "yes", "on"}

PIPER_MODEL_ID = "piper-tts"
QWEN_MODEL_ID = "qwen3-tts"
KOKORO_MODEL_ID = "kokoro-82m"
DEFAULT_MODEL_IDS = [PIPER_MODEL_ID, QWEN_MODEL_ID, KOKORO_MODEL_ID]
DEFAULT_PIPER_VOICES = ["ru_RU-irina-medium", "en_US-lessac-medium"]
DEFAULT_QWEN_SPEAKERS = ["Vivian", "Ryan"]
QWEN_SUPPORTED_SPEAKERS = [
    "Vivian",
    "Serena",
    "Uncle_Fu",
    "Dylan",
    "Eric",
    "Ryan",
    "Aiden",
    "Ono_Anna",
    "Sohee",
]
DEFAULT_KOKORO_VOICES = ["af_heart", "af_bella"]
QWEN_SPEAKER_LOOKUP = {speaker.lower(): speaker for speaker in QWEN_SUPPORTED_SPEAKERS}
VOICE_ID_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{1,127}$")
PIPER_VOICE_PATTERN = re.compile(r"^[a-z]{2}_[A-Z]{2}-[A-Za-z0-9_-]+(?:-[A-Za-z0-9_-]+)*$")
KOKORO_VOICE_PATTERN = re.compile(r"^[abefhipjz][fm]_[A-Za-z0-9_-]+$")

DEFAULT_MODEL = os.getenv("LOCAL_AUDIO_DEFAULT_MODEL", PIPER_MODEL_ID).strip() or PIPER_MODEL_ID
DEFAULT_VOICE = os.getenv("LOCAL_AUDIO_DEFAULT_VOICE", DEFAULT_PIPER_VOICES[0]).strip() or DEFAULT_PIPER_VOICES[0]
QWEN_MODEL_REF = os.getenv("LOCAL_AUDIO_QWEN_MODEL_REF", "Qwen/Qwen3-TTS-12Hz-0.6B-CustomVoice").strip()
KOKORO_REPO_ID = os.getenv("LOCAL_AUDIO_KOKORO_REPO_ID", "hexgrad/Kokoro-82M").strip()

_synthesis_lock = asyncio.Lock()
_preload_error: str | None = None
_qwen_runtime: Any | None = None
_qwen_runtime_device = ""
_kokoro_pipelines: dict[str, Any] = {}


@dataclass(frozen=True)
class AudioModel:
    model_id: str
    provider: str
    docs_url: str
    default_voice: str
    preload_voices: list[str]


AVAILABLE_MODELS: dict[str, AudioModel] = {
    PIPER_MODEL_ID: AudioModel(
        model_id=PIPER_MODEL_ID,
        provider="piper",
        docs_url="https://rhasspy.github.io/piper-samples/",
        default_voice=DEFAULT_PIPER_VOICES[0],
        preload_voices=DEFAULT_PIPER_VOICES,
    ),
    QWEN_MODEL_ID: AudioModel(
        model_id=QWEN_MODEL_ID,
        provider="qwen",
        docs_url="https://github.com/QwenLM/Qwen3-TTS",
        default_voice=DEFAULT_QWEN_SPEAKERS[0],
        preload_voices=DEFAULT_QWEN_SPEAKERS,
    ),
    KOKORO_MODEL_ID: AudioModel(
        model_id=KOKORO_MODEL_ID,
        provider="kokoro",
        docs_url="https://github.com/hexgrad/kokoro",
        default_voice=DEFAULT_KOKORO_VOICES[0],
        preload_voices=DEFAULT_KOKORO_VOICES,
    ),
}


class SpeechRequest(BaseModel):
    model: str | None = None
    input: str
    voice: str | None = None
    response_format: str | None = "wav"


def _split_csv(raw: str, fallback: list[str]) -> list[str]:
    values = [item.strip() for item in raw.split(",") if item.strip()]
    return values or list(fallback)


def _normalize_enabled_models(raw: str | None) -> list[str]:
    if raw is None or not raw.strip():
        return list(DEFAULT_MODEL_IDS)

    enabled: list[str] = []
    legacy_piper_voices: list[str] = []
    for item in [part.strip() for part in raw.split(",") if part.strip()]:
        lowered = item.lower()
        if lowered in AVAILABLE_MODELS:
            if lowered not in enabled:
                enabled.append(lowered)
            continue
        if PIPER_VOICE_PATTERN.match(item):
            legacy_piper_voices.append(item)
            if PIPER_MODEL_ID not in enabled:
                enabled.append(PIPER_MODEL_ID)

    if legacy_piper_voices:
        os.environ.setdefault("LOCAL_AUDIO_PIPER_VOICES", ",".join(legacy_piper_voices))
    return enabled or list(DEFAULT_MODEL_IDS)


ENABLED_MODEL_IDS = _normalize_enabled_models(os.getenv("LOCAL_AUDIO_MODELS"))
ENABLED_MODELS = [AVAILABLE_MODELS[model_id] for model_id in ENABLED_MODEL_IDS if model_id in AVAILABLE_MODELS]
EFFECTIVE_DEFAULT_MODEL = DEFAULT_MODEL if DEFAULT_MODEL in ENABLED_MODEL_IDS else (ENABLED_MODEL_IDS[0] if ENABLED_MODEL_IDS else PIPER_MODEL_ID)
PIPER_PRELOAD_VOICES = _split_csv(os.getenv("LOCAL_AUDIO_PIPER_VOICES", ",".join(DEFAULT_PIPER_VOICES)), DEFAULT_PIPER_VOICES)
QWEN_PRELOAD_VOICES = _split_csv(os.getenv("LOCAL_AUDIO_QWEN_VOICES", ",".join(DEFAULT_QWEN_SPEAKERS)), DEFAULT_QWEN_SPEAKERS)
KOKORO_PRELOAD_VOICES = _split_csv(os.getenv("LOCAL_AUDIO_KOKORO_VOICES", ",".join(DEFAULT_KOKORO_VOICES)), DEFAULT_KOKORO_VOICES)


def _ensure_dirs() -> None:
    DATA_ROOT.mkdir(parents=True, exist_ok=True)
    HF_HOME.mkdir(parents=True, exist_ok=True)
    PIPER_DATA_DIR.mkdir(parents=True, exist_ok=True)
    PIPER_DOWNLOAD_DIR.mkdir(parents=True, exist_ok=True)
    os.environ.setdefault("HF_HOME", str(HF_HOME))


def _content_type(audio_format: str) -> str:
    normalized = audio_format.lower()
    if normalized == "wav":
        return "audio/wav"
    if normalized == "ogg":
        return "audio/ogg"
    if normalized == "mp3":
        return "audio/mpeg"
    raise HTTPException(status_code=400, detail=f"Unsupported response_format: {audio_format}")


def _read_state() -> dict[str, Any]:
    _ensure_dirs()
    if not STATE_PATH.exists():
        return {"voices": {"piper": [], "qwen": [], "kokoro": []}}
    try:
        return json.loads(STATE_PATH.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        logger.warning("local-audio-gateway state is unreadable path=%s", STATE_PATH)
        return {"voices": {"piper": [], "qwen": [], "kokoro": []}}


def _write_state(state: dict[str, Any]) -> None:
    _ensure_dirs()
    STATE_PATH.write_text(json.dumps(state, ensure_ascii=True, indent=2), encoding="utf-8")


def _remember_voice(provider: str, voice: str) -> None:
    if not voice:
        return
    state = _read_state()
    voices = state.setdefault("voices", {})
    items = voices.setdefault(provider, [])
    if voice not in items:
        items.append(voice)
        items.sort(key=str.lower)
        _write_state(state)


def _installed_voices(provider: str | None = None) -> list[str]:
    state = _read_state()
    voices = state.get("voices", {})
    if provider:
        return sorted({str(item).strip() for item in voices.get(provider, []) if str(item).strip()}, key=str.lower)
    result: set[str] = set()
    for item in voices.values():
        if isinstance(item, list):
            result.update(str(value).strip() for value in item if str(value).strip())
    return sorted(result, key=str.lower)


def _known_models() -> list[AudioModel]:
    result: list[AudioModel] = []
    for model in ENABLED_MODELS:
        if model.model_id not in {item.model_id for item in result}:
            result.append(model)
    return result


def _validate_voice_id(voice: str) -> str:
    trimmed = voice.strip()
    if not trimmed:
        raise HTTPException(status_code=400, detail="voice is required")
    if not VOICE_ID_PATTERN.match(trimmed) and not PIPER_VOICE_PATTERN.match(trimmed):
        raise HTTPException(status_code=400, detail=f"Unsupported voice id: {trimmed}")
    return trimmed


def _normalize_model(model: str | None) -> str:
    trimmed = (model or "").strip().lower()
    if not trimmed:
        return EFFECTIVE_DEFAULT_MODEL if EFFECTIVE_DEFAULT_MODEL in AVAILABLE_MODELS else PIPER_MODEL_ID
    if trimmed in AVAILABLE_MODELS:
        return trimmed
    if PIPER_VOICE_PATTERN.match(model or ""):
        return PIPER_MODEL_ID
    raise HTTPException(status_code=400, detail=f"Unsupported local TTS model: {model}")


def _infer_provider_from_voice(voice: str) -> str:
    if voice.lower() in QWEN_SPEAKER_LOOKUP:
        return "qwen"
    lowered = voice.lower()
    if KOKORO_VOICE_PATTERN.match(lowered):
        return "kokoro"
    if PIPER_VOICE_PATTERN.match(voice):
        return "piper"
    installed = _read_state().get("voices", {})
    for provider in ("qwen", "kokoro", "piper"):
        if voice in installed.get(provider, []):
            return provider
    return "piper"


def _resolve_request(model: str | None, voice: str | None) -> tuple[AudioModel, str]:
    candidate_voice = _validate_voice_id(voice) if voice and voice.strip() else ""
    normalized_model = _normalize_model(model)
    audio_model = AVAILABLE_MODELS.get(normalized_model)
    if audio_model is None or audio_model.model_id not in ENABLED_MODEL_IDS:
        raise HTTPException(status_code=400, detail=f"Local TTS model is not enabled: {normalized_model}")

    if candidate_voice:
        inferred_provider = _infer_provider_from_voice(candidate_voice)
        if inferred_provider != audio_model.provider:
            raise HTTPException(
                status_code=400,
                detail=f"Voice {candidate_voice} does not belong to selected model {audio_model.model_id}",
            )
        if inferred_provider == "qwen":
            candidate_voice = QWEN_SPEAKER_LOOKUP[candidate_voice.lower()]
        return audio_model, candidate_voice

    installed = _installed_voices(audio_model.provider)
    if audio_model.default_voice in installed:
        return audio_model, audio_model.default_voice
    if installed:
        return audio_model, installed[0]
    return audio_model, audio_model.default_voice


def _local_voice_model_path(voice: str) -> Path | None:
    if "/" in voice or "\\" in voice or voice.endswith(".onnx"):
        return Path(voice)
    return PIPER_DATA_DIR / f"{voice}.onnx"


def _local_voice_is_ready(model_path: Path) -> bool:
    return model_path.exists() and Path(f"{model_path}.json").exists()


def _piper_model_args(voice: str) -> list[str]:
    model_path = _local_voice_model_path(voice)
    if model_path and _local_voice_is_ready(model_path):
        return ["--model", str(model_path)]
    return [
        "--model",
        voice,
        "--data-dir",
        str(PIPER_DATA_DIR),
        "--download-dir",
        str(PIPER_DOWNLOAD_DIR),
        "--update-voices",
    ]


def _run_piper(text: str, voice: str, target_path: Path) -> None:
    cmd = [
        "piper",
        *_piper_model_args(voice),
        "--output_file",
        str(target_path),
    ]
    started = time.monotonic()
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

    elapsed_ms = int((time.monotonic() - started) * 1000)
    if proc.returncode != 0:
        stderr = proc.stderr.strip() or proc.stdout.strip() or "unknown piper error"
        logger.warning("piper synthesis failed voice=%s elapsed_ms=%s error=%s", voice, elapsed_ms, stderr[-500:])
        raise HTTPException(status_code=502, detail=f"Piper synthesis failed for {voice}: {stderr[-1000:]}")
    if not target_path.exists() or target_path.stat().st_size == 0:
        raise HTTPException(status_code=502, detail=f"Piper synthesis produced empty audio for {voice}")
    _remember_voice("piper", voice)


def _qwen_device() -> tuple[str, str | None]:
    import torch

    if torch.cuda.is_available():
        if torch.cuda.is_bf16_supported():
            return "cuda:0", "bfloat16"
        return "cuda:0", "float16"
    return "cpu", "float32"


def _load_qwen_runtime() -> Any:
    global _qwen_runtime
    global _qwen_runtime_device

    device, dtype_name = _qwen_device()
    if _qwen_runtime is not None and _qwen_runtime_device == device:
        return _qwen_runtime

    try:
        import torch
        from qwen_tts import Qwen3TTSModel
    except ImportError as exc:
        raise HTTPException(status_code=500, detail="Qwen3-TTS runtime is not installed in local-audio-gateway") from exc

    dtype = getattr(torch, dtype_name) if dtype_name else None
    kwargs: dict[str, Any] = {
        "device_map": device,
        "dtype": dtype,
    }
    _qwen_runtime = Qwen3TTSModel.from_pretrained(QWEN_MODEL_REF, **kwargs)
    _qwen_runtime_device = device
    return _qwen_runtime


def _run_qwen(text: str, voice: str, target_path: Path) -> None:
    if voice not in QWEN_SUPPORTED_SPEAKERS:
        raise HTTPException(
            status_code=400,
            detail=f"Unsupported Qwen3-TTS speaker: {voice}. See https://github.com/QwenLM/Qwen3-TTS",
        )
    try:
        import soundfile as sf
    except ImportError as exc:
        raise HTTPException(status_code=500, detail="soundfile is required for Qwen3-TTS output") from exc

    model = _load_qwen_runtime()
    started = time.monotonic()
    try:
        wavs, sample_rate = model.generate_custom_voice(
            text=text,
            language="Auto",
            speaker=voice,
            instruct="",
        )
    except Exception as exc:
        raise HTTPException(status_code=502, detail=f"Qwen3-TTS synthesis failed for {voice}: {exc}") from exc

    elapsed_ms = int((time.monotonic() - started) * 1000)
    if not wavs:
        raise HTTPException(status_code=502, detail=f"Qwen3-TTS produced empty audio for {voice}")
    sf.write(target_path, wavs[0], sample_rate, subtype="PCM_16")
    _remember_voice("qwen", voice)
    logger.info("qwen synthesis completed voice=%s chars=%s elapsed_ms=%s", voice, len(text), elapsed_ms)


def _kokoro_lang_code(voice: str) -> str:
    lowered = voice.lower()
    if not KOKORO_VOICE_PATTERN.match(lowered):
        raise HTTPException(
            status_code=400,
            detail=f"Unsupported Kokoro voice id: {voice}. Expected ids like af_heart or bf_emma.",
        )
    return lowered[0]


def _load_kokoro_pipeline(lang_code: str) -> Any:
    try:
        from kokoro import KPipeline
    except ImportError as exc:
        raise HTTPException(status_code=500, detail="Kokoro runtime is not installed in local-audio-gateway") from exc

    pipeline = _kokoro_pipelines.get(lang_code)
    if pipeline is not None:
        return pipeline

    device = "cuda" if _qwen_device()[0].startswith("cuda") else "cpu"
    pipeline = KPipeline(lang_code=lang_code, repo_id=KOKORO_REPO_ID, device=device)
    _kokoro_pipelines[lang_code] = pipeline
    return pipeline


def _run_kokoro(text: str, voice: str, target_path: Path) -> None:
    try:
        import numpy as np
        import soundfile as sf
    except ImportError as exc:
        raise HTTPException(status_code=500, detail="Kokoro dependencies are missing from local-audio-gateway") from exc

    lang_code = _kokoro_lang_code(voice)
    pipeline = _load_kokoro_pipeline(lang_code)
    started = time.monotonic()
    chunks: list[Any] = []
    try:
        for result in pipeline(text, voice=voice, speed=1, split_pattern=r"\n+"):
            if result.audio is not None:
                chunks.append(result.audio)
    except Exception as exc:
        raise HTTPException(status_code=502, detail=f"Kokoro synthesis failed for {voice}: {exc}") from exc

    elapsed_ms = int((time.monotonic() - started) * 1000)
    if not chunks:
        raise HTTPException(status_code=502, detail=f"Kokoro produced empty audio for {voice}")

    audio = np.concatenate([chunk.detach().cpu().numpy() for chunk in chunks])
    sf.write(target_path, audio, 24000, subtype="PCM_16")
    _remember_voice("kokoro", voice)
    logger.info("kokoro synthesis completed voice=%s chars=%s elapsed_ms=%s", voice, len(text), elapsed_ms)


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


def _run_provider(model: AudioModel, text: str, voice: str, target_path: Path) -> None:
    _ensure_dirs()
    if model.provider == "piper":
        _run_piper(text, voice, target_path)
        return
    if model.provider == "qwen":
        _run_qwen(text, voice, target_path)
        return
    if model.provider == "kokoro":
        _run_kokoro(text, voice, target_path)
        return
    raise HTTPException(status_code=500, detail=f"Unsupported provider runtime: {model.provider}")


async def _synthesize(text: str, model: AudioModel, voice: str, response_format: str) -> bytes:
    with tempfile.TemporaryDirectory(prefix="mnema-audio-") as temp_dir:
        temp_path = Path(temp_dir)
        wav_path = temp_path / "speech.wav"
        out_path = temp_path / f"speech.{response_format}"

        async with _synthesis_lock:
            await asyncio.to_thread(_run_provider, model, text, voice, wav_path)

        if response_format == "wav":
            return wav_path.read_bytes()

        await asyncio.to_thread(_convert_audio, wav_path, out_path, response_format)
        return out_path.read_bytes()


async def _preload_model(audio_model: AudioModel) -> None:
    if audio_model.provider == "piper":
        for voice in PIPER_PRELOAD_VOICES:
            with tempfile.TemporaryDirectory(prefix="mnema-piper-preload-") as temp_dir:
                await asyncio.to_thread(_run_piper, "Mnema local audio is ready.", voice, Path(temp_dir) / "ready.wav")
        return
    if audio_model.provider == "qwen":
        await asyncio.to_thread(_load_qwen_runtime)
        for voice in QWEN_PRELOAD_VOICES:
            _remember_voice("qwen", voice)
        return
    if audio_model.provider == "kokoro":
        for voice in KOKORO_PRELOAD_VOICES:
            with tempfile.TemporaryDirectory(prefix="mnema-kokoro-preload-") as temp_dir:
                await asyncio.to_thread(_run_kokoro, "Mnema local audio is ready.", voice, Path(temp_dir) / "ready.wav")


async def _preload_selected_models() -> None:
    global _preload_error
    if not PRELOAD_MODELS:
        return

    errors: list[str] = []
    for model in _known_models():
        try:
            await _preload_model(model)
        except Exception as exc:  # noqa: BLE001 - startup should keep running and report the provider failure
            logger.warning("local audio preload failed model=%s error=%s", model.model_id, exc)
            errors.append(f"{model.model_id}: {exc}")
    _preload_error = "; ".join(errors) if errors else None


@app.on_event("startup")
async def startup() -> None:
    _ensure_dirs()
    await _preload_selected_models()


@app.get("/health")
async def health() -> dict[str, Any]:
    return {
        "status": "degraded" if _preload_error else "ok",
        "engines": [model.provider for model in _known_models()],
        "default_model": EFFECTIVE_DEFAULT_MODEL,
        "default_voice": DEFAULT_VOICE,
        "models": [model.model_id for model in _known_models()],
        "installed_voices": _installed_voices(),
        "preload_error": _preload_error,
    }


@app.get("/v1/models")
async def list_models() -> dict[str, Any]:
    return {
        "object": "list",
        "data": [
            {
                "id": model.model_id,
                "object": "model",
                "owned_by": "local-audio-gateway",
                "metadata": {
                    "engine": model.provider,
                    "capabilities": ["tts"],
                    "default_voice": model.default_voice,
                    "docs_url": model.docs_url,
                },
            }
            for model in _known_models()
        ],
    }


@app.get("/v1/audio/voices")
async def list_audio_voices() -> dict[str, Any]:
    state = _read_state().get("voices", {})
    voices: list[dict[str, Any]] = []
    for provider in ("piper", "qwen", "kokoro"):
        for voice in sorted({str(item).strip() for item in state.get(provider, []) if str(item).strip()}, key=str.lower):
            voices.append(
                {
                    "id": voice,
                    "object": "voice",
                    "owned_by": "local-audio-gateway",
                    "metadata": {
                        "engine": provider,
                        "installed": True,
                    },
                }
            )
    return {"object": "list", "data": voices}


@app.post("/v1/audio/speech")
async def create_speech(payload: SpeechRequest) -> Response:
    text = payload.input.strip()
    if not text:
        raise HTTPException(status_code=400, detail="input is required")

    response_format = (payload.response_format or "wav").strip().lower()
    _content_type(response_format)
    model, voice = _resolve_request(payload.model, payload.voice)
    audio = await _synthesize(text, model, voice, response_format)

    headers = {
        "x-model": model.model_id,
        "x-voice": voice,
        "x-engine": model.provider,
    }
    return Response(content=audio, media_type=_content_type(response_format), headers=headers)


@app.post("/v1/audio/transcriptions")
async def create_transcription() -> JSONResponse:
    return JSONResponse(
        status_code=501,
        content={"error": {"message": "Transcriptions are not configured in local-audio-gateway", "type": "not_implemented"}},
    )
