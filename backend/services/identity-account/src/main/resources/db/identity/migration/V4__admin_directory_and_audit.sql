-- Append-only journal of moderation. SUCCESS rows commit in the moderation transaction; DENIED rows record a refused attempt by a
-- current administrator. The ban reason is the owner-typed text already held on the account, kept here after an unban clears it.
CREATE TABLE app_identity.admin_audit (
    audit_id UUID PRIMARY KEY DEFAULT uuidv7(),
    actor_account_id UUID NOT NULL,
    action TEXT NOT NULL CHECK (action IN ('BAN','UNBAN','GRANT_ADMIN','REVOKE_ADMIN')),
    resource_id UUID NOT NULL,
    outcome TEXT NOT NULL DEFAULT 'SUCCESS' CHECK (outcome IN ('SUCCESS','DENIED')),
    reason TEXT CHECK (reason IS NULL OR (action = 'BAN' AND outcome = 'SUCCESS' AND char_length(reason) BETWEEN 1 AND 280)),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp()
);
CREATE FUNCTION app_identity.admin_audit_immutable_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Admin audit is append-only' USING ERRCODE = '23000';
END;
$$;
-- Rows are never updated or deleted; retention and erasure belong to the account-purge workstream (#409) and need an explicit migration.
CREATE TRIGGER admin_audit_immutable BEFORE UPDATE OR DELETE ON app_identity.admin_audit
    FOR EACH ROW EXECUTE FUNCTION app_identity.admin_audit_immutable_guard();
CREATE TRIGGER admin_audit_no_truncate BEFORE TRUNCATE ON app_identity.admin_audit
    FOR EACH STATEMENT EXECUTE FUNCTION app_identity.admin_audit_immutable_guard();
CREATE INDEX account_admin_directory ON app_identity.account(created_at DESC, account_id DESC);
