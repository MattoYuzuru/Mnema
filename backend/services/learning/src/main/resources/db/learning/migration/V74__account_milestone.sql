-- «Путь Mnema» milestones and the PATH notification route (OB-301, #532; docs/engineering/onboarding/adr/adr-onb-002-milestones.md, contracts/onboarding/milestones.json).
--
-- account_milestone: one row per account and milestone of the closed registry, a FACT of the account's history. MilestoneRecorder writes it with one upsert in the
-- transaction of the domain action (a rolled-back action leaves no row); the OB-302 backfill writes BACKFILL rows from history. progress counts towards the target of
-- the registry (1 for most, 2 for CAPTURE_NOTE stages and TWO_DAYS dates, 3 for OWN_EXERCISES) and never passes it; achieved_at is set once, when progress reaches the
-- target, and never cleared (a tour replay does not reset the path). The target lives in code and the fixture, not here, so this table only bounds progress.
-- No user text, deck or item ids: the key, a small counter and two times. Read by primary key (account_id prefix) only.
--
-- TODO(account-deletion task; owner: Learning's account-purge epic #409): delete the rows by account_id together with onboarding_journey.
CREATE TABLE app_learning.account_milestone (
    account_id    UUID        NOT NULL,
    milestone_key TEXT        NOT NULL CHECK (milestone_key IN ('GOAL_TOLD', 'FIRST_DECK', 'FIRST_SESSION', 'OWN_MATERIAL', 'MEDIA_IN_MATERIAL', 'CAPTURE_NOTE',
                                                                'OWN_EXERCISES', 'AI_EXPLANATION', 'VOICE_ANSWER', 'WORKSHOP_APPROVED', 'RHYTHM_WEEK', 'TWO_DAYS',
                                                                'COMMUNITY_COPY')),
    progress      SMALLINT    NOT NULL CHECK (progress BETWEEN 1 AND 3),
    achieved_at   TIMESTAMPTZ NULL CHECK (achieved_at IS NULL OR isfinite(achieved_at)),
    first_at      TIMESTAMPTZ NOT NULL CHECK (isfinite(first_at)),
    source        TEXT        NOT NULL CHECK (source IN ('LIVE', 'BACKFILL')),
    PRIMARY KEY (account_id, milestone_key),
    CHECK (achieved_at IS NULL OR achieved_at >= first_at)
);

-- PATH_MILESTONE notifications (contracts/notifications) use the new route PATH, so the route CHECK of V24 is replaced. Adding it scans the notification table once under
-- the table lock (bounded: at most 200 rows per account, 30-day retention); lock_timeout makes the migration fail fast instead of queueing behind a long transaction.
SET LOCAL lock_timeout = '5s';
ALTER TABLE app_learning.notification DROP CONSTRAINT notification_route_check;
ALTER TABLE app_learning.notification ADD CONSTRAINT notification_route_check CHECK (route IN ('WORKSHOP', 'DECK', 'PLANS', 'PATH', 'NONE'));

-- The study dates of an account: the TWO_DAYS probe (a completed SCHEDULED or PRACTICE session on another local date, MilestoneRecorder.sessionCompleted) and the weekly rhythm of
-- OB-401 (architecture §7) read the account's completed dates by this index. Without it the planner may scan study_session for an account with many sessions. Partial and narrow:
-- only completed scheduled and practice sessions. Built in the migration's transaction under lock_timeout (the table is written by study completion only).
CREATE INDEX study_session_study_dates ON app_learning.study_session(account_id, local_study_date) WHERE status = 'COMPLETE' AND mode IN ('SCHEDULED', 'PRACTICE');
