CREATE TABLE app_identity.account (
    account_id UUID PRIMARY KEY DEFAULT uuidv7(),
    email TEXT NOT NULL,
    normalized_email TEXT GENERATED ALWAYS AS (lower(btrim(email))) STORED,
    email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    username TEXT,
    normalized_username TEXT GENERATED ALWAYS AS (lower(btrim(username))) STORED,
    display_name TEXT,
    bio TEXT,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    is_admin BOOLEAN NOT NULL DEFAULT FALSE,
    admin_granted_by UUID,
    admin_granted_at TIMESTAMPTZ,
    suspended_by UUID,
    suspended_at TIMESTAMPTZ,
    suspension_reason VARCHAR(280),
    created_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp(),
    last_login_at TIMESTAMPTZ,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_account_normalized_email UNIQUE (normalized_email),
    CONSTRAINT ck_account_id CHECK (
        account_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND uuid_extract_version(account_id) IS NOT NULL
        AND uuid_extract_version(account_id) BETWEEN 1 AND 8
    ),
    CONSTRAINT ck_account_email CHECK (
        btrim(email) <> '' AND octet_length(email) <= 320
    ),
    CONSTRAINT ck_account_username CHECK (
        username IS NULL OR btrim(username) <> ''
    ),
    CONSTRAINT ck_account_display_name CHECK (
        display_name IS NULL OR btrim(display_name) <> ''
    ),
    CONSTRAINT ck_account_status CHECK (
        status IN ('ACTIVE', 'SUSPENDED', 'PENDING_DELETION', 'PURGING', 'PURGED')
    ),
    CONSTRAINT ck_account_admin_metadata CHECK (
        (is_admin OR (admin_granted_by IS NULL AND admin_granted_at IS NULL))
        AND (admin_granted_by IS NULL OR admin_granted_at IS NOT NULL)
    ),
    CONSTRAINT ck_account_suspension_metadata CHECK (
        status <> 'SUSPENDED' OR suspended_at IS NOT NULL
    ),
    CONSTRAINT ck_account_timestamps CHECK (
        updated_at >= created_at AND (last_login_at IS NULL OR last_login_at >= created_at)
    ),
    CONSTRAINT ck_account_row_version CHECK (row_version >= 0)
);

ALTER TABLE app_identity.account
    ADD CONSTRAINT fk_account_admin_granted_by
        FOREIGN KEY (admin_granted_by) REFERENCES app_identity.account (account_id)
        ON DELETE SET NULL,
    ADD CONSTRAINT fk_account_suspended_by
        FOREIGN KEY (suspended_by) REFERENCES app_identity.account (account_id)
        ON DELETE SET NULL;

CREATE UNIQUE INDEX uq_account_normalized_username
    ON app_identity.account (normalized_username)
    WHERE normalized_username IS NOT NULL;

CREATE INDEX ix_account_status
    ON app_identity.account (status, created_at, account_id);

CREATE TABLE app_identity.local_credential (
    account_id UUID PRIMARY KEY,
    password_hash TEXT NOT NULL,
    failed_login_attempts INTEGER NOT NULL DEFAULT 0,
    locked_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT fk_local_credential_account
        FOREIGN KEY (account_id) REFERENCES app_identity.account (account_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_local_credential_password_hash CHECK (
        btrim(password_hash) <> '' AND octet_length(password_hash) <= 4096
    ),
    CONSTRAINT ck_local_credential_failed_attempts CHECK (
        failed_login_attempts BETWEEN 0 AND 1000000
    ),
    CONSTRAINT ck_local_credential_timestamps CHECK (updated_at >= created_at)
);

CREATE TABLE app_identity.external_identity (
    identity_id UUID PRIMARY KEY DEFAULT uuidv7(),
    account_id UUID NOT NULL,
    provider VARCHAR(64) NOT NULL,
    provider_subject VARCHAR(255) NOT NULL,
    provider_email TEXT,
    normalized_provider_email TEXT GENERATED ALWAYS AS (lower(btrim(provider_email))) STORED,
    email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    display_name TEXT,
    picture_url TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp(),
    last_login_at TIMESTAMPTZ,
    CONSTRAINT uq_external_identity_provider_subject UNIQUE (provider, provider_subject),
    CONSTRAINT fk_external_identity_account
        FOREIGN KEY (account_id) REFERENCES app_identity.account (account_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_external_identity_id CHECK (
        identity_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND uuid_extract_version(identity_id) IS NOT NULL
        AND uuid_extract_version(identity_id) BETWEEN 1 AND 8
    ),
    CONSTRAINT ck_external_identity_provider CHECK (
        provider = lower(btrim(provider))
        AND provider ~ '^[a-z][a-z0-9._-]{0,63}$'
    ),
    CONSTRAINT ck_external_identity_subject CHECK (
        btrim(provider_subject) <> '' AND octet_length(provider_subject) <= 255
    ),
    CONSTRAINT ck_external_identity_email CHECK (
        provider_email IS NULL
        OR (btrim(provider_email) <> '' AND octet_length(provider_email) <= 320)
    ),
    CONSTRAINT ck_external_identity_verified_email CHECK (
        NOT email_verified OR provider_email IS NOT NULL
    ),
    CONSTRAINT ck_external_identity_display_name CHECK (
        display_name IS NULL OR btrim(display_name) <> ''
    ),
    CONSTRAINT ck_external_identity_timestamps CHECK (
        last_login_at IS NULL OR last_login_at >= created_at
    )
);

CREATE INDEX ix_external_identity_account
    ON app_identity.external_identity (account_id, provider, identity_id);

CREATE TABLE app_identity.account_avatar (
    account_id UUID PRIMARY KEY,
    asset_id UUID UNIQUE,
    storage_key TEXT UNIQUE,
    content_type VARCHAR(127),
    byte_size BIGINT,
    content_sha256 BYTEA,
    source_url TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT fk_account_avatar_account
        FOREIGN KEY (account_id) REFERENCES app_identity.account (account_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_account_avatar_source CHECK (
        asset_id IS NOT NULL OR (source_url IS NOT NULL AND btrim(source_url) <> '')
    ),
    CONSTRAINT ck_account_avatar_asset_id CHECK (
        asset_id IS NULL
        OR (
            asset_id <> '00000000-0000-0000-0000-000000000000'::uuid
            AND uuid_extract_version(asset_id) IS NOT NULL
            AND uuid_extract_version(asset_id) BETWEEN 1 AND 8
        )
    ),
    CONSTRAINT ck_account_avatar_asset_metadata CHECK (
        (asset_id IS NULL
            AND storage_key IS NULL
            AND content_type IS NULL
            AND byte_size IS NULL
            AND content_sha256 IS NULL)
        OR
        (asset_id IS NOT NULL
            AND storage_key IS NOT NULL
            AND btrim(storage_key) <> ''
            AND content_type IS NOT NULL
            AND btrim(content_type) <> ''
            AND byte_size IS NOT NULL
            AND byte_size >= 0
            AND content_sha256 IS NOT NULL
            AND octet_length(content_sha256) = 32)
    ),
    CONSTRAINT ck_account_avatar_source_url CHECK (
        source_url IS NULL OR (btrim(source_url) <> '' AND octet_length(source_url) <= 4096)
    ),
    CONSTRAINT ck_account_avatar_timestamps CHECK (updated_at >= created_at)
);
