-- Share/10 (#432): «Добавить к себе», the O(1) copy of a published deck and its background materialization (community decks architecture sections 5, 12 and 14).
--
-- deck_fork: one row per copy, written by the copy command in the same transaction as the copy's deck row, its revision 0 and the job that fills its heads.
--   owner_id / reuse_scope_id: the copier and the lineage (the source's scope); the composite foreign key ties both to the copy's deck row.
--   upstream_deck_id / upstream_code / base_revision_id: the source, the public code the copier used (his only way to read the source again; a rotated code
--     stops working, which is exactly how a lowered level cuts a copier off) and the PUBLISHED revision of the source the copy was made from. The BEFORE INSERT guard
--     below proves that the copy's revision 0 pins that revision's roots and that it still is the source's published revision, so a copy cannot be made from
--     anything the author did not publish.
--   job_id: the materialization job (no foreign key: finished jobs are purged by retention; an unfinished copy whose job is gone is reported as failed and re-enqueued by retry).
--   materialized_at: NULL until the job has written and verified all heads; the preparing guard of the API and the head guard below are keyed on it.
--   detached_at: «Отвязать от автора» (the owner's decision; updates, epic 2, stop).  auto_update: reserved for epic 2, always false in this task.
--   released_at: set when the copy is deleted (trigger below), so that "one live copy per (owner, source)" is a unique index that a deleted copy leaves.
-- Heads of a preparing copy (deck_head_item, deck_head_exercise, objective_head) may be written only by the job: the guard function compares the transaction-local
--   setting mnema.fork_materializing with the deck. The API refuses mutations of a preparing copy before they get here (DECK_PREPARING); this is the net under it.
SET LOCAL lock_timeout = '5s';

CREATE TABLE app_learning.deck_fork (
    deck_id UUID PRIMARY KEY,
    reuse_scope_id UUID NOT NULL,
    owner_id UUID NOT NULL,
    upstream_deck_id UUID NOT NULL,
    upstream_code TEXT NOT NULL CHECK (upstream_code ~ '^[1-9A-HJ-NP-Za-km-z]{10}$'),
    base_revision_id UUID NOT NULL,
    forked_at TIMESTAMPTZ NOT NULL CHECK (isfinite(forked_at)),
    job_id UUID NOT NULL,
    auto_update BOOLEAN NOT NULL DEFAULT FALSE,
    detached_at TIMESTAMPTZ CHECK (detached_at IS NULL OR isfinite(detached_at)),
    materialized_at TIMESTAMPTZ CHECK (materialized_at IS NULL OR isfinite(materialized_at)),
    released_at TIMESTAMPTZ CHECK (released_at IS NULL OR isfinite(released_at)),
    CHECK (deck_id <> upstream_deck_id),
    CONSTRAINT deck_fork_deck_fkey FOREIGN KEY (deck_id, reuse_scope_id, owner_id)
        REFERENCES app_learning.deck(deck_id, reuse_scope_id, owner_id),
    CONSTRAINT deck_fork_upstream_fkey FOREIGN KEY (upstream_deck_id, reuse_scope_id)
        REFERENCES app_learning.deck(deck_id, reuse_scope_id),
    CONSTRAINT deck_fork_base_fkey FOREIGN KEY (upstream_deck_id, base_revision_id)
        REFERENCES app_learning.deck_revision(deck_id, revision_id)
);

-- one live copy per (owner, source)
CREATE UNIQUE INDEX deck_fork_live ON app_learning.deck_fork(owner_id, upstream_deck_id) WHERE released_at IS NULL;
-- the cap of unfinished copies of one account (a bounded count)
CREATE INDEX deck_fork_unfinished ON app_learning.deck_fork(owner_id) WHERE materialized_at IS NULL AND released_at IS NULL;
-- the copies of a source (epic 2 fans an update out through it); also the index of the foreign key
CREATE INDEX deck_fork_upstream ON app_learning.deck_fork(upstream_deck_id);

-- A copy is made only from what its source PUBLISHED: its revision 0 pins exactly the roots and counts of base_revision_id, base_revision_id is the source's
-- published revision at this moment, and the copy's revision 0 is the first revision of the copy's own deck.
CREATE FUNCTION app_learning.deck_fork_genesis_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    genesis app_learning.deck_revision%ROWTYPE;
    base app_learning.deck_revision%ROWTYPE;
BEGIN
    SELECT * INTO genesis FROM app_learning.deck_revision WHERE deck_id = NEW.deck_id AND sequence = 0;
    SELECT * INTO base FROM app_learning.deck_revision WHERE deck_id = NEW.upstream_deck_id AND revision_id = NEW.base_revision_id;
    IF genesis.revision_id IS NULL OR base.revision_id IS NULL
       OR genesis.members_root_id <> base.members_root_id OR genesis.exercises_root_id <> base.exercises_root_id
       OR genesis.member_count <> base.member_count OR genesis.exercise_count <> base.exercise_count
       OR genesis.title <> base.title OR genesis.description <> base.description
       OR NOT EXISTS (SELECT 1 FROM app_learning.deck_publication p
                       WHERE p.deck_id = NEW.upstream_deck_id AND p.published_revision_id = NEW.base_revision_id) THEN
        RAISE EXCEPTION 'A copy is made from the published revision of its source' USING ERRCODE = '23514';
    END IF;
    IF NEW.materialized_at IS NOT NULL OR NEW.released_at IS NOT NULL THEN
        RAISE EXCEPTION 'A new copy is not materialized' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER deck_fork_genesis_guard BEFORE INSERT ON app_learning.deck_fork
    FOR EACH ROW EXECUTE FUNCTION app_learning.deck_fork_genesis_guard();

-- Identity and one-way transitions: the columns that name the copy never change, materialized_at and detached_at are set once, released_at is set once.
CREATE FUNCTION app_learning.deck_fork_transition_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF (to_jsonb(OLD) - ARRAY['job_id', 'materialized_at', 'detached_at', 'released_at'])
          IS DISTINCT FROM (to_jsonb(NEW) - ARRAY['job_id', 'materialized_at', 'detached_at', 'released_at'])
       OR (OLD.materialized_at IS NOT NULL AND NEW.materialized_at IS DISTINCT FROM OLD.materialized_at)
       OR (OLD.detached_at IS NOT NULL AND NEW.detached_at IS DISTINCT FROM OLD.detached_at)
       OR (OLD.released_at IS NOT NULL AND NEW.released_at IS DISTINCT FROM OLD.released_at)
       OR (NEW.job_id <> OLD.job_id AND OLD.materialized_at IS NOT NULL) THEN
        RAISE EXCEPTION 'Invalid deck copy transition' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER deck_fork_transition_guard BEFORE UPDATE ON app_learning.deck_fork
    FOR EACH ROW EXECUTE FUNCTION app_learning.deck_fork_transition_guard();

-- Deleting a copy (deck_head_guard allows exactly the tombstone transition) frees its (owner, source) slot.
CREATE FUNCTION app_learning.deck_fork_release() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    UPDATE app_learning.deck_fork SET released_at = statement_timestamp() WHERE deck_id = NEW.deck_id AND released_at IS NULL;
    RETURN NULL;
END;
$$;
CREATE TRIGGER deck_fork_release AFTER UPDATE OF deleted_at ON app_learning.deck
    FOR EACH ROW WHEN (OLD.deleted_at IS NULL AND NEW.deleted_at IS NOT NULL) EXECUTE FUNCTION app_learning.deck_fork_release();

-- The net under the API guard: the head of a copy that is not materialized yet moves only inside the materialization job (it sets the transaction-local
-- setting mnema.fork_materializing to the copy's deck id), and the deck's head revision does not advance at all (the job never creates a deck revision).
CREATE FUNCTION app_learning.deck_fork_preparing_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    target UUID;
BEGIN
    IF TG_OP = 'DELETE' THEN target := OLD.deck_id; ELSE target := NEW.deck_id; END IF;
    IF TG_TABLE_NAME <> 'deck' AND current_setting('mnema.fork_materializing', true) = target::text THEN
        IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
        RETURN NEW;
    END IF;
    IF EXISTS (SELECT 1 FROM app_learning.deck_fork WHERE deck_id = target AND materialized_at IS NULL) THEN
        RAISE EXCEPTION 'The deck copy is still being prepared' USING ERRCODE = '55000';
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER deck_fork_preparing_guard BEFORE UPDATE ON app_learning.deck
    FOR EACH ROW WHEN (OLD.head_revision_id IS DISTINCT FROM NEW.head_revision_id) EXECUTE FUNCTION app_learning.deck_fork_preparing_guard();
CREATE TRIGGER deck_fork_preparing_guard BEFORE INSERT OR UPDATE OR DELETE ON app_learning.deck_head_item
    FOR EACH ROW EXECUTE FUNCTION app_learning.deck_fork_preparing_guard();
CREATE TRIGGER deck_fork_preparing_guard BEFORE INSERT OR UPDATE OR DELETE ON app_learning.deck_head_exercise
    FOR EACH ROW EXECUTE FUNCTION app_learning.deck_fork_preparing_guard();
CREATE TRIGGER deck_fork_preparing_guard BEFORE INSERT OR UPDATE OR DELETE ON app_learning.objective_head
    FOR EACH ROW EXECUTE FUNCTION app_learning.deck_fork_preparing_guard();

-- The per-account sliding window on copy creations counts deck_fork rows by forked_at INCLUDING released ones (a deleted copy still spent the
-- account's allowance); deck_fork_live and deck_fork_unfinished are partial and cannot answer it.
CREATE INDEX deck_fork_owner_window ON app_learning.deck_fork(owner_id, forked_at DESC);
