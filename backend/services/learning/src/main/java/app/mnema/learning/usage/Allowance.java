package app.mnema.learning.usage;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * What one plan grants for one period ({@code contracts/usage/allowances-v1.json} with the policy's owner decisions
 * applied). It is persisted in {@code usage_allowance} when a period is first used, and enforcement reads the stored
 * row, so a catalog change never moves the limits of a period that already started.
 *
 * @param portions      Free only: the weekly portions of the bar; null when the whole bar is available at once
 * @param burstFraction paid plans only: the share of the bar that one calendar day may debit; null on Free
 * @param sttMonth      speech-to-text seconds per month; null when unlimited within fair use
 * @param sttDay        speech-to-text seconds per day; on a plan without a monthly limit this is the velocity limit
 * @param assessmentMonth answer checks per month
 * @param assessmentDay   answer checks per day
 * @param smartPlanLimit  smart plans per {@code smartPlanWindow}
 */
record Allowance(Plan plan, int credits, List<Integer> portions, BigDecimal burstFraction, Long sttMonth, Long sttDay,
                 long assessmentMonth, long assessmentDay, int podcasts, int qualityImages, int highFactcheck,
                 int smartPlanLimit, Window smartPlanWindow) {

    /** One limit of a bucket: {@code limit} is null when unlimited and 0 when the plan does not offer the bucket. */
    record WindowLimit(Window window, Long limit) { }

    Allowance {
        portions = portions == null ? null : List.copyOf(portions);
    }

    /** The windows a consumption of {@code bucket} is checked against, widest first; empty only for the bar. */
    List<WindowLimit> limits(Bucket bucket) {
        List<WindowLimit> limits = new ArrayList<>(2);
        switch (bucket) {
            case STT -> {
                limits.add(new WindowLimit(Window.MONTH, sttMonth));
                limits.add(new WindowLimit(Window.DAY, sttDay));
            }
            case ASSESSMENT -> {
                limits.add(new WindowLimit(Window.MONTH, assessmentMonth));
                limits.add(new WindowLimit(Window.DAY, assessmentDay));
            }
            case PODCASTS -> limits.add(new WindowLimit(Window.MONTH, (long) podcasts));
            case QUALITY_IMAGES -> limits.add(new WindowLimit(Window.MONTH, (long) qualityImages));
            case HIGH_FACTCHECK -> limits.add(new WindowLimit(Window.MONTH, (long) highFactcheck));
            case SMART_PLAN -> limits.add(new WindowLimit(smartPlanWindow, (long) smartPlanLimit));
            case CREDITS, DAILY_BURST -> throw new IllegalArgumentException("Bucket has no consumption windows");
        }
        return limits;
    }

    /** Free: the bar opens in portions. */
    boolean weekly() {
        return portions != null;
    }
}
