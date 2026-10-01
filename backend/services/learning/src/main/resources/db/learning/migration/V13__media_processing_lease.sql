-- A sealed transfer can be processed more than once after crashes or transient
-- storage failures. The token fences a stale worker even when the asset generation
-- is unchanged. lease_until already belongs to this session and is free after seal.
ALTER TABLE app_learning.media_upload_session
    ADD COLUMN processing_token UUID,
    ADD COLUMN processing_attempts INTEGER NOT NULL DEFAULT 0
        CHECK (processing_attempts BETWEEN 0 AND 1000),
    ADD COLUMN processing_next_attempt_at TIMESTAMPTZ
        CHECK (processing_next_attempt_at IS NULL OR isfinite(processing_next_attempt_at)),
    ADD COLUMN processing_error_code TEXT
        CHECK (processing_error_code IS NULL OR
               (octet_length(processing_error_code) BETWEEN 1 AND 64
                AND processing_error_code ~ '^[a-z][a-z0-9_]*$')),
    ADD CONSTRAINT media_processing_token_lease
        CHECK (processing_token IS NULL OR (state = 'SEALED' AND lease_until IS NOT NULL));

CREATE INDEX media_processing_due ON app_learning.media_upload_session
    (processing_next_attempt_at, updated_at, session_id)
    WHERE state = 'SEALED';
