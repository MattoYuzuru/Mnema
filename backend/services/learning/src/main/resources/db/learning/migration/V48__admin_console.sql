-- Owner operations read persisted facts; no account data is copied into Learning.
CREATE TABLE app_learning.admin_audit (
    audit_id UUID PRIMARY KEY DEFAULT uuidv7(),
    actor_account_id UUID NOT NULL,
    action TEXT NOT NULL CHECK (action IN ('EVENT_CREATE','EVENT_REPLACE','EVENT_DELETE','PROMO_CREATE','PROMO_ENABLE','PROMO_DISABLE')),
    resource_id UUID NOT NULL,
    command_id UUID,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp()
);
CREATE FUNCTION app_learning.admin_audit_immutable_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Admin audit is append-only' USING ERRCODE = '23000';
END;
$$;
-- Rows are never updated or deleted; retention and erasure belong to the account-purge workstream (#409) and need an explicit migration.
CREATE TRIGGER admin_audit_immutable BEFORE UPDATE OR DELETE ON app_learning.admin_audit
    FOR EACH ROW EXECUTE FUNCTION app_learning.admin_audit_immutable_guard();
CREATE TRIGGER admin_audit_no_truncate BEFORE TRUNCATE ON app_learning.admin_audit
    FOR EACH STATEMENT EXECUTE FUNCTION app_learning.admin_audit_immutable_guard();
CREATE INDEX usage_ledger_reporting_time ON app_learning.usage_ledger_entry(created_at) WHERE kind = 'DEBIT';
CREATE INDEX study_attempt_reporting_time ON app_learning.study_attempt_tombstone(submitted_at);
CREATE INDEX study_session_reporting_complete ON app_learning.study_session(completed_at) WHERE completed_at IS NOT NULL;
CREATE INDEX generation_provenance_reporting_time ON app_learning.generation_provenance(created_at);
-- Revenue reporting reads confirmed orders by their payment time.
CREATE INDEX billing_order_reporting_paid ON app_learning.billing_order(paid_at) WHERE status IN ('PAID', 'REFUNDED');
