package app.mnema.learning.usage;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The versioned rate card ({@code contracts/usage/rate-card-v1.json}, classpath copy {@code usage/rate-card-v1.json};
 * a test keeps the two identical). Weights are worst cases in credits. A revision is a new version that never applies
 * to ledger entries or reservations already carrying an older one, so a version is only ever added, never edited.
 */
@Component
final class RateCard {
    /** The 0.6 of the contract: an rc-v1 placeholder until measured weights exist (14 days of measurements). */
    static final BigDecimal TYPICAL_FACTOR = new BigDecimal("0.6");
    static final String EXERCISES = "EXERCISES_PER_MATERIAL";
    /** The operation a plan-first spec adds and the planner debits: the Flash plan (the Pro plan is not selectable in v1). */
    static final String PLAN = "SMART_PLAN_FLASH";
    private static final int EXERCISES_PER_UNIT = 5;

    enum Pricing { CREDITS, FAIR_USE }

    enum Availability { AVAILABLE, LATER, DEFERRED }

    /**
     * @param credits the weight, null for fair-use and for the deferred video range
     * @param cap     the count cap or fair-use bucket the operation also consumes, null for none (or an unmodelled one)
     */
    record Operation(String id, Pricing pricing, Integer credits, Bucket cap, Availability availability) { }

    private final String version;
    private final Map<String, Operation> operations;
    private final Map<String, String> actionOperations;

    @Autowired
    RateCard(UsagePolicy policy) {
        this(read("usage/rate-card-" + policy.rateCardVersion.substring(3) + ".json"), policy.rateCardVersion);
    }

    RateCard(JsonNode document, String expectedVersion) {
        this.version = document.path("rateCardVersion").stringValue(null);
        if (!expectedVersion.equals(version)) throw new IllegalStateException("Rate card version mismatch");
        Map<String, Operation> parsed = new LinkedHashMap<>();
        for (JsonNode node : document.path("operations")) {
            String id = node.path("id").stringValue(null);
            Pricing pricing = Pricing.valueOf(node.path("pricing").stringValue(null));
            JsonNode credits = node.path("credits");
            Integer weight = credits.isNumber() ? credits.intValue() : null;
            if (pricing == Pricing.CREDITS && weight == null && !credits.isObject()) {
                throw new IllegalStateException("Rate card operation without a weight");
            }
            parsed.put(id, new Operation(id, pricing, weight, capOf(node.path("cap").stringValue(null)),
                    Availability.valueOf(node.path("availability").stringValue(null))));
        }
        this.operations = Map.copyOf(parsed);
        Map<String, String> actions = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : document.path("actionOperations").properties()) {
            if (entry.getKey().equals("description") || entry.getKey().equals("notes")) continue;
            actions.put(entry.getKey(), entry.getValue().stringValue(null));
        }
        this.actionOperations = actions;
    }

    private static Bucket capOf(String name) {
        if (name == null || name.equals("video")) return null;
        return Bucket.ofCap(name);
    }

    private static JsonNode read(String resource) {
        try (InputStream stream = RateCard.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) throw new IllegalStateException("Unknown rate card version");
            return JsonMapper.builder().build().readTree(stream);
        } catch (IOException | JacksonException failure) {
            throw new IllegalStateException("Unreadable rate card", failure);
        }
    }

    String version() {
        return version;
    }

    Operation operation(String id) {
        Operation operation = operations.get(id);
        if (operation == null) throw new IllegalArgumentException("Unknown operation " + id);
        return operation;
    }

    /**
     * The operation an edit action charges: empty for a free action ({@code REMOVE_MEDIA}).
     *
     * @throws IllegalArgumentException for an action the card does not list
     */
    Optional<Operation> forAction(String action) {
        if (!actionOperations.containsKey(action)) throw new IllegalArgumentException("Unknown action");
        String id = actionOperations.get(action);
        return id == null ? Optional.empty() : Optional.of(operation(id));
    }

    /**
     * The exact cost of {@code count} units before rounding: exercises are priced per exercise, 8 credits per five.
     *
     * @throws IllegalArgumentException for a fair-use or deferred operation, which has no credit weight
     */
    BigDecimal exactCredits(String id, int count) {
        Operation operation = operation(id);
        if (operation.credits() == null) throw new IllegalArgumentException("Operation is not priced in credits");
        BigDecimal total = BigDecimal.valueOf((long) operation.credits() * count);
        return id.equals(EXERCISES) ? total.divide(BigDecimal.valueOf(EXERCISES_PER_UNIT)) : total;
    }

    /** The p95 cost of {@code count} units: a fractional weight is rounded up when it becomes a debit. */
    int credits(String id, int count) {
        return exactCredits(id, count).setScale(0, RoundingMode.CEILING).intValueExact();
    }
}
