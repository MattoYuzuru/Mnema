package app.mnema.learning.catalog.exercise;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.concurrency.VersionPreconditionRequiredException;
import app.mnema.learning.platform.id.UuidPolicy;
import app.mnema.learning.platform.json.ContentJsonReader;
import app.mnema.learning.platform.text.SoftTextNormalizer;
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

/** Strict versioned exercise publication command; wire DTOs do not accept unknown fields. */
public record ExerciseCommand(UUID commandId, UUID expectedDeckRevisionId, UUID expectedExerciseRevisionId,
                              Objective objective, Exercise exercise, ObjectNode payload) {
    private static final int MAX_BYTES = 262_144;
    private static final ContentJsonReader JSON = new ContentJsonReader(MAX_BYTES, 32, 20_000);
    private static final Set<String> TYPES = Set.of("SELF_CHECK", "TYPED", "CLOZE_SINGLE", "SINGLE_CHOICE",
            "LISTEN_CHOICE", "AUDIO_TEXT_MATCH", "LISTEN_TYPE");
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
            Objective objective = objective(body.path("objective"));
            Exercise exercise = exercise(body.path("exercise"));
            validateAnswerCompatibility(objective, exercise);
            return new ExerciseCommand(id(body, "commandId"), id(body, "expectedDeckRevisionId"),
                    update ? id(body, "expectedExerciseRevisionId") : null,
                    objective, exercise, (ObjectNode) body);
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
        if (value.path("schemaVersion").isIntegralNumber() && value.path("schemaVersion").intValue() == 3) {
            fields(value, Set.of("schemaVersion", "selectionMode", "correctOptionIds", "accepted"));
            if (!Set.of("SINGLE", "MULTIPLE").contains(value.path("selectionMode").asText())
                    || !value.path("correctOptionIds").isArray() || value.path("correctOptionIds").isEmpty()
                    || !value.path("accepted").isArray()
                    || value.path("accepted").size() != value.path("correctOptionIds").size()
                    || (value.path("selectionMode").asText().equals("SINGLE")
                        && value.path("correctOptionIds").size() != 1)) throw invalid();
            var ids = new HashSet<UUID>();
            value.path("correctOptionIds").forEach(option -> { if (!ids.add(idValue(option))) throw invalid(); });
            value.path("accepted").forEach(entry -> { if (!boundedText(entry, 512)) throw invalid(); });
            return ((ObjectNode) value).deepCopy();
        }
        if (value.path("schemaVersion").isIntegralNumber() && value.path("schemaVersion").intValue() == 2) {
            fields(value, Set.of("schemaVersion", "pairs"));
            JsonNode pairs = value.path("pairs");
            if (!pairs.isArray() || pairs.size() < 2 || pairs.size() > 6) throw invalid();
            var cues = new HashSet<UUID>();
            var options = new HashSet<UUID>();
            pairs.forEach(pair -> {
                fields(pair, Set.of("cueId", "optionId"));
                if (!cues.add(id(pair, "cueId")) || !options.add(id(pair, "optionId"))) throw invalid();
            });
            return ((ObjectNode) value).deepCopy();
        }
        if (value.has("matchingMode")) fields(value, Set.of("schemaVersion", "normalization", "accepted", "matchingMode"));
        else fields(value, Set.of("schemaVersion", "normalization", "accepted"));
        if (!value.path("schemaVersion").canConvertToInt() || value.path("schemaVersion").intValue() != 1
                || !value.path("normalization").isArray() || value.path("normalization").isEmpty()
                || value.path("normalization").size() > NORMALIZATIONS.size()
                || !value.path("accepted").isArray() || value.path("accepted").isEmpty()
                || value.path("accepted").size() > 20) throw invalid();
        if (value.has("matchingMode") && (!value.path("matchingMode").isTextual()
                || !Set.of("STRICT", "SOFT").contains(value.path("matchingMode").textValue()))) throw invalid();
        var normalizations = new HashSet<String>();
        value.path("normalization").forEach(entry -> {
            if (!entry.isTextual() || !NORMALIZATIONS.contains(entry.textValue())
                    || !normalizations.add(entry.textValue())) throw invalid();
        });
        var accepted = new HashSet<String>();
        boolean soft = value.path("matchingMode").asText("STRICT").equals("SOFT");
        value.path("accepted").forEach(entry -> {
            if (!boundedText(entry, 512) || !accepted.add(entry.textValue())
                    || (soft && SoftTextNormalizer.normalize(entry.textValue()).isEmpty())) throw invalid();
        });
        return ((ObjectNode) value).deepCopy();
    }

    private static Exercise exercise(JsonNode value) {
        fields(value, Set.of("type", "schemaVersion", "enabled", "prompt", "bindings", "evaluatorPolicy"));
        String type = text(value, "type", 32);
        if (!TYPES.contains(type) || !value.path("schemaVersion").canConvertToInt()
                || value.path("schemaVersion").intValue() != 1 || !value.path("enabled").isBoolean()) throw invalid();
        ObjectNode prompt = prompt(value.path("prompt"), type);
        List<Binding> bindings = bindings(value.path("bindings"));
        ObjectNode evaluator = evaluator(value.path("evaluatorPolicy"), type);
        long assessed = bindings.stream().filter(binding -> binding.role().equals("ASSESSED")).count();
        long options = bindings.stream().filter(binding -> binding.role().equals("OPTION")).count();
        boolean choices = type.equals("SINGLE_CHOICE") || type.equals("LISTEN_CHOICE")
                || type.equals("AUDIO_TEXT_MATCH");
        if (assessed != 1 || (choices ? options < 2 || (type.equals("AUDIO_TEXT_MATCH") && options > 6) : options != 0)) {
            throw invalid();
        }
        for (Binding binding : bindings) {
            if (binding.display().path("kind").textValue().equals("CUSTOM_TEXT")
                    && (!binding.role().equals("ASSESSED") || choices)) throw invalid();
        }
        if (choices) validateChoiceTargets(bindings);
        return new Exercise(type, value.path("enabled").booleanValue(), prompt, bindings, evaluator);
    }

    private static void validateAnswerCompatibility(Objective objective, Exercise exercise) {
        String type = exercise.type();
        if (type.equals("AUDIO_TEXT_MATCH") && objective instanceof ReuseObjective) throw invalid();
        ObjectNode answer = switch (objective) {
            case CreateObjective create -> create.answerContract();
            case ReviseObjective revise -> revise.answerContract();
            case ReuseObjective ignored -> null;
        };
        if (answer == null) return;
        if (type.equals("SINGLE_CHOICE") || type.equals("LISTEN_CHOICE")) {
            if (answer.path("schemaVersion").intValue() == 3) {
                validateChoiceAnswer(answer, exercise);
                return;
            }
        }
        if (!type.equals("AUDIO_TEXT_MATCH")) {
            if (answer.path("schemaVersion").intValue() != 1) throw invalid();
            validateBlank(exercise.prompt(), answer);
            Binding assessed = exercise.bindings().stream().filter(binding -> binding.role().equals("ASSESSED"))
                    .findFirst().orElseThrow();
            if (assessed.display().path("kind").textValue().equals("CUSTOM_TEXT")
                    && !answer.path("accepted").get(0).textValue().equals(assessed.display().path("text").textValue())) {
                throw invalid();
            }
            return;
        }
        if (answer.path("schemaVersion").intValue() != 2) throw invalid();
        var cues = new HashSet<UUID>();
        exercise.prompt().path("cues").forEach(cue -> cues.add(id(cue, "cueId")));
        var options = new HashSet<UUID>();
        exercise.bindings().stream().filter(binding -> binding.role().equals("OPTION"))
                .forEach(binding -> options.add(binding.bindingId()));
        var mappedCues = new HashSet<UUID>();
        var mappedOptions = new HashSet<UUID>();
        answer.path("pairs").forEach(pair -> {
            mappedCues.add(id(pair, "cueId"));
            mappedOptions.add(id(pair, "optionId"));
        });
        if (!cues.equals(mappedCues) || !options.equals(mappedOptions)) throw invalid();
    }

    static void validateChoiceAnswer(JsonNode answer, Exercise exercise) {
        var options = new HashSet<String>();
        exercise.bindings().stream().filter(binding -> binding.role().equals("OPTION"))
                .forEach(binding -> options.add(binding.bindingId().toString()));
        for (JsonNode correct : answer.path("correctOptionIds")) {
            if (!options.contains(correct.textValue())) throw invalid();
        }
        Binding assessed = exercise.bindings().stream().filter(binding -> binding.role().equals("ASSESSED"))
                .findFirst().orElseThrow();
        boolean assessedCorrect = exercise.bindings().stream().filter(binding -> binding.role().equals("OPTION"))
                .filter(binding -> Target.of(binding).equals(Target.of(assessed)))
                .anyMatch(binding -> {
                    for (JsonNode correct : answer.path("correctOptionIds")) {
                        if (binding.bindingId().toString().equals(correct.textValue())) return true;
                    }
                    return false;
                });
        if (!assessedCorrect) throw invalid();
    }

    private static void validateChoiceTargets(List<Binding> bindings) {
        Binding assessed = bindings.stream().filter(binding -> binding.role().equals("ASSESSED"))
                .findFirst().orElseThrow();
        if (assessed.nodeIds().size() != 1) throw invalid();
        Target assessedTarget = Target.of(assessed);
        Set<Target> options = new HashSet<>();
        for (Binding binding : bindings) {
            if (!binding.role().equals("OPTION")) continue;
            if (binding.nodeIds().size() != 1 || !options.add(Target.of(binding))) throw invalid();
        }
        if (!options.contains(assessedTarget)) throw invalid();
    }

    private static ObjectNode prompt(JsonNode value, String type) {
        String kind = text(value, "kind", 32);
        boolean listening = type.equals("LISTEN_CHOICE") || type.equals("LISTEN_TYPE")
                || type.equals("AUDIO_TEXT_MATCH");
        if (kind.equals("AUDIO_ASSET") && listening && !type.equals("AUDIO_TEXT_MATCH")) {
            audioCue(value, false);
        } else if (kind.equals("AUDIO_MATCH") && type.equals("AUDIO_TEXT_MATCH")) {
            fields(value, Set.of("kind", "instruction", "cues"));
            shortText(value.path("instruction"));
            JsonNode cues = value.path("cues");
            if (!cues.isArray() || cues.size() < 2 || cues.size() > 6) throw invalid();
            var ids = new HashSet<UUID>();
            var assets = new HashSet<UUID>();
            cues.forEach(cue -> {
                audioCue(cue, true);
                if (!ids.add(id(cue, "cueId")) || !assets.add(id(cue, "assetId"))) throw invalid();
            });
        } else if (kind.equals("NODE_TEXT") && !listening) {
            fields(value, type.equals("CLOZE_SINGLE") && value.has("blank")
                    ? Set.of("kind", "memberKey", "itemRevisionId", "nodeId", "blank")
                    : Set.of("kind", "memberKey", "itemRevisionId", "nodeId"));
            id(value, "memberKey"); id(value, "itemRevisionId"); id(value, "nodeId");
        } else if (kind.equals("CUSTOM_TEXT") && !listening) {
            fields(value, type.equals("CLOZE_SINGLE") && value.has("blank")
                    ? Set.of("kind", "text", "blank") : Set.of("kind", "text"));
            shortText(value.path("text"));
        } else throw invalid();
        if (value.has("blank")) blank(value.path("blank"));
        return ((ObjectNode) value).deepCopy();
    }

    static void validateBlank(JsonNode prompt, JsonNode answer) {
        JsonNode blank = prompt.path("blank");
        if (blank.isMissingNode() || !blank.path("mode").asText().equals("ANSWER_LENGTH")) return;
        int length = -1;
        for (JsonNode accepted : answer.path("accepted")) {
            String canonical = java.text.Normalizer.normalize(accepted.textValue(), java.text.Normalizer.Form.NFC);
            int current = canonical.codePointCount(0, canonical.length());
            if (length >= 0 && length != current) throw invalid();
            length = current;
        }
        if (length < 1 || length > 80) throw invalid();
    }

    private static void blank(JsonNode value) {
        String mode = text(value, "mode", 16);
        if (mode.equals("ANSWER_LENGTH")) fields(value, Set.of("mode"));
        else if (mode.equals("FIXED")) {
            fields(value, Set.of("mode", "length"));
            if (!value.path("length").isIntegralNumber() || !value.path("length").canConvertToInt()
                    || value.path("length").intValue() < 5 || value.path("length").intValue() > 20) throw invalid();
        } else throw invalid();
    }

    private static void audioCue(JsonNode value, boolean matching) {
        Set<String> required = matching
                ? Set.of("cueId", "assetId", "title", "transcript")
                : Set.of("kind", "assetId", "title", "instruction", "transcript");
        fields(value, required);
        if (matching) id(value, "cueId");
        id(value, "assetId");
        if (!boundedText(value.path("title"), 1_024)
                || !value.path("transcript").isTextual()
                || value.path("transcript").textValue().getBytes(StandardCharsets.UTF_8).length > 16_384) {
            throw invalid();
        }
        if (!matching) shortText(value.path("instruction"));
    }

    private static void shortText(JsonNode value) {
        if (!boundedText(value, 320) || value.textValue().codePoints().count() > 80) throw invalid();
    }

    private static List<Binding> bindings(JsonNode values) {
        if (!values.isArray() || values.isEmpty()) throw invalid();
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
            if (!ROLES.contains(role) || !ids.add(bindingId) || !ordinals.add(ordinal)) throw invalid();
            List<UUID> nodeIds = new ArrayList<>();
            if (value.has("nodeIds")) {
                if (!value.path("nodeIds").isArray() || value.path("nodeIds").size() > 16) throw invalid();
                value.path("nodeIds").forEach(node -> nodeIds.add(idValue(node)));
                if (nodeIds.stream().distinct().count() != nodeIds.size()) throw invalid();
            }
            ObjectNode display = value.has("display") ? display(value.path("display")) : emptyDisplay();
            if (display.path("kind").textValue().equals("CUSTOM_TEXT")
                    ? !role.equals("ASSESSED") || !nodeIds.isEmpty()
                    : role.equals("ASSESSED") && nodeIds.isEmpty()) {
                throw invalid();
            }
            result.add(new Binding(bindingId, role, id(value, "memberKey"), id(value, "itemRevisionId"),
                    List.copyOf(nodeIds), display, ordinal));
        }
        return result.stream().sorted(java.util.Comparator.comparingInt(Binding::ordinal)).toList();
    }

    private static ObjectNode display(JsonNode value) {
        String kind = text(value, "kind", 32);
        if (kind.equals("CUSTOM_TEXT")) {
            fields(value, Set.of("kind", "text"));
            shortText(value.path("text"));
        } else {
            fields(value, Set.of("kind"));
            if (!kind.equals("NODE_TEXT")) throw invalid();
        }
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
            case "TYPED", "CLOZE_SINGLE", "LISTEN_TYPE" -> "deterministic-text";
            case "SINGLE_CHOICE", "LISTEN_CHOICE" -> "deterministic-choice";
            case "AUDIO_TEXT_MATCH" -> "deterministic-audio-match";
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

    private record Target(UUID memberKey, UUID itemRevisionId, List<UUID> nodeIds) {
        private static Target of(Binding binding) {
            return new Target(binding.memberKey(), binding.itemRevisionId(), binding.nodeIds());
        }
    }

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

        public List<UUID> audioAssets() {
            if (type.equals("AUDIO_TEXT_MATCH")) {
                var assets = new ArrayList<UUID>();
                prompt.path("cues").forEach(cue -> assets.add(UUID.fromString(cue.path("assetId").textValue())));
                return List.copyOf(assets);
            }
            if (type.equals("LISTEN_CHOICE") || type.equals("LISTEN_TYPE")) {
                return List.of(UUID.fromString(prompt.path("assetId").textValue()));
            }
            return List.of();
        }
    }
    public record Binding(UUID bindingId, String role, UUID memberKey, UUID itemRevisionId,
                          List<UUID> nodeIds, ObjectNode display, int ordinal) {
        public Binding { nodeIds = List.copyOf(nodeIds); display = display.deepCopy(); }
        @Override public ObjectNode display() { return display.deepCopy(); }
    }
}
