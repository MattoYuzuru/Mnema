package app.mnema.learning.update.plan;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * {@code learning.updates.plan.*} (Updates/3, #442, the update plan of a copy). Invalid values fail at startup.
 * <ul>
 *   <li>{@code compute-budget} [PT3S, PT0S-PT8S]: the time one plan computation may spend reading and decoding documents (the content fingerprint of a unit both sides changed, the node
 *       check of an exercise). When it is spent, every remaining comparison is judged conservatively (a conflict, the reason unchanged) and the plan is still answered, well inside the
 *       10 s transaction. The diff, the lookups and the sorting are not budgeted: they follow the number of changes and are measured in milliseconds. Under a spent budget two requests may
 *       classify the same unit differently (the budget is time); the apply command recomputes anyway and a conflict is only ever more cautious.</li>
 *   <li>{@code max-fingerprints} [200, 0-100000]: the pairs of contents ONE plan compares in all, in the order of the author's manifest (materials first, then exercises); past it the
 *       contents are assumed to differ. About 24 statements for a pair of materials.</li>
 *   <li>{@code max-concurrent} [2, 1-64]: plan and unit requests one instance computes at the same time; the next is refused at once with 503 {@code UPDATE_PLAN_BUSY} before any
 *       transaction ({@link UpdatePlanBulkhead}). {@link UpdatePlanConnectionBudget} checks at start that the pool has room for it.</li>
 * </ul>
 */
@Component
final class UpdatePlanSettings {
    final Duration computeBudget;
    final int maxFingerprints;
    final int maxConcurrent;

    UpdatePlanSettings(@Value("${learning.updates.plan.compute-budget:PT3S}") Duration computeBudget,
                       @Value("${learning.updates.plan.max-fingerprints:200}") int maxFingerprints,
                       @Value("${learning.updates.plan.max-concurrent:2}") int maxConcurrent) {
        if (computeBudget == null || computeBudget.isNegative() || computeBudget.compareTo(Duration.ofSeconds(8)) > 0
                || maxFingerprints < 0 || maxFingerprints > 100_000 || maxConcurrent < 1 || maxConcurrent > 64) {
            throw new IllegalArgumentException("Invalid learning.updates.plan settings");
        }
        this.computeBudget = computeBudget;
        this.maxFingerprints = maxFingerprints;
        this.maxConcurrent = maxConcurrent;
    }

    /** A fresh budget for one computation. */
    PlanBudget budget() { return new PlanBudget(computeBudget, maxFingerprints, System::nanoTime); }
}
