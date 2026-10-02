package app.mnema.learning.usage;

import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * The instant of every usage decision. Production uses the system clock; a test supplies a {@code @Primary} bean to
 * move time, because periods, weekly unlocks, daily bursts and expiries are all functions of it.
 */
interface UsageClock {
    Instant now();

    @Component
    final class System implements UsageClock {
        @Override
        public Instant now() {
            return Instant.now();
        }
    }
}
