CREATE SCHEMA IF NOT EXISTS app_media;

DO
$$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'media_kind') THEN
            CREATE TYPE media_kind AS ENUM (
                'avatar',
                'deck_icon',
                'card_image',
                'card_audio',
                'card_video'
            );
        END IF;
    END
$$ LANGUAGE plpgsql;

DO
$$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'media_status') THEN
            CREATE TYPE media_status AS ENUM (
                'pending',
                'ready',
                'rejected',
                'deleted'
            );
        END IF;
    END
$$ LANGUAGE plpgsql;

DO
$$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'upload_status') THEN
            CREATE TYPE upload_status AS ENUM (
                'initiated',
                'completed',
                'failed',
                'aborted'
            );
        END IF;
    END
$$ LANGUAGE plpgsql;

CREATE TABLE IF NOT EXISTS app_media.media_assets
(
    media_id           UUID         NOT NULL DEFAULT gen_random_uuid(),
    owner_user_id      UUID         NOT NULL,
    kind               media_kind   NOT NULL,
    status             media_status NOT NULL DEFAULT 'pending',
    storage_key        TEXT         NOT NULL,
    mime_type          TEXT         NOT NULL,
    size_bytes         BIGINT,
    duration_seconds   INT,
    width              INT,
    height             INT,
    original_file_name TEXT,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ,
    deleted_at         TIMESTAMPTZ,
    PRIMARY KEY (media_id)
);

CREATE INDEX IF NOT EXISTS ix_media_assets_owner ON app_media.media_assets (owner_user_id);
CREATE INDEX IF NOT EXISTS ix_media_assets_kind ON app_media.media_assets (kind);
CREATE INDEX IF NOT EXISTS ix_media_assets_status ON app_media.media_assets (status);

CREATE TABLE IF NOT EXISTS app_media.media_uploads
(
    upload_id           UUID          NOT NULL DEFAULT gen_random_uuid(),
    media_id            UUID          NOT NULL,
    status              upload_status NOT NULL DEFAULT 'initiated',
    expected_size_bytes BIGINT        NOT NULL,
    expected_mime_type  TEXT          NOT NULL,
    multipart           BOOLEAN       NOT NULL DEFAULT false,
    parts_count         INT,
    part_size_bytes     BIGINT,
    s3_upload_id        TEXT,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    expires_at          TIMESTAMPTZ,
    completed_at        TIMESTAMPTZ,
    error_message       TEXT,
    PRIMARY KEY (upload_id),
    CONSTRAINT fk_media_uploads_media_id
        FOREIGN KEY (media_id) REFERENCES app_media.media_assets (media_id)
);

CREATE INDEX IF NOT EXISTS ix_media_uploads_media_id ON app_media.media_uploads (media_id);
CREATE INDEX IF NOT EXISTS ix_media_uploads_status ON app_media.media_uploads (status);
