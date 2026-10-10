-- The GC hold check and blob deletion look up assets by their source blob; the FK had no index (#461).
CREATE INDEX media_asset_source_blob ON app_learning.media_asset(source_blob_id) WHERE source_blob_id IS NOT NULL;
