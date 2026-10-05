-- Promo codes, redemptions, pending discounts, abuse counters and the promo popup state (#302, AI-21).
--
-- promo_code: a code is stored only as the SHA-256 of its normalized form (upper case, no spaces or dashes); code_hint (first two and last two
-- characters) lets an admin tell codes apart. The plain code exists once, in the response that created it. MAX is not purchasable, so no code can grant it.
CREATE TABLE app_learning.promo_code (
    code_id UUID PRIMARY KEY,
    code_hash BYTEA NOT NULL UNIQUE CHECK (octet_length(code_hash) = 32),
    code_hint TEXT NOT NULL CHECK (char_length(code_hint) BETWEEN 3 AND 9),
    type TEXT NOT NULL CHECK (type IN ('TIER_DAYS', 'TIER_MONTHS', 'DISCOUNT_PERCENT')),
    plan TEXT CHECK (plan IN ('PLUS', 'PRO')),
    days INTEGER CHECK (days BETWEEN 1 AND 366),
    months INTEGER CHECK (months BETWEEN 1 AND 24),
    percent INTEGER CHECK (percent BETWEEN 1 AND 90),
    valid_from TIMESTAMPTZ NOT NULL CHECK (isfinite(valid_from)),
    valid_until TIMESTAMPTZ CHECK (valid_until IS NULL OR (isfinite(valid_until) AND valid_until > valid_from)),
    max_redemptions INTEGER NOT NULL CHECK (max_redemptions BETWEEN 1 AND 1000000),
    once_per_account BOOLEAN NOT NULL DEFAULT TRUE,
    channel TEXT CHECK (channel IS NULL OR char_length(channel) BETWEEN 1 AND 40),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL CHECK (isfinite(created_at)),
    created_by UUID NOT NULL,
    CHECK ((type = 'TIER_DAYS' AND plan IS NOT NULL AND days IS NOT NULL AND months IS NULL AND percent IS NULL)
        OR (type = 'TIER_MONTHS' AND plan IS NOT NULL AND months IS NOT NULL AND days IS NULL AND percent IS NULL)
        OR (type = 'DISCOUNT_PERCENT' AND percent IS NOT NULL AND days IS NULL AND months IS NULL AND valid_until IS NOT NULL))
);

-- promo_redemption: the audit of every successful redemption. It holds identifiers and keyed hashes only (the IP and the User-Agent are
-- HMAC-SHA256 hashes), never a code or an address. snapshot_id names the entitlement_inbox snapshot of a tier code; a discount has none.
CREATE TABLE app_learning.promo_redemption (
    redemption_id UUID PRIMARY KEY,
    code_id UUID NOT NULL REFERENCES app_learning.promo_code(code_id),
    owner_id UUID NOT NULL,
    redeemed_at TIMESTAMPTZ NOT NULL CHECK (isfinite(redeemed_at)),
    ip_hash BYTEA CHECK (ip_hash IS NULL OR octet_length(ip_hash) = 32),
    device_hash BYTEA CHECK (device_hash IS NULL OR octet_length(device_hash) = 32),
    snapshot_id TEXT,
    once_per_account BOOLEAN NOT NULL
);
CREATE UNIQUE INDEX promo_redemption_once ON app_learning.promo_redemption(code_id, owner_id) WHERE once_per_account;
CREATE INDEX promo_redemption_code ON app_learning.promo_redemption(code_id);
CREATE INDEX promo_redemption_ip ON app_learning.promo_redemption(ip_hash, redeemed_at) WHERE ip_hash IS NOT NULL;

-- promo_discount: the pending percentage an account has earned. It is independent of entitlements: the billing context (#79) reads it when it
-- prices a purchase. One row per account: the larger percentage wins.
CREATE TABLE app_learning.promo_discount (
    owner_id UUID PRIMARY KEY,
    percent INTEGER NOT NULL CHECK (percent BETWEEN 1 AND 90),
    plan TEXT CHECK (plan IN ('PLUS', 'PRO')),
    valid_until TIMESTAMPTZ NOT NULL CHECK (isfinite(valid_until)),
    code_id UUID NOT NULL REFERENCES app_learning.promo_code(code_id),
    created_at TIMESTAMPTZ NOT NULL CHECK (isfinite(created_at))
);

-- promo_attempt: one row per redemption attempt (right or wrong), the counter of the hourly limits per account and per address hash. Rows older
-- than a day are deleted by the next attempt of the same account.
CREATE TABLE app_learning.promo_attempt (
    attempt_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    owner_id UUID NOT NULL,
    ip_hash BYTEA CHECK (ip_hash IS NULL OR octet_length(ip_hash) = 32),
    attempted_at TIMESTAMPTZ NOT NULL CHECK (isfinite(attempted_at))
);
CREATE INDEX promo_attempt_owner ON app_learning.promo_attempt(owner_id, attempted_at);
CREATE INDEX promo_attempt_ip ON app_learning.promo_attempt(ip_hash, attempted_at) WHERE ip_hash IS NOT NULL;

-- promo_popup_state: per account, not per device. dismissed_at starts the cooldown; declined_at is final.
CREATE TABLE app_learning.promo_popup_state (
    owner_id UUID PRIMARY KEY,
    campaign_id TEXT NOT NULL CHECK (char_length(campaign_id) BETWEEN 1 AND 60),
    last_shown_at TIMESTAMPTZ CHECK (last_shown_at IS NULL OR isfinite(last_shown_at)),
    dismissed_at TIMESTAMPTZ CHECK (dismissed_at IS NULL OR isfinite(dismissed_at)),
    declined_at TIMESTAMPTZ CHECK (declined_at IS NULL OR isfinite(declined_at))
);
