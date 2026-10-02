package app.mnema.learning.usage;

import app.mnema.learning.usage.UsageRepository.BalanceRow;
import app.mnema.learning.usage.UsageRepository.StoredAllowance;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-side computation shared by {@code GET /api/usage}, the estimate and the limit errors: where an account stands
 * in its period. Nothing here writes; the ledger materializes grants when it admits something.
 */
@Component
final class UsageState {
    private static final int SECONDS_PER_MINUTE = 60;

    /** An account at one instant: its entitlement, period, effective allowance and balance row (absent before first use). */
    record Resolved(UUID owner, Instant now, Entitlement entitlement, UsageCalendar.Period period, Allowance allowance,
                    Optional<BalanceRow> balance) { }

    /**
     * The credit bar now.
     *
     * @param window               the window the bar is currently limited by: WEEK while Free portions are still to
     *                             unlock, MONTH otherwise
     * @param windowStart          the start of that window instance (with the window kind, the key of
     *                             {@code USAGE_EXHAUSTED}); on Free the last unlock, never the period start twice
     * @param renewsAt             the next unlock (Free) or the end of the period
     * @param availableAfterRenewal credits spendable right after {@code renewsAt}
     */
    record Credits(Plan plan, int total, int unlocked, int used, int reserved, Window window, Instant windowStart,
                   Instant renewsAt, int availableAfterRenewal) {
        int remaining() {
            return Math.max(0, unlocked - used - reserved);
        }
    }

    /** The daily debit limit of a paid plan. */
    record Burst(int limitCredits, int debitedTodayCredits, Instant resetsAt) {
        int remainingTodayCredits() {
            return Math.max(0, limitCredits - debitedTodayCredits);
        }
    }

    private final EntitlementSource entitlements;
    private final UsageCalendar calendar;
    private final AllowanceCatalog catalog;
    private final UsageRepository repository;

    UsageState(EntitlementSource entitlements, UsageCalendar calendar, AllowanceCatalog catalog, UsageRepository repository) {
        this.entitlements = entitlements;
        this.calendar = calendar;
        this.catalog = catalog;
        this.repository = repository;
    }

    Resolved resolve(UUID owner, Instant now) {
        Entitlement entitlement = entitlements.current(owner, now);
        UsageCalendar.Period period = calendar.period(now);
        Allowance allowance = repository.allowance(owner, period.id())
                .filter(stored -> matches(stored, entitlement)).map(StoredAllowance::allowance)
                .orElseGet(() -> catalog.allowance(entitlement.plan()));
        return new Resolved(owner, now, entitlement, period, allowance, repository.balance(owner, period.id()));
    }

    static boolean matches(StoredAllowance stored, Entitlement entitlement) {
        return stored.allowance().plan() == entitlement.plan() && stored.source() == entitlement.source()
                && stored.validUntil().equals(entitlement.validUntil());
    }

    /** How many Free portions have opened by {@code now}. */
    int openedPortions(Allowance allowance, UsageCalendar.Period period, Instant now) {
        return (int) calendar.unlocks(period, allowance.portions().size()).stream()
                .filter(unlock -> !unlock.isAfter(now)).count();
    }

    /** The credits the schedule has unlocked by {@code now}: the whole bar, or the Free portions opened so far. */
    int scheduled(Allowance allowance, UsageCalendar.Period period, Instant now) {
        if (!allowance.weekly()) return allowance.credits();
        return allowance.portions().stream().limit(openedPortions(allowance, period, now)).mapToInt(Integer::intValue).sum();
    }

    Credits credits(Resolved state) {
        Allowance allowance = state.allowance();
        UsageCalendar.Period period = state.period();
        int used = state.balance().map(BalanceRow::used).orElse(0);
        int reserved = state.balance().map(BalanceRow::reserved).orElse(0);
        int unlocked = Math.max(state.balance().map(BalanceRow::unlocked).orElse(0),
                scheduled(allowance, period, state.now()));
        if (allowance.weekly()) {
            List<Instant> unlocks = calendar.unlocks(period, allowance.portions().size());
            int opened = openedPortions(allowance, period, state.now());
            if (opened < unlocks.size()) {
                int afterNext = Math.max(unlocked, allowance.portions().stream().limit(opened + 1L)
                        .mapToInt(Integer::intValue).sum());
                return new Credits(allowance.plan(), allowance.credits(), unlocked, used, reserved, Window.WEEK,
                        unlocks.get(opened - 1), unlocks.get(opened), Math.max(0, afterNext - used - reserved));
            }
            // After the last unlock the window instance starts at that unlock, not at the period start: the first
            // week of the month would otherwise share its start (and its USAGE_EXHAUSTED key) with this one.
            return new Credits(allowance.plan(), allowance.credits(), unlocked, used, reserved, Window.MONTH,
                    unlocks.getLast(), period.end(), allowance.portions().getFirst());
        }
        return new Credits(allowance.plan(), allowance.credits(), unlocked, used, reserved, Window.MONTH, period.start(),
                period.end(), allowance.credits());
    }

    /** The paid daily burst; empty on Free. Counts debits of the calendar day of {@code state.now()}. */
    Optional<Burst> burst(Resolved state) {
        BigDecimal fraction = state.allowance().burstFraction();
        if (fraction == null) return Optional.empty();
        int limit = BigDecimal.valueOf(state.allowance().credits()).multiply(fraction)
                .setScale(0, RoundingMode.FLOOR).intValueExact();
        Instant dayStart = calendar.dayStart(state.now());
        Instant dayEnd = calendar.nextDayStart(state.now());
        int debited = Math.toIntExact(repository.debitedBetween(state.owner(), dayStart, dayEnd));
        return Optional.of(new Burst(limit, debited, dayEnd));
    }

    /** How much of {@code bucket} the owner has used in the window instance containing {@code now}. */
    long counter(UUID owner, Bucket bucket, Window window, Instant now) {
        return repository.counter(owner, bucket, window, calendar.windowStart(window, now));
    }

    /** The problem of a credit admission that does not fit: required credits against the bar. */
    UsageLimitReachedException.Block creditsBlock(Credits credits, long required) {
        return new UsageLimitReachedException.Block(Bucket.CREDITS, credits.window(), Unit.CREDITS,
                (long) credits.unlocked(), (long) credits.used() + credits.reserved(), required, true,
                credits.renewsAt(), required <= credits.availableAfterRenewal(), credits.plan());
    }

    /**
     * The problem of a fair-use or cap window that does not fit. Speech to text is stored in seconds and reported in
     * whole minutes (used and required rounded up). A day window can only be the one that blocks while the month still
     * has room (a spent month is reported first), so {@code fitsAfterRenewal} is whether one window fits the amount.
     */
    UsageLimitReachedException.Block windowBlock(Plan plan, Bucket bucket, Allowance.WindowLimit limit, long used,
                                                 long required, Instant now) {
        boolean offered = limit.limit() != null && limit.limit() > 0;
        Instant renewsAt = offered ? calendar.windowEnd(limit.window(), now) : null;
        return new UsageLimitReachedException.Block(bucket, limit.window(), bucket.unit(),
                limit.limit() == null ? null : display(bucket, limit.limit(), false), display(bucket, used, true),
                display(bucket, required, true), offered, renewsAt, offered && required <= limit.limit(), plan);
    }

    /** Storage units to display units; only speech to text differs (seconds to minutes). */
    static long display(Bucket bucket, long stored, boolean roundUp) {
        if (bucket != Bucket.STT) return stored;
        return roundUp ? (stored + SECONDS_PER_MINUTE - 1) / SECONDS_PER_MINUTE : stored / SECONDS_PER_MINUTE;
    }
}
