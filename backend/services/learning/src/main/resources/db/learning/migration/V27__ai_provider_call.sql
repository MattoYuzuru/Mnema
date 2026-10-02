-- Journal of provider calls (architecture §4, §9). One row per provider call: an intent row is committed before the
-- call and its outcome afterwards, each in its own short transaction, so no transaction is open during the HTTP call and
-- a crash leaves a visible PENDING row. The row carries identifiers, counts, hashes and enums only: never a prompt, a
-- response, a key or personal data. Rows are append-only: the outcome moves once from PENDING, nothing else changes, and
-- the 90-day retention worker is the only deleter. It is also the source of the global daily budget sum.
CREATE TABLE app_learning.ai_provider_call (
    call_id UUID PRIMARY KEY,
    -- Generation steps arrive with AI-04; the column is nullable until then.
    step_id UUID,
    attempt INTEGER NOT NULL DEFAULT 1 CHECK (attempt >= 1),
    capability TEXT NOT NULL CHECK (capability IN ('TEXT', 'ASSESS', 'TTS', 'IMAGE', 'IMAGE_SEARCH', 'VIDEO', 'SEARCH')),
    provider TEXT NOT NULL CHECK (provider ~ '^[a-z][a-z0-9_-]{0,31}$'),
    model TEXT NOT NULL CHECK (octet_length(model) BETWEEN 1 AND 100),
    -- SHA-256 of the request shape, for correlating repeats; the text itself is never stored.
    request_hash TEXT NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    prompt_tokens INTEGER NOT NULL DEFAULT 0 CHECK (prompt_tokens >= 0),
    cache_hit_tokens INTEGER NOT NULL DEFAULT 0 CHECK (cache_hit_tokens >= 0),
    cache_miss_tokens INTEGER NOT NULL DEFAULT 0 CHECK (cache_miss_tokens >= 0),
    completion_tokens INTEGER NOT NULL DEFAULT 0 CHECK (completion_tokens >= 0),
    -- Micro-US-dollars from the configured price table.
    cost_micros BIGINT NOT NULL DEFAULT 0 CHECK (cost_micros >= 0),
    provider_request_id TEXT CHECK (provider_request_id IS NULL OR octet_length(provider_request_id) <= 200),
    outcome TEXT NOT NULL DEFAULT 'PENDING' CHECK (outcome IN ('PENDING', 'OK', 'RATE_LIMITED', 'TRANSIENT', 'TIMEOUT',
        'INVALID_OUTPUT', 'REFUSAL', 'NOT_CONFIGURED')),
    latency_ms INTEGER CHECK (latency_ms IS NULL OR latency_ms >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP CHECK (isfinite(created_at))
);
-- The daily budget sums one capability from the start of the day.
CREATE INDEX ai_provider_call_budget ON app_learning.ai_provider_call(capability, created_at);
CREATE INDEX ai_provider_call_step ON app_learning.ai_provider_call(step_id, attempt) WHERE step_id IS NOT NULL;
-- Retention scans by age only.
CREATE INDEX ai_provider_call_expiry ON app_learning.ai_provider_call(created_at);

CREATE FUNCTION app_learning.ai_provider_call_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.outcome <> 'PENDING'
       OR (OLD.call_id, OLD.step_id, OLD.attempt, OLD.capability, OLD.provider, OLD.model, OLD.request_hash, OLD.created_at)
          IS DISTINCT FROM (NEW.call_id, NEW.step_id, NEW.attempt, NEW.capability, NEW.provider, NEW.model,
          NEW.request_hash, NEW.created_at) THEN
        RAISE EXCEPTION 'ai_provider_call is append-only' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER ai_provider_call_guard BEFORE UPDATE ON app_learning.ai_provider_call
    FOR EACH ROW EXECUTE FUNCTION app_learning.ai_provider_call_guard();
