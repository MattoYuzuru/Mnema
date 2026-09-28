-- A physical object key is never reused after its producing upload/worker attempt.
-- This ledger records derived PUT intent before S3 I/O and discovers sealed sources
-- and cataloged keys during scans. Two complete root checks, separated by an epoch
-- and a grace interval, precede a durable deletion claim.
CREATE TABLE app_learning.media_gc_object (
    object_key TEXT PRIMARY KEY CHECK (octet_length(object_key) BETWEEN 1 AND 512),
    origin TEXT NOT NULL CHECK (origin IN ('derived','source','catalog')),
    asset_id UUID,
    asset_generation BIGINT CHECK (asset_generation IS NULL OR asset_generation >= 0),
    processing_token UUID,
    created_at TIMESTAMPTZ NOT NULL CHECK (isfinite(created_at)),
    state TEXT NOT NULL DEFAULT 'TRACKED' CHECK (state IN ('TRACKED','FIRST','SECOND','DELETING','DELETED')),
    first_scan_at TIMESTAMPTZ CHECK (first_scan_at IS NULL OR isfinite(first_scan_at)),
    first_scan_epoch BIGINT CHECK (first_scan_epoch IS NULL OR first_scan_epoch >= 0),
    second_scan_at TIMESTAMPTZ CHECK (second_scan_at IS NULL OR isfinite(second_scan_at)),
    delete_token UUID,
    lease_until TIMESTAMPTZ CHECK (lease_until IS NULL OR isfinite(lease_until)),
    next_attempt_at TIMESTAMPTZ CHECK (next_attempt_at IS NULL OR isfinite(next_attempt_at)),
    delete_attempts INTEGER NOT NULL DEFAULT 0 CHECK (delete_attempts BETWEEN 0 AND 1000000),
    last_error_code TEXT CHECK (last_error_code IS NULL OR
        (octet_length(last_error_code) BETWEEN 1 AND 64
         AND last_error_code ~ '^[a-z][a-z0-9_]*$')),
    deleted_at TIMESTAMPTZ CHECK (deleted_at IS NULL OR isfinite(deleted_at)),
    updated_at TIMESTAMPTZ NOT NULL CHECK (isfinite(updated_at) AND updated_at >= created_at),
    CHECK ((origin = 'catalog') OR (asset_id IS NOT NULL AND asset_generation IS NOT NULL)),
    CHECK (processing_token IS NULL OR origin = 'derived'),
    CHECK ((state = 'TRACKED' AND first_scan_at IS NULL AND second_scan_at IS NULL)
        OR (state <> 'TRACKED' AND first_scan_at IS NOT NULL)),
    CHECK (state <> 'DELETING' OR (delete_token IS NOT NULL AND lease_until IS NOT NULL)),
    CHECK (state = 'DELETING' OR (delete_token IS NULL AND lease_until IS NULL)),
    CHECK (state <> 'DELETED' OR deleted_at IS NOT NULL)
);
CREATE INDEX media_gc_object_scan ON app_learning.media_gc_object(object_key)
    WHERE state IN ('TRACKED','FIRST','SECOND');
CREATE INDEX media_gc_object_due ON app_learning.media_gc_object(first_scan_at,object_key)
    WHERE state IN ('SECOND','DELETING');

CREATE TABLE app_learning.media_gc_scan_cursor (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    last_key TEXT NOT NULL DEFAULT '',
    epoch BIGINT NOT NULL DEFAULT 0 CHECK (epoch >= 0),
    updated_at TIMESTAMPTZ NOT NULL CHECK (isfinite(updated_at))
);
INSERT INTO app_learning.media_gc_scan_cursor(singleton,last_key,epoch,updated_at)
VALUES (TRUE,'',0,CURRENT_TIMESTAMP);

-- A manifest creator locks the blob FOR SHARE. GC locks the same row FOR UPDATE
-- before changing its object to DELETING. New pins reset an earlier scan.
CREATE FUNCTION app_learning.media_gc_manifest_pin_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE key_value TEXT;
DECLARE object_state TEXT;
BEGIN
    SELECT object_key INTO key_value FROM app_learning.media_blob
        WHERE blob_id = NEW.blob_id FOR SHARE;
    SELECT state INTO object_state FROM app_learning.media_gc_object
        WHERE object_key = key_value FOR UPDATE;
    IF object_state IN ('DELETING','DELETED') THEN
        RAISE EXCEPTION 'Media blob is being reclaimed' USING ERRCODE = '23514';
    END IF;
    UPDATE app_learning.media_gc_object SET state='TRACKED',first_scan_at=NULL,
        first_scan_epoch=NULL,second_scan_at=NULL,next_attempt_at=NULL,
        updated_at=CURRENT_TIMESTAMP WHERE object_key=key_value AND state IN ('FIRST','SECOND');
    RETURN NEW;
END;
$$;
CREATE TRIGGER media_gc_manifest_pin_guard BEFORE INSERT ON app_learning.media_manifest_blob_ref
    FOR EACH ROW EXECUTE FUNCTION app_learning.media_gc_manifest_pin_guard();

-- Keep tombstones immutable to clients while allowing GC to sever a dead physical
-- blob reference. A DELETED asset can never become READY again.
CREATE OR REPLACE FUNCTION app_learning.media_asset_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF (OLD.asset_id, OLD.owner_id, OLD.upload_intent_id, OLD.origin, OLD.created_at)
        IS DISTINCT FROM (NEW.asset_id, NEW.owner_id, NEW.upload_intent_id, NEW.origin, NEW.created_at)
       OR NEW.generation < OLD.generation OR NEW.generation - OLD.generation > 1
       OR (OLD.state = 'DELETED' AND
           (NEW.state <> 'DELETED' OR NEW.source_blob_id IS NOT NULL
            OR NEW.owner_hold_until IS DISTINCT FROM OLD.owner_hold_until))
       OR (OLD.state = 'READY' AND (NEW.generation <> OLD.generation
           OR NEW.source_blob_id IS DISTINCT FROM OLD.source_blob_id
           OR NEW.state NOT IN ('READY', 'DELETED')))
       OR (NEW.generation > OLD.generation AND NEW.state <> 'PENDING_UPLOAD') THEN
        RAISE EXCEPTION 'Invalid media asset identity or generation' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
