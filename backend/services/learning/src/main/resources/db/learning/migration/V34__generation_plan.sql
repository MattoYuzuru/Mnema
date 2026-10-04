-- #295 AI-14 «Сначала показать план»: the plan of a plan-first session. It is a column of the session, not a table of its own: the plan is
-- written twice at most (by the PLAN step, then by the approval that replaces it with the plan the owner edited), is read with the session
-- and has no life apart from it (it goes with the session at the purge). The document is the wire shape of getSession `plan`
-- (contracts/generation/http.json): items, totals, cost, notes; the identifiers in it are the owner's own materials and notes, never a
-- prompt or a model answer.
ALTER TABLE app_learning.generation_session
    ADD COLUMN plan JSONB CHECK (plan IS NULL OR (jsonb_typeof(plan) = 'object' AND octet_length(plan::text) <= 65536));

-- The renewal of holds reads the sessions that are doing work: RUNNING, and PLANNING since the planner holds a plan reservation while it thinks.
-- A PLAN_READY session is deliberately not renewed (the owner may take days; its batch hold lapses by its time to live).
DROP INDEX IF EXISTS app_learning.generation_session_running_reservation;
CREATE INDEX generation_session_running_reservation ON app_learning.generation_session(session_id)
    WHERE state IN ('RUNNING', 'PLANNING') AND reservation_id IS NOT NULL;
