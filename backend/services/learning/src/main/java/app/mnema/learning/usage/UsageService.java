package app.mnema.learning.usage;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/** Builds {@code GET /api/usage}: a pure read of the owner's period, never a write. */
@Service
class UsageService {
    private final UsageState state;
    private final UsagePolicy policy;
    private final RateCard rateCard;
    private final UsageClock clock;

    UsageService(UsageState state, UsagePolicy policy, RateCard rateCard, UsageClock clock) {
        this.state = state;
        this.policy = policy;
        this.rateCard = rateCard;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    UsageView read(UUID owner) {
        Instant now = clock.now();
        UsageState.Resolved resolved = state.resolve(owner, now);
        Allowance allowance = resolved.allowance();
        UsageState.Credits credits = state.credits(resolved);
        UsageCalendar.Period period = resolved.period();
        return new UsageView(rateCard.version(), allowance.plan().name(),
                new UsageView.PeriodView(period.id(), Wire.time(period.start()), Wire.time(period.end())),
                new UsageView.EntitlementView(resolved.entitlement().source().name(),
                        Wire.time(resolved.entitlement().validUntil())),
                new UsageView.CreditsView(credits.total(), credits.unlocked(), credits.used(), credits.reserved(),
                        credits.remaining(), Wire.percent((long) credits.used() + credits.reserved(), credits.total())),
                state.burst(resolved).map(burst -> new UsageView.DailyBurstView(burst.limitCredits(),
                        burst.debitedTodayCredits(), burst.remainingTodayCredits(), Wire.time(burst.resetsAt()), null))
                        .orElse(null),
                allowance.weekly() ? weekly(allowance, credits, resolved) : null,
                new UsageView.FairUseView(stt(resolved, now), assessment(resolved, now)),
                new UsageView.CapsView(cap(resolved, Bucket.PODCASTS, now), cap(resolved, Bucket.QUALITY_IMAGES, now),
                        cap(resolved, Bucket.HIGH_FACTCHECK, now), cap(resolved, Bucket.SMART_PLAN, now)),
                Wire.time(now));
    }

    private UsageView.WeeklyUnlockView weekly(Allowance allowance, UsageState.Credits credits,
                                              UsageState.Resolved resolved) {
        int opened = state.openedPortions(allowance, resolved.period(), resolved.now());
        boolean more = credits.window() == Window.WEEK;
        return new UsageView.WeeklyUnlockView(allowance.portions(), opened, more ? Wire.time(credits.renewsAt()) : null,
                more ? allowance.portions().get(opened) : null);
    }

    private UsageView.SttView stt(UsageState.Resolved resolved, Instant now) {
        Allowance allowance = resolved.allowance();
        UUID owner = resolved.owner();
        long month = state.counter(owner, Bucket.STT, Window.MONTH, now);
        long day = state.counter(owner, Bucket.STT, Window.DAY, now);
        boolean velocity = allowance.sttMonth() == null;
        Long limit = velocity ? null : UsageState.display(Bucket.STT, allowance.sttMonth(), false);
        long usedDay = UsageState.display(Bucket.STT, day, true);
        long dayLimit = UsageState.display(Bucket.STT, allowance.sttDay(), false);
        boolean warn = !velocity && month * 100 > (long) policy.lowThresholdPercent * allowance.sttMonth();
        return new UsageView.SttView("MINUTES", UsageState.display(Bucket.STT, month, true), limit, usedDay,
                velocity ? null : dayLimit, warn, month, velocity ? dayLimit : null, velocity ? usedDay : null);
    }

    private UsageView.AssessmentView assessment(UsageState.Resolved resolved, Instant now) {
        Allowance allowance = resolved.allowance();
        long month = state.counter(resolved.owner(), Bucket.ASSESSMENT, Window.MONTH, now);
        long day = state.counter(resolved.owner(), Bucket.ASSESSMENT, Window.DAY, now);
        boolean warn = month * 100 > (long) policy.lowThresholdPercent * allowance.assessmentMonth();
        return new UsageView.AssessmentView("ANSWERS", month, allowance.assessmentMonth(), day,
                allowance.assessmentDay(), warn);
    }

    private UsageView.CapView cap(UsageState.Resolved resolved, Bucket bucket, Instant now) {
        Allowance.WindowLimit limit = resolved.allowance().limits(bucket).getFirst();
        return new UsageView.CapView(state.counter(resolved.owner(), bucket, limit.window(), now), limit.limit(),
                limit.window().name());
    }
}
