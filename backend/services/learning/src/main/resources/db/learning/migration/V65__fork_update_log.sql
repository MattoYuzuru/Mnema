-- Updates/6 part B (#445): the fan-out of a publication to the copies that follow the source automatically, the «Что нового» log and the return of one unit
-- (community decks architecture sections 5, 7, 12 and 14).
--
-- 1. fork_update_log: one row per apply of a copy that CREATED a revision of the copy (manual or automatic), written by the final writer of the apply command in the
--    same transaction as the revision. It is what the «Что нового» screen and the badge-adjacent notice read: the unit lists come from the journals of that deck revision
--    (deck_item_change, deck_exercise_change) and from fork_update_log_objective, never from a copy of them here.
--      mode            AUTO (the background job of learning.updates.auto) or MANUAL (the owner's apply command)
--      upstream_revision_id  the source's published revision the apply moved the base to
--      applied_units / pending_units  the counts of the apply (what was taken, what was left for the owner), for the notice on the deck without a join
--      seen_at         NULL until the owner marked the entry seen; the notice shows the newest AUTO entry that is still unseen
--    It goes with the copy (deck_fork) and with the revision. At most 50 entries of a copy are kept (the final writer trims the older ones).
-- 2. fork_update_log_objective: the objective heads an apply moved. Objectives have no journal of their own, so «what changed» would otherwise not list them.
-- 3. fork_update_log_unseen_auto: the badge-adjacent notice is read with the owner's deck (a page of copies): it asks, per copy, for the newest unseen AUTO entry; the partial
--    index holds only those rows, so the probe is one entry whatever the history.
-- 4. fork_update_log_history: the history route, newest first.
-- 5. deck_fork_auto_fanout: the fan-out of a publication walks the live, attached, materialized copies of ONE source that follow it automatically, by copy id; the partial
--    index holds exactly those rows (deck_fork_upstream would read every copy of the source, auto or not).
SET LOCAL lock_timeout = '5s';

CREATE TABLE app_learning.fork_update_log (
    deck_id UUID NOT NULL,
    deck_revision_id UUID NOT NULL,
    upstream_revision_id UUID NOT NULL,
    mode TEXT NOT NULL CHECK (mode IN ('AUTO', 'MANUAL')),
    applied_units INTEGER NOT NULL CHECK (applied_units >= 0),
    pending_units INTEGER NOT NULL CHECK (pending_units >= 0),
    applied_at TIMESTAMPTZ NOT NULL CHECK (isfinite(applied_at)),
    seen_at TIMESTAMPTZ CHECK (seen_at IS NULL OR isfinite(seen_at)),
    PRIMARY KEY (deck_id, deck_revision_id),
    CONSTRAINT fork_update_log_fork_fkey FOREIGN KEY (deck_id) REFERENCES app_learning.deck_fork(deck_id) ON DELETE CASCADE,
    CONSTRAINT fork_update_log_revision_fkey FOREIGN KEY (deck_id, deck_revision_id) REFERENCES app_learning.deck_revision(deck_id, revision_id) ON DELETE CASCADE
);

CREATE INDEX fork_update_log_history ON app_learning.fork_update_log (deck_id, applied_at DESC, deck_revision_id DESC);
CREATE INDEX fork_update_log_unseen_auto ON app_learning.fork_update_log (deck_id, applied_at DESC) WHERE mode = 'AUTO' AND seen_at IS NULL;

CREATE TABLE app_learning.fork_update_log_objective (
    deck_id UUID NOT NULL,
    deck_revision_id UUID NOT NULL,
    objective_id UUID NOT NULL,
    previous_revision_id UUID,
    revision_id UUID NOT NULL,
    PRIMARY KEY (deck_id, deck_revision_id, objective_id),
    CONSTRAINT fork_update_log_objective_log_fkey FOREIGN KEY (deck_id, deck_revision_id)
        REFERENCES app_learning.fork_update_log(deck_id, deck_revision_id) ON DELETE CASCADE
);

CREATE INDEX deck_fork_auto_fanout ON app_learning.deck_fork (upstream_deck_id, deck_id)
    WHERE auto_update AND materialized_at IS NOT NULL AND detached_at IS NULL AND released_at IS NULL;
