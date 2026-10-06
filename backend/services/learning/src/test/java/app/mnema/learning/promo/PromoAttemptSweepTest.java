package app.mnema.learning.promo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistrar;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The retention sweep of redemption attempts: only rows older than two hours go, in bounded batches per tick. */
@Import(PromoAttemptSweepTest.DatabaseConfiguration.class)
class PromoAttemptSweepTest extends PromoIntegrationTest {
    private static final String DATABASE = createDatabase("promo_retention_" + UUID.randomUUID().toString().replace("-", ""));

    @TestConfiguration(proxyBeanMethods = false)
    static class DatabaseConfiguration {
        @Bean
        DynamicPropertyRegistrar isolatedDatabase() {
            // The sweep is global: rows from other clock-driven tests must not consume this test's batch budget.
            return registry -> {
                registry.add("spring.datasource.url", () -> DATABASE);
                registry.add("spring.flyway.url", () -> DATABASE);
            };
        }
    }
    @Autowired private PromoRepository repository;

    /** The test context runs the api role only, where the scheduled bean is absent: the sweep is built by hand over the real repository and clock. */
    private PromoAttemptSweep sweep;

    @BeforeEach
    void sweeper() {
        assertThat(jdbc.sql("SELECT current_database()").query(String.class).single()).startsWith("promo_retention_");
        sweep = new PromoAttemptSweep(repository, clock);
    }

    private void insert(UUID owner, Duration age, int count) {
        jdbc.sql("INSERT INTO app_learning.promo_attempt(owner_id,attempted_at) SELECT :owner,:at FROM generate_series(1,:count)")
                .param("owner", owner).param("at", Timestamp.from(now().minus(age))).param("count", count).update();
    }

    private long rows(UUID owner) {
        return jdbc.sql("SELECT count(*) FROM app_learning.promo_attempt WHERE owner_id=:o").param("o", owner).query(Long.class).single();
    }

    @Test
    void onlyAttemptsOlderThanTwoHoursAreDeleted() {
        UUID owner = UUID.randomUUID();
        insert(owner, Duration.ofHours(3), 4);
        insert(owner, Duration.ofMinutes(119), 2);
        insert(owner, Duration.ofMinutes(5), 1);

        assertThat(sweep.sweep()).isGreaterThanOrEqualTo(4);

        assertThat(rows(owner)).isEqualTo(3);
    }

    @Test
    void oneTickIsBoundedAndTheNextTickFinishesTheJob() {
        UUID owner = UUID.randomUUID();
        int total = PromoAttemptSweep.BATCH_SIZE * PromoAttemptSweep.MAX_BATCHES_PER_TICK + 5;
        insert(owner, Duration.ofHours(5), total);

        assertThat(sweep.sweep()).isGreaterThanOrEqualTo(PromoAttemptSweep.BATCH_SIZE * PromoAttemptSweep.MAX_BATCHES_PER_TICK);
        assertThat(rows(owner)).isLessThanOrEqualTo(5);

        sweep.sweep();
        assertThat(rows(owner)).isZero();
    }
}
