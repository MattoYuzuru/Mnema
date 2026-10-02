-- Usage ledger (contracts/usage): per-period allowance, materialized balance, reservations, append-only ledger,
-- fair-use and count-cap counters, and the entitlement snapshots billing will publish. No personal data: rows carry an
-- account id and opaque references only.

-- What a plan granted an account for one calendar period (zone: learning.usage.calendar-zone). Enforcement reads the
-- stored row, so a catalog edit never moves the limits of a period that already started.
CREATE TABLE app_learning.usage_allowance (
    owner_id UUID NOT NULL,
    period_id TEXT NOT NULL CHECK (period_id ~ '^[0-9]{4}-(0[1-9]|1[0-2])$'),
    plan TEXT NOT NULL CHECK (plan IN ('FREE', 'PLUS', 'PRO', 'MAX')),
    source TEXT NOT NULL CHECK (source IN ('CONFIG', 'BILLING')),
    period_start TIMESTAMPTZ NOT NULL CHECK (isfinite(period_start)),
    period_end TIMESTAMPTZ NOT NULL CHECK (isfinite(period_end) AND period_end > period_start),
    valid_until TIMESTAMPTZ NOT NULL CHECK (isfinite(valid_until)),
    credits_total INTEGER NOT NULL CHECK (credits_total >= 0),
    -- Free only: weekly portions of the bar as comma-separated credits; NULL when the whole bar opens at once.
    portions TEXT CHECK (portions IS NULL OR portions ~ '^[0-9]+(,[0-9]+)*$'),
    -- Paid plans only: the share of the bar that one calendar day may debit.
    burst_fraction NUMERIC(4, 3) CHECK (burst_fraction IS NULL OR (burst_fraction > 0 AND burst_fraction <= 1)),
    -- Fair-use buckets in storage units (speech to text in seconds); NULL monthly = unlimited within fair use.
    stt_month_seconds BIGINT CHECK (stt_month_seconds IS NULL OR stt_month_seconds >= 0),
    stt_day_seconds BIGINT NOT NULL CHECK (stt_day_seconds >= 0),
    assessment_month BIGINT NOT NULL CHECK (assessment_month >= 0),
    assessment_day BIGINT NOT NULL CHECK (assessment_day >= 0),
    cap_podcasts INTEGER NOT NULL CHECK (cap_podcasts >= 0),
    cap_quality_images INTEGER NOT NULL CHECK (cap_quality_images >= 0),
    cap_high_factcheck INTEGER NOT NULL CHECK (cap_high_factcheck >= 0),
    cap_smart_plan INTEGER NOT NULL CHECK (cap_smart_plan >= 0),
    smart_plan_window TEXT NOT NULL CHECK (smart_plan_window IN ('DAY', 'WEEK', 'MONTH')),
    created_at TIMESTAMPTZ NOT NULL CHECK (isfinite(created_at)),
    updated_at TIMESTAMPTZ NOT NULL CHECK (isfinite(updated_at)),
    PRIMARY KEY (owner_id, period_id)
);

-- The materialized credit balance of one period. available = unlocked - used - reserved and can never go negative:
-- reservation is one conditional UPDATE guarded by row_version (see UsageRepository#tryReserve).
CREATE TABLE app_learning.usage_balance (
    owner_id UUID NOT NULL,
    period_id TEXT NOT NULL,
    unlocked INTEGER NOT NULL DEFAULT 0 CHECK (unlocked >= 0),
    used INTEGER NOT NULL DEFAULT 0 CHECK (used >= 0),
    reserved INTEGER NOT NULL DEFAULT 0 CHECK (reserved >= 0),
    row_version BIGINT NOT NULL DEFAULT 0 CHECK (row_version >= 0),
    updated_at TIMESTAMPTZ NOT NULL CHECK (isfinite(updated_at)),
    PRIMARY KEY (owner_id, period_id),
    FOREIGN KEY (owner_id, period_id) REFERENCES app_learning.usage_allowance(owner_id, period_id),
    CONSTRAINT usage_balance_never_negative CHECK (used + reserved <= unlocked)
);

-- A hold on one period's balance. session_id and turn_id are opaque: the generation module owns what they name.
CREATE TABLE app_learning.usage_reservation (
    reservation_id UUID PRIMARY KEY,
    owner_id UUID NOT NULL,
    period_id TEXT NOT NULL,
    scope TEXT NOT NULL CHECK (scope IN ('SESSION', 'TURN', 'STEP')),
    session_id UUID,
    turn_id UUID,
    state TEXT NOT NULL CHECK (state IN ('ACTIVE', 'SETTLED', 'RELEASED', 'EXPIRED')),
    held_credits INTEGER NOT NULL CHECK (held_credits > 0),
    debited_credits INTEGER NOT NULL DEFAULT 0 CHECK (debited_credits >= 0 AND debited_credits <= held_credits),
    rate_card_version TEXT NOT NULL CHECK (rate_card_version ~ '^rc-v[0-9]{1,3}$'),
    created_at TIMESTAMPTZ NOT NULL CHECK (isfinite(created_at)),
    -- min(learning.usage.reservation-ttl, end of the period): a hold never outlives its period.
    expires_at TIMESTAMPTZ NOT NULL CHECK (isfinite(expires_at) AND expires_at > created_at),
    ended_at TIMESTAMPTZ CHECK (ended_at IS NULL OR isfinite(ended_at)),
    CONSTRAINT usage_reservation_ended_iff_terminal CHECK ((state = 'ACTIVE') = (ended_at IS NULL)),
    FOREIGN KEY (owner_id, period_id) REFERENCES app_learning.usage_balance(owner_id, period_id)
);
-- The expiry worker reads only live holds that are due.
CREATE INDEX usage_reservation_due ON app_learning.usage_reservation(expires_at) WHERE state = 'ACTIVE';
CREATE INDEX usage_reservation_owner ON app_learning.usage_reservation(owner_id, created_at DESC);

-- Append-only ledger. credits is signed (GRANT +, DEBIT -). A fair-use or cap consumption is a DEBIT of 0 credits that
-- carries its bucket and units. idempotency_key is globally unique, so a retried write is a no-op. reference is an
-- opaque step or call id: no prose, no personal data.
CREATE TABLE app_learning.usage_ledger_entry (
    entry_id UUID PRIMARY KEY,
    owner_id UUID NOT NULL,
    kind TEXT NOT NULL CHECK (kind IN ('GRANT', 'DEBIT', 'REFUND', 'ADJUSTMENT', 'EXPIRE')),
    credits INTEGER NOT NULL,
    cost_micros BIGINT CHECK (cost_micros IS NULL OR cost_micros >= 0),
    operation TEXT CHECK (operation IS NULL OR operation ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    rate_card_version TEXT NOT NULL CHECK (rate_card_version ~ '^rc-v[0-9]{1,3}$'),
    period_id TEXT NOT NULL CHECK (period_id ~ '^[0-9]{4}-(0[1-9]|1[0-2])$'),
    idempotency_key TEXT NOT NULL UNIQUE CHECK (octet_length(idempotency_key) BETWEEN 1 AND 200),
    reservation_id UUID REFERENCES app_learning.usage_reservation(reservation_id),
    reference TEXT CHECK (reference IS NULL OR reference ~ '^[A-Za-z0-9][A-Za-z0-9_.:+@/-]{0,199}$'),
    bucket TEXT CHECK (bucket IS NULL OR bucket IN ('STT', 'ASSESSMENT', 'PODCASTS', 'QUALITY_IMAGES', 'HIGH_FACTCHECK', 'SMART_PLAN')),
    units BIGINT CHECK (units IS NULL OR units > 0),
    created_at TIMESTAMPTZ NOT NULL CHECK (isfinite(created_at)),
    CONSTRAINT usage_ledger_sign CHECK (
        (kind = 'GRANT' AND credits > 0) OR (kind = 'DEBIT' AND credits <= 0) OR (kind = 'REFUND' AND credits > 0)
        OR (kind = 'EXPIRE' AND credits < 0) OR kind = 'ADJUSTMENT'),
    CONSTRAINT usage_ledger_bucket_units CHECK ((bucket IS NULL) = (units IS NULL)),
    CONSTRAINT usage_ledger_bucket_is_free CHECK (bucket IS NULL OR (kind = 'DEBIT' AND credits = 0))
);
CREATE INDEX usage_ledger_owner_time ON app_learning.usage_ledger_entry(owner_id, created_at);
CREATE INDEX usage_ledger_reservation ON app_learning.usage_ledger_entry(reservation_id) WHERE reservation_id IS NOT NULL;

-- Rows are immutable. DELETE stays possible for the account-deletion purge (a retention task, see guide.md).
CREATE FUNCTION app_learning.usage_ledger_immutable_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'usage ledger entries are append-only' USING ERRCODE = '23000';
END;
$$;
CREATE TRIGGER usage_ledger_immutable BEFORE UPDATE ON app_learning.usage_ledger_entry
    FOR EACH ROW EXECUTE FUNCTION app_learning.usage_ledger_immutable_guard();

-- Fair-use buckets (speech to text in seconds, answer checks) and count caps, per window instance. window_start is the
-- UTC instant of the calendar boundary (day, Monday or month) in learning.usage.calendar-zone.
CREATE TABLE app_learning.usage_counter (
    owner_id UUID NOT NULL,
    bucket TEXT NOT NULL CHECK (bucket IN ('STT', 'ASSESSMENT', 'PODCASTS', 'QUALITY_IMAGES', 'HIGH_FACTCHECK', 'SMART_PLAN')),
    window_kind TEXT NOT NULL CHECK (window_kind IN ('DAY', 'WEEK', 'MONTH')),
    window_start TIMESTAMPTZ NOT NULL CHECK (isfinite(window_start)),
    used BIGINT NOT NULL DEFAULT 0 CHECK (used >= 0),
    PRIMARY KEY (owner_id, bucket, window_kind, window_start)
);
CREATE INDEX usage_counter_window ON app_learning.usage_counter(window_start);

-- The contract for the future billing context: an idempotent entitlement snapshot (plan, period, allowances,
-- valid_until). Until billing exists the only writer is the validated insert of EntitlementInbox; usage reads
-- entitlements from the configuration source.
CREATE TABLE app_learning.entitlement_inbox (
    snapshot_id TEXT PRIMARY KEY CHECK (snapshot_id ~ '^[A-Za-z0-9][A-Za-z0-9_.:+@/-]{0,199}$'),
    owner_id UUID NOT NULL,
    plan TEXT NOT NULL CHECK (plan IN ('FREE', 'PLUS', 'PRO', 'MAX')),
    source TEXT NOT NULL CHECK (source IN ('BILLING', 'PROMO')),
    period_start TIMESTAMPTZ NOT NULL CHECK (isfinite(period_start)),
    period_end TIMESTAMPTZ NOT NULL CHECK (isfinite(period_end) AND period_end > period_start),
    allowances JSONB NOT NULL CHECK (jsonb_typeof(allowances) = 'object' AND octet_length(allowances::text) <= 8192),
    valid_until TIMESTAMPTZ NOT NULL CHECK (isfinite(valid_until)),
    received_at TIMESTAMPTZ NOT NULL CHECK (isfinite(received_at))
);
CREATE INDEX entitlement_inbox_owner ON app_learning.entitlement_inbox(owner_id, valid_until DESC);
