-- Share/15 (#437): complaints about a public deck or profile, hiding by moderation and legal takedown by lineage
-- (community decks architecture sections 3 and 11; contracts/moderation/complaints.json).
--
-- content_complaint: one row per complaint. target_kind DECK keeps a snapshot of the deck id and its public code at filing time, PROFILE the account id
--   and the login that was asked for. There are NO foreign keys to deck or to Identity data: the record must outlive a deleted deck and an account purge
--   (a complaint is evidence). reporter_id is an opaque account id. status OPEN becomes DECIDED exactly once (decision, decided_at, decided_by); a guard
--   trigger freezes every other column. due_at is created_at + 30 days (the product's review promise), retain_until created_at + 3 years (the privacy
--   policy paragraph «Жалобы»); both are written by the application. The application never deletes: the trigger refuses to delete a row before
--   retain_until, and the foreign key of content_takedown refuses to delete a complaint that still has a standing takedown. The purge job is open work (#409).
--   One OPEN complaint per (reporter, target): the partial unique index is the backstop of the idempotent filing.
-- content_takedown: the legal takedown by lineage. One row per (reuse scope, unit kind, revision): "this revision of this unit is taken down". The unit key
--   is the member key (ITEM) or the exercise id (EXERCISE). The readers of content ask exactly one question, whether a row exists for the revision they are
--   about to serve, which is a unique-index probe. Rows are immutable (no update, no delete): reversing one is a deliberate operator change with its own journal row.
-- content_takedown_journal: the append-only record of every decision (time, actor, action, target snapshot, number of new takedown rows). It has no foreign
--   keys either. Like the complaint it can only be deleted after retain_until.
-- deck_publication.hidden_by_moderation: the HIDDEN decision. Not a deck column (deck_head_guard) and not part of the owner's publication command.
SET LOCAL lock_timeout = '5s';

CREATE TABLE app_learning.content_complaint (
    complaint_id UUID PRIMARY KEY DEFAULT uuidv7(),
    target_kind TEXT NOT NULL CHECK (target_kind IN ('DECK', 'PROFILE')),
    deck_id UUID,
    public_code TEXT CHECK (public_code IS NULL OR public_code ~ '^[1-9A-HJ-NP-Za-km-z]{10}$'),
    profile_account_id UUID,
    profile_username TEXT CHECK (profile_username IS NULL OR profile_username ~ '^[A-Za-z0-9_.-]{3,50}$'),
    reason TEXT NOT NULL CHECK (reason IN ('COPYRIGHT', 'PERSONAL_DATA', 'ILLEGAL', 'SPAM', 'OTHER')),
    reporter_text TEXT CHECK (reporter_text IS NULL OR char_length(reporter_text) BETWEEN 1 AND 1000),
    reporter_id UUID NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('OPEN', 'DECIDED')),
    decision TEXT CHECK (decision IS NULL OR decision IN ('REJECTED', 'HIDDEN', 'TAKEN_DOWN', 'ACCOUNT_REFERRED')),
    created_at TIMESTAMPTZ NOT NULL CHECK (isfinite(created_at)),
    due_at TIMESTAMPTZ NOT NULL CHECK (isfinite(due_at)),
    decided_at TIMESTAMPTZ CHECK (decided_at IS NULL OR isfinite(decided_at)),
    decided_by UUID,
    retain_until TIMESTAMPTZ NOT NULL CHECK (isfinite(retain_until)),
    CONSTRAINT content_complaint_target CHECK (
        (target_kind = 'DECK' AND deck_id IS NOT NULL AND public_code IS NOT NULL AND profile_account_id IS NULL AND profile_username IS NULL)
        OR (target_kind = 'PROFILE' AND profile_account_id IS NOT NULL AND profile_username IS NOT NULL AND deck_id IS NULL AND public_code IS NULL)),
    CONSTRAINT content_complaint_decided CHECK (
        (status = 'OPEN' AND decision IS NULL AND decided_at IS NULL AND decided_by IS NULL)
        OR (status = 'DECIDED' AND decision IS NOT NULL AND decided_at IS NOT NULL AND decided_by IS NOT NULL)),
    CONSTRAINT content_complaint_decision_target CHECK (
        decision IS NULL OR decision = 'REJECTED' OR (decision IN ('HIDDEN', 'TAKEN_DOWN') AND target_kind = 'DECK')
        OR (decision = 'ACCOUNT_REFERRED' AND target_kind = 'PROFILE')),
    CONSTRAINT content_complaint_times CHECK (due_at > created_at AND retain_until > created_at AND (decided_at IS NULL OR decided_at >= created_at))
);
-- the idempotent filing: one open complaint per reporter and target
CREATE UNIQUE INDEX content_complaint_open_per_reporter ON app_learning.content_complaint (reporter_id, target_kind, COALESCE(deck_id, profile_account_id))
    WHERE status = 'OPEN';
-- the reporter's daily limit is a range over his own complaints
CREATE INDEX content_complaint_reporter ON app_learning.content_complaint (reporter_id, created_at DESC);
-- the console queue: oldest deadline first, and decided newest first (keyset pages)
CREATE INDEX content_complaint_queue ON app_learning.content_complaint (due_at, complaint_id) WHERE status = 'OPEN';
CREATE INDEX content_complaint_decided ON app_learning.content_complaint (decided_at DESC, complaint_id DESC) WHERE status = 'DECIDED';
-- "other open complaints about this deck" in the detail
CREATE INDEX content_complaint_deck ON app_learning.content_complaint (deck_id) WHERE deck_id IS NOT NULL AND status = 'OPEN';

CREATE FUNCTION app_learning.content_complaint_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'TRUNCATE' THEN
        RAISE EXCEPTION 'Complaints are never truncated' USING ERRCODE = '23000';
    END IF;
    IF TG_OP = 'DELETE' THEN
        IF OLD.retain_until >= statement_timestamp() THEN
            RAISE EXCEPTION 'A complaint is kept until its retention ends' USING ERRCODE = '23000';
        END IF;
        RETURN OLD;
    END IF;
    IF NEW.complaint_id <> OLD.complaint_id OR NEW.target_kind <> OLD.target_kind OR NEW.deck_id IS DISTINCT FROM OLD.deck_id
       OR NEW.public_code IS DISTINCT FROM OLD.public_code OR NEW.profile_account_id IS DISTINCT FROM OLD.profile_account_id
       OR NEW.profile_username IS DISTINCT FROM OLD.profile_username OR NEW.reason <> OLD.reason
       OR NEW.reporter_text IS DISTINCT FROM OLD.reporter_text OR NEW.reporter_id <> OLD.reporter_id OR NEW.created_at <> OLD.created_at
       OR NEW.due_at <> OLD.due_at OR NEW.retain_until <> OLD.retain_until OR OLD.status <> 'OPEN' OR NEW.status <> 'DECIDED' THEN
        RAISE EXCEPTION 'Invalid complaint transition' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER content_complaint_guard BEFORE UPDATE OR DELETE ON app_learning.content_complaint
    FOR EACH ROW EXECUTE FUNCTION app_learning.content_complaint_guard();
CREATE TRIGGER content_complaint_no_truncate BEFORE TRUNCATE ON app_learning.content_complaint
    FOR EACH STATEMENT EXECUTE FUNCTION app_learning.content_complaint_guard();

CREATE TABLE app_learning.content_takedown (
    takedown_id UUID PRIMARY KEY DEFAULT uuidv7(),
    reuse_scope_id UUID NOT NULL,
    unit_kind TEXT NOT NULL CHECK (unit_kind IN ('ITEM', 'EXERCISE')),
    unit_key UUID NOT NULL,
    revision_id UUID NOT NULL,
    complaint_id UUID NOT NULL REFERENCES app_learning.content_complaint(complaint_id),
    created_at TIMESTAMPTZ NOT NULL CHECK (isfinite(created_at)),
    -- the lookup key of every reader: one unique-index probe per revision about to be served
    CONSTRAINT content_takedown_revision_key UNIQUE (reuse_scope_id, unit_kind, revision_id)
);
CREATE INDEX content_takedown_complaint ON app_learning.content_takedown (complaint_id, takedown_id);
-- the owner's notice and the picker find the takedowns of a lineage (the unique key's prefix), a copy's heads are probed from there

CREATE FUNCTION app_learning.content_takedown_immutable_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'A takedown is immutable' USING ERRCODE = '23000';
END;
$$;
CREATE TRIGGER content_takedown_immutable BEFORE UPDATE OR DELETE ON app_learning.content_takedown
    FOR EACH ROW EXECUTE FUNCTION app_learning.content_takedown_immutable_guard();
CREATE TRIGGER content_takedown_no_truncate BEFORE TRUNCATE ON app_learning.content_takedown
    FOR EACH STATEMENT EXECUTE FUNCTION app_learning.content_takedown_immutable_guard();

CREATE TABLE app_learning.content_takedown_journal (
    journal_id UUID PRIMARY KEY DEFAULT uuidv7(),
    complaint_id UUID NOT NULL,
    action TEXT NOT NULL CHECK (action IN ('REJECTED', 'HIDDEN', 'TAKEN_DOWN', 'ACCOUNT_REFERRED', 'UNHIDDEN')),
    actor_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL CHECK (isfinite(occurred_at)),
    target_kind TEXT NOT NULL CHECK (target_kind IN ('DECK', 'PROFILE')),
    deck_id UUID,
    public_code TEXT CHECK (public_code IS NULL OR public_code ~ '^[1-9A-HJ-NP-Za-km-z]{10}$'),
    profile_account_id UUID,
    unit_count INTEGER NOT NULL DEFAULT 0 CHECK (unit_count >= 0),
    retain_until TIMESTAMPTZ NOT NULL CHECK (isfinite(retain_until)),
    CONSTRAINT content_takedown_journal_units CHECK (unit_count = 0 OR action = 'TAKEN_DOWN')
);
CREATE FUNCTION app_learning.content_takedown_journal_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.retain_until < statement_timestamp() THEN
            RETURN OLD;
        END IF;
    END IF;
    RAISE EXCEPTION 'The takedown journal is append-only' USING ERRCODE = '23000';
END;
$$;
CREATE TRIGGER content_takedown_journal_guard BEFORE UPDATE OR DELETE ON app_learning.content_takedown_journal
    FOR EACH ROW EXECUTE FUNCTION app_learning.content_takedown_journal_guard();
CREATE TRIGGER content_takedown_journal_no_truncate BEFORE TRUNCATE ON app_learning.content_takedown_journal
    FOR EACH STATEMENT EXECUTE FUNCTION app_learning.content_takedown_journal_guard();

-- HIDDEN: the deck answers like an unknown code to everyone but its owner. hidden_at and the flag move together.
ALTER TABLE app_learning.deck_publication
    ADD COLUMN hidden_by_moderation BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN hidden_at TIMESTAMPTZ CHECK (hidden_at IS NULL OR isfinite(hidden_at)),
    ADD CONSTRAINT deck_publication_hidden CHECK (hidden_by_moderation = (hidden_at IS NOT NULL));

-- the console journal lists the decisions too
ALTER TABLE app_learning.admin_audit DROP CONSTRAINT admin_audit_action_check;
ALTER TABLE app_learning.admin_audit ADD CONSTRAINT admin_audit_action_check CHECK (action IN
    ('EVENT_CREATE','EVENT_REPLACE','EVENT_DELETE','PROMO_CREATE','PROMO_ENABLE','PROMO_DISABLE','COMPLAINT_DECIDE','COMPLAINT_UNHIDE'));
