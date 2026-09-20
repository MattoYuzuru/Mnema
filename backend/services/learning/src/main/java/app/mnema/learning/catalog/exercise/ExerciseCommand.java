package app.mnema.learning.catalog.exercise;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.concurrency.VersionPreconditionRequiredException;
import app.mnema.learning.platform.id.UuidPolicy;
import app.mnema.learning.platform.json.ContentJsonReader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Strict P0 exercise publication command; wire DTOs do not accept unknown fields. */
public record ExerciseCommand(UUID commandId, UUID expectedDeckRevisionId, UUID expectedExerciseRevisionId,
                              Objective objective, Exercise exercise, ObjectNode payload) {
    private static final int MAX_BYTES = 262_144;
    private static final ContentJsonReader JSON = new ContentJsonReader(MAX_BYTES, 32, 20_000);
    private static final Set<String> TYPES = Set.of("SELF_CHECK", "TYPED", "CLOZE_SINGLE", "SINGLE_CHOICE");
    private static final Set<String> ROLES = Set.of("ASSESSED", "CUE", "OPTION", "CONTEXT");
    private static final Set<String> NORMALIZATIONS = Set.of("UNICODE_NFC", "TRIM", "CASE_FOLD");

    public ExerciseCommand {
        commandId = UuidPolicy.requireCommandId(commandId);
        expectedDeckRevisionId = UuidPolicy.requireEntityId(expectedDeckRevisionId, "expectedDeckRevisionId");
        if (expectedExerciseRevisionId != null) {
            expectedExerciseRevisionId = UuidPolicy.requireEntityId(expectedExerciseRevisionId,
                    "expectedExerciseRevisionId");
        }
        payload = payload.deepCopy();
    }

    @Override public ObjectNode payload() { return payload.deepCopy(); }

    public ObjectNode envelope(UUID deckId, UUID exerciseId, long expectedDeckVersion) {
        ObjectNode envelope = payload();
        envelope.put("deckId", deckId.toString()).put("expectedDeckVersion", Long.toString(expectedDeckVersion));
        if (exerciseId != null) envelope.put("exerciseId", exerciseId.toString());
        return envelope;
    }

    public static ExerciseCommand readCreate(InputStream input) { return read(input, false); }

    public static ExerciseCommand readUpdate(InputStream input) { return read(input, true); }

    private static ExerciseCommand read(InputStream input, boolean update) {
        try {
            JsonNode body = JSON.read(input.readNBytes(MAX_BYTES + 1));
            require(body, "expectedDeckRevisionId");
            if (update) require(body, "expectedExerciseRevisionId");
            fields(body, update
                    ? Set.of("commandId", "expectedDeckRevisionId", "expectedExerciseRevisionId", "objective", "exercise")
                    : Set.of("commandId", "expectedDeckRevisionId", "objective", "exercise"));
            return new ExerciseCommand(id(body, "commandId"), id(body, "expectedDeckRevisionId"),
                    update ? id(body, "expectedExerciseRevisionId") : null,
                    objective(body.path("objective")), exercise(body.path("exercise")), (ObjectNode) body);
        } catch (IOException | IllegalArgumentException exception) {
            throw new InvalidRequestException();
        }
    }

    private static Objective objective(JsonNode value) {
        if (!value.isObject() || !value.path("operation").isTextual()) throw invalid();
        return switch (value.path("operation").textValue()) {
            case "create" -> {
                fields(value, Set.of("operation", "answerContract"));
                yield new CreateObjective(answer(value.path("answerContract")));
            }
            case "reuse" -> {
                fields(value, Set.of("operation", "objectiveId", "objectiveRevisionId"));
                yield new ReuseObjective(id(value, "objectiveId"), id(value, "objectiveRevisionId"));
            }
            case "revise" -> {
                require(value, "expectedObjectiveRevisionId");
                fields(value, Set.of("operation", "objectiveId", "expectedObjectiveRevisionId", "answerContract"));
                yield new ReviseObjective(id(value, "objectiveId"), id(value, "expectedObjectiveRevisionId"),
                        answer(value.path("answerContract")));
            }
            default -> throw invalid();
        };
    }

    private static ObjectNode answer(JsonNode value) {
        fields(value, Set.of("schemaVersion", "normalization", "accepted"));
        if (!value.path("schemaVersion").canConvertToInt() || value.path("schemaVersion").intValue() != 1
                || !value.path("normalization").isArray() || value.path("normalization").isEmpty()
                || value.path("normalization").size() > NORMALIZATIONS.size()
                || !value.path("accepted").isArray() || value.path("accepted").isEmpty()
                || value.path("accepted").size() > 20) throw invalid();
        var normalizations = new HashSet<String>();
        value.path("normalization").forEach(entry -> {
            if (!entry.isTextual() || !NORMALIZATIONS.contains(entry.textValue())
                    || !normalizations.add(entry.textValue())) throw invalid();
        });
        var accepted = new HashSet<String>();
        value.path("accepted").forEach(entry -> {
            if (!boundedText(entry, 512) || !accepted.add(entry.textValue())) throw invalid();
        });
        return ((ObjectNode) value).deepCopy();
    }

    private static Exercise exercise(JsonNode value) {
        fields(value, Set.of("type", "schemaVersion", "enabled", "prompt", "bindings", "evaluatorPolicy"));
        String type = text(value, "type", 32);
        if (!TYPES.contains(type) || !value.path("schemaVersion").canConvertToInt()
                || value.path("schemaVersion").intValue() != 1 || !value.path("enabled").isBoolean()) throw invalid();
        ObjectNode prompt = prompt(value.path("prompt"));
        List<Binding> bindings = bindings(value.path("bindings"));
        ObjectNode evaluator = evaluator(value.path("evaluatorPolicy"), type);
        long assessed = bindings.stream().filter(binding -> binding.role().equals("ASSESSED")).count();
        long options = bindings.stream().filter(binding -> binding.role().equals("OPTION")).count();
        if (assessed != 1 || (type.equals("SINGLE_CHOICE") ? options < 2 || options > 6 : options != 0)) {
            throw invalid();
        }
        return new Exercise(type, value.path("enabled").booleanValue(), prompt, bindings, evaluator);
    }

    private static ObjectNode prompt(JsonNode value) {
        String kind = text(value, "kind", 32);
        if (kind.equals("NODE_TEXT")) {
            fields(value, Set.of("kind", "memberKey", "itemRevisionId", "nodeId"));
            id(value, "memberKey"); id(value, "itemRevisionId"); id(value, "nodeId");
        } else if (kind.equals("CUSTOM_TEXT")) {
            fields(value, Set.of("kind", "text"));
            if (!boundedText(value.path("text"), 320)) throw invalid();
            if (value.path("text").textValue().codePoints().count() > 80) throw invalid();
        } else throw invalid();
        return ((ObjectNode) value).deepCopy();
    }

    private static List<Binding> bindings(JsonNode values) {
        if (!values.isArray() || values.isEmpty() || values.size() > 8) throw invalid();
        List<Binding> result = new ArrayList<>();
        Set<UUID> ids = new HashSet<>();
        Set<Integer> ordinals = new HashSet<>();
        for (JsonNode value : values) {
            Set<String> actual = names(value);
            if (!actual.equals(Set.of("bindingId", "role", "memberKey", "itemRevisionId", "ordinal"))
                    && !actual.equals(Set.of("bindingId", "role", "memberKey", "itemRevisionId", "nodeIds", "display", "ordinal"))) {
                throw invalid();
            }
            UUID bindingId = id(value, "bindingId");
            String role = text(value, "role", 16);
            int ordinal = integer(value.path("ordinal"));
            if (!ROLES.contains(role) || ordinal > 15 || !ids.add(bindingId) || !ordinals.add(ordinal)) throw invalid();
            List<UUID> nodeIds = new ArrayList<>();
            if (value.has("nodeIds")) {
                if (!value.path("nodeIds").isArray() || value.path("nodeIds").size() > 16) throw invalid();
                value.path("nodeIds").forEach(node -> nodeIds.add(idValue(node)));
                if (nodeIds.stream().distinct().count() != nodeIds.size()) throw invalid();
            }
            ObjectNode display = value.has("display") ? display(value.path("display")) : emptyDisplay();
            result.add(new Binding(bindingId, role, id(value, "memberKey"), id(value, "itemRevisionId"),
                    List.copyOf(nodeIds), display, ordinal));
        }
        return result.stream().sorted(java.util.Comparator.comparingInt(Binding::ordinal)).toList();
    }

    private static ObjectNode display(JsonNode value) {
        fields(value, Set.of("kind"));
        text(value, "kind", 32);
        return ((ObjectNode) value).deepCopy();
    }

    private static ObjectNode emptyDisplay() {
        return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode().put("kind", "NODE_TEXT");
    }

    private static ObjectNode evaluator(JsonNode value, String type) {
        fields(value, Set.of("id", "version"));
        String id = text(value, "id", 64);
        String version = text(value, "version", 16);
        String expected = switch (type) {
            case "SELF_CHECK" -> "self-check";
            case "TYPED", "CLOZE_SINGLE" -> "deterministic-text";
            case "SINGLE_CHOICE" -> "deterministic-choice";
            default -> throw invalid();
        };
        if (!id.equals(expected) || !version.equals("1")) throw invalid();
        return ((ObjectNode) value).deepCopy();
    }

    private static String text(JsonNode object, String name, int maxBytes) {
        JsonNode value = object.path(name);
        if (!boundedText(value, maxBytes)) throw invalid();
        return value.textValue();
    }

    private static boolean boundedText(JsonNode value, int maxBytes) {
        return value.isTextual() && !value.textValue().isBlank()
                && value.textValue().getBytes(StandardCharsets.UTF_8).length <= maxBytes;
    }

    private static int integer(JsonNode value) {
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) throw invalid();
        return value.intValue();
    }

    private static UUID id(JsonNode object, String name) { return idValue(object.path(name)); }

    private static UUID idValue(JsonNode value) {
        if (!value.isTextual() || value.textValue().length() != 36) throw invalid();
        try {
            UUID id = UuidPolicy.requireEntityId(UUID.fromString(value.textValue()), "id");
            if (!id.toString().equals(value.textValue())) throw invalid();
            return id;
        } catch (IllegalArgumentException exception) { throw invalid(); }
    }

    private static void require(JsonNode object, String name) {
        if (!object.has(name)) throw new VersionPreconditionRequiredException();
    }

    private static void fields(JsonNode node, Set<String> expected) {
        if (!node.isObject() || !names(node).equals(expected)) throw invalid();
    }

    private static Set<String> names(JsonNode node) {
        if (!node.isObject()) throw invalid();
        return node.properties().stream().map(java.util.Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static InvalidRequestException invalid() { return new InvalidRequestException(); }

    public sealed interface Objective permits CreateObjective, ReuseObjective, ReviseObjective { }
    public record CreateObjective(ObjectNode answerContract) implements Objective {
        public CreateObjective { answerContract = answerContract.deepCopy(); }
        @Override public ObjectNode answerContract() { return answerContract.deepCopy(); }
    }
    public record ReuseObjective(UUID objectiveId, UUID objectiveRevisionId) implements Objective { }
    public record ReviseObjective(UUID objectiveId, UUID expectedObjectiveRevisionId,
                                  ObjectNode answerContract) implements Objective {
        public ReviseObjective { answerContract = answerContract.deepCopy(); }
        @Override public ObjectNode answerContract() { return answerContract.deepCopy(); }
    }
    public record Exercise(String type, boolean enabled, ObjectNode prompt, List<Binding> bindings,
                           ObjectNode evaluatorPolicy) {
        public Exercise {
            prompt = prompt.deepCopy(); bindings = List.copyOf(bindings); evaluatorPolicy = evaluatorPolicy.deepCopy();
        }
        @Override public ObjectNode prompt() { return prompt.deepCopy(); }
        @Override public ObjectNode evaluatorPolicy() { return evaluatorPolicy.deepCopy(); }
    }
    public record Binding(UUID bindingId, String role, UUID memberKey, UUID itemRevisionId,
                          List<UUID> nodeIds, ObjectNode display, int ordinal) {
        public Binding { nodeIds = List.copyOf(nodeIds); display = display.deepCopy(); }
        @Override public ObjectNode display() { return display.deepCopy(); }
    }
}
