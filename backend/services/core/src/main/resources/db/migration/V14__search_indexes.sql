CREATE INDEX IF NOT EXISTS ix_user_decks_search_fts
    ON app_core.user_decks
    USING GIN (to_tsvector('simple', coalesce(display_name, '') || ' ' || coalesce(display_description, '')));

CREATE INDEX IF NOT EXISTS ix_public_decks_tags_gin
    ON app_core.public_decks
    USING GIN (tags);

CREATE INDEX IF NOT EXISTS ix_public_cards_tags_gin
    ON app_core.public_cards
    USING GIN (tags);

CREATE INDEX IF NOT EXISTS ix_public_cards_content_fts
    ON app_core.public_cards
    USING GIN (to_tsvector('simple', coalesce(content::text, '')));

CREATE INDEX IF NOT EXISTS ix_user_cards_content_override_fts
    ON app_core.user_cards
    USING GIN (to_tsvector('simple', coalesce(content_override::text, '')));

CREATE INDEX IF NOT EXISTS ix_user_cards_personal_note_fts
    ON app_core.user_cards
    USING GIN (to_tsvector('simple', coalesce(personal_note, '')));

CREATE INDEX IF NOT EXISTS ix_card_templates_search_fts
    ON app_core.card_templates
    USING GIN (to_tsvector('simple', coalesce(name, '') || ' ' || coalesce(description, '')));
