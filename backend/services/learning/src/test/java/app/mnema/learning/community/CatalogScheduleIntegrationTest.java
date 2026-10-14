package app.mnema.learning.community;

import app.mnema.learning.platform.jobs.LearningJobs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Community/3 (#450): the periodic enqueuer of the catalogue's system jobs. The dedupe key IS the identity of the work: the UTC hour for the rank, the UTC date (from the configured
 * hour) for the anomaly query, so any number of instances, restarts and ticks produce exactly one job per hour / day, and a first start enqueues the current hour at once. The jobs
 * are not run here (the role is {@code api}); the executor part is {@link CatalogWorkerIntegrationTest}. Dates are far from today so that nothing collides with a real hour.
 */
@SpringBootTest
class CatalogScheduleIntegrationTest extends IsolatedCatalogDatabase {
    private static final Instant START = Instant.parse("2031-03-04T10:07:00Z");

    @Autowired private LearningJobs jobs;
    @Autowired private JdbcClient jdbc;
    private TestClock clock;

    @BeforeEach
    void setUp() {
        reset(jdbc);
        clock = new TestClock(START);
    }

    @AfterEach
    void cleanUp() {
        reset(jdbc);
    }

    private CatalogSchedule instance(RankSettings rankOn, AnomalySettings anomalyOn) {
        return new CatalogSchedule(jobs, rankOn, anomalyOn, clock);
    }

    private List<String> keys(String queue) {
        return jdbc.sql("SELECT dedupe_key FROM app_learning.learning_job WHERE queue = :q ORDER BY dedupe_key").param("q", queue).query(String.class).list();
    }

    @Test
    void twoWorkersAndManyTicksEnqueueOneRankJobPerHourAndOneAnomalyJobPerDay() {
        // the test profile switches the schedule off; this test turns it on explicitly
        RankSettings rankOn = new RankSettings(true, 30, 14, 3, 1.0, 2.0, 10, 0.25, 0.6, 120, Duration.ofHours(24));
        AnomalySettings anomalyOn = new AnomalySettings(true, 20, 7, "0.7", 3);
        CatalogSchedule first = new CatalogSchedule(jobs, rankOn, anomalyOn, clock);
        CatalogSchedule second = new CatalogSchedule(jobs, rankOn, anomalyOn, clock);

        // a first start (no generation exists): the current hour is enqueued at once, and so is the day's anomaly job (it is past 03:00 UTC)
        first.tick();
        second.tick();
        first.tick();
        assertThat(keys(CatalogRank.QUEUE)).containsExactly("rank:2031-03-04T10");
        assertThat(keys(CatalogAnomalyJob.QUEUE)).containsExactly("anomaly:2031-03-04");
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.learning_job WHERE queue = 'catalog.rank' AND account_id IS NULL").query(Long.class).single()).isEqualTo(1);

        clock.set(Instant.parse("2031-03-04T10:59:59Z"));
        first.tick();
        second.tick();
        assertThat(keys(CatalogRank.QUEUE)).containsExactly("rank:2031-03-04T10");

        clock.set(Instant.parse("2031-03-04T11:00:00Z"));
        first.tick();
        second.tick();
        assertThat(keys(CatalogRank.QUEUE)).containsExactly("rank:2031-03-04T10", "rank:2031-03-04T11");
        assertThat(keys(CatalogAnomalyJob.QUEUE)).containsExactly("anomaly:2031-03-04");

        // a restarted process (a new instance with an empty memory) meets the existing job of the hour and enqueues nothing new
        instance(rankOn, anomalyOn).tick();
        assertThat(keys(CatalogRank.QUEUE)).hasSize(2);

        clock.set(Instant.parse("2031-03-05T02:59:00Z"));
        first.tick();
        assertThat(keys(CatalogRank.QUEUE)).hasSize(3).contains("rank:2031-03-05T02");
        assertThat(keys(CatalogAnomalyJob.QUEUE)).as("the new day's query waits for its hour").containsExactly("anomaly:2031-03-04");
        clock.set(Instant.parse("2031-03-05T03:00:00Z"));
        first.tick();
        second.tick();
        assertThat(keys(CatalogAnomalyJob.QUEUE)).containsExactly("anomaly:2031-03-04", "anomaly:2031-03-05");
    }

    @Test
    void theKillSwitchesStopTheEnqueuingOfEachJobSeparately() {
        RankSettings rankOff = new RankSettings(false, 30, 14, 3, 1.0, 2.0, 10, 0.25, 0.6, 120, Duration.ofHours(24));
        AnomalySettings anomalyOn = new AnomalySettings(true, 20, 7, "0.7", 3);
        new CatalogSchedule(jobs, rankOff, anomalyOn, clock).tick();
        assertThat(keys(CatalogRank.QUEUE)).isEmpty();
        assertThat(keys(CatalogAnomalyJob.QUEUE)).hasSize(1);
        reset(jdbc);
        RankSettings rankOn = new RankSettings(true, 30, 14, 3, 1.0, 2.0, 10, 0.25, 0.6, 120, Duration.ofHours(24));
        new CatalogSchedule(jobs, rankOn, new AnomalySettings(false, 20, 7, "0.7", 3), clock).tick();
        assertThat(keys(CatalogRank.QUEUE)).hasSize(1);
        assertThat(keys(CatalogAnomalyJob.QUEUE)).isEmpty();
    }

    @Test
    void theQueuesAreServedByTheHandlersAndTheirPolicyIsTheOneOfApplicationProperties(@Autowired CatalogRank rankJob, @Autowired CatalogAnomalyJob anomalyJob,
                                                                                     @Autowired org.springframework.core.env.Environment environment) {
        assertThat(rankJob.queue()).isEqualTo("catalog.rank");
        assertThat(anomalyJob.queue()).isEqualTo("catalog.anomaly");
        assertThat(environment.getProperty("learning.jobs.catalog.rank.concurrency")).isEqualTo("1");
        assertThat(environment.getProperty("learning.jobs.catalog.rank.lease")).isEqualTo("PT5M");
        assertThat(environment.getProperty("learning.jobs.catalog.anomaly.concurrency")).isEqualTo("1");
    }
}
