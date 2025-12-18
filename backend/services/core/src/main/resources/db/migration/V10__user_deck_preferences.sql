CREATE TABLE IF NOT EXISTS app_core.user_deck_preferences (
    user_deck_id UUID PRIMARY KEY REFERENCES app_core.user_decks (user_deck_id) ON DELETE CASCADE,
    learning_horizon_minutes INTEGER NOT NULL DEFAULT 1440,
    max_new_per_day INTEGER,
    max_review_per_day INTEGER,
    new_seen_today INTEGER NOT NULL DEFAULT 0,
    review_seen_today INTEGER NOT NULL DEFAULT 0,
    counter_date DATE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    row_version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_user_deck_preferences_counter_date
    ON app_core.user_deck_preferences (counter_date);
