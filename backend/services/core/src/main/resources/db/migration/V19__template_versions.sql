-- Template versions (immutable snapshots of layout/AI profile)
CREATE TABLE IF NOT EXISTS app_core.card_template_versions
(
    template_id UUID        NOT NULL,
    version     INT         NOT NULL,
    layout      JSONB,
    ai_profile  JSONB,
    icon_url    TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by  UUID        NOT NULL,
    PRIMARY KEY (template_id, version),
    CONSTRAINT fk_template_versions_template
        FOREIGN KEY (template_id) REFERENCES app_core.card_templates (template_id)
            ON DELETE CASCADE
);

-- Seed initial version for existing templates
INSERT INTO app_core.card_template_versions (
    template_id,
    version,
    layout,
    ai_profile,
    icon_url,
    created_at,
    created_by
)
SELECT
    template_id,
    1,
    layout,
    ai_profile,
    icon_url,
    COALESCE(updated_at, created_at, now()),
    owner_id
FROM app_core.card_templates
ON CONFLICT DO NOTHING;

-- Track latest template version on base template
ALTER TABLE app_core.card_templates
    ADD COLUMN IF NOT EXISTS latest_version INT NOT NULL DEFAULT 1;

UPDATE app_core.card_templates
SET latest_version = 1
WHERE latest_version IS NULL;

-- Versioned fields
ALTER TABLE app_core.field_templates
    ADD COLUMN IF NOT EXISTS template_version INT NOT NULL DEFAULT 1;

UPDATE app_core.field_templates
SET template_version = 1
WHERE template_version IS NULL;

-- Version reference for public decks
ALTER TABLE app_core.public_decks
    ADD COLUMN IF NOT EXISTS template_version INT NOT NULL DEFAULT 1;

UPDATE app_core.public_decks
SET template_version = 1
WHERE template_version IS NULL;

-- Version reference for user decks (per-user template sync)
ALTER TABLE app_core.user_decks
    ADD COLUMN IF NOT EXISTS template_version INT NOT NULL DEFAULT 1;

ALTER TABLE app_core.user_decks
    ADD COLUMN IF NOT EXISTS subscribed_template_version INT NOT NULL DEFAULT 1;

UPDATE app_core.user_decks ud
SET template_version = COALESCE(pd.template_version, 1),
    subscribed_template_version = COALESCE(pd.template_version, 1)
FROM app_core.public_decks pd
WHERE ud.public_deck_id = pd.deck_id
  AND ud.current_version = pd.version;

-- Foreign keys and indexes
ALTER TABLE app_core.field_templates
    ADD CONSTRAINT fk_field_templates_template_version
        FOREIGN KEY (template_id, template_version)
            REFERENCES app_core.card_template_versions (template_id, version)
            ON DELETE CASCADE;

ALTER TABLE app_core.public_decks
    ADD CONSTRAINT fk_public_decks_template_version
        FOREIGN KEY (template_id, template_version)
            REFERENCES app_core.card_template_versions (template_id, version);

CREATE INDEX IF NOT EXISTS ix_template_versions_template
    ON app_core.card_template_versions (template_id, version DESC);

CREATE INDEX IF NOT EXISTS ix_field_templates_template_version_order
    ON app_core.field_templates (template_id, template_version, order_index);

CREATE INDEX IF NOT EXISTS ix_public_decks_template_version
    ON app_core.public_decks (template_id, template_version);

CREATE INDEX IF NOT EXISTS ix_user_decks_template_version
    ON app_core.user_decks (template_version);

COMMENT ON TABLE app_core.card_template_versions IS 'Immutable snapshots of template layout/AI profile per version.';
COMMENT ON COLUMN app_core.card_template_versions.template_id IS 'Template identifier.';
COMMENT ON COLUMN app_core.card_template_versions.version IS 'Template version number.';
COMMENT ON COLUMN app_core.card_template_versions.layout IS 'Layout for this template version.';
COMMENT ON COLUMN app_core.card_template_versions.ai_profile IS 'AI profile for this template version.';
COMMENT ON COLUMN app_core.card_template_versions.icon_url IS 'Icon URL for this template version.';
COMMENT ON COLUMN app_core.card_template_versions.created_at IS 'Creation time of this template version.';
COMMENT ON COLUMN app_core.card_template_versions.created_by IS 'User who created this template version.';

COMMENT ON COLUMN app_core.card_templates.latest_version IS 'Latest template version number.';
COMMENT ON COLUMN app_core.field_templates.template_version IS 'Template version this field belongs to.';
COMMENT ON COLUMN app_core.public_decks.template_version IS 'Template version used by this deck version.';
COMMENT ON COLUMN app_core.user_decks.template_version IS 'Template version currently used by the user deck.';
COMMENT ON COLUMN app_core.user_decks.subscribed_template_version IS 'Template version at the time of subscription/creation.';
