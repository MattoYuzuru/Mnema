ALTER TABLE app_core.user_cards
    ADD COLUMN IF NOT EXISTS tags TEXT[];

COMMENT ON COLUMN app_core.user_cards.tags IS 'Теги карточки (локальные/override).';

CREATE INDEX IF NOT EXISTS ix_user_cards_tags_gin
    ON app_core.user_cards
    USING GIN (tags);
