# Local AI Model Matrix (Self-Hosted)

Матрица для self-host режима без внешних AI SaaS.

## Важно про Ollama

Ollama хорошо подходит для text/chat/vision и частично image, но:
- audio endpoint'ы OpenAI-совместимости (`/v1/audio/*`) не являются стабильной частью Ollama OpenAI API;
- `images/generations` в OpenAI-совместимости у Ollama помечен как experimental;
- video/gif генерация через Ollama как основной контур не рекомендуется.

## Рекомендуемое разделение по задачам

- Text/chat/vision: `Ollama`
- STT: `whisper.cpp server` (или иной локальный ASR сервис)
- TTS: `Piper` (или иной локальный TTS сервис)
- Image/video/gif: `ComfyUI` (+ video pipelines)
- orchestration: `local-ai-gateway` (единая OpenAI-compatible точка для Mnema)

## Gateway схема

- `OPENAI_BASE_URL` в Mnema -> `local-ai-gateway`
- `local-ai-gateway` маршрутизирует:
  - `/v1/responses`, `/v1/chat/completions` -> `Ollama`
  - `/v1/audio/*` -> `LOCAL_AUDIO_BASE_URL` (если задан), иначе fallback
  - `/v1/images/*` -> `LOCAL_IMAGE_BASE_URL` (если задан), иначе fallback
  - `/v1/videos*` -> `LOCAL_VIDEO_BASE_URL` (если задан), иначе fallback
- Runtime API в Mnema использует `/v1/models` и `/v1/audio/voices` gateway, чтобы UI показывал доступные модели и голоса.

## Text/Chat (Ollama)

### Базовый tier (8-16 GB RAM)
- `qwen3:4b`
- `llama3.2:3b`

### Баланс (16-24 GB RAM)
- `qwen3:8b`
- `llama3.1:8b`

### Качество (24+ GB RAM)
- `qwen3:14b`
- более крупные reasoning модели по возможностям хоста

## Практические профили железа

### RTX 2060 Mobile + 16 GB RAM
- text/chat: `qwen3:4b`, `qwen2.5:3b`, `llama3.2:3b`
- vision: `qwen2.5vl:3b`
- image: легкие SD1.5/SDXL turbo профили, небольшие batch
- video/gif: только короткие ролики в low-res, лучше выносить на более мощный узел

### RTX 3060 + 32 GB RAM
- text/chat: `qwen3:8b`, `llama3.1:8b`
- vision: `qwen2.5vl:7b`, `minicpm-v:8b`
- image: SDXL/FLUX с умеренными шагами и очередью
- video/gif: ограниченный low-mid tier, лучше `1` job за раз

## STT (локально)

- `whisper.cpp` small/medium для баланса скорости/качества
- large-v3 для качества (существенно выше требования)

## TTS (локально)

- `Piper` голоса для CPU-friendly режима
- более тяжелые neural TTS в отдельном GPU сервисе при необходимости

## Image

- SDXL/FLUX pipeline через ComfyUI
- для Ollama image generation использовать только как опцию с осторожностью

## Video / GIF

## Практика

- Video generation обычно самый тяжелый контур (VRAM/время выше image в разы).
- Для GIF обычно: сначала генерируют видео (mp4/webm), затем делают transcode в gif (доп. CPU/disk I/O).

## Минимальные рекомендации

- Эксперименты: 12-16 GB VRAM
- Комфорт: 16-24+ GB VRAM
- Production-like batch: 24+ GB VRAM и отдельная очередь задач

## Операционные рекомендации

- Запускать video/gif в отдельном worker/process и с жесткими лимитами очереди.
- Ограничивать concurrency (`1-2` video job на GPU).
- Ограничивать длительность (например 3-6s на пользователя по умолчанию).
- Для GIF хранить исходное видео и генерировать GIF on-demand, а не всегда заранее.

## Источники (официальные)

- Ollama API: https://docs.ollama.com/api
- Ollama OpenAI compatibility: https://docs.ollama.com/openai
- Ollama model library (Qwen): https://ollama.com/library/qwen3
- Speaches docs: https://speaches.ai/
- Speaches OpenAI-compatible API: https://speaches.ai/usage/python
- ComfyUI docs: https://docs.comfy.org/
- Hugging Face Diffusers text-to-video: https://huggingface.co/docs/diffusers/en/api/pipelines/text_to_video
- whisper.cpp: https://github.com/ggml-org/whisper.cpp
- Piper TTS: https://github.com/rhasspy/piper
