package app.mnema.learning.catalog.exercise;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static app.mnema.learning.catalog.exercise.StrictJson.array;
import static app.mnema.learning.catalog.exercise.StrictJson.distinctIds;
import static app.mnema.learning.catalog.exercise.StrictJson.fields;
import static app.mnema.learning.catalog.exercise.StrictJson.id;
import static app.mnema.learning.catalog.exercise.StrictJson.invalid;

/**
 * Private evaluation authority of one exercise revision. The {@code kind} discriminator must agree with
 * the mechanic; keys never reach a learner presentation.
 */
public sealed interface AnswerKey {
    int MAX_TEXT_ACCEPTED = 20;
    int MAX_TEXT_LENGTH = 512;
    int MAX_BLANK_ACCEPTED = 10;
    int MAX_BLANK_LENGTH = 200;
    int MAX_ANSWER_LENGTH = 80;

    record SelfReport() implements AnswerKey { }
    record Text(TextRule rule) implements AnswerKey { }
    record Cloze(List<BlankKey> blanks) implements AnswerKey { }
    record Choice(List<UUID> correctOptionIds) implements AnswerKey { }
    record Match(List<Pair> pairs) implements AnswerKey { }
    /** The explicit correct sequence of every item identifier; order is never inferred. */
    record Order(List<UUID> sequence) implements AnswerKey { }
    /** Every item mapped to exactly one category; categories may repeat or stay empty. */
    record Categorize(List<Assignment> assignments) implements AnswerKey { }
    record Assignment(UUID itemId, UUID categoryId) { }
    record BlankKey(UUID blankId, TextRule rule) { }
    record Pair(UUID leftId, UUID rightId) { }

    /** Strict exact-field parse; the kind must be the one this mechanic uses. */
    static AnswerKey parse(ExerciseType type, JsonNode value) {
        if (!value.isObject() || !value.path("kind").isTextual()
                || !expectedKind(type).equals(value.path("kind").textValue())) throw invalid();
        return switch (type) {
            case SELF_CHECK -> {
                fields(value, "kind");
                yield new SelfReport();
            }
            case FREE_RESPONSE -> {
                fields(value, "kind", "accepted", "normalization", "matchingMode");
                yield new Text(TextRule.read(value, MAX_TEXT_ACCEPTED, MAX_TEXT_LENGTH));
            }
            case CLOZE -> {
                fields(value, "kind", "blanks");
                List<BlankKey> blanks = new ArrayList<>();
                Set<UUID> ids = new HashSet<>();
                for (JsonNode blank : array(value.path("blanks"), 1, ExerciseContent.MAX_BLANKS)) {
                    fields(blank, "blankId", "accepted", "normalization", "matchingMode");
                    UUID blankId = id(blank, "blankId");
                    if (!ids.add(blankId)) throw invalid();
                    blanks.add(new BlankKey(blankId, TextRule.read(blank, MAX_BLANK_ACCEPTED, MAX_BLANK_LENGTH)));
                }
                yield new Cloze(List.copyOf(blanks));
            }
            case CHOICE -> {
                fields(value, "kind", "correctOptionIds");
                yield new Choice(distinctIds(array(value.path("correctOptionIds"), 1, ExerciseContent.MAX_OPTIONS)));
            }
            case MATCH -> {
                fields(value, "kind", "pairs");
                List<Pair> pairs = new ArrayList<>();
                Set<UUID> lefts = new HashSet<>();
                Set<UUID> rights = new HashSet<>();
                for (JsonNode pair : array(value.path("pairs"), ExerciseContent.MIN_SIDE, ExerciseContent.MAX_SIDE)) {
                    fields(pair, "leftId", "rightId");
                    Pair parsed = new Pair(id(pair, "leftId"), id(pair, "rightId"));
                    if (!lefts.add(parsed.leftId()) || !rights.add(parsed.rightId())) throw invalid();
                    pairs.add(parsed);
                }
                yield new Match(List.copyOf(pairs));
            }
            case ORDER -> {
                fields(value, "kind", "sequence");
                yield new Order(distinctIds(array(value.path("sequence"), ExerciseContent.MIN_ORDER_ITEMS,
                        ExerciseContent.MAX_ORDER_ITEMS)));
            }
            case CATEGORIZE -> {
                fields(value, "kind", "assignments");
                List<Assignment> assignments = new ArrayList<>();
                Set<UUID> items = new HashSet<>();
                for (JsonNode assignment : array(value.path("assignments"), ExerciseContent.MIN_CATEGORIZE_ITEMS,
                        ExerciseContent.MAX_CATEGORIZE_ITEMS)) {
                    fields(assignment, "itemId", "categoryId");
                    Assignment parsed = new Assignment(id(assignment, "itemId"), id(assignment, "categoryId"));
                    if (!items.add(parsed.itemId())) throw invalid();
                    assignments.add(parsed);
                }
                yield new Categorize(List.copyOf(assignments));
            }
        };
    }

    static String expectedKind(ExerciseType type) {
        return switch (type) {
            case SELF_CHECK -> "SELF_REPORT";
            case FREE_RESPONSE -> "TEXT";
            default -> type.name();
        };
    }

    /**
     * Agreement between key and content: blanks, options, sides and items name exactly the same identifiers,
     * every CATEGORIZE item sits in an existing category, and an ANSWER_LENGTH blank has one well-defined width.
     */
    static void requireConsistent(ExerciseContent content, AnswerKey key) {
        switch (content) {
            case ExerciseContent.Cloze cloze -> {
                Cloze blanks = (Cloze) key;
                Set<UUID> keyed = new HashSet<>();
                blanks.blanks().forEach(blank -> keyed.add(blank.blankId()));
                Set<UUID> passage = new HashSet<>();
                cloze.blanks().forEach(blank -> passage.add(blank.blankId()));
                if (!keyed.equals(passage) || keyed.size() != blanks.blanks().size()) throw invalid();
                for (ExerciseContent.Blank blank : cloze.blanks()) {
                    if (blank.fixed()) continue;
                    TextRule rule = blanks.blanks().stream().filter(candidate -> candidate.blankId().equals(blank.blankId()))
                            .findFirst().orElseThrow().rule();
                    int length = ExerciseContent.answerLength(rule.accepted().getFirst());
                    if (length < 1 || length > MAX_ANSWER_LENGTH || rule.accepted().stream()
                            .anyMatch(accepted -> ExerciseContent.answerLength(accepted) != length)) throw invalid();
                }
            }
            case ExerciseContent.Choice choice -> {
                Set<UUID> options = new HashSet<>();
                choice.options().forEach(option -> options.add(option.optionId()));
                List<UUID> correct = ((Choice) key).correctOptionIds();
                if (!options.containsAll(correct) || (!choice.multiple() && correct.size() != 1)) throw invalid();
            }
            case ExerciseContent.Match match -> {
                Set<UUID> lefts = new HashSet<>();
                Set<UUID> rights = new HashSet<>();
                match.left().forEach(item -> lefts.add(item.itemId()));
                match.right().forEach(item -> rights.add(item.itemId()));
                List<MappingRules.Link> links = ((Match) key).pairs().stream()
                        .map(pair -> new MappingRules.Link(pair.leftId(), pair.rightId())).toList();
                if (!MappingRules.bijection(links, lefts, rights)) throw invalid();
            }
            case ExerciseContent.Order order -> {
                Set<UUID> items = new HashSet<>();
                order.items().forEach(item -> items.add(item.itemId()));
                // distinct ids of the same size, so equal sets mean an exact permutation
                List<UUID> sequence = ((Order) key).sequence();
                if (sequence.size() != items.size() || !items.containsAll(sequence)) throw invalid();
            }
            case ExerciseContent.Categorize categorize -> {
                Set<UUID> items = new HashSet<>();
                Set<UUID> categories = new HashSet<>();
                categorize.items().forEach(item -> items.add(item.itemId()));
                categorize.categories().forEach(category -> categories.add(category.categoryId()));
                List<MappingRules.Link> links = ((Categorize) key).assignments().stream()
                        .map(assignment -> new MappingRules.Link(assignment.itemId(), assignment.categoryId())).toList();
                if (!MappingRules.totalManyToOne(links, items, categories)) throw invalid();
            }
            default -> { }
        }
    }
}
