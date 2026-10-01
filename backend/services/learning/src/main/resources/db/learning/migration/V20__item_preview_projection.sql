-- Rebuildable display text: immutable revision identity prevents stale previews after edits.
CREATE TABLE app_learning.item_preview (
    deck_id UUID NOT NULL,
    member_key UUID NOT NULL,
    revision_id UUID NOT NULL,
    title TEXT NOT NULL CHECK (char_length(title) <= 240),
    PRIMARY KEY (deck_id, member_key, revision_id),
    FOREIGN KEY (deck_id, member_key, revision_id)
        REFERENCES app_learning.item_revision(deck_id, member_key, revision_id) ON DELETE CASCADE
);
