-- Updates/2 (#441): the combined publication of many units in ONE deck revision (community decks architecture sections 4 and 7).
--
-- A copy applies an upstream update by moving many heads at once to revisions that ALREADY EXIST in the shared lineage (the author's), plus deletions, member order and the
-- title/description, as one new revision of the copy. Nothing of that creates a lineage revision, so what changes here is only what the old "one writer per deck" shape forbade:
--
--   1. The ORIGIN sequence uniques are dropped: item_revision (deck_id, member_key, item_sequence), exercise_revision (deck_id, exercise_id, exercise_sequence) and
--      objective_revision (deck_id, objective_id, objective_sequence). After a copy adopts the author's revision R (sequence s, origin = the author's deck) and edits it, its new revision
--      gets sequence s + 1 with origin = the copy, which may collide with the copy's OWN older revision s + 1 (copy edited twice, then adopted the author's s). The chain is
--      the parent foreign key and the parent CHECK (sequence = parent sequence + 1), and (reuse_scope_id, revision_id) stays unique: numbers are no longer unique per origin deck.
--      item_revision (deck_id, member_key, deck_revision_id) is KEPT: it says "a deck revision wrote at most one revision of a member", which stays true because adoption writes NO
--      item_revision (it only moves a head to a revision of the lineage). exercise_revision and objective_revision never had an equivalent.
--   2. deck_item_change.change_ordinal may reach the largest number of item changes of one combined revision (the CHECK was 0..99, the size of one ordinary publication). The column is
--      SMALLINT, so the ceiling is the type's own, 32767; the engine's learning.updates.max-units (default 10000, validated to at most 32767) is what is actually enforced. No table rewrite.
--   3. deck_exercise_change allows many exercises per deck revision: the primary key is (deck_id, deck_revision_id, exercise_id) instead of (deck_id, deck_revision_id). Every existing
--      read of the table goes through the partial indexes (deck_id, exercise_id, revision_id / previous_revision_id) and (deck_id, deck_sequence), all kept.
--   4. deck_item_change_revision stops being UNIQUE. An adopted revision is journaled so that the copy can read it by id afterwards (ItemRevisionVisibility); a later command may adopt an
--      older revision again (a revert), and a third may adopt the first again, so one deck may journal the same (member, revision) more than once. The index keeps its name and columns:
--      it is still the probe of the visibility rule. The readers that relied on one row (ItemRepository.revision, ExerciseRepository.exercise) take the newest row explicitly.
--   5. combined_publication + combined_publication_unit: the command of a combined publication above the synchronous limit, written in the transaction that enqueues its job
--      (queue deck.combined.publish), read by the job's slices and deleted when the job ends. A job payload is at most 16 KiB, a command has up to 10000 units. No foreign key to deck:
--      these are transient work rows owned by the job (its end, success or not, deletes them; a command whose job was cancelled while queued or is gone is
--      removed by the bounded orphan purge of CombinedPublication).
SET LOCAL lock_timeout = '5s';

ALTER TABLE app_learning.item_revision DROP CONSTRAINT item_revision_deck_id_member_key_item_sequence_key;
ALTER TABLE app_learning.exercise_revision DROP CONSTRAINT exercise_revision_deck_id_exercise_id_exercise_sequence_key;
ALTER TABLE app_learning.objective_revision DROP CONSTRAINT objective_revision_deck_id_objective_id_objective_sequence_key;

ALTER TABLE app_learning.deck_item_change DROP CONSTRAINT deck_item_change_change_ordinal_check;
ALTER TABLE app_learning.deck_item_change ADD CONSTRAINT deck_item_change_change_ordinal_check
    CHECK (change_ordinal >= 0 AND change_ordinal <= 32767) NOT VALID;
ALTER TABLE app_learning.deck_item_change VALIDATE CONSTRAINT deck_item_change_change_ordinal_check;

ALTER TABLE app_learning.deck_exercise_change DROP CONSTRAINT deck_exercise_change_pkey;
ALTER TABLE app_learning.deck_exercise_change ADD CONSTRAINT deck_exercise_change_pkey
    PRIMARY KEY (deck_id, deck_revision_id, exercise_id);

DROP INDEX app_learning.deck_item_change_revision;
CREATE INDEX deck_item_change_revision
    ON app_learning.deck_item_change(deck_id, member_key, revision_id) WHERE revision_id IS NOT NULL;

CREATE TABLE app_learning.combined_publication (
    command_id UUID PRIMARY KEY,
    deck_id UUID NOT NULL,
    actor_id UUID NOT NULL,
    expected_head_revision_id UUID NOT NULL,
    digest BYTEA NOT NULL CHECK (octet_length(digest) = 32),
    unit_count INTEGER NOT NULL CHECK (unit_count BETWEEN 0 AND 32767),
    manifest_unit_count INTEGER NOT NULL CHECK (manifest_unit_count BETWEEN 0 AND unit_count),
    metadata JSONB CHECK (metadata IS NULL OR jsonb_typeof(metadata) = 'object'),
    -- TEXT, not jsonb: the caller's writes are stored byte for byte (jsonb would reorder keys and rewrite numbers such as 1e2)
    final_writes TEXT CHECK (final_writes IS NULL OR octet_length(final_writes) <= 131072),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP CHECK (isfinite(created_at))
);
-- the account purge (#409) finds a deck's and an actor's commands; the orphan purge walks the oldest first
CREATE INDEX combined_publication_deck ON app_learning.combined_publication(deck_id);
CREATE INDEX combined_publication_actor ON app_learning.combined_publication(actor_id);
CREATE INDEX combined_publication_created ON app_learning.combined_publication(created_at);

CREATE TABLE app_learning.combined_publication_unit (
    command_id UUID NOT NULL REFERENCES app_learning.combined_publication(command_id) ON DELETE CASCADE,
    unit_ordinal INTEGER NOT NULL CHECK (unit_ordinal >= 0),
    kind TEXT NOT NULL CHECK (kind IN ('ITEM_REPLACE', 'ITEM_INSERT', 'ITEM_REMOVE', 'ITEM_MOVE',
                                       'EXERCISE_REPLACE', 'EXERCISE_INSERT', 'EXERCISE_REMOVE', 'OBJECTIVE_ADOPT')),
    key_id UUID NOT NULL,
    revision_id UUID,
    ordinal INTEGER CHECK (ordinal IS NULL OR ordinal BETWEEN 0 AND 99999),
    to_ordinal INTEGER CHECK (to_ordinal IS NULL OR to_ordinal BETWEEN 0 AND 99999),
    PRIMARY KEY (command_id, unit_ordinal),
    CHECK ((kind IN ('ITEM_REPLACE', 'ITEM_INSERT', 'EXERCISE_REPLACE', 'EXERCISE_INSERT')
                AND revision_id IS NOT NULL AND ordinal IS NOT NULL AND to_ordinal IS NULL)
        OR (kind IN ('ITEM_REMOVE', 'EXERCISE_REMOVE') AND revision_id IS NULL AND ordinal IS NOT NULL AND to_ordinal IS NULL)
        OR (kind = 'ITEM_MOVE' AND revision_id IS NULL AND ordinal IS NOT NULL AND to_ordinal IS NOT NULL)
        OR (kind = 'OBJECTIVE_ADOPT' AND revision_id IS NOT NULL AND ordinal IS NULL AND to_ordinal IS NULL))
);

COMMENT ON TABLE app_learning.combined_publication IS
    'Transient: the command of a combined publication above the synchronous limit, for the job deck.combined.publish. Deleted when the job ends.';
