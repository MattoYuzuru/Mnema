package app.mnema.learning.usage;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * What the generation module asks usage to price: the hold of a session's initial batch, and the weight of one
 * material. Usage owns the rate card and the spec interpretation; the generation module never reads either.
 */
@Component
public final class AdmissionPricing {
    private final EstimateService estimates;

    AdmissionPricing(EstimateService estimates) {
        this.estimates = estimates;
    }

    /**
     * What to reserve for {@code spec} and the usage refusal to raise last. {@code credits} is the p95 of the spec, capped by
     * {@code settings.budgetPercent} of the remaining budget; {@code block} is set when a count cap is full or the hold cannot
     * pay for one material. Credits themselves are checked by {@link UsageLedger#reserve}. Runs the same interpretation as
     * the estimate, plus the admission-only check of stale pins.
     */
    public Hold hold(UUID owner, UUID deckId, JsonNode spec) {
        EstimateService.Hold hold = estimates.hold(owner, deckId, spec);
        return new Hold(hold.credits(), hold.block(), hold.exercises(), hold.planCredits());
    }

    /**
     * @see #hold
     * @param credits the hold of the batch: what the spec costs without a plan, capped by {@code budgetPercent}
     * @param exercises for an {@code EXERCISES} spec the resolved quantity (the spec's mode, the limits and, for
     *                  {@code BUDGET_PERCENT}, the budget decide it); zero for every other spec
     * @param planCredits what a plan-first spec adds for its plan ({@code SMART_PLAN_FLASH}), held and debited apart from the batch; zero without one
     */
    public record Hold(int credits, UsageLimitReachedException.Block block, int exercises, int planCredits) {
        public Hold(int credits, UsageLimitReachedException.Block block, int exercises) {
            this(credits, block, exercises, 0);
        }

        /** Raises the refusal, if there is one; call it as the very last admission check before the reservation. */
        public void requireFits() {
            if (block != null) throw new UsageLimitReachedException(block);
        }
    }

    /** The rate-card operation that charges one material of this effort ({@code AUTO} is charged as {@code MEDIUM}). */
    public static String materialOperation(String effort) {
        return switch (effort) {
            case "SHORT" -> "MATERIAL_SHORT";
            case "DETAILED" -> "MATERIAL_DETAILED";
            default -> "MATERIAL_MEDIUM";
        };
    }

    /** The rate-card operation the plan of a plan-first spec is debited under. */
    public static final String PLAN_OPERATION = RateCard.PLAN;

    /** The bar of the owner's current period (the whole credit amount the usage percentages are a share of). */
    public int barCredits(UUID owner) {
        return estimates.barCredits(owner);
    }

    /** What one run of {@code operation} charges (the weight of the rate card in force). */
    public int credits(String operation) {
        return estimates.credits(operation);
    }

    /** What {@code count} generated exercises charge together: 8 credits per five, rounded up (the rate card's exercise unit). */
    public int exerciseCredits(int count) {
        return estimates.exerciseCredits(count);
    }
}
