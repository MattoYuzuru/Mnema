ALTER TABLE app_user.users
    ADD COLUMN IF NOT EXISTS admin_granted_by UUID,
    ADD COLUMN IF NOT EXISTS admin_granted_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS banned_by UUID,
    ADD COLUMN IF NOT EXISTS banned_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS ban_reason VARCHAR(280);

CREATE INDEX IF NOT EXISTS ix_users_admin_granted_by
    ON app_user.users (admin_granted_by)
    WHERE admin_granted_by IS NOT NULL;

CREATE INDEX IF NOT EXISTS ix_users_admin_banned_at
    ON app_user.users (banned_at DESC)
    WHERE banned_at IS NOT NULL;
