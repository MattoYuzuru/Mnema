package app.mnema.learning.catalog.deck;

import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.UUID;

/**
 * Internal query result; physical locators never appear in its public projection. {@code visibility} is the lowercase level of the deck's publication row
 * ({@code private} for a deck without one): the owner's JSON reports the real level, which only the publication command (Share/8) changes.
 */
record DeckRecord(UUID deckId, UUID revisionId, UUID scopeId, long rowVersion,
                  String title, String description, Instant createdAt, Instant updatedAt,
                  UUID membersRootId, UUID exercisesRootId, int memberCount, int exerciseCount, String visibility) {
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
