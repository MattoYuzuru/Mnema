package app.mnema.learning.community;

import app.mnema.learning.platform.jobs.Job;
import app.mnema.learning.platform.jobs.JobHandler;
import app.mnema.learning.platform.jobs.Slice;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import tools.jackson.databind.node.JsonNodeFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

/** Keyset slices of active profiles. The account lock precedes reading any input, so delayed jobs cannot resurrect reset data. */
@Component
class InterestRebuild implements JobHandler {
    static final String QUEUE = "catalog.interests.rebuild";
    static final int BATCH = 25;
    private final InterestRepository repository;
    private final InterestService service;
    private final RecommendationSettings settings;
    private final Clock clock;

    @Autowired InterestRebuild(InterestRepository repository, InterestService service, RecommendationSettings settings) {
        this(repository, service, settings, Clock.systemUTC());
    }

    InterestRebuild(InterestRepository repository, InterestService service, RecommendationSettings settings, Clock clock) {
        this.repository = repository;
        this.service = service;
        this.settings = settings;
        this.clock = clock;
    }

    @Override public String queue() {
        return QUEUE;
    }

    @Override public Slice run(Job job) {
        if (!settings.forYouEnabled && !settings.eventsEnabled) return Slice.done(job.progressCount());
        UUID after = job.progress() == null ? new UUID(0, 0) : UUID.fromString(job.progress().path("after").stringValue(null));
        var accounts = repository.activeAccounts(after, clock.instant().minus(Duration.ofDays(30)), BATCH);
        accounts.forEach(service::rebuild);
        long count = job.progressCount() + accounts.size();
        return accounts.size()<BATCH ? Slice.done(count) : Slice.proceed(JsonNodeFactory.instance.objectNode().put("after", accounts.getLast().toString()), count);
    }
}
