-- Licensed image search (#296, AI-10).
--
-- 1. media_asset.origin gains 'generated': an asset the server staged itself (a downloaded stock image, later generated media) and sent through the
--    same untrusted-upload verification as a browser upload.
-- 2. generation_media_candidate: the images the searches of one IMAGE slot found and staged (at most 12 per slot), with their attribution. The
--    chosen one is the asset the image node uses in the shown revision; the others stay selectable until the session ends.
-- 3. generation_media_hold: the one reachability view of the media GC for assets a proposal holds (the node holds and every candidate).
-- 4. image_search_cache: the 24-hour cache of source answers (Pixabay's terms), keyed by a hash, no account data.
-- 5. generation_provenance.media: {assetId, source, sourceId, license, sourcePageUrl} of each stock image a published artifact uses.

ALTER TABLE app_learning.media_asset DROP CONSTRAINT IF EXISTS media_asset_origin_check;
ALTER TABLE app_learning.media_asset ADD CONSTRAINT media_asset_origin_check
    CHECK (origin IN ('upload', 'recording', 'import', 'generated'));

CREATE TABLE app_learning.generation_media_candidate (
    candidate_id UUID PRIMARY KEY,
    artifact_id UUID NOT NULL,
    slot_key TEXT NOT NULL CHECK (slot_key ~ '^[a-z][a-z0-9_]{0,31}$'),
    session_id UUID NOT NULL,
    owner_id UUID NOT NULL,
    asset_id UUID NOT NULL,
    source TEXT NOT NULL CHECK (source IN ('PIXABAY', 'OPENVERSE', 'WIKIMEDIA', 'STUB')),
    source_id TEXT NOT NULL CHECK (char_length(source_id) BETWEEN 1 AND 100),
    title TEXT NOT NULL CHECK (char_length(title) <= 300),
    author TEXT NOT NULL CHECK (char_length(author) <= 200),
    license TEXT NOT NULL CHECK (char_length(license) BETWEEN 1 AND 100),
    license_url TEXT CHECK (license_url IS NULL OR (license_url LIKE 'https://%' AND char_length(license_url) <= 2000)),
    source_page_url TEXT NOT NULL CHECK (source_page_url LIKE 'https://%' AND char_length(source_page_url) <= 2000),
    share_alike BOOLEAN NOT NULL,
    width INTEGER NOT NULL CHECK (width BETWEEN 0 AND 32768),
    height INTEGER NOT NULL CHECK (height BETWEEN 0 AND 32768),
    state TEXT NOT NULL CHECK (state IN ('VERIFYING', 'READY', 'FAILED')),
    created_at TIMESTAMPTZ NOT NULL CHECK (isfinite(created_at)),
    UNIQUE (artifact_id, slot_key, source, source_id),
    UNIQUE (asset_id),
    FOREIGN KEY (artifact_id, session_id, owner_id)
        REFERENCES app_learning.generation_artifact(artifact_id, session_id, owner_id) ON DELETE CASCADE,
    FOREIGN KEY (asset_id, owner_id) REFERENCES app_learning.media_asset(asset_id, owner_id)
);
CREATE INDEX generation_media_candidate_slot ON app_learning.generation_media_candidate(artifact_id, slot_key, created_at);

CREATE VIEW app_learning.generation_media_hold AS
    SELECT artifact_id, session_id, owner_id, asset_id FROM app_learning.generation_media_ref
    UNION ALL
    SELECT artifact_id, session_id, owner_id, asset_id FROM app_learning.generation_media_candidate;

CREATE TABLE app_learning.image_search_cache (
    cache_key BYTEA PRIMARY KEY CHECK (octet_length(cache_key) = 32),
    response JSONB NOT NULL CHECK (jsonb_typeof(response) = 'array' AND octet_length(response::text) <= 262144),
    fetched_at TIMESTAMPTZ NOT NULL CHECK (isfinite(fetched_at))
);
CREATE INDEX image_search_cache_fetched ON app_learning.image_search_cache(fetched_at);

ALTER TABLE app_learning.generation_provenance
    ADD COLUMN media JSONB NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(media) = 'array');
