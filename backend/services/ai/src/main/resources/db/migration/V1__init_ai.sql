CREATE SCHEMA IF NOT EXISTS app_ai;

DO
$$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'ai_provider_status') THEN
            CREATE TYPE ai_provider_status AS ENUM (
                'active',
                'inactive'
                );
        END IF;
    END
$$ LANGUAGE plpgsql;

DO
$$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'ai_job_status') THEN
            CREATE TYPE ai_job_status AS ENUM (
                'queued',
                'processing',
                'completed',
                'failed',
                'canceled'
                );
        END IF;
    END
$$ LANGUAGE plpgsql;

DO
$$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'ai_job_type') THEN
            CREATE TYPE ai_job_type AS ENUM (
                'generic',
                'enrich',
                'tts'
                );
        END IF;
    END
$$ LANGUAGE plpgsql;

DO
$$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'ai_job_step_status') THEN
            CREATE TYPE ai_job_step_status AS ENUM (
                'queued',
                'processing',
                'completed',
                'failed'
                );
        END IF;
    END
$$ LANGUAGE plpgsql;

CREATE TABLE IF NOT EXISTS app_ai.ai_provider_credentials
(
    id                 UUID               NOT NULL DEFAULT gen_random_uuid(),
    user_id            UUID               NOT NULL,
    provider           TEXT               NOT NULL,
    alias              TEXT,
    encrypted_secret   BYTEA              NOT NULL,
    encrypted_data_key BYTEA,
    key_id             TEXT,
    nonce              BYTEA,
    aad                BYTEA,
    status             ai_provider_status NOT NULL DEFAULT 'active',
    created_at         TIMESTAMPTZ        NOT NULL DEFAULT now(),
    last_used_at       TIMESTAMPTZ,
    updated_at         TIMESTAMPTZ,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS app_ai.ai_jobs
(
    job_id         UUID          NOT NULL DEFAULT gen_random_uuid(),
    request_id     UUID          NOT NULL,
    user_id        UUID          NOT NULL,
    deck_id        UUID,
    type           ai_job_type   NOT NULL,
    status         ai_job_status NOT NULL DEFAULT 'queued',
    progress       INT           NOT NULL DEFAULT 0,
    params_json    JSONB,
    input_hash     TEXT,
    result_summary JSONB,
    attempts       INT           NOT NULL DEFAULT 0,
    next_run_at    TIMESTAMPTZ,
    locked_at      TIMESTAMPTZ,
    locked_by      TEXT,
    started_at     TIMESTAMPTZ,
    completed_at   TIMESTAMPTZ,
    error_message  TEXT,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ,
    PRIMARY KEY (job_id),
    UNIQUE (request_id)
);

CREATE TABLE IF NOT EXISTS app_ai.ai_job_steps
(
    job_id        UUID                NOT NULL,
    step_name     TEXT                NOT NULL,
    status        ai_job_step_status  NOT NULL,
    started_at    TIMESTAMPTZ,
    ended_at      TIMESTAMPTZ,
    error_summary TEXT,
    PRIMARY KEY (job_id, step_name),
    CONSTRAINT fk_ai_job_steps_job FOREIGN KEY (job_id) REFERENCES app_ai.ai_jobs (job_id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS app_ai.ai_usage_ledger
(
    id            BIGSERIAL PRIMARY KEY,
    request_id    UUID        NOT NULL,
    job_id        UUID,
    user_id       UUID        NOT NULL,
    provider      TEXT,
    model         TEXT,
    tokens_in     INT,
    tokens_out    INT,
    cost_estimate NUMERIC,
    prompt_hash   TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS app_ai.ai_quota
(
    user_id      UUID        NOT NULL,
    period_start DATE        NOT NULL,
    tokens_limit INT,
    tokens_used  INT         NOT NULL DEFAULT 0,
    cost_limit   NUMERIC,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, period_start)
);

CREATE TABLE IF NOT EXISTS app_ai.subscriptions
(
    user_id             UUID PRIMARY KEY,
    plan_id             TEXT,
    subscription_status TEXT,
    period_end          TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS ix_ai_provider_credentials_user_id ON app_ai.ai_provider_credentials (user_id);
CREATE INDEX IF NOT EXISTS ix_ai_provider_credentials_status ON app_ai.ai_provider_credentials (status);

CREATE INDEX IF NOT EXISTS ix_ai_jobs_user_id ON app_ai.ai_jobs (user_id);
CREATE INDEX IF NOT EXISTS ix_ai_jobs_status ON app_ai.ai_jobs (status);
CREATE INDEX IF NOT EXISTS ix_ai_jobs_created_at ON app_ai.ai_jobs (created_at);
CREATE INDEX IF NOT EXISTS ix_ai_jobs_request_id ON app_ai.ai_jobs (request_id);
CREATE INDEX IF NOT EXISTS ix_ai_jobs_next_run_at ON app_ai.ai_jobs (next_run_at);

CREATE INDEX IF NOT EXISTS ix_ai_job_steps_job_id ON app_ai.ai_job_steps (job_id);

CREATE INDEX IF NOT EXISTS ix_ai_usage_ledger_user_id ON app_ai.ai_usage_ledger (user_id);
CREATE INDEX IF NOT EXISTS ix_ai_usage_ledger_job_id ON app_ai.ai_usage_ledger (job_id);
CREATE INDEX IF NOT EXISTS ix_ai_usage_ledger_request_id ON app_ai.ai_usage_ledger (request_id);
CREATE INDEX IF NOT EXISTS ix_ai_usage_ledger_created_at ON app_ai.ai_usage_ledger (created_at);

CREATE INDEX IF NOT EXISTS ix_ai_quota_user_id ON app_ai.ai_quota (user_id);
