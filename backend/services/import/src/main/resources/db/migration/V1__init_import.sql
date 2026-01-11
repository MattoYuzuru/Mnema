CREATE SCHEMA IF NOT EXISTS app_import;

DO
$$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'import_status') THEN
            CREATE TYPE import_status AS ENUM (
                'queued',
                'processing',
                'completed',
                'failed',
                'canceled'
                );
        END IF;
    END
$$ LANGUAGE plpgsql;

DO
$$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'import_source_type') THEN
            CREATE TYPE import_source_type AS ENUM (
                'apkg',
                'csv',
                'tsv',
                'txt'
                );
        END IF;
    END
$$ LANGUAGE plpgsql;

DO
$$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'import_mode') THEN
            CREATE TYPE import_mode AS ENUM (
                'create_new',
                'merge_into_existing'
                );
        END IF;
    END
$$ LANGUAGE plpgsql;

CREATE TABLE IF NOT EXISTS app_import.import_jobs
(
    job_id            UUID               NOT NULL DEFAULT gen_random_uuid(),
    user_id           UUID               NOT NULL,
    target_deck_id    UUID,
    source_type       import_source_type NOT NULL,
    source_name       TEXT,
    source_location   TEXT               NOT NULL,
    source_size_bytes BIGINT,
    mode              import_mode        NOT NULL,
    status            import_status      NOT NULL DEFAULT 'queued',
    total_items       INT,
    processed_items   INT,
    locked_at         TIMESTAMPTZ,
    locked_by         TEXT,
    started_at        TIMESTAMPTZ,
    completed_at      TIMESTAMPTZ,
    error_message     TEXT,
    created_at        TIMESTAMPTZ        NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ,
    PRIMARY KEY (job_id)
);

CREATE INDEX IF NOT EXISTS ix_import_jobs_user_id ON app_import.import_jobs (user_id);
CREATE INDEX IF NOT EXISTS ix_import_jobs_status ON app_import.import_jobs (status);
CREATE INDEX IF NOT EXISTS ix_import_jobs_locked_at ON app_import.import_jobs (locked_at);
CREATE INDEX IF NOT EXISTS ix_import_jobs_created_at ON app_import.import_jobs (created_at);
