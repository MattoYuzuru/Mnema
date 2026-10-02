package app.mnema.learning.catalog.item;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.concurrency.VersionPreconditionRequiredException;
import app.mnema.learning.platform.id.UuidPolicy;
import app.mnema.learning.platform.json.ContentJsonReader;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The materials a bulk action targets, named against one exact Deck revision: either explicit {@code itemIds} (1..100)
 * or {@code allInDeck} minus {@code except} (up to 500). The selection is resolved against
 * {@code expectedDeckRevisionId}, never against whatever the head is when the request arrives, so an exact retry after
 * a crash resolves to the same materials.
 */
record BulkSelection(UUID expectedDeckRevisionId, List<UUID> itemIds, boolean allInDeck, List<UUID> except) {
    static final int MAX_EXPLICIT = 100;
    static final int MAX_EXCEPT = 500;
    /** Largest resolved selection one request may process (five chunks of {@link ItemBulkDeleteService#CHUNK}). */
    static final long MAX_SELECTION = 500;
    private static final int MAX_REQUEST_BYTES = 65_536;
    private static final ContentJsonReader JSON = new ContentJsonReader(MAX_REQUEST_BYTES, 8, 4_096);

    BulkSelection {
        itemIds = itemIds == null ? null : List.copyOf(itemIds);
        except = List.copyOf(except);
    }

    /** Reads a selection body; {@code commandId} is read separately by {@link BulkDeleteCommand}. */
    static BulkSelection read(JsonNode body) {
        if (!body.has("expectedDeckRevisionId")) throw new VersionPreconditionRequiredException();
        UUID revision = id(body.path("expectedDeckRevisionId"));
        boolean explicit = body.has("itemIds");
        boolean all = body.has("allInDeck");
        if (explicit == all) throw new InvalidRequestException();
        if (explicit) {
            if (body.has("except")) throw new InvalidRequestException();
            List<UUID> ids = ids(body.path("itemIds"), 1, MAX_EXPLICIT);
            return new BulkSelection(revision, ids, false, List.of());
        }
        if (!body.path("allInDeck").isBoolean() || !body.path("allInDeck").booleanValue()) throw new InvalidRequestException();
        List<UUID> except = body.has("except") ? ids(body.path("except"), 0, MAX_EXCEPT) : List.of();
        return new BulkSelection(revision, null, true, except);
    }

    static JsonNode parse(InputStream input) {
        try {
            JsonNode body = JSON.read(input.readNBytes(MAX_REQUEST_BYTES + 1));
            if (!body.isObject()) throw new InvalidRequestException();
            return body;
        } catch (IOException | IllegalArgumentException exception) {
            throw new InvalidRequestException();
        }
    }

    /** Rejects unknown fields so a typo is not a silently different selection. */
    static void fields(JsonNode body, Set<String> allowed) {
        body.properties().forEach(entry -> {
            if (!allowed.contains(entry.getKey())) throw new InvalidRequestException();
        });
    }

    static UUID id(JsonNode value) {
        if (!value.isString()) throw new InvalidRequestException();
        return ItemIds.entity(value.stringValue(null));
    }

    private static List<UUID> ids(JsonNode value, int min, int max) {
        if (!value.isArray() || value.size() < min || value.size() > max) throw new InvalidRequestException();
        List<UUID> result = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (JsonNode element : value) {
            UUID id = id(element);
            if (!seen.add(id)) throw new InvalidRequestException();
            result.add(id);
        }
        return result;
    }

    /** Normalized selection for receipts: identifier order does not change the command. */
    ObjectNode normalized() {
        ObjectNode result = JsonNodeFactory.instance.objectNode().put("expectedDeckRevisionId", expectedDeckRevisionId.toString());
        if (allInDeck) {
            result.put("allInDeck", true);
            var except = result.putArray("except");
            this.except.stream().map(UUID::toString).sorted().forEach(except::add);
        } else {
            var ids = result.putArray("itemIds");
            itemIds.stream().map(UUID::toString).sorted().forEach(ids::add);
        }
        return result;
    }

    static UUID requireCommandId(JsonNode body) {
        try {
            return UuidPolicy.requireCommandId(id(body.path("commandId")));
        } catch (IllegalArgumentException exception) {
            throw new InvalidRequestException();
        }
    }
}
