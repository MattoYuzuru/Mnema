CREATE TABLE app_identity.admin_audit (
    audit_id UUID PRIMARY KEY DEFAULT uuidv7(),
    actor_account_id UUID NOT NULL,
    action TEXT NOT NULL CHECK (action IN ('BAN','UNBAN','GRANT_ADMIN','REVOKE_ADMIN')),
    resource_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp()
);
CREATE FUNCTION app_identity.admin_audit_immutable_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Admin audit is append-only' USING ERRCODE = '23000';
END;
$$;
CREATE TRIGGER admin_audit_immutable BEFORE UPDATE ON app_identity.admin_audit
    FOR EACH ROW EXECUTE FUNCTION app_identity.admin_audit_immutable_guard();
CREATE INDEX account_admin_directory ON app_identity.account(created_at DESC, account_id DESC);
