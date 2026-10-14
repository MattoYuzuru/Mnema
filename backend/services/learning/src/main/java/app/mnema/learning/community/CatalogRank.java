package app.mnema.learning.community;

import app.mnema.learning.platform.jobs.Job;
import app.mnema.learning.platform.jobs.JobHandler;
import app.mnema.learning.platform.jobs.JobState;
import app.mnema.learning.platform.jobs.Slice;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;

/**
 * The rank job (queue {@value #QUEUE}, a system job, Community/3, #450): ONE slice that builds the next generation of {@code deck_rank} for every {@code public_deck} row and
 * makes it current, then deletes the generations that are past their retention. The slice is one transaction (the executor's), so a generation is either complete and current
 * or does not exist; a killed worker leaves nothing, and a retried job builds the generation again. {@link CatalogSchedule} enqueues it with the dedupe key of the current hour
 * ({@code rank:2026-10-12T10}), so there is exactly one generation per hour however many instances run.
 *
 * <p>It stays one slice up to H (5 000 public decks, 300 000 engagement rows: about a second, see the guide); past roughly ten times that the engagement fold would need slicing by
 * deck-id range with a cursor, and the generation would be published by the last slice's {@code complete}.
 */
@Component
public class CatalogRank implements JobHandler {
    /** The queue of the hourly rank job. */
    public static final String QUEUE = "catalog.rank";
    private static final Logger LOG = LoggerFactory.getLogger(CatalogRank.class);

    private final RankRepository repository;
    private final RankSettings settings;
    private final MeterRegistry meters;
    private final Clock clock;

    @Autowired
    CatalogRank(RankRepository repository, RankSettings settings, MeterRegistry meters) {
        this(repository, settings, meters, Clock.systemUTC());
    }

    CatalogRank(RankRepository repository, RankSettings settings, MeterRegistry meters, Clock clock) {
        this.repository = repository;
        this.settings = settings;
        this.meters = meters;
        this.clock = clock;
    }

    @Override
    public String queue() {
        return QUEUE;
    }

    @Override
    public Slice run(Job job) {
        Timer.Sample sample = Timer.start(meters);
        Instant now = clock.instant();
        long generation = repository.open(now);
        int decks = repository.fill(generation, now, settings);
        repository.complete(generation, decks, clock.instant());
        int pruned = repository.prune(now, settings.retention);
        sample.stop(meters.timer("mnema_catalog_rank_seconds"));
        meters.counter("mnema_catalog_rank_total", "outcome", "generated").increment();
        LOG.info("catalog_rank_generated generation={} decks={} pruned_generations={}", generation, decks, pruned);
        return Slice.done(decks);
    }

    /** A rank job that ends {@code FAILED} or {@code CANCELLED} leaves the previous generation current: counted and logged so that it is noticed (the age gauge grows meanwhile). */
    @Override
    public void onEnded(Job job, JobState state) {
        meters.counter("mnema_catalog_rank_ended_total", "outcome", state.name().toLowerCase(Locale.ROOT)).increment();
        LOG.warn("catalog_rank_ended job_id={} state={}", job.id(), state);
    }
}
