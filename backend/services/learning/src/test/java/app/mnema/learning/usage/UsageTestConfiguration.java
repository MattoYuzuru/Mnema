package app.mnema.learning.usage;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Moves usage time and assigns plans per account: periods, weekly unlocks, daily bursts and expiries are all functions
 * of the clock, and the plan of an account is configuration until billing exists.
 */
@TestConfiguration(proxyBeanMethods = false)
class UsageTestConfiguration {
    static final class MutableClock implements UsageClock {
        private volatile Instant now = Instant.parse("2026-10-02T09:00:42Z");

        @Override
        public Instant now() {
            return now;
        }

        void set(String instant) {
            now = Instant.parse(instant);
        }
    }

    static final class TestEntitlements implements EntitlementSource {
        private final Map<UUID, Plan> plans = new ConcurrentHashMap<>();
        private final UsageCalendar calendar;

        TestEntitlements(UsageCalendar calendar) {
            this.calendar = calendar;
        }

        void set(UUID owner, Plan plan) {
            plans.put(owner, plan);
        }

        @Override
        public Entitlement current(UUID owner, Instant now) {
            return new Entitlement(plans.getOrDefault(owner, Plan.FREE), Entitlement.Source.CONFIG, calendar.period(now).end());
        }
    }

    /**
     * The usage tests price specs and edits with identifiers that belong to no real note, material or session: ownership and
     * capabilities are the generation module's boundary and are tested there ({@code GenerationHttpContractTest}).
     */
    @Bean
    @Primary
    GenerationBoundary permissiveGenerationBoundary() {
        return new GenerationBoundary() {
            @Override
            public void checkSpec(UUID owner, UUID deckId, SpecFacts facts, boolean admission) { }

            @Override
            public boolean ownsArtifact(UUID owner, UUID deckId, UUID sessionId, UUID artifactId) { return true; }
        };
    }

    @Bean
    @Primary
    MutableClock testUsageClock() {
        return new MutableClock();
    }

    @Bean
    @Primary
    TestEntitlements testEntitlements(UsageCalendar calendar) {
        return new TestEntitlements(calendar);
    }
}
