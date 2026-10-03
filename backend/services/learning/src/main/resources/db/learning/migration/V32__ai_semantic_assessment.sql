-- #292 AI-20: asynchronous AI assessment of a free explanation (evaluator ai-semantic), its self-check fallback and the dispute.
--
-- study_assessment is the Study-owned state of one answer that is being graded by the model. It exists between the moment an
-- answer is accepted and the moment the attempt becomes terminal (a receipt in study_attempt_tombstone, with evidence and a
-- transition in the scheduled mode). State machine, every arrow a compare-and-set on the previous state:
--   ASSESSING -> DONE         the grade arrived in time (the result transaction writes receipt, evidence and transition)
--   ASSESSING -> SELF_CHECK   the learner chose to rate themselves, or the grader was uncertain (reason says which)
--   ASSESSING -> UNAVAILABLE  provider failure, the capability went away, the fair-use limit, or the deadline sweeper
--   SELF_CHECK | UNAVAILABLE  -> DONE when the learner rates themselves (the same attempt, one terminal receipt per presentation)
-- A late grade for a row that left ASSESSING is discarded. `response` holds the learner's answer only until the attempt is
-- terminal (then it is cleared; scheduled attempts keep the usual 30-day raw response in study_raw_response) and in any case not
-- past expires_at, which is the presentation's expiry (the retention worker clears it).
CREATE TABLE app_learning.study_assessment (
    attempt_id UUID PRIMARY KEY,
    account_id UUID NOT NULL,
    session_id UUID NOT NULL,
    presentation_id UUID NOT NULL,
    deck_id UUID NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('ASSESSING', 'DONE', 'SELF_CHECK', 'UNAVAILABLE')),
    reason TEXT CHECK (reason IS NULL OR reason ~ '^[A-Z_]{1,40}$'),
    strictness TEXT NOT NULL CHECK (strictness IN ('S1', 'S2', 'S3')),
    -- the canonical hash of the submit command, so an exact retry replays and a changed one conflicts
    payload_hash BYTEA NOT NULL CHECK (octet_length(payload_hash) = 32),
    answer_source TEXT NOT NULL CHECK (answer_source IN ('TYPED', 'SPEECH')),
    response JSONB CHECK (response IS NULL OR jsonb_typeof(response) = 'object'),
    confidence TEXT CHECK (confidence IS NULL OR confidence IN ('KNEW', 'UNSURE', 'GUESSED')),
    duration_ms INTEGER NOT NULL CHECK (duration_ms BETWEEN 0 AND 3600000),
    created_at TIMESTAMPTZ NOT NULL CHECK (isfinite(created_at)),
    deadline_at TIMESTAMPTZ NOT NULL CHECK (isfinite(deadline_at) AND deadline_at > created_at),
    expires_at TIMESTAMPTZ NOT NULL CHECK (isfinite(expires_at)),
    resolved_at TIMESTAMPTZ CHECK (resolved_at IS NULL OR isfinite(resolved_at)),
    UNIQUE (account_id, session_id, presentation_id),
    FOREIGN KEY (account_id, session_id, presentation_id)
        REFERENCES app_learning.study_presentation(account_id, session_id, presentation_id),
    CHECK ((state = 'ASSESSING') = (resolved_at IS NULL)),
    CHECK (state <> 'DONE' OR response IS NULL)
);
-- the deadline sweeper looks at rows that are still being graded, oldest deadline first
CREATE INDEX study_assessment_deadline ON app_learning.study_assessment(deadline_at) WHERE state = 'ASSESSING';
-- the retention worker clears answers past the presentation's expiry
CREATE INDEX study_assessment_answer_expiry ON app_learning.study_assessment(expires_at) WHERE response IS NOT NULL;

-- «Оспорить оценку»: one row per disputed attempt, for the product owner's rate of disputes. Counts only: the answer text is
-- copied into `example` only when the learner explicitly ticked «Отправить пример для улучшения проверки» (shareExample).
CREATE TABLE app_learning.study_assessment_dispute (
    attempt_id UUID PRIMARY KEY,
    command_id UUID NOT NULL UNIQUE,
    account_id UUID NOT NULL,
    deck_id UUID NOT NULL,
    exercise_id UUID NOT NULL,
    exercise_revision_id UUID NOT NULL,
    strictness TEXT NOT NULL CHECK (strictness IN ('S1', 'S2', 'S3')),
    judgement TEXT NOT NULL CHECK (judgement IN ('COMPLETE', 'PARTIAL', 'INSUFFICIENT')),
    share_example BOOLEAN NOT NULL DEFAULT FALSE,
    example JSONB CHECK (example IS NULL OR (share_example AND jsonb_typeof(example) = 'object')),
    created_at TIMESTAMPTZ NOT NULL CHECK (isfinite(created_at)),
    FOREIGN KEY (attempt_id) REFERENCES app_learning.study_attempt_tombstone(attempt_id)
);
CREATE INDEX study_assessment_dispute_age ON app_learning.study_assessment_dispute(created_at);

-- A dispute appends a compensating transition (append-only): it restores the before-state of the AI transition and belongs to no
-- attempt of its own, so attempt_id becomes nullable and the row says what it compensates. Existing rows are attempt transitions.
ALTER TABLE app_learning.study_transition ALTER COLUMN attempt_id DROP NOT NULL;
ALTER TABLE app_learning.study_transition
    ADD COLUMN kind TEXT NOT NULL DEFAULT 'ATTEMPT' CHECK (kind IN ('ATTEMPT', 'COMPENSATION')),
    ADD COLUMN compensates_attempt_id UUID UNIQUE REFERENCES app_learning.study_evidence(attempt_id),
    ADD COLUMN reason_code TEXT CHECK (reason_code IS NULL OR reason_code ~ '^[A-Z_]{1,40}$');
ALTER TABLE app_learning.study_transition ADD CONSTRAINT study_transition_kind_shape CHECK (
    (kind = 'ATTEMPT' AND attempt_id IS NOT NULL AND compensates_attempt_id IS NULL AND reason_code IS NULL)
    OR (kind = 'COMPENSATION' AND attempt_id IS NULL AND compensates_attempt_id IS NOT NULL AND reason_code IS NOT NULL));
