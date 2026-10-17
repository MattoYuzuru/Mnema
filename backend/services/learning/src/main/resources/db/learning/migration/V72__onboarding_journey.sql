-- Onboarding journey (OB-101, OB-105; #520, #525). One row per account that was offered or started the first-entry tour; no row means «not offered».
-- cohort is the holdout assignment frozen when the row was created by eligibility: TOUR or CONTROL when the experiment bucketed the account, UNASSIGNED when it
-- did not (experiment disabled or no secret: the account gets the tour but is no experiment data); NULL marks a row created by REPLAY outside the cohort.
-- The owner report counts only TOUR and CONTROL. weeks_met and last_met_week are reserved for the rhythm of OB-401 and stay at their defaults until then.
-- Personal data tied to the account (tour state, experience, weekly target): deleted with the account (#409, TODO on OnboardingRepository).
SET LOCAL lock_timeout = '5s';
CREATE TABLE app_learning.onboarding_journey (
    account_id UUID PRIMARY KEY,
    tour_version SMALLINT NOT NULL CHECK (tour_version >= 1),
    state TEXT NOT NULL CHECK (state IN ('OFFERED','IN_PROGRESS','PAUSED','SKIPPED','COMPLETED')),
    cohort TEXT CHECK (cohort IS NULL OR cohort IN ('TOUR','CONTROL','UNASSIGNED')),
    branch TEXT CHECK (branch IS NULL OR branch IN ('SAMPLE','COMMUNITY','OWN')),
    step_key TEXT CHECK (step_key IS NULL OR char_length(step_key) BETWEEN 1 AND 64),
    experience TEXT CHECK (experience IS NULL OR experience IN ('NEW','FAMILIAR','CONFIDENT')),
    weekly_target SMALLINT CHECK (weekly_target IS NULL OR weekly_target BETWEEN 1 AND 7),
    offered_at TIMESTAMPTZ NOT NULL CHECK (isfinite(offered_at)),
    finished_at TIMESTAMPTZ CHECK (finished_at IS NULL OR isfinite(finished_at)),
    replay_count SMALLINT NOT NULL DEFAULT 0 CHECK (replay_count >= 0),
    weeks_met SMALLINT NOT NULL DEFAULT 0 CHECK (weeks_met >= 0),
    last_met_week DATE,
    row_version BIGINT NOT NULL DEFAULT 0 CHECK (row_version >= 0),
    updated_at TIMESTAMPTZ NOT NULL CHECK (isfinite(updated_at)),
    CONSTRAINT onboarding_journey_finished CHECK ((state IN ('SKIPPED','COMPLETED')) = (finished_at IS NOT NULL))
);

-- No index on the table beyond the primary key: the owner report reads the rows of one window of at most 90 days once in a while, a narrow row per account
-- (about 30 MB at 200 000 accounts), and reaches study_session through its primary key (account_id, session_id).
-- Account purge (#409) can locate the command receipts of this scope without a global scan.
CREATE INDEX command_receipt_onboarding_actor ON app_learning.command_receipt(actor_id,created_at) WHERE command_scope = 'onboarding';
