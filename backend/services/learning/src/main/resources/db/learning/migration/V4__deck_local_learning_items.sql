-- Deck-local LearningItems, immutable content revisions and rebuildable current projection.
CREATE TABLE app_learning.learning_item (
    deck_id UUID NOT NULL,
    member_key UUID NOT NULL,
    owner_id UUID NOT NULL,
    reuse_scope_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL CHECK (isfinite(created_at)),
    PRIMARY KEY (deck_id, member_key),
    UNIQUE (deck_id, member_key, reuse_scope_id, owner_id),
    FOREIGN KEY (deck_id, reuse_scope_id, owner_id)
        REFERENCES app_learning.deck(deck_id, reuse_scope_id, owner_id)
);

CREATE TABLE app_learning.item_revision (
    deck_id UUID NOT NULL,
    member_key UUID NOT NULL,
    revision_id UUID NOT NULL,
    reuse_scope_id UUID NOT NULL,
    owner_id UUID NOT NULL,
    item_sequence BIGINT NOT NULL CHECK (item_sequence >= 0),
    parent_revision_id UUID,
    parent_item_sequence BIGINT,
    deck_revision_id UUID NOT NULL,
    deck_sequence BIGINT NOT NULL CHECK (deck_sequence >= 1),
    command_id UUID NOT NULL,
    format_version INTEGER NOT NULL CHECK (format_version = 1),
    content_root_id UUID NOT NULL,
    descriptor_root_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL CHECK (isfinite(created_at)),
    PRIMARY KEY (deck_id, member_key, revision_id),
    UNIQUE (deck_id, revision_id),
    UNIQUE (deck_id, member_key, item_sequence),
    UNIQUE (deck_id, member_key, revision_id, item_sequence),
    UNIQUE (deck_id, member_key, deck_revision_id),
    CHECK (content_root_id <> descriptor_root_id),
    CHECK ((item_sequence = 0 AND parent_revision_id IS NULL AND parent_item_sequence IS NULL)
        OR (item_sequence > 0 AND parent_revision_id IS NOT NULL AND parent_item_sequence = item_sequence - 1)),
    FOREIGN KEY (deck_id, member_key, reuse_scope_id, owner_id)
        REFERENCES app_learning.learning_item(deck_id, member_key, reuse_scope_id, owner_id),
    FOREIGN KEY (deck_id, member_key, parent_revision_id, parent_item_sequence)
        REFERENCES app_learning.item_revision(deck_id, member_key, revision_id, item_sequence),
    FOREIGN KEY (deck_id, deck_revision_id, deck_sequence)
        REFERENCES app_learning.deck_revision(deck_id, revision_id, sequence)
        DEFERRABLE INITIALLY DEFERRED,
    FOREIGN KEY (reuse_scope_id, content_root_id)
        REFERENCES app_learning.storage_object(reuse_scope_id, object_id),
    FOREIGN KEY (reuse_scope_id, descriptor_root_id)
        REFERENCES app_learning.storage_object(reuse_scope_id, object_id)
);
CREATE INDEX item_revision_history
    ON app_learning.item_revision(deck_id, member_key, item_sequence DESC);

CREATE TABLE app_learning.deck_head_item (
    deck_id UUID NOT NULL,
    member_key UUID NOT NULL,
    revision_id UUID NOT NULL,
    item_sequence BIGINT NOT NULL CHECK (item_sequence >= 0),
    ordinal INTEGER NOT NULL CHECK (ordinal >= 0 AND ordinal < 100000),
    updated_at TIMESTAMPTZ NOT NULL CHECK (isfinite(updated_at)),
    PRIMARY KEY (deck_id, member_key),
    UNIQUE (deck_id, ordinal) DEFERRABLE INITIALLY DEFERRED,
    FOREIGN KEY (deck_id, member_key, revision_id, item_sequence)
        REFERENCES app_learning.item_revision(deck_id, member_key, revision_id, item_sequence)
        DEFERRABLE INITIALLY DEFERRED
);

CREATE TABLE app_learning.deck_item_change (
    deck_id UUID NOT NULL,
    deck_revision_id UUID NOT NULL,
    deck_sequence BIGINT NOT NULL,
    change_ordinal SMALLINT NOT NULL CHECK (change_ordinal BETWEEN 0 AND 99),
    member_key UUID NOT NULL,
    change_kind TEXT NOT NULL CHECK (change_kind IN ('create', 'save', 'delete', 'reorder')),
    previous_revision_id UUID,
    revision_id UUID,
    from_ordinal INTEGER CHECK (from_ordinal IS NULL OR from_ordinal BETWEEN 0 AND 99999),
    to_ordinal INTEGER CHECK (to_ordinal IS NULL OR to_ordinal BETWEEN 0 AND 99999),
    PRIMARY KEY (deck_id, deck_revision_id, change_ordinal),
    FOREIGN KEY (deck_id, deck_revision_id, deck_sequence)
        REFERENCES app_learning.deck_revision(deck_id, revision_id, sequence)
        DEFERRABLE INITIALLY DEFERRED,
    FOREIGN KEY (deck_id, member_key)
        REFERENCES app_learning.learning_item(deck_id, member_key),
    FOREIGN KEY (deck_id, member_key, previous_revision_id)
        REFERENCES app_learning.item_revision(deck_id, member_key, revision_id)
        DEFERRABLE INITIALLY DEFERRED,
    FOREIGN KEY (deck_id, member_key, revision_id)
        REFERENCES app_learning.item_revision(deck_id, member_key, revision_id)
        DEFERRABLE INITIALLY DEFERRED,
    CHECK ((change_kind = 'create' AND previous_revision_id IS NULL AND revision_id IS NOT NULL
                AND from_ordinal IS NULL AND to_ordinal IS NOT NULL)
        OR (change_kind = 'save' AND previous_revision_id IS NOT NULL AND revision_id IS NOT NULL
                AND from_ordinal IS NOT NULL AND to_ordinal IS NOT NULL)
        OR (change_kind = 'delete' AND previous_revision_id IS NOT NULL AND revision_id IS NULL
                AND from_ordinal IS NOT NULL AND to_ordinal IS NULL)
        OR (change_kind = 'reorder' AND previous_revision_id IS NOT NULL AND revision_id IS NULL
                AND from_ordinal IS NOT NULL AND to_ordinal IS NOT NULL))
);
CREATE UNIQUE INDEX deck_item_change_revision
    ON app_learning.deck_item_change(deck_id, member_key, revision_id)
    WHERE revision_id IS NOT NULL;

CREATE FUNCTION app_learning.learning_item_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'Immutable learning item identity' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER learning_item_guard BEFORE UPDATE ON app_learning.learning_item
    FOR EACH ROW EXECUTE FUNCTION app_learning.learning_item_guard();

CREATE FUNCTION app_learning.item_revision_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE descriptor app_learning.storage_object%ROWTYPE;
DECLARE edge app_learning.storage_edge%ROWTYPE;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'Immutable item revision' USING ERRCODE = '23514';
    END IF;
    SELECT * INTO descriptor FROM app_learning.storage_object
      WHERE reuse_scope_id = NEW.reuse_scope_id AND object_id = NEW.descriptor_root_id AND sealed;
    SELECT * INTO edge FROM app_learning.storage_edge
      WHERE reuse_scope_id = NEW.reuse_scope_id AND parent_id = NEW.descriptor_root_id AND ordinal = 0;
    IF descriptor.kind IS DISTINCT FROM 'page' OR descriptor.encoding_version IS DISTINCT FROM 1
       OR descriptor.dag_rank IS DISTINCT FROM 9 OR descriptor.edge_count IS DISTINCT FROM 1
       OR descriptor.payload IS DISTINCT FROM jsonb_build_object(
            'codec', 1, 'role', 'item', 'formatVersion', 1,
            'memberKey', NEW.member_key::text, 'itemRevisionId', NEW.revision_id::text)
       OR edge.logical_key IS NOT NULL OR edge.child_id IS DISTINCT FROM NEW.content_root_id
       OR edge.child_rank IS DISTINCT FROM 8 THEN
        RAISE EXCEPTION 'Invalid item descriptor' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER item_revision_guard BEFORE INSERT OR UPDATE ON app_learning.item_revision
    FOR EACH ROW EXECUTE FUNCTION app_learning.item_revision_guard();

CREATE FUNCTION app_learning.deck_item_change_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'Immutable item change' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER deck_item_change_guard BEFORE UPDATE OR DELETE ON app_learning.deck_item_change
    FOR EACH ROW EXECUTE FUNCTION app_learning.deck_item_change_guard();
