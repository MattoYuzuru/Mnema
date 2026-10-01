-- Offline download snapshots pin a deck revision and verified blob identities.
-- Document bytes and ETag never change. Expired blob holds may be released by GC;
-- old snapshots remain audit records and are no longer served after expiry.
ALTER TABLE app_learning.deck_revision ADD CONSTRAINT deck_revision_manifest_owner
    UNIQUE (deck_id, revision_id, owner_id);

CREATE TABLE app_learning.media_manifest (
    manifest_id UUID PRIMARY KEY,
    deck_id UUID NOT NULL,
    deck_revision_id UUID NOT NULL,
    owner_id UUID NOT NULL,
    version BIGINT NOT NULL CHECK (version > 0),
    content_sha256 BYTEA NOT NULL CHECK (octet_length(content_sha256) = 32),
    etag TEXT NOT NULL CHECK (octet_length(etag) = 66),
    document_json TEXT NOT NULL CHECK (octet_length(document_json) BETWEEN 2 AND 8388608
        AND jsonb_typeof(document_json::jsonb) = 'object'),
    created_at TIMESTAMPTZ NOT NULL CHECK (isfinite(created_at)),
    expires_at TIMESTAMPTZ NOT NULL CHECK (isfinite(expires_at) AND expires_at > created_at),
    UNIQUE (deck_id, version),
    FOREIGN KEY (deck_id, deck_revision_id, owner_id)
        REFERENCES app_learning.deck_revision(deck_id, revision_id, owner_id)
);
CREATE INDEX media_manifest_owner_deck ON app_learning.media_manifest(owner_id, deck_id, version DESC);

CREATE TABLE app_learning.media_manifest_blob_ref (
    manifest_id UUID NOT NULL REFERENCES app_learning.media_manifest(manifest_id),
    blob_id UUID NOT NULL REFERENCES app_learning.media_blob(blob_id),
    PRIMARY KEY (manifest_id, blob_id)
);
CREATE INDEX media_manifest_blob_hold ON app_learning.media_manifest_blob_ref(blob_id);

-- Preserve the logical route to bytes for as long as an immutable snapshot can be installed.
-- Blob pins alone keep S3 bytes alive but cannot authorize a former asset after its owner hold.
CREATE TABLE app_learning.media_manifest_asset_ref (
    manifest_id UUID NOT NULL REFERENCES app_learning.media_manifest(manifest_id),
    asset_id UUID NOT NULL REFERENCES app_learning.media_asset(asset_id),
    PRIMARY KEY (manifest_id, asset_id)
);
CREATE INDEX media_manifest_asset_hold ON app_learning.media_manifest_asset_ref(asset_id);

CREATE FUNCTION app_learning.media_manifest_immutable_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Immutable media manifest' USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER media_manifest_immutable BEFORE UPDATE OR DELETE ON app_learning.media_manifest
    FOR EACH ROW EXECUTE FUNCTION app_learning.media_manifest_immutable_guard();
