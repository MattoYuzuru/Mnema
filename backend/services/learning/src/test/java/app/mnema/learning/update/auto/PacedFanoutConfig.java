package app.mnema.learning.update.auto;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The seam of the tests of the automatic mode (Updates/6 part B, #445): the pace of the fan-out under a clock and an allowance the test controls. It lives in the package of
 * {@link AutoThrottle} so that the throttle and its constructor stay package-private in production; the integration tests import this configuration.
 */
@TestConfiguration
public class PacedFanoutConfig {
    /** The allowance of the fan-out, per rolling minute (10 000 unless a test lowers it). */
    public static final AtomicInteger ALLOWANCE = new AtomicInteger(10_000);

    /** The clock of the pace; the test moves it. */
    public static final class MovingClock extends Clock {
        private volatile Instant now = Instant.now();

        public void advance(Duration by) { now = now.plus(by); }

        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }

        @Override public Clock withZone(ZoneId zone) { return this; }

        @Override public Instant instant() { return now; }
    }

    @Bean
    public MovingClock movingClock() { return new MovingClock(); }

    @Bean
    @Primary
    AutoThrottle pacedThrottle(MovingClock clock) { return new AutoThrottle(ALLOWANCE::get, clock); }
}
