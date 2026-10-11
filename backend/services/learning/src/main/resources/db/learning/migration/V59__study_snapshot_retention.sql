-- Study snapshot retention (#460): the per-session snapshot (presentations, exposures, reveals, pair checks, AI answer rows and the
-- start receipt's presentations) and the candidate generations nothing can read any more are deleted by the Study retention worker
-- after learning.study.retention.snapshot-horizon (default P7D, floor P5D). The receipt, evidence, transition and state chain
-- is never touched.
--
-- Order matters. Flyway runs this file in ONE transaction and an ACCESS EXCLUSIVE lock lasts to its end, so everything slow or merely
-- additive comes first (part A: the backfill, the index on the biggest table, the function) and the statements that lock a hot table
-- exclusively come last and are all metadata work (part B): ADD COLUMN without a default or a CHECK, partial indexes on the small
-- session and generation tables, dropping foreign keys and triggers, replacing the guards.
--   A1. study_exercise_attempt: "the learner already has an assessed attempt at this exercise in this epoch" (the first-attempt cap of the
--       AI strictness, AssessmentRepository.attemptedExercise) used to be answered by joining tombstones to presentations of any age.
--       Presentations are going away, so the fact is stored on its own, as a counter per (account, deck, exercise, epoch) written with the
--       receipt (a dispute takes one away, exactly as the tombstone status used to). Backfilled here from the assessed tombstones; every
--       later write goes through AttemptRepository.insertReceipt, so nothing else re-creates it.
--   A2. study_presentation(deck_id, generation_id, candidate_ordinal): the foreign key from the presentation to its candidate had no index,
--       so every deleted candidate scanned study_presentation (measured: 148 ms for 30 candidates among 252k presentations, 0.8 ms with the index).
--   B1. study_session.presentations_purged_at marks a session whose snapshot is gone; partial indexes keep the worker's probes on the unpurged
--       window (a few days of sessions), not on the whole history, and find a REPLAY child that still reads its source.
--   B2. study_candidate_generation.evicting_since / swept_at: a generation that no session reads and the deck no longer uses is first made
--       unreachable (its root is replaced by a random id and evicting_since is set), then its candidates are deleted in chunks over as many
--       passes as it takes, then the row; swept_at lets the worker's bounded sweep rotate over all generations.
--   B3. FKs that pinned the snapshot are dropped: the receipt (study_attempt_tombstone) must outlive its presentation (the contract keeps
--       receipts until account deletion), and a session is provenance only once its snapshot is purged, so it must not keep its candidate
--       generation alive. The unique keys on both tables stay.
--   B4. The immutability guard of exactly five snapshot tables (presentation, exposure, hint reveal, transcript accommodation, candidate)
--       allows DELETE (never UPDATE) inside a transaction that set mnema.study_retention to 'on' (set_config(..., true)), the pattern of
--       mnema.fork_materializing (V57). evidence, transition, policy assignment, restart audit and scheduler config keep
--       study_snapshot_immutable_guard.
--   B5. study_presentation_exercise (account_id, exercise_id) served only the join that A1 replaces, so it goes.
-- Roll-forward only.
SET LOCAL lock_timeout = '5s';

-- ===== Part A: additive, no exclusive lock on a hot table =====================================================================
-- A1. the attempted-exercise fact
CREATE TABLE app_learning.study_exercise_attempt (
    account_id UUID NOT NULL,
    deck_id UUID NOT NULL,
    exercise_id UUID NOT NULL,
    learning_epoch BIGINT NOT NULL CHECK (learning_epoch >= 0),
    attempts INTEGER NOT NULL CHECK (attempts > 0),
    PRIMARY KEY (account_id, deck_id, exercise_id, learning_epoch)
);
INSERT INTO app_learning.study_exercise_attempt(account_id, deck_id, exercise_id, learning_epoch, attempts)
SELECT tombstone.account_id, presentation.deck_id, presentation.exercise_id, presentation.learning_epoch, count(*)
  FROM app_learning.study_attempt_tombstone tombstone
  JOIN app_learning.study_presentation presentation ON presentation.account_id = tombstone.account_id
   AND presentation.session_id = tombstone.session_id AND presentation.presentation_id = tombstone.presentation_id
 WHERE tombstone.status = 'ASSESSED'
 GROUP BY tombstone.account_id, presentation.deck_id, presentation.exercise_id, presentation.learning_epoch;

-- A2. the index of the candidate foreign key (SHARE lock: reads go on, writes wait for the build)
CREATE INDEX study_presentation_candidate ON app_learning.study_presentation(deck_id, generation_id, candidate_ordinal);

-- the retention guard function (not attached yet)
CREATE FUNCTION app_learning.study_snapshot_retention_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' AND current_setting('mnema.study_retention', true) = 'on' THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'Immutable study snapshot' USING ERRCODE = '23514';
END;
$$;

-- ===== Part B: short metadata-only statements that lock a table exclusively ===================================================
-- B1. purge marker (a plain nullable column: no default, no CHECK, no scan, no rewrite) and the partial indexes of the small session table
ALTER TABLE app_learning.study_session ADD COLUMN presentations_purged_at TIMESTAMPTZ;
CREATE INDEX study_session_unpurged_age ON app_learning.study_session(created_at, session_id) WHERE presentations_purged_at IS NULL;
CREATE INDEX study_session_unpurged_generation ON app_learning.study_session(deck_id, exercise_generation_id)
    WHERE presentations_purged_at IS NULL;
CREATE INDEX study_session_unpurged_source ON app_learning.study_session(account_id, source_session_id)
    WHERE presentations_purged_at IS NULL AND source_session_id IS NOT NULL;
COMMENT ON COLUMN app_learning.study_session.exercise_generation_id IS
    'The generation the session was issued from. Intentionally NOT a foreign key (V59): once the session snapshot is purged the id is provenance only, and the generation is evicted when no session with a snapshot reads it.';

-- B2. generation eviction
ALTER TABLE app_learning.study_candidate_generation ADD COLUMN evicting_since TIMESTAMPTZ;
ALTER TABLE app_learning.study_candidate_generation ADD COLUMN swept_at TIMESTAMPTZ;
CREATE INDEX study_candidate_generation_evicting ON app_learning.study_candidate_generation(evicting_since)
    WHERE evicting_since IS NOT NULL;
CREATE INDEX study_candidate_generation_sweep ON app_learning.study_candidate_generation(swept_at NULLS FIRST, created_at)
    WHERE evicting_since IS NULL;

-- B3. foreign keys that pinned the snapshot (found by what they reference: their generated names are truncated)
DO $drop$
DECLARE
    found RECORD;
    dropped INTEGER := 0;
BEGIN
    FOR found IN SELECT conrelid::regclass AS relation, conname FROM pg_constraint
                  WHERE contype = 'f'
                    AND ((conrelid = 'app_learning.study_attempt_tombstone'::regclass
                          AND confrelid = 'app_learning.study_presentation'::regclass)
                      OR (conrelid = 'app_learning.study_session'::regclass
                          AND confrelid = 'app_learning.study_candidate_generation'::regclass)) LOOP
        EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', found.relation, found.conname);
        dropped := dropped + 1;
    END LOOP;
    IF dropped <> 2 THEN
        RAISE EXCEPTION 'V59 expected to drop exactly 2 foreign keys, dropped %', dropped;
    END IF;
END
$drop$;

-- B4. the retention guard of the five snapshot tables
DROP TRIGGER study_presentation_immutable ON app_learning.study_presentation;
DROP TRIGGER study_exposure_immutable ON app_learning.study_exposure;
DROP TRIGGER study_hint_reveal_immutable ON app_learning.study_hint_reveal;
DROP TRIGGER study_transcript_accommodation_immutable ON app_learning.study_transcript_accommodation;
DROP TRIGGER study_candidate_immutable ON app_learning.study_candidate;
CREATE TRIGGER study_presentation_immutable BEFORE UPDATE OR DELETE ON app_learning.study_presentation
    FOR EACH ROW EXECUTE FUNCTION app_learning.study_snapshot_retention_guard();
CREATE TRIGGER study_exposure_immutable BEFORE UPDATE OR DELETE ON app_learning.study_exposure
    FOR EACH ROW EXECUTE FUNCTION app_learning.study_snapshot_retention_guard();
CREATE TRIGGER study_hint_reveal_immutable BEFORE UPDATE OR DELETE ON app_learning.study_hint_reveal
    FOR EACH ROW EXECUTE FUNCTION app_learning.study_snapshot_retention_guard();
CREATE TRIGGER study_transcript_accommodation_immutable BEFORE UPDATE OR DELETE ON app_learning.study_transcript_accommodation
    FOR EACH ROW EXECUTE FUNCTION app_learning.study_snapshot_retention_guard();
CREATE TRIGGER study_candidate_immutable BEFORE UPDATE OR DELETE ON app_learning.study_candidate
    FOR EACH ROW EXECUTE FUNCTION app_learning.study_snapshot_retention_guard();

-- B5. the index nothing reads any more
DROP INDEX app_learning.study_presentation_exercise;

-- Fail the whole migration if a guard is missing or disabled (tgenabled = 'O' is origin/enabled), as V52 does.
DO $guards$
DECLARE
    retention CONSTANT TEXT[] := ARRAY['study_presentation_immutable', 'study_exposure_immutable', 'study_hint_reveal_immutable',
        'study_transcript_accommodation_immutable', 'study_candidate_immutable'];
    permanent CONSTANT TEXT[] := ARRAY['study_policy_assignment_immutable', 'study_evidence_immutable', 'study_transition_immutable',
        'study_restart_audit_immutable', 'scheduler_config_immutable'];
    guard TEXT;
BEGIN
    FOREACH guard IN ARRAY retention LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_trigger t JOIN pg_proc p ON p.oid = t.tgfoid
                        WHERE t.tgname = guard AND NOT t.tgisinternal AND t.tgenabled = 'O'
                          AND p.proname = 'study_snapshot_retention_guard') THEN
            RAISE EXCEPTION 'Retention guard % is missing or not enabled after V59', guard;
        END IF;
    END LOOP;
    FOREACH guard IN ARRAY permanent LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_trigger t JOIN pg_proc p ON p.oid = t.tgfoid
                        WHERE t.tgname = guard AND NOT t.tgisinternal AND t.tgenabled = 'O'
                          AND p.proname = 'study_snapshot_immutable_guard') THEN
            RAISE EXCEPTION 'Immutability guard % is missing, not enabled or was weakened by V59', guard;
        END IF;
    END LOOP;
    IF EXISTS (SELECT 1 FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid
                WHERE NOT t.tgisinternal AND t.tgenabled <> 'O' AND c.relnamespace = 'app_learning'::regnamespace) THEN
        RAISE EXCEPTION 'A trigger of the Learning schema is not enabled after V59';
    END IF;
END
$guards$;
