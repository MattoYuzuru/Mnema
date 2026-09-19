package app.mnema.learning.catalog.item;

import app.mnema.learning.catalog.content.NativeDocument;
import app.mnema.learning.catalog.content.NativeDocumentReader;
import app.mnema.learning.catalog.content.storage.NativeStructuralEdit;
import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.concurrency.VersionPreconditionRequiredException;
import app.mnema.learning.platform.id.UuidPolicy;
import app.mnema.learning.platform.json.CanonicalJsonHasher;
import app.mnema.learning.platform.json.ContentJsonReader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Strict, one-MiB publication command shared by single-item and bounded bulk routes. */
public final class ItemPublicationCommand {
    public static final int MAX_REQUEST_BYTES = 1_048_576;
    public static final int MAX_CHANGES = 100;
    private static final ContentJsonReader JSON = new ContentJsonReader(MAX_REQUEST_BYTES, 132, 250_500);
    private static final CanonicalJsonHasher CANONICAL = new CanonicalJsonHasher();

    private final UUID commandId;
    private final UUID expectedDeckRevisionId;
    private final List<Change> changes;
    private final ObjectNode payload;

    private ItemPublicationCommand(UUID commandId, UUID expectedDeckRevisionId, List<Change> changes, ObjectNode payload) {
        this.commandId = UuidPolicy.requireCommandId(commandId);
        this.expectedDeckRevisionId = UuidPolicy.requireEntityId(expectedDeckRevisionId, "expectedDeckRevisionId");
        this.changes = List.copyOf(changes);
        if (changes.isEmpty() || changes.size() > MAX_CHANGES) throw new InvalidRequestException();
        this.payload = payload.deepCopy();
    }

    public UUID commandId() { return commandId; }
    public UUID expectedDeckRevisionId() { return expectedDeckRevisionId; }
    public List<Change> changes() { return changes; }
    public ObjectNode payload() { return payload.deepCopy(); }

    public ObjectNode envelope(UUID deckId, long expectedDeckVersion) {
        ObjectNode result = JsonNodeFactory.instance.objectNode()
                .put("deckId", deckId.toString()).put("expectedDeckVersion", Long.toString(expectedDeckVersion));
        result.set("command", payload());
        return result;
    }

    public static ItemPublicationCommand readBulk(InputStream input) {
        JsonNode body = read(input);
        requirePrecondition(body, "expectedDeckRevisionId");
        fields(body, Set.of("commandId", "expectedDeckRevisionId", "changes"));
        if (!body.path("changes").isArray() || body.path("changes").isEmpty()
                || body.path("changes").size() > MAX_CHANGES) throw new InvalidRequestException();
        List<Change> changes = new ArrayList<>();
        body.path("changes").forEach(value -> changes.add(change(value, true, null)));
        uniqueMembers(changes);
        return command(body, changes);
    }

    public static ItemPublicationCommand readCreate(InputStream input) {
        JsonNode body = read(input);
        requirePrecondition(body, "expectedDeckRevisionId");
        fields(body, Set.of("commandId", "expectedDeckRevisionId", "document"),
                Set.of("commandId", "expectedDeckRevisionId", "document", "ordinal"));
        Change value = new Create(null, ordinal(body.path("ordinal")), document(body.path("document")));
        return command(body, List.of(value));
    }

    public static ItemPublicationCommand readSave(InputStream input, UUID memberKey) {
        JsonNode body = read(input);
        requirePrecondition(body, "expectedDeckRevisionId");
        requirePrecondition(body, "expectedItemRevisionId");
        requirePrecondition(body, "expectedOrdinal");
        fields(body, Set.of("commandId", "expectedDeckRevisionId", "expectedItemRevisionId", "expectedOrdinal", "document"),
                Set.of("commandId", "expectedDeckRevisionId", "expectedItemRevisionId", "expectedOrdinal", "document", "ordinal"),
                Set.of("commandId", "expectedDeckRevisionId", "expectedItemRevisionId", "expectedOrdinal", "document", "edit"),
                Set.of("commandId", "expectedDeckRevisionId", "expectedItemRevisionId", "expectedOrdinal", "document", "ordinal", "edit"));
        Change value = new Save(memberKey, id(body, "expectedItemRevisionId"), requiredOrdinal(body, "expectedOrdinal"), ordinal(body.path("ordinal")),
                document(body.path("document")), edit(body.path("edit")));
        return command(body, List.of(value));
    }

    static ItemPublicationCommand create(UUID commandId, UUID expectedDeckRevisionId,
                                         Integer ordinal, NativeDocument document) {
        ObjectNode body = JsonNodeFactory.instance.objectNode().put("commandId", commandId.toString())
                .put("expectedDeckRevisionId", expectedDeckRevisionId.toString());
        if (ordinal != null) body.put("ordinal", ordinal);
        body.set("document", document.toJson());
        return new ItemPublicationCommand(commandId, expectedDeckRevisionId,
                List.of(new Create(null, ordinal, document)), body);
    }

    private static ItemPublicationCommand command(JsonNode body, List<Change> changes) {
        try {
            return new ItemPublicationCommand(id(body, "commandId"), id(body, "expectedDeckRevisionId"), changes,
                    (ObjectNode) body);
        } catch (IllegalArgumentException exception) {
            throw new InvalidRequestException();
        }
    }

    private static Change change(JsonNode value, boolean requireMember, UUID pathMember) {
        if (!value.isObject() || !value.path("operation").isTextual()) throw new InvalidRequestException();
        UUID member = pathMember != null ? pathMember : requireMember ? id(value, "memberKey") : null;
        return switch (value.path("operation").textValue()) {
            case "create" -> {
                fields(value, Set.of("operation", "memberKey", "document"),
                        Set.of("operation", "memberKey", "document", "ordinal"));
                yield new Create(member, ordinal(value.path("ordinal")), document(value.path("document")));
            }
            case "save" -> {
                requirePrecondition(value, "expectedItemRevisionId");
                requirePrecondition(value, "expectedOrdinal");
                fields(value, Set.of("operation", "memberKey", "expectedItemRevisionId", "expectedOrdinal", "document"),
                        Set.of("operation", "memberKey", "expectedItemRevisionId", "expectedOrdinal", "document", "ordinal"),
                        Set.of("operation", "memberKey", "expectedItemRevisionId", "expectedOrdinal", "document", "edit"),
                        Set.of("operation", "memberKey", "expectedItemRevisionId", "expectedOrdinal", "document", "ordinal", "edit"));
                yield new Save(member, id(value, "expectedItemRevisionId"), requiredOrdinal(value, "expectedOrdinal"), ordinal(value.path("ordinal")),
                        document(value.path("document")), edit(value.path("edit")));
            }
            case "delete" -> {
                requirePrecondition(value, "expectedItemRevisionId");
                requirePrecondition(value, "expectedOrdinal");
                fields(value, Set.of("operation", "memberKey", "expectedItemRevisionId", "expectedOrdinal"));
                yield new Delete(member, id(value, "expectedItemRevisionId"), requiredOrdinal(value, "expectedOrdinal"));
            }
            case "reorder" -> {
                requirePrecondition(value, "expectedItemRevisionId");
                requirePrecondition(value, "expectedOrdinal");
                fields(value, Set.of("operation", "memberKey", "expectedItemRevisionId", "expectedOrdinal", "ordinal"));
                Integer position = ordinal(value.path("ordinal"));
                yield new Reorder(member, id(value, "expectedItemRevisionId"), requiredOrdinal(value, "expectedOrdinal"), position);
            }
            default -> throw new InvalidRequestException();
        };
    }

    private static JsonNode read(InputStream input) {
        try {
            return JSON.read(input.readNBytes(MAX_REQUEST_BYTES + 1));
        } catch (IOException | IllegalArgumentException exception) {
            throw new InvalidRequestException();
        }
    }

    private static NativeDocument document(JsonNode value) {
        if (!value.isObject()) throw new InvalidRequestException();
        try {
            return new NativeDocumentReader().read(CANONICAL.canonicalBytes(value));
        } catch (IllegalArgumentException exception) {
            throw new InvalidRequestException();
        }
    }

    private static NativeStructuralEdit edit(JsonNode value) {
        if (value.isMissingNode()) return null;
        if (!value.isObject() || !value.path("type").isTextual()) throw new InvalidRequestException();
        try {
            return switch (value.path("type").textValue()) {
                case "insert" -> {
                    fields(value, Set.of("type", "parentId", "childIndex"));
                    yield new NativeStructuralEdit.Insert(id(value, "parentId"), integer(value.path("childIndex")));
                }
                case "delete" -> {
                    fields(value, Set.of("type", "nodeId"));
                    yield new NativeStructuralEdit.Delete(id(value, "nodeId"));
                }
                case "move" -> {
                    fields(value, Set.of("type", "nodeId", "parentId", "childIndex"));
                    yield new NativeStructuralEdit.Move(id(value, "nodeId"), id(value, "parentId"),
                            integer(value.path("childIndex")));
                }
                default -> throw new InvalidRequestException();
            };
        } catch (IllegalArgumentException exception) {
            throw new InvalidRequestException();
        }
    }

    private static Integer ordinal(JsonNode value) {
        if (value.isMissingNode()) return null;
        int result = integer(value);
        if (result >= 100_000) throw new InvalidRequestException();
        return result;
    }

    private static int requiredOrdinal(JsonNode object, String name) {
        Integer value = ordinal(object.path(name));
        if (value == null) throw new InvalidRequestException();
        return value;
    }

    private static int integer(JsonNode value) {
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) {
            throw new InvalidRequestException();
        }
        return value.intValue();
    }

    private static UUID id(JsonNode object, String name) {
        JsonNode value = object.path(name);
        if (!value.isTextual() || value.textValue().length() != 36) throw new InvalidRequestException();
        try {
            UUID id = UuidPolicy.requireEntityId(UUID.fromString(value.textValue()), name);
            if (!id.toString().equalsIgnoreCase(value.textValue())) throw new InvalidRequestException();
            return id;
        } catch (IllegalArgumentException exception) {
            throw new InvalidRequestException();
        }
    }

    private static void requirePrecondition(JsonNode object, String name) {
        if (!object.has(name)) throw new VersionPreconditionRequiredException();
    }

    @SafeVarargs
    private static void fields(JsonNode node, Set<String>... alternatives) {
        if (!node.isObject()) throw new InvalidRequestException();
        Set<String> actual = node.properties().stream().map(java.util.Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        for (Set<String> candidate : alternatives) if (actual.equals(candidate)) return;
        throw new InvalidRequestException();
    }

    private static void uniqueMembers(List<Change> changes) {
        long count = changes.stream().map(Change::memberKey).distinct().count();
        if (count != changes.size()) throw new InvalidRequestException();
    }

    public sealed interface Change permits Create, Save, Delete, Reorder {
        UUID memberKey();
        String operation();
    }

    public record Create(UUID memberKey, Integer ordinal, NativeDocument document) implements Change {
        @Override public String operation() { return "create"; }
    }

    public record Save(UUID memberKey, UUID expectedItemRevisionId, int expectedOrdinal, Integer ordinal,
                       NativeDocument document, NativeStructuralEdit edit) implements Change {
        @Override public String operation() { return "save"; }
    }

    public record Delete(UUID memberKey, UUID expectedItemRevisionId, int expectedOrdinal) implements Change {
        @Override public String operation() { return "delete"; }
    }

    public record Reorder(UUID memberKey, UUID expectedItemRevisionId, int expectedOrdinal, int ordinal) implements Change {
        @Override public String operation() { return "reorder"; }
    }
}
