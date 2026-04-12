from __future__ import annotations

import base64
import io
import os
import threading
import time
from typing import Any

from diffusers import AutoPipelineForText2Image
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field
from PIL import Image
import torch

app = FastAPI(title="Mnema Local Image Gateway", version="1.0.0")

DEFAULT_IMAGE_MODEL = os.getenv("IMAGE_DEFAULT_MODEL", "local-sd-turbo").strip() or "local-sd-turbo"
MODEL_ALIASES = {
    "local-sd-turbo": "stabilityai/sd-turbo",
    "stabilityai/sd-turbo": "stabilityai/sd-turbo",
    "x/flux2-klein:latest": "stabilityai/sd-turbo",
    "x/z-image-turbo:latest": "stabilityai/sd-turbo",
}
DEFAULT_STEPS = int(os.getenv("IMAGE_DEFAULT_STEPS", "1"))
DEFAULT_GUIDANCE = float(os.getenv("IMAGE_DEFAULT_GUIDANCE", "0.0"))

_device = "cuda" if torch.cuda.is_available() else "cpu"
_pipeline_lock = threading.Lock()
_generation_lock = threading.Lock()
_pipelines: dict[str, Any] = {}


class ImageGenerationRequest(BaseModel):
    model: str | None = None
    prompt: str = Field(min_length=1)
    size: str | None = "512x512"
    quality: str | None = None
    style: str | None = None
    output_format: str | None = "png"
    response_format: str | None = "b64_json"


def _resolve_model(model: str | None) -> tuple[str, str]:
    requested = (model or DEFAULT_IMAGE_MODEL).strip() or DEFAULT_IMAGE_MODEL
    resolved = MODEL_ALIASES.get(requested, requested)
    return requested, resolved


def _parse_size(size: str | None) -> tuple[int, int]:
    raw = (size or "512x512").strip().lower()
    if "x" not in raw:
        return 512, 512
    width_text, height_text = raw.split("x", 1)
    try:
        width = max(256, min(1024, int(width_text)))
        height = max(256, min(1024, int(height_text)))
    except ValueError:
        return 512, 512
    width -= width % 8
    height -= height % 8
    return max(256, width), max(256, height)


def _torch_dtype() -> torch.dtype:
    return torch.float16 if _device == "cuda" else torch.float32


def _load_pipeline(requested_model: str, resolved_model: str):
    cache_key = requested_model
    existing = _pipelines.get(cache_key)
    if existing is not None:
        return existing

    with _pipeline_lock:
        existing = _pipelines.get(cache_key)
        if existing is not None:
            return existing

        kwargs: dict[str, Any] = {"torch_dtype": _torch_dtype()}
        if _device == "cuda":
            kwargs["variant"] = "fp16"

        pipeline = AutoPipelineForText2Image.from_pretrained(resolved_model, **kwargs)
        pipeline.set_progress_bar_config(disable=True)
        if hasattr(pipeline, "enable_attention_slicing"):
            pipeline.enable_attention_slicing()
        pipeline = pipeline.to(_device)
        _pipelines[cache_key] = pipeline
        return pipeline


def _save_image_bytes(image: Image.Image, output_format: str) -> bytes:
    normalized = (output_format or "png").strip().lower()
    if normalized == "jpg":
        normalized = "jpeg"
    image_format = "JPEG" if normalized == "jpeg" else "PNG"
    buffer = io.BytesIO()
    params: dict[str, Any] = {}
    if image_format == "JPEG":
        params["quality"] = 92
        image = image.convert("RGB")
    image.save(buffer, format=image_format, **params)
    return buffer.getvalue()


def _generate_image_bytes(request: ImageGenerationRequest) -> tuple[bytes, str]:
    requested_model, resolved_model = _resolve_model(request.model)
    pipeline = _load_pipeline(requested_model, resolved_model)
    width, height = _parse_size(request.size)
    num_inference_steps = DEFAULT_STEPS
    guidance_scale = DEFAULT_GUIDANCE

    with _generation_lock, torch.inference_mode():
        result = pipeline(
            prompt=request.prompt.strip(),
            width=width,
            height=height,
            num_inference_steps=num_inference_steps,
            guidance_scale=guidance_scale,
        )
        image = result.images[0]
        image_bytes = _save_image_bytes(image, request.output_format or "png")

    if _device == "cuda":
        torch.cuda.empty_cache()

    return image_bytes, requested_model


@app.get("/health")
async def health() -> dict[str, Any]:
    return {
        "status": "ok",
        "device": _device,
        "default_model": DEFAULT_IMAGE_MODEL,
    }


@app.get("/v1/models")
async def models() -> dict[str, Any]:
    visible = []
    seen = set()
    for model_id in [DEFAULT_IMAGE_MODEL, *MODEL_ALIASES.keys()]:
        model_id = model_id.strip()
        if not model_id or model_id in seen:
            continue
        seen.add(model_id)
        visible.append(
            {
                "id": model_id,
                "object": "model",
                "owned_by": "local-image-gateway",
                "created": None,
                "metadata": {"capabilities": ["image"]},
            }
        )
    return {"object": "list", "data": visible}


@app.post("/v1/images/generations")
async def image_generations(request: ImageGenerationRequest) -> dict[str, Any]:
    try:
        image_bytes, requested_model = _generate_image_bytes(request)
    except Exception as exc:
        raise HTTPException(status_code=502, detail=f"Image generation failed: {exc}") from exc

    return {
        "created": int(time.time()),
        "model": requested_model,
        "output_format": (request.output_format or "png").strip().lower() or "png",
        "data": [
            {
                "b64_json": base64.b64encode(image_bytes).decode("ascii"),
                "revised_prompt": request.prompt.strip(),
            }
        ],
    }
