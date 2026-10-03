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
        return new Hold(hold.credits(), hold.block());
    }

    /** @see #hold */
    public record Hold(int credits, UsageLimitReachedException.Block block) {
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

    /** What one run of {@code operation} charges (the weight of the rate card in force). */
    public int credits(String operation) {
        return estimates.credits(operation);
    }
}
