package app.mnema.learning.catalog.item;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.io.InputStream;
import java.util.Set;
import java.util.UUID;

/** Strict {@code POST /items/deletions} body: {@code commandId} plus one {@link BulkSelection}. */
record BulkDeleteCommand(UUID commandId, BulkSelection selection) {
    static BulkDeleteCommand read(InputStream input) {
        JsonNode body = BulkSelection.parse(input);
        BulkSelection.fields(body, Set.of("commandId", "expectedDeckRevisionId", "itemIds", "allInDeck", "except"));
        BulkSelection selection = BulkSelection.read(body);
        return new BulkDeleteCommand(BulkSelection.requireCommandId(body), selection);
    }

    /** Receipt envelope: bound to the Deck, the If-Match version and the normalized selection. */
    ObjectNode envelope(UUID deckId, long expectedDeckVersion) {
        ObjectNode result = JsonNodeFactory.instance.objectNode().put("deckId", deckId.toString())
                .put("expectedDeckVersion", Long.toString(expectedDeckVersion));
        ObjectNode command = selection.normalized().put("commandId", commandId.toString());
        result.set("command", command);
        return result;
    }
}
