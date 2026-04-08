CREATE TABLE IF NOT EXISTS app_core.moderation_reports
(
    report_id           UUID PRIMARY KEY,
    target_type         VARCHAR(32)  NOT NULL,
    target_id           UUID         NOT NULL,
    target_parent_id    UUID,
    target_title        VARCHAR(160) NOT NULL,
    content_owner_id    UUID         NOT NULL,
    reporter_id         UUID         NOT NULL,
    reporter_username   VARCHAR(80)  NOT NULL,
    reason              VARCHAR(48)  NOT NULL,
    details             VARCHAR(500),
    status              VARCHAR(16)  NOT NULL DEFAULT 'OPEN',
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    closed_at           TIMESTAMPTZ,
    closed_by_user_id   UUID,
    closed_by_username  VARCHAR(80),
    resolution_note     VARCHAR(500)
);

CREATE INDEX IF NOT EXISTS ix_moderation_reports_status_created_at
    ON app_core.moderation_reports (status, created_at DESC);

CREATE INDEX IF NOT EXISTS ix_moderation_reports_target
    ON app_core.moderation_reports (target_type, target_id);

CREATE INDEX IF NOT EXISTS ix_moderation_reports_reason
    ON app_core.moderation_reports (reason);

CREATE INDEX IF NOT EXISTS ix_moderation_reports_closed_by
    ON app_core.moderation_reports (closed_by_user_id)
    WHERE closed_by_user_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS ux_moderation_reports_open_by_reporter_and_target
    ON app_core.moderation_reports (reporter_id, target_type, target_id)
    WHERE status = 'OPEN';
