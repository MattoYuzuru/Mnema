package app.mnema.learning.community;

import app.mnema.learning.platform.jobs.LearningJobs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.JsonNodeFactory;

import java.time.Clock;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The periodic enqueuer of the catalogue's system jobs (Community/3, #450). Like the other workers it exists only for the {@code worker} and {@code all} roles
 * ({@code learning.runtime.roles}); an {@code api} process never schedules. Every minute it enqueues
 * <ul>
 *   <li>{@value CatalogRank#QUEUE} with the dedupe key of the current UTC hour ({@code rank:2026-10-12T10}): the key is the generation's identity, so across any number of instances (and
 *       restarts) exactly one job exists per hour, and a first start builds the first generation within the initial delay and the job's seconds instead of leaving the catalogue
 *       unranked for an hour;</li>
 *   <li>{@value CatalogAnomalyJob#QUEUE} with the dedupe key of the UTC date ({@code anomaly:2026-10-12}), once the hour {@code learning.community.anomaly.run-hour-utc} (3) has come.</li>
 * </ul>
 * A process remembers the last key it enqueued, so the steady state is no statement at all; an enqueue that fails is retried at the next tick. The kill switches are
 * {@code learning.community.rank.enabled} and {@code learning.community.anomaly.enabled} (and the queues' own {@code learning.jobs.<queue>.enabled}).
 */
@Component
@ConditionalOnExpression("'${learning.runtime.roles:all}'.trim().toLowerCase() == 'worker' or "
        + "'${learning.runtime.roles:all}'.trim().toLowerCase() == 'all'")
class CatalogSchedule {
    private static final Logger LOG = LoggerFactory.getLogger(CatalogSchedule.class);
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH", java.util.Locale.ROOT);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd", java.util.Locale.ROOT);

    private final LearningJobs jobs;
    private final RankSettings rank;
    private final AnomalySettings anomaly;
    private final Clock clock;
    private final AtomicReference<String> lastRank = new AtomicReference<>();
    private final AtomicReference<String> lastAnomaly = new AtomicReference<>();

    @Autowired
    CatalogSchedule(LearningJobs jobs, RankSettings rank, AnomalySettings anomaly) {
        this(jobs, rank, anomaly, Clock.systemUTC());
    }

    CatalogSchedule(LearningJobs jobs, RankSettings rank, AnomalySettings anomaly, Clock clock) {
        this.jobs = jobs;
        this.rank = rank;
        this.anomaly = anomaly;
        this.clock = clock;
    }

    @Scheduled(initialDelayString = "${learning.community.rank.initial-delay:PT30S}", fixedDelayString = "${learning.community.rank.schedule-interval:PT1M}")
    void tick() {
        ZonedDateTime now = clock.instant().atZone(ZoneOffset.UTC);
        if (rank.enabled) {
            String hour = HOUR.format(now);
            enqueue(lastRank, CatalogRank.QUEUE, "rank:" + hour, "hour", hour);
        }
        if (anomaly.enabled && now.getHour() >= anomaly.runHourUtc) {
            String day = DAY.format(now);
            enqueue(lastAnomaly, CatalogAnomalyJob.QUEUE, "anomaly:" + day, "day", day);
        }
    }

    private void enqueue(AtomicReference<String> last, String queue, String key, String field, String value) {
        if (key.equals(last.get())) return;
        try {
            jobs.enqueue(queue, key, null, JsonNodeFactory.instance.objectNode().put(field, value));
            last.set(key);
        } catch (RuntimeException failure) {
            LOG.warn("catalog_schedule_enqueue_failed queue={} error_type={}", queue, failure.getClass().getSimpleName());
        }
    }
}
