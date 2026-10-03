package app.mnema.learning.catalog.exercise;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static app.mnema.learning.catalog.exercise.StrictJson.array;
import static app.mnema.learning.catalog.exercise.StrictJson.fields;
import static app.mnema.learning.catalog.exercise.StrictJson.integer;
import static app.mnema.learning.catalog.exercise.StrictJson.invalid;
import static app.mnema.learning.catalog.exercise.StrictJson.nonBlank;
import static app.mnema.learning.catalog.exercise.StrictJson.oneOf;

/**
 * Rubric v1 of the {@code ai-semantic} evaluator ({@code contracts/study}, AI assessment): a reference answer, 3..9 key points
 * in three tiers, typical misconceptions and acceptable terms. The tier shape is what the server's strictness policy
 * ({@code ai-semantic-v1}) aggregates: 2..3 {@link Tier#CORE} (the essence), 1..4 {@link Tier#DETAIL} (completeness) and 0..2
 * {@link Tier#TERM} (terminology), each point weighing 1..3 inside its tier. The model never sees tiers or weights.
 *
 * <p>The rubric is author data stored in the immutable evaluator policy of an exercise revision; a learner presentation never
 * carries it.
 */
public record Rubric(String referenceAnswer, List<Criterion> criteria, List<String> misconceptions,
                     List<String> acceptableTerms) {
    public static final int MAX_REFERENCE = 4_000;
    public static final int MAX_CRITERIA = 10;
    public static final int MAX_DESCRIPTION = 500;
    public static final int MAX_MISCONCEPTIONS = 10;
    public static final int MAX_MISCONCEPTION = 300;
    public static final int MAX_TERMS = 30;
    public static final int MAX_TERM = 80;
    public static final int MIN_WEIGHT = 1;
    public static final int MAX_WEIGHT = 3;

    public Rubric {
        criteria = List.copyOf(criteria);
        misconceptions = List.copyOf(misconceptions);
        acceptableTerms = List.copyOf(acceptableTerms);
    }

    /** What a key point is for: the essence, the completeness or the terminology of an explanation. */
    public enum Tier {
        CORE(2, 3), DETAIL(1, 4), TERM(0, 2);

        private final int min;
        private final int max;

        Tier(int min, int max) {
            this.min = min;
            this.max = max;
        }
    }

    public record Criterion(UUID criterionId, String description, Tier tier, int weight) { }

    /** The points of one tier, in authored order. */
    public List<Criterion> of(Tier tier) { return criteria.stream().filter(point -> point.tier() == tier).toList(); }

    /**
     * Strict parse of a rubric: exact fields, the tier counts above, distinct criterion ids, bounded text. Every failure is
     * the same opaque {@code INVALID_REQUEST}.
     */
    public static Rubric read(JsonNode value) {
        fields(value, "referenceAnswer", "criteria", "misconceptions", "acceptableTerms");
        String reference = nonBlank(value.path("referenceAnswer"), MAX_REFERENCE);
        List<Criterion> criteria = new ArrayList<>();
        Set<UUID> ids = new HashSet<>();
        for (JsonNode criterion : array(value.path("criteria"), 3, MAX_CRITERIA)) {
            fields(criterion, "criterionId", "description", "tier", "weight");
            UUID id = StrictJson.id(criterion, "criterionId");
            if (!ids.add(id)) throw invalid();
            Tier tier = Tier.valueOf(oneOf(criterion.path("tier"), Set.of("CORE", "DETAIL", "TERM")));
            criteria.add(new Criterion(id, nonBlank(criterion.path("description"), MAX_DESCRIPTION), tier,
                    integer(criterion.path("weight"), MIN_WEIGHT, MAX_WEIGHT)));
        }
        for (Tier tier : Tier.values()) {
            long count = criteria.stream().filter(point -> point.tier() == tier).count();
            if (count < tier.min || count > tier.max) throw invalid();
        }
        return new Rubric(reference, criteria, strings(value.path("misconceptions"), MAX_MISCONCEPTIONS, MAX_MISCONCEPTION),
                strings(value.path("acceptableTerms"), MAX_TERMS, MAX_TERM));
    }

    private static List<String> strings(JsonNode node, int maxCount, int maxLength) {
        List<String> values = new ArrayList<>();
        for (JsonNode item : array(node, 0, maxCount)) values.add(nonBlank(item, maxLength));
        return values;
    }
}
