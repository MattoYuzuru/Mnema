-- AI operations (#300, AI-17): the wake-up of a split topology and the claim of an assessment.
--
-- notify_work: a statement that creates work tells the workers of another process (learning.runtime.roles=worker) with NOTIFY on a fixed channel.
--   NOTIFY issued inside a transaction is delivered only when it commits and never when it rolls back, so the hint cannot overtake the row. It is a hint
--   only: the sweeper of every worker stays the source of truth, a lost notification costs at most one sweep interval. The payload is empty, nothing is
--   exposed. Identical notifications of one transaction are delivered once.
CREATE FUNCTION app_learning.notify_work() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    PERFORM pg_notify(TG_ARGV[0], '');
    RETURN NULL;
END
$$;

CREATE TRIGGER generation_step_ready AFTER INSERT OR UPDATE OF state, next_attempt_at ON app_learning.generation_step
    FOR EACH ROW WHEN (NEW.state = 'READY') EXECUTE FUNCTION app_learning.notify_work('mnema_generation_steps');
CREATE TRIGGER speech_input_queued AFTER INSERT ON app_learning.speech_input
    FOR EACH ROW WHEN (NEW.state = 'QUEUED') EXECUTE FUNCTION app_learning.notify_work('mnema_speech_inputs');
CREATE TRIGGER study_assessment_assessing AFTER INSERT ON app_learning.study_assessment
    FOR EACH ROW WHEN (NEW.state = 'ASSESSING') EXECUTE FUNCTION app_learning.notify_work('mnema_assessments');

-- study_assessment.claimed_at: the grader that took the answer. A grader (the process that accepted the answer, or a worker process when the api
--   process has no provider key) takes an ASSESSING answer exactly once; a crash after the claim leaves it to the deadline sweeper (20 s), the same
--   outcome as before. NULL while nobody has taken it.
ALTER TABLE app_learning.study_assessment ADD COLUMN claimed_at TIMESTAMPTZ CHECK (claimed_at IS NULL OR isfinite(claimed_at));
-- the worker's sweep looks only at answers nobody has taken, oldest first
CREATE INDEX study_assessment_unclaimed ON app_learning.study_assessment(created_at) WHERE state = 'ASSESSING' AND claimed_at IS NULL;
