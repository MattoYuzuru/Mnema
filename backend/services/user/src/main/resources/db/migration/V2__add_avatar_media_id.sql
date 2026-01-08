ALTER TABLE app_user.users
    ADD COLUMN IF NOT EXISTS avatar_media_id UUID;

CREATE INDEX IF NOT EXISTS ix_users_avatar_media_id
    ON app_user.users (avatar_media_id);
