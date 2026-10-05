# Speech input contract (`speech-v1`)

**Status: accepted** (AI-15, [#298](https://github.com/MattoYuzuru/Mnema/issues/298)).

Dictation and spoken answers (AI-15, #298; architecture [§9, §11, §13](../../docs/architecture/ai-generation-platform.md);
product [§3, §5](../../docs/product/ai-layer-2026-10.md)). A short recording becomes **text that the user sees and edits
before sending**; nothing is sent, graded or saved automatically. The recording is ephemeral; the transcript is ordinary
user input from then on (a Study answer carries `answerSource: SPEECH`, contracts/study).

## Capability and consent

- `GET /api/capabilities` → `speechToText {available, reason}`: the flag `learning.features.speech-to-text.enabled` **and** a
  configured transcription route (self-host container, Gemini through the egress proxy, or the Stub in local runs and CI).
  Reasons as for every capability: `DISABLED`, `PROVIDER_NOT_CONFIGURED`, `TEMPORARILY_UNAVAILABLE` (open circuit on every
  route entry or the global daily budget).
- Voice is personal data, so it needs a separate consent: `GET /api/speech-consent` →
  `{required: {version, processing}, accepted: {version, processing, acceptedAt} | null}` where `processing` is `RU` (self-host in
  Russia) or `ABROAD` (Gemini through the egress gateway; audio leaves Russia de-identified). `PUT /api/speech-consent
  {version, processing}` records it (idempotent; a stale `version`/`processing` is `409 SPEECH_CONSENT_OUTDATED`). The required
  version changes when the active route's processing region changes, so a move to a foreign route asks again.
  `DELETE /api/speech-consent` withdraws it. `version` is a string (`speech-2026-10`); `PUT` answers `200` with the `GET` body, `DELETE`
  answers `204` (also when nothing was accepted). The UI shows the disclosure on the first microphone press.

## Endpoints

| Method | Path | Body / query | Success |
|---|---|---|---|
| POST | `/api/speech-inputs` | raw audio body (`Content-Type` one of `audio/mp4`, `audio/mpeg`, `audio/ogg;codecs=opus`, `audio/webm;codecs=opus`, `audio/webm`), ≤ 2 MiB; headers `Idempotency-Key` (UUIDv4/v7) and `X-Audio-Duration-Ms` (1..60000, the recorder's measurement); query `purpose` = `COMPOSER` \| `EDIT` \| `CAPTURE` \| `STUDY_ANSWER`, optional `lang` (BCP 47 hint), optional `deckId` (owner's deck: its terms become recognition hints) | `202 {speechInputId, state: "QUEUED", pollAfterMs: 400, expiresAt}` |
| GET | `/api/speech-inputs/{id}` | — | `200 {speechInputId, state, text, seconds, lang, garbled, errorCode, expiresAt}` |
| DELETE | `/api/speech-inputs/{id}` | — | `204` (also for an absent one: idempotent) |

States: `QUEUED → TRANSCRIBING → DONE | FAILED`. `text` is set only when `DONE` (may be empty: then `errorCode` is
`NO_SPEECH` and the state is `FAILED`); `seconds` is the metered duration; `garbled` is true when the provider flags low
confidence (the AI grader then sends a spoken Study answer to self-check, contracts/study); `errorCode` when `FAILED`:
`UNAVAILABLE` (every route failed or the deadline passed — never the learner's fault), `NO_SPEECH`, `UNSUPPORTED_AUDIO` (the
provider could not decode it), `TOO_LONG` (the provider measured more than 60 s). The client polls every `pollAfterMs` (300–500
ms) while the tab is visible and stops at a terminal state; the server's deadline per input is `PT30S`.

Errors (problem+json, the platform's stable schema): `400 INVALID_REQUEST` (missing/invalid header or query, unsupported
`Content-Type`, empty body, duration out of range, foreign or unknown `deckId` is `404 RESOURCE_NOT_FOUND`), `413
PAYLOAD_TOO_LARGE` (> 2 MiB), `409 CAPABILITY_UNAVAILABLE` (`capability: speechToText`, reason), `409
SPEECH_CONSENT_REQUIRED` (no or outdated consent for the active processing region), `409 USAGE_LIMIT_REACHED` (`bucket:
STT`, `window: DAY | MONTH`, remaining and `renewsAt` as in contracts/usage), `429 RATE_LIMITED` with `Retry-After` (more than
20 inputs in 10 minutes per account), `409 IDEMPOTENCY_CONFLICT` (the key reused with another body). A repeat with the same key
and body returns the stored `202` with `Idempotency-Replayed: true`. `GET` of a foreign, expired or unknown id is `404`.

## Lifetime and privacy

- The audio bytes live only until transcription ends (success or failure) and are deleted then; the row (text, metadata)
  expires 15 minutes after creation (`expiresAt`) and is purged; nothing is kept for training or debugging.
- Only the de-identified audio and optional hints (deck terms, language) reach a provider; the provider user id is the HMAC
  user key. Logs carry ids, durations, sizes, outcome and route — never text or audio.
- Fair use: the STT bucket (seconds) of contracts/usage: Free 60 min/month and ≤10 min/day, Plus 300 (30), Pro 600 (60), Max
  velocity ≤120 min/day. Admission reserves the declared seconds, completion settles the metered seconds; a failed input is not
  counted. A global daily budget of the transcription capability turns the capability `TEMPORARILY_UNAVAILABLE`.

## Recording on the client

`AudioRecorder` (shared, extracted from the native media upload) prefers `audio/mp4`, then `audio/ogg;codecs=opus`, then
`audio/webm;codecs=opus` (`MediaRecorder.isTypeSupported`), stops at 60 s, shows elapsed time, and is cancellable. The
transcript is inserted at the caret of the field that started it and is never sent automatically.
