package app.mnema.learning.catalog.deck;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.UUID;

/** Internal query result; physical locators never appear in its public projection. */
record DeckRecord(UUID deckId, UUID revisionId, UUID scopeId, long rowVersion,
                  String title, String description, Instant createdAt, Instant updatedAt,
                  UUID membersRootId, UUID exercisesRootId, int memberCount, int exerciseCount) {
    ObjectNode toJson() {
        ObjectNode result = JsonNodeFactory.instance.objectNode()
                .put("deckId", deckId.toString()).put("revisionId", revisionId.toString())
                .put("rowVersion", Long.toString(rowVersion)).put("sequence", Long.toString(rowVersion))
                .put("visibility", "private").put("createdAt", createdAt.toString()).put("updatedAt", updatedAt.toString())
                .put("memberCount", memberCount).put("exerciseCount", exerciseCount);
        result.set("metadata", JsonNodeFactory.instance.objectNode().put("title", title).put("description", description));
        return result;
    }
}
