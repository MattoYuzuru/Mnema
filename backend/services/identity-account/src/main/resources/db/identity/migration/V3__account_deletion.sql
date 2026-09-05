ALTER TABLE app_identity.account
    ADD COLUMN deletion_state VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN deletion_generation BIGINT NOT NULL DEFAULT 0;

ALTER TABLE app_identity.account
    ALTER COLUMN email DROP NOT NULL,
    DROP CONSTRAINT ck_account_email,
    DROP CONSTRAINT ck_account_admin_status,
    ADD CONSTRAINT ck_account_email CHECK (
        (deletion_state = 'PURGED' AND email IS NULL)
        OR
        (deletion_state <> 'PURGED' AND email IS NOT NULL
            AND btrim(email) <> '' AND octet_length(email) <= 320)
    ),
    ADD CONSTRAINT ck_account_deletion_state CHECK (
        deletion_state IN ('ACTIVE', 'PENDING_DELETION', 'PURGING', 'PURGED')
    ),
    ADD CONSTRAINT ck_account_deletion_generation CHECK (deletion_generation >= 0),
    ADD CONSTRAINT uq_account_deletion_generation UNIQUE (account_id, deletion_generation),
    ADD CONSTRAINT ck_account_admin_status CHECK (
        NOT is_admin OR (status = 'ACTIVE' AND deletion_state = 'ACTIVE')
    ),
    ADD CONSTRAINT ck_account_purged_tombstone CHECK (
        deletion_state <> 'PURGED'
        OR (
            email IS NULL AND NOT email_verified
            AND profile_username IS NULL AND display_name IS NULL AND bio IS NULL
            AND NOT is_admin AND admin_granted_by IS NULL AND admin_granted_at IS NULL
            AND ban_reason IS NULL AND profile_created_at IS NULL AND last_login_at IS NULL
        )
    );

CREATE INDEX ix_account_deletion_state
    ON app_identity.account (deletion_state, account_id);

ALTER TABLE app_identity.account_avatar
    ADD COLUMN storage_version TEXT;

ALTER TABLE app_identity.avatar_cleanup
    ADD COLUMN storage_version TEXT,
    ADD COLUMN content_sha256 BYTEA,
    ADD CONSTRAINT ck_avatar_cleanup_checksum CHECK (
        content_sha256 IS NULL OR octet_length(content_sha256) = 32
    );

CREATE TABLE app_identity.account_deletion (
    account_id UUID PRIMARY KEY REFERENCES app_identity.account(account_id) ON DELETE RESTRICT,
    operation_id UUID NOT NULL UNIQUE DEFAULT uuidv7(),
    generation BIGINT NOT NULL,
    deletion_requested_at TIMESTAMPTZ NOT NULL,
    recoverable_until TIMESTAMPTZ NOT NULL,
    purge_after TIMESTAMPTZ NOT NULL,
    confirmation_hash CHAR(64) NOT NULL UNIQUE,
    confirmation_expires_at TIMESTAMPTZ NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    lease_owner UUID,
    lease_epoch BIGINT NOT NULL DEFAULT 0,
    lease_expires_at TIMESTAMPTZ,
    last_error_code VARCHAR(64),
    completed_at TIMESTAMPTZ,
    CONSTRAINT uq_account_deletion_operation_account_generation
        UNIQUE (operation_id, account_id, generation),
    CONSTRAINT fk_account_deletion_generation
        FOREIGN KEY (account_id, generation)
        REFERENCES app_identity.account(account_id, deletion_generation)
        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT ck_account_deletion_generation CHECK (generation > 0),
    CONSTRAINT ck_account_deletion_deadlines CHECK (
        recoverable_until >= deletion_requested_at AND purge_after >= recoverable_until
    ),
    CONSTRAINT ck_account_deletion_confirmation CHECK (
        confirmation_hash ~ '^[0-9a-f]{64}$' AND confirmation_expires_at > deletion_requested_at
    ),
    CONSTRAINT ck_account_deletion_attempts CHECK (attempt_count >= 0 AND lease_epoch >= 0),
    CONSTRAINT ck_account_deletion_lease CHECK (
        (lease_owner IS NULL AND lease_expires_at IS NULL)
        OR (lease_owner IS NOT NULL AND lease_expires_at IS NOT NULL)
    ),
    CONSTRAINT ck_account_deletion_error CHECK (
        last_error_code IS NULL OR last_error_code ~ '^[a-z][a-z0-9_]{0,63}$'
    )
);

CREATE INDEX ix_account_deletion_due
    ON app_identity.account_deletion (next_attempt_at, purge_after, operation_id)
    WHERE completed_at IS NULL;

CREATE TABLE app_identity.account_deletion_avatar (
    operation_id UUID NOT NULL,
    account_id UUID NOT NULL,
    generation BIGINT NOT NULL,
    asset_id UUID NOT NULL,
    storage_key TEXT NOT NULL,
    storage_version TEXT,
    content_sha256 BYTEA,
    source VARCHAR(16) NOT NULL,
    PRIMARY KEY (operation_id, storage_key),
    CONSTRAINT fk_account_deletion_avatar_operation
        FOREIGN KEY (operation_id, account_id, generation)
        REFERENCES app_identity.account_deletion(operation_id, account_id, generation) ON DELETE CASCADE,
    CONSTRAINT ck_account_deletion_avatar_key CHECK (
        storage_key = 'account-avatar/' || account_id::text || '/' || asset_id::text
    ),
    CONSTRAINT ck_account_deletion_avatar_checksum CHECK (
        content_sha256 IS NULL OR octet_length(content_sha256) = 32
    ),
    CONSTRAINT ck_account_deletion_avatar_source CHECK (source IN ('CURRENT', 'CLEANUP'))
);

CREATE TABLE app_identity.account_erasure_handoff (
    operation_id UUID PRIMARY KEY,
    account_id UUID NOT NULL,
    generation BIGINT NOT NULL,
    avatar_object_count INTEGER NOT NULL,
    avatar_manifest_sha256 BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_account_erasure_handoff_operation
        FOREIGN KEY (operation_id, account_id, generation)
        REFERENCES app_identity.account_deletion(operation_id, account_id, generation) ON DELETE RESTRICT,
    CONSTRAINT ck_account_erasure_handoff_generation CHECK (generation > 0),
    CONSTRAINT ck_account_erasure_handoff_manifest CHECK (
        avatar_object_count >= 0 AND octet_length(avatar_manifest_sha256) = 32
    )
);

CREATE TABLE app_identity.account_erasure_receipt (
    operation_id UUID NOT NULL REFERENCES app_identity.account_erasure_handoff(operation_id) ON DELETE RESTRICT,
    scope VARCHAR(64) NOT NULL,
    receipt_id UUID NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (operation_id, scope),
    CONSTRAINT uq_account_erasure_receipt_id UNIQUE (receipt_id),
    CONSTRAINT ck_account_erasure_receipt_scope CHECK (scope ~ '^[a-z][a-z0-9-]{0,63}$')
);
