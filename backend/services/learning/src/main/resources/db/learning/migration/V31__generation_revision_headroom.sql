-- #293 AI-11: a rewrite stops at 30 revisions per artifact, but REMOVE_MEDIA is exempt from that cap (it only takes media away, at most
-- eight directives per artifact), so the table bound leaves headroom above 30. The bound of V28 had an unnamed CHECK on each column.
DO $$
DECLARE
    found record;
BEGIN
    FOR found IN
        SELECT conrelid::regclass::text AS table_name, conname
        FROM pg_constraint
        WHERE contype = 'c'
          AND conrelid IN ('app_learning.generation_artifact'::regclass, 'app_learning.generation_artifact_revision'::regclass)
          AND (pg_get_constraintdef(oid) LIKE '%revision_count%' OR pg_get_constraintdef(oid) LIKE '%revision_no%')
    LOOP
        EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', found.table_name, found.conname);
    END LOOP;
END
$$;
ALTER TABLE app_learning.generation_artifact
    ADD CONSTRAINT generation_artifact_revision_count_bound CHECK (revision_count BETWEEN 0 AND 40);
ALTER TABLE app_learning.generation_artifact_revision
    ADD CONSTRAINT generation_artifact_revision_no_bound CHECK (revision_no BETWEEN 1 AND 40);
