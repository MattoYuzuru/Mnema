package app.mnema.learning.usage;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Instant;

/** Isolated clock for the research day-boundary regression; ordinary generation contexts keep their midday clock. */
@TestConfiguration(proxyBeanMethods = false)
public class ResearchBurstClock {
    public static final class MutableClock implements UsageClock {
        private volatile Instant now = Instant.parse("2026-10-15T09:00:00Z");

        @Override public Instant now() { return now; }

        public void set(Instant instant) { now = instant; }
    }

    @Bean
    @Primary
    MutableClock researchBurstClock() { return new MutableClock(); }
}
