-- A transfer attempt is distinct from the stable media asset. Old generations remain
-- observable for cleanup but can never publish bytes into a newer generation.
CREATE TABLE app_learning.media_upload_session (
    session_id UUID PRIMARY KEY,
    asset_id UUID NOT NULL REFERENCES app_learning.media_asset(asset_id),
    owner_id UUID NOT NULL,
    generation BIGINT NOT NULL CHECK (generation >= 0),
    kind TEXT NOT NULL CHECK (kind IN ('image','audio','video')),
    declared_mime TEXT NOT NULL CHECK (octet_length(declared_mime) BETWEEN 3 AND 127),
    declared_length BIGINT NOT NULL CHECK (declared_length > 0),
    request_fingerprint BYTEA NOT NULL CHECK (octet_length(request_fingerprint) = 32),
    retry_command_id UUID,
    method TEXT NOT NULL CHECK (method IN ('SINGLE','MULTIPART')),
    staging_key TEXT NOT NULL UNIQUE CHECK (octet_length(staging_key) BETWEEN 1 AND 512),
    frozen_key TEXT NOT NULL UNIQUE CHECK (octet_length(frozen_key) BETWEEN 1 AND 512),
    storage_upload_id TEXT CHECK (storage_upload_id IS NULL OR octet_length(storage_upload_id) BETWEEN 1 AND 2048),
    state TEXT NOT NULL CHECK (state IN
        ('INITIATING','OPEN','FINALIZING','SEALED','ABORTING','ABORTED','EXPIRED','FAILED')),
    finalize_command_id UUID,
    copy_started_at TIMESTAMPTZ,
    issued_until TIMESTAMPTZ NOT NULL CHECK (isfinite(issued_until)),
    expires_at TIMESTAMPTZ NOT NULL CHECK (isfinite(expires_at)),
    lease_until TIMESTAMPTZ CHECK (lease_until IS NULL OR isfinite(lease_until)),
    cleanup_done_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL CHECK (isfinite(created_at)),
    updated_at TIMESTAMPTZ NOT NULL CHECK (isfinite(updated_at) AND updated_at >= created_at),
    UNIQUE (asset_id, generation),
    UNIQUE (asset_id, retry_command_id),
    FOREIGN KEY (asset_id, owner_id) REFERENCES app_learning.media_asset(asset_id, owner_id),
    CHECK ((method = 'SINGLE' AND storage_upload_id IS NULL)
        OR method = 'MULTIPART'),
    CHECK (state NOT IN ('OPEN','FINALIZING','SEALED') OR
        method = 'SINGLE' OR storage_upload_id IS NOT NULL),
    CHECK (state NOT IN ('FINALIZING','SEALED') OR finalize_command_id IS NOT NULL),
    CHECK (copy_started_at IS NULL OR method = 'SINGLE'),
    CHECK (expires_at > created_at AND issued_until >= created_at)
);
CREATE INDEX media_upload_owner_active ON app_learning.media_upload_session(owner_id, state)
    WHERE state IN ('INITIATING','OPEN','FINALIZING');
CREATE INDEX media_upload_cleanup ON app_learning.media_upload_session(expires_at, issued_until)
    WHERE cleanup_done_at IS NULL;
