package app.mnema.learning.catalog.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.UUID;

record DraftRecord(UUID draftId, UUID ownerId, UUID deckId, UUID memberKey, UUID baseRevisionId,
                   long rowVersion, JsonNode document, int contentBytes, Instant createdAt,
                   Instant acknowledgedAt, Instant expiresAt) {
    DraftRecord { document = document.deepCopy(); }
    @Override public JsonNode document() { return document.deepCopy(); }

    ObjectNode summary() {
        ObjectNode value = JsonNodeFactory.instance.objectNode().put("draftId", draftId.toString())
                .put("deckId", deckId.toString()).put("rowVersion", Long.toString(rowVersion))
                .put("contentBytes", contentBytes).put("createdAt", createdAt.toString())
                .put("acknowledgedAt", acknowledgedAt.toString()).put("expiresAt", expiresAt.toString());
        nullable(value, "memberKey", memberKey);
        nullable(value, "baseRevisionId", baseRevisionId);
        return value;
    }

    ObjectNode detail() {
        ObjectNode value = summary();
        value.set("document", document());
        return value;
    }

    private static void nullable(ObjectNode value, String name, UUID id) {
        if (id == null) value.putNull(name); else value.put(name, id.toString());
    }
}
