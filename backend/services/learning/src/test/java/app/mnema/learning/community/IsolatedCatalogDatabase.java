package app.mnema.learning.community;

import app.mnema.learning.support.PostgresIntegrationTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistrar;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

/**
 * The Community/3 tests compute over EVERY row of {@code public_deck} and {@code deck_engagement} (ranks, percentiles, the anomaly query), so they run in a database of their own: the
 * shared test database holds the public decks of every other class. All the classes that extend this one share that one database, one after the other, and each starts by
 * {@link #reset clearing} the tables of the feature.
 */
@Import(IsolatedCatalogDatabase.DatabaseConfiguration.class)
abstract class IsolatedCatalogDatabase extends PostgresIntegrationTest {
    static final String DATABASE = createDatabase("community3_" + UUID.randomUUID().toString().replace("-", ""));

    @TestConfiguration(proxyBeanMethods = false)
    static class DatabaseConfiguration {
        @Bean
        DynamicPropertyRegistrar isolatedDatabase() {
            // Registrars run after inherited dynamic methods and before the DataSource/Flyway are instantiated.
            return registry -> {
                registry.add("spring.datasource.url", () -> DATABASE);
                registry.add("spring.flyway.url", () -> DATABASE);
            };
        }
    }

    /** Empties the tables of the feature and the decks' catalogue rows; decks and revisions of earlier tests stay (nothing references them from here). */
    static void reset(JdbcClient jdbc) {
        jdbc.sql("DELETE FROM app_learning.deck_rank").update();
        jdbc.sql("DELETE FROM app_learning.rank_generation").update();
        jdbc.sql("DELETE FROM app_learning.deck_engagement").update();
        jdbc.sql("DELETE FROM app_learning.catalog_anomaly").update();
        jdbc.sql("DELETE FROM app_learning.public_deck").update();
        jdbc.sql("DELETE FROM app_learning.learning_job WHERE queue IN ('catalog.rank', 'catalog.anomaly', 'catalog.project', 'catalog.rebuild')").update();
    }

    /** A clock the test moves. */
    static final class TestClock extends Clock {
        private volatile Instant now;

        TestClock(Instant now) { this.now = now; }

        void set(Instant instant) { now = instant; }

        void advance(Duration duration) { now = now.plus(duration); }

        @Override public ZoneId getZone() { return java.time.ZoneOffset.UTC; }

        @Override public Clock withZone(ZoneId zone) { return this; }

        @Override public Instant instant() { return now; }
    }
}
