package app.mnema.learning.ai;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * A clock that cannot jump: the wall-clock time at construction plus the monotonic {@code System.nanoTime()} elapsed since.
 * Deadlines, backoff and breaker periods use it, so an NTP step or a manual clock change can neither shorten nor extend a
 * call. The daily budget alone needs the real wall clock (calendar days) and uses {@code Clock.systemUTC()}.
 */
final class MonotonicClock extends Clock {
    private final Instant base = Instant.now();
    private final long startNanos = System.nanoTime();

    @Override public ZoneId getZone() { return ZoneOffset.UTC; }

    @Override public Clock withZone(ZoneId zone) { return this; }

    @Override public Instant instant() { return base.plus(Duration.ofNanos(System.nanoTime() - startNanos)); }
}
