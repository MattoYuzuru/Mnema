CREATE SCHEMA IF NOT EXISTS app_user;

CREATE TABLE IF NOT EXISTS app_user.users
(
    id         UUID PRIMARY KEY         DEFAULT gen_random_uuid(),
    email      TEXT NOT NULL UNIQUE,
    username   VARCHAR         NOT NULL UNIQUE,
    bio        TEXT,
    is_admin   BOOLEAN         NOT NULL DEFAULT FALSE,
    avatar_url VARCHAR,
    created_at TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ     NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_users_created_at ON app_user.users (created_at);
