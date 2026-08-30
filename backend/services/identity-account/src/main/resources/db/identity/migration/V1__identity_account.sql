CREATE TABLE app_identity.account (
    account_id UUID PRIMARY KEY DEFAULT uuidv7(),
    email TEXT NOT NULL,
    normalized_email TEXT GENERATED ALWAYS AS (lower(btrim(email))) STORED,
    email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    profile_username TEXT,
    normalized_profile_username TEXT GENERATED ALWAYS AS (lower(btrim(profile_username))) STORED,
    display_name TEXT,
    bio TEXT,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    is_admin BOOLEAN NOT NULL DEFAULT FALSE,
    admin_granted_by UUID,
    admin_granted_at TIMESTAMPTZ,
    banned_by UUID,
    banned_at TIMESTAMPTZ,
    ban_reason VARCHAR(280),
    created_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp(),
    profile_created_at TIMESTAMPTZ,
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
    CONSTRAINT ck_account_profile_username CHECK (
        profile_username IS NULL
        OR (btrim(profile_username) <> '' AND char_length(btrim(profile_username)) <= 50)
    ),
    CONSTRAINT ck_account_display_name CHECK (
        display_name IS NULL
        OR (btrim(display_name) <> '' AND char_length(display_name) <= 200)
    ),
    CONSTRAINT ck_account_bio CHECK (
        bio IS NULL OR char_length(bio) <= 200
    ),
    CONSTRAINT ck_account_status CHECK (
        status IN ('ACTIVE', 'BANNED')
    ),
    CONSTRAINT ck_account_admin_metadata CHECK (
        (NOT is_admin AND admin_granted_by IS NULL AND admin_granted_at IS NULL)
        OR
        (is_admin AND (
            (admin_granted_by IS NULL AND admin_granted_at IS NULL)
            OR (admin_granted_by IS NOT NULL AND admin_granted_at IS NOT NULL)
        ))
    ),
    CONSTRAINT ck_account_ban_metadata CHECK (
        (status = 'ACTIVE'
            AND banned_by IS NULL
            AND banned_at IS NULL
            AND ban_reason IS NULL)
        OR
        (status = 'BANNED' AND banned_at IS NOT NULL)
    ),
    CONSTRAINT ck_account_admin_status CHECK (
        NOT is_admin OR status = 'ACTIVE'
    ),
    CONSTRAINT ck_account_moderation_actors CHECK (
        (admin_granted_by IS NULL OR admin_granted_by <> account_id)
        AND (banned_by IS NULL OR banned_by <> account_id)
    ),
    CONSTRAINT ck_account_timestamps CHECK (
        updated_at >= created_at
        AND (profile_created_at IS NULL OR profile_created_at >= created_at)
        AND (last_login_at IS NULL OR last_login_at >= created_at)
    ),
    CONSTRAINT ck_account_row_version CHECK (row_version >= 0)
);

ALTER TABLE app_identity.account
    ADD CONSTRAINT fk_account_admin_granted_by
        FOREIGN KEY (admin_granted_by) REFERENCES app_identity.account (account_id)
        ON DELETE RESTRICT,
    ADD CONSTRAINT fk_account_banned_by
        FOREIGN KEY (banned_by) REFERENCES app_identity.account (account_id)
        ON DELETE RESTRICT;

CREATE UNIQUE INDEX uq_account_normalized_profile_username
    ON app_identity.account (normalized_profile_username)
    WHERE normalized_profile_username IS NOT NULL;

CREATE INDEX ix_account_status
    ON app_identity.account (status, created_at, account_id);

CREATE TABLE app_identity.local_credential (
    account_id UUID PRIMARY KEY,
    login_name TEXT,
    normalized_login_name TEXT GENERATED ALWAYS AS (lower(btrim(login_name))) STORED,
    password_hash TEXT NOT NULL,
    failed_login_attempts INTEGER NOT NULL DEFAULT 0,
    locked_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT uq_local_credential_normalized_login UNIQUE (normalized_login_name),
    CONSTRAINT fk_local_credential_account
        FOREIGN KEY (account_id) REFERENCES app_identity.account (account_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_local_credential_login_name CHECK (
        login_name IS NULL
        OR (btrim(login_name) <> '' AND char_length(btrim(login_name)) <= 50)
    ),
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
    linked_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp(),
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
    CONSTRAINT ck_external_identity_timestamps CHECK (
        last_login_at IS NULL OR last_login_at >= linked_at
    )
);

CREATE INDEX ix_external_identity_account
    ON app_identity.external_identity (account_id, provider, identity_id);

CREATE TABLE app_identity.account_avatar (
    account_id UUID PRIMARY KEY,
    asset_id UUID NOT NULL UNIQUE,
    storage_key TEXT NOT NULL UNIQUE,
    content_type VARCHAR(127) NOT NULL,
    byte_size BIGINT NOT NULL,
    content_sha256 BYTEA NOT NULL,
    width INTEGER,
    height INTEGER,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_account_avatar_account
        FOREIGN KEY (account_id) REFERENCES app_identity.account (account_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_account_avatar_asset_id CHECK (
        asset_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND uuid_extract_version(asset_id) IS NOT NULL
        AND uuid_extract_version(asset_id) BETWEEN 1 AND 8
    ),
    CONSTRAINT ck_account_avatar_storage_key CHECK (
        btrim(storage_key) <> '' AND octet_length(storage_key) <= 1024
    ),
    CONSTRAINT ck_account_avatar_content_type CHECK (
        content_type IN ('image/jpeg', 'image/png', 'image/webp')
    ),
    CONSTRAINT ck_account_avatar_byte_size CHECK (
        byte_size BETWEEN 1 AND 10485760
    ),
    CONSTRAINT ck_account_avatar_checksum CHECK (
        octet_length(content_sha256) = 32
    ),
    CONSTRAINT ck_account_avatar_dimensions CHECK (
        (width IS NULL AND height IS NULL)
        OR
        (width BETWEEN 1 AND 1024 AND height BETWEEN 1 AND 1024)
    )
);
