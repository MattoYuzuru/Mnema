package app.mnema.learning.usage;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** The body of {@code GET /api/usage} ({@code contracts/usage/usage.json#/usageResponse}). */
public record UsageView(String rateCardVersion, String plan, PeriodView period, EntitlementView entitlement,
                        CreditsView credits, DailyBurstView dailyBurst, WeeklyUnlockView weeklyUnlock,
                        FairUseView fairUse, CapsView caps, String updatedAt) {

    public record PeriodView(String periodId, String start, String end) { }

    public record EntitlementView(String source, String validUntil) { }

    public record CreditsView(int total, int unlocked, int used, int reserved, int remaining, int percentUsed) { }

    /** Paid plans only. {@code deferredUntil} is set by the step scheduler's own state, never by this read. */
    public record DailyBurstView(int limitCredits, int debitedTodayCredits, int remainingTodayCredits, String resetsAt,
                                 String deferredUntil) { }

    /** Free only; {@code nextUnlockAt} and {@code nextPortionCredits} are null once the whole bar is unlocked. */
    public record WeeklyUnlockView(List<Integer> portions, int unlockedPortions, String nextUnlockAt,
                                   Integer nextPortionCredits) { }

    public record FairUseView(SttView stt, AssessmentView assessment) { }

    /**
     * Speech to text in whole minutes rounded up; {@code usedSeconds} is the metered value. A plan without a monthly
     * limit has {@code limit} null and carries its velocity limit instead.
     */
    public record SttView(String unit, long used, Long limit, long usedToday, Long limitToday, boolean warn,
                          long usedSeconds,
                          @JsonInclude(JsonInclude.Include.NON_NULL) Long velocityPerDay,
                          @JsonInclude(JsonInclude.Include.NON_NULL) Long usedTodayVelocity) { }

    public record AssessmentView(String unit, long used, long limit, long usedToday, long limitToday, boolean warn) { }

    public record CapsView(CapView podcasts, CapView qualityImages, CapView highFactcheck, CapView smartPlan) { }

    /** A limit of 0 means the plan does not offer it. */
    public record CapView(long used, long limit, String window) { }
}
