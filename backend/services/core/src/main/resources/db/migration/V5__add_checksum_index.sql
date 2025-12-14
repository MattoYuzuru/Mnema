CREATE INDEX IF NOT EXISTS idx_public_cards_deck_ver_checksum
    ON app_core.public_cards(deck_id, deck_version, checksum);
