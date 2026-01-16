ALTER TABLE app_import.import_jobs
    ADD COLUMN IF NOT EXISTS deck_description TEXT,
    ADD COLUMN IF NOT EXISTS language_code TEXT,
    ADD COLUMN IF NOT EXISTS tags TEXT[],
    ADD COLUMN IF NOT EXISTS is_public BOOLEAN,
    ADD COLUMN IF NOT EXISTS is_listed BOOLEAN;
