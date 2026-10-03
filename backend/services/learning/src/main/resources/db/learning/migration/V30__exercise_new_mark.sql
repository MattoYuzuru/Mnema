-- #291 AI-13 «Новое»: a server-side mark on an exercise that a generation approval just published, so that lists and Study can
-- say "new" until the owner opens it, answers it, or the mark expires (learning.exercise.new-mark-ttl, P7D).
--
-- The mark is a user-facing hint, not content: it lives outside exercise_definition and the exercise revisions (immutable),
-- creates no Deck revision and is deleted when the owner opens the exercise (DELETE .../new-mark) or answers it in Study (the
-- same transaction as the attempt). "New" is the row existing and marked_at inside the TTL, so an expired row that the
-- retention worker has not purged yet is never shown. Owner-scoped like every deck-local row.
CREATE TABLE app_learning.exercise_new_mark (
    deck_id UUID NOT NULL,
    exercise_id UUID NOT NULL,
    owner_id UUID NOT NULL,
    marked_at TIMESTAMPTZ NOT NULL CHECK (isfinite(marked_at)),
    PRIMARY KEY (deck_id, exercise_id),
    FOREIGN KEY (deck_id, exercise_id) REFERENCES app_learning.exercise_definition(deck_id, exercise_id),
    FOREIGN KEY (deck_id, owner_id) REFERENCES app_learning.deck(deck_id, owner_id)
);
-- The retention worker purges by age.
CREATE INDEX exercise_new_mark_age ON app_learning.exercise_new_mark(marked_at);

-- A presentation records whether its exercise was new when it was issued; a REPLAY copy is never new. The column is added
-- with a default: existing rows are not rewritten, so the immutability trigger of the snapshot never fires.
ALTER TABLE app_learning.study_presentation ADD COLUMN is_new BOOLEAN NOT NULL DEFAULT FALSE;

-- A proposal the owner changed in the exercise editor before saving ("Изменить"): provenance records it (audit and economics).
ALTER TABLE app_learning.generation_provenance ADD COLUMN edited BOOLEAN NOT NULL DEFAULT FALSE;
