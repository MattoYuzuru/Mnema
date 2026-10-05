package app.mnema.learning.usage;

import java.util.List;
import java.util.Map;

/**
 * The body of {@code GET /api/plans}: what the paywall shows. Prices and highlights are computed from configuration and
 * {@code contracts/usage/allowances-v1.json}; nothing here grants anything. {@code experiments} maps each enabled A/B experiment to the variant the
 * server assigned to the owner.
 */
public record PlansView(CurrentView current, List<PlanView> plans, Map<String, String> experiments, PendingDiscount pendingDiscount) {

    /**
     * The discount a promo code earned, waiting for a purchase (#79); {@code plan} is {@code PLUS}, {@code PRO} or null for either. The
     * response member is null when there is none.
     */
    public record PendingDiscount(int percent, String plan, String validUntil) { }

    /** The entitlement now. {@code autoRenew} is always false until recurring payments exist (#79). */
    public record CurrentView(String plan, String period, String validUntil, boolean autoRenew, String source) { }

    /**
     * @param availability  {@code AVAILABLE}, or {@code TEASER} for a tier that is shown as «в работе» and cannot be chosen
     * @param perDayRub     the monthly price over 30 days, whole rubles
     * @param highlights    three human-unit lines, ready to show
     * @param recommendedFor learning goals that point at this tier
     */
    public record PlanView(String plan, String availability, PriceView priceRub, int perDayRub, int yearDiscountPercent,
                           List<String> highlights, AllowanceTable allowances, List<String> recommendedFor) { }

    /** Whole rubles; {@code year} is the full price of the year after the discount. */
    public record PriceView(int month, int year) { }

    /** The comparison table. A null limit is unlimited within fair use; a 0 means the tier does not offer it. */
    public record AllowanceTable(Range materialsPerMonth, Long voiceMinutesPerMonth, long voiceMinutesPerDay,
                                 long answerChecksPerMonth, long answerChecksPerDay, int podcastsPerMonth,
                                 int qualityImagesPerMonth, int factChecksPerMonth, SmartPlans smartPlans) { }

    public record Range(int min, int max) { }

    /** {@code window} is {@code WEEK} or {@code MONTH}; a limit of 0 means no smart plans. */
    public record SmartPlans(int limit, String window) { }
}
