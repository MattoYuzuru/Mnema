ALTER TABLE app_core.user_deck_preferences
    ALTER COLUMN learning_horizon_minutes SET DEFAULT 120;

UPDATE app_core.user_deck_preferences
SET learning_horizon_minutes = 120
WHERE learning_horizon_minutes IS NULL OR learning_horizon_minutes = 1440;
