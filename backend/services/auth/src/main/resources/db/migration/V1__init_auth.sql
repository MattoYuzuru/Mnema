CREATE SCHEMA IF NOT EXISTS auth;

-- Таблица аккаунтов (маппинг внешнего провайдера на локального пользователя)
CREATE TABLE IF NOT EXISTS auth.accounts
(
    id             UUID PRIMARY KEY     DEFAULT gen_random_uuid(),
    provider       VARCHAR     NOT NULL,
    provider_sub   VARCHAR     NOT NULL,
    email          TEXT NOT NULL,
    email_verified BOOLEAN              DEFAULT FALSE,
    name           VARCHAR     NOT NULL,
    picture_url    VARCHAR,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_login_at  TIMESTAMPTZ
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_accounts_provider_sub ON auth.accounts (provider, provider_sub);
CREATE UNIQUE INDEX IF NOT EXISTS ux_accounts_email ON auth.accounts (email);

-- Минимальный набор таблиц Spring Authorization Server (JPA схема хранения)
CREATE TABLE IF NOT EXISTS auth.oauth2_registered_client
(
    id                            VARCHAR PRIMARY KEY,
    client_id                     VARCHAR     NOT NULL UNIQUE,
    client_id_issued_at           TIMESTAMPTZ NOT NULL,
    client_secret                 VARCHAR,
    client_secret_expires_at      TIMESTAMPTZ,
    client_name                   VARCHAR     NOT NULL,
    client_authentication_methods VARCHAR     NOT NULL,
    authorization_grant_types     VARCHAR     NOT NULL,
    redirect_uris                 VARCHAR,
    post_logout_redirect_uris     VARCHAR,
    scopes                        VARCHAR     NOT NULL,
    client_settings               VARCHAR     NOT NULL,
    token_settings                VARCHAR     NOT NULL
);

CREATE TABLE IF NOT EXISTS auth.oauth2_authorization
(
    id                            VARCHAR PRIMARY KEY,
    registered_client_id          VARCHAR NOT NULL,
    principal_name                VARCHAR NOT NULL,
    authorization_grant_type      VARCHAR NOT NULL,
    authorized_scopes             VARCHAR,
    attributes                    TEXT,
    state                         VARCHAR,
    authorization_code_value      BYTEA,
    authorization_code_issued_at  TIMESTAMPTZ,
    authorization_code_expires_at TIMESTAMPTZ,
    authorization_code_metadata   TEXT,
    access_token_value            BYTEA,
    access_token_issued_at        TIMESTAMPTZ,
    access_token_expires_at       TIMESTAMPTZ,
    access_token_metadata         TEXT,
    access_token_type             VARCHAR,
    access_token_scopes           VARCHAR,
    oidc_id_token_value           BYTEA,
    oidc_id_token_issued_at       TIMESTAMPTZ,
    oidc_id_token_expires_at      TIMESTAMPTZ,
    oidc_id_token_metadata        TEXT,
    refresh_token_value           BYTEA,
    refresh_token_issued_at       TIMESTAMPTZ,
    refresh_token_expires_at      TIMESTAMPTZ,
    refresh_token_metadata        TEXT
);

CREATE TABLE IF NOT EXISTS auth.oauth2_authorization_consent
(
    registered_client_id VARCHAR NOT NULL,
    principal_name       VARCHAR NOT NULL,
    authorities          VARCHAR NOT NULL,
    PRIMARY KEY (registered_client_id, principal_name)
);