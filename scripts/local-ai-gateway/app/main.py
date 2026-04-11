from __future__ import annotations

import json
import os
import time
from typing import Any, Iterable
from urllib.parse import parse_qsl

import httpx
from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse, Response

app = FastAPI(title="Mnema Local AI Gateway", version="1.0.0")

TIMEOUT_SECONDS = float(os.getenv("GATEWAY_TIMEOUT_SECONDS", "600"))
HTTP_TIMEOUT = httpx.Timeout(connect=10.0, read=TIMEOUT_SECONDS, write=TIMEOUT_SECONDS, pool=TIMEOUT_SECONDS)
OLLAMA_BASE_URL = os.getenv("OLLAMA_BASE_URL", "http://ollama:11434").rstrip("/")
REMOTE_OPENAI_BASE_URL = os.getenv("REMOTE_OPENAI_BASE_URL", "https://api.openai.com").strip().rstrip("/")
AUDIO_BASE_URL = os.getenv("AUDIO_BASE_URL", "").strip().rstrip("/")
IMAGE_BASE_URL = os.getenv("IMAGE_BASE_URL", "").strip().rstrip("/")
VIDEO_BASE_URL = os.getenv("VIDEO_BASE_URL", "").strip().rstrip("/")
OLLAMA_AUDIO_EXPERIMENTAL = os.getenv("OLLAMA_AUDIO_EXPERIMENTAL", "false").strip().lower() in {"1", "true", "yes", "on"}
OLLAMA_IMAGE_EXPERIMENTAL = os.getenv("OLLAMA_IMAGE_EXPERIMENTAL", "true").strip().lower() in {"1", "true", "yes", "on"}
AUDIO_CAPABILITY_CACHE_TTL_SECONDS = float(os.getenv("AUDIO_CAPABILITY_CACHE_TTL_SECONDS", "300"))

DEFAULT_TEXT_MODEL = os.getenv("GATEWAY_DEFAULT_TEXT_MODEL", "qwen3:8b").strip()
DEFAULT_TTS_MODEL = os.getenv("GATEWAY_DEFAULT_TTS_MODEL", "").strip()
DEFAULT_STT_MODEL = os.getenv("GATEWAY_DEFAULT_STT_MODEL", "").strip()
DEFAULT_IMAGE_MODEL = os.getenv("GATEWAY_DEFAULT_IMAGE_MODEL", "").strip()
DEFAULT_VIDEO_MODEL = os.getenv("GATEWAY_DEFAULT_VIDEO_MODEL", "").strip()

STATIC_TTS_VOICES = [
    voice.strip() for voice in os.getenv("GATEWAY_TTS_VOICES", "").split(",") if voice.strip()
]

_ollama_audio_supported_cache: bool | None = None
_ollama_audio_supported_checked_at = 0.0


def _has_bearer_auth(request: Request) -> bool:
    auth = request.headers.get("authorization", "")
    return auth.lower().startswith("bearer ") and len(auth.strip()) > 7


def _should_use_remote_openai(path: str, request: Request) -> bool:
    if not REMOTE_OPENAI_BASE_URL:
        return False
    if not _has_bearer_auth(request):
        return False
    return path.startswith("/v1/")


def _probe_ollama_audio_support() -> bool:
    speech_url = f"{OLLAMA_BASE_URL}/v1/audio/speech"
    transcription_url = f"{OLLAMA_BASE_URL}/v1/audio/transcriptions"
    try:
        timeout = httpx.Timeout(connect=2.0, read=2.0, write=2.0, pool=2.0)
        with httpx.Client(timeout=timeout, follow_redirects=True) as client:
            speech = client.post(
                speech_url,
                json={"model": "__probe__", "input": "test"},
                headers={"content-type": "application/json"},
            )
            transcription = client.post(
                transcription_url,
                json={"model": "__probe__"},
                headers={"content-type": "application/json"},
            )
        return speech.status_code != 404 and transcription.status_code != 404
    except Exception:
        return False


def _ollama_audio_supported() -> bool:
    if not OLLAMA_AUDIO_EXPERIMENTAL:
        return False

    global _ollama_audio_supported_cache
    global _ollama_audio_supported_checked_at

    now = time.monotonic()
    if (
        _ollama_audio_supported_cache is not None
        and (now - _ollama_audio_supported_checked_at) < AUDIO_CAPABILITY_CACHE_TTL_SECONDS
    ):
        return _ollama_audio_supported_cache

    _ollama_audio_supported_cache = _probe_ollama_audio_support()
    _ollama_audio_supported_checked_at = now
    return _ollama_audio_supported_cache


def _choose_backend(path: str, request: Request) -> str:
    if _should_use_remote_openai(path, request):
        return REMOTE_OPENAI_BASE_URL
    if path.startswith("/v1/audio/"):
        if AUDIO_BASE_URL:
            return AUDIO_BASE_URL
        if _ollama_audio_supported():
            return OLLAMA_BASE_URL
        return ""
    if path.startswith("/v1/images/"):
        if IMAGE_BASE_URL:
            return IMAGE_BASE_URL
        if OLLAMA_IMAGE_EXPERIMENTAL:
            return OLLAMA_BASE_URL
        return ""
    if path.startswith("/v1/videos"):
        return VIDEO_BASE_URL or ""
    return OLLAMA_BASE_URL


def _forward_headers(request: Request) -> dict[str, str]:
    allowed = {
        "authorization",
        "content-type",
        "accept",
        "openai-organization",
        "openai-project",
        "x-request-id",
    }
    result: dict[str, str] = {}
    for name, value in request.headers.items():
        lower = name.lower()
        if lower in allowed:
            result[lower] = value
    return result


def _build_query_params(request: Request) -> list[tuple[str, str]]:
    raw = request.url.query
    if not raw:
        return []
    return parse_qsl(raw, keep_blank_values=True)


def _inject_default_model(path: str, body: bytes) -> bytes:
    if not body:
        return body
    content: dict[str, Any]
    try:
        content = json.loads(body)
    except json.JSONDecodeError:
        return body

    model = content.get("model")
    if isinstance(model, str) and model.strip():
        return body

    fallback = ""
    if path in ("/v1/responses", "/v1/chat/completions", "/v1/completions"):
        fallback = DEFAULT_TEXT_MODEL
    elif path == "/v1/audio/speech":
        fallback = DEFAULT_TTS_MODEL
    elif path == "/v1/audio/transcriptions":
        fallback = DEFAULT_STT_MODEL
    elif path == "/v1/images/generations":
        fallback = DEFAULT_IMAGE_MODEL
    elif path == "/v1/videos":
        fallback = DEFAULT_VIDEO_MODEL

    if not fallback:
        return body

    content["model"] = fallback
    return json.dumps(content, ensure_ascii=True).encode("utf-8")


async def _proxy(request: Request, path: str, backend_base_url: str) -> Response:
    if not backend_base_url:
        raise HTTPException(status_code=503, detail=f"Backend is not configured for path {path}")

    method = request.method.upper()
    target_url = f"{backend_base_url}{path}"
    body = await request.body()
    headers = _forward_headers(request)

    if headers.get("content-type", "").startswith("application/json"):
        body = _inject_default_model(path, body)

    try:
        async with httpx.AsyncClient(timeout=HTTP_TIMEOUT, follow_redirects=True) as client:
            upstream = await client.request(
                method,
                target_url,
                params=_build_query_params(request),
                content=body,
                headers=headers,
            )
    except httpx.TimeoutException as exc:
        raise HTTPException(
            status_code=504,
            detail=f"Upstream timeout backend={backend_base_url} path={path} timeout_seconds={TIMEOUT_SECONDS} reason={exc.__class__.__name__}"
        ) from exc
    except httpx.HTTPError as exc:
        raise HTTPException(status_code=502, detail=f"Upstream connection failed: {exc!s}") from exc

    response_headers = {}
    content_type = upstream.headers.get("content-type")
    if content_type:
        response_headers["content-type"] = content_type

    return Response(
        content=upstream.content,
        status_code=upstream.status_code,
        headers=response_headers,
    )


def _capabilities_from_model_name(model_name: str, allow_ollama_audio: bool = False) -> set[str]:
    normalized = model_name.lower()
    caps = {"text"}
    if any(token in normalized for token in ("vision", "vl", "llava", "moondream", "minicpm-v")):
        caps.add("vision")
    if any(token in normalized for token in ("image", "sd", "flux")):
        caps.add("image")
    if allow_ollama_audio:
        if any(token in normalized for token in ("whisper", "asr", "stt")):
            caps.add("stt")
        if any(token in normalized for token in ("tts", "voice", "kokoro", "orpheus", "piper")):
            caps.add("tts")
    if any(token in normalized for token in ("video", "t2v", "wan", "sora")):
        caps.add("video")
    return caps


async def _load_ollama_models() -> list[dict[str, Any]]:
    try:
        async with httpx.AsyncClient(timeout=HTTP_TIMEOUT) as client:
            response = await client.get(f"{OLLAMA_BASE_URL}/api/tags")
            response.raise_for_status()
            payload = response.json()
    except Exception:
        return []

    result = []
    allow_ollama_audio = _ollama_audio_supported()
    for model in payload.get("models", []):
        name = str(model.get("name", "")).strip()
        if not name:
            continue
        caps = sorted(_capabilities_from_model_name(name, allow_ollama_audio=allow_ollama_audio))
        result.append(
            {
                "id": name,
                "object": "model",
                "owned_by": "ollama",
                "created": None,
                "metadata": {
                    "size": model.get("size"),
                    "modified_at": model.get("modified_at"),
                    "capabilities": caps,
                },
            }
        )
    return result


async def _load_audio_models() -> list[dict[str, Any]]:
    if not AUDIO_BASE_URL:
        return []

    try:
        async with httpx.AsyncClient(timeout=HTTP_TIMEOUT) as client:
            response = await client.get(f"{AUDIO_BASE_URL}/v1/models")
            response.raise_for_status()
            payload = response.json()
    except Exception:
        return []

    result = []
    for model in payload.get("data", []):
        model_id = str(model.get("id", "")).strip()
        if not model_id:
            continue
        caps: set[str] = set()
        metadata = model.get("metadata") if isinstance(model.get("metadata"), dict) else {}
        for cap in metadata.get("capabilities", []):
            cap_text = str(cap).strip().lower()
            if cap_text:
                caps.add(cap_text)
        caps.add("tts")
        result.append(
            {
                "id": model_id,
                "object": "model",
                "owned_by": str(model.get("owned_by") or "audio-backend"),
                "created": model.get("created"),
                "metadata": {
                    "capabilities": sorted(caps),
                },
            }
        )
    return result


async def _load_audio_voices() -> list[str]:
    if AUDIO_BASE_URL:
        try:
            async with httpx.AsyncClient(timeout=HTTP_TIMEOUT) as client:
                response = await client.get(f"{AUDIO_BASE_URL}/v1/audio/voices")
                response.raise_for_status()
                payload = response.json()

            voices: list[str] = []
            for item in payload.get("data", []):
                voice_id = str(item.get("id", "")).strip()
                if voice_id:
                    voices.append(voice_id)
            if voices:
                return sorted(set(voices), key=str.lower)
        except Exception:
            pass
    return sorted(set(STATIC_TTS_VOICES), key=str.lower)


def _merge_models(models: Iterable[dict[str, Any]]) -> list[dict[str, Any]]:
    merged: dict[str, dict[str, Any]] = {}
    for model in models:
        model_id = str(model.get("id", "")).strip()
        if not model_id:
            continue
        existing = merged.get(model_id)
        if existing is None:
            merged[model_id] = model
            continue
        old_caps = set(existing.get("metadata", {}).get("capabilities", []))
        new_caps = set(model.get("metadata", {}).get("capabilities", []))
        existing.setdefault("metadata", {})["capabilities"] = sorted(old_caps | new_caps)
    return sorted(merged.values(), key=lambda item: item.get("id", ""))


@app.get("/health")
async def health() -> dict[str, Any]:
    return {
        "status": "ok",
        "ollama_base_url": OLLAMA_BASE_URL,
        "remote_openai_base_url": REMOTE_OPENAI_BASE_URL or None,
        "audio_base_url": AUDIO_BASE_URL or None,
        "image_base_url": IMAGE_BASE_URL or None,
        "video_base_url": VIDEO_BASE_URL or None,
        "ollama_audio_experimental": OLLAMA_AUDIO_EXPERIMENTAL,
        "ollama_audio_supported": _ollama_audio_supported(),
        "ollama_image_experimental": OLLAMA_IMAGE_EXPERIMENTAL,
    }


@app.get("/v1/models")
async def list_models() -> dict[str, Any]:
    ollama_models = await _load_ollama_models()
    audio_models = await _load_audio_models()
    merged = _merge_models([*ollama_models, *audio_models])

    if DEFAULT_TEXT_MODEL and not any(item.get("id") == DEFAULT_TEXT_MODEL for item in merged):
        merged.insert(
            0,
            {
                "id": DEFAULT_TEXT_MODEL,
                "object": "model",
                "owned_by": "gateway-default",
                "created": None,
                "metadata": {
                    "capabilities": ["text"],
                },
            },
        )

    return {"object": "list", "data": merged}


@app.get("/v1/audio/voices")
async def list_voices() -> dict[str, Any]:
    voices = await _load_audio_voices()
    return {
        "object": "list",
        "data": [
            {
                "id": voice,
                "object": "voice",
                "owned_by": "audio-backend",
            }
            for voice in voices
        ],
    }


@app.api_route(
    "/v1/responses",
    methods=["POST"],
)
async def responses(request: Request) -> Response:
    return await _proxy(request, "/v1/responses", _choose_backend("/v1/responses", request))


@app.api_route(
    "/v1/chat/completions",
    methods=["POST"],
)
async def chat_completions(request: Request) -> Response:
    return await _proxy(request, "/v1/chat/completions", _choose_backend("/v1/chat/completions", request))


@app.api_route(
    "/v1/completions",
    methods=["POST"],
)
async def completions(request: Request) -> Response:
    return await _proxy(request, "/v1/completions", _choose_backend("/v1/completions", request))


@app.api_route(
    "/v1/audio/speech",
    methods=["POST"],
)
async def audio_speech(request: Request) -> Response:
    return await _proxy(request, "/v1/audio/speech", _choose_backend("/v1/audio/speech", request))


@app.api_route(
    "/v1/audio/transcriptions",
    methods=["POST"],
)
async def audio_transcriptions(request: Request) -> Response:
    return await _proxy(request, "/v1/audio/transcriptions", _choose_backend("/v1/audio/transcriptions", request))


@app.api_route(
    "/v1/images/generations",
    methods=["POST"],
)
async def image_generations(request: Request) -> Response:
    return await _proxy(request, "/v1/images/generations", _choose_backend("/v1/images/generations", request))


@app.api_route(
    "/v1/videos",
    methods=["POST"],
)
async def videos(request: Request) -> Response:
    return await _proxy(request, "/v1/videos", _choose_backend("/v1/videos", request))


@app.api_route(
    "/v1/videos/{video_id}",
    methods=["GET"],
)
async def videos_status(video_id: str, request: Request) -> Response:
    return await _proxy(request, f"/v1/videos/{video_id}", _choose_backend("/v1/videos", request))


@app.api_route(
    "/v1/videos/{video_id}/content",
    methods=["GET"],
)
async def videos_content(video_id: str, request: Request) -> Response:
    return await _proxy(request, f"/v1/videos/{video_id}/content", _choose_backend("/v1/videos", request))


@app.exception_handler(HTTPException)
async def http_exception_handler(_: Request, exc: HTTPException) -> JSONResponse:
    return JSONResponse(
        status_code=exc.status_code,
        content={"error": {"message": str(exc.detail), "type": "gateway_error"}},
    )
