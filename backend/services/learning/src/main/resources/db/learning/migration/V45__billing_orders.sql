-- Billing core (#389, epic #79): purchase orders of Plus and Pro and the append-only audit of every exchange with the bank.
--
-- billing_order: one purchase attempt of one plan for one period. order_id is also the T-Bank OrderId; payment_id is the bank PaymentId, stored when Init
-- answers. Amounts are kopecks. status follows contracts/billing: CREATED -> PENDING -> PAID | FAILED, PAID -> REFUNDED, REVIEW for a confirmed payment that
-- does not match the order. PaymentStateApplier is the only writer of status after Init, under a row lock, and a PAID row carries the period and the id of the
-- entitlement_inbox snapshot it granted. No card data exists here: the hosted payment form belongs to the bank.
--
-- Retention: these are financial records; they are kept. Deleting them on an account purge is a decision of the account-purge epic with legal task #351
-- (and the receipts and refunds of #392), not of this migration.
CREATE TABLE app_learning.billing_order (
    order_id UUID PRIMARY KEY,
    owner_id UUID NOT NULL,
    plan TEXT NOT NULL CHECK (plan IN ('PLUS', 'PRO')),
    period TEXT NOT NULL CHECK (period IN ('MONTH')),
    status TEXT NOT NULL CHECK (status IN ('CREATED', 'PENDING', 'PAID', 'FAILED', 'REFUNDED', 'REVIEW')),
    amount_kopecks BIGINT NOT NULL CHECK (amount_kopecks > 0),
    list_price_kopecks BIGINT NOT NULL CHECK (list_price_kopecks > 0 AND amount_kopecks <= list_price_kopecks),
    discount_percent INTEGER CHECK (discount_percent BETWEEN 1 AND 90),
    discount_code_id UUID,
    payment_id TEXT UNIQUE CHECK (payment_id ~ '^[0-9]{1,20}$'),
    payment_url TEXT CHECK (payment_url IS NULL OR char_length(payment_url) <= 2048),
    provider_status TEXT CHECK (provider_status IS NULL OR char_length(provider_status) BETWEEN 1 AND 40),
    failure_reason TEXT CHECK (failure_reason IS NULL OR char_length(failure_reason) BETWEEN 1 AND 40),
    snapshot_id TEXT CHECK (snapshot_id IS NULL OR char_length(snapshot_id) BETWEEN 1 AND 200),
    period_start TIMESTAMPTZ CHECK (period_start IS NULL OR isfinite(period_start)),
    period_end TIMESTAMPTZ CHECK (period_end IS NULL OR isfinite(period_end)),
    paid_at TIMESTAMPTZ CHECK (paid_at IS NULL OR isfinite(paid_at)),
    expires_at TIMESTAMPTZ NOT NULL CHECK (isfinite(expires_at)),
    created_at TIMESTAMPTZ NOT NULL CHECK (isfinite(created_at)),
    updated_at TIMESTAMPTZ NOT NULL CHECK (isfinite(updated_at)),
    last_checked_at TIMESTAMPTZ CHECK (last_checked_at IS NULL OR isfinite(last_checked_at)),
    init_claimed_at TIMESTAMPTZ CHECK (init_claimed_at IS NULL OR isfinite(init_claimed_at)),
    row_version BIGINT NOT NULL DEFAULT 0 CHECK (row_version >= 0),
    CHECK ((discount_percent IS NULL) = (discount_code_id IS NULL)),
    CHECK (period_start IS NULL OR period_end IS NULL OR period_end > period_start),
    CHECK (status NOT IN ('PAID', 'REFUNDED')
        OR (paid_at IS NOT NULL AND period_start IS NOT NULL AND period_end IS NOT NULL AND snapshot_id IS NOT NULL))
);
CREATE INDEX billing_order_owner ON app_learning.billing_order(owner_id, created_at);
-- One unfinished order per promo discount: the discount of a code prices one purchase, so an account cannot open several discounted orders and pay them all.
CREATE UNIQUE INDEX billing_order_discount_open ON app_learning.billing_order(owner_id, discount_code_id)
    WHERE status IN ('CREATED', 'PENDING') AND discount_code_id IS NOT NULL;
-- The reconciler looks at unfinished orders only; terminal orders never enter this index.
CREATE INDEX billing_order_open ON app_learning.billing_order(created_at) WHERE status IN ('CREATED', 'PENDING');

-- billing_event: the audit of what the bank said and what billing did with it, one row per exchange. Never stored: Pan, CardId, ExpDate, RebillId, Data, Token
-- or a raw body. Notifications are deduplicated per (payment_id, bank_status): the bank repeats them until it gets OK. The same retention note as billing_order.
CREATE TABLE app_learning.billing_event (
    event_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id UUID,
    payment_id TEXT CHECK (payment_id IS NULL OR payment_id ~ '^[0-9]{1,20}$'),
    source TEXT NOT NULL CHECK (source IN ('INIT', 'NOTIFICATION', 'GET_STATE', 'RECONCILE')),
    bank_status TEXT CHECK (bank_status IS NULL OR char_length(bank_status) BETWEEN 1 AND 40),
    success BOOLEAN,
    error_code TEXT CHECK (error_code IS NULL OR char_length(error_code) BETWEEN 1 AND 20),
    amount_kopecks BIGINT CHECK (amount_kopecks IS NULL OR amount_kopecks >= 0),
    outcome TEXT CHECK (outcome IS NULL OR char_length(outcome) BETWEEN 1 AND 40),
    received_at TIMESTAMPTZ NOT NULL CHECK (isfinite(received_at))
);
CREATE INDEX billing_event_order ON app_learning.billing_event(order_id, event_id) WHERE order_id IS NOT NULL;
CREATE UNIQUE INDEX billing_event_notification_once ON app_learning.billing_event(payment_id, bank_status) WHERE source = 'NOTIFICATION';
