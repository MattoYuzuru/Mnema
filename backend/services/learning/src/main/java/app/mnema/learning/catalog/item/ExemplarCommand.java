package app.mnema.learning.catalog.item;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.concurrency.VersionPreconditionRequiredException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.io.InputStream;
import java.util.Set;
import java.util.UUID;

/**
 * Strict {@code POST /items/{memberKey}/exemplar} body. {@code exemplar} is the desired value, not a toggle, so a retry
 * or a double click is harmless; {@code expectedItemRevisionId} pins the content the user saw when starring.
 */
record ExemplarCommand(UUID commandId, UUID expectedItemRevisionId, boolean exemplar) {
    static ExemplarCommand read(InputStream input) {
        JsonNode body = BulkSelection.parse(input);
        if (!body.has("expectedItemRevisionId")) throw new VersionPreconditionRequiredException();
        BulkSelection.fields(body, Set.of("commandId", "expectedItemRevisionId", "exemplar"));
        if (!body.path("exemplar").isBoolean()) throw new InvalidRequestException();
        return new ExemplarCommand(BulkSelection.requireCommandId(body), BulkSelection.id(body.path("expectedItemRevisionId")),
                body.path("exemplar").booleanValue());
    }

    /** Receipt envelope: bound to the Deck, the member, the desired value and the expected revision. */
    ObjectNode envelope(UUID deckId, UUID memberKey) {
        return JsonNodeFactory.instance.objectNode().put("deckId", deckId.toString()).put("memberKey", memberKey.toString())
                .put("commandId", commandId.toString()).put("expectedItemRevisionId", expectedItemRevisionId.toString())
                .put("exemplar", exemplar);
    }
}
