CREATE TABLE IF NOT EXISTS app_core.deck_update_sessions
(
    deck_id       UUID        NOT NULL,
    operation_id  UUID        NOT NULL,
    author_id     UUID        NOT NULL,
    target_version INT        NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (deck_id, operation_id)
);

CREATE INDEX IF NOT EXISTS deck_update_sessions_deck_id_idx
    ON app_core.deck_update_sessions (deck_id);
