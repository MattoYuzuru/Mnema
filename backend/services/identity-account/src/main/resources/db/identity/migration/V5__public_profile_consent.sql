-- Public profile consent (152-FZ art. 10.1): separate, per field, revocable. No row means no consent.
-- A public card exists only for an enabled row; withdrawal stores every field flag as false.
CREATE TABLE app_identity.public_profile_consent (
    account_id UUID PRIMARY KEY REFERENCES app_identity.account(account_id) ON DELETE RESTRICT,
    enabled BOOLEAN NOT NULL,
    show_display_name BOOLEAN NOT NULL,
    show_avatar BOOLEAN NOT NULL,
    show_bio BOOLEAN NOT NULL,
    text_version TEXT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT ck_public_profile_consent_text_version CHECK (text_version ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$'),
    CONSTRAINT ck_public_profile_consent_withdrawn CHECK (
        enabled OR (NOT show_display_name AND NOT show_avatar AND NOT show_bio)
    )
);

-- Append-only evidence of every effective consent change and the text version it was given for.
CREATE TABLE app_identity.public_profile_consent_event (
    event_id UUID PRIMARY KEY DEFAULT uuidv7(),
    account_id UUID NOT NULL REFERENCES app_identity.account(account_id) ON DELETE RESTRICT,
    action TEXT NOT NULL CHECK (action IN ('GRANT','CHANGE','WITHDRAW')),
    enabled BOOLEAN NOT NULL,
    show_display_name BOOLEAN NOT NULL,
    show_avatar BOOLEAN NOT NULL,
    show_bio BOOLEAN NOT NULL,
    text_version TEXT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT ck_public_profile_consent_event_text_version CHECK (text_version ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$'),
    CONSTRAINT ck_public_profile_consent_event_action CHECK (
        (action = 'WITHDRAW' AND NOT enabled AND NOT show_display_name AND NOT show_avatar AND NOT show_bio)
        OR (action <> 'WITHDRAW' AND enabled)
    )
);
CREATE INDEX ix_public_profile_consent_event_account
    ON app_identity.public_profile_consent_event (account_id, event_id);

-- Rows are never updated and the table is never truncated. The only deletion is the account purge, which runs while the
-- account is PURGING (data minimisation after the deletion deadline); the guard refuses it in every other state.
CREATE FUNCTION app_identity.public_profile_consent_event_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF EXISTS (SELECT 1 FROM app_identity.account
                   WHERE account_id = OLD.account_id AND deletion_state = 'PURGING') THEN
            RETURN OLD;
        END IF;
    END IF;
    RAISE EXCEPTION 'Public profile consent journal is append-only' USING ERRCODE = '23000';
END;
$$;
CREATE TRIGGER public_profile_consent_event_immutable BEFORE UPDATE OR DELETE ON app_identity.public_profile_consent_event
    FOR EACH ROW EXECUTE FUNCTION app_identity.public_profile_consent_event_guard();
CREATE TRIGGER public_profile_consent_event_no_truncate BEFORE TRUNCATE ON app_identity.public_profile_consent_event
    FOR EACH STATEMENT EXECUTE FUNCTION app_identity.public_profile_consent_event_guard();
