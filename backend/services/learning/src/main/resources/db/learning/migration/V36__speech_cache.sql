-- Speech synthesis with a cache (#297, AI-09).
--
-- speech_cache: one row per synthesised clip, keyed by the SHA-256 of the canonical JSON {schema, normalized text, lang, provider, model, model version,
-- voice, format, take}. It has no account: the same text, language and voice is the same clip for everyone, so a hit gives the next owner a new asset on
-- the already verified blobs without a provider call. The text itself is never stored (only its hash is a part of the key).
--   PENDING: one step holds the lease (lease_token, lease_until) and is synthesising; another waits for READY or takes over after the lease.
--   READY: verified = {sourceBlobId, variants[{purpose, profile, blobId, width, height, durationMs}]} of the first asset that passed the media
--     pipeline; blob_ids lists every blob of it, which the media GC treats as a root (see MediaGcRepository) until the entry is evicted.
-- A failed synthesis or a rejected clip deletes its PENDING row, so the next step starts clean.
CREATE TABLE app_learning.speech_cache (
    cache_key BYTEA PRIMARY KEY CHECK (octet_length(cache_key) = 32),
    provider TEXT NOT NULL CHECK (provider ~ '^[a-z][a-z0-9_-]{0,31}$'),
    model TEXT NOT NULL CHECK (char_length(model) BETWEEN 1 AND 100),
    voice TEXT NOT NULL CHECK (char_length(voice) BETWEEN 1 AND 40),
    lang TEXT NOT NULL CHECK (char_length(lang) BETWEEN 1 AND 35),
    take INTEGER NOT NULL CHECK (take >= 0),
    state TEXT NOT NULL CHECK (state IN ('PENDING', 'READY')),
    lease_token UUID,
    lease_until TIMESTAMPTZ CHECK (lease_until IS NULL OR isfinite(lease_until)),
    verified JSONB CHECK (verified IS NULL OR jsonb_typeof(verified) = 'object'),
    blob_ids UUID[] NOT NULL DEFAULT '{}',
    duration_ms BIGINT CHECK (duration_ms IS NULL OR duration_ms >= 0),
    byte_length BIGINT CHECK (byte_length IS NULL OR byte_length > 0),
    created_at TIMESTAMPTZ NOT NULL CHECK (isfinite(created_at)),
    last_used_at TIMESTAMPTZ NOT NULL CHECK (isfinite(last_used_at)),
    CHECK ((state = 'PENDING' AND lease_token IS NOT NULL AND lease_until IS NOT NULL AND verified IS NULL)
        OR (state = 'READY' AND lease_token IS NULL AND verified IS NOT NULL AND cardinality(blob_ids) >= 1))
);
CREATE INDEX speech_cache_used ON app_learning.speech_cache(last_used_at);
CREATE INDEX speech_cache_blobs ON app_learning.speech_cache USING GIN (blob_ids);

-- generation_media_clip: every clip a slot of a proposal has had (the first one and each redo), with the voice and take it was made with. The media
-- GC holds all of them (generation_media_hold) for the life of the session, so «Вернуть» can restore an earlier take, and a revert gives the slot back
-- the voice of the clip the restored revision uses. Rows go with their artifact.
CREATE TABLE app_learning.generation_media_clip (
    asset_id UUID PRIMARY KEY,
    artifact_id UUID NOT NULL,
    slot_key TEXT NOT NULL CHECK (slot_key ~ '^[a-z][a-z0-9_]{0,31}$'),
    session_id UUID NOT NULL,
    owner_id UUID NOT NULL,
    voice TEXT NOT NULL CHECK (voice IN ('female', 'male')),
    take INTEGER NOT NULL CHECK (take >= 0),
    created_at TIMESTAMPTZ NOT NULL CHECK (isfinite(created_at)),
    FOREIGN KEY (artifact_id, session_id, owner_id)
        REFERENCES app_learning.generation_artifact(artifact_id, session_id, owner_id) ON DELETE CASCADE,
    FOREIGN KEY (asset_id, owner_id) REFERENCES app_learning.media_asset(asset_id, owner_id)
);
CREATE INDEX generation_media_clip_slot ON app_learning.generation_media_clip(artifact_id, slot_key, created_at);

CREATE OR REPLACE VIEW app_learning.generation_media_hold AS
    SELECT artifact_id, session_id, owner_id, asset_id FROM app_learning.generation_media_ref
    UNION ALL
    SELECT artifact_id, session_id, owner_id, asset_id FROM app_learning.generation_media_candidate
    UNION ALL
    SELECT artifact_id, session_id, owner_id, asset_id FROM app_learning.generation_media_clip;
