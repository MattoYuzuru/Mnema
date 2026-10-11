package app.mnema.learning.update;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.UUID;

/**
 * What a combined publication did, as stored in its receipt (so a replay answers exactly this).
 *
 * @param deckRevisionId the new revision, or the unchanged head when {@code changed} is false
 * @param deckVersion the deck version of {@code deckRevisionId}
 * @param changed whether a deck revision was created (a command whose operations are all no-ops creates none)
 * @param itemsChanged item heads that moved, were inserted, removed or reordered
 * @param exercisesChanged exercise heads that moved, were inserted or removed
 * @param objectivesChanged objective heads that moved or were inserted
 * @param metadataChanged whether the title or description changed
 */
public record CombinedResult(UUID commandId, UUID deckId, UUID deckRevisionId, long deckVersion, boolean changed, int itemsChanged,
                             int exercisesChanged, int objectivesChanged, boolean metadataChanged) {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    ObjectNode toJson() {
        return JSON.createObjectNode().put("commandId", commandId.toString()).put("deckId", deckId.toString())
                .put("deckRevisionId", deckRevisionId.toString()).put("deckVersion", Long.toString(deckVersion)).put("changed", changed)
                .put("itemsChanged", itemsChanged).put("exercisesChanged", exercisesChanged).put("objectivesChanged", objectivesChanged)
                .put("metadataChanged", metadataChanged);
    }

    static CombinedResult fromJson(JsonNode node) {
        return new CombinedResult(UUID.fromString(node.path("commandId").stringValue(null)), UUID.fromString(node.path("deckId").stringValue(null)),
                UUID.fromString(node.path("deckRevisionId").stringValue(null)), Long.parseLong(node.path("deckVersion").stringValue(null)),
                node.path("changed").booleanValue(false), node.path("itemsChanged").intValue(0), node.path("exercisesChanged").intValue(0),
                node.path("objectivesChanged").intValue(0), node.path("metadataChanged").booleanValue(false));
    }
}
