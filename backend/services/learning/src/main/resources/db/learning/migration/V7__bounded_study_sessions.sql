-- Immutable candidate generations and bounded, deck-scoped Study session snapshots.
CREATE TABLE app_learning.scheduler_config (
    config_id UUID PRIMARY KEY,
    reducer_id TEXT NOT NULL,
    reducer_version TEXT NOT NULL,
    config_hash TEXT NOT NULL,
    config JSONB NOT NULL CHECK (jsonb_typeof(config) = 'object'),
    created_at TIMESTAMPTZ NOT NULL CHECK (isfinite(created_at)),
    UNIQUE (reducer_id, reducer_version, config_hash)
);

INSERT INTO app_learning.scheduler_config(config_id,reducer_id,reducer_version,config_hash,config,created_at)
VALUES ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1','mnema-baseline','1',
        'sha256:9b70f5513a223f50f8358fd1033798b0037d5dcbc66e5f908239b8f3f8421718',
        '{"configId":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1","intervals":["PT10M","PT4H","P1D","P3D","P7D","P14D","P30D","P60D"],"reducerId":"mnema-baseline","reducerVersion":"1","transitions":[{"cap":7,"evidenceClass":"HIGH","lapse":"KEEP","levels":2,"operation":"ADD","result":"CORRECT","streak":"INCREMENT"},{"cap":6,"evidenceClass":"MEDIUM","lapse":"KEEP","levels":1,"operation":"ADD","result":"CORRECT","streak":"INCREMENT"},{"cap":3,"evidenceClass":"LOW","lapse":"KEEP","levels":1,"operation":"ADD","result":"CORRECT","streak":"INCREMENT"},{"cap":7,"evidenceClass":"HIGH","lapse":"KEEP","levels":1,"operation":"SUBTRACT","result":"PARTIAL","streak":"RESET"},{"cap":7,"evidenceClass":"MEDIUM","lapse":"KEEP","levels":1,"operation":"SUBTRACT","result":"PARTIAL","streak":"RESET"},{"cap":7,"evidenceClass":"LOW","lapse":"KEEP","levels":1,"operation":"SUBTRACT","result":"PARTIAL","streak":"RESET"},{"cap":7,"evidenceClass":"HIGH","lapse":"INCREMENT","levels":1,"operation":"SUBTRACT","result":"UNSURE","streak":"RESET"},{"cap":7,"evidenceClass":"MEDIUM","lapse":"INCREMENT","levels":1,"operation":"SUBTRACT","result":"UNSURE","streak":"RESET"},{"cap":7,"evidenceClass":"LOW","lapse":"INCREMENT","levels":1,"operation":"SUBTRACT","result":"UNSURE","streak":"RESET"},{"cap":7,"evidenceClass":"HIGH","lapse":"INCREMENT","levels":0,"operation":"SET","result":"INCORRECT","streak":"RESET"},{"cap":7,"evidenceClass":"MEDIUM","lapse":"INCREMENT","levels":2,"operation":"SUBTRACT","result":"INCORRECT","streak":"RESET"},{"cap":7,"evidenceClass":"LOW","lapse":"INCREMENT","levels":1,"operation":"SUBTRACT","result":"INCORRECT","streak":"RESET"}]}'::jsonb,
        TIMESTAMPTZ '2026-09-20T00:00:00Z');

CREATE TABLE app_learning.study_candidate_generation (
    deck_id UUID NOT NULL,
    generation_id UUID NOT NULL,
    owner_id UUID NOT NULL,
    deck_revision_id UUID NOT NULL,
    deck_sequence BIGINT NOT NULL CHECK (deck_sequence >= 0),
    exercises_root_id UUID NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('PREPARING','READY','FAILED')),
    source_cursor UUID,
    scanned_count INTEGER NOT NULL CHECK (scanned_count >= 0),
    candidate_count INTEGER NOT NULL CHECK (candidate_count >= 0),
    expected_exercise_count INTEGER NOT NULL CHECK (expected_exercise_count >= 0),
    row_version BIGINT NOT NULL CHECK (row_version >= 0),
    created_at TIMESTAMPTZ NOT NULL CHECK (isfinite(created_at)),
    ready_at TIMESTAMPTZ CHECK (ready_at IS NULL OR isfinite(ready_at)),
    PRIMARY KEY (deck_id, generation_id),
    UNIQUE (generation_id),
    UNIQUE (deck_id, exercises_root_id),
    FOREIGN KEY (deck_id) REFERENCES app_learning.deck(deck_id),
    FOREIGN KEY (deck_id, deck_revision_id, deck_sequence)
        REFERENCES app_learning.deck_revision(deck_id, revision_id, sequence),
    CHECK ((status = 'READY' AND ready_at IS NOT NULL AND scanned_count = expected_exercise_count)
        OR (status <> 'READY' AND ready_at IS NULL))
);

CREATE TABLE app_learning.study_candidate (
    generation_id UUID NOT NULL,
    candidate_ordinal INTEGER NOT NULL CHECK (candidate_ordinal >= 0),
    deck_id UUID NOT NULL,
    exercise_id UUID NOT NULL,
    exercise_revision_id UUID NOT NULL,
    objective_id UUID NOT NULL,
    objective_revision_id UUID NOT NULL,
    member_key UUID NOT NULL,
    PRIMARY KEY (generation_id, candidate_ordinal),
    UNIQUE (deck_id, generation_id, candidate_ordinal),
    UNIQUE (generation_id, exercise_revision_id),
    FOREIGN KEY (deck_id, generation_id)
        REFERENCES app_learning.study_candidate_generation(deck_id, generation_id),
    FOREIGN KEY (deck_id, exercise_id, exercise_revision_id)
        REFERENCES app_learning.exercise_revision(deck_id, exercise_id, revision_id),
    FOREIGN KEY (deck_id, objective_id, objective_revision_id)
        REFERENCES app_learning.objective_revision(deck_id, objective_id, revision_id),
    FOREIGN KEY (deck_id, member_key, objective_id)
        REFERENCES app_learning.memory_objective(deck_id, member_key, objective_id)
);
CREATE INDEX study_candidate_objective
    ON app_learning.study_candidate(generation_id, objective_id, candidate_ordinal);
CREATE INDEX exercise_revision_snapshot_seek
    ON app_learning.exercise_revision(deck_id, exercise_id, deck_sequence DESC);

CREATE TABLE app_learning.study_session (
    account_id UUID NOT NULL,
    session_id UUID NOT NULL,
    deck_id UUID NOT NULL,
    command_id UUID NOT NULL UNIQUE,
    mode TEXT NOT NULL CHECK (mode IN ('SCHEDULED','REPLAY','PRACTICE')),
    status TEXT NOT NULL CHECK (status IN ('PREPARING','ACTIVE','EMPTY','COMPLETE')),
    timezone TEXT NOT NULL CHECK (char_length(timezone) BETWEEN 1 AND 80),
    local_study_date DATE NOT NULL,
    deck_revision_id UUID NOT NULL,
    deck_sequence BIGINT NOT NULL CHECK (deck_sequence >= 0),
    exercise_generation_id UUID NOT NULL,
    selection_policy_version TEXT NOT NULL,
    reducer_config_id UUID NOT NULL,
    seed BIGINT NOT NULL,
    budget INTEGER NOT NULL CHECK (budget BETWEEN 1 AND 100),
    issued_count INTEGER NOT NULL CHECK (issued_count >= 0 AND issued_count <= budget),
    batch_start INTEGER NOT NULL CHECK (batch_start >= 0),
    batch_size INTEGER NOT NULL CHECK (batch_size BETWEEN 0 AND 20),
    scan_cursor INTEGER NOT NULL CHECK (scan_cursor >= 0),
    wrapped BOOLEAN NOT NULL,
    include_new BOOLEAN NOT NULL,
    practice_order TEXT CHECK (practice_order IS NULL OR practice_order IN ('SEEDED','WEAKEST_FIRST')),
    source_session_id UUID,
    row_version BIGINT NOT NULL CHECK (row_version >= 0),
    created_at TIMESTAMPTZ NOT NULL CHECK (isfinite(created_at)),
    expires_at TIMESTAMPTZ NOT NULL CHECK (isfinite(expires_at) AND expires_at > created_at),
    completed_at TIMESTAMPTZ CHECK (completed_at IS NULL OR isfinite(completed_at)),
    PRIMARY KEY (account_id, session_id),
    UNIQUE (session_id),
    FOREIGN KEY (deck_id, exercise_generation_id)
        REFERENCES app_learning.study_candidate_generation(deck_id, generation_id),
    FOREIGN KEY (deck_id, deck_revision_id, deck_sequence)
        REFERENCES app_learning.deck_revision(deck_id, revision_id, sequence),
    FOREIGN KEY (reducer_config_id) REFERENCES app_learning.scheduler_config(config_id),
    FOREIGN KEY (account_id, source_session_id) REFERENCES app_learning.study_session(account_id, session_id),
    CHECK ((mode = 'REPLAY') = (source_session_id IS NOT NULL)),
    CHECK ((mode = 'PRACTICE') = (practice_order IS NOT NULL))
);
CREATE INDEX study_session_resume ON app_learning.study_session(account_id, deck_id, created_at DESC);

CREATE TABLE app_learning.study_presentation (
    account_id UUID NOT NULL,
    session_id UUID NOT NULL,
    presentation_id UUID NOT NULL,
    presentation_ordinal INTEGER NOT NULL CHECK (presentation_ordinal >= 0),
    nonce TEXT NOT NULL CHECK (char_length(nonce) BETWEEN 16 AND 100),
    deck_id UUID NOT NULL,
    generation_id UUID NOT NULL,
    candidate_ordinal INTEGER NOT NULL,
    exercise_id UUID NOT NULL,
    exercise_revision_id UUID NOT NULL,
    exercise_type TEXT NOT NULL CHECK (exercise_type IN ('SELF_CHECK','TYPED','CLOZE_SINGLE','SINGLE_CHOICE')),
    objective_id UUID NOT NULL,
    objective_revision_id UUID NOT NULL,
    learning_epoch BIGINT NOT NULL CHECK (learning_epoch >= 0),
    prompt JSONB NOT NULL CHECK (jsonb_typeof(prompt) = 'object'),
    options JSONB NOT NULL CHECK (jsonb_typeof(options) = 'array'),
    bindings JSONB NOT NULL CHECK (jsonb_typeof(bindings) = 'array'),
    evaluator JSONB NOT NULL CHECK (jsonb_typeof(evaluator) = 'object'),
    answer_contract JSONB NOT NULL CHECK (jsonb_typeof(answer_contract) = 'object'),
    issued_at TIMESTAMPTZ NOT NULL CHECK (isfinite(issued_at)),
    expires_at TIMESTAMPTZ NOT NULL CHECK (isfinite(expires_at) AND expires_at > issued_at),
    PRIMARY KEY (account_id, session_id, presentation_id),
    UNIQUE (presentation_id),
    UNIQUE (account_id, session_id, presentation_ordinal),
    UNIQUE (account_id, session_id, objective_id),
    FOREIGN KEY (account_id, session_id) REFERENCES app_learning.study_session(account_id, session_id),
    FOREIGN KEY (deck_id, generation_id, candidate_ordinal)
        REFERENCES app_learning.study_candidate(deck_id, generation_id, candidate_ordinal),
    FOREIGN KEY (deck_id, exercise_id, exercise_revision_id)
        REFERENCES app_learning.exercise_revision(deck_id, exercise_id, revision_id),
    FOREIGN KEY (deck_id, objective_id, objective_revision_id)
        REFERENCES app_learning.objective_revision(deck_id, objective_id, revision_id)
);

CREATE FUNCTION app_learning.study_snapshot_immutable_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Immutable study snapshot' USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER scheduler_config_immutable BEFORE UPDATE OR DELETE ON app_learning.scheduler_config
    FOR EACH ROW EXECUTE FUNCTION app_learning.study_snapshot_immutable_guard();
CREATE TRIGGER study_candidate_immutable BEFORE UPDATE OR DELETE ON app_learning.study_candidate
    FOR EACH ROW EXECUTE FUNCTION app_learning.study_snapshot_immutable_guard();
CREATE TRIGGER study_presentation_immutable BEFORE UPDATE OR DELETE ON app_learning.study_presentation
    FOR EACH ROW EXECUTE FUNCTION app_learning.study_snapshot_immutable_guard();
