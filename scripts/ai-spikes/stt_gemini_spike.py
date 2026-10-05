#!/usr/bin/env python3
"""Speech-to-text spike for AI-15 (#298): Gemini audio transcription over the Interactions API (stdlib only).

Transcribes known-text WAVs (the TTS spike of 2026-10-05: <model>-<lang>-<voice>.wav) with several Gemini models and reports latency p50/p95, the
character error rate per language and the cost per minute from the response usage. It also proves the container formats a browser recorder sends
(audio/mp4 AAC, audio/ogg Opus, audio/webm Opus) by converting one WAV with ffmpeg. A Chinese sample is synthesised first (the TTS spike has none).

    set -a; source .env; set +a
    python3 scripts/ai-spikes/stt_gemini_spike.py --samples .mnema/tts-spike-2026-10-05 --out /tmp/stt-spike [--reps 2]

The key is read from MNEMA_AI_GOOGLE_API_KEY and never printed. Prices are USD per million tokens from https://ai.google.dev/gemini-api/docs/pricing
(read 2026-10-05; the Flash prices double on 2027-01-01). Audio is 32 tokens per second (https://ai.google.dev/gemini-api/docs/audio) except for the
dedicated transcription model (25 tokens per second per its pricing note). Output includes thinking tokens.
"""
import argparse
import base64
import json
import os
import re
import statistics
import subprocess
import sys
import time
import unicodedata
import urllib.error
import urllib.request

ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/interactions"
PHRASES = {
    "ru": "Завтра я поеду в Токио, чтобы встретиться с друзьями и попробовать настоящий рамен.",
    "ru_word": "Здравствуйте",
    "es": "Hola, ¿cómo estás? Mañana vamos a la playa con mis amigos.",
    "ja": "明日、友達と東京へ行きます。本物のラーメンを食べたいです。",
    "ko": "내일 친구들과 서울에 가서 진짜 비빔밥을 먹고 싶어요.",
    "en": "Tomorrow I will travel to Tokyo to meet my friends.",
    "zh": "明天我要和朋友们一起去东京，想尝尝正宗的拉面。",
}
BCP47 = {"ru": "ru-RU", "ru_word": "ru-RU", "es": "es-419", "ja": "ja-JP", "ko": "ko-KR", "en": "en-US", "zh": "cmn-Hans-CN"}
# (model, mode, input $/M, output $/M)
VARIANTS = [
    ("gemini-3.5-transcribe", "hint", 2.00, 12.00),
    ("gemini-3.5-transcribe", "auto", 2.00, 12.00),
    ("gemini-3.5-flash-lite", "prompt", 0.30, 2.50),
    ("gemini-3.1-flash-lite", "prompt", 0.50, 1.50),
    ("gemini-3.8-flash", "prompt", 0.75, 3.75),
]
PROMPT = ("Transcribe this audio verbatim in the language that is spoken. Do not translate. Output only the transcript text. "
          "If there is no speech, output nothing.")
KEY = os.environ.get("MNEMA_AI_GOOGLE_API_KEY", "")


def post(body, timeout=60):
    request = urllib.request.Request(ENDPOINT, data=json.dumps(body).encode(), headers={"x-goog-api-key": KEY, "Content-Type": "application/json"})
    started = time.time()
    try:
        with urllib.request.urlopen(request, timeout=timeout) as reply:
            return time.time() - started, json.load(reply), None
    except urllib.error.HTTPError as error:
        wait = error.headers.get("Retry-After") if error.headers else None
        return time.time() - started, None, f"http_{error.code}" + (f":retry_after={wait}" if wait else "")
    except Exception as error:  # noqa: BLE001 - a spike reports every failure class, never a message
        return time.time() - started, None, type(error).__name__


def text_of(reply):
    return "".join(part.get("text", "") for step in reply.get("steps", []) if step.get("type") == "model_output"
                   for part in step.get("content", []) if part.get("type") == "text").strip()


RATE_LIMITED = {"hits": 0}


def transcribe(model, mode, audio_b64, mime, lang):
    """One transcription; a 429 is waited out (Retry-After or 15 s, up to 6 times) and counted, the wait is not part of the latency."""
    for _ in range(7):
        latency, reply, error = transcribe_once(model, mode, audio_b64, mime, lang)
        if not (error and error.startswith("http_429")):
            return latency, reply, error
        RATE_LIMITED["hits"] += 1
        wait = re.search(r"retry_after=(\d+)", error)
        time.sleep(min(int(wait.group(1)), 30) if wait else 15)
    return latency, reply, error


def transcribe_once(model, mode, audio_b64, mime, lang):
    audio = {"type": "audio", "data": audio_b64, "mime_type": mime}
    body = {"model": model}
    if model.endswith("transcribe"):
        body["input"] = [audio]
        config = {"language_codes": [BCP47[lang]]} if mode == "hint" else {}
        if config:
            body["generation_config"] = {"transcription_config": config}
    else:
        body["input"] = [{"type": "text", "text": PROMPT}, audio]
    return post(body)


def normalise(value):
    value = unicodedata.normalize("NFKC", value).lower()
    return "".join(ch for ch in value if not unicodedata.category(ch).startswith(("P", "Z", "C", "S")))


def edit_distance(a, b):
    previous = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        current = [i]
        for j, cb in enumerate(b, 1):
            current.append(min(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + (ca != cb)))
        previous = current
    return previous[-1]


def cer(reference, hypothesis):
    ref, hyp = normalise(reference), normalise(hypothesis)
    return edit_distance(ref, hyp) / max(1, len(ref))


def percentile(values, p):
    values = sorted(values)
    return values[min(len(values) - 1, round(p * (len(values) - 1)))]


def wav_seconds(path):
    import wave
    with wave.open(path) as handle:
        return handle.getnframes() / handle.getframerate()


def synthesise_zh(out):
    path = os.path.join(out, "zh-female.wav")
    if os.path.exists(path):
        return path
    body = {"model": "gemini-3.8-flash-tts", "input": [{"type": "user_input", "content": [{"type": "text", "text": PHRASES["zh"]}]}],
            "response_format": {"type": "audio", "mime_type": "audio/wav"}, "generation_config": {"speech_config": [{"voice": "Kore"}]}}
    _, reply, error = post(body)
    if error:
        print("zh synthesis failed:", error)
        return None
    for step in reply.get("steps", []):
        for part in step.get("content", []):
            if part.get("type") == "audio" and part.get("data"):
                open(path, "wb").write(base64.b64decode(part["data"]))
                return path
    return None


def samples(directory, out):
    found = []
    for name in sorted(os.listdir(directory)):
        match = re.search(r"-(ru_word|ru|es|ja|ko|en)-(female|male)\.wav$", name)
        if match:
            found.append((os.path.join(directory, name), match.group(1)))
    zh = synthesise_zh(out)
    if zh:
        found.append((zh, "zh"))
    return found


def formats(out, source):
    """One browser-like clip per container: mp4/AAC, ogg/Opus, webm/Opus; the same phrase, so the text is known."""
    results = []
    for mime, extension, codec in (("audio/mp4", "mp4", ["-c:a", "aac", "-b:a", "48k"]), ("audio/ogg", "ogg", ["-c:a", "libopus", "-b:a", "32k"]),
                                   ("audio/webm", "webm", ["-c:a", "libopus", "-b:a", "32k"])):
        path = os.path.join(out, f"format.{extension}")
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", source, *codec, path], check=True)
        data = open(path, "rb").read()
        for model in ("gemini-3.5-transcribe", "gemini-3.5-flash-lite"):
            for label in (mime, "audio/m4a") if extension == "mp4" else (mime,):
                latency, reply, error = transcribe(model, "hint", base64.b64encode(data).decode(), label, "ru")
                results.append({"model": model, "mime": label, "bytes": len(data), "latency_s": round(latency, 2), "error": error,
                                "cer": None if error else round(cer(PHRASES["ru"], text_of(reply)), 3)})
                if not error:
                    break
    return results


def repeated(path, lang, out):
    """The clip repeated until it is at least ten seconds (a phrase said again and again): a longer clip with a known text."""
    import wave
    with wave.open(path) as source:
        params, frames = source.getparams(), source.readframes(source.getnframes())
    count = max(2, -(-10 * params.framerate // max(1, params.nframes)))
    target = os.path.join(out, f"long-{lang}.wav")
    with wave.open(target, "wb") as sink:
        sink.setparams(params)
        sink.writeframes(frames * count)
    return target, (PHRASES[lang] + " ") * count, params.nframes * count / params.framerate


def long_run(args, clips):
    rows = []
    chosen = {}
    for path, lang in clips:
        if lang in ("ru", "es", "ja", "ko", "en", "zh") and lang not in chosen:
            chosen[lang] = path
    wanted = [name for name in args.only.split(",") if name]
    for model, mode in (("gemini-3.5-transcribe", "hint"), ("gemini-3.5-flash-lite", "prompt")):
        if wanted and model not in wanted:
            continue
        for lang, path in chosen.items():
            target, reference, seconds = repeated(path, lang, args.out)
            audio_b64 = base64.b64encode(open(target, "rb").read()).decode()
            for rep in range(args.reps):
                time.sleep(args.pause)
                latency, reply, error = transcribe(model, mode, audio_b64, "audio/wav", lang)
                rows.append({"model": model, "lang": lang, "audio_s": round(seconds, 1), "latency_s": round(latency, 2), "error": error,
                             "cer": None if error else round(cer(reference, text_of(reply)), 3)})
                print(rows[-1], flush=True)
    summary = {}
    for model in sorted({r["model"] for r in rows}):
        subset = [r for r in rows if r["model"] == model and not r["error"]]
        if subset:
            summary[model] = {"calls": len(subset), "audio_s_min": min(r["audio_s"] for r in subset), "audio_s_max": max(r["audio_s"] for r in subset),
                              "latency_p50_s": round(percentile([r["latency_s"] for r in subset], 0.5), 2),
                              "latency_p95_s": round(percentile([r["latency_s"] for r in subset], 0.95), 2),
                              "cer_mean": round(statistics.mean(r["cer"] for r in subset), 3)}
    with open(os.path.join(args.out, "results-long.json"), "w", encoding="utf-8") as handle:
        json.dump({"rate_limited_429": RATE_LIMITED["hits"], "summary": summary, "rows": rows}, handle, ensure_ascii=False, indent=1)
    print(json.dumps(summary, ensure_ascii=False, indent=1))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--samples", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--reps", type=int, default=2)
    parser.add_argument("--pause", type=float, default=0, help="seconds to wait between calls (the dedicated model's quota is tight)")
    parser.add_argument("--only", default="", help="comma separated models to run (default: all of the matrix)")
    parser.add_argument("--long", action="store_true", help="instead of the matrix: 10 to 15 second clips (a phrase repeated), the latency the issue asks for")
    args = parser.parse_args()
    if not KEY:
        sys.exit("MNEMA_AI_GOOGLE_API_KEY is not set")
    os.makedirs(args.out, exist_ok=True)
    clips = samples(args.samples, args.out)
    if args.long:
        return long_run(args, clips)
    rows = []
    for model, mode, price_in, price_out in VARIANTS:
        if args.only and model not in args.only.split(","):
            continue
        for path, lang in clips:
            audio_b64 = base64.b64encode(open(path, "rb").read()).decode()
            seconds = wav_seconds(path)
            for rep in range(args.reps):
                latency, reply, error = transcribe(model, mode, audio_b64, "audio/wav", lang)
                row = {"model": model, "mode": mode, "lang": lang, "file": os.path.basename(path), "rep": rep, "audio_s": round(seconds, 2),
                       "latency_s": round(latency, 2), "error": error}
                if reply:
                    usage = reply.get("usage", {})
                    out_tokens = usage.get("total_output_tokens", 0) + usage.get("total_thought_tokens", 0)
                    in_tokens = usage.get("total_input_tokens", 0)
                    hypothesis = text_of(reply)
                    row.update(text=hypothesis, cer=round(cer(PHRASES[lang], hypothesis), 3), in_tokens=in_tokens, out_tokens=out_tokens,
                               thought_tokens=usage.get("total_thought_tokens", 0),
                               cost_usd=(in_tokens * price_in + out_tokens * price_out) / 1e6)
                rows.append(row)
                print(model, mode, lang, row.get("cer"), row["latency_s"], error, flush=True)
    summary = {}
    for model, mode, _, _ in VARIANTS:
        subset = [r for r in rows if r["model"] == model and r["mode"] == mode and not r["error"]]
        if not subset:
            continue
        per_lang = {}
        for lang in sorted({r["lang"] for r in subset}):
            values = [r["cer"] for r in subset if r["lang"] == lang]
            per_lang[lang] = round(statistics.mean(values), 3)
        minutes = sum(r["audio_s"] for r in subset) / 60
        summary[f"{model}/{mode}"] = {
            "calls": len(subset), "errors": len([r for r in rows if r["model"] == model and r["mode"] == mode and r["error"]]),
            "latency_p50_s": round(percentile([r["latency_s"] for r in subset], 0.5), 2),
            "latency_p95_s": round(percentile([r["latency_s"] for r in subset], 0.95), 2),
            "cer_by_lang": per_lang, "cer_mean": round(statistics.mean(r["cer"] for r in subset), 3),
            "cost_usd_per_audio_minute": round(sum(r["cost_usd"] for r in subset) / minutes, 5),
            "audio_tokens_per_second_in": round(sum(r["in_tokens"] for r in subset) / (minutes * 60), 1)}
    ru = next((p for p, lang in clips if lang == "ru"), clips[0][0])
    report = {"rate_limited_429": RATE_LIMITED["hits"], "summary": summary, "formats": formats(args.out, ru), "rows": rows}
    with open(os.path.join(args.out, "results.json"), "w", encoding="utf-8") as handle:
        json.dump(report, handle, ensure_ascii=False, indent=1)
    print(json.dumps(summary, ensure_ascii=False, indent=1))
    print(json.dumps(report["formats"], ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
