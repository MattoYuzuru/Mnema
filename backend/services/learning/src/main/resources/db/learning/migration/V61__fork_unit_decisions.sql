-- Updates/3 (#442): the update plan of a copy (community decks architecture sections 5, 7 and 12).
--
-- fork_unit_decision: what the owner of a copy has said about units of the AUTHOR's updates that the plan offered. One row per unit the learner touched, never one per unit of
-- the deck, so the state of a copy is O(units touched) whatever the number of versions or copies (architecture 12). The plan (Updates/3) READS it; the apply command
-- (Updates/4) writes it through the combined publication's final writer, in the same transaction that moves the heads.
--   unit_kind / unit_key   MATERIAL (member key), EXERCISE (exercise id), OBJECTIVE (objective id), METADATA and ORDER (both keyed by the copy's own deck id: they are single units).
--   decision               KEEP_MINE     the learner kept his version (a conflict, a fix he declined, or a unit the author removed and he kept);
--                          DECLINE_NEW   he declined a unit the author added;
--                          KEEP_DELETED  he had deleted the unit himself, the author changed it, and he keeps it deleted;
--                          PENDING       he decided nothing yet: the unit is still on offer and keeps its OWN old base (see below).
--   resolved_upstream_*    the author's state the row covers: the lineage revision of the unit (for METADATA and ORDER the SOURCE's deck revision) or, with
--                          resolved_upstream_absent, "the author had removed the unit". For KEEP_MINE/DECLINE_NEW/KEEP_DELETED it is the state the answer was given to; the plan hides
--                          the unit while the author's state is still that one and asks again, as ASK_AGAIN, when the author changes it. For PENDING it is the author's state that was
--                          OFFERED: it lets the plan evaluate a pending unit the author has not touched since, without a search of the manifest by key (nothing here is "resolved").
--   pending_base_*         PENDING only: the unit's base, the state of the unit in the copy's base revision before the apply moved the base, so that a safe change taken
--                          already is not offered twice while a unit left for later still compares the author with the old state. NULL alone would be ambiguous ("not set" or "the
--                          unit did not exist at that base"), so "absent at the base" is its own column, pending_base_absent, and the shape CHECK demands exactly one of the two.
--   The same explicit pair is used for resolved_upstream_absent.
-- Base rule (the clarification of the architecture): an apply moves deck_fork.base_revision_id to the plan's published revision P and records a PENDING row for every unit that
-- was listed and that the learner neither accepted nor declined. The badge "an update exists" is then (base <> P) OR (some PENDING row exists). The base rule needs the
-- deck_fork transition guard below to let base_revision_id move, only forwards.
SET LOCAL lock_timeout = '5s';

CREATE TABLE app_learning.fork_unit_decision (
    deck_id UUID NOT NULL,
    reuse_scope_id UUID NOT NULL,
    unit_kind TEXT NOT NULL CHECK (unit_kind IN ('MATERIAL', 'EXERCISE', 'OBJECTIVE', 'METADATA', 'ORDER')),
    unit_key UUID NOT NULL,
    decision TEXT NOT NULL CHECK (decision IN ('KEEP_MINE', 'DECLINE_NEW', 'KEEP_DELETED', 'PENDING')),
    resolved_upstream_revision_id UUID,
    resolved_upstream_absent BOOLEAN NOT NULL DEFAULT FALSE,
    pending_base_revision_id UUID,
    pending_base_absent BOOLEAN NOT NULL DEFAULT FALSE,
    decided_at TIMESTAMPTZ NOT NULL CHECK (isfinite(decided_at)),
    PRIMARY KEY (deck_id, unit_kind, unit_key),
    -- the copy's deck and its immutable lineage scope; the copy relation itself: a row cannot outlive the deck_fork row it belongs to (the account purge deletes deck_fork first)
    CONSTRAINT fork_unit_decision_deck_fkey FOREIGN KEY (deck_id, reuse_scope_id) REFERENCES app_learning.deck(deck_id, reuse_scope_id),
    CONSTRAINT fork_unit_decision_fork_fkey FOREIGN KEY (deck_id) REFERENCES app_learning.deck_fork(deck_id) ON DELETE CASCADE,
    -- the deck-wide units are single units named by the copy itself
    CONSTRAINT fork_unit_decision_single_unit CHECK (unit_kind NOT IN ('METADATA', 'ORDER') OR unit_key = deck_id),
    -- only materials and exercises are added or deleted by the author (DECLINE_NEW, KEEP_DELETED, "the author removed it"); an objective may have no head in the copy yet ("absent" base),
    -- the deck-wide units always have both sides
    CONSTRAINT fork_unit_decision_added_deleted_kinds CHECK (decision NOT IN ('DECLINE_NEW', 'KEEP_DELETED') OR unit_kind IN ('MATERIAL', 'EXERCISE')),
    CONSTRAINT fork_unit_decision_author_absent_kinds CHECK (unit_kind IN ('MATERIAL', 'EXERCISE') OR NOT resolved_upstream_absent),
    CONSTRAINT fork_unit_decision_base_absent_kinds CHECK (unit_kind IN ('MATERIAL', 'EXERCISE', 'OBJECTIVE') OR NOT pending_base_absent),
    -- PENDING: a base (a revision or "absent") and the author's offered state (a revision or "absent"), each exactly one of the two; KEEP_MINE: no base and exactly one author
    -- state; DECLINE_NEW and KEEP_DELETED: no base and an author revision (the author did not remove the unit: he changed it or added it)
    CONSTRAINT fork_unit_decision_pending_shape CHECK (
        CASE WHEN decision = 'PENDING' THEN (pending_base_revision_id IS NULL) = pending_base_absent
             ELSE pending_base_revision_id IS NULL AND NOT pending_base_absent END),
    CONSTRAINT fork_unit_decision_resolved_shape CHECK (
        CASE WHEN decision IN ('PENDING', 'KEEP_MINE') THEN (resolved_upstream_revision_id IS NULL) = resolved_upstream_absent
             ELSE resolved_upstream_revision_id IS NOT NULL AND NOT resolved_upstream_absent END)
);

-- Updates/4 moves the base of a copy to the published revision it applied (the base rule above). Until now the transition guard of V57 kept the whole identity of the row
-- immutable, base_revision_id included. The new rule: the base moves only FORWARD (to a later revision of the same source, which the foreign key deck_fork_base_fkey also demands
-- to exist) and never past what the source has PUBLISHED (its sequence is at most that of the current published revision: a later draft is not a base), and only for a copy that
-- is materialized, attached and not deleted, before AND after the statement: a statement that moves the base and sets detached_at or released_at at once is refused.
-- Everything else of V57 is unchanged.
CREATE OR REPLACE FUNCTION app_learning.deck_fork_transition_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF (to_jsonb(OLD) - ARRAY['job_id', 'materialized_at', 'detached_at', 'released_at', 'base_revision_id'])
          IS DISTINCT FROM (to_jsonb(NEW) - ARRAY['job_id', 'materialized_at', 'detached_at', 'released_at', 'base_revision_id'])
       OR (OLD.materialized_at IS NOT NULL AND NEW.materialized_at IS DISTINCT FROM OLD.materialized_at)
       OR (OLD.detached_at IS NOT NULL AND NEW.detached_at IS DISTINCT FROM OLD.detached_at)
       OR (OLD.released_at IS NOT NULL AND NEW.released_at IS DISTINCT FROM OLD.released_at)
       OR (NEW.job_id <> OLD.job_id AND OLD.materialized_at IS NOT NULL)
       OR (NEW.base_revision_id <> OLD.base_revision_id AND NOT (
              OLD.materialized_at IS NOT NULL AND OLD.detached_at IS NULL AND OLD.released_at IS NULL
              AND NEW.materialized_at IS NOT NULL AND NEW.detached_at IS NULL AND NEW.released_at IS NULL
              AND EXISTS (SELECT 1 FROM app_learning.deck_revision before_base JOIN app_learning.deck_revision after_base ON after_base.deck_id = before_base.deck_id
                           WHERE before_base.deck_id = OLD.upstream_deck_id AND before_base.revision_id = OLD.base_revision_id
                             AND after_base.revision_id = NEW.base_revision_id AND after_base.sequence > before_base.sequence
                             AND after_base.sequence <= (SELECT published.sequence FROM app_learning.deck_publication p
                                                           JOIN app_learning.deck_revision published ON published.deck_id = p.deck_id AND published.revision_id = p.published_revision_id
                                                          WHERE p.deck_id = OLD.upstream_deck_id)))) THEN
        RAISE EXCEPTION 'Invalid deck copy transition' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
