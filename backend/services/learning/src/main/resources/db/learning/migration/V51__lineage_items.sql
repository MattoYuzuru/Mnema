-- Share/4 (#426): material revisions belong to the storage lineage (reuse_scope_id), not to one deck.
--
-- Lineage rows (learning_item, item_revision, item_preview, content_media_ref) are keyed by
-- (reuse_scope_id, member_key[, revision_id]); their deck_id / deck_revision_id / deck_sequence / owner_id stay as the
-- ORIGIN of the revision (the deck whose command wrote it), never as "belongs to deck D". Per-deck rows (heads, journal,
-- exemplars, drafts, capture notes) carry their deck's immutable scope and reach lineage rows through it. A deck that
-- shares a scope with another one (a copy, Share/10) therefore reads and edits inherited materials, and can never reach a
-- revision it does not own through its head or journal. Today every scope still belongs to exactly one deck.
--
-- Objectives, exercises and Study keep their deck keys until Share/5; the only change there is the binding FK moved to
-- the temporary origin key item_revision_origin_key (Share/5 drops it together with the bindings' deck keys).
--
-- One Flyway transaction. Roll-forward only: after this, restoring a pre-V51 dump is a separate, rehearsed operation.
SET LOCAL lock_timeout = '5s';

-- ---------------------------------------------------------------------------------------------------------------
-- 1. Lineage keys.
ALTER TABLE app_learning.deck ADD CONSTRAINT deck_scope_key UNIQUE (deck_id, reuse_scope_id);
ALTER TABLE app_learning.learning_item ADD CONSTRAINT learning_item_lineage_key UNIQUE (reuse_scope_id, member_key);

-- ---------------------------------------------------------------------------------------------------------------
-- 2. Per-deck rows (and the two lineage projections) gain the scope; backfill from the owning deck, then NOT NULL.
ALTER TABLE app_learning.deck_head_item ADD COLUMN reuse_scope_id UUID;
ALTER TABLE app_learning.deck_item_change ADD COLUMN reuse_scope_id UUID;
ALTER TABLE app_learning.deck_item_exemplar ADD COLUMN reuse_scope_id UUID;
ALTER TABLE app_learning.editing_draft ADD COLUMN reuse_scope_id UUID;
ALTER TABLE app_learning.capture_note ADD COLUMN reuse_scope_id UUID;
ALTER TABLE app_learning.item_preview ADD COLUMN reuse_scope_id UUID;
ALTER TABLE app_learning.content_media_ref ADD COLUMN reuse_scope_id UUID;

-- The owner column of a media reference was both "owner of the revision" and "owner of the asset". Only the asset owner
-- is meaningful once a copy may keep the author's image on a revision the copy's owner wrote.
ALTER TABLE app_learning.content_media_ref RENAME COLUMN owner_id TO asset_owner_id;

-- The immutability guards forbid UPDATE; the one-time backfill of a derived column is the only thing they are lifted for.
-- They are re-enabled right after it and verified again at the end of the migration.
ALTER TABLE app_learning.deck_item_change DISABLE TRIGGER deck_item_change_guard;
ALTER TABLE app_learning.content_media_ref DISABLE TRIGGER content_media_ref_guard;
ALTER TABLE app_learning.capture_note DISABLE TRIGGER capture_note_guard;
ALTER TABLE app_learning.editing_draft DISABLE TRIGGER editing_draft_guard;

UPDATE app_learning.deck_head_item t SET reuse_scope_id = d.reuse_scope_id FROM app_learning.deck d WHERE d.deck_id = t.deck_id;
UPDATE app_learning.deck_item_change t SET reuse_scope_id = d.reuse_scope_id FROM app_learning.deck d WHERE d.deck_id = t.deck_id;
UPDATE app_learning.deck_item_exemplar t SET reuse_scope_id = d.reuse_scope_id FROM app_learning.deck d WHERE d.deck_id = t.deck_id;
UPDATE app_learning.editing_draft t SET reuse_scope_id = d.reuse_scope_id FROM app_learning.deck d WHERE d.deck_id = t.deck_id;
UPDATE app_learning.capture_note t SET reuse_scope_id = d.reuse_scope_id FROM app_learning.deck d WHERE d.deck_id = t.deck_id;
UPDATE app_learning.item_preview t SET reuse_scope_id = d.reuse_scope_id FROM app_learning.deck d WHERE d.deck_id = t.deck_id;
UPDATE app_learning.content_media_ref t SET reuse_scope_id = d.reuse_scope_id FROM app_learning.deck d WHERE d.deck_id = t.deck_id;

ALTER TABLE app_learning.deck_item_change ENABLE TRIGGER deck_item_change_guard;
ALTER TABLE app_learning.content_media_ref ENABLE TRIGGER content_media_ref_guard;
ALTER TABLE app_learning.capture_note ENABLE TRIGGER capture_note_guard;
ALTER TABLE app_learning.editing_draft ENABLE TRIGGER editing_draft_guard;

ALTER TABLE app_learning.deck_head_item ALTER COLUMN reuse_scope_id SET NOT NULL;
ALTER TABLE app_learning.deck_item_change ALTER COLUMN reuse_scope_id SET NOT NULL;
ALTER TABLE app_learning.deck_item_exemplar ALTER COLUMN reuse_scope_id SET NOT NULL;
ALTER TABLE app_learning.editing_draft ALTER COLUMN reuse_scope_id SET NOT NULL;
ALTER TABLE app_learning.capture_note ALTER COLUMN reuse_scope_id SET NOT NULL;
ALTER TABLE app_learning.item_preview ALTER COLUMN reuse_scope_id SET NOT NULL;
ALTER TABLE app_learning.content_media_ref ALTER COLUMN reuse_scope_id SET NOT NULL;

-- ---------------------------------------------------------------------------------------------------------------
-- 3. Drop every deck-keyed reference to item_revision / learning_item (names read from the V4/V5/V6/V10/V20/V25 schema).
ALTER TABLE app_learning.capture_note DROP CONSTRAINT capture_note_deck_id_converted_member_key_converted_revisi_fkey;
ALTER TABLE app_learning.content_media_ref DROP CONSTRAINT content_media_ref_deck_id_member_key_revision_id_owner_id_fkey;
ALTER TABLE app_learning.deck_head_item DROP CONSTRAINT deck_head_item_deck_id_member_key_revision_id_item_sequenc_fkey;
ALTER TABLE app_learning.deck_item_change DROP CONSTRAINT deck_item_change_deck_id_member_key_fkey;
ALTER TABLE app_learning.deck_item_change DROP CONSTRAINT deck_item_change_deck_id_member_key_previous_revision_id_fkey;
ALTER TABLE app_learning.deck_item_change DROP CONSTRAINT deck_item_change_deck_id_member_key_revision_id_fkey;
ALTER TABLE app_learning.deck_item_exemplar DROP CONSTRAINT deck_item_exemplar_deck_id_member_key_fkey;
ALTER TABLE app_learning.editing_draft DROP CONSTRAINT editing_draft_deck_id_member_key_base_revision_id_fkey;
ALTER TABLE app_learning.item_preview DROP CONSTRAINT item_preview_deck_id_member_key_revision_id_fkey;
ALTER TABLE app_learning.item_revision DROP CONSTRAINT item_revision_deck_id_member_key_parent_revision_id_parent_fkey;
ALTER TABLE app_learning.item_revision DROP CONSTRAINT item_revision_deck_id_member_key_reuse_scope_id_owner_id_fkey;
-- Exercise bindings are still deck-keyed until Share/5; their FK moves to the temporary origin key created below.
ALTER TABLE app_learning.exercise_content_binding DROP CONSTRAINT exercise_content_binding_deck_id_member_key_item_revision__fkey;

-- Its only referrer (the item_revision FK dropped above) is gone: item identity is (deck_id, member_key) or the lineage
-- key, and the origin owner is checked through item_revision_origin_deck_fkey against deck.
ALTER TABLE app_learning.learning_item DROP CONSTRAINT learning_item_deck_id_member_key_reuse_scope_id_owner_id_key;

-- ---------------------------------------------------------------------------------------------------------------
-- 4. Re-key item_revision on the lineage.
ALTER TABLE app_learning.item_revision DROP CONSTRAINT item_revision_pkey;
ALTER TABLE app_learning.item_revision DROP CONSTRAINT item_revision_deck_id_revision_id_key;
ALTER TABLE app_learning.item_revision DROP CONSTRAINT item_revision_deck_id_member_key_revision_id_item_sequence_key;
ALTER TABLE app_learning.item_revision DROP CONSTRAINT item_revision_media_owner;
DROP INDEX app_learning.item_revision_history;

ALTER TABLE app_learning.item_revision ADD CONSTRAINT item_revision_pkey
    PRIMARY KEY (reuse_scope_id, member_key, revision_id);
ALTER TABLE app_learning.item_revision ADD CONSTRAINT item_revision_lineage_id_key
    UNIQUE (reuse_scope_id, revision_id);
ALTER TABLE app_learning.item_revision ADD CONSTRAINT item_revision_lineage_sequence_key
    UNIQUE (reuse_scope_id, member_key, revision_id, item_sequence);
-- Kept on purpose (Share/3 review, item 5): deck_id is the deck whose command wrote the revision, so numbers inside one
-- origin deck stay linear. Updates/2 (#441) replaces it with the parent chain when copies may continue an author's chain.
-- item_revision_deck_id_member_key_item_sequence_key and item_revision_deck_id_member_key_deck_revision_id_key stay.
ALTER TABLE app_learning.item_revision ADD CONSTRAINT item_revision_item_fkey
    FOREIGN KEY (reuse_scope_id, member_key) REFERENCES app_learning.learning_item(reuse_scope_id, member_key);
ALTER TABLE app_learning.item_revision ADD CONSTRAINT item_revision_parent_fkey
    FOREIGN KEY (reuse_scope_id, member_key, parent_revision_id, parent_item_sequence)
    REFERENCES app_learning.item_revision(reuse_scope_id, member_key, revision_id, item_sequence);
-- The origin of a revision stays tied to a deck of this scope and to that deck's owner (Share/3 review, item 4).
ALTER TABLE app_learning.item_revision ADD CONSTRAINT item_revision_origin_deck_fkey
    FOREIGN KEY (deck_id, reuse_scope_id, owner_id) REFERENCES app_learning.deck(deck_id, reuse_scope_id, owner_id);
-- TEMPORARY (Share/5 removes it): exercise_content_binding still references revisions by origin. Created after the
-- primary key swap, otherwise PostgreSQL would bind the new foreign key to the index of the primary key being dropped.
ALTER TABLE app_learning.item_revision ADD CONSTRAINT item_revision_origin_key
    UNIQUE (deck_id, member_key, revision_id);
ALTER TABLE app_learning.exercise_content_binding ADD CONSTRAINT exercise_content_binding_item_revision_origin_fkey
    FOREIGN KEY (deck_id, member_key, item_revision_id)
    REFERENCES app_learning.item_revision(deck_id, member_key, revision_id);

-- ---------------------------------------------------------------------------------------------------------------
-- 5. Lineage projections of a revision.
-- The preview is a rebuildable cache of a lineage revision: it has no deck.
ALTER TABLE app_learning.item_preview DROP CONSTRAINT item_preview_pkey;
ALTER TABLE app_learning.item_preview DROP COLUMN deck_id;
ALTER TABLE app_learning.item_preview ADD CONSTRAINT item_preview_pkey
    PRIMARY KEY (reuse_scope_id, member_key, revision_id);
ALTER TABLE app_learning.item_preview ADD CONSTRAINT item_preview_revision_fkey
    FOREIGN KEY (reuse_scope_id, member_key, revision_id)
    REFERENCES app_learning.item_revision(reuse_scope_id, member_key, revision_id) ON DELETE CASCADE;

-- A media reference belongs to the revision (lineage). deck_id stays as origin; asset_owner_id owns the asset, which the
-- unchanged FK content_media_ref_asset_id_owner_id_fkey (asset_id, asset_owner_id) -> media_asset(asset_id, owner_id) checks.
ALTER TABLE app_learning.content_media_ref DROP CONSTRAINT content_media_ref_pkey;
ALTER TABLE app_learning.content_media_ref ADD CONSTRAINT content_media_ref_pkey
    PRIMARY KEY (reuse_scope_id, member_key, revision_id, node_id);
ALTER TABLE app_learning.content_media_ref ADD CONSTRAINT content_media_ref_revision_fkey
    FOREIGN KEY (reuse_scope_id, member_key, revision_id)
    REFERENCES app_learning.item_revision(reuse_scope_id, member_key, revision_id);

-- ---------------------------------------------------------------------------------------------------------------
-- 6. Per-deck rows: the deck's own scope (immutable on deck), and lineage rows reached through it.
ALTER TABLE app_learning.deck_head_item ADD CONSTRAINT deck_head_item_deck_scope_fkey
    FOREIGN KEY (deck_id, reuse_scope_id) REFERENCES app_learning.deck(deck_id, reuse_scope_id);
ALTER TABLE app_learning.deck_head_item ADD CONSTRAINT deck_head_item_revision_fkey
    FOREIGN KEY (reuse_scope_id, member_key, revision_id, item_sequence)
    REFERENCES app_learning.item_revision(reuse_scope_id, member_key, revision_id, item_sequence)
    DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE app_learning.deck_item_change ADD CONSTRAINT deck_item_change_deck_scope_fkey
    FOREIGN KEY (deck_id, reuse_scope_id) REFERENCES app_learning.deck(deck_id, reuse_scope_id);
ALTER TABLE app_learning.deck_item_change ADD CONSTRAINT deck_item_change_item_fkey
    FOREIGN KEY (reuse_scope_id, member_key) REFERENCES app_learning.learning_item(reuse_scope_id, member_key);
ALTER TABLE app_learning.deck_item_change ADD CONSTRAINT deck_item_change_previous_fkey
    FOREIGN KEY (reuse_scope_id, member_key, previous_revision_id)
    REFERENCES app_learning.item_revision(reuse_scope_id, member_key, revision_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE app_learning.deck_item_change ADD CONSTRAINT deck_item_change_revision_fkey
    FOREIGN KEY (reuse_scope_id, member_key, revision_id)
    REFERENCES app_learning.item_revision(reuse_scope_id, member_key, revision_id) DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE app_learning.deck_item_exemplar ADD CONSTRAINT deck_item_exemplar_deck_scope_fkey
    FOREIGN KEY (deck_id, reuse_scope_id) REFERENCES app_learning.deck(deck_id, reuse_scope_id);
ALTER TABLE app_learning.deck_item_exemplar ADD CONSTRAINT deck_item_exemplar_item_fkey
    FOREIGN KEY (reuse_scope_id, member_key) REFERENCES app_learning.learning_item(reuse_scope_id, member_key);

ALTER TABLE app_learning.editing_draft ADD CONSTRAINT editing_draft_deck_scope_fkey
    FOREIGN KEY (deck_id, reuse_scope_id) REFERENCES app_learning.deck(deck_id, reuse_scope_id);
ALTER TABLE app_learning.editing_draft ADD CONSTRAINT editing_draft_base_fkey
    FOREIGN KEY (reuse_scope_id, member_key, base_revision_id)
    REFERENCES app_learning.item_revision(reuse_scope_id, member_key, revision_id);

ALTER TABLE app_learning.capture_note ADD CONSTRAINT capture_note_deck_scope_fkey
    FOREIGN KEY (deck_id, reuse_scope_id) REFERENCES app_learning.deck(deck_id, reuse_scope_id);
ALTER TABLE app_learning.capture_note ADD CONSTRAINT capture_note_converted_fkey
    FOREIGN KEY (reuse_scope_id, converted_member_key, converted_revision_id)
    REFERENCES app_learning.item_revision(reuse_scope_id, member_key, revision_id) DEFERRABLE INITIALLY DEFERRED;

-- Revision visibility (ItemRevisionVisibility): a deck sees a revision its own change replaced; one index probe per branch.
CREATE INDEX deck_item_change_previous_revision
    ON app_learning.deck_item_change(deck_id, member_key, previous_revision_id) WHERE previous_revision_id IS NOT NULL;

COMMENT ON COLUMN app_learning.item_revision.deck_id IS
    'Origin: the deck whose command wrote this revision. Never a membership filter: a deck owns the revisions its head or journal reaches.';
COMMENT ON COLUMN app_learning.item_revision.owner_id IS
    'Origin: the account that wrote this revision (the owner of deck_id).';
COMMENT ON COLUMN app_learning.item_revision.deck_revision_id IS
    'Origin: the revision of deck_id that published this item revision. Responses use the reading deck''s own rows instead.';
COMMENT ON COLUMN app_learning.content_media_ref.deck_id IS
    'Origin deck of the revision. Media of deck D are the references of D''s heads, joined through (reuse_scope_id, member_key, revision_id).';
COMMENT ON COLUMN app_learning.content_media_ref.asset_owner_id IS
    'Owner of the referenced asset; the revision may have been written by another account (a copy keeping the author''s image).';

-- ---------------------------------------------------------------------------------------------------------------
-- 7. Fail the whole migration if an immutability guard is not enabled again (tgenabled = 'O' means origin/enabled).
DO $guards$
DECLARE
    guarded CONSTANT regclass[] := ARRAY['app_learning.deck_item_change'::regclass, 'app_learning.content_media_ref'::regclass,
        'app_learning.capture_note'::regclass, 'app_learning.editing_draft'::regclass, 'app_learning.item_revision'::regclass,
        'app_learning.learning_item'::regclass];
    expected CONSTANT TEXT[] := ARRAY['deck_item_change_guard', 'content_media_ref_guard', 'capture_note_guard',
        'editing_draft_guard', 'item_revision_guard', 'learning_item_guard'];
    guard TEXT;
BEGIN
    FOREACH guard IN ARRAY expected LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_trigger t
                        WHERE t.tgname = guard AND NOT t.tgisinternal AND t.tgenabled = 'O' AND t.tgrelid = ANY (guarded)) THEN
            RAISE EXCEPTION 'Immutability guard % is missing or not enabled after V51', guard;
        END IF;
    END LOOP;
    IF EXISTS (SELECT 1 FROM pg_trigger t WHERE NOT t.tgisinternal AND t.tgenabled <> 'O'
                AND t.tgrelid = ANY (guarded || ARRAY['app_learning.deck_head_item'::regclass,
                    'app_learning.deck_item_exemplar'::regclass, 'app_learning.item_preview'::regclass])) THEN
        RAISE EXCEPTION 'A trigger on the item tables is not enabled after V51';
    END IF;
END
$guards$;
