package app.mnema.learning.generation.exercise;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The inverse of {@link ExerciseCompiler} for revising an existing exercise (REVISE_EXERCISE, #294): turns the stored exercise
 * ({@code type}, {@code content}, {@code answerKey}) back into the strict-JSON output form of
 * {@code contracts/generation/exercises/output.schema.json}, with local IDs ({@code o1}, {@code l1}, {@code r1}, {@code bl1},
 * {@code i1}, {@code c1}) numbered in the order they appear, the identifiers they stand for ({@link Decompiled#ids}, which
 * {@link ExerciseCompiler#compile(JsonNode, ExerciseContext, ExerciseIds, Map)} reuses for what the model keeps), the subject as
 * {@code m1}, the objective as {@code t1} and the quoted material blocks as handles of the pinned material.
 *
 * <p>The model sees only what it can express and write back. An {@code AUDIO} block of the prompt is not shown (like the media blocks of a
 * material edit): it is set aside with its position and put back by {@link Decompiled#withMedia}. Everything else must survive the round
 * trip: the decompiled exercise is compiled again with the same identifiers and must equal the stored one (the enabled flag aside), so an
 * exercise with an image, a rubric, a speech answer, a quote of another material or any other feature the output form does not have is
 * not revisable by the model and the decompiler answers empty. {@code whyWrong} is not stored, so a distractor is shown with a neutral one.
 */
public final class ExerciseDecompiler {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String WHY_WRONG = "Неверный вариант.";

    private ExerciseDecompiler() { }

    /** An {@code AUDIO} block of the prompt and where it stood. */
    public record Media(int index, ObjectNode block) { }

    /**
     * @param model the exercise in the output form ({@code mechanic}, {@code subject}, {@code objective}, ...)
     * @param ids local ID to the identifier it stands for
     * @param media the audio blocks of the prompt, in order
     */
    public record Decompiled(ObjectNode model, Map<String, UUID> ids, List<Media> media) {
        /** A copy of the compiled exercise with the audio blocks back in the prompt, each at its old position (or the end). */
        public ObjectNode withMedia(ObjectNode exercise) {
            ObjectNode copy = exercise.deepCopy();
            if (media.isEmpty()) return copy;
            ArrayNode prompt = (ArrayNode) copy.path("content").path("prompt");
            List<JsonNode> blocks = new ArrayList<>();
            prompt.forEach(blocks::add);
            for (Media audio : media) blocks.add(Math.min(audio.index(), blocks.size()), audio.block().deepCopy());
            ArrayNode rebuilt = ((ObjectNode) copy.path("content")).putArray("prompt");
            blocks.forEach(rebuilt::add);
            return copy;
        }
    }

    /**
     * @param exercise {@code {type, schemaVersion, enabled, subject, content, answerKey, evaluatorPolicy}} as stored
     * @param context the validation context of the revision: {@code m1} is the pinned material (the exercise's subject), {@code t1} its
     *                objective
     * @return the exercise in the output form, or empty when the model cannot be given it
     */
    public static Optional<Decompiled> decompile(JsonNode exercise, ExerciseContext context) {
        try {
            Decompiled result = new Builder(exercise, context).build();
            return roundTrips(exercise, result, context) ? Optional.of(result) : Optional.empty();
        } catch (UnsupportedExercise | IllegalArgumentException | NullPointerException | ClassCastException unsupported) {
            return Optional.empty();
        }
    }

    private static boolean roundTrips(JsonNode stored, Decompiled decompiled, ExerciseContext context) {
        ExerciseCompiler.Compiled compiled = ExerciseCompiler.compile(decompiled.model(), context, ExerciseIds.random(), decompiled.ids());
        ObjectNode again = decompiled.withMedia((ObjectNode) compiled.command().path("exercise"));
        return canonical(again).equals(canonical(stored));
    }

    /** The exercise without {@code enabled} and with the arrays whose order carries no meaning put in order. */
    private static JsonNode canonical(JsonNode exercise) {
        ObjectNode copy = (ObjectNode) exercise.deepCopy();
        copy.remove("enabled");
        JsonNode key = copy.path("answerKey");
        if (key.has("correctOptionIds")) sort((ArrayNode) key.path("correctOptionIds"));
        if (copy.path("type").stringValue("").equals("ORDER")) {
            Map<String, JsonNode> byId = new LinkedHashMap<>();
            copy.path("content").path("items").forEach(item -> byId.put(item.path("itemId").stringValue(""), item));
            ArrayNode ordered = ((ObjectNode) copy.path("content")).putArray("items");
            key.path("sequence").forEach(id -> ordered.add(byId.get(id.stringValue(""))));
        }
        return copy;
    }

    private static void sort(ArrayNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.stringValue("")));
        values.sort(String::compareTo);
        array.removeAll();
        values.forEach(array::add);
    }

    private static final class UnsupportedExercise extends RuntimeException {
        private static final long serialVersionUID = 1L;

        UnsupportedExercise() {
            super("The exercise has no output form", null, false, false);
        }
    }

    private static final class Builder {
        private final JsonNode exercise;
        private final JsonNode content;
        private final JsonNode key;
        private final ExerciseContext context;
        private final Map<String, UUID> ids = new LinkedHashMap<>();
        private final Map<String, Integer> counters = new LinkedHashMap<>();
        private final List<Media> media = new ArrayList<>();

        Builder(JsonNode exercise, ExerciseContext context) {
            this.exercise = exercise;
            this.content = exercise.path("content");
            this.key = exercise.path("answerKey");
            this.context = context;
        }

        Decompiled build() {
            String mechanic = exercise.path("type").stringValue("");
            ObjectNode model = JSON.createObjectNode().put("mechanic", mechanic).put("subject", "m1");
            model.putObject("objective").put("ref", "t1");
            model.set("prompt", prompt());
            switch (mechanic) {
                case "SELF_CHECK" -> model.set("reference", blocks(content.path("reference")));
                case "FREE_RESPONSE" -> freeResponse(model);
                case "CLOZE" -> cloze(model);
                case "CHOICE" -> choice(model);
                case "MATCH" -> match(model);
                case "ORDER" -> order(model);
                case "CATEGORIZE" -> categorize(model);
                default -> throw new UnsupportedExercise();
            }
            return new Decompiled(model, Map.copyOf(ids), List.copyOf(media));
        }

        // ------------------------------------------------------------------------ ids

        private String local(String prefix, String id) {
            String name = prefix + counters.merge(prefix, 1, Integer::sum);
            ids.put(name, UUID.fromString(id));
            return name;
        }

        private String known(String id) {
            return ids.entrySet().stream().filter(entry -> entry.getValue().toString().equals(id)).map(Map.Entry::getKey).findFirst()
                    .orElseThrow(UnsupportedExercise::new);
        }

        // ---------------------------------------------------------------------- blocks

        /** The prompt: audio blocks are set aside, everything else is expressed. */
        private ArrayNode prompt() {
            ArrayNode shown = JSON.createArrayNode();
            JsonNode all = content.path("prompt");
            for (int index = 0; index < all.size(); index++) {
                JsonNode block = all.get(index);
                if (block.path("kind").stringValue("").equals("AUDIO")) media.add(new Media(index, (ObjectNode) block.deepCopy()));
                else shown.add(block(block));
            }
            if (shown.isEmpty()) throw new UnsupportedExercise();
            return shown;
        }

        private ArrayNode blocks(JsonNode blocks) {
            ArrayNode shown = JSON.createArrayNode();
            blocks.forEach(block -> shown.add(block(block)));
            return shown;
        }

        private ObjectNode block(JsonNode block) {
            return switch (block.path("kind").stringValue("")) {
                case "TEXT" -> JSON.createObjectNode().put("kind", "TEXT").put("text", block.path("text").stringValue(""));
                case "MATERIAL" -> JSON.createObjectNode().put("kind", "MATERIAL").put("ref", handle(block));
                default -> throw new UnsupportedExercise();
            };
        }

        /** {@code m1:b3} of a quoted node, only when it is a block of the pinned material the model was shown. */
        private String handle(JsonNode quote) {
            ExerciseContext.Material material = context.materials().get("m1");
            if (!material.memberKey().toString().equals(quote.path("memberKey").stringValue(""))
                    || !material.itemRevisionId().toString().equals(quote.path("itemRevisionId").stringValue(""))) {
                throw new UnsupportedExercise();
            }
            String node = quote.path("nodeId").stringValue("");
            return material.blocks().entrySet().stream().filter(entry -> entry.getValue().nodeId().toString().equals(node))
                    .map(entry -> "m1:" + entry.getKey()).findFirst().orElseThrow(UnsupportedExercise::new);
        }

        /** The one text of a block list that holds exactly one TEXT block (an option, a pair side, an item). */
        private static String single(JsonNode blocks) {
            if (blocks.size() != 1 || !blocks.get(0).path("kind").stringValue("").equals("TEXT")) throw new UnsupportedExercise();
            return blocks.get(0).path("text").stringValue("");
        }

        private static ArrayNode copy(JsonNode array) {
            ArrayNode copy = JSON.createArrayNode();
            array.forEach(copy::add);
            return copy;
        }

        // ------------------------------------------------------------------- mechanics

        private void freeResponse(ObjectNode model) {
            if (!content.path("responseInput").stringValue("").equals("TEXT") || !exercise.path("evaluatorPolicy").path("id").stringValue("")
                    .equals("deterministic-text")) {
                throw new UnsupportedExercise();
            }
            model.set("reference", blocks(content.path("reference")));
            model.set("accepted", copy(key.path("accepted")));
            model.put("matchingMode", key.path("matchingMode").stringValue(""));
            model.set("normalization", copy(key.path("normalization")));
        }

        private void cloze(ObjectNode model) {
            ArrayNode passage = model.putArray("passage");
            for (JsonNode segment : content.path("passage")) {
                if (segment.path("kind").stringValue("").equals("TEXT")) {
                    passage.addObject().put("kind", "TEXT").put("text", segment.path("text").stringValue(""));
                } else {
                    ObjectNode blank = passage.addObject().put("kind", "BLANK").put("blank", local("bl", segment.path("blankId").stringValue("")));
                    blank.set("size", segment.path("size").deepCopy());
                    blank.put("firstLetterHint", segment.path("firstLetterHint").booleanValue(false));
                }
            }
            ArrayNode blanks = model.putArray("blanks");
            for (JsonNode blank : key.path("blanks")) {
                ObjectNode entry = blanks.addObject().put("blank", known(blank.path("blankId").stringValue("")));
                entry.set("accepted", copy(blank.path("accepted")));
                entry.put("matchingMode", blank.path("matchingMode").stringValue(""));
                entry.set("normalization", copy(blank.path("normalization")));
            }
        }

        private void choice(ObjectNode model) {
            model.put("selectionMode", content.path("selectionMode").stringValue(""));
            java.util.Set<String> correct = new java.util.HashSet<>();
            key.path("correctOptionIds").forEach(id -> correct.add(id.stringValue("")));
            ArrayNode options = model.putArray("options");
            for (JsonNode option : content.path("options")) {
                String id = option.path("optionId").stringValue("");
                ObjectNode entry = options.addObject().put("id", local("o", id)).put("text", single(option.path("blocks")));
                boolean right = correct.contains(id);
                entry.put("correct", right);
                if (!right) entry.put("whyWrong", WHY_WRONG);
            }
        }

        private void match(ObjectNode model) {
            ArrayNode left = model.putArray("left");
            for (JsonNode item : content.path("left")) {
                left.addObject().put("id", local("l", item.path("itemId").stringValue(""))).put("text", single(item.path("blocks")));
            }
            ArrayNode right = model.putArray("right");
            for (JsonNode item : content.path("right")) {
                right.addObject().put("id", local("r", item.path("itemId").stringValue(""))).put("text", single(item.path("blocks")));
            }
            ArrayNode pairs = model.putArray("pairs");
            for (JsonNode pair : key.path("pairs")) {
                pairs.addObject().put("left", known(pair.path("leftId").stringValue(""))).put("right", known(pair.path("rightId").stringValue("")));
            }
        }

        /** Items are listed in the correct order (the sequence of the key), as the output form wants. */
        private void order(ObjectNode model) {
            Map<String, JsonNode> byId = new LinkedHashMap<>();
            content.path("items").forEach(item -> byId.put(item.path("itemId").stringValue(""), item));
            ArrayNode items = model.putArray("items");
            for (JsonNode id : key.path("sequence")) {
                JsonNode item = byId.get(id.stringValue(""));
                items.addObject().put("id", local("i", id.stringValue(""))).put("text", single(item.path("blocks")));
            }
        }

        private void categorize(ObjectNode model) {
            ArrayNode categories = model.putArray("categories");
            for (JsonNode category : content.path("categories")) {
                categories.addObject().put("id", local("c", category.path("categoryId").stringValue("")))
                        .put("label", category.path("label").stringValue(""));
            }
            Map<String, String> assigned = new LinkedHashMap<>();
            key.path("assignments").forEach(entry -> assigned.put(entry.path("itemId").stringValue(""), entry.path("categoryId").stringValue("")));
            ArrayNode items = model.putArray("items");
            for (JsonNode item : content.path("items")) {
                String id = item.path("itemId").stringValue("");
                items.addObject().put("id", local("i", id)).put("text", single(item.path("blocks"))).put("category", known(assigned.get(id)));
            }
        }
    }
}
