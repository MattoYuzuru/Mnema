ALTER TABLE IF EXISTS app_ai.ai_jobs
    ADD COLUMN IF NOT EXISTS user_access_token TEXT;

CREATE INDEX IF NOT EXISTS ix_ai_jobs_user_deck_created_at
    ON app_ai.ai_jobs (user_id, deck_id, created_at DESC);
