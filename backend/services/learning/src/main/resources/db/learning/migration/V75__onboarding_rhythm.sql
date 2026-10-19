-- Weekly rhythm (OB-401, #538; docs/engineering/onboarding/adr/adr-onb-004-rhythm.md, contracts/onboarding/README.md «Weekly rhythm»).
--
-- The rhythm of every account lives in onboarding_journey (weekly_target, weeks_met, last_met_week of V72), but an account that was never offered the tour has
-- no row, so its weekly target could not be changed and its weeks could not be counted. Such an account now gets a row in state NOT_OFFERED: no cohort, no
-- offered_at, created by PUT /api/onboarding/preferences or by the completion that meets its first week. On the wire it reads exactly like no row; the owner
-- report counts only TOUR and CONTROL rows, so it never sees one. The first read of an eligible account turns it into the OFFERED row, as it inserts one.
-- last_met_week is the Monday of the last counted ISO week: weeks_met grows once per week (the guarded upsert of WeeklyRhythm), and only together with it.
-- The table is empty in production while learning.onboarding.enabled is off, so the CHECKs validate nothing.
SET LOCAL lock_timeout = '5s';
ALTER TABLE app_learning.onboarding_journey DROP CONSTRAINT onboarding_journey_state_check;
ALTER TABLE app_learning.onboarding_journey ADD CONSTRAINT onboarding_journey_state_check
    CHECK (state IN ('NOT_OFFERED','OFFERED','IN_PROGRESS','PAUSED','SKIPPED','COMPLETED'));
ALTER TABLE app_learning.onboarding_journey ALTER COLUMN offered_at DROP NOT NULL;
ALTER TABLE app_learning.onboarding_journey ADD CONSTRAINT onboarding_journey_not_offered
    CHECK ((state = 'NOT_OFFERED') = (offered_at IS NULL) AND (state <> 'NOT_OFFERED' OR cohort IS NULL));
ALTER TABLE app_learning.onboarding_journey ADD CONSTRAINT onboarding_journey_met_week
    CHECK ((weeks_met = 0) = (last_met_week IS NULL) AND (last_met_week IS NULL OR extract(isodow FROM last_met_week) = 1));
