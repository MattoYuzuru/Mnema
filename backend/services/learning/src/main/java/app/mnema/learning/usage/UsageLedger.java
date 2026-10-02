package app.mnema.learning.usage;

import app.mnema.learning.platform.id.UuidPolicy;
import app.mnema.learning.usage.UsageRepository.BalanceRow;
import app.mnema.learning.usage.UsageRepository.LedgerEntry;
import app.mnema.learning.usage.UsageRepository.StoredAllowance;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Credits, reservations and fair-use consumption ({@code contracts/usage}). The writers join the caller's transaction
 * ({@link Propagation#MANDATORY}): a reservation, a debit and a consumption commit or roll back with the domain change
 * they pay for, and the ledger entry, the balance and any notification are one unit.
 *
 * <p>Lifecycle: {@link #reserve} holds credits for one admission; {@link #settle} records a debit by fact against the
 * hold, once per idempotency key; {@link #release} ends the hold and returns the unspent remainder; {@link #expireDue}
 * ends orphaned holds. The balance never goes negative: admission is one conditional update on the balance row.
 *
 * <p>Call these as late in the transaction as possible: a debit or consumption that crosses a threshold publishes a
 * notification, which takes the owner's notification cursor lock until the caller commits.
 */
@Service
public class UsageLedger {
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:+@/-]{0,199}");
    private static final Pattern OPERATION = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final int MAX_CREDITS = 1_000_000;
    private static final long MAX_UNITS = 10_000_000L;
    /** Re-reads of the balance after losing a race on its row version, before giving up. */
    private static final int ADMISSION_ATTEMPTS = 32;

    /**
     * One debit by fact.
     *
     * @param reservationId  the hold it draws from
     * @param idempotencyKey e.g. {@code debit:{stepId}:{attempt}}; a repeat changes nothing
     * @param operation      the rate card operation charged
     * @param credits        what the rate card charged, at most what the reservation still holds
     * @param costMicros     the measured provider cost in millionths of a rouble, null when unknown
     * @param reference      an opaque step or call id, null for none
     */
    public record Debit(UUID reservationId, String idempotencyKey, String operation, int credits, Long costMicros,
                        String reference) { }

    /** @param replayed true when the key had been settled before and nothing was written */
    public record Settlement(boolean replayed, Reservation reservation) { }

    /** @param replayed true when the key had been consumed before and nothing was written */
    public record Consumption(boolean replayed) { }

    /** The debit limit of one calendar day of a paid plan. */
    public record DailyBurst(int limitCredits, int debitedTodayCredits, int remainingTodayCredits, Instant resetsAt) { }

    private final UsageRepository repository;
    private final UsageState state;
    private final UsageCalendar calendar;
    private final UsagePolicy policy;
    private final RateCard rateCard;
    private final AllowanceCatalog catalog;
    private final EntitlementSource entitlements;
    private final UsageNotifier notifier;
    private final UsageClock clock;

    UsageLedger(UsageRepository repository, UsageState state, UsageCalendar calendar, UsagePolicy policy,
                RateCard rateCard, AllowanceCatalog catalog, EntitlementSource entitlements, UsageNotifier notifier,
                UsageClock clock) {
        this.repository = repository;
        this.state = state;
        this.calendar = calendar;
        this.policy = policy;
        this.rateCard = rateCard;
        this.catalog = catalog;
        this.entitlements = entitlements;
        this.notifier = notifier;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ reserve

    /**
     * Holds {@code credits} on the owner's balance of the current period for one admission. The hold expires at
     * {@code min(learning.usage.reservation-ttl, end of the period)}.
     *
     * @param sessionId opaque, may be null
     * @param turnId    opaque, may be null
     * @throws UsageLimitReachedException {@code 409 USAGE_LIMIT_REACHED}: it does not fit; nothing was written for it
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Reservation reserve(UUID owner, ReservationScope scope, UUID sessionId, UUID turnId, int credits) {
        UuidPolicy.requireEntityId(owner, "owner");
        Objects.requireNonNull(scope, "scope");
        if (credits < 1 || credits > MAX_CREDITS) throw new IllegalArgumentException("Invalid reservation credits");
        Instant now = clock.now();
        Entitlement entitlement = entitlements.current(owner, now);
        UsageCalendar.Period period = calendar.period(now);
        Allowance allowance = ensureAllowance(owner, period, entitlement, now);
        repository.ensureBalance(owner, period.id(), now);
        grantScheduled(owner, period, entitlement, allowance, now);

        for (int attempt = 0; attempt < ADMISSION_ATTEMPTS; attempt++) {
            BalanceRow balance = repository.balance(owner, period.id()).orElseThrow();
            if (balance.available() < credits) {
                var resolved = new UsageState.Resolved(owner, now, entitlement, period, allowance, Optional.of(balance));
                throw new UsageLimitReachedException(state.creditsBlock(state.credits(resolved), credits));
            }
            if (repository.tryReserve(owner, period.id(), credits, balance.rowVersion(), now)) {
                Instant expiresAt = now.plus(policy.reservationTtl);
                if (expiresAt.isAfter(period.end())) expiresAt = period.end();
                Reservation reservation = new Reservation(UUID.randomUUID(), owner, scope, sessionId, turnId, period.id(),
                        ReservationState.ACTIVE, credits, 0, rateCard.version(), now, expiresAt);
                repository.insertReservation(reservation);
                return reservation;
            }
        }
        throw new IllegalStateException("Usage admission lost its balance row repeatedly");
    }

    /** Writes the period's allowance when it is absent or the entitlement changed; returns the one in force. */
    private Allowance ensureAllowance(UUID owner, UsageCalendar.Period period, Entitlement entitlement, Instant now) {
        Optional<StoredAllowance> stored = repository.allowance(owner, period.id());
        if (stored.isPresent() && UsageState.matches(stored.get(), entitlement)) return stored.get().allowance();
        Allowance desired = catalog.allowance(entitlement.plan());
        repository.upsertAllowance(owner, period, desired, entitlement, now);
        return desired;
    }

    /**
     * Raises {@code unlocked} to what the schedule has opened by {@code now}, with one GRANT ledger entry for the
     * difference. The grant is keyed by plan, period and portion, so concurrent first uses write it once. Grants only
     * ever add: a plan change mid-period never claws credits back.
     */
    private void grantScheduled(UUID owner, UsageCalendar.Period period, Entitlement entitlement, Allowance allowance,
                                Instant now) {
        int target = state.scheduled(allowance, period, now);
        if (repository.balance(owner, period.id()).orElseThrow().unlocked() >= target) return;
        BalanceRow locked = repository.lockBalance(owner, period.id());
        if (locked.unlocked() >= target) return;
        int delta = target - locked.unlocked();
        int portion = allowance.weekly() ? state.openedPortions(allowance, period, now) - 1 : 0;
        String key = "grant:" + entitlement.source().name().toLowerCase(java.util.Locale.ROOT) + "-" + owner + "-"
                + allowance.plan() + ":" + period.id() + ":" + portion;
        boolean appended = repository.insertLedger(new LedgerEntry(UUID.randomUUID(), owner, "GRANT", delta, null, null,
                rateCard.version(), period.id(), key, null, null, null, null, now));
        if (appended) repository.addUnlocked(owner, period.id(), delta, now);
    }

    // ------------------------------------------------------------------- settle

    /**
     * Records a debit by fact against a reservation, in the transaction that stores the result it pays for. A repeat
     * of the same {@code idempotencyKey} writes nothing and returns {@code replayed}. The daily burst never fails a
     * settle: a running step completes and the scheduler defers the next ones ({@link #dailyDebitRoom}).
     *
     * @throws EstimateExceededException   the debit exceeds what the reservation still holds; the call must not be made
     * @throws ReservationNotActiveException the reservation has ended
     * @throws IllegalArgumentException    the key was used for a different debit, or the reservation does not exist
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Settlement settle(UUID owner, Debit debit) {
        UuidPolicy.requireEntityId(owner, "owner");
        validateKey(debit.idempotencyKey());
        if (debit.reference() != null) validateKey(debit.reference());
        if (!OPERATION.matcher(debit.operation()).matches() || debit.credits() < 0 || debit.credits() > MAX_CREDITS
                || (debit.costMicros() != null && debit.costMicros() < 0)) {
            throw new IllegalArgumentException("Invalid debit");
        }
        rateCard.operation(debit.operation());
        Reservation reservation = repository.lockReservation(debit.reservationId())
                .filter(found -> found.ownerId().equals(owner)).orElseThrow(() -> new IllegalArgumentException("Unknown reservation"));
        Optional<UsageRepository.LedgerRow> existing = repository.ledgerByKey(debit.idempotencyKey());
        if (existing.isPresent()) {
            var row = existing.get();
            if (!row.kind().equals("DEBIT") || row.credits() != -debit.credits()
                    || !debit.reservationId().equals(row.reservationId())) {
                throw new IllegalArgumentException("Idempotency key reused for a different debit");
            }
            return new Settlement(true, reservation);
        }
        if (reservation.state() != ReservationState.ACTIVE) throw new ReservationNotActiveException(reservation.state());
        int held = reservation.heldCredits() - reservation.debitedCredits();
        if (debit.credits() > held) throw new EstimateExceededException(held, debit.credits());

        Instant now = clock.now();
        boolean appended = repository.insertLedger(new LedgerEntry(UUID.randomUUID(), owner, "DEBIT", -debit.credits(),
                debit.costMicros(), debit.operation(), reservation.rateCardVersion(), reservation.periodId(),
                debit.idempotencyKey(), reservation.reservationId(), debit.reference(), null, null, now));
        if (!appended) throw new IllegalStateException("Debit key raced under the reservation lock");
        repository.addDebited(reservation.reservationId(), debit.credits());
        repository.applyDebit(owner, reservation.periodId(), debit.credits(), now);
        notifyCredits(owner, reservation.periodId(), debit.credits(), now);
        return new Settlement(false, repository.reservation(reservation.reservationId()).orElseThrow());
    }

    private void notifyCredits(UUID owner, String periodId, int credits, Instant now) {
        if (credits == 0) return;
        BalanceRow balance = repository.balance(owner, periodId).orElseThrow();
        Allowance allowance = repository.allowance(owner, periodId).orElseThrow().allowance();
        UsageCalendar.Period period = calendar.period(periodId);
        var resolved = new UsageState.Resolved(owner, now, new Entitlement(allowance.plan(), Entitlement.Source.CONFIG,
                period.end()), period, allowance, Optional.of(balance));
        UsageState.Credits credit = state.credits(resolved);
        notifier.publish(new UsageNotifier.Crossing(owner, allowance.plan(), Bucket.CREDITS, credit.window(),
                credit.windowStart(), periodId, balance.used() - credits, balance.used(), credit.unlocked(),
                credit.renewsAt(), true));
    }

    // ------------------------------------------------------------------ release

    /**
     * Ends a hold: it becomes {@code SETTLED} when it recorded at least one debit and {@code RELEASED} otherwise, and
     * the unspent remainder returns to the balance. Ending an ended reservation is a no-op.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Reservation release(UUID owner, UUID reservationId) {
        UuidPolicy.requireEntityId(owner, "owner");
        Reservation reservation = repository.lockReservation(reservationId).filter(found -> found.ownerId().equals(owner))
                .orElseThrow(() -> new IllegalArgumentException("Unknown reservation"));
        if (reservation.state() != ReservationState.ACTIVE) return reservation;
        end(reservation, reservation.debitedCredits() > 0 ? ReservationState.SETTLED : ReservationState.RELEASED,
                clock.now());
        return repository.reservation(reservationId).orElseThrow();
    }

    private void end(Reservation reservation, ReservationState terminal, Instant now) {
        repository.end(reservation.reservationId(), terminal, now);
        repository.returnHold(reservation.ownerId(), reservation.periodId(), reservation.heldRemaining(), now);
    }

    // ------------------------------------------------------------------- expire

    /**
     * Ends up to {@code batch} live holds past their {@code expiresAt} and returns their remainder to the balance. A
     * hold that outlived its period (the rollover case) ends as {@code SETTLED} or {@code RELEASED} by whether it
     * debited anything; an orphan that merely timed out is {@code EXPIRED}. Rows another worker holds are skipped.
     *
     * @return how many holds were ended; a result below {@code batch} means nothing more is due
     */
    @Transactional
    public int expireDue(int batch) {
        Instant now = clock.now();
        // Balances are updated in one global order, so two workers holding different holds of one account never deadlock.
        List<Reservation> due = repository.lockDue(now, batch).stream()
                .sorted(Comparator.comparing(Reservation::ownerId).thenComparing(Reservation::periodId)).toList();
        for (Reservation reservation : due) {
            boolean rolledOver = !now.isBefore(calendar.period(reservation.periodId()).end());
            ReservationState terminal = !rolledOver ? ReservationState.EXPIRED
                    : reservation.debitedCredits() > 0 ? ReservationState.SETTLED : ReservationState.RELEASED;
            end(reservation, terminal, now);
        }
        return due.size();
    }

    // ------------------------------------------------------------------ consume

    /**
     * Counts {@code amount} of a fair-use bucket (speech to text in seconds, answer checks) or a count cap (podcasts,
     * quality images, high fact check, smart plan) against its monthly and daily windows, once per idempotency key.
     * The windows are locked in a fixed order and checked before anything is written, so a refusal changes nothing.
     *
     * @param idempotencyKey unique per consumption; include the account so keys of different accounts never collide
     * @param reference      an opaque call id, null for none
     * @throws UsageLimitReachedException the amount does not fit a window (a plan without the bucket has a limit of 0)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Consumption consume(UUID owner, Bucket bucket, long amount, String idempotencyKey, String reference) {
        UuidPolicy.requireEntityId(owner, "owner");
        Objects.requireNonNull(bucket, "bucket");
        if (!bucket.consumable() || amount < 1 || amount > MAX_UNITS) throw new IllegalArgumentException("Invalid consumption");
        validateKey(idempotencyKey);
        if (reference != null) validateKey(reference);
        Instant now = clock.now();
        Entitlement entitlement = entitlements.current(owner, now);
        UsageCalendar.Period period = calendar.period(now);
        Allowance allowance = ensureAllowance(owner, period, entitlement, now);

        List<Allowance.WindowLimit> limits = allowance.limits(bucket).stream()
                .sorted(Comparator.comparing(Allowance.WindowLimit::window)).toList();
        for (Allowance.WindowLimit limit : limits) {
            repository.ensureCounter(owner, bucket, limit.window(), calendar.windowStart(limit.window(), now));
        }
        long[] used = new long[limits.size()];
        for (int i = 0; i < used.length; i++) {
            used[i] = repository.lockCounter(owner, bucket, limits.get(i).window(), calendar.windowStart(limits.get(i).window(), now));
        }
        if (repository.ledgerByKey(idempotencyKey).isPresent()) return new Consumption(true);

        int violated = -1;
        for (int i = 0; i < used.length; i++) {
            Long limit = limits.get(i).limit();
            if (limit == null || used[i] + amount <= limit) continue;
            // The widest window decides: waiting for a day never helps when the month is spent.
            if (violated < 0 || limits.get(i).window().compareTo(limits.get(violated).window()) > 0) violated = i;
        }
        if (violated >= 0) {
            throw new UsageLimitReachedException(state.windowBlock(allowance.plan(), bucket, limits.get(violated),
                    used[violated], amount, now));
        }

        boolean appended = repository.insertLedger(new LedgerEntry(UUID.randomUUID(), owner, "DEBIT", 0, null, null,
                rateCard.version(), period.id(), idempotencyKey, null, reference, bucket, amount, now));
        if (!appended) throw new IllegalStateException("Consumption key raced under the counter lock");
        for (int i = 0; i < used.length; i++) {
            Allowance.WindowLimit limit = limits.get(i);
            Instant windowStart = calendar.windowStart(limit.window(), now);
            repository.addCounter(owner, bucket, limit.window(), windowStart, amount);
            if (limit.limit() != null) {
                // The low-usage notice follows the monthly (or only) window; exhaustion follows each window.
                boolean low = limit.window() != Window.DAY || limits.size() == 1;
                notifier.publish(new UsageNotifier.Crossing(owner, allowance.plan(), bucket, limit.window(), windowStart,
                        period.id(), used[i], used[i] + amount, limit.limit(), calendar.windowEnd(limit.window(), now), low));
            }
        }
        return new Consumption(false);
    }

    // -------------------------------------------------------------- daily burst

    /**
     * The room the daily burst leaves a paid plan today: the debits of the calendar day against 35% of the bar. This
     * is a scheduling signal for the step scheduler (AI-04), which parks steps that would exceed it; it never gates
     * admission or {@link #settle}. Empty on Free, which is limited by its weekly unlocks instead.
     */
    @Transactional(readOnly = true)
    public Optional<DailyBurst> dailyDebitRoom(UUID owner) {
        UuidPolicy.requireEntityId(owner, "owner");
        return state.burst(state.resolve(owner, clock.now())).map(burst -> new DailyBurst(burst.limitCredits(),
                burst.debitedTodayCredits(), burst.remainingTodayCredits(), burst.resetsAt()));
    }

    private static void validateKey(String key) {
        if (key == null || !KEY.matcher(key).matches()) throw new IllegalArgumentException("Invalid idempotency key");
    }
}
