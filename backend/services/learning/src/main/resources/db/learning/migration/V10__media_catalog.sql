-- Media identity is separate from physical bytes. Every authorization path starts at an asset.
CREATE TABLE app_learning.media_blob (
    blob_id UUID PRIMARY KEY,
    sha256 BYTEA NOT NULL CHECK (octet_length(sha256) = 32),
    byte_length BIGINT NOT NULL CHECK (byte_length > 0),
    mime_type TEXT NOT NULL CHECK (octet_length(mime_type) BETWEEN 3 AND 127),
    object_key TEXT NOT NULL UNIQUE CHECK (octet_length(object_key) BETWEEN 1 AND 512),
    verified_at TIMESTAMPTZ NOT NULL CHECK (isfinite(verified_at)),
    UNIQUE (sha256, byte_length)
);

CREATE TABLE app_learning.media_asset (
    asset_id UUID PRIMARY KEY,
    owner_id UUID NOT NULL,
    upload_intent_id UUID NOT NULL,
    origin TEXT NOT NULL CHECK (origin IN ('upload', 'recording', 'import')),
    generation BIGINT NOT NULL DEFAULT 0 CHECK (generation >= 0),
    state TEXT NOT NULL DEFAULT 'PENDING_UPLOAD' CHECK (state IN
        ('PENDING_UPLOAD', 'VERIFYING', 'PROCESSING', 'READY', 'FAILED_RETRYABLE', 'REJECTED', 'DELETED')),
    source_blob_id UUID REFERENCES app_learning.media_blob(blob_id),
    owner_hold_until TIMESTAMPTZ CHECK (owner_hold_until IS NULL OR isfinite(owner_hold_until)),
    created_at TIMESTAMPTZ NOT NULL CHECK (isfinite(created_at)),
    updated_at TIMESTAMPTZ NOT NULL CHECK (isfinite(updated_at) AND updated_at >= created_at),
    UNIQUE (asset_id, owner_id),
    UNIQUE (owner_id, upload_intent_id),
    CHECK (state <> 'READY' OR (source_blob_id IS NOT NULL AND owner_hold_until IS NOT NULL)),
    CHECK (state <> 'PENDING_UPLOAD' OR source_blob_id IS NULL)
);
CREATE INDEX media_asset_owner_state ON app_learning.media_asset(owner_id, state, created_at DESC);
CREATE INDEX media_asset_owner_hold ON app_learning.media_asset(owner_hold_until)
    WHERE state = 'READY';

CREATE TABLE app_learning.media_variant (
    variant_id UUID PRIMARY KEY,
    asset_id UUID NOT NULL REFERENCES app_learning.media_asset(asset_id),
    asset_generation BIGINT NOT NULL CHECK (asset_generation >= 0),
    purpose TEXT NOT NULL CHECK (purpose IN ('playback', 'poster', 'thumbnail', 'waveform')),
    profile TEXT NOT NULL CHECK (octet_length(profile) BETWEEN 1 AND 64
        AND profile ~ '^[a-z][a-z0-9_]*$'),
    blob_id UUID NOT NULL REFERENCES app_learning.media_blob(blob_id),
    width INTEGER CHECK (width BETWEEN 1 AND 32768),
    height INTEGER CHECK (height BETWEEN 1 AND 32768),
    duration_ms BIGINT CHECK (duration_ms >= 0),
    created_at TIMESTAMPTZ NOT NULL CHECK (isfinite(created_at)),
    UNIQUE (asset_id, asset_generation, profile),
    CHECK ((width IS NULL) = (height IS NULL))
);
CREATE INDEX media_variant_blob ON app_learning.media_variant(blob_id);

ALTER TABLE app_learning.item_revision ADD CONSTRAINT item_revision_media_owner
    UNIQUE (deck_id, member_key, revision_id, owner_id);
ALTER TABLE app_learning.editing_draft ADD CONSTRAINT editing_draft_media_owner
    UNIQUE (draft_id, owner_id);

CREATE TABLE app_learning.content_media_ref (
    deck_id UUID NOT NULL,
    member_key UUID NOT NULL,
    revision_id UUID NOT NULL,
    node_id UUID NOT NULL,
    owner_id UUID NOT NULL,
    asset_id UUID NOT NULL,
    PRIMARY KEY (deck_id, member_key, revision_id, node_id),
    FOREIGN KEY (deck_id, member_key, revision_id, owner_id)
        REFERENCES app_learning.item_revision(deck_id, member_key, revision_id, owner_id),
    FOREIGN KEY (asset_id, owner_id)
        REFERENCES app_learning.media_asset(asset_id, owner_id)
);
CREATE INDEX content_media_ref_asset ON app_learning.content_media_ref(asset_id);

CREATE TABLE app_learning.draft_media_ref (
    draft_id UUID NOT NULL,
    node_id UUID NOT NULL,
    owner_id UUID NOT NULL,
    asset_id UUID NOT NULL,
    PRIMARY KEY (draft_id, node_id),
    FOREIGN KEY (draft_id, owner_id)
        REFERENCES app_learning.editing_draft(draft_id, owner_id) ON DELETE CASCADE,
    FOREIGN KEY (asset_id, owner_id)
        REFERENCES app_learning.media_asset(asset_id, owner_id)
);
CREATE INDEX draft_media_ref_asset ON app_learning.draft_media_ref(asset_id);

CREATE FUNCTION app_learning.media_asset_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF (OLD.asset_id, OLD.owner_id, OLD.upload_intent_id, OLD.origin, OLD.created_at)
        IS DISTINCT FROM (NEW.asset_id, NEW.owner_id, NEW.upload_intent_id, NEW.origin, NEW.created_at)
       OR NEW.generation < OLD.generation OR NEW.generation - OLD.generation > 1
       OR OLD.state = 'DELETED'
       OR (OLD.state = 'READY' AND (NEW.generation <> OLD.generation
           OR NEW.source_blob_id IS DISTINCT FROM OLD.source_blob_id
           OR NEW.state NOT IN ('READY', 'DELETED')))
       OR (NEW.generation > OLD.generation AND NEW.state <> 'PENDING_UPLOAD') THEN
        RAISE EXCEPTION 'Invalid media asset identity or generation' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER media_asset_guard BEFORE UPDATE ON app_learning.media_asset
    FOR EACH ROW EXECUTE FUNCTION app_learning.media_asset_guard();

CREATE FUNCTION app_learning.media_blob_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Immutable media blob' USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER media_blob_guard BEFORE UPDATE ON app_learning.media_blob
    FOR EACH ROW EXECUTE FUNCTION app_learning.media_blob_guard();

CREATE FUNCTION app_learning.media_variant_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Immutable media variant' USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER media_variant_guard BEFORE UPDATE ON app_learning.media_variant
    FOR EACH ROW EXECUTE FUNCTION app_learning.media_variant_guard();

CREATE FUNCTION app_learning.content_media_ref_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Immutable published media reference' USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER content_media_ref_guard BEFORE UPDATE ON app_learning.content_media_ref
    FOR EACH ROW EXECUTE FUNCTION app_learning.content_media_ref_guard();
