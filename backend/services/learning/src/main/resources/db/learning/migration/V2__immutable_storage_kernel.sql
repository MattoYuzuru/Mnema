-- Internal physical graph, not a content API or a history-retention policy.
CREATE TABLE app_learning.storage_object (
    reuse_scope_id UUID NOT NULL,
    object_id UUID NOT NULL,
    kind TEXT NOT NULL CHECK (kind IN ('block', 'header', 'fragment', 'page')),
    encoding_version SMALLINT NOT NULL CHECK (encoding_version > 0),
    dag_rank SMALLINT NOT NULL CHECK (dag_rank BETWEEN 0 AND 32),
    edge_count SMALLINT NOT NULL CHECK (edge_count BETWEEN 0 AND 32),
    payload JSONB NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    fingerprint BYTEA NOT NULL CHECK (octet_length(fingerprint) = 32),
    sealed BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp(),
    PRIMARY KEY (reuse_scope_id, object_id),
    UNIQUE (reuse_scope_id, object_id, dag_rank),
    CHECK (octet_length(payload::text) <= 16384),
    CHECK ((kind = 'page' AND dag_rank > 0)
        OR (kind <> 'page' AND dag_rank = 0 AND edge_count = 0))
);

CREATE TABLE app_learning.storage_edge (
    reuse_scope_id UUID NOT NULL,
    parent_id UUID NOT NULL,
    ordinal SMALLINT NOT NULL CHECK (ordinal BETWEEN 0 AND 31),
    parent_rank SMALLINT NOT NULL,
    child_id UUID NOT NULL,
    child_rank SMALLINT NOT NULL,
    logical_key UUID,
    PRIMARY KEY (reuse_scope_id, parent_id, ordinal),
    CHECK (parent_rank > child_rank),
    FOREIGN KEY (reuse_scope_id, parent_id, parent_rank)
        REFERENCES app_learning.storage_object(reuse_scope_id, object_id, dag_rank) ON DELETE CASCADE,
    FOREIGN KEY (reuse_scope_id, child_id, child_rank)
        REFERENCES app_learning.storage_object(reuse_scope_id, object_id, dag_rank)
);
CREATE INDEX storage_edge_incoming ON app_learning.storage_edge(reuse_scope_id, child_id);

CREATE TABLE app_learning.storage_pin (
    reuse_scope_id UUID NOT NULL,
    pin_id UUID NOT NULL,
    root_id UUID NOT NULL,
    pin_kind TEXT NOT NULL CHECK (pin_kind IN ('staging', 'durable')),
    owner_kind VARCHAR(80) NOT NULL CHECK (owner_kind ~ '^[a-z][a-z0-9]*([.-][a-z0-9]+)*$'),
    owner_id UUID NOT NULL,
    actor_id UUID NOT NULL,
    expires_at TIMESTAMPTZ,
    row_version BIGINT NOT NULL DEFAULT 0 CHECK (row_version >= 0),
    PRIMARY KEY (reuse_scope_id, pin_id),
    FOREIGN KEY (reuse_scope_id, root_id)
        REFERENCES app_learning.storage_object(reuse_scope_id, object_id),
    CHECK ((pin_kind = 'staging' AND expires_at IS NOT NULL AND isfinite(expires_at))
        OR (pin_kind = 'durable' AND expires_at IS NULL))
);
CREATE INDEX storage_pin_incoming ON app_learning.storage_pin(reuse_scope_id, root_id);
CREATE INDEX storage_pin_expiry ON app_learning.storage_pin(reuse_scope_id, expires_at, pin_id)
    WHERE expires_at IS NOT NULL;

CREATE TABLE app_learning.storage_gc_candidate (
    reuse_scope_id UUID NOT NULL,
    object_id UUID NOT NULL,
    not_before TIMESTAMPTZ NOT NULL CHECK (isfinite(not_before)),
    PRIMARY KEY (reuse_scope_id, object_id),
    FOREIGN KEY (reuse_scope_id, object_id)
        REFERENCES app_learning.storage_object(reuse_scope_id, object_id) ON DELETE CASCADE
);
CREATE INDEX storage_gc_ready ON app_learning.storage_gc_candidate(reuse_scope_id, not_before, object_id);

CREATE FUNCTION app_learning.storage_object_seal_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.sealed OR NOT NEW.sealed
       OR (to_jsonb(OLD) - 'sealed') IS DISTINCT FROM (to_jsonb(NEW) - 'sealed') THEN
        RAISE EXCEPTION 'Immutable storage object' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER storage_object_seal_guard BEFORE UPDATE ON app_learning.storage_object
    FOR EACH ROW EXECUTE FUNCTION app_learning.storage_object_seal_guard();

CREATE FUNCTION app_learning.storage_edge_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE parent_sealed BOOLEAN;
DECLARE child_sealed BOOLEAN;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'Immutable storage edge' USING ERRCODE = '23514';
    ELSIF TG_OP = 'DELETE' THEN
        -- Parent removal may cascade its own edges; a live parent's edges never change.
        IF EXISTS (SELECT 1 FROM app_learning.storage_object
                   WHERE reuse_scope_id = OLD.reuse_scope_id AND object_id = OLD.parent_id) THEN
            RAISE EXCEPTION 'Immutable storage edge' USING ERRCODE = '23514';
        END IF;
        RETURN OLD;
    END IF;
    -- This lock serializes edge append with sealing, including other connections.
    SELECT sealed INTO parent_sealed FROM app_learning.storage_object
        WHERE reuse_scope_id = NEW.reuse_scope_id AND object_id = NEW.parent_id FOR UPDATE;
    IF parent_sealed IS DISTINCT FROM FALSE THEN
        RAISE EXCEPTION 'Storage parent is not open' USING ERRCODE = '23514';
    END IF;
    SELECT sealed INTO child_sealed FROM app_learning.storage_object
        WHERE reuse_scope_id = NEW.reuse_scope_id AND object_id = NEW.child_id FOR KEY SHARE;
    IF child_sealed IS DISTINCT FROM TRUE THEN
        RAISE EXCEPTION 'Storage child is not sealed' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER storage_edge_guard BEFORE INSERT OR UPDATE OR DELETE ON app_learning.storage_edge
    FOR EACH ROW EXECUTE FUNCTION app_learning.storage_edge_guard();

CREATE FUNCTION app_learning.storage_complete_at_commit() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE object_sealed BOOLEAN;
DECLARE expected_count INTEGER;
DECLARE actual_count INTEGER;
DECLARE final_ordinal INTEGER;
BEGIN
    SELECT sealed, edge_count INTO object_sealed, expected_count FROM app_learning.storage_object
        WHERE reuse_scope_id = NEW.reuse_scope_id AND object_id = NEW.object_id;
    IF NOT FOUND THEN RETURN NULL; END IF;
    SELECT count(*), max(ordinal) INTO actual_count, final_ordinal FROM app_learning.storage_edge
        WHERE reuse_scope_id = NEW.reuse_scope_id AND parent_id = NEW.object_id;
    IF NOT object_sealed OR actual_count <> expected_count
       OR (actual_count > 0 AND final_ordinal <> actual_count - 1) THEN
        RAISE EXCEPTION 'Incomplete storage object' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER storage_complete_at_commit AFTER INSERT ON app_learning.storage_object
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
    EXECUTE FUNCTION app_learning.storage_complete_at_commit();

CREATE FUNCTION app_learning.storage_pin_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE root_sealed BOOLEAN;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF OLD.pin_kind <> 'staging' OR NEW.pin_kind <> 'staging'
           OR OLD.row_version = 9223372036854775807 OR NEW.row_version <> OLD.row_version + 1
           OR (to_jsonb(OLD) - ARRAY['expires_at', 'row_version'])
              IS DISTINCT FROM (to_jsonb(NEW) - ARRAY['expires_at', 'row_version']) THEN
            RAISE EXCEPTION 'Immutable storage pin target' USING ERRCODE = '23514';
        END IF;
    ELSE
        SELECT sealed INTO root_sealed FROM app_learning.storage_object
            WHERE reuse_scope_id = NEW.reuse_scope_id AND object_id = NEW.root_id FOR KEY SHARE;
        IF root_sealed IS DISTINCT FROM TRUE THEN
            RAISE EXCEPTION 'Storage root is not sealed' USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER storage_pin_guard BEFORE INSERT OR UPDATE ON app_learning.storage_pin
    FOR EACH ROW EXECUTE FUNCTION app_learning.storage_pin_guard();
