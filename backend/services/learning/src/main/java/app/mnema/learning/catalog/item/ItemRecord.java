package app.mnema.learning.catalog.item;

import app.mnema.learning.catalog.content.NativeDocument;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.UUID;

record ItemRecord(UUID deckId, UUID memberKey, UUID revisionId, long itemSequence,
                  UUID publishedDeckRevisionId, long publishedDeckVersion, int ordinal,
                  UUID scopeId, UUID contentRootId, UUID descriptorRootId, Instant createdAt, Instant updatedAt) {
    ObjectNode summary() {
        return JsonNodeFactory.instance.objectNode().put("memberKey", memberKey.toString())
                .put("itemRevisionId", revisionId.toString()).put("itemVersion", Long.toString(itemSequence))
                .put("ordinal", ordinal).put("formatVersion", 1)
                .put("createdAt", createdAt.toString()).put("updatedAt", updatedAt.toString());
    }

    ObjectNode detail(UUID deckRevisionId, long deckVersion, NativeDocument document) {
        ObjectNode result = summary().put("deckId", deckId.toString())
                .put("deckRevisionId", deckRevisionId.toString()).put("deckVersion", Long.toString(deckVersion));
        result.set("document", document.toJson());
        return result;
    }
}
