-- Speech to text (#298, AI-15): dictation and spoken answers.
--
-- ai_provider_call: the capability STT joins the journal (the daily budget sums it like every other capability).
--
-- speech_input: one row per recording the learner sent to be turned into text. It is ephemeral: the text is shown to the learner for editing and is
--   ordinary user input from then on, the row expires after 15 minutes (expires_at) and is purged; nothing is kept for training or debugging.
--   QUEUED -> TRANSCRIBING -> DONE | FAILED. The row never holds audio; it holds the metadata the worker needs and the answer.
--   lease/deadline: deadline_at is created_at + the input's deadline (PT30S); a row that is not terminal after it is failed UNAVAILABLE by the sweeper
--   (a crashed worker included). claim_token fences a worker whose row was failed meanwhile: its late write changes nothing.
--   (owner_id, idempotency_key) is unique and body_hash is the SHA-256 of the audio and every request member: the same key and body replays the 202,
--   the same key with another body is IDEMPOTENCY_CONFLICT.
--   consent_region: the region the account's consent had to cover when the input was admitted; the worker re-reads the consent when it claims the row
--   and fails the input (UNAVAILABLE, audio deleted) when the consent is gone, outdated or no longer covers it.
-- speech_input_audio: the recording itself (at most 2 MiB), deleted as soon as the transcription ends (success or failure) and with its input.
--   script is the answer a harness scripted with X-Stub-Transcript; it is only ever stored while the Stub is active.
-- speech_consent: the one consent of an account to process its voice, with the processing region it was given for (RU or ABROAD).
-- speech_input_use: one row per admitted input, counted over the last ten minutes under an advisory lock of the account (the rate limit of 20); like
--   generation_intent_use it holds only the account and the time.
ALTER TABLE app_learning.ai_provider_call DROP CONSTRAINT ai_provider_call_capability_check;
-- NOT VALID first, then VALIDATE: the new constraint takes only a short lock, the scan of the (large) journal runs with writers admitted
ALTER TABLE app_learning.ai_provider_call ADD CONSTRAINT ai_provider_call_capability_check
    CHECK (capability IN ('TEXT', 'ASSESS', 'TTS', 'STT', 'IMAGE', 'IMAGE_SEARCH', 'VIDEO', 'SEARCH')) NOT VALID;
ALTER TABLE app_learning.ai_provider_call VALIDATE CONSTRAINT ai_provider_call_capability_check;

CREATE TABLE app_learning.speech_input (
    speech_input_id UUID PRIMARY KEY,
    owner_id UUID NOT NULL,
    purpose TEXT NOT NULL CHECK (purpose IN ('COMPOSER', 'EDIT', 'CAPTURE', 'STUDY_ANSWER')),
    state TEXT NOT NULL CHECK (state IN ('QUEUED', 'TRANSCRIBING', 'DONE', 'FAILED')),
    lang_hint TEXT CHECK (lang_hint IS NULL OR char_length(lang_hint) BETWEEN 1 AND 35),
    deck_id UUID,
    mime_type TEXT NOT NULL CHECK (mime_type IN ('audio/mp4', 'audio/mpeg', 'audio/ogg', 'audio/webm')),
    byte_length INTEGER NOT NULL CHECK (byte_length BETWEEN 1 AND 2097152),
    declared_ms INTEGER NOT NULL CHECK (declared_ms BETWEEN 1 AND 60000),
    seconds INTEGER CHECK (seconds IS NULL OR seconds BETWEEN 1 AND 61),
    text TEXT CHECK (text IS NULL OR char_length(text) <= 20000),
    lang TEXT CHECK (lang IS NULL OR char_length(lang) BETWEEN 1 AND 35),
    garbled BOOLEAN NOT NULL DEFAULT FALSE,
    error_code TEXT CHECK (error_code IS NULL OR error_code IN ('UNAVAILABLE', 'NO_SPEECH', 'UNSUPPORTED_AUDIO', 'TOO_LONG')),
    idempotency_key UUID NOT NULL,
    body_hash BYTEA NOT NULL CHECK (octet_length(body_hash) = 32),
    consent_region TEXT NOT NULL CHECK (consent_region IN ('RU', 'ABROAD')),
    claim_token UUID,
    created_at TIMESTAMPTZ NOT NULL CHECK (isfinite(created_at)),
    deadline_at TIMESTAMPTZ NOT NULL CHECK (isfinite(deadline_at)),
    expires_at TIMESTAMPTZ NOT NULL CHECK (isfinite(expires_at)),
    UNIQUE (owner_id, idempotency_key),
    CHECK ((state = 'DONE' AND text IS NOT NULL AND seconds IS NOT NULL AND error_code IS NULL)
        OR (state = 'FAILED' AND error_code IS NOT NULL)
        OR (state IN ('QUEUED', 'TRANSCRIBING') AND text IS NULL AND error_code IS NULL))
);
-- the worker claims the oldest QUEUED row; the sweeper looks for overdue and expired rows
CREATE INDEX speech_input_queue ON app_learning.speech_input(created_at) WHERE state = 'QUEUED';
CREATE INDEX speech_input_open ON app_learning.speech_input(deadline_at) WHERE state IN ('QUEUED', 'TRANSCRIBING');
CREATE INDEX speech_input_expiry ON app_learning.speech_input(expires_at);
-- the seconds an account has admitted and not counted yet (fair-use admission)
CREATE INDEX speech_input_owner_open ON app_learning.speech_input(owner_id) WHERE state IN ('QUEUED', 'TRANSCRIBING');

CREATE TABLE app_learning.speech_input_audio (
    speech_input_id UUID PRIMARY KEY REFERENCES app_learning.speech_input(speech_input_id) ON DELETE CASCADE,
    audio BYTEA NOT NULL CHECK (octet_length(audio) BETWEEN 1 AND 2097152),
    script TEXT CHECK (script IS NULL OR char_length(script) <= 2000)
);

CREATE TABLE app_learning.speech_consent (
    owner_id UUID PRIMARY KEY,
    version TEXT NOT NULL CHECK (version ~ '^[a-z0-9-]{1,40}$'),
    processing TEXT NOT NULL CHECK (processing IN ('RU', 'ABROAD')),
    accepted_at TIMESTAMPTZ NOT NULL CHECK (isfinite(accepted_at))
);

CREATE TABLE app_learning.speech_input_use (
    owner_id UUID NOT NULL,
    used_at TIMESTAMPTZ NOT NULL CHECK (isfinite(used_at))
);
CREATE INDEX speech_input_use_owner ON app_learning.speech_input_use(owner_id, used_at DESC);
CREATE INDEX speech_input_use_age ON app_learning.speech_input_use(used_at);
