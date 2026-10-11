package app.mnema.learning.catalog.deck;

import app.mnema.learning.fork.ForkLink;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.UUID;

/**
 * Internal query result; physical locators never appear in its public projection. {@code visibility} is the lowercase level of the deck's publication row
 * ({@code private} for a deck without one): the owner's JSON reports the real level, which only the publication command (Share/8) changes.
 * {@code fork} is the copy link of a deck made by «Добавить к себе» (Share/10), {@code null} for an ordinary deck; the owner's reads add it to the JSON
 * ({@code ForkReadModel}), acknowledgements do not carry it.
 */
record DeckRecord(UUID deckId, UUID revisionId, UUID scopeId, long rowVersion,
                  String title, String description, Instant createdAt, Instant updatedAt,
                  UUID membersRootId, UUID exercisesRootId, int memberCount, int exerciseCount, String visibility, ForkLink fork) {
    /** An ordinary deck: no copy link. */
    DeckRecord(UUID deckId, UUID revisionId, UUID scopeId, long rowVersion, String title, String description, Instant createdAt, Instant updatedAt,
               UUID membersRootId, UUID exercisesRootId, int memberCount, int exerciseCount, String visibility) {
        this(deckId, revisionId, scopeId, rowVersion, title, description, createdAt, updatedAt, membersRootId, exercisesRootId, memberCount, exerciseCount,
                visibility, null);
    }

    ObjectNode toJson() {
        ObjectNode result = JsonNodeFactory.instance.objectNode()
                .put("deckId", deckId.toString()).put("revisionId", revisionId.toString())
                .put("rowVersion", Long.toString(rowVersion)).put("sequence", Long.toString(rowVersion))
                .put("visibility", visibility).put("createdAt", createdAt.toString()).put("updatedAt", updatedAt.toString())
                .put("memberCount", memberCount).put("exerciseCount", exerciseCount);
        result.set("metadata", JsonNodeFactory.instance.objectNode().put("title", title).put("description", description));
        return result;
    }
}
