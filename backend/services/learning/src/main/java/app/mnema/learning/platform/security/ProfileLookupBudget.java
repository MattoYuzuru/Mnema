package app.mnema.learning.platform.security;

import app.mnema.learning.platform.api.RateLimitedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The ONE in-process budget of every call that Learning makes to Identity's public by-username lookup ({@code GET /api/accounts/profiles/by-username/{u}}):
 * grants by login ({@link Pool#GRANTS}, an owner) and profile complaints ({@link Pool#COMPLAINTS}, a reporter). The link previews do not call it (they answer the
 * generic card until the author page exists).
 *
 * <p><b>Why one budget, and why 15 minutes.</b> Identity limits that endpoint to 60 requests per 15-minute fixed window per CALLER ADDRESS, and every Learning call
 * comes from one address, so all callers and all instances behind it share that one allowance. Counting per minute (as the first version did) would admit up to
 * 40 a minute and meet Identity's 429 after about a minute and a half; this class counts in Identity's own unit, a fixed window of {@link #WINDOW} on the monotonic
 * clock, in memory of the instance. The window starts at the first admission after the previous one ended, so it is not aligned with Identity's own (which
 * Learning cannot see); the caps below leave headroom for exactly that.
 *
 * <p><b>Caps</b> (all configurable, {@code learning.community.profile-lookups.*}): at most {@code instance-per-window} [40] lookups in total; within it at most
 * {@code complaints-per-window} [10] for complaints, so complaints can never starve grants (grants always keep the rest: 30 of 40 by default); per account at most
 * {@code owner-per-window} [10] grants and {@code reporter-per-window} [3] complaints. With N instances behind one address divide the instance cap by about N.
 * A lookup is admitted only when every cap has room, and then takes a place in each; a refused call spends nothing.
 *
 * <p><b>Retry-After</b> is always the real wait: the seconds until the current window ends and every counter starts over (in a fixed window no place frees
 * earlier). When Identity itself answers 429 ({@link #lookup} sees the {@link RateLimitedException} of {@code ProfileDirectory.accountOf}) the whole budget is treated as
 * exhausted until the end of the current window and the caller gets that wait, never a guessed constant. The per-account tables hold only accounts that were admitted,
 * so they are bounded by the instance cap.
 */
@Component
public final class ProfileLookupBudget {
    /** Identity's own window of this endpoint ({@code RateLimits}: 60 per 15 minutes per caller address). */
    public static final Duration WINDOW = Duration.ofMinutes(15);
    public static final int DEFAULT_PER_INSTANCE = 40;
    public static final int DEFAULT_COMPLAINTS = 10;
    public static final int DEFAULT_PER_OWNER = 10;
    public static final int DEFAULT_PER_REPORTER = 3;
    private static final long WINDOW_NANOS = WINDOW.toNanos();
    private static final int MAX_CAP = 1_000;

    /** Who asks: the two pools share the instance total, and complaints are also capped on their own. */
    public enum Pool { GRANTS, COMPLAINTS }

    private final int perInstance;
    private final int complaints;
    private final int perOwner;
    private final int perReporter;
    private final LongSupplier nanos;
    private final Map<UUID, Integer> owners = new HashMap<>();
    private final Map<UUID, Integer> reporters = new HashMap<>();
    private boolean started;
    private long windowStart;
    private int total;
    private int complaintTotal;
    private boolean identityRefused;

    @Autowired
    public ProfileLookupBudget(@Value("${learning.community.profile-lookups.instance-per-window:" + DEFAULT_PER_INSTANCE + "}") int perInstance,
                               @Value("${learning.community.profile-lookups.complaints-per-window:" + DEFAULT_COMPLAINTS + "}") int complaints,
                               @Value("${learning.community.profile-lookups.owner-per-window:" + DEFAULT_PER_OWNER + "}") int perOwner,
                               @Value("${learning.community.profile-lookups.reporter-per-window:" + DEFAULT_PER_REPORTER + "}") int perReporter) {
        this(perInstance, complaints, perOwner, perReporter, System::nanoTime);
    }

    public ProfileLookupBudget() { this(DEFAULT_PER_INSTANCE, DEFAULT_COMPLAINTS, DEFAULT_PER_OWNER, DEFAULT_PER_REPORTER, System::nanoTime); }

    public ProfileLookupBudget(int perInstance, int complaints, int perOwner, int perReporter, LongSupplier nanos) {
        if (outside(perInstance, MAX_CAP) || outside(complaints, perInstance) || outside(perOwner, perInstance) || outside(perReporter, complaints)) {
            throw new IllegalArgumentException("Invalid profile lookup budget");
        }
        this.perInstance = perInstance;
        this.complaints = complaints;
        this.perOwner = perOwner;
        this.perReporter = perReporter;
        this.nanos = nanos;
    }

    private static boolean outside(int value, int max) { return value < 1 || value > max; }

    /**
     * Takes a place in the budget, calls Identity through {@code identityCall} and returns its answer. A {@link RateLimitedException} from the call means
     * Identity refused: the budget is spent until the end of the window and the exception that leaves carries that wait.
     *
     * @throws RateLimitedException a cap is used (the wait is the rest of the window), or Identity refused
     */
    public <T> T lookup(Pool pool, UUID caller, Supplier<T> identityCall) {
        admit(pool, caller);
        try {
            return identityCall.get();
        } catch (RateLimitedException refused) {
            throw new RateLimitedException(Math.max(refused.retryAfterSeconds(), identityRefused()));
        }
    }

    synchronized void admit(Pool pool, UUID caller) {
        long now = nanos.getAsLong();
        roll(now);
        if (identityRefused || total >= perInstance) throw new RateLimitedException(rest(now));
        if (pool == Pool.COMPLAINTS && complaintTotal >= complaints) throw new RateLimitedException(rest(now));
        Map<UUID, Integer> callers = pool == Pool.GRANTS ? owners : reporters;
        int used = callers.getOrDefault(caller, 0);
        if (used >= (pool == Pool.GRANTS ? perOwner : perReporter)) throw new RateLimitedException(rest(now));
        callers.put(caller, used + 1);
        total++;
        if (pool == Pool.COMPLAINTS) complaintTotal++;
    }

    /** Identity answered 429: nothing more is admitted until the window ends. @return the seconds until it does */
    synchronized long identityRefused() {
        long now = nanos.getAsLong();
        roll(now);
        identityRefused = true;
        return rest(now);
    }

    private void roll(long now) {
        if (started && now - windowStart < WINDOW_NANOS) return;
        started = true;
        windowStart = now;
        total = 0;
        complaintTotal = 0;
        identityRefused = false;
        owners.clear();
        reporters.clear();
    }

    private long rest(long now) { return Math.max(1, Math.ceilDiv(WINDOW_NANOS - (now - windowStart), 1_000_000_000L)); }
}
