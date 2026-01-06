ALTER TABLE app_core.public_decks
    ADD COLUMN IF NOT EXISTS icon_media_id UUID;

CREATE INDEX IF NOT EXISTS ix_public_decks_icon_media_id
    ON app_core.public_decks (icon_media_id);
