-- Updates/6 part A (#445): the badge «Есть обновление», the re-link of a copy after its source rotated the code, and the auto-update setting
-- (community decks architecture sections 5, 7 and 14).
--
-- 1. deck_fork_transition_guard gets a NEW version (V57 and V61 are never edited). Two columns that the earlier versions kept immutable may now change, each under its own rule:
--    upstream_code  the re-link. The copier opened the source by a NEW link and pressed «Добавить к себе» again; the copy command (under the account's advisory lock and the
--                   FOR SHARE lock of the publication row) points the copy at the code that now opens the SAME source. Allowed only to the source's CURRENT public code
--                   (deck_publication.public_code of upstream_deck_id): a code of another deck, a code nobody holds and a made-up one are refused whoever asks, and only for a copy that is
--                   attached and live before and after the statement. The other identity columns (owner, source, lineage, forked_at, reuse scope) still never change.
--    auto_update    the owner's setting. Allowed for a copy that is materialized, attached and live before and after the statement (the route answers 409 for a preparing or detached copy;
--                   this is the net under it).
--    Everything else of V57/V61 is unchanged: base_revision_id moves only forward as V61 defined, materialized_at, detached_at and released_at are set once, a finished copy's job is final.
-- 2. fork_unit_decision_pending: the badge asks, for a page of copies, whether a PENDING row exists. The primary key (deck_id, unit_kind, unit_key) would be read over every decision of the
--    copy (O(units touched)) to answer "none"; this partial index holds the PENDING rows only, so the probe is one index entry whatever the copy has decided.
SET LOCAL lock_timeout = '5s';

CREATE OR REPLACE FUNCTION app_learning.deck_fork_transition_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF (to_jsonb(OLD) - ARRAY['job_id', 'materialized_at', 'detached_at', 'released_at', 'base_revision_id', 'upstream_code', 'auto_update'])
          IS DISTINCT FROM (to_jsonb(NEW) - ARRAY['job_id', 'materialized_at', 'detached_at', 'released_at', 'base_revision_id', 'upstream_code', 'auto_update'])
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
                                                          WHERE p.deck_id = OLD.upstream_deck_id))))
       OR (NEW.upstream_code <> OLD.upstream_code AND NOT (
              OLD.detached_at IS NULL AND OLD.released_at IS NULL AND NEW.detached_at IS NULL AND NEW.released_at IS NULL
              AND EXISTS (SELECT 1 FROM app_learning.deck_publication p WHERE p.deck_id = OLD.upstream_deck_id AND p.public_code = NEW.upstream_code)))
       OR (NEW.auto_update <> OLD.auto_update AND NOT (
              OLD.materialized_at IS NOT NULL AND OLD.detached_at IS NULL AND OLD.released_at IS NULL
              AND NEW.materialized_at IS NOT NULL AND NEW.detached_at IS NULL AND NEW.released_at IS NULL)) THEN
        RAISE EXCEPTION 'Invalid deck copy transition' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE INDEX fork_unit_decision_pending ON app_learning.fork_unit_decision(deck_id) WHERE decision = 'PENDING';
