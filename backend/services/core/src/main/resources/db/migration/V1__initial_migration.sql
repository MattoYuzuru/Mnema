CREATE SCHEMA IF NOT EXISTS app_core;


DO
$$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'language') THEN
            CREATE TYPE language_tag AS ENUM ('ru', 'en');
        END IF;
    END
$$ LANGUAGE plpgsql;

CREATE TABLE IF NOT EXISTS app_core.public_decks
(
    deck_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version          int              DEFAULT 1,
    author_id        UUID NOT NULL,
    name             TEXT NOT NULL,
    description      TEXT,
    template_id      UUID NOT NULL,
    is_public        BOOLEAN          DEFAULT false,
    is_listed        BOOLEAN          DEFAULT true,
    language_code    language_tag     DEFAULT language_tag('ru'),
    tags             TEXT[],
    created_at       TIMESTAMPTZ      DEFAULT now(),
    updated_at       TIMESTAMPTZ,
    published_at     TIMESTAMPTZ,
    forked_from_deck UUID
);

CREATE TABLE IF NOT EXISTS app_core.public_cards
(
    deck_id      UUID  NOT NULL,
    deck_version INT                        DEFAULT 1,
    card_id      UUID  NOT NULL PRIMARY KEY DEFAULT gen_random_uuid(),
    content      JSONB NOT NULL,
    order_index  INT,
    tags         TEXT[],
    created_at   TIMESTAMPTZ                DEFAULT now(),
    updated_at   TIMESTAMPTZ,
    is_active    BOOLEAN,
    checksum     TEXT
);

CREATE TABLE IF NOT EXISTS app_core.user_decks
(
    used_deck_id        UUID        DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL,
    public_deck_id      UUID NOT NULL,
    subscribed_version  INT,
    current_version     INT,
    auto_update         BOOLEAN     DEFAULT true,
    algorithm_id        TEXT,
    algorithm_params    JSONB,
    display_name        TEXT,
    display_description TEXT,
    created_at          TIMESTAMPTZ default now(),
    last_synced_at      TIMESTAMPTZ,
    is_archived         BOOLEAN     DEFAULT false
);

CREATE TABLE IF NOT EXISTS app_core.user_cards
(
    user_card_id     UUID        DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id          UUID NOT NULL,
    subscription_id  UUID,
    public_card_id   UUID,
    is_custom        BOOLEAN     DEFAULT true,
    is_deleted       BOOLEAN     DEFAULT false,
    personal_note    TEXT,
    content_override JSONB,
    created_at       TIMESTAMPTZ DEFAULT now(),
    updated_at       TIMESTAMPTZ,
    last_review_at   TIMESTAMPTZ,
    next_review_at   TIMESTAMPTZ,
    review_count     INT,
    is_suspended     BOOLEAN     default false
);

CREATE TABLE IF NOT EXISTS app_core.card_templates
(
    template_id UUID PRIMARY KEY,
    owner_id    UUID NOT NULL,
    name        TEXT NOT NULL,
    description TEXT,
    is_public   BOOLEAN,
    created_at  TIMESTAMPTZ default now(),
    updated_at  TIMESTAMPTZ,
    layout      JSONB,
    ai_profile  JSONB,
    icon_url    TEXT
);

CREATE TABLE IF NOT EXISTS app_core.field_templates
(
    field_id      UUID PRIMARY KEY,
    template_id   UUID NOT NULL,
    name          TEXT,
    label         TEXT,
    field_type    TEXT,
    is_required   BOOLEAN,
    is_on_front   BOOLEAN,
    order_index   INT,
    default_value TEXT,
    help_text     TEXT
);

CREATE TABLE IF NOT EXISTS app_core.sr_algorithms
(
    algorithm_id   TEXT PRIMARY KEY,
    name           TEXT,
    description    TEXT,
    version        TEXT,
    config_schema  JSONB,
    default_config JSONB,
    created_at     TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS app_core.sr_card_states
(
    user_card_id   UUID PRIMARY KEY,
    algorithm_id   TEXT NOT NULL,
    state          JSONB,
    last_review_at TIMESTAMPTZ,
    next_review_at TIMESTAMPTZ,
    review_count   INT
);

CREATE TABLE IF NOT EXISTS app_core.sr_review_logs
(
    id           BIGSERIAL PRIMARY KEY,
    user_card_id UUID,
    algorithm_id TEXT,
    reviewed_at  TIMESTAMPTZ,
    rating       SMALLINT,
    response_ms  INT,
    state_before JSONB,
    state_after  JSONB,
    source       TEXT
);