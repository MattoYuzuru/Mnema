-- Deck-scoped active-note pages and counts must not scan another deck's captures.
CREATE INDEX capture_note_active_deck_page
    ON app_learning.capture_note(owner_id, deck_id, created_at DESC, note_id DESC)
    WHERE NOT archived AND conversion_command_id IS NULL;
