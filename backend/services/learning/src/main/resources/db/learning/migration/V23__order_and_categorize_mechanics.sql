-- #268: two more mechanics, ORDER (restore a sequence) and CATEGORIZE (assign items to groups), on the
-- unified exercise model. Only the closed enumerations widen: content, answer key and evaluator stay typed JSON
-- documents validated by the application, so no existing row is read, rewritten or reinterpreted. The
-- constraints are replaced in place, which keeps the whole chain valid on an empty database.
ALTER TABLE app_learning.exercise_revision DROP CONSTRAINT exercise_revision_exercise_type_check;
ALTER TABLE app_learning.exercise_revision ADD CONSTRAINT exercise_revision_exercise_type_check
    CHECK (exercise_type IN ('SELF_CHECK','FREE_RESPONSE','CLOZE','CHOICE','MATCH','ORDER','CATEGORIZE'));

ALTER TABLE app_learning.exercise_revision DROP CONSTRAINT exercise_revision_answer_key_check;
ALTER TABLE app_learning.exercise_revision ADD CONSTRAINT exercise_revision_answer_key_check
    CHECK (jsonb_typeof(answer_key) = 'object'
        AND answer_key ->> 'kind' IN ('SELF_REPORT','TEXT','CLOZE','CHOICE','MATCH','ORDER','CATEGORIZE'));

-- Issued presentations of the new mechanics are persisted exactly like the others (shuffled content once).
ALTER TABLE app_learning.study_presentation DROP CONSTRAINT study_presentation_exercise_type_check;
ALTER TABLE app_learning.study_presentation ADD CONSTRAINT study_presentation_exercise_type_check
    CHECK (exercise_type IN ('SELF_CHECK','FREE_RESPONSE','CLOZE','CHOICE','MATCH','ORDER','CATEGORIZE'));
