ALTER TABLE app_core.user_cards
    DROP CONSTRAINT IF EXISTS fk_user_cards_public_card;

ALTER TABLE app_core.public_cards
    DROP CONSTRAINT IF EXISTS uq_public_cards_card_id;
