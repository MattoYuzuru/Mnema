DROP INDEX IF EXISTS app_core.ix_sr_card_states_next_review;
CREATE INDEX ix_sr_card_states_next_review
    ON app_core.sr_card_states (next_review_at)
    WHERE is_suspended = false AND next_review_at IS NOT NULL;

DROP INDEX IF EXISTS app_core.ix_user_cards_deck_active;
CREATE INDEX ix_user_cards_deck_active
    ON app_core.user_cards (subscription_id)
    WHERE is_deleted = false;
