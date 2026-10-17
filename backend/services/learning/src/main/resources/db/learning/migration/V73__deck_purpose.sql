-- Onboarding sample deck (OB-201, #527; docs/engineering/onboarding/adr/adr-onb-003-sample-deck.md).
--
-- deck.purpose: what a deck is FOR. USER is every deck that exists today. SAMPLE_TEMPLATE is the editorial deck named by learning.onboarding.sample-deck.template-id
-- (marked at startup by SampleTemplateMarker, the one USER -> SAMPLE_TEMPLATE transition). SAMPLE is the account's «Моя первая колода», a copy of the template made by
-- POST /api/onboarding/sample-deck (inserted as SAMPLE by the copy, never updated into it). A SAMPLE or SAMPLE_TEMPLATE deck is never published, shared or copied by a user
-- (409 SAMPLE_DECK_NOT_SHAREABLE), writes no engagement, catalogue event or interest signal, and its AI answer checks are free for the account up to
-- learning.onboarding.sample-deck.free-ai-checks.
--
-- Cost. ADD COLUMN with a constant DEFAULT is metadata only on PostgreSQL 11+ (no rewrite); the CHECK and the unique index each read the table once (a sequential scan of
-- narrow rows) while the migration holds the table lock, which is why lock_timeout is set: the migration fails fast instead of queueing behind a long transaction.
SET LOCAL lock_timeout = '5s';
ALTER TABLE app_learning.deck ADD COLUMN purpose TEXT NOT NULL DEFAULT 'USER' CHECK (purpose IN ('USER', 'SAMPLE_TEMPLATE', 'SAMPLE'));

-- One live sample deck per account: the net under SampleDeckService (which serializes an account's requests with an advisory lock) and under any future path.
-- After the owner deletes the copy (deleted_at set) a new one may be made.
CREATE UNIQUE INDEX deck_one_live_sample ON app_learning.deck(owner_id) WHERE purpose = 'SAMPLE' AND deleted_at IS NULL;

-- The head guard (V3, V17) refuses every change of a deck row except the head, the version and the tombstone, so purpose is immutable, with ONE exception: the
-- USER -> SAMPLE_TEMPLATE mark of a live deck whose other columns do not change. SAMPLE is never the result of an update.
CREATE OR REPLACE FUNCTION app_learning.deck_head_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.deleted_at IS NULL AND NEW.deleted_at IS NOT NULL
       AND (to_jsonb(OLD) - 'deleted_at') IS NOT DISTINCT FROM (to_jsonb(NEW) - 'deleted_at') THEN
        RETURN NEW;
    END IF;
    IF OLD.deleted_at IS NULL AND NEW.deleted_at IS NULL AND OLD.purpose = 'USER' AND NEW.purpose = 'SAMPLE_TEMPLATE'
       AND (to_jsonb(OLD) - 'purpose') IS NOT DISTINCT FROM (to_jsonb(NEW) - 'purpose') THEN
        RETURN NEW;
    END IF;
    IF OLD.deleted_at IS NOT NULL
       OR (to_jsonb(OLD) - ARRAY['head_revision_id', 'row_version'])
          IS DISTINCT FROM (to_jsonb(NEW) - ARRAY['head_revision_id', 'row_version'])
       OR OLD.row_version = 9223372036854775807 OR NEW.row_version <> OLD.row_version + 1 THEN
        RAISE EXCEPTION 'Invalid deck head transition' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

-- The free AI checks of an account (usage ledger rows with operation ONBOARDING_SAMPLE_CHECK, credits 0, no bucket) are counted by this index alone: at most
-- learning.onboarding.sample-deck.free-ai-checks (10) entries per account, whatever else the account's ledger holds.
CREATE INDEX usage_ledger_sample_check ON app_learning.usage_ledger_entry(owner_id) WHERE operation = 'ONBOARDING_SAMPLE_CHECK';
