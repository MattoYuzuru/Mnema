package app.mnema.learning.catalog.exercise;

import com.fasterxml.jackson.databind.JsonNode;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.UUID;
import java.util.stream.Stream;

import static app.mnema.learning.catalog.exercise.StrictJson.array;
import static app.mnema.learning.catalog.exercise.StrictJson.bool;
import static app.mnema.learning.catalog.exercise.StrictJson.fields;
import static app.mnema.learning.catalog.exercise.StrictJson.id;
import static app.mnema.learning.catalog.exercise.StrictJson.integer;
import static app.mnema.learning.catalog.exercise.StrictJson.invalid;
import static app.mnema.learning.catalog.exercise.StrictJson.nonBlank;
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
    int MIN_ORDER_ITEMS = 2;
    int MAX_ORDER_ITEMS = 12;
    int MIN_CATEGORIES = 2;
    int MAX_CATEGORIES = 6;
    int MIN_CATEGORIZE_ITEMS = 2;
    int MAX_CATEGORIZE_ITEMS = 12;
    int MAX_CATEGORY_LABEL = 80;
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

    /** Items in authored order; the answer key is the explicit correct sequence of their identifiers. */
    record Order(List<Block> prompt, List<Item> items) implements ExerciseContent {
        @Override public List<Block> blocks() { return withItems(prompt, items); }
    }

    /** Categories keep their authored order (display only); items are assigned by identifier. */
    record Categorize(List<Block> prompt, List<Category> categories, List<Item> items) implements ExerciseContent {
        @Override public List<Block> blocks() { return withItems(prompt, items); }
    }

    sealed interface Segment { }
    record Literal(String text) implements Segment { }
    record Blank(UUID blankId, boolean fixed, int length, boolean firstLetterHint) implements Segment { }
    record Option(UUID optionId, List<Block> blocks) { }
    record Item(UUID itemId, List<Block> blocks) { }
    record Category(UUID categoryId, String label) { }

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
                List<Item> left = items(value.path("left"), Slot.COMPACT, MIN_SIDE, MAX_SIDE);
                List<Item> right = items(value.path("right"), Slot.COMPACT, MIN_SIDE, MAX_SIDE);
                Set<UUID> ids = new HashSet<>();
                if (left.size() != right.size() || Stream.concat(left.stream(), right.stream())
                        .anyMatch(item -> !ids.add(item.itemId()))) throw invalid();
                yield new Match(Slot.PROMPT.read(value.path("prompt"), 0, MAX_PROMPT_BLOCKS), left, right);
            }
            case ORDER -> {
                fields(value, "prompt", "items");
                List<Item> items = items(value.path("items"), Slot.SEQUENCE, MIN_ORDER_ITEMS, MAX_ORDER_ITEMS);
                // fewer than two distinguishable tiles would make every arrangement trivially correct
                if (OrderEquivalence.signatures(value.path("items")).values().stream().distinct().count() < 2) {
                    throw invalid();
                }
                yield new Order(Slot.PROMPT.read(value.path("prompt"), 0, MAX_PROMPT_BLOCKS), items);
            }
            case CATEGORIZE -> {
                fields(value, "prompt", "categories", "items");
                yield new Categorize(Slot.PROMPT.read(value.path("prompt"), 0, MAX_PROMPT_BLOCKS),
                        categories(value.path("categories")),
                        items(value.path("items"), Slot.COMPACT, MIN_CATEGORIZE_ITEMS, MAX_CATEGORIZE_ITEMS));
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

    /** Items of one ORDER or CATEGORIZE exercise: unique identifiers, tiles of the given slot profile. */
    private static List<Item> items(JsonNode node, Slot slot, int min, int max) {
        List<Item> items = new ArrayList<>();
        Set<UUID> ids = new HashSet<>();
        for (JsonNode item : array(node, min, max)) {
            fields(item, "itemId", "blocks");
            UUID itemId = id(item, "itemId");
            if (!ids.add(itemId)) throw invalid();
            items.add(new Item(itemId, slot.read(item.path("blocks"), 1, 2)));
        }
        return List.copyOf(items);
    }

    /** Unique identifiers and labels: plain nonblank text, distinct after trimming and case folding. */
    private static List<Category> categories(JsonNode node) {
        List<Category> categories = new ArrayList<>();
        Set<UUID> ids = new HashSet<>();
        Set<String> labels = new HashSet<>();
        for (JsonNode category : array(node, MIN_CATEGORIES, MAX_CATEGORIES)) {
            fields(category, "categoryId", "label");
            UUID categoryId = id(category, "categoryId");
            String label = nonBlank(category.path("label"), MAX_CATEGORY_LABEL);
            String folded = foldLabel(label);
            if (folded.isEmpty() || !ids.add(categoryId) || !labels.add(folded)) throw invalid();
            categories.add(new Category(categoryId, label));
        }
        return List.copyOf(categories);
    }

    Pattern INVISIBLE = Pattern.compile("[\\p{Z}\\s\\p{Cf}]");
    Pattern EDGE_INVISIBLE = Pattern.compile("^[\\p{Z}\\s\\p{Cf}]+|[\\p{Z}\\s\\p{Cf}]+$");

    /**
     * Comparison key of a category label: NFC, no leading or trailing Unicode whitespace or format characters
     * (NBSP, U+200B), then a full case fold approximated by upper- then lower-casing, so {@code ß}/{@code SS} and
     * {@code Σ}/{@code ς} collide. An empty result means the label shows nothing. The stored label stays verbatim.
     */
    static String foldLabel(String label) {
        String canonical = Normalizer.normalize(label, Normalizer.Form.NFC);
        if (INVISIBLE.matcher(canonical).replaceAll("").isEmpty()) return "";
        return EDGE_INVISIBLE.matcher(canonical).replaceAll("").toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT);
    }

    private static List<Block> withItems(List<Block> prompt, List<Item> items) {
        return Stream.concat(prompt.stream(), items.stream().flatMap(item -> item.blocks().stream())).toList();
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
