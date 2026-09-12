-- Private logical decks and immutable metadata history; no legacy/core references.
CREATE TABLE app_learning.deck (
    deck_id UUID PRIMARY KEY,
    owner_id UUID NOT NULL,
    -- A trusted physical lineage can span independent Deck namespaces; scope is not ownership.
    reuse_scope_id UUID NOT NULL,
    head_revision_id UUID NOT NULL,
    row_version BIGINT NOT NULL CHECK (row_version >= 0),
    created_at TIMESTAMPTZ NOT NULL CHECK (isfinite(created_at)),
    UNIQUE (deck_id, reuse_scope_id, owner_id)
);
CREATE INDEX deck_owner_page ON app_learning.deck(owner_id, created_at DESC, deck_id DESC);

-- Exact pin/root FK prevents accidental release of a published revision's protection.
ALTER TABLE app_learning.storage_pin ADD CONSTRAINT storage_pin_exact_root
    UNIQUE (reuse_scope_id, pin_id, root_id);

CREATE TABLE app_learning.deck_revision (
    deck_id UUID NOT NULL,
    revision_id UUID NOT NULL,
    reuse_scope_id UUID NOT NULL,
    owner_id UUID NOT NULL,
    sequence BIGINT NOT NULL CHECK (sequence >= 0),
    parent_revision_id UUID,
    parent_sequence BIGINT,
    command_id UUID NOT NULL UNIQUE,
    title TEXT NOT NULL CHECK (char_length(title) BETWEEN 1 AND 200 AND octet_length(title) <= 800),
    description TEXT NOT NULL CHECK (octet_length(description) <= 4096),
    created_at TIMESTAMPTZ NOT NULL CHECK (isfinite(created_at)),
    members_root_id UUID NOT NULL,
    exercises_root_id UUID NOT NULL,
    members_pin_id UUID NOT NULL,
    exercises_pin_id UUID NOT NULL,
    member_count INTEGER NOT NULL CHECK (member_count >= 0),
    exercise_count INTEGER NOT NULL CHECK (exercise_count >= 0),
    PRIMARY KEY (deck_id, revision_id),
    UNIQUE (deck_id, sequence),
    UNIQUE (deck_id, revision_id, sequence),
    CHECK (members_root_id <> exercises_root_id AND members_pin_id <> exercises_pin_id),
    CHECK ((sequence = 0 AND parent_revision_id IS NULL AND parent_sequence IS NULL)
        OR (sequence > 0 AND parent_revision_id IS NOT NULL AND parent_sequence IS NOT NULL
            AND parent_sequence = sequence - 1)),
    FOREIGN KEY (deck_id, reuse_scope_id, owner_id)
        REFERENCES app_learning.deck(deck_id, reuse_scope_id, owner_id),
    FOREIGN KEY (deck_id, parent_revision_id, parent_sequence)
        REFERENCES app_learning.deck_revision(deck_id, revision_id, sequence),
    FOREIGN KEY (reuse_scope_id, members_pin_id, members_root_id)
        REFERENCES app_learning.storage_pin(reuse_scope_id, pin_id, root_id),
    FOREIGN KEY (reuse_scope_id, exercises_pin_id, exercises_root_id)
        REFERENCES app_learning.storage_pin(reuse_scope_id, pin_id, root_id)
);

ALTER TABLE app_learning.deck ADD CONSTRAINT deck_exact_head
    FOREIGN KEY (deck_id, head_revision_id, row_version)
    REFERENCES app_learning.deck_revision(deck_id, revision_id, sequence)
    DEFERRABLE INITIALLY DEFERRED;

CREATE FUNCTION app_learning.deck_head_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF (to_jsonb(OLD) - ARRAY['head_revision_id', 'row_version'])
          IS DISTINCT FROM (to_jsonb(NEW) - ARRAY['head_revision_id', 'row_version'])
       OR OLD.row_version = 9223372036854775807 OR NEW.row_version <> OLD.row_version + 1 THEN
        RAISE EXCEPTION 'Invalid deck head transition' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER deck_head_guard BEFORE UPDATE ON app_learning.deck
    FOR EACH ROW EXECUTE FUNCTION app_learning.deck_head_guard();

CREATE FUNCTION app_learning.deck_revision_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'Immutable deck revision' USING ERRCODE = '23514';
    END IF;
    -- Pin targets and owners are immutable in K1. The FK holds against deletion;
    -- this additional domain check prevents borrowing an unrelated durable pin.
    IF (SELECT count(*) FROM app_learning.storage_pin
        WHERE reuse_scope_id = NEW.reuse_scope_id
          AND ((pin_id = NEW.members_pin_id AND root_id = NEW.members_root_id)
            OR (pin_id = NEW.exercises_pin_id AND root_id = NEW.exercises_root_id))
          AND pin_kind = 'durable' AND owner_kind = 'deck.revision'
          AND owner_id = NEW.revision_id AND actor_id = NEW.owner_id) <> 2 THEN
        RAISE EXCEPTION 'Invalid revision protection' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER deck_revision_guard BEFORE INSERT OR UPDATE ON app_learning.deck_revision
    FOR EACH ROW EXECUTE FUNCTION app_learning.deck_revision_guard();
