ALTER TABLE IF EXISTS app_ai.ai_usage_ledger
    ADD COLUMN IF NOT EXISTS details JSONB;
