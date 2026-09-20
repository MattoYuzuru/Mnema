-- Durable scheduled evidence and bounded receipt/raw children for the Study reducer.
CREATE TABLE app_learning.study_policy_assignment (
    account_id UUID NOT NULL,
    deck_id UUID NOT NULL,
    objective_id UUID NOT NULL,
    reducer_config_id UUID NOT NULL,
    assigned_at TIMESTAMPTZ NOT NULL CHECK (isfinite(assigned_at)),
    PRIMARY KEY (account_id, deck_id, objective_id),
    UNIQUE (account_id, deck_id, objective_id, reducer_config_id),
    FOREIGN KEY (deck_id, objective_id)
        REFERENCES app_learning.memory_objective(deck_id, objective_id),
    FOREIGN KEY (reducer_config_id) REFERENCES app_learning.scheduler_config(config_id)
);

CREATE TABLE app_learning.study_state (
    account_id UUID NOT NULL,
    deck_id UUID NOT NULL,
    objective_id UUID NOT NULL,
    learning_epoch BIGINT NOT NULL CHECK (learning_epoch >= 0),
    level SMALLINT NOT NULL CHECK (level BETWEEN 0 AND 7),
    correct_streak INTEGER NOT NULL CHECK (correct_streak >= 0),
    lapse_count INTEGER NOT NULL CHECK (lapse_count >= 0),
    last_assessed_at TIMESTAMPTZ CHECK (last_assessed_at IS NULL OR isfinite(last_assessed_at)),
    next_due TIMESTAMPTZ CHECK (next_due IS NULL OR isfinite(next_due)),
    reducer_config_id UUID NOT NULL,
    transition_sequence BIGINT NOT NULL CHECK (transition_sequence >= 0),
    row_version BIGINT NOT NULL CHECK (row_version >= 0),
    introduced_at TIMESTAMPTZ NOT NULL CHECK (isfinite(introduced_at)),
    updated_at TIMESTAMPTZ NOT NULL CHECK (isfinite(updated_at)),
    PRIMARY KEY (account_id, deck_id, objective_id),
    FOREIGN KEY (account_id, deck_id, objective_id, reducer_config_id)
        REFERENCES app_learning.study_policy_assignment(account_id, deck_id, objective_id, reducer_config_id),
    CHECK ((last_assessed_at IS NULL) = (transition_sequence = 0)),
    CHECK (next_due IS NOT NULL OR transition_sequence = 0)
);
CREATE INDEX study_state_due
    ON app_learning.study_state(account_id, deck_id, next_due, objective_id);

CREATE TABLE app_learning.study_exposure (
    account_id UUID NOT NULL,
    session_id UUID NOT NULL,
    presentation_id UUID NOT NULL,
    deck_id UUID NOT NULL,
    objective_id UUID NOT NULL,
    learning_epoch BIGINT NOT NULL CHECK (learning_epoch >= 0),
    exposed_at TIMESTAMPTZ NOT NULL CHECK (isfinite(exposed_at)),
    PRIMARY KEY (account_id, session_id, presentation_id),
    UNIQUE (presentation_id),
    FOREIGN KEY (account_id, session_id, presentation_id)
        REFERENCES app_learning.study_presentation(account_id, session_id, presentation_id),
    FOREIGN KEY (account_id, deck_id, objective_id)
        REFERENCES app_learning.study_state(account_id, deck_id, objective_id)
);

CREATE TABLE app_learning.study_attempt_tombstone (
    attempt_id UUID PRIMARY KEY,
    account_id UUID NOT NULL,
    session_id UUID NOT NULL,
    presentation_id UUID NOT NULL,
    deck_id UUID NOT NULL,
    payload_hash BYTEA NOT NULL CHECK (octet_length(payload_hash) = 32),
    mode TEXT NOT NULL CHECK (mode IN ('SCHEDULED','REPLAY','PRACTICE')),
    status TEXT NOT NULL CHECK (status IN ('ASSESSED','NOT_ASSESSED','UNAVAILABLE')),
    outcome JSONB CHECK (outcome IS NULL OR jsonb_typeof(outcome) = 'object'),
    receipt_expires_at TIMESTAMPTZ CHECK (receipt_expires_at IS NULL OR isfinite(receipt_expires_at)),
    submitted_at TIMESTAMPTZ NOT NULL CHECK (isfinite(submitted_at)),
    UNIQUE (account_id, session_id, presentation_id),
    FOREIGN KEY (account_id, session_id, presentation_id)
        REFERENCES app_learning.study_presentation(account_id, session_id, presentation_id),
    CHECK ((mode = 'SCHEDULED' AND receipt_expires_at IS NULL AND outcome IS NOT NULL)
        OR (mode <> 'SCHEDULED' AND receipt_expires_at IS NOT NULL)),
    CHECK (receipt_expires_at IS NULL OR receipt_expires_at > submitted_at)
);
CREATE INDEX study_attempt_receipt_expiry
    ON app_learning.study_attempt_tombstone(receipt_expires_at, attempt_id)
    WHERE receipt_expires_at IS NOT NULL AND outcome IS NOT NULL;

CREATE TABLE app_learning.study_evidence (
    attempt_id UUID PRIMARY KEY,
    account_id UUID NOT NULL,
    deck_id UUID NOT NULL,
    objective_id UUID NOT NULL,
    objective_revision_id UUID NOT NULL,
    learning_epoch BIGINT NOT NULL CHECK (learning_epoch >= 0),
    result TEXT NOT NULL CHECK (result IN ('CORRECT','PARTIAL','UNSURE','INCORRECT')),
    evidence_class TEXT NOT NULL CHECK (evidence_class IN ('HIGH','MEDIUM','LOW')),
    reason_codes JSONB NOT NULL CHECK (jsonb_typeof(reason_codes) = 'array'),
    evaluator_id TEXT NOT NULL,
    evaluator_version TEXT NOT NULL,
    hints_used JSONB NOT NULL CHECK (jsonb_typeof(hints_used) = 'array'),
    confidence TEXT CHECK (confidence IS NULL OR confidence IN ('KNEW','UNSURE','GUESSED')),
    duration_ms INTEGER NOT NULL CHECK (duration_ms BETWEEN 0 AND 3600000),
    accepted_at TIMESTAMPTZ NOT NULL CHECK (isfinite(accepted_at)),
    FOREIGN KEY (attempt_id) REFERENCES app_learning.study_attempt_tombstone(attempt_id),
    FOREIGN KEY (deck_id, objective_id, objective_revision_id)
        REFERENCES app_learning.objective_revision(deck_id, objective_id, revision_id)
);
CREATE INDEX study_evidence_objective_history
    ON app_learning.study_evidence(account_id, deck_id, objective_id, learning_epoch, accepted_at, attempt_id);

CREATE TABLE app_learning.study_transition (
    account_id UUID NOT NULL,
    deck_id UUID NOT NULL,
    objective_id UUID NOT NULL,
    learning_epoch BIGINT NOT NULL CHECK (learning_epoch >= 0),
    transition_sequence BIGINT NOT NULL CHECK (transition_sequence > 0),
    attempt_id UUID NOT NULL UNIQUE,
    before_level SMALLINT NOT NULL CHECK (before_level BETWEEN 0 AND 7),
    after_level SMALLINT NOT NULL CHECK (after_level BETWEEN 0 AND 7),
    before_correct_streak INTEGER NOT NULL CHECK (before_correct_streak >= 0),
    after_correct_streak INTEGER NOT NULL CHECK (after_correct_streak >= 0),
    before_lapse_count INTEGER NOT NULL CHECK (before_lapse_count >= 0),
    after_lapse_count INTEGER NOT NULL CHECK (after_lapse_count >= 0),
    accepted_at TIMESTAMPTZ NOT NULL CHECK (isfinite(accepted_at)),
    next_due TIMESTAMPTZ NOT NULL CHECK (isfinite(next_due)),
    reducer_id TEXT NOT NULL,
    reducer_version TEXT NOT NULL,
    reducer_config_id UUID NOT NULL,
    config_hash TEXT NOT NULL,
    PRIMARY KEY (account_id, deck_id, objective_id, learning_epoch, transition_sequence),
    FOREIGN KEY (attempt_id) REFERENCES app_learning.study_evidence(attempt_id),
    FOREIGN KEY (account_id, deck_id, objective_id)
        REFERENCES app_learning.study_state(account_id, deck_id, objective_id),
    FOREIGN KEY (reducer_config_id) REFERENCES app_learning.scheduler_config(config_id)
);

CREATE TABLE app_learning.study_raw_response (
    attempt_id UUID PRIMARY KEY,
    response JSONB NOT NULL CHECK (jsonb_typeof(response) = 'object'),
    expires_at TIMESTAMPTZ NOT NULL CHECK (isfinite(expires_at)),
    FOREIGN KEY (attempt_id) REFERENCES app_learning.study_attempt_tombstone(attempt_id)
);
CREATE INDEX study_raw_response_expiry ON app_learning.study_raw_response(expires_at, attempt_id);

CREATE TABLE app_learning.study_restart_audit (
    command_id UUID NOT NULL,
    account_id UUID NOT NULL,
    deck_id UUID NOT NULL,
    objective_id UUID NOT NULL,
    prior_learning_epoch BIGINT NOT NULL CHECK (prior_learning_epoch >= 0),
    new_learning_epoch BIGINT NOT NULL CHECK (new_learning_epoch = prior_learning_epoch + 1),
    restarted_at TIMESTAMPTZ NOT NULL CHECK (isfinite(restarted_at)),
    PRIMARY KEY (command_id, objective_id),
    FOREIGN KEY (account_id, deck_id, objective_id)
        REFERENCES app_learning.study_state(account_id, deck_id, objective_id)
);
CREATE INDEX study_restart_history
    ON app_learning.study_restart_audit(account_id, deck_id, objective_id, restarted_at);

CREATE TRIGGER study_exposure_immutable BEFORE UPDATE OR DELETE ON app_learning.study_exposure
    FOR EACH ROW EXECUTE FUNCTION app_learning.study_snapshot_immutable_guard();
CREATE TRIGGER study_policy_assignment_immutable BEFORE UPDATE OR DELETE ON app_learning.study_policy_assignment
    FOR EACH ROW EXECUTE FUNCTION app_learning.study_snapshot_immutable_guard();
CREATE TRIGGER study_evidence_immutable BEFORE UPDATE OR DELETE ON app_learning.study_evidence
    FOR EACH ROW EXECUTE FUNCTION app_learning.study_snapshot_immutable_guard();
CREATE TRIGGER study_transition_immutable BEFORE UPDATE OR DELETE ON app_learning.study_transition
    FOR EACH ROW EXECUTE FUNCTION app_learning.study_snapshot_immutable_guard();
CREATE TRIGGER study_restart_audit_immutable BEFORE UPDATE OR DELETE ON app_learning.study_restart_audit
    FOR EACH ROW EXECUTE FUNCTION app_learning.study_snapshot_immutable_guard();
