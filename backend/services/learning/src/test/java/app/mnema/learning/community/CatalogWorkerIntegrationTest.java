package app.mnema.learning.community;

import app.mnema.learning.platform.jobs.JobState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Community/3 (#450), the whole chain in a {@code worker} process with the real scheduler and the real executor: a first start with no generation enqueues the job of the current hour
 * within the initial delay, the executor runs it, and the catalogue is ranked (an empty generation at zero load, current at once) instead of waiting for the next hour; the daily
 * anomaly job runs too. Nothing is reset here: the executor of this context also serves the jobs other classes of this database left queued.
 */
@SpringBootTest(properties = {"learning.runtime.roles=worker", "learning.community.rank.enabled=true", "learning.community.anomaly.enabled=true",
        "learning.community.anomaly.run-hour-utc=0", "learning.community.rank.initial-delay=PT0S", "learning.community.rank.schedule-interval=PT1S",
        "learning.jobs.sweep-interval=PT0.5S", "learning.ai.provider=stub", "learning.features.speech-to-text.enabled=true", "learning.speech.sweep-interval=PT5M",
        "learning.generation.worker.sweep-interval=PT5M", "learning.ai.assess.sweep-interval=PT15S"})
// The context is closed after the class: its executor and its scheduler would otherwise go on serving the jobs of the classes that share this database.
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class CatalogWorkerIntegrationTest extends IsolatedCatalogDatabase {
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH", java.util.Locale.ROOT);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd", java.util.Locale.ROOT);

    @Autowired private JdbcClient jdbc;
    @Autowired private CatalogRanks ranks;

    @Test
    void aFirstStartRanksTheCatalogueAtOnceAndTheDailyAnomalyJobRuns() throws InterruptedException {
        Instant before = Instant.now();
        List<String> hours = List.of("rank:" + HOUR.format(before.atZone(ZoneOffset.UTC)));
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        boolean ranked = false;
        boolean anomaly = false;
        while (System.nanoTime() < deadline && !(ranked && anomaly)) {
            String hour = "rank:" + HOUR.format(Instant.now().atZone(ZoneOffset.UTC));
            ranked = state("catalog.rank", hour) == JobState.SUCCEEDED || state("catalog.rank", hours.getFirst()) == JobState.SUCCEEDED;
            anomaly = state("catalog.anomaly", "anomaly:" + DAY.format(Instant.now().atZone(ZoneOffset.UTC))) == JobState.SUCCEEDED
                    || state("catalog.anomaly", "anomaly:" + DAY.format(before.atZone(ZoneOffset.UTC))) == JobState.SUCCEEDED;
            if (!(ranked && anomaly)) Thread.sleep(200);
        }
        assertThat(ranked).as("the rank job of the current hour succeeded").isTrue();
        assertThat(anomaly).as("the anomaly job of today succeeded").isTrue();
        OptionalLong current = ranks.current();
        assertThat(current).isPresent();
        assertThat(jdbc.sql("SELECT completed_at >= :before FROM app_learning.rank_generation WHERE generation = :g").param("before", java.sql.Timestamp.from(before.minusSeconds(1)))
                .param("g", current.getAsLong()).query(Boolean.class).single()).isTrue();
        // one job per hour however often the scheduler ticks (it ticks every second here)
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.learning_job WHERE queue = 'catalog.rank' AND dedupe_key = :k").param("k", hours.getFirst()).query(Long.class).single())
                .isLessThanOrEqualTo(1);
    }

    private JobState state(String queue, String key) {
        return jdbc.sql("SELECT state FROM app_learning.learning_job WHERE queue = :q AND dedupe_key = :k").param("q", queue).param("k", key).query(String.class).optional()
                .map(JobState::valueOf).orElse(null);
    }
}
