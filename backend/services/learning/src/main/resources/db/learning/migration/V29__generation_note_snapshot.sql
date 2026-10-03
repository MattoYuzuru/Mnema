-- The text of a pinned note at the pinned row_version (#290). Notes are mutable in place (archive, conversion) and keep no
-- history, so the text a material is written from is copied when the pin is made (session admission, or a re-pin on retry):
-- a later edit never reaches a step that has not started, and the Workshop can tell an edited note from an archived one by
-- comparing the snapshot with the note as it is now. Rows go with the session (cascade) and are append-only.
CREATE TABLE app_learning.generation_note_snapshot (
    session_id UUID NOT NULL,
    owner_id UUID NOT NULL,
    note_id UUID NOT NULL,
    note_row_version BIGINT NOT NULL CHECK (note_row_version >= 0),
    note_text TEXT NOT NULL CHECK (octet_length(note_text) BETWEEN 1 AND 32768),
    PRIMARY KEY (session_id, note_id, note_row_version),
    FOREIGN KEY (session_id, owner_id) REFERENCES app_learning.generation_session(session_id, owner_id) ON DELETE CASCADE
);
CREATE TRIGGER generation_note_snapshot_guard BEFORE UPDATE ON app_learning.generation_note_snapshot
    FOR EACH ROW EXECUTE FUNCTION app_learning.generation_immutable_guard();

-- Sessions created before this migration: the pin holds only while the note still is at that version.
INSERT INTO app_learning.generation_note_snapshot(session_id, owner_id, note_id, note_row_version, note_text)
SELECT DISTINCT s.session_id, s.owner_id, s.note_id, s.note_row_version, n.note_text
FROM app_learning.generation_session_source s
JOIN app_learning.capture_note n ON n.note_id = s.note_id AND n.owner_id = s.owner_id AND n.row_version = s.note_row_version
WHERE s.type = 'NOTE'
ON CONFLICT DO NOTHING;
