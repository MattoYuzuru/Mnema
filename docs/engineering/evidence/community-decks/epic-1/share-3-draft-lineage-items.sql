-- DRAFT (Share/3 prototype) of the Share/4 migration: item revisions belong to the lineage (reuse_scope_id).
-- Per-deck rows (heads, journals, drafts, exemplars, capture conversions) carry the deck's immutable scope and
-- reference lineage rows by (reuse_scope_id, member_key, revision_id). deck_id on lineage rows is the origin.
SET search_path = app_learning;

ALTER TABLE deck ADD CONSTRAINT deck_scope UNIQUE (deck_id, reuse_scope_id);

-- Lineage identity of a material (objectives/exercises keep (deck_id, member_key) until Share/5).
ALTER TABLE learning_item ADD CONSTRAINT learning_item_lineage UNIQUE (reuse_scope_id, member_key);

-- 1. Per-deck tables gain the deck's scope.
ALTER TABLE deck_head_item ADD COLUMN reuse_scope_id UUID;
ALTER TABLE deck_item_change ADD COLUMN reuse_scope_id UUID;
ALTER TABLE deck_item_exemplar ADD COLUMN reuse_scope_id UUID;
ALTER TABLE editing_draft ADD COLUMN reuse_scope_id UUID;
ALTER TABLE capture_note ADD COLUMN reuse_scope_id UUID;
ALTER TABLE item_preview ADD COLUMN reuse_scope_id UUID;
ALTER TABLE content_media_ref ADD COLUMN reuse_scope_id UUID;
-- Immutable-row guards forbid UPDATE; the one-time backfill adds a derived column only.
ALTER TABLE deck_item_change DISABLE TRIGGER deck_item_change_guard;
ALTER TABLE content_media_ref DISABLE TRIGGER content_media_ref_guard;
ALTER TABLE capture_note DISABLE TRIGGER capture_note_guard;
ALTER TABLE editing_draft DISABLE TRIGGER editing_draft_guard;
UPDATE deck_head_item t SET reuse_scope_id = d.reuse_scope_id FROM deck d WHERE d.deck_id = t.deck_id;
UPDATE deck_item_change t SET reuse_scope_id = d.reuse_scope_id FROM deck d WHERE d.deck_id = t.deck_id;
UPDATE deck_item_exemplar t SET reuse_scope_id = d.reuse_scope_id FROM deck d WHERE d.deck_id = t.deck_id;
UPDATE editing_draft t SET reuse_scope_id = d.reuse_scope_id FROM deck d WHERE d.deck_id = t.deck_id;
UPDATE capture_note t SET reuse_scope_id = d.reuse_scope_id FROM deck d WHERE d.deck_id = t.deck_id;
UPDATE item_preview t SET reuse_scope_id = d.reuse_scope_id FROM deck d WHERE d.deck_id = t.deck_id;
UPDATE content_media_ref t SET reuse_scope_id = d.reuse_scope_id FROM deck d WHERE d.deck_id = t.deck_id;
ALTER TABLE deck_item_change ENABLE TRIGGER deck_item_change_guard;
ALTER TABLE content_media_ref ENABLE TRIGGER content_media_ref_guard;
ALTER TABLE capture_note ENABLE TRIGGER capture_note_guard;
ALTER TABLE editing_draft ENABLE TRIGGER editing_draft_guard;
ALTER TABLE deck_head_item ALTER COLUMN reuse_scope_id SET NOT NULL;
ALTER TABLE deck_item_change ALTER COLUMN reuse_scope_id SET NOT NULL;
ALTER TABLE deck_item_exemplar ALTER COLUMN reuse_scope_id SET NOT NULL;
ALTER TABLE editing_draft ALTER COLUMN reuse_scope_id SET NOT NULL;
ALTER TABLE capture_note ALTER COLUMN reuse_scope_id SET NOT NULL;
ALTER TABLE item_preview ALTER COLUMN reuse_scope_id SET NOT NULL;
ALTER TABLE content_media_ref ALTER COLUMN reuse_scope_id SET NOT NULL;

-- 2. Drop deck-keyed references to item revisions.
ALTER TABLE capture_note DROP CONSTRAINT capture_note_deck_id_converted_member_key_converted_revisi_fkey;
ALTER TABLE content_media_ref DROP CONSTRAINT content_media_ref_deck_id_member_key_revision_id_owner_id_fkey;
ALTER TABLE deck_head_item DROP CONSTRAINT deck_head_item_deck_id_member_key_revision_id_item_sequenc_fkey;
ALTER TABLE deck_item_change DROP CONSTRAINT deck_item_change_deck_id_member_key_fkey;
ALTER TABLE deck_item_change DROP CONSTRAINT deck_item_change_deck_id_member_key_previous_revision_id_fkey;
ALTER TABLE deck_item_change DROP CONSTRAINT deck_item_change_deck_id_member_key_revision_id_fkey;
ALTER TABLE deck_item_exemplar DROP CONSTRAINT deck_item_exemplar_deck_id_member_key_fkey;
ALTER TABLE editing_draft DROP CONSTRAINT editing_draft_deck_id_member_key_base_revision_id_fkey;
ALTER TABLE item_preview DROP CONSTRAINT item_preview_deck_id_member_key_revision_id_fkey;
ALTER TABLE item_revision DROP CONSTRAINT item_revision_deck_id_member_key_parent_revision_id_parent_fkey;
ALTER TABLE item_revision DROP CONSTRAINT item_revision_deck_id_member_key_reuse_scope_id_owner_id_fkey;

-- Share/5 still references item_revision by its origin key from exercise_content_binding: move that FK to a
-- non-PK unique until Share/5 switches bindings to the lineage.
ALTER TABLE exercise_content_binding DROP CONSTRAINT exercise_content_binding_deck_id_member_key_item_revision__fkey;

-- 3. Re-key item revisions on the lineage. Number uniqueness per material is replaced by the parent chain.
ALTER TABLE item_revision DROP CONSTRAINT item_revision_pkey;
ALTER TABLE item_revision DROP CONSTRAINT item_revision_deck_id_member_key_item_sequence_key;
ALTER TABLE item_revision DROP CONSTRAINT item_revision_deck_id_member_key_revision_id_item_sequence_key;
ALTER TABLE item_revision DROP CONSTRAINT item_revision_deck_id_revision_id_key;
ALTER TABLE item_revision DROP CONSTRAINT item_revision_media_owner;
ALTER TABLE item_revision ADD PRIMARY KEY (reuse_scope_id, member_key, revision_id);
ALTER TABLE item_revision ADD CONSTRAINT item_revision_lineage_id UNIQUE (reuse_scope_id, revision_id);
ALTER TABLE item_revision ADD CONSTRAINT item_revision_lineage_sequence UNIQUE (reuse_scope_id, member_key, revision_id, item_sequence);
ALTER TABLE item_revision ADD CONSTRAINT item_revision_media_owner UNIQUE (reuse_scope_id, member_key, revision_id, owner_id);
ALTER TABLE item_revision ADD FOREIGN KEY (reuse_scope_id, member_key)
    REFERENCES learning_item(reuse_scope_id, member_key);
ALTER TABLE item_revision ADD FOREIGN KEY (reuse_scope_id, member_key, parent_revision_id, parent_item_sequence)
    REFERENCES item_revision(reuse_scope_id, member_key, revision_id, item_sequence);
ALTER TABLE item_revision ADD CONSTRAINT item_revision_origin_key UNIQUE (deck_id, member_key, revision_id);
ALTER TABLE exercise_content_binding ADD FOREIGN KEY (deck_id, member_key, item_revision_id)
    REFERENCES item_revision(deck_id, member_key, revision_id);
-- Origin columns: (deck_id, deck_revision_id) still references the authoring deck revision (decks are never deleted).

-- 4. Lineage projections of a revision.
ALTER TABLE item_preview DROP CONSTRAINT item_preview_pkey;
ALTER TABLE item_preview ADD PRIMARY KEY (reuse_scope_id, member_key, revision_id);
ALTER TABLE item_preview ADD FOREIGN KEY (reuse_scope_id, member_key, revision_id)
    REFERENCES item_revision(reuse_scope_id, member_key, revision_id) ON DELETE CASCADE;
ALTER TABLE content_media_ref DROP CONSTRAINT content_media_ref_pkey;
ALTER TABLE content_media_ref ADD PRIMARY KEY (reuse_scope_id, member_key, revision_id, node_id);
ALTER TABLE content_media_ref ADD FOREIGN KEY (reuse_scope_id, member_key, revision_id, owner_id)
    REFERENCES item_revision(reuse_scope_id, member_key, revision_id, owner_id);

-- 5. Per-deck rows reference the lineage through their own deck's scope.
ALTER TABLE deck_head_item ADD FOREIGN KEY (deck_id, reuse_scope_id) REFERENCES deck(deck_id, reuse_scope_id);
ALTER TABLE deck_head_item ADD FOREIGN KEY (reuse_scope_id, member_key, revision_id, item_sequence)
    REFERENCES item_revision(reuse_scope_id, member_key, revision_id, item_sequence) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE deck_item_change ADD FOREIGN KEY (deck_id, reuse_scope_id) REFERENCES deck(deck_id, reuse_scope_id);
ALTER TABLE deck_item_change ADD FOREIGN KEY (reuse_scope_id, member_key) REFERENCES learning_item(reuse_scope_id, member_key);
ALTER TABLE deck_item_change ADD FOREIGN KEY (reuse_scope_id, member_key, previous_revision_id)
    REFERENCES item_revision(reuse_scope_id, member_key, revision_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE deck_item_change ADD FOREIGN KEY (reuse_scope_id, member_key, revision_id)
    REFERENCES item_revision(reuse_scope_id, member_key, revision_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE deck_item_exemplar ADD FOREIGN KEY (deck_id, reuse_scope_id) REFERENCES deck(deck_id, reuse_scope_id);
ALTER TABLE deck_item_exemplar ADD FOREIGN KEY (reuse_scope_id, member_key) REFERENCES learning_item(reuse_scope_id, member_key);
ALTER TABLE editing_draft ADD FOREIGN KEY (deck_id, reuse_scope_id) REFERENCES deck(deck_id, reuse_scope_id);
ALTER TABLE editing_draft ADD FOREIGN KEY (reuse_scope_id, member_key, base_revision_id)
    REFERENCES item_revision(reuse_scope_id, member_key, revision_id);
ALTER TABLE capture_note ADD FOREIGN KEY (deck_id, reuse_scope_id) REFERENCES deck(deck_id, reuse_scope_id);
ALTER TABLE capture_note ADD FOREIGN KEY (reuse_scope_id, converted_member_key, converted_revision_id)
    REFERENCES item_revision(reuse_scope_id, member_key, revision_id) DEFERRABLE INITIALLY DEFERRED;

