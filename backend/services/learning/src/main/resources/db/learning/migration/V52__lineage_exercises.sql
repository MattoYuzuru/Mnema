-- Share/5 (#427): objectives, exercises, bindings, exercise media references and Study move onto the storage lineage.
--
-- Lineage rows (memory_objective, objective_revision, exercise_definition, exercise_revision, exercise_content_binding,
-- exercise_media_ref) are keyed by (reuse_scope_id, ...); their deck_id / deck_revision_id / deck_sequence stay as the
-- ORIGIN of the row (the deck whose command wrote it), never as "belongs to deck D". Per-deck rows (objective_head,
-- deck_head_exercise, deck_exercise_change, exercise_new_mark) and the Study rows that need it (study_candidate_generation,
-- study_candidate, study_policy_assignment) carry their deck's immutable scope and reach lineage rows through it. Progress keys
-- are unchanged: (account_id, deck_id, objective_id).
--
-- study_presentation and study_evidence get NO scope column and no backfill (review item 13): a presentation reaches the
-- exercise/objective revisions through a composite foreign key to the study_candidate row that carries the copied ids, and an
-- evidence row is tied to its presentation (through the attempt tombstone) by an insert guard. study_candidate_generation drops
-- its UUID source cursor: the candidate source is the pinned exercises manifest read by ordinal, scanned_count being the ordinal.
--
-- One Flyway transaction. Roll-forward only: after this, restoring a pre-V52 dump is a separate, rehearsed operation.
SET LOCAL lock_timeout = '5s';

-- ---------------------------------------------------------------------------------------------------------------
-- 1. Lineage keys of the identities.
ALTER TABLE app_learning.memory_objective ADD CONSTRAINT memory_objective_lineage_key UNIQUE (reuse_scope_id, objective_id);
ALTER TABLE app_learning.memory_objective ADD CONSTRAINT memory_objective_lineage_objective_key UNIQUE (reuse_scope_id, objective_key);
ALTER TABLE app_learning.memory_objective ADD CONSTRAINT memory_objective_lineage_member_key UNIQUE (reuse_scope_id, member_key, objective_id);
ALTER TABLE app_learning.exercise_definition ADD CONSTRAINT exercise_definition_lineage_key UNIQUE (reuse_scope_id, exercise_id);

-- ---------------------------------------------------------------------------------------------------------------
-- 2. Rows that gain the scope: lineage rows keep deck_id as origin, per-deck and Study rows are keyed by the reading deck.
ALTER TABLE app_learning.objective_revision ADD COLUMN reuse_scope_id UUID;
ALTER TABLE app_learning.exercise_content_binding ADD COLUMN reuse_scope_id UUID;
ALTER TABLE app_learning.exercise_media_ref ADD COLUMN reuse_scope_id UUID;
ALTER TABLE app_learning.objective_head ADD COLUMN reuse_scope_id UUID;
ALTER TABLE app_learning.deck_head_exercise ADD COLUMN reuse_scope_id UUID;
ALTER TABLE app_learning.deck_exercise_change ADD COLUMN reuse_scope_id UUID;
ALTER TABLE app_learning.exercise_new_mark ADD COLUMN reuse_scope_id UUID;
ALTER TABLE app_learning.study_candidate_generation ADD COLUMN reuse_scope_id UUID;
ALTER TABLE app_learning.study_candidate ADD COLUMN reuse_scope_id UUID;
ALTER TABLE app_learning.study_policy_assignment ADD COLUMN reuse_scope_id UUID;

-- The owner column of an exercise media reference was both "owner of the exercise" and "owner of the asset". Only the asset
-- owner is meaningful once a copy may keep the author's image on an exercise revision the copy's owner wrote.
ALTER TABLE app_learning.exercise_media_ref RENAME COLUMN owner_id TO asset_owner_id;

-- The immutability guards forbid UPDATE and DELETE; the one-time backfill of a derived column (and the reset of the
-- candidate cache below) is the only thing they are lifted for. They are re-enabled right after it and verified again at the end.
ALTER TABLE app_learning.objective_revision DISABLE TRIGGER objective_revision_immutable;
ALTER TABLE app_learning.exercise_content_binding DISABLE TRIGGER exercise_binding_immutable;
ALTER TABLE app_learning.exercise_media_ref DISABLE TRIGGER exercise_media_ref_guard;
ALTER TABLE app_learning.deck_exercise_change DISABLE TRIGGER deck_exercise_change_immutable;
ALTER TABLE app_learning.study_candidate DISABLE TRIGGER study_candidate_immutable;
ALTER TABLE app_learning.study_policy_assignment DISABLE TRIGGER study_policy_assignment_immutable;

UPDATE app_learning.objective_revision t SET reuse_scope_id = d.reuse_scope_id FROM app_learning.deck d WHERE d.deck_id = t.deck_id;
UPDATE app_learning.exercise_content_binding t SET reuse_scope_id = d.reuse_scope_id FROM app_learning.deck d WHERE d.deck_id = t.deck_id;
UPDATE app_learning.exercise_media_ref t SET reuse_scope_id = d.reuse_scope_id FROM app_learning.deck d WHERE d.deck_id = t.deck_id;
UPDATE app_learning.objective_head t SET reuse_scope_id = d.reuse_scope_id FROM app_learning.deck d WHERE d.deck_id = t.deck_id;
UPDATE app_learning.deck_head_exercise t SET reuse_scope_id = d.reuse_scope_id FROM app_learning.deck d WHERE d.deck_id = t.deck_id;
UPDATE app_learning.deck_exercise_change t SET reuse_scope_id = d.reuse_scope_id FROM app_learning.deck d WHERE d.deck_id = t.deck_id;
UPDATE app_learning.exercise_new_mark t SET reuse_scope_id = d.reuse_scope_id FROM app_learning.deck d WHERE d.deck_id = t.deck_id;
UPDATE app_learning.study_candidate t SET reuse_scope_id = d.reuse_scope_id FROM app_learning.deck d WHERE d.deck_id = t.deck_id;
UPDATE app_learning.study_policy_assignment t SET reuse_scope_id = d.reuse_scope_id FROM app_learning.deck d WHERE d.deck_id = t.deck_id;

-- A generation that was still being prepared walked the exercises in exercise_id order with a UUID cursor; the new source is
-- the pinned manifest read by ordinal, so those partial walks are not resumable. The candidate table is a rebuildable cache and a
-- PREPARING generation has issued no presentation (a session turns ACTIVE only when its generation is READY), so it starts over:
-- its partial candidates are dropped and the next poll of any session on it rebuilds them from ordinal 0. READY generations
-- are complete and untouched.
DELETE FROM app_learning.study_candidate c USING app_learning.study_candidate_generation g
 WHERE g.generation_id = c.generation_id AND g.status <> 'READY';

-- One UPDATE per row: a second update of a row this transaction already rewrote would force the foreign keys of the row to be
-- re-checked. A still-PREPARING generation starts over (see the candidate reset above): cursor and counters are cleared.
UPDATE app_learning.study_candidate_generation t
   SET reuse_scope_id = d.reuse_scope_id,
       scanned_count = CASE WHEN t.status = 'READY' THEN t.scanned_count ELSE 0 END,
       candidate_count = CASE WHEN t.status = 'READY' THEN t.candidate_count ELSE 0 END,
       source_cursor = CASE WHEN t.status = 'READY' THEN t.source_cursor ELSE NULL END,
       row_version = CASE WHEN t.status = 'READY' THEN t.row_version ELSE t.row_version + 1 END
  FROM app_learning.deck d WHERE d.deck_id = t.deck_id;

ALTER TABLE app_learning.objective_revision ENABLE TRIGGER objective_revision_immutable;
ALTER TABLE app_learning.exercise_content_binding ENABLE TRIGGER exercise_binding_immutable;
ALTER TABLE app_learning.exercise_media_ref ENABLE TRIGGER exercise_media_ref_guard;
ALTER TABLE app_learning.deck_exercise_change ENABLE TRIGGER deck_exercise_change_immutable;
ALTER TABLE app_learning.study_candidate ENABLE TRIGGER study_candidate_immutable;
ALTER TABLE app_learning.study_policy_assignment ENABLE TRIGGER study_policy_assignment_immutable;

-- The backfill of deck_head_exercise queued deferred uniqueness checks (deck_id, ordinal) and the deferred foreign keys of the
-- head tables; PostgreSQL refuses to ALTER a table with pending trigger events (55006), so they run now.
SET CONSTRAINTS ALL IMMEDIATE;

ALTER TABLE app_learning.objective_revision ALTER COLUMN reuse_scope_id SET NOT NULL;
ALTER TABLE app_learning.exercise_content_binding ALTER COLUMN reuse_scope_id SET NOT NULL;
ALTER TABLE app_learning.exercise_media_ref ALTER COLUMN reuse_scope_id SET NOT NULL;
ALTER TABLE app_learning.objective_head ALTER COLUMN reuse_scope_id SET NOT NULL;
ALTER TABLE app_learning.deck_head_exercise ALTER COLUMN reuse_scope_id SET NOT NULL;
ALTER TABLE app_learning.deck_exercise_change ALTER COLUMN reuse_scope_id SET NOT NULL;
ALTER TABLE app_learning.exercise_new_mark ALTER COLUMN reuse_scope_id SET NOT NULL;
ALTER TABLE app_learning.study_candidate_generation ALTER COLUMN reuse_scope_id SET NOT NULL;
ALTER TABLE app_learning.study_candidate ALTER COLUMN reuse_scope_id SET NOT NULL;
ALTER TABLE app_learning.study_policy_assignment ALTER COLUMN reuse_scope_id SET NOT NULL;

ALTER TABLE app_learning.study_candidate_generation DROP COLUMN source_cursor;

-- ---------------------------------------------------------------------------------------------------------------
-- 3. Drop every deck-keyed reference to the lineage rows (names read from pg_constraint of the V51 schema; 7 foreign keys
--    reference exercise_revision, 6 objective_revision, 3 memory_objective, 3 exercise_definition).
ALTER TABLE app_learning.deck_exercise_change DROP CONSTRAINT deck_exercise_change_deck_id_exercise_id_previous_revision_fkey;
ALTER TABLE app_learning.deck_exercise_change DROP CONSTRAINT deck_exercise_change_deck_id_exercise_id_revision_id_fkey;
ALTER TABLE app_learning.deck_head_exercise DROP CONSTRAINT deck_head_exercise_deck_id_exercise_id_revision_id_exercis_fkey;
ALTER TABLE app_learning.exercise_content_binding DROP CONSTRAINT exercise_content_binding_deck_id_exercise_id_exercise_revi_fkey;
ALTER TABLE app_learning.exercise_content_binding DROP CONSTRAINT exercise_content_binding_deck_id_objective_id_objective_re_fkey;
-- Share/4's temporary origin foreign key onto item_revision(deck_id, member_key, revision_id) goes with its key (step 4).
ALTER TABLE app_learning.exercise_content_binding DROP CONSTRAINT exercise_content_binding_item_revision_origin_fkey;
ALTER TABLE app_learning.exercise_media_ref DROP CONSTRAINT exercise_media_ref_deck_id_exercise_id_exercise_revision_i_fkey;
ALTER TABLE app_learning.exercise_media_ref DROP CONSTRAINT exercise_media_ref_deck_id_exercise_id_owner_id_fkey;
ALTER TABLE app_learning.exercise_new_mark DROP CONSTRAINT exercise_new_mark_deck_id_exercise_id_fkey;
ALTER TABLE app_learning.exercise_revision DROP CONSTRAINT exercise_revision_deck_id_exercise_id_fkey;
ALTER TABLE app_learning.exercise_revision DROP CONSTRAINT exercise_revision_deck_id_exercise_id_parent_revision_id_p_fkey;
ALTER TABLE app_learning.memory_objective DROP CONSTRAINT memory_objective_deck_id_member_key_fkey;
ALTER TABLE app_learning.objective_head DROP CONSTRAINT objective_head_deck_id_objective_id_revision_id_objective__fkey;
ALTER TABLE app_learning.objective_revision DROP CONSTRAINT objective_revision_deck_id_objective_id_fkey;
ALTER TABLE app_learning.objective_revision DROP CONSTRAINT objective_revision_deck_id_objective_id_parent_revision_id_fkey;
ALTER TABLE app_learning.study_candidate DROP CONSTRAINT study_candidate_deck_id_exercise_id_exercise_revision_id_fkey;
ALTER TABLE app_learning.study_candidate DROP CONSTRAINT study_candidate_deck_id_member_key_objective_id_fkey;
ALTER TABLE app_learning.study_candidate DROP CONSTRAINT study_candidate_deck_id_objective_id_objective_revision_id_fkey;
ALTER TABLE app_learning.study_candidate_generation DROP CONSTRAINT study_candidate_generation_deck_id_fkey;
ALTER TABLE app_learning.study_evidence DROP CONSTRAINT study_evidence_deck_id_objective_id_objective_revision_id_fkey;
ALTER TABLE app_learning.study_policy_assignment DROP CONSTRAINT study_policy_assignment_deck_id_objective_id_fkey;
ALTER TABLE app_learning.study_presentation DROP CONSTRAINT study_presentation_deck_id_exercise_id_exercise_revision_i_fkey;
ALTER TABLE app_learning.study_presentation DROP CONSTRAINT study_presentation_deck_id_objective_id_objective_revision_fkey;
ALTER TABLE app_learning.study_presentation DROP CONSTRAINT study_presentation_deck_id_generation_id_candidate_ordinal_fkey;

-- Their targets that nothing references any more: the deck-keyed alternate keys, whose lineage twins replace them.
ALTER TABLE app_learning.memory_objective DROP CONSTRAINT memory_objective_deck_id_objective_key_key;
ALTER TABLE app_learning.memory_objective DROP CONSTRAINT memory_objective_deck_id_member_key_objective_id_key;
ALTER TABLE app_learning.exercise_definition DROP CONSTRAINT exercise_definition_media_owner;
ALTER TABLE app_learning.study_candidate DROP CONSTRAINT study_candidate_deck_id_generation_id_candidate_ordinal_key;
ALTER TABLE app_learning.item_revision DROP CONSTRAINT item_revision_origin_key;
DROP INDEX app_learning.exercise_revision_snapshot_seek;
DROP INDEX app_learning.exercise_binding_assessed_member;
DROP INDEX app_learning.one_assessed_binding_per_exercise_revision;

-- ---------------------------------------------------------------------------------------------------------------
-- 4. Re-key the immutable lineage rows.
ALTER TABLE app_learning.memory_objective ADD CONSTRAINT memory_objective_item_fkey
    FOREIGN KEY (reuse_scope_id, member_key) REFERENCES app_learning.learning_item(reuse_scope_id, member_key);

-- objective_revision
ALTER TABLE app_learning.objective_revision DROP CONSTRAINT objective_revision_pkey;
ALTER TABLE app_learning.objective_revision DROP CONSTRAINT objective_revision_deck_id_revision_id_key;
ALTER TABLE app_learning.objective_revision DROP CONSTRAINT objective_revision_deck_id_objective_id_revision_id_objecti_key;
ALTER TABLE app_learning.objective_revision ADD CONSTRAINT objective_revision_pkey
    PRIMARY KEY (reuse_scope_id, objective_id, revision_id);
ALTER TABLE app_learning.objective_revision ADD CONSTRAINT objective_revision_lineage_id_key
    UNIQUE (reuse_scope_id, revision_id);
ALTER TABLE app_learning.objective_revision ADD CONSTRAINT objective_revision_lineage_sequence_key
    UNIQUE (reuse_scope_id, objective_id, revision_id, objective_sequence);
-- Kept on purpose (Share/3 review, item 5): deck_id is the deck whose command wrote the revision, so numbers inside one origin
-- deck stay linear. Updates/2 (#441) replaces it with the parent chain when copies may continue an author's chain.
-- objective_revision_deck_id_objective_id_objective_sequence_key stays.
ALTER TABLE app_learning.objective_revision ADD CONSTRAINT objective_revision_objective_fkey
    FOREIGN KEY (reuse_scope_id, objective_id) REFERENCES app_learning.memory_objective(reuse_scope_id, objective_id);
ALTER TABLE app_learning.objective_revision ADD CONSTRAINT objective_revision_parent_fkey
    FOREIGN KEY (reuse_scope_id, objective_id, parent_revision_id, parent_objective_sequence)
    REFERENCES app_learning.objective_revision(reuse_scope_id, objective_id, revision_id, objective_sequence);
ALTER TABLE app_learning.objective_revision ADD CONSTRAINT objective_revision_origin_deck_fkey
    FOREIGN KEY (deck_id, reuse_scope_id) REFERENCES app_learning.deck(deck_id, reuse_scope_id);

-- exercise_revision
ALTER TABLE app_learning.exercise_revision DROP CONSTRAINT exercise_revision_pkey;
ALTER TABLE app_learning.exercise_revision DROP CONSTRAINT exercise_revision_deck_id_revision_id_key;
ALTER TABLE app_learning.exercise_revision DROP CONSTRAINT exercise_revision_deck_id_exercise_id_revision_id_exercise__key;
ALTER TABLE app_learning.exercise_revision ADD CONSTRAINT exercise_revision_pkey
    PRIMARY KEY (reuse_scope_id, exercise_id, revision_id);
ALTER TABLE app_learning.exercise_revision ADD CONSTRAINT exercise_revision_lineage_id_key
    UNIQUE (reuse_scope_id, revision_id);
ALTER TABLE app_learning.exercise_revision ADD CONSTRAINT exercise_revision_lineage_sequence_key
    UNIQUE (reuse_scope_id, exercise_id, revision_id, exercise_sequence);
-- exercise_revision_deck_id_exercise_id_exercise_sequence_key stays (origin numbers, see above).
ALTER TABLE app_learning.exercise_revision ADD CONSTRAINT exercise_revision_exercise_fkey
    FOREIGN KEY (reuse_scope_id, exercise_id) REFERENCES app_learning.exercise_definition(reuse_scope_id, exercise_id);
ALTER TABLE app_learning.exercise_revision ADD CONSTRAINT exercise_revision_parent_fkey
    FOREIGN KEY (reuse_scope_id, exercise_id, parent_revision_id, parent_exercise_sequence)
    REFERENCES app_learning.exercise_revision(reuse_scope_id, exercise_id, revision_id, exercise_sequence);
ALTER TABLE app_learning.exercise_revision ADD CONSTRAINT exercise_revision_origin_deck_fkey
    FOREIGN KEY (deck_id, reuse_scope_id) REFERENCES app_learning.deck(deck_id, reuse_scope_id);

-- exercise_content_binding: the one-ASSESSED rule and the ordinal are per revision of the lineage.
ALTER TABLE app_learning.exercise_content_binding DROP CONSTRAINT exercise_content_binding_pkey;
ALTER TABLE app_learning.exercise_content_binding DROP CONSTRAINT exercise_content_binding_deck_id_exercise_id_exercise_revis_key;
ALTER TABLE app_learning.exercise_content_binding ADD CONSTRAINT exercise_content_binding_pkey
    PRIMARY KEY (reuse_scope_id, exercise_id, exercise_revision_id, binding_id);
ALTER TABLE app_learning.exercise_content_binding ADD CONSTRAINT exercise_content_binding_lineage_ordinal_key
    UNIQUE (reuse_scope_id, exercise_id, exercise_revision_id, binding_ordinal);
CREATE UNIQUE INDEX one_assessed_binding_per_exercise_revision
    ON app_learning.exercise_content_binding(reuse_scope_id, exercise_id, exercise_revision_id) WHERE role = 'ASSESSED';
CREATE INDEX exercise_binding_assessed_member
    ON app_learning.exercise_content_binding(reuse_scope_id, member_key, exercise_id, exercise_revision_id) WHERE role = 'ASSESSED';
-- The revisions a binding pins, found from the item revision (ItemRevisionVisibility, step 6 of the plan) and from the objective.
CREATE INDEX exercise_binding_item_revision
    ON app_learning.exercise_content_binding(reuse_scope_id, member_key, item_revision_id);
ALTER TABLE app_learning.exercise_content_binding ADD CONSTRAINT exercise_content_binding_revision_fkey
    FOREIGN KEY (reuse_scope_id, exercise_id, exercise_revision_id)
    REFERENCES app_learning.exercise_revision(reuse_scope_id, exercise_id, revision_id);
ALTER TABLE app_learning.exercise_content_binding ADD CONSTRAINT exercise_content_binding_item_revision_fkey
    FOREIGN KEY (reuse_scope_id, member_key, item_revision_id)
    REFERENCES app_learning.item_revision(reuse_scope_id, member_key, revision_id);
ALTER TABLE app_learning.exercise_content_binding ADD CONSTRAINT exercise_content_binding_objective_fkey
    FOREIGN KEY (reuse_scope_id, objective_id, objective_revision_id)
    REFERENCES app_learning.objective_revision(reuse_scope_id, objective_id, revision_id);

-- The assessed objective belongs to the assessed material of the same lineage.
CREATE OR REPLACE FUNCTION app_learning.exercise_binding_objective_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.role = 'ASSESSED' AND NOT EXISTS (
        SELECT 1 FROM app_learning.memory_objective objective
         WHERE objective.reuse_scope_id = NEW.reuse_scope_id AND objective.objective_id = NEW.objective_id
           AND objective.member_key = NEW.member_key
    ) THEN
        RAISE EXCEPTION 'Assessed objective must belong to the assessed material' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

-- exercise_media_ref: a reference belongs to the exercise revision (lineage); asset_owner_id owns the asset.
ALTER TABLE app_learning.exercise_media_ref DROP CONSTRAINT exercise_media_ref_pkey;
ALTER TABLE app_learning.exercise_media_ref ADD CONSTRAINT exercise_media_ref_pkey
    PRIMARY KEY (reuse_scope_id, exercise_id, exercise_revision_id, asset_id);
ALTER TABLE app_learning.exercise_media_ref ADD CONSTRAINT exercise_media_ref_revision_fkey
    FOREIGN KEY (reuse_scope_id, exercise_id, exercise_revision_id)
    REFERENCES app_learning.exercise_revision(reuse_scope_id, exercise_id, revision_id);
-- exercise_media_ref_asset_id_owner_id_fkey (asset_id, asset_owner_id) -> media_asset(asset_id, owner_id) is unchanged.

-- ---------------------------------------------------------------------------------------------------------------
-- 5. Per-deck rows: the deck's own scope (immutable on deck), and lineage rows reached through it.
ALTER TABLE app_learning.objective_head ADD CONSTRAINT objective_head_deck_scope_fkey
    FOREIGN KEY (deck_id, reuse_scope_id) REFERENCES app_learning.deck(deck_id, reuse_scope_id);
ALTER TABLE app_learning.objective_head ADD CONSTRAINT objective_head_revision_fkey
    FOREIGN KEY (reuse_scope_id, objective_id, revision_id, objective_sequence)
    REFERENCES app_learning.objective_revision(reuse_scope_id, objective_id, revision_id, objective_sequence)
    DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE app_learning.deck_head_exercise ADD CONSTRAINT deck_head_exercise_deck_scope_fkey
    FOREIGN KEY (deck_id, reuse_scope_id) REFERENCES app_learning.deck(deck_id, reuse_scope_id);
ALTER TABLE app_learning.deck_head_exercise ADD CONSTRAINT deck_head_exercise_revision_fkey
    FOREIGN KEY (reuse_scope_id, exercise_id, revision_id, exercise_sequence)
    REFERENCES app_learning.exercise_revision(reuse_scope_id, exercise_id, revision_id, exercise_sequence)
    DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE app_learning.deck_exercise_change ADD CONSTRAINT deck_exercise_change_deck_scope_fkey
    FOREIGN KEY (deck_id, reuse_scope_id) REFERENCES app_learning.deck(deck_id, reuse_scope_id);
ALTER TABLE app_learning.deck_exercise_change ADD CONSTRAINT deck_exercise_change_previous_fkey
    FOREIGN KEY (reuse_scope_id, exercise_id, previous_revision_id)
    REFERENCES app_learning.exercise_revision(reuse_scope_id, exercise_id, revision_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE app_learning.deck_exercise_change ADD CONSTRAINT deck_exercise_change_revision_fkey
    FOREIGN KEY (reuse_scope_id, exercise_id, revision_id)
    REFERENCES app_learning.exercise_revision(reuse_scope_id, exercise_id, revision_id) DEFERRABLE INITIALLY DEFERRED;
-- Revision visibility (ExerciseRevisionVisibility): a deck sees a revision its own change published or replaced.
CREATE INDEX deck_exercise_change_revision
    ON app_learning.deck_exercise_change(deck_id, exercise_id, revision_id) WHERE revision_id IS NOT NULL;
CREATE INDEX deck_exercise_change_previous_revision
    ON app_learning.deck_exercise_change(deck_id, exercise_id, previous_revision_id) WHERE previous_revision_id IS NOT NULL;

-- (deck_id, owner_id) -> deck stays: the mark is owner-scoped.
ALTER TABLE app_learning.exercise_new_mark ADD CONSTRAINT exercise_new_mark_deck_scope_fkey
    FOREIGN KEY (deck_id, reuse_scope_id) REFERENCES app_learning.deck(deck_id, reuse_scope_id);
ALTER TABLE app_learning.exercise_new_mark ADD CONSTRAINT exercise_new_mark_exercise_fkey
    FOREIGN KEY (reuse_scope_id, exercise_id) REFERENCES app_learning.exercise_definition(reuse_scope_id, exercise_id);

-- ---------------------------------------------------------------------------------------------------------------
-- 6. Study. The candidate cache and the policy assignment are per reading deck and reach the lineage through its scope.
ALTER TABLE app_learning.study_candidate_generation ADD CONSTRAINT study_candidate_generation_deck_scope_fkey
    FOREIGN KEY (deck_id, reuse_scope_id) REFERENCES app_learning.deck(deck_id, reuse_scope_id);

ALTER TABLE app_learning.study_candidate ADD CONSTRAINT study_candidate_deck_scope_fkey
    FOREIGN KEY (deck_id, reuse_scope_id) REFERENCES app_learning.deck(deck_id, reuse_scope_id);
ALTER TABLE app_learning.study_candidate ADD CONSTRAINT study_candidate_exercise_fkey
    FOREIGN KEY (reuse_scope_id, exercise_id, exercise_revision_id)
    REFERENCES app_learning.exercise_revision(reuse_scope_id, exercise_id, revision_id);
ALTER TABLE app_learning.study_candidate ADD CONSTRAINT study_candidate_objective_member_fkey
    FOREIGN KEY (reuse_scope_id, member_key, objective_id)
    REFERENCES app_learning.memory_objective(reuse_scope_id, member_key, objective_id);
ALTER TABLE app_learning.study_candidate ADD CONSTRAINT study_candidate_objective_revision_fkey
    FOREIGN KEY (reuse_scope_id, objective_id, objective_revision_id)
    REFERENCES app_learning.objective_revision(reuse_scope_id, objective_id, revision_id);
-- A presentation copies exactly the ids of its candidate; this key is what its foreign key checks (see below).
ALTER TABLE app_learning.study_candidate ADD CONSTRAINT study_candidate_presentation_key
    UNIQUE (deck_id, generation_id, candidate_ordinal, exercise_id, exercise_revision_id, objective_id, objective_revision_id);

-- study_presentation has no scope column: its exercise and objective revisions exist in the lineage because the candidate it
-- was issued from does (study_candidate_exercise_fkey / study_candidate_objective_revision_fkey), and the copied ids are
-- identical by this key. A replay copies the same candidate coordinates, so it satisfies the key as well.
ALTER TABLE app_learning.study_presentation ADD CONSTRAINT study_presentation_candidate_fkey
    FOREIGN KEY (deck_id, generation_id, candidate_ordinal, exercise_id, exercise_revision_id, objective_id, objective_revision_id)
    REFERENCES app_learning.study_candidate(deck_id, generation_id, candidate_ordinal, exercise_id, exercise_revision_id,
        objective_id, objective_revision_id);

ALTER TABLE app_learning.study_policy_assignment ADD CONSTRAINT study_policy_assignment_deck_scope_fkey
    FOREIGN KEY (deck_id, reuse_scope_id) REFERENCES app_learning.deck(deck_id, reuse_scope_id);
ALTER TABLE app_learning.study_policy_assignment ADD CONSTRAINT study_policy_assignment_objective_fkey
    FOREIGN KEY (reuse_scope_id, objective_id) REFERENCES app_learning.memory_objective(reuse_scope_id, objective_id);

-- study_evidence has no scope column either (it is one of the largest tables and immutable). Its attempt is a tombstone of a
-- presentation, which carries the objective revision through its candidate; the evidence must state exactly that revision. A
-- foreign key cannot say it without a new column and a backfill, so an insert guard does. Evidence is written only by a
-- SCHEDULED session whose attempt was just recorded as ASSESSED (a later dispute flips the tombstone to NOT_ASSESSED, so the
-- status is a fact at insert time only), and the objective revision is the one the presented exercise revision's ASSESSED
-- binding names (reached through the scope of the evidence's own deck).
CREATE FUNCTION app_learning.study_evidence_presentation_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM app_learning.study_attempt_tombstone attempt
          JOIN app_learning.study_presentation presentation ON presentation.account_id = attempt.account_id
           AND presentation.session_id = attempt.session_id AND presentation.presentation_id = attempt.presentation_id
          JOIN app_learning.deck deck ON deck.deck_id = presentation.deck_id
          JOIN app_learning.exercise_content_binding binding ON binding.reuse_scope_id = deck.reuse_scope_id
           AND binding.exercise_id = presentation.exercise_id AND binding.exercise_revision_id = presentation.exercise_revision_id
           AND binding.role = 'ASSESSED'
         WHERE attempt.attempt_id = NEW.attempt_id AND attempt.account_id = NEW.account_id
           AND attempt.mode = 'SCHEDULED' AND attempt.status = 'ASSESSED'
           AND presentation.deck_id = NEW.deck_id AND presentation.objective_id = NEW.objective_id
           AND presentation.objective_revision_id = NEW.objective_revision_id
           AND binding.objective_id = NEW.objective_id AND binding.objective_revision_id = NEW.objective_revision_id
    ) THEN
        RAISE EXCEPTION 'Evidence must belong to a scheduled, assessed attempt and state the objective revision of its presentation'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER study_evidence_presentation_guard BEFORE INSERT ON app_learning.study_evidence
    FOR EACH ROW EXECUTE FUNCTION app_learning.study_evidence_presentation_guard();

-- The rows that exist are verified by the same chain (without the status, which a dispute may have turned to NOT_ASSESSED).
DO $evidence$
BEGIN
    IF EXISTS (
        SELECT 1 FROM app_learning.study_evidence evidence
         WHERE NOT EXISTS (
            SELECT 1 FROM app_learning.study_attempt_tombstone attempt
              JOIN app_learning.study_presentation presentation ON presentation.account_id = attempt.account_id
               AND presentation.session_id = attempt.session_id AND presentation.presentation_id = attempt.presentation_id
              JOIN app_learning.deck deck ON deck.deck_id = presentation.deck_id
              JOIN app_learning.exercise_content_binding binding ON binding.reuse_scope_id = deck.reuse_scope_id
               AND binding.exercise_id = presentation.exercise_id AND binding.exercise_revision_id = presentation.exercise_revision_id
               AND binding.role = 'ASSESSED'
             WHERE attempt.attempt_id = evidence.attempt_id AND attempt.account_id = evidence.account_id
               AND attempt.mode = 'SCHEDULED'
               AND presentation.deck_id = evidence.deck_id AND presentation.objective_id = evidence.objective_id
               AND presentation.objective_revision_id = evidence.objective_revision_id
               AND binding.objective_id = evidence.objective_id AND binding.objective_revision_id = evidence.objective_revision_id)) THEN
        RAISE EXCEPTION 'A study evidence row does not match its presentation; V52 cannot drop its objective revision key';
    END IF;
END
$evidence$;

-- ---------------------------------------------------------------------------------------------------------------
-- 7. Media readiness is a property of the lineage revision, not of an actor or deck. Fail-closed (architecture §8): the assets
-- the revision's content declares must each have a READY reference of the declared kind; a missing reference row is NOT ready.
-- A revision that does not exist, and one whose content names an asset without a reference, are not ready; a text-only
-- revision (no declared asset, no reference) is trivially ready.
DROP FUNCTION app_learning.exercise_media_ready(UUID, UUID, UUID, UUID);
CREATE FUNCTION app_learning.exercise_media_ready(p_scope UUID, p_exercise UUID, p_revision UUID)
    RETURNS BOOLEAN LANGUAGE sql STABLE AS $$
    SELECT EXISTS (SELECT 1 FROM app_learning.exercise_revision revision
                    WHERE revision.reuse_scope_id = p_scope AND revision.exercise_id = p_exercise
                      AND revision.revision_id = p_revision)
       AND NOT EXISTS (
            SELECT 1
              FROM (SELECT DISTINCT (jsonb_path_query(revision.content, 'strict $.**.assetId') #>> '{}')::uuid AS asset_id
                      FROM app_learning.exercise_revision revision
                     WHERE revision.reuse_scope_id = p_scope AND revision.exercise_id = p_exercise
                       AND revision.revision_id = p_revision) declared
              FULL JOIN (SELECT reference.asset_id, reference.asset_owner_id, reference.media_kind
                           FROM app_learning.exercise_media_ref reference
                          WHERE reference.reuse_scope_id = p_scope AND reference.exercise_id = p_exercise
                            AND reference.exercise_revision_id = p_revision) pinned ON pinned.asset_id = declared.asset_id
              LEFT JOIN app_learning.media_asset asset ON asset.asset_id = pinned.asset_id AND asset.owner_id = pinned.asset_owner_id
              LEFT JOIN app_learning.media_blob blob ON blob.blob_id = asset.source_blob_id
             WHERE NOT COALESCE(asset.state = 'READY' AND blob.mime_type LIKE pinned.media_kind || '/%', FALSE));
$$;

-- What the stricter readiness changes for data that exists: revisions whose content declares an asset that no reference row
-- pins were "ready" before (no rows -> TRUE) and are not any more. Reported, not fatal: such a revision was never playable.
DO $media$
DECLARE
    flipped BIGINT;
BEGIN
    SELECT count(*) INTO flipped FROM (
        SELECT DISTINCT revision.reuse_scope_id, revision.exercise_id, revision.revision_id
          FROM app_learning.exercise_revision revision
          CROSS JOIN LATERAL jsonb_path_query(revision.content, 'strict $.**.assetId') AS declared(value)
         WHERE NOT EXISTS (SELECT 1 FROM app_learning.exercise_media_ref reference
                            WHERE reference.reuse_scope_id = revision.reuse_scope_id
                              AND reference.exercise_id = revision.exercise_id
                              AND reference.exercise_revision_id = revision.revision_id
                              AND reference.asset_id = (declared.value #>> '{}')::uuid)) missing;
    RAISE NOTICE 'V52: % exercise revision(s) declare a media asset without a reference row and are now not ready', flipped;
END
$media$;

COMMENT ON COLUMN app_learning.exercise_revision.deck_id IS
    'Origin: the deck whose command wrote this revision. Never a membership filter: a deck owns the revisions its head or journal reaches.';
COMMENT ON COLUMN app_learning.objective_revision.deck_id IS
    'Origin: the deck whose command wrote this revision. Never a membership filter: a deck owns the revisions its head reaches.';
COMMENT ON COLUMN app_learning.exercise_content_binding.deck_id IS
    'Origin deck of the exercise revision. Bindings are read through (reuse_scope_id, exercise_id, exercise_revision_id).';
COMMENT ON COLUMN app_learning.exercise_media_ref.deck_id IS
    'Origin deck of the exercise revision. Media of deck D are the references of D''s head exercises, joined through the scope.';
COMMENT ON COLUMN app_learning.exercise_media_ref.asset_owner_id IS
    'Owner of the referenced asset; the revision may have been written by another account (a copy keeping the author''s image).';
COMMENT ON COLUMN app_learning.study_candidate_generation.scanned_count IS
    'Ordinal into the pinned exercises manifest (exercises_root_id) of the next entry to scan: the candidate source cursor.';

-- ---------------------------------------------------------------------------------------------------------------
-- 8. Fail the whole migration if an immutability guard is not enabled again (tgenabled = 'O' means origin/enabled).
DO $guards$
DECLARE
    guarded CONSTANT regclass[] := ARRAY['app_learning.memory_objective'::regclass, 'app_learning.objective_revision'::regclass,
        'app_learning.exercise_definition'::regclass, 'app_learning.exercise_revision'::regclass,
        'app_learning.exercise_content_binding'::regclass, 'app_learning.exercise_media_ref'::regclass,
        'app_learning.deck_exercise_change'::regclass, 'app_learning.study_candidate'::regclass,
        'app_learning.study_policy_assignment'::regclass, 'app_learning.study_evidence'::regclass];
    expected CONSTANT TEXT[] := ARRAY['memory_objective_immutable', 'objective_revision_immutable', 'exercise_definition_immutable',
        'exercise_revision_immutable', 'exercise_revision_descriptor_guard', 'exercise_binding_immutable',
        'exercise_binding_objective_guard', 'exercise_media_ref_guard', 'deck_exercise_change_immutable',
        'study_candidate_immutable', 'study_policy_assignment_immutable', 'study_evidence_immutable',
        'study_evidence_presentation_guard'];
    guard TEXT;
BEGIN
    FOREACH guard IN ARRAY expected LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_trigger t
                        WHERE t.tgname = guard AND NOT t.tgisinternal AND t.tgenabled = 'O' AND t.tgrelid = ANY (guarded)) THEN
            RAISE EXCEPTION 'Immutability guard % is missing or not enabled after V52', guard;
        END IF;
    END LOOP;
    IF EXISTS (SELECT 1 FROM pg_trigger t WHERE NOT t.tgisinternal AND t.tgenabled <> 'O'
                AND t.tgrelid = ANY (guarded || ARRAY['app_learning.objective_head'::regclass,
                    'app_learning.deck_head_exercise'::regclass, 'app_learning.exercise_new_mark'::regclass,
                    'app_learning.study_candidate_generation'::regclass, 'app_learning.study_presentation'::regclass])) THEN
        RAISE EXCEPTION 'A trigger on the exercise or study tables is not enabled after V52';
    END IF;
END
$guards$;
