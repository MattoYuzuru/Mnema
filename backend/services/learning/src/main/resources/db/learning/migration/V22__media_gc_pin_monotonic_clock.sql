-- A pin reset writes updated_at from the database clock. After a small backwards clock step
-- CURRENT_TIMESTAMP can precede the row's created_at/updated_at, which would violate
-- updated_at >= created_at and fail the manifest insert. Keep the value monotonic; the
-- constraint is unchanged.
CREATE OR REPLACE FUNCTION app_learning.media_gc_manifest_pin_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
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
        updated_at=GREATEST(CURRENT_TIMESTAMP,updated_at)
        WHERE object_key=key_value AND state IN ('FIRST','SECOND');
    RETURN NEW;
END;
$$;
