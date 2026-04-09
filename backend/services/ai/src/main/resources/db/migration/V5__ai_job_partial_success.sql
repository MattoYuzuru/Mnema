DO
$$
    BEGIN
        IF NOT EXISTS (
            SELECT 1
            FROM pg_enum
            WHERE enumlabel = 'partial_success'
              AND enumtypid = 'ai_job_status'::regtype
        ) THEN
            ALTER TYPE ai_job_status ADD VALUE 'partial_success' AFTER 'processing';
        END IF;
    END
$$ LANGUAGE plpgsql;
