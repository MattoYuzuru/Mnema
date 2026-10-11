package app.mnema.learning.library;

import java.time.Instant;
import java.util.UUID;

/** The publication row of a deck (the owner's view): its level, its code and the version of the row. */
public record Publication(UUID deckId, DeckVisibility visibility, String publicCode, Instant codeRotatedAt,
                          UUID publishedRevisionId, Instant publishedAt, long rowVersion, Instant createdAt, Instant updatedAt) { }
