ALTER TABLE IF EXISTS app_ai.ai_quota
    ADD COLUMN IF NOT EXISTS period_end DATE;

UPDATE app_ai.ai_quota
SET period_end = (period_start + interval '1 month')::date
WHERE period_end IS NULL;
