package app.mnema.learning.update.auto;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.IntSupplier;

/**
 * The pace of the fan-out (§12): at most {@code learning.updates.auto.per-minute} copies are handed to the automatic apply in ANY rolling minute by this instance, all fan-outs
 * of the instance together. A sliding window of grants, in memory: it is the cheapest honest bound of a rate on one process, it needs no row and no hot-path write, and a restart
 * forgets the minute (at worst one more burst of {@code per-minute} copies). A slice that was granted copies and then rolled back has spent them; that is the conservative side.
 */
@Component
final class AutoThrottle {
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private record Grant(long at, int count) { }

    private final IntSupplier allowance;
    private final Clock clock;
    private final Deque<Grant> grants = new ArrayDeque<>();
    private int inWindow;

    @Autowired
    AutoThrottle(AutoUpdateSettings settings) {
        this(settings::perMinute, Clock.systemUTC());
    }

    /** The allowance is read at every grant, so a setting or a test can change it; the clock is the instance's. */
    AutoThrottle(IntSupplier perMinute, Clock clock) {
        this.allowance = perMinute;
        this.clock = clock;
    }

    /** The instance's clock, so one test clock paces the fan-out and decides who is active. */
    Clock clock() { return clock; }

    /** Takes up to {@code wanted} copies of the minute's allowance; 0 when it is spent. */
    synchronized int acquire(int wanted) {
        long now = clock.millis();
        expire(now);
        int granted = Math.max(0, Math.min(wanted, allowance.getAsInt() - inWindow));
        if (granted > 0) {
            grants.addLast(new Grant(now, granted));
            inWindow += granted;
        }
        return granted;
    }

    /** Gives back the copies a slice was granted and did not need (the newest grants first). */
    synchronized void giveBack(int unused) {
        int left = unused;
        while (left > 0 && !grants.isEmpty()) {
            Grant last = grants.removeLast();
            int returned = Math.min(left, last.count());
            left -= returned;
            inWindow -= returned;
            if (last.count() > returned) grants.addLast(new Grant(last.at(), last.count() - returned));
        }
    }

    /** How long until some of the allowance is free again (at least a second, at most the minute); zero when there is some now. */
    synchronized Duration retryIn() {
        long now = clock.millis();
        expire(now);
        if (inWindow < allowance.getAsInt() || grants.isEmpty()) return Duration.ZERO;
        long free = grants.peekFirst().at() + WINDOW.toMillis() - now;
        return Duration.ofMillis(Math.max(1_000, Math.min(WINDOW.toMillis(), free)));
    }

    private void expire(long now) {
        while (!grants.isEmpty() && grants.peekFirst().at() <= now - WINDOW.toMillis()) inWindow -= grants.removeFirst().count();
    }
}
