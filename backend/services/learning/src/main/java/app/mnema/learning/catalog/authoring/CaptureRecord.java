package app.mnema.learning.catalog.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.UUID;

record CaptureRecord(UUID noteId, UUID ownerId, UUID deckId, long rowVersion, String source, String text,
                     int contentBytes, boolean archived, Instant createdAt, Instant updatedAt,
                     UUID conversionCommandId, byte[] conversionHash, UUID convertedMemberKey,
                     UUID convertedRevisionId, Instant convertedAt, JsonNode conversionResult) {
    CaptureRecord {
        conversionHash = conversionHash == null ? null : conversionHash.clone();
        conversionResult = conversionResult == null ? null : conversionResult.deepCopy();
    }
    @Override public byte[] conversionHash() { return conversionHash == null ? null : conversionHash.clone(); }
    @Override public JsonNode conversionResult() {
        return conversionResult == null ? null : conversionResult.deepCopy();
    }

    ObjectNode summary() {
        ObjectNode value = JsonNodeFactory.instance.objectNode().put("noteId", noteId.toString())
                .put("deckId", deckId.toString()).put("rowVersion", Long.toString(rowVersion))
                .put("source", source).put("text", text).put("contentBytes", contentBytes)
                .put("archived", archived).put("createdAt", createdAt.toString()).put("updatedAt", updatedAt.toString());
        if (conversionCommandId == null) value.putNull("conversion");
        else value.set("conversion", JsonNodeFactory.instance.objectNode()
                .put("commandId", conversionCommandId.toString())
                .put("memberKey", convertedMemberKey.toString())
                .put("itemRevisionId", convertedRevisionId.toString())
                .put("convertedAt", convertedAt.toString()));
        return value;
    }
}
