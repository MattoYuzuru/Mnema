-- #300: free intent parsing runs only on the worker, including in split api/worker topology.
-- The request and resolved context are ephemeral (at most the intent deadline), cleared on completion.
CREATE TABLE app_learning.generation_intent_request (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL,
    deck_id UUID NOT NULL,
    context JSONB,
    title TEXT,
    request TEXT CHECK (request IS NULL OR char_length(request) BETWEEN 1 AND 2000),
    state TEXT NOT NULL DEFAULT 'QUEUED' CHECK (state IN ('QUEUED','RUNNING','SUCCEEDED','FAILED')),
    result JSONB,
    error TEXT CHECK (error IS NULL OR error IN ('PROVIDER_NOT_CONFIGURED','TEMPORARILY_UNAVAILABLE')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP CHECK (isfinite(created_at)),
    deadline_at TIMESTAMPTZ NOT NULL CHECK (isfinite(deadline_at)),
    CHECK ((state IN ('QUEUED','RUNNING') AND context IS NOT NULL AND title IS NOT NULL AND request IS NOT NULL AND result IS NULL AND error IS NULL)
        OR (state='SUCCEEDED' AND context IS NULL AND title IS NULL AND request IS NULL AND result IS NOT NULL AND error IS NULL)
        OR (state='FAILED' AND context IS NULL AND title IS NULL AND request IS NULL AND result IS NULL AND error IS NOT NULL))
);
CREATE INDEX generation_intent_request_due ON app_learning.generation_intent_request(created_at,id) WHERE state='QUEUED';
CREATE INDEX generation_intent_request_expiry ON app_learning.generation_intent_request(deadline_at);
CREATE TRIGGER generation_intent_queued AFTER INSERT ON app_learning.generation_intent_request
    FOR EACH ROW EXECUTE FUNCTION app_learning.notify_work('mnema_generation_intents');
