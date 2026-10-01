-- Presentation-local feedback is durable and idempotent; mistakes cannot disappear on refresh.
CREATE TABLE app_learning.study_pair_interaction (
    account_id UUID NOT NULL,
    session_id UUID NOT NULL,
    presentation_id UUID NOT NULL,
    cue_id UUID NOT NULL,
    option_id UUID NOT NULL,
    correct BOOLEAN NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL CHECK (isfinite(expires_at)),
    PRIMARY KEY (account_id, session_id, presentation_id, cue_id, option_id),
    FOREIGN KEY (account_id, session_id, presentation_id)
        REFERENCES app_learning.study_presentation(account_id, session_id, presentation_id) ON DELETE CASCADE
);
CREATE INDEX study_pair_interaction_expiry ON app_learning.study_pair_interaction(expires_at);
