#!/usr/bin/env python3
"""
Mnema Local AI bootstrap.

Features:
- Preset-based model selection (or custom model overrides)
- Optional Ollama autostart and model pull
- Optional custom FastAPI process start
- Generates env file for Mnema AI service to run in local-only mode.
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List


SCRIPT_DIR = Path(__file__).resolve().parent
DEFAULT_ENV_FILE = SCRIPT_DIR / ".mnema-local-ai.env"
DEFAULT_OLLAMA_URL = "http://127.0.0.1:11434"
DEFAULT_FASTAPI_URL = "http://127.0.0.1:8000"
DEFAULT_FASTAPI_HEALTH = "http://127.0.0.1:8000/health"


@dataclass(frozen=True)
class Preset:
    name: str
    description: str
    models: Dict[str, str]


PRESETS: Dict[str, Preset] = {
    "cpu-safe": Preset(
        name="CPU Safe",
        description="Minimal local setup, works on weak machines.",
        models={
            "text": "llama3.2:3b",
            "vision": "llava:7b",
            "stt": "whisper:base",
            "tts": "kokoro:latest",
            "image": "sdxl:latest",
            "video": "wan2.2-t2v:1.3b",
        },
    ),
    "balanced": Preset(
        name="Balanced",
        description="Good quality/speed for modern desktop GPU.",
        models={
            "text": "qwen2.5:7b",
            "vision": "qwen2.5vl:7b",
            "stt": "whisper:large-v3",
            "tts": "kokoro:latest",
            "image": "flux.1-schnell:latest",
            "video": "wan2.2-t2v:14b",
        },
    ),
    "rtx5090": Preset(
        name="RTX 5090 High Quality",
        description="Aggressive preset for top-tier NVIDIA GPUs.",
        models={
            "text": "qwen2.5:32b",
            "vision": "qwen2.5vl:32b",
            "stt": "whisper:large-v3",
            "tts": "kokoro:latest",
            "image": "flux.1-dev:latest",
            "video": "wan2.2-t2v:14b",
        },
    ),
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        prog="mnema-local-ai",
        description="Bootstrap Mnema local AI runtime with preset or custom models.",
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    subparsers.add_parser("list-presets", help="List available model presets.")

    setup = subparsers.add_parser("setup", help="Prepare local runtime and write env file.")
    setup.add_argument("--preset", choices=sorted(PRESETS.keys()), default="balanced")
    setup.add_argument("--model-text", help="Override text model name.")
    setup.add_argument("--model-vision", help="Override vision model name.")
    setup.add_argument("--model-stt", help="Override STT model name.")
    setup.add_argument("--model-tts", help="Override TTS model name.")
    setup.add_argument("--model-image", help="Override image model name.")
    setup.add_argument("--model-video", help="Override video model name.")
    setup.add_argument("--env-file", type=Path, default=DEFAULT_ENV_FILE, help="Output env file.")
    setup.add_argument("--ollama-url", default=DEFAULT_OLLAMA_URL, help="Ollama base URL.")
    setup.add_argument(
        "--runtime-base-url",
        default="",
        help="OpenAI-compatible runtime URL for Mnema (defaults to ollama-url).",
    )
    setup.add_argument("--fastapi-cmd", default="", help="Command to run FastAPI runtime.")
    setup.add_argument(
        "--fastapi-health-url",
        default=DEFAULT_FASTAPI_HEALTH,
        help="Health URL to verify custom FastAPI runtime.",
    )
    setup.add_argument(
        "--fastapi-base-url",
        default=DEFAULT_FASTAPI_URL,
        help="Base URL to write when custom FastAPI runtime is used.",
    )
    setup.add_argument("--skip-pull", action="store_true", help="Skip ollama model pull.")
    setup.add_argument("--no-start-ollama", action="store_true", help="Do not start ollama serve.")
    setup.add_argument("--non-interactive", action="store_true", help="No prompts.")
    setup.add_argument("--print-env", action="store_true", help="Print env values to stdout.")
    return parser.parse_args()


def pick_preset_interactive(default_preset: str) -> str:
    print("\nAvailable presets:")
    preset_ids = sorted(PRESETS.keys())
    for idx, preset_id in enumerate(preset_ids, start=1):
        preset = PRESETS[preset_id]
        print(f"  {idx}. {preset_id} - {preset.name}: {preset.description}")
    raw = input(f"Choose preset [default: {default_preset}]: ").strip()
    if not raw:
        return default_preset
    if raw in PRESETS:
        return raw
    if raw.isdigit():
        num = int(raw)
        if 1 <= num <= len(preset_ids):
            return preset_ids[num - 1]
    print(f"Unknown preset '{raw}', fallback to {default_preset}.")
    return default_preset


def apply_model_overrides(base: Dict[str, str], args: argparse.Namespace) -> Dict[str, str]:
    models = dict(base)
    mapping = {
        "text": args.model_text,
        "vision": args.model_vision,
        "stt": args.model_stt,
        "tts": args.model_tts,
        "image": args.model_image,
        "video": args.model_video,
    }
    for key, value in mapping.items():
        if value and value.strip():
            models[key] = value.strip()
    return models


def prompt_model_overrides(models: Dict[str, str]) -> Dict[str, str]:
    print("\nModel overrides (Enter to keep preset value):")
    updated = dict(models)
    for key in ["text", "vision", "stt", "tts", "image", "video"]:
        raw = input(f"  {key} [{models[key]}]: ").strip()
        if raw:
            updated[key] = raw
    return updated


def command_exists(name: str) -> bool:
    return shutil.which(name) is not None


def http_ok(url: str, timeout_s: int = 2) -> bool:
    try:
        with urllib.request.urlopen(url, timeout=timeout_s) as resp:
            return 200 <= resp.getcode() < 300
    except (urllib.error.URLError, TimeoutError, ValueError):
        return False


def ollama_running(ollama_url: str) -> bool:
    return http_ok(f"{ollama_url.rstrip('/')}/api/tags")


def start_ollama_service() -> None:
    if os.name == "nt":
        flags = subprocess.DETACHED_PROCESS | subprocess.CREATE_NEW_PROCESS_GROUP  # type: ignore[attr-defined]
        subprocess.Popen(  # noqa: S603
            ["ollama", "serve"],
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            creationflags=flags,
        )
    else:
        subprocess.Popen(  # noqa: S603
            ["ollama", "serve"],
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            start_new_session=True,
        )


def wait_for_ollama(ollama_url: str, timeout_s: int = 40) -> bool:
    started = time.time()
    while time.time() - started < timeout_s:
        if ollama_running(ollama_url):
            return True
        time.sleep(1)
    return False


def start_fastapi(cmd: str) -> None:
    if os.name == "nt":
        flags = subprocess.DETACHED_PROCESS | subprocess.CREATE_NEW_PROCESS_GROUP  # type: ignore[attr-defined]
        subprocess.Popen(  # noqa: S602,S603
            cmd,
            shell=True,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            creationflags=flags,
        )
    else:
        subprocess.Popen(  # noqa: S602,S603
            cmd,
            shell=True,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            start_new_session=True,
        )


def wait_for_http(url: str, timeout_s: int = 50) -> bool:
    started = time.time()
    while time.time() - started < timeout_s:
        if http_ok(url):
            return True
        time.sleep(1)
    return False


def run_pull(model: str) -> bool:
    proc = subprocess.run(  # noqa: S603
        ["ollama", "pull", model],
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
    )
    if proc.returncode != 0:
        print(f"[warn] pull failed for {model}")
        if proc.stdout:
            lines = [line for line in proc.stdout.strip().splitlines() if line.strip()]
            if lines:
                print(f"       {lines[-1]}")
        return False
    return True


def pull_models(models: Dict[str, str]) -> List[str]:
    unique = []
    for _, model in models.items():
        if model not in unique:
            unique.append(model)

    print("\nPulling models from Ollama:")
    failed = []
    for model in unique:
        print(f"  - {model}")
        if not run_pull(model):
            failed.append(model)
    return failed


def build_env(models: Dict[str, str], runtime_base_url: str, ollama_url: str) -> Dict[str, str]:
    openai_url = runtime_base_url.strip() or ollama_url.strip()
    return {
        "AI_PROVIDER": "openai",
        "AI_SYSTEM_MANAGED_PROVIDER_ENABLED": "true",
        "AI_SYSTEM_PROVIDER_NAME": "ollama",
        "AI_OLLAMA_ENABLED": "true",
        "OLLAMA_BASE_URL": ollama_url.strip(),
        "OPENAI_BASE_URL": openai_url,
        "OPENAI_SYSTEM_API_KEY": "",
        "OPENAI_DEFAULT_MODEL": models["text"],
        "OPENAI_TTS_MODEL": models["tts"],
        "OPENAI_STT_MODEL": models["stt"],
        "OPENAI_IMAGE_MODEL": models["image"],
        "OPENAI_VIDEO_MODEL": models["video"],
    }


def write_env_file(path: Path, env_map: Dict[str, str]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    lines = [
        "# Generated by scripts/mnema-local-ai.py",
        "# Local-only Mnema AI runtime settings",
    ]
    for key, value in env_map.items():
        lines.append(f"{key}={value}")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def print_models(models: Dict[str, str]) -> None:
    print("\nSelected models:")
    for key in ["text", "vision", "stt", "tts", "image", "video"]:
        print(f"  {key:>6}: {models[key]}")


def detect_gpu_hint() -> str:
    if not command_exists("nvidia-smi"):
        return "GPU detect: nvidia-smi not found (NVIDIA GPU not detected)."
    proc = subprocess.run(  # noqa: S603
        ["nvidia-smi", "--query-gpu=name", "--format=csv,noheader"],
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        text=True,
    )
    names = [line.strip() for line in proc.stdout.splitlines() if line.strip()]
    if not names:
        return "GPU detect: NVIDIA runtime not available."
    return f"GPU detect: {', '.join(names)}"


def list_presets() -> None:
    payload = {}
    for preset_id, preset in sorted(PRESETS.items()):
        payload[preset_id] = {
            "name": preset.name,
            "description": preset.description,
            "models": preset.models,
        }
    print(json.dumps(payload, ensure_ascii=True, indent=2))


def setup(args: argparse.Namespace) -> int:
    if not command_exists("ollama"):
        print("[error] 'ollama' command not found. Install Ollama first: https://ollama.com/download")
        return 2

    preset_id = args.preset
    if not args.non_interactive:
        preset_id = pick_preset_interactive(args.preset)
    preset = PRESETS[preset_id]
    models = apply_model_overrides(preset.models, args)
    if not args.non_interactive:
        models = prompt_model_overrides(models)

    print(f"\nPreset: {preset_id} ({preset.name})")
    print(detect_gpu_hint())
    print_models(models)

    if not ollama_running(args.ollama_url):
        if args.no_start_ollama:
            print(f"[error] Ollama is not reachable on {args.ollama_url}")
            return 3
        print(f"\nStarting Ollama service for {args.ollama_url} ...")
        start_ollama_service()
        if not wait_for_ollama(args.ollama_url):
            print("[error] Ollama did not become healthy in time.")
            return 4
    else:
        print(f"\nOllama is already healthy on {args.ollama_url}")

    if not args.skip_pull:
        failed = pull_models(models)
        if failed:
            print(f"\n[warn] Some models failed to pull: {', '.join(failed)}")
            print("       Check model names or switch to custom FastAPI runtime.")

    runtime_base_url = args.runtime_base_url.strip()
    if args.fastapi_cmd.strip():
        print("\nStarting custom FastAPI runtime ...")
        start_fastapi(args.fastapi_cmd.strip())
        if not wait_for_http(args.fastapi_health_url):
            print(f"[error] FastAPI health check failed: {args.fastapi_health_url}")
            return 5
        print(f"FastAPI runtime is healthy: {args.fastapi_health_url}")
        if not runtime_base_url:
            runtime_base_url = args.fastapi_base_url.strip()

    env_map = build_env(models, runtime_base_url, args.ollama_url)
    write_env_file(args.env_file, env_map)
    print(f"\nEnv file generated: {args.env_file}")

    if args.print_env:
        print("\n--- env preview ---")
        for key, value in env_map.items():
            print(f"{key}={value}")

    print("\nUse these vars when starting AI service (example):")
    print("  SPRING_PROFILES_ACTIVE=selfhost-local $(cat <env-file>) ./gradlew :services:ai:bootRun")
    print("Replace <env-file> with the generated path and adapt command per OS shell.")
    return 0


def main() -> int:
    args = parse_args()
    if args.command == "list-presets":
        list_presets()
        return 0
    if args.command == "setup":
        return setup(args)
    return 1


if __name__ == "__main__":
    sys.exit(main())
