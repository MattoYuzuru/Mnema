ALTER TABLE app_core.sr_card_states
    ADD COLUMN IF NOT EXISTS row_version BIGINT NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS ix_sr_card_states_next_review
    ON app_core.sr_card_states (next_review_at)
    WHERE next_review_at IS NOT NULL;

CREATE INDEX IF NOT EXISTS ix_user_cards_deck_active
    ON app_core.user_cards (subscription_id)
    WHERE is_deleted = false;
