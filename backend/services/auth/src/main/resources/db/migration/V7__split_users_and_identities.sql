CREATE TABLE IF NOT EXISTS auth.users
(
    id             UUID PRIMARY KEY     DEFAULT gen_random_uuid(),
    email          TEXT        NOT NULL,
    email_verified BOOLEAN     NOT NULL DEFAULT FALSE,
    name           TEXT,
    picture_url    TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_login_at  TIMESTAMPTZ
);

ALTER TABLE auth.users
    ADD CONSTRAINT uq_users_email UNIQUE (email);

ALTER TABLE auth.accounts
    ADD COLUMN IF NOT EXISTS user_id UUID;

-- 1) перенесём существующих пользователей из accounts
INSERT INTO auth.users (id, email, email_verified, name, picture_url, created_at, last_login_at)
SELECT gen_random_uuid(),
       lower(a.email),
       COALESCE(a.email_verified, false),
       a.name,
       a.picture_url,
       a.created_at,
       a.last_login_at
FROM auth.accounts a;

-- 2) проставим user_id в accounts по email
UPDATE auth.accounts a
SET user_id = u.id
FROM auth.users u
WHERE lower(a.email) = u.email
  AND a.user_id IS NULL;

-- 3) зафиксируем ссылочную целостность
ALTER TABLE auth.accounts
    ALTER COLUMN user_id SET NOT NULL;

ALTER TABLE auth.accounts
    ADD CONSTRAINT fk_accounts_user
        FOREIGN KEY (user_id) REFERENCES auth.users (id);

CREATE INDEX IF NOT EXISTS idx_accounts_user_id ON auth.accounts (user_id);

-- 4) снимаем уникальность по email в accounts, иначе не будет нескольких провайдеров на одного пользователя
DROP INDEX IF EXISTS auth.ux_accounts_email;

-- опционально: если хотите запретить два аккаунта одного провайдера на одного user
-- CREATE UNIQUE INDEX IF NOT EXISTS ux_accounts_user_provider ON auth.accounts (user_id, provider);
