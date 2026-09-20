-- Stable study objectives and immutable exercise publication history.
CREATE TABLE app_learning.memory_objective (
    deck_id UUID NOT NULL,
    objective_id UUID NOT NULL,
    objective_key UUID NOT NULL,
    member_key UUID NOT NULL,
    owner_id UUID NOT NULL,
    reuse_scope_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL CHECK (isfinite(created_at)),
    PRIMARY KEY (deck_id, objective_id),
    UNIQUE (deck_id, objective_key),
    UNIQUE (deck_id, member_key, objective_id),
    FOREIGN KEY (deck_id, member_key) REFERENCES app_learning.learning_item(deck_id, member_key),
    FOREIGN KEY (deck_id, reuse_scope_id, owner_id)
        REFERENCES app_learning.deck(deck_id, reuse_scope_id, owner_id)
);

CREATE TABLE app_learning.objective_revision (
    deck_id UUID NOT NULL,
    objective_id UUID NOT NULL,
    revision_id UUID NOT NULL,
    objective_sequence BIGINT NOT NULL CHECK (objective_sequence >= 0),
    parent_revision_id UUID,
    parent_objective_sequence BIGINT,
    deck_revision_id UUID NOT NULL,
    deck_sequence BIGINT NOT NULL CHECK (deck_sequence >= 1),
    command_id UUID NOT NULL,
    answer_contract JSONB NOT NULL CHECK (jsonb_typeof(answer_contract) = 'object'),
    created_at TIMESTAMPTZ NOT NULL CHECK (isfinite(created_at)),
    PRIMARY KEY (deck_id, objective_id, revision_id),
    UNIQUE (deck_id, revision_id),
    UNIQUE (deck_id, objective_id, objective_sequence),
    UNIQUE (deck_id, objective_id, revision_id, objective_sequence),
    CHECK ((objective_sequence = 0 AND parent_revision_id IS NULL AND parent_objective_sequence IS NULL)
        OR (objective_sequence > 0 AND parent_revision_id IS NOT NULL
            AND parent_objective_sequence = objective_sequence - 1)),
    FOREIGN KEY (deck_id, objective_id) REFERENCES app_learning.memory_objective(deck_id, objective_id),
    FOREIGN KEY (deck_id, objective_id, parent_revision_id, parent_objective_sequence)
        REFERENCES app_learning.objective_revision(deck_id, objective_id, revision_id, objective_sequence),
    FOREIGN KEY (deck_id, deck_revision_id, deck_sequence)
        REFERENCES app_learning.deck_revision(deck_id, revision_id, sequence) DEFERRABLE INITIALLY DEFERRED
);

CREATE TABLE app_learning.objective_head (
    deck_id UUID NOT NULL,
    objective_id UUID NOT NULL,
    revision_id UUID NOT NULL,
    objective_sequence BIGINT NOT NULL CHECK (objective_sequence >= 0),
    updated_at TIMESTAMPTZ NOT NULL CHECK (isfinite(updated_at)),
    PRIMARY KEY (deck_id, objective_id),
    FOREIGN KEY (deck_id, objective_id, revision_id, objective_sequence)
        REFERENCES app_learning.objective_revision(deck_id, objective_id, revision_id, objective_sequence)
        DEFERRABLE INITIALLY DEFERRED
);

CREATE TABLE app_learning.exercise_definition (
    deck_id UUID NOT NULL,
    exercise_id UUID NOT NULL,
    owner_id UUID NOT NULL,
    reuse_scope_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL CHECK (isfinite(created_at)),
    PRIMARY KEY (deck_id, exercise_id),
    FOREIGN KEY (deck_id, reuse_scope_id, owner_id)
        REFERENCES app_learning.deck(deck_id, reuse_scope_id, owner_id)
);

CREATE TABLE app_learning.exercise_revision (
    deck_id UUID NOT NULL,
    exercise_id UUID NOT NULL,
    revision_id UUID NOT NULL,
    reuse_scope_id UUID NOT NULL,
    exercise_sequence BIGINT NOT NULL CHECK (exercise_sequence >= 0),
    parent_revision_id UUID,
    parent_exercise_sequence BIGINT,
    deck_revision_id UUID NOT NULL,
    deck_sequence BIGINT NOT NULL CHECK (deck_sequence >= 1),
    command_id UUID NOT NULL,
    exercise_type TEXT NOT NULL CHECK (exercise_type IN ('SELF_CHECK','TYPED','CLOZE_SINGLE','SINGLE_CHOICE')),
    schema_version INTEGER NOT NULL CHECK (schema_version = 1),
    enabled BOOLEAN NOT NULL,
    prompt_spec JSONB NOT NULL CHECK (jsonb_typeof(prompt_spec) = 'object'),
    evaluator_policy JSONB NOT NULL CHECK (jsonb_typeof(evaluator_policy) = 'object'),
    descriptor_root_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL CHECK (isfinite(created_at)),
    PRIMARY KEY (deck_id, exercise_id, revision_id),
    UNIQUE (deck_id, revision_id),
    UNIQUE (deck_id, exercise_id, exercise_sequence),
    UNIQUE (deck_id, exercise_id, revision_id, exercise_sequence),
    CHECK ((exercise_sequence = 0 AND parent_revision_id IS NULL AND parent_exercise_sequence IS NULL)
        OR (exercise_sequence > 0 AND parent_revision_id IS NOT NULL
            AND parent_exercise_sequence = exercise_sequence - 1)),
    FOREIGN KEY (deck_id, exercise_id) REFERENCES app_learning.exercise_definition(deck_id, exercise_id),
    FOREIGN KEY (deck_id, exercise_id, parent_revision_id, parent_exercise_sequence)
        REFERENCES app_learning.exercise_revision(deck_id, exercise_id, revision_id, exercise_sequence),
    FOREIGN KEY (deck_id, deck_revision_id, deck_sequence)
        REFERENCES app_learning.deck_revision(deck_id, revision_id, sequence) DEFERRABLE INITIALLY DEFERRED,
    FOREIGN KEY (reuse_scope_id, descriptor_root_id)
        REFERENCES app_learning.storage_object(reuse_scope_id, object_id)
);

CREATE TABLE app_learning.exercise_content_binding (
    deck_id UUID NOT NULL,
    exercise_id UUID NOT NULL,
    exercise_revision_id UUID NOT NULL,
    binding_id UUID NOT NULL,
    binding_ordinal SMALLINT NOT NULL CHECK (binding_ordinal BETWEEN 0 AND 15),
    role TEXT NOT NULL CHECK (role IN ('ASSESSED','CUE','OPTION','CONTEXT')),
    member_key UUID NOT NULL,
    item_revision_id UUID NOT NULL,
    objective_id UUID,
    objective_revision_id UUID,
    node_ids UUID[] NOT NULL,
    display_spec JSONB NOT NULL CHECK (jsonb_typeof(display_spec) = 'object'),
    PRIMARY KEY (deck_id, exercise_id, exercise_revision_id, binding_id),
    UNIQUE (deck_id, exercise_id, exercise_revision_id, binding_ordinal),
    CHECK ((role = 'ASSESSED' AND objective_id IS NOT NULL AND objective_revision_id IS NOT NULL)
        OR (role <> 'ASSESSED' AND objective_id IS NULL AND objective_revision_id IS NULL)),
    FOREIGN KEY (deck_id, exercise_id, exercise_revision_id)
        REFERENCES app_learning.exercise_revision(deck_id, exercise_id, revision_id),
    FOREIGN KEY (deck_id, member_key, item_revision_id)
        REFERENCES app_learning.item_revision(deck_id, member_key, revision_id),
    FOREIGN KEY (deck_id, objective_id, objective_revision_id)
        REFERENCES app_learning.objective_revision(deck_id, objective_id, revision_id)
);
CREATE UNIQUE INDEX one_assessed_binding_per_exercise_revision
    ON app_learning.exercise_content_binding(deck_id, exercise_id, exercise_revision_id)
    WHERE role = 'ASSESSED';

CREATE TABLE app_learning.deck_head_exercise (
    deck_id UUID NOT NULL,
    exercise_id UUID NOT NULL,
    revision_id UUID NOT NULL,
    exercise_sequence BIGINT NOT NULL CHECK (exercise_sequence >= 0),
    ordinal INTEGER NOT NULL CHECK (ordinal BETWEEN 0 AND 99999),
    updated_at TIMESTAMPTZ NOT NULL CHECK (isfinite(updated_at)),
    PRIMARY KEY (deck_id, exercise_id),
    UNIQUE (deck_id, ordinal),
    FOREIGN KEY (deck_id, exercise_id, revision_id, exercise_sequence)
        REFERENCES app_learning.exercise_revision(deck_id, exercise_id, revision_id, exercise_sequence)
        DEFERRABLE INITIALLY DEFERRED
);

CREATE TABLE app_learning.deck_exercise_change (
    deck_id UUID NOT NULL,
    deck_revision_id UUID NOT NULL,
    deck_sequence BIGINT NOT NULL,
    exercise_id UUID NOT NULL,
    previous_revision_id UUID,
    revision_id UUID NOT NULL,
    ordinal INTEGER NOT NULL CHECK (ordinal BETWEEN 0 AND 99999),
    PRIMARY KEY (deck_id, deck_revision_id),
    FOREIGN KEY (deck_id, deck_revision_id, deck_sequence)
        REFERENCES app_learning.deck_revision(deck_id, revision_id, sequence) DEFERRABLE INITIALLY DEFERRED,
    FOREIGN KEY (deck_id, exercise_id, previous_revision_id)
        REFERENCES app_learning.exercise_revision(deck_id, exercise_id, revision_id) DEFERRABLE INITIALLY DEFERRED,
    FOREIGN KEY (deck_id, exercise_id, revision_id)
        REFERENCES app_learning.exercise_revision(deck_id, exercise_id, revision_id) DEFERRABLE INITIALLY DEFERRED
);

CREATE FUNCTION app_learning.study_authoring_immutable_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Immutable study authoring history' USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER memory_objective_immutable BEFORE UPDATE OR DELETE ON app_learning.memory_objective
    FOR EACH ROW EXECUTE FUNCTION app_learning.study_authoring_immutable_guard();
CREATE TRIGGER objective_revision_immutable BEFORE UPDATE OR DELETE ON app_learning.objective_revision
    FOR EACH ROW EXECUTE FUNCTION app_learning.study_authoring_immutable_guard();
CREATE TRIGGER exercise_definition_immutable BEFORE UPDATE OR DELETE ON app_learning.exercise_definition
    FOR EACH ROW EXECUTE FUNCTION app_learning.study_authoring_immutable_guard();
CREATE TRIGGER exercise_revision_immutable BEFORE UPDATE OR DELETE ON app_learning.exercise_revision
    FOR EACH ROW EXECUTE FUNCTION app_learning.study_authoring_immutable_guard();
CREATE TRIGGER exercise_binding_immutable BEFORE UPDATE OR DELETE ON app_learning.exercise_content_binding
    FOR EACH ROW EXECUTE FUNCTION app_learning.study_authoring_immutable_guard();
CREATE TRIGGER deck_exercise_change_immutable BEFORE UPDATE OR DELETE ON app_learning.deck_exercise_change
    FOR EACH ROW EXECUTE FUNCTION app_learning.study_authoring_immutable_guard();

CREATE FUNCTION app_learning.exercise_revision_descriptor_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE descriptor app_learning.storage_object%ROWTYPE;
BEGIN
    SELECT * INTO descriptor FROM app_learning.storage_object
      WHERE reuse_scope_id = NEW.reuse_scope_id AND object_id = NEW.descriptor_root_id AND sealed;
    IF descriptor.kind IS DISTINCT FROM 'page' OR descriptor.encoding_version IS DISTINCT FROM 1
       OR descriptor.dag_rank IS DISTINCT FROM 9 OR descriptor.edge_count IS DISTINCT FROM 0
       OR descriptor.payload IS DISTINCT FROM jsonb_build_object(
            'codec', 1, 'role', 'exercise', 'formatVersion', 1,
            'exerciseId', NEW.exercise_id::text, 'exerciseRevisionId', NEW.revision_id::text,
            'type', NEW.exercise_type) THEN
        RAISE EXCEPTION 'Invalid exercise descriptor' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER exercise_revision_descriptor_guard BEFORE INSERT ON app_learning.exercise_revision
    FOR EACH ROW EXECUTE FUNCTION app_learning.exercise_revision_descriptor_guard();

CREATE FUNCTION app_learning.exercise_binding_objective_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.role = 'ASSESSED' AND NOT EXISTS (
        SELECT 1 FROM app_learning.memory_objective objective
         WHERE objective.deck_id = NEW.deck_id AND objective.objective_id = NEW.objective_id
           AND objective.member_key = NEW.member_key
    ) THEN
        RAISE EXCEPTION 'Assessed objective must belong to the assessed material' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER exercise_binding_objective_guard BEFORE INSERT ON app_learning.exercise_content_binding
    FOR EACH ROW EXECUTE FUNCTION app_learning.exercise_binding_objective_guard();
