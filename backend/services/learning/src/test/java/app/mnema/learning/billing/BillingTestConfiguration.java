package app.mnema.learning.billing;

import app.mnema.learning.usage.UsageClock;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Duration;
import java.time.Instant;

/** A movable clock: link lifetimes, refresh intervals, reconciler gaps and the months of an entitlement are all functions of time. */
@TestConfiguration(proxyBeanMethods = false)
class BillingTestConfiguration {
    static final String START = "2026-10-09T09:00:00Z";

    static final class MutableClock implements UsageClock {
        private volatile Instant now = Instant.parse(START);

        @Override
        public Instant now() {
            return now;
        }

        void set(String instant) {
            now = Instant.parse(instant);
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }
    }

    @Bean
    @Primary
    MutableClock billingTestClock() {
        return new MutableClock();
    }
}
