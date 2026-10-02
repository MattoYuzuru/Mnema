-- Durable notification center (contracts/notifications). One row per owner in notification_cursor is the lock under
-- which `seq` is allocated, so a poller that has read seq N can never miss a notification that commits later with a
-- lower value (a global sequence is assigned before commit and would allow exactly that).
CREATE TABLE app_learning.notification_cursor (
    owner_id UUID PRIMARY KEY,
    last_seq BIGINT NOT NULL DEFAULT 0 CHECK (last_seq >= 0),
    read_upto BIGINT NOT NULL DEFAULT 0 CHECK (read_upto >= 0 AND read_upto <= last_seq)
);

CREATE TABLE app_learning.notification (
    notification_id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES app_learning.notification_cursor(owner_id),
    seq BIGINT NOT NULL CHECK (seq >= 1),
    -- The kind vocabulary lives in the application (a client ignores unknown kinds); only the shape is constrained.
    kind TEXT NOT NULL CHECK (kind ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    severity TEXT NOT NULL CHECK (severity IN ('INFO', 'WARNING', 'ERROR')),
    -- Identifiers, counts, enums and timestamps only: no prose and no personal data.
    params JSONB NOT NULL CHECK (jsonb_typeof(params) = 'object' AND octet_length(params::text) <= 4096),
    route TEXT NOT NULL CHECK (route IN ('WORKSHOP', 'DECK', 'PLANS', 'NONE')),
    dedupe_key TEXT NOT NULL CHECK (octet_length(dedupe_key) BETWEEN 1 AND 200),
    created_at TIMESTAMPTZ NOT NULL CHECK (isfinite(created_at)),
    dismissed_at TIMESTAMPTZ CHECK (dismissed_at IS NULL OR isfinite(dismissed_at)),
    expires_at TIMESTAMPTZ NOT NULL CHECK (isfinite(expires_at) AND expires_at > created_at),
    UNIQUE (owner_id, dedupe_key),
    UNIQUE (owner_id, seq)
);
-- Newest-first listing and ascending catch-up read only what is visible; the unique (owner_id, seq) index serves ranges.
CREATE INDEX notification_owner_visible ON app_learning.notification(owner_id, seq DESC)
    WHERE dismissed_at IS NULL;
CREATE INDEX notification_expiry ON app_learning.notification(expires_at);
