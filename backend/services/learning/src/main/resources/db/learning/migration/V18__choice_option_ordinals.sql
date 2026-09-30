-- Choice counts are constrained by publication bytes/tokens, rather than a UI-only six-option cap.
ALTER TABLE app_learning.exercise_content_binding
    DROP CONSTRAINT exercise_content_binding_binding_ordinal_check,
    ALTER COLUMN binding_ordinal TYPE INTEGER,
    ADD CONSTRAINT exercise_content_binding_binding_ordinal_check CHECK (binding_ordinal >= 0);
