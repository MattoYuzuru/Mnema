-- #294 AI-16 «Попросить Мнему…»: intent calls per account (the hourly rate limit) and a media slot that may point at an asset
-- the owner already has.
--
-- 1. generation_intent_use: one row per intent call (POST /decks/{deckId}/generation-intents), counted over the last hour by
--    learning.generation.intent.per-hour. State lives in the database because every service instance is stateless. Rows carry no
--    text and no deck: only the account and the time. The retention worker deletes rows older than a day.
CREATE TABLE app_learning.generation_intent_use (
    owner_id UUID NOT NULL,
    used_at TIMESTAMPTZ NOT NULL CHECK (isfinite(used_at))
);
CREATE INDEX generation_intent_use_owner ON app_learning.generation_intent_use(owner_id, used_at DESC);
CREATE INDEX generation_intent_use_age ON app_learning.generation_intent_use(used_at);

-- 2. A REVISE_EXERCISE session copies the exercise it revises, and the slot of one of its audio blocks names the asset that block
--    already uses (spec mode "existing": the Stub speech executor keeps it; real synthesis, #297, replaces it with a new one and must then
--    write the generation_media_ref hold for it). Two sessions may revise the same exercise, so an "existing" slot may share its asset with
--    another slot; every other slot (a material's media, a real synthesis) still has a pre-allocated asset of its own, unique as before.
DO $$
DECLARE
    found record;
BEGIN
    FOR found IN
        SELECT conname
        FROM pg_constraint
        WHERE contype = 'u'
          AND conrelid = 'app_learning.generation_media_slot'::regclass
          AND pg_get_constraintdef(oid) = 'UNIQUE (asset_id)'
    LOOP
        EXECUTE format('ALTER TABLE app_learning.generation_media_slot DROP CONSTRAINT %I', found.conname);
    END LOOP;
END
$$;
CREATE UNIQUE INDEX generation_media_slot_asset ON app_learning.generation_media_slot(asset_id)
    WHERE spec ->> 'mode' IS DISTINCT FROM 'existing';
CREATE INDEX generation_media_slot_existing_asset ON app_learning.generation_media_slot(asset_id)
    WHERE spec ->> 'mode' = 'existing';

-- 3. The voice of a turn that redoes the audio of an exercise (female or male); null for every other turn.
ALTER TABLE app_learning.generation_artifact_turn
    ADD COLUMN voice TEXT CHECK (voice IS NULL OR voice IN ('female', 'male'));
