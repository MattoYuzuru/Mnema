-- #285 deck hub: the deck-local «Эталон» flag, a member index for per-material exercise counts and an open-capture index.
--
-- The flag is a user preference on a LearningItem, not content: it lives outside learning_item (immutable identity)
-- and deck_head_item (a projection rebuildable from immutable roots), creates no Deck revision and is dropped by the
-- delete publication in the same transaction. Reads join deck_head_item, so a row left behind by a concurrent delete
-- is never visible and never counted. The ≤10 per Deck limit is enforced by the service under an advisory lock.
CREATE TABLE app_learning.deck_item_exemplar (
    deck_id UUID NOT NULL,
    member_key UUID NOT NULL,
    marked_at TIMESTAMPTZ NOT NULL CHECK (isfinite(marked_at)),
    PRIMARY KEY (deck_id, member_key),
    FOREIGN KEY (deck_id, member_key) REFERENCES app_learning.learning_item(deck_id, member_key)
);

-- Per-material exercise counts, the deletion preview and the consequence text look exercises up by assessed member.
CREATE INDEX exercise_binding_assessed_member
    ON app_learning.exercise_content_binding(deck_id, member_key, exercise_id, exercise_revision_id)
    WHERE role = 'ASSESSED';

-- Insights count a Deck's open notes (neither archived nor converted) and read the oldest one.
CREATE INDEX capture_note_deck_open
    ON app_learning.capture_note(owner_id, deck_id, created_at)
    WHERE NOT archived AND conversion_command_id IS NULL;
