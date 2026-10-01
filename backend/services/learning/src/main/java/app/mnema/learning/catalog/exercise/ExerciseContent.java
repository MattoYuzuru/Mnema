package app.mnema.learning.catalog.exercise;

import com.fasterxml.jackson.databind.JsonNode;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static app.mnema.learning.catalog.exercise.StrictJson.array;
import static app.mnema.learning.catalog.exercise.StrictJson.bool;
import static app.mnema.learning.catalog.exercise.StrictJson.fields;
import static app.mnema.learning.catalog.exercise.StrictJson.id;
import static app.mnema.learning.catalog.exercise.StrictJson.integer;
import static app.mnema.learning.catalog.exercise.StrictJson.invalid;
import static app.mnema.learning.catalog.exercise.StrictJson.oneOf;

/**
 * Validated content of one mechanic: typed slots of {@link Block}s plus mechanic settings. This is the
 * author-side view; the persisted JSON is stored verbatim next to it.
 */
public sealed interface ExerciseContent {
    int MAX_PROMPT_BLOCKS = 8;
    int MAX_PASSAGE_SEGMENTS = 64;
    int MAX_BLANKS = 12;
    int MAX_PASSAGE_TEXT = 4_000;
    int MIN_FIXED_BLANK = 5;
    int MAX_FIXED_BLANK = 20;
    int MIN_OPTIONS = 2;
    int MAX_OPTIONS = 12;
    int MIN_SIDE = 2;
    int MAX_SIDE = 6;
    Set<String> RESPONSE_INPUTS = Set.of("TEXT", "TEXT_OR_SPEECH");

    /** Every block of every slot in document order. */
    List<Block> blocks();

    record SelfCheck(List<Block> prompt, List<Block> reference) implements ExerciseContent {
        @Override public List<Block> blocks() { return concat(prompt, reference); }
    }

    record FreeResponse(List<Block> prompt, List<Block> reference, String responseInput) implements ExerciseContent {
        @Override public List<Block> blocks() { return concat(prompt, reference); }
        public boolean acceptsSpeech() { return responseInput.equals("TEXT_OR_SPEECH"); }
    }

    record Cloze(List<Block> prompt, List<Segment> passage) implements ExerciseContent {
        @Override public List<Block> blocks() { return prompt; }

        public List<Blank> blanks() {
            return passage.stream().filter(Blank.class::isInstance).map(Blank.class::cast).toList();
        }
    }

    record Choice(List<Block> prompt, boolean multiple, List<Option> options) implements ExerciseContent {
        @Override public List<Block> blocks() {
            return Stream.concat(prompt.stream(), options.stream().flatMap(option -> option.blocks().stream())).toList();
        }
    }

    record Match(List<Block> prompt, List<Item> left, List<Item> right) implements ExerciseContent {
        @Override public List<Block> blocks() {
            return Stream.of(prompt.stream(), left.stream().flatMap(item -> item.blocks().stream()),
                    right.stream().flatMap(item -> item.blocks().stream())).flatMap(stream -> stream).toList();
        }
    }

    sealed interface Segment { }
    record Literal(String text) implements Segment { }
    record Blank(UUID blankId, boolean fixed, int length, boolean firstLetterHint) implements Segment { }
    record Option(UUID optionId, List<Block> blocks) { }
    record Item(UUID itemId, List<Block> blocks) { }

    /** Strict exact-field parse of one mechanic's {@code content} object. */
    static ExerciseContent parse(ExerciseType type, JsonNode value) {
        return switch (type) {
            case SELF_CHECK -> {
                fields(value, "prompt", "reference");
                yield new SelfCheck(Slot.PROMPT.read(value.path("prompt"), 1, MAX_PROMPT_BLOCKS),
                        Slot.REFERENCE.read(value.path("reference"), 1, MAX_PROMPT_BLOCKS));
            }
            case FREE_RESPONSE -> {
                fields(value, "prompt", "reference", "responseInput");
                yield new FreeResponse(Slot.PROMPT.read(value.path("prompt"), 1, MAX_PROMPT_BLOCKS),
                        Slot.REFERENCE.read(value.path("reference"), 0, MAX_PROMPT_BLOCKS),
                        oneOf(value.path("responseInput"), RESPONSE_INPUTS));
            }
            case CLOZE -> {
                fields(value, "prompt", "passage");
                yield new Cloze(Slot.PROMPT.read(value.path("prompt"), 0, MAX_PROMPT_BLOCKS),
                        passage(value.path("passage")));
            }
            case CHOICE -> {
                fields(value, "prompt", "selectionMode", "options");
                yield new Choice(Slot.PROMPT.read(value.path("prompt"), 1, MAX_PROMPT_BLOCKS),
                        oneOf(value.path("selectionMode"), Set.of("SINGLE", "MULTIPLE")).equals("MULTIPLE"),
                        options(value.path("options")));
            }
            case MATCH -> {
                fields(value, "prompt", "left", "right");
                List<Item> left = items(value.path("left"));
                List<Item> right = items(value.path("right"));
                Set<UUID> ids = new HashSet<>();
                if (left.size() != right.size() || Stream.concat(left.stream(), right.stream())
                        .anyMatch(item -> !ids.add(item.itemId()))) throw invalid();
                yield new Match(Slot.PROMPT.read(value.path("prompt"), 0, MAX_PROMPT_BLOCKS), left, right);
            }
        };
    }

    private static List<Segment> passage(JsonNode node) {
        List<Segment> segments = new ArrayList<>();
        Set<UUID> blanks = new HashSet<>();
        int text = 0;
        int literals = 0;
        for (JsonNode segment : array(node, 2, MAX_PASSAGE_SEGMENTS)) {
            String kind = segment.path("kind").textValue();
            if ("TEXT".equals(kind)) {
                fields(segment, "kind", "text");
                // Whitespace-only segments are legitimate (indentation between two blanks).
                if (!segment.path("text").isTextual() || segment.path("text").textValue().isEmpty()) throw invalid();
                text += segment.path("text").textValue().length();
                literals++;
                segments.add(new Literal(segment.path("text").textValue()));
            } else if ("BLANK".equals(kind)) {
                fields(segment, "kind", "blankId", "size", "firstLetterHint");
                UUID blankId = id(segment, "blankId");
                if (!blanks.add(blankId)) throw invalid();
                JsonNode size = segment.path("size");
                boolean fixed = "FIXED".equals(size.path("mode").textValue());
                if (fixed) fields(size, "mode", "length"); else fields(size, "mode");
                if (!fixed && !"ANSWER_LENGTH".equals(size.path("mode").textValue())) throw invalid();
                segments.add(new Blank(blankId, fixed,
                        fixed ? integer(size.path("length"), MIN_FIXED_BLANK, MAX_FIXED_BLANK) : 0,
                        bool(segment.path("firstLetterHint"))));
            } else throw invalid();
        }
        if (literals == 0 || blanks.isEmpty() || blanks.size() > MAX_BLANKS || text > MAX_PASSAGE_TEXT) throw invalid();
        return List.copyOf(segments);
    }

    private static List<Option> options(JsonNode node) {
        List<Option> options = new ArrayList<>();
        Set<UUID> ids = new HashSet<>();
        for (JsonNode option : array(node, MIN_OPTIONS, MAX_OPTIONS)) {
            fields(option, "optionId", "blocks");
            UUID optionId = id(option, "optionId");
            if (!ids.add(optionId)) throw invalid();
            options.add(new Option(optionId, Slot.COMPACT.read(option.path("blocks"), 1, 2)));
        }
        return List.copyOf(options);
    }

    private static List<Item> items(JsonNode node) {
        List<Item> items = new ArrayList<>();
        for (JsonNode item : array(node, MIN_SIDE, MAX_SIDE)) {
            fields(item, "itemId", "blocks");
            items.add(new Item(id(item, "itemId"), Slot.COMPACT.read(item.path("blocks"), 1, 2)));
        }
        return List.copyOf(items);
    }

    /** Canonical (NFC) length in code points, the unit of an ANSWER_LENGTH blank. */
    static int answerLength(String value) {
        String canonical = Normalizer.normalize(value, Normalizer.Form.NFC);
        return canonical.codePointCount(0, canonical.length());
    }

    private static List<Block> concat(List<Block> first, List<Block> second) {
        return Stream.concat(first.stream(), second.stream()).toList();
    }
}
