package app.mnema.learning.generation.exercise;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Turns one schema- and lint-clean model exercise into the publication command of {@code ExerciseCommand.readCreate}
 * (compile rules of {@code contracts/generation/exercises/README.md}). Pure and Spring-free: identifiers come from the
 * injected {@link ExerciseIds}, material blocks from the {@link ExerciseContext}. Text is kept verbatim; the stored
 * {@code whyWrong} rationale is dropped (decision 6 of the generation contract).
 */
public final class ExerciseCompiler {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<String> DEFAULT_NORMALIZATION = List.of("UNICODE_NFC", "TRIM", "CASE_FOLD");

    /**
     * @param command {@code {commandId, expectedDeckRevisionId, objective, exercise}} with the context's placeholders
     * @param idMap local ID to allocated UUID, for every local ID of the exercise
     * @param objectiveTitle the title of the objective the exercise evidences (offered or new), for display
     */
    public record Compiled(ObjectNode command, Map<String, UUID> idMap, String objectiveTitle) { }

    private final JsonNode source;
    private final ExerciseContext context;
    private final ExerciseIds ids;
    private final Map<String, UUID> allocated = new LinkedHashMap<>();

    private ExerciseCompiler(JsonNode source, ExerciseContext context, ExerciseIds ids, Map<String, UUID> known) {
        this.source = source;
        this.context = context;
        this.ids = ids;
        this.allocated.putAll(known);
    }

    /** @throws IllegalArgumentException the exercise was not validated by the schema and the lint first */
    public static Compiled compile(JsonNode exercise, ExerciseContext context, ExerciseIds ids) {
        return new ExerciseCompiler(exercise, context, ids, Map.of()).compile();
    }

    /**
     * Compiles a revision of an existing exercise: {@code known} maps the local IDs the model was shown to the identifiers they had, so an
     * option, pair, item, category or blank that the model keeps under its local ID keeps its identifier; only the local IDs that are new
     * get one from {@code ids}.
     *
     * @throws IllegalArgumentException the exercise was not validated by the schema and the lint first
     */
    public static Compiled compile(JsonNode exercise, ExerciseContext context, ExerciseIds ids, Map<String, UUID> known) {
        return new ExerciseCompiler(exercise, context, ids, known).compile();
    }

    private Compiled compile() {
        String mechanic = source.path("mechanic").stringValue("");
        ObjectNode content = JSON.createObjectNode();
        ObjectNode key = JSON.createObjectNode();
        String evaluator = switch (mechanic) {
            case "SELF_CHECK" -> {
                content.set("prompt", blocks("prompt"));
                content.set("reference", blocks("reference"));
                key.put("kind", "SELF_REPORT");
                yield "self-check";
            }
            case "FREE_RESPONSE" -> {
                content.set("prompt", blocks("prompt"));
                content.set("reference", source.has("reference") ? blocks("reference") : JSON.createArrayNode());
                content.put("responseInput", "TEXT");
                key.put("kind", "TEXT");
                key.set("accepted", copy(source.path("accepted")));
                key.set("normalization", normalization(source));
                key.put("matchingMode", source.path("matchingMode").stringValue(""));
                yield "deterministic-text";
            }
            case "CLOZE" -> {
                cloze(content, key);
                yield "deterministic-cloze";
            }
            case "CHOICE" -> {
                choice(content, key);
                yield "deterministic-choice";
            }
            case "MATCH" -> {
                match(content, key);
                yield "deterministic-match";
            }
            case "ORDER" -> {
                order(content, key);
                yield "deterministic-order";
            }
            case "CATEGORIZE" -> {
                categorize(content, key);
                yield "deterministic-categorize";
            }
            default -> throw new IllegalArgumentException("Unknown mechanic");
        };
        ObjectNode exercise = JSON.createObjectNode().put("type", mechanic).put("schemaVersion", 2).put("enabled", true);
        ExerciseContext.Material subject = context.materials().get(source.path("subject").stringValue(""));
        if (subject == null) throw new IllegalArgumentException("Unknown subject");
        exercise.putObject("subject").put("memberKey", subject.memberKey().toString())
                .put("itemRevisionId", subject.itemRevisionId().toString());
        exercise.set("content", content);
        exercise.set("answerKey", key);
        exercise.putObject("evaluatorPolicy").put("id", evaluator).put("version", "1");

        ObjectNode command = JSON.createObjectNode().put("commandId", context.commandId().toString())
                .put("expectedDeckRevisionId", context.expectedDeckRevisionId().toString());
        ObjectNode objective = objective();
        command.set("objective", objective);
        command.set("exercise", exercise);
        return new Compiled(command, new LinkedHashMap<>(allocated), objectiveTitle(objective));
    }

    // -------------------------------------------------------------------------- objective

    private ObjectNode objective() {
        JsonNode objective = source.path("objective");
        ExerciseContext.Objective offered = objective.has("ref") ? context.objectives().get(objective.path("ref").stringValue(""))
                : context.objectiveTitled(objective.path("title").stringValue("")).orElse(null);
        ObjectNode result = JSON.createObjectNode();
        if (offered != null) {
            result.put("operation", "reuse").put("objectiveId", offered.objectiveId().toString())
                    .put("objectiveRevisionId", offered.objectiveRevisionId().toString());
        } else {
            result.put("operation", "create").put("title", objective.path("title").stringValue(""));
        }
        return result;
    }

    private String objectiveTitle(ObjectNode objective) {
        if (objective.has("title")) return objective.path("title").stringValue("");
        UUID id = UUID.fromString(objective.path("objectiveId").stringValue(""));
        return context.objectives().values().stream().filter(offered -> offered.objectiveId().equals(id))
                .map(ExerciseContext.Objective::title).filter(title -> title != null).findFirst().orElse("");
    }

    // ---------------------------------------------------------------------------- blocks

    private ArrayNode blocks(String slot) {
        ArrayNode blocks = JSON.createArrayNode();
        for (JsonNode block : source.path(slot)) {
            if (block.path("kind").stringValue("").equals("MATERIAL")) {
                String reference = block.path("ref").stringValue("");
                ExerciseContext.Material material = context.materials().get(reference.substring(0, reference.indexOf(':')));
                ExerciseContext.Block pinned = context.block(reference).orElseThrow(() -> new IllegalArgumentException("Unknown block"));
                blocks.addObject().put("kind", "MATERIAL").put("memberKey", material.memberKey().toString())
                        .put("itemRevisionId", material.itemRevisionId().toString()).put("nodeId", pinned.nodeId().toString());
            } else {
                blocks.addObject().put("kind", "TEXT").put("text", block.path("text").stringValue(""));
            }
        }
        return blocks;
    }

    private ArrayNode textTile(String text) {
        ArrayNode blocks = JSON.createArrayNode();
        blocks.addObject().put("kind", "TEXT").put("text", text);
        return blocks;
    }

    private UUID id(ExerciseIds.Kind kind, String local) {
        return allocated.computeIfAbsent(local, ignored -> ids.next(kind));
    }

    private static ArrayNode copy(JsonNode array) {
        ArrayNode copy = JSON.createArrayNode();
        array.forEach(copy::add);
        return copy;
    }

    private static ArrayNode normalization(JsonNode owner) {
        ArrayNode rules = JSON.createArrayNode();
        if (owner.has("normalization")) owner.path("normalization").forEach(rules::add);
        else DEFAULT_NORMALIZATION.forEach(rules::add);
        return rules;
    }

    // -------------------------------------------------------------------------- mechanics

    private void cloze(ObjectNode content, ObjectNode key) {
        content.set("prompt", blocks("prompt"));
        ArrayNode passage = content.putArray("passage");
        for (JsonNode segment : source.path("passage")) {
            if (segment.path("kind").stringValue("").equals("TEXT")) {
                passage.addObject().put("kind", "TEXT").put("text", segment.path("text").stringValue(""));
            } else {
                ObjectNode blank = passage.addObject().put("kind", "BLANK")
                        .put("blankId", id(ExerciseIds.Kind.BLANK, segment.path("blank").stringValue("")).toString());
                blank.set("size", segment.path("size").deepCopy());
                blank.put("firstLetterHint", segment.path("firstLetterHint").booleanValue(false));
            }
        }
        key.put("kind", "CLOZE");
        ArrayNode blanks = key.putArray("blanks");
        for (JsonNode blank : source.path("blanks")) {
            ObjectNode entry = blanks.addObject().put("blankId", id(ExerciseIds.Kind.BLANK, blank.path("blank").stringValue("")).toString());
            entry.set("accepted", copy(blank.path("accepted")));
            entry.set("normalization", normalization(blank));
            entry.put("matchingMode", blank.path("matchingMode").stringValue(""));
        }
    }

    private void choice(ObjectNode content, ObjectNode key) {
        content.set("prompt", blocks("prompt"));
        content.put("selectionMode", source.path("selectionMode").stringValue(""));
        ArrayNode options = content.putArray("options");
        ArrayNode correct = JSON.createArrayNode();
        for (JsonNode option : source.path("options")) {
            UUID optionId = id(ExerciseIds.Kind.OPTION, option.path("id").stringValue(""));
            ObjectNode entry = options.addObject().put("optionId", optionId.toString());
            entry.set("blocks", textTile(option.path("text").stringValue("")));
            if (option.path("correct").booleanValue(false)) correct.add(optionId.toString());
        }
        key.put("kind", "CHOICE");
        key.set("correctOptionIds", correct);
    }

    private void match(ObjectNode content, ObjectNode key) {
        content.set("prompt", blocks("prompt"));
        ArrayNode left = content.putArray("left");
        for (JsonNode item : source.path("left")) {
            left.addObject().put("itemId", id(ExerciseIds.Kind.LEFT, item.path("id").stringValue("")).toString())
                    .set("blocks", textTile(item.path("text").stringValue("")));
        }
        ArrayNode right = content.putArray("right");
        for (JsonNode item : source.path("right")) {
            right.addObject().put("itemId", id(ExerciseIds.Kind.RIGHT, item.path("id").stringValue("")).toString())
                    .set("blocks", textTile(item.path("text").stringValue("")));
        }
        key.put("kind", "MATCH");
        ArrayNode pairs = key.putArray("pairs");
        for (JsonNode pair : source.path("pairs")) {
            pairs.addObject().put("leftId", id(ExerciseIds.Kind.LEFT, pair.path("left").stringValue("")).toString())
                    .put("rightId", id(ExerciseIds.Kind.RIGHT, pair.path("right").stringValue("")).toString());
        }
    }

    private void order(ObjectNode content, ObjectNode key) {
        content.set("prompt", blocks("prompt"));
        ArrayNode items = content.putArray("items");
        ArrayNode sequence = JSON.createArrayNode();
        for (JsonNode item : source.path("items")) {
            UUID itemId = id(ExerciseIds.Kind.ITEM, item.path("id").stringValue(""));
            items.addObject().put("itemId", itemId.toString()).set("blocks", textTile(item.path("text").stringValue("")));
            sequence.add(itemId.toString());
        }
        key.put("kind", "ORDER");
        key.set("sequence", sequence);
    }

    private void categorize(ObjectNode content, ObjectNode key) {
        content.set("prompt", blocks("prompt"));
        ArrayNode categories = content.putArray("categories");
        for (JsonNode category : source.path("categories")) {
            categories.addObject().put("categoryId", id(ExerciseIds.Kind.CATEGORY, category.path("id").stringValue("")).toString())
                    .put("label", category.path("label").stringValue(""));
        }
        ArrayNode items = content.putArray("items");
        ArrayNode assignments = JSON.createArrayNode();
        for (JsonNode item : source.path("items")) {
            UUID itemId = id(ExerciseIds.Kind.ITEM, item.path("id").stringValue(""));
            items.addObject().put("itemId", itemId.toString()).set("blocks", textTile(item.path("text").stringValue("")));
            assignments.addObject().put("itemId", itemId.toString())
                    .put("categoryId", id(ExerciseIds.Kind.CATEGORY, item.path("category").stringValue("")).toString());
        }
        key.put("kind", "CATEGORIZE");
        key.set("assignments", assignments);
    }
}
