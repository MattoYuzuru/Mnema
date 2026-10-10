-- НПД receipts (#392, epic #79): a durable outbox of the «Мой налог» receipt of every paid order, and of its annulment when the order is refunded.
-- The seller is on НПД; the bank cash register is not used, so every income needs a receipt registered through the (unofficial) lknpd.nalog.ru API
-- (422-ФЗ art. 14; a refund annuls the receipt). The row is written in the transaction that moves an order to PAID (exactly once: order_id is the key) and
-- is updated by the receipt worker only, never by a request thread.
--
-- state: PENDING -> SENDING -> REGISTERED -> CANCEL_PENDING -> CANCELLED, and FAILED_PERMANENT for a refusal retrying cannot cure.
--   SENDING means a request may be on the wire: before any resend the worker looks the receipt up in the taxpayer's incomes (no idempotency key exists).
--   A refund of a PENDING receipt cancels it locally (nothing was registered); a refund while SENDING sets cancel_requested, and the worker cancels as
--   soon as it knows whether the receipt exists.
-- next_attempt_at doubles as the lease of a claimed row. deadline_at is the 9th of the month after the operation, end of day, Europe/Moscow.
-- last_error_code is a short machine code (TIMEOUT, HTTP_422, ...): never a response body, a token or a personal value.
--
-- Retention: financial records, kept like billing_order; the account-purge owner (#351) decides together with the order.
-- Orders paid before this migration get no row; the daily consistency check of the worker reports them (billing anomaly kind=receipt_mismatch).
CREATE TABLE app_learning.billing_receipt (
    order_id UUID PRIMARY KEY REFERENCES app_learning.billing_order(order_id),
    state TEXT NOT NULL CHECK (state IN ('PENDING', 'SENDING', 'REGISTERED', 'CANCEL_PENDING', 'CANCELLED', 'FAILED_PERMANENT')),
    service_name TEXT NOT NULL CHECK (char_length(service_name) BETWEEN 1 AND 128),
    amount_kopecks BIGINT NOT NULL CHECK (amount_kopecks > 0),
    operation_time TIMESTAMPTZ NOT NULL CHECK (isfinite(operation_time)),
    deadline_at TIMESTAMPTZ NOT NULL CHECK (isfinite(deadline_at)),
    receipt_uuid TEXT CHECK (receipt_uuid ~ '^[A-Za-z0-9_-]{1,64}$'),
    cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL CHECK (isfinite(next_attempt_at)),
    last_error_code TEXT CHECK (last_error_code ~ '^[A-Z0-9_]{1,40}$'),
    failed_alerted_at TIMESTAMPTZ CHECK (failed_alerted_at IS NULL OR isfinite(failed_alerted_at)),
    overdue_alerted_at TIMESTAMPTZ CHECK (overdue_alerted_at IS NULL OR isfinite(overdue_alerted_at)),
    created_at TIMESTAMPTZ NOT NULL CHECK (isfinite(created_at)),
    updated_at TIMESTAMPTZ NOT NULL CHECK (isfinite(updated_at)),
    row_version BIGINT NOT NULL DEFAULT 0 CHECK (row_version >= 0),
    CHECK (state NOT IN ('REGISTERED', 'CANCEL_PENDING') OR receipt_uuid IS NOT NULL)
);
-- The worker's claim and the deadline alarm look at unfinished rows only.
CREATE INDEX billing_receipt_due ON app_learning.billing_receipt(next_attempt_at) WHERE state IN ('PENDING', 'SENDING', 'CANCEL_PENDING');
CREATE INDEX billing_receipt_deadline ON app_learning.billing_receipt(deadline_at) WHERE state IN ('PENDING', 'SENDING') AND overdue_alerted_at IS NULL;
