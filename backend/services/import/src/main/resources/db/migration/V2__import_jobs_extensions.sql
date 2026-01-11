DO
$$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'import_job_type') THEN
            CREATE TYPE import_job_type AS ENUM (
                'import_job',
                'export_job'
            );
        ELSE
            IF NOT EXISTS (
                SELECT 1 FROM pg_enum
                JOIN pg_type ON pg_enum.enumtypid = pg_type.oid
                WHERE pg_type.typname = 'import_job_type' AND enumlabel = 'import_job'
            ) THEN
                ALTER TYPE import_job_type ADD VALUE 'import_job';
            END IF;
            IF NOT EXISTS (
                SELECT 1 FROM pg_enum
                JOIN pg_type ON pg_enum.enumtypid = pg_type.oid
                WHERE pg_type.typname = 'import_job_type' AND enumlabel = 'export_job'
            ) THEN
                ALTER TYPE import_job_type ADD VALUE 'export_job';
            END IF;
        END IF;
    END
$$ LANGUAGE plpgsql;

ALTER TABLE app_import.import_jobs
    ADD COLUMN IF NOT EXISTS job_type import_job_type NOT NULL DEFAULT 'import_job';

DO
$$
    BEGIN
        IF EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_schema = 'app_import'
              AND table_name = 'import_jobs'
              AND column_name = 'job_type'
        ) THEN
            UPDATE app_import.import_jobs
            SET job_type = 'import_job'
            WHERE job_type::text = 'import';
            UPDATE app_import.import_jobs
            SET job_type = 'export_job'
            WHERE job_type::text = 'export';
        END IF;
    END
$$ LANGUAGE plpgsql;

ALTER TABLE app_import.import_jobs
    ADD COLUMN IF NOT EXISTS source_media_id UUID,
    ADD COLUMN IF NOT EXISTS field_mapping JSONB,
    ADD COLUMN IF NOT EXISTS deck_name TEXT,
    ADD COLUMN IF NOT EXISTS result_media_id UUID,
    ADD COLUMN IF NOT EXISTS user_access_token TEXT;

ALTER TABLE app_import.import_jobs
    ALTER COLUMN source_location DROP NOT NULL;

ALTER TABLE app_import.import_jobs
    ALTER COLUMN user_access_token SET DEFAULT '';

UPDATE app_import.import_jobs
SET user_access_token = ''
WHERE user_access_token IS NULL;

ALTER TABLE app_import.import_jobs
    ALTER COLUMN user_access_token SET NOT NULL;

ALTER TABLE app_import.import_jobs
    ALTER COLUMN user_access_token DROP DEFAULT;
