package app.mnema.learning.ai;

import app.mnema.learning.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The global daily budget on the real journal and a clock the test drives: spend recorded on a Moscow day turns the capability
 * {@code TEMPORARILY_UNAVAILABLE} for the rest of that day and clears at the Moscow day boundary (21:00 UTC), with no restart and no operator.
 */
@SpringBootTest(properties = {"learning.ai.provider=stub", "learning.ai.budget.text-micros=1000", "learning.ai.budget.search-micros=500",
        "spring.datasource.hikari.maximum-pool-size=2"})
class AiBudgetDayBoundaryIntegrationTest extends PostgresIntegrationTest {
    @Autowired private JdbcClient jdbc;
    @Autowired private JdbcCallJournal journal;
    @Autowired private AiProperties properties;

    private void spend(AiCapability capability, long micros, Instant at) {
        jdbc.sql("INSERT INTO app_learning.ai_provider_call(call_id,capability,provider,model,request_hash,outcome,cost_micros,created_at) "
                        + "VALUES (:id,:capability,'stub','stub',:hash,'OK',:cost,:at)").param("id", UUID.randomUUID()).param("capability", capability.name())
                .param("hash", "e".repeat(64)).param("cost", micros).param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC)).update();
    }

    @Test
    void aSpentCapabilityIsTemporarilyUnavailableUntilTheMoscowDayEndsAndOnlyThatCapability() {
        jdbc.sql("DELETE FROM app_learning.ai_provider_call").update();
        // 23:30 on 4 October in Moscow; the Moscow day began at 2026-10-03T21:00Z
        var clock = new AiTestSupport.MutableClock(Instant.parse("2026-10-04T20:30:00Z"));
        var budget = new AiBudget(properties.budget(), journal, clock);
        var availability = new DefaultAiAvailability(new AiRouting(properties, Map.of("stub", new StubTextAdapter())),
                new BreakerRegistry(clock, properties.breaker()), budget, new UserKeys(properties.userKey()), true);

        assertThat(availability.text()).isEqualTo(AiAvailability.State.AVAILABLE);
        assertThat(availability.port(AiCapability.SEARCH, true)).isEqualTo(AiAvailability.State.AVAILABLE);

        spend(AiCapability.TEXT, 1_200, Instant.parse("2026-10-04T20:10:00Z"));
        clock.advance(Duration.ofSeconds(11));
        assertThat(availability.text()).as("the limit of 1000 micros is spent").isEqualTo(AiAvailability.State.TEMPORARILY_UNAVAILABLE);
        assertThat(availability.assessment()).as("grading is a capability of its own with its own budget").isEqualTo(AiAvailability.State.AVAILABLE);
        assertThat(availability.port(AiCapability.SEARCH, true)).as("and so is search").isEqualTo(AiAvailability.State.AVAILABLE);

        spend(AiCapability.SEARCH, 600, Instant.parse("2026-10-04T20:40:00Z"));
        clock.advance(Duration.ofSeconds(11));
        assertThat(availability.port(AiCapability.SEARCH, true)).isEqualTo(AiAvailability.State.TEMPORARILY_UNAVAILABLE);

        // still the same Moscow day a minute before midnight (20:59 UTC: the clock is at 20:30:22 now)
        clock.advance(Duration.ofSeconds(28 * 60 + 38));
        assertThat(availability.text()).isEqualTo(AiAvailability.State.TEMPORARILY_UNAVAILABLE);
        assertThat(availability.port(AiCapability.SEARCH, true)).isEqualTo(AiAvailability.State.TEMPORARILY_UNAVAILABLE);

        // past midnight in Moscow (21:00 UTC): a new day, the counters start from zero without a restart
        clock.advance(Duration.ofMinutes(2));
        assertThat(availability.text()).as("cleared at the day boundary").isEqualTo(AiAvailability.State.AVAILABLE);
        assertThat(availability.port(AiCapability.SEARCH, true)).isEqualTo(AiAvailability.State.AVAILABLE);

        // and the new day is guarded again
        spend(AiCapability.TEXT, 1_500, Instant.parse("2026-10-04T21:05:00Z"));
        clock.advance(Duration.ofSeconds(11));
        assertThat(availability.text()).isEqualTo(AiAvailability.State.TEMPORARILY_UNAVAILABLE);
        jdbc.sql("DELETE FROM app_learning.ai_provider_call").update();
    }
}
