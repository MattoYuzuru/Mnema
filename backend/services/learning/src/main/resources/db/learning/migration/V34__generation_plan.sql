-- #295 AI-14 «Сначала показать план»: the plan of a plan-first session. It is a column of the session, not a table of its own: the plan is
-- written twice at most (by the PLAN step, then by the approval that replaces it with the plan the owner edited), is read with the session
-- and has no life apart from it (it goes with the session at the purge). The document is the wire shape of getSession `plan`
-- (contracts/generation/http.json): items, totals, cost, notes; the identifiers in it are the owner's own materials and notes, never a
-- prompt or a model answer.
ALTER TABLE app_learning.generation_session
    ADD COLUMN plan JSONB CHECK (plan IS NULL OR (jsonb_typeof(plan) = 'object' AND octet_length(plan::text) <= 65536));
