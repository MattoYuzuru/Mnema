-- Pin the new-objective allowance beside the existing presentation budget.
ALTER TABLE app_learning.study_session
    ADD COLUMN max_new_objectives INTEGER,
    ADD COLUMN issued_new_objectives INTEGER;

-- Existing in-flight sessions keep their previous unrestricted behavior. Counting
-- every issued presentation as new is conservative for any later refill.
UPDATE app_learning.study_session
   SET max_new_objectives=budget,
       issued_new_objectives=issued_count;

ALTER TABLE app_learning.study_session
    ALTER COLUMN max_new_objectives SET NOT NULL,
    ALTER COLUMN issued_new_objectives SET NOT NULL,
    ADD CONSTRAINT study_session_new_budget_bounds
        CHECK (max_new_objectives BETWEEN 0 AND budget),
    ADD CONSTRAINT study_session_issued_new_bounds
        CHECK (issued_new_objectives BETWEEN 0 AND max_new_objectives);
