-- Preserve immutable revisions and attempt evidence while removing owner-facing access.
ALTER TABLE app_learning.deck ADD COLUMN deleted_at TIMESTAMPTZ
    CHECK (deleted_at IS NULL OR isfinite(deleted_at));

CREATE OR REPLACE FUNCTION app_learning.deck_head_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.deleted_at IS NULL AND NEW.deleted_at IS NOT NULL
       AND (to_jsonb(OLD) - 'deleted_at') IS NOT DISTINCT FROM (to_jsonb(NEW) - 'deleted_at') THEN
        RETURN NEW;
    END IF;
    IF OLD.deleted_at IS NOT NULL
       OR (to_jsonb(OLD) - ARRAY['head_revision_id', 'row_version'])
          IS DISTINCT FROM (to_jsonb(NEW) - ARRAY['head_revision_id', 'row_version'])
       OR OLD.row_version = 9223372036854775807 OR NEW.row_version <> OLD.row_version + 1 THEN
        RAISE EXCEPTION 'Invalid deck head transition' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

-- A removal records the previous revision without inventing a new exercise revision.
ALTER TABLE app_learning.deck_exercise_change ALTER COLUMN revision_id DROP NOT NULL;
ALTER TABLE app_learning.deck_exercise_change ADD CONSTRAINT exercise_change_has_revision
    CHECK (revision_id IS NOT NULL OR previous_revision_id IS NOT NULL);

-- Removing a middle exercise shifts all later ordinals in one transactional update.
ALTER TABLE app_learning.deck_head_exercise DROP CONSTRAINT deck_head_exercise_deck_id_ordinal_key;
ALTER TABLE app_learning.deck_head_exercise ADD CONSTRAINT deck_head_exercise_deck_id_ordinal_key
    UNIQUE (deck_id, ordinal) DEFERRABLE INITIALLY DEFERRED;
