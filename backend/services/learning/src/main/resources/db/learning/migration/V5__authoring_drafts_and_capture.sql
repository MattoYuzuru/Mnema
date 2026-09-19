-- Recoverable unpublished native drafts and durable quick-capture notes.
ALTER TABLE app_learning.deck ADD CONSTRAINT deck_owner_identity UNIQUE (deck_id, owner_id);

CREATE TABLE app_learning.editing_draft (
    draft_id UUID PRIMARY KEY,
    owner_id UUID NOT NULL,
    deck_id UUID NOT NULL,
    member_key UUID,
    base_revision_id UUID,
    row_version BIGINT NOT NULL CHECK (row_version >= 0),
    document JSONB NOT NULL,
    content_bytes INTEGER GENERATED ALWAYS AS (octet_length(document::text)) STORED,
    created_at TIMESTAMPTZ NOT NULL CHECK (isfinite(created_at)),
    acknowledged_at TIMESTAMPTZ NOT NULL CHECK (isfinite(acknowledged_at)),
    expires_at TIMESTAMPTZ NOT NULL CHECK (isfinite(expires_at)),
    CHECK (content_bytes BETWEEN 1 AND 1048576),
    CHECK ((member_key IS NULL AND base_revision_id IS NULL)
        OR (member_key IS NOT NULL AND base_revision_id IS NOT NULL)),
    CHECK (acknowledged_at >= created_at AND expires_at = acknowledged_at + interval '30 days'),
    FOREIGN KEY (deck_id, owner_id) REFERENCES app_learning.deck(deck_id, owner_id),
    FOREIGN KEY (deck_id, member_key, base_revision_id)
        REFERENCES app_learning.item_revision(deck_id, member_key, revision_id)
);
CREATE INDEX editing_draft_owner_page
    ON app_learning.editing_draft(owner_id, created_at DESC, draft_id DESC);
CREATE INDEX editing_draft_expiry ON app_learning.editing_draft(expires_at);

CREATE FUNCTION app_learning.editing_draft_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND (OLD.draft_id, OLD.owner_id, OLD.deck_id, OLD.member_key,
            OLD.base_revision_id, OLD.created_at)
        IS DISTINCT FROM (NEW.draft_id, NEW.owner_id, NEW.deck_id, NEW.member_key,
            NEW.base_revision_id, NEW.created_at) THEN
        RAISE EXCEPTION 'Immutable draft context' USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'UPDATE' AND (OLD.row_version = 9223372036854775807
            OR NEW.row_version <> OLD.row_version + 1
            OR NEW.acknowledged_at < OLD.acknowledged_at) THEN
        RAISE EXCEPTION 'Invalid draft acknowledgement transition' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER editing_draft_guard BEFORE UPDATE ON app_learning.editing_draft
    FOR EACH ROW EXECUTE FUNCTION app_learning.editing_draft_guard();

CREATE TABLE app_learning.capture_note (
    note_id UUID PRIMARY KEY,
    owner_id UUID NOT NULL,
    deck_id UUID NOT NULL,
    row_version BIGINT NOT NULL CHECK (row_version >= 0),
    source TEXT NOT NULL CHECK (octet_length(source) BETWEEN 1 AND 2048),
    note_text TEXT NOT NULL CHECK (octet_length(note_text) BETWEEN 1 AND 32768),
    content_bytes INTEGER GENERATED ALWAYS AS (octet_length(source) + octet_length(note_text)) STORED,
    archived BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL CHECK (isfinite(created_at)),
    updated_at TIMESTAMPTZ NOT NULL CHECK (isfinite(updated_at)),
    conversion_command_id UUID UNIQUE,
    conversion_payload_hash BYTEA,
    converted_member_key UUID,
    converted_revision_id UUID,
    converted_at TIMESTAMPTZ,
    conversion_result JSONB,
    CHECK (updated_at >= created_at),
    CHECK (content_bytes BETWEEN 2 AND 34816),
    CHECK ((conversion_command_id IS NULL AND conversion_payload_hash IS NULL
                AND converted_member_key IS NULL AND converted_revision_id IS NULL
                AND converted_at IS NULL AND conversion_result IS NULL)
        OR (conversion_command_id IS NOT NULL AND octet_length(conversion_payload_hash) = 32
                AND converted_member_key IS NOT NULL AND converted_revision_id IS NOT NULL
                AND converted_at IS NOT NULL AND isfinite(converted_at) AND converted_at >= created_at
                AND conversion_result IS NOT NULL)),
    FOREIGN KEY (deck_id, owner_id) REFERENCES app_learning.deck(deck_id, owner_id),
    FOREIGN KEY (conversion_command_id) REFERENCES app_learning.command_receipt(command_id),
    FOREIGN KEY (deck_id, converted_member_key, converted_revision_id)
        REFERENCES app_learning.item_revision(deck_id, member_key, revision_id)
        DEFERRABLE INITIALLY DEFERRED
);
CREATE INDEX capture_note_owner_page
    ON app_learning.capture_note(owner_id, created_at DESC, note_id DESC);

CREATE FUNCTION app_learning.capture_note_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF (OLD.note_id, OLD.owner_id, OLD.deck_id, OLD.created_at)
        IS DISTINCT FROM (NEW.note_id, NEW.owner_id, NEW.deck_id, NEW.created_at)
       OR OLD.row_version = 9223372036854775807 OR NEW.row_version <> OLD.row_version + 1
       OR NEW.updated_at < OLD.updated_at THEN
        RAISE EXCEPTION 'Invalid capture transition' USING ERRCODE = '23514';
    END IF;
    IF OLD.conversion_command_id IS NOT NULL AND
       (OLD.source, OLD.note_text, OLD.conversion_command_id, OLD.conversion_payload_hash,
        OLD.converted_member_key, OLD.converted_revision_id, OLD.converted_at, OLD.conversion_result)
       IS DISTINCT FROM
       (NEW.source, NEW.note_text, NEW.conversion_command_id, NEW.conversion_payload_hash,
        NEW.converted_member_key, NEW.converted_revision_id, NEW.converted_at, NEW.conversion_result) THEN
        RAISE EXCEPTION 'Converted capture source is immutable' USING ERRCODE = '23514';
    END IF;
    IF OLD.conversion_command_id IS NOT NULL AND NEW.conversion_command_id IS NULL THEN
        RAISE EXCEPTION 'Capture conversion cannot be removed' USING ERRCODE = '23514';
    END IF;
    IF OLD.conversion_command_id IS NULL AND NEW.conversion_command_id IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM app_learning.command_receipt receipt
            WHERE receipt.command_id = NEW.conversion_command_id
              AND receipt.actor_id = NEW.owner_id
              AND receipt.command_scope = 'deck.items' AND receipt.command_type = 'item.publish'
              AND receipt.result = NEW.conversion_result->'publication') THEN
        RAISE EXCEPTION 'Capture conversion receipt mismatch' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER capture_note_guard BEFORE UPDATE ON app_learning.capture_note
    FOR EACH ROW EXECUTE FUNCTION app_learning.capture_note_guard();
