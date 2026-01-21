ALTER TABLE app_core.user_deck_preferences
    ADD COLUMN IF NOT EXISTS time_zone TEXT,
    ADD COLUMN IF NOT EXISTS day_cutoff_minutes INTEGER NOT NULL DEFAULT 0;
