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

/** One UTC-day key per job, remembered in-process: idle ticks cause no DB work. Retention has no product kill switch. */
@Component
@ConditionalOnExpression("'${learning.runtime.roles:all}'.trim().toLowerCase() == 'worker' or '${learning.runtime.roles:all}'.trim().toLowerCase() == 'all'")
class RecommendationSchedule {
    private static final Logger LOG = LoggerFactory.getLogger(RecommendationSchedule.class);
    private final LearningJobs jobs;
    private final RecommendationSettings settings;
    private final Clock clock;
    private String partitionsDay;
    private String interestsDay;
    private String receiptsDay;

    @Autowired RecommendationSchedule(LearningJobs jobs, RecommendationSettings settings) {
        this(jobs, settings, Clock.systemUTC());
    }

    RecommendationSchedule(LearningJobs jobs, RecommendationSettings settings, Clock clock) {
        this.jobs = jobs;
        this.settings = settings;
        this.clock = clock;
    }

    @Scheduled(initialDelayString = "${learning.community.events.initial-delay:PT30S}", fixedDelayString = "${learning.community.events.schedule-interval:PT1M}")
    synchronized void tick() {
        var now = clock.instant().atZone(ZoneOffset.UTC);
        String day = now.toLocalDate().toString();
        if (!day.equals(partitionsDay) && enqueue(CatalogEventPartitions.QUEUE, "partitions:" + day, day)) partitionsDay = day;
        if (!day.equals(receiptsDay) && enqueue(CatalogEventReceiptRetention.QUEUE, "event-receipts:" + day, day)) receiptsDay = day;
        if ((settings.forYouEnabled || settings.eventsEnabled) && now.getHour() >= 3 && !day.equals(interestsDay) && enqueue(InterestRebuild.QUEUE, "interests:" + day, day)) interestsDay = day;
    }

    private boolean enqueue(String queue, String key, String day) {
        try {
            jobs.enqueue(queue, key, null, JsonNodeFactory.instance.objectNode().put("day", day));
            return true;
        }
        catch (RuntimeException failure) {
            LOG.warn("recommendation_schedule_enqueue_failed queue={} error_type={}", queue, failure.getClass().getSimpleName());
            return false;
        }
    }
}
