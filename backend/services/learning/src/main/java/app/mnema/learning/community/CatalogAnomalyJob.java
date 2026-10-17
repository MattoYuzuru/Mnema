package app.mnema.learning.community;

import app.mnema.learning.platform.jobs.Job;
import app.mnema.learning.platform.jobs.JobHandler;
import app.mnema.learning.platform.jobs.JobState;
import app.mnema.learning.platform.jobs.Slice;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Locale;

/**
 * The daily anomaly job (queue {@value #QUEUE}, a system job, Community/3, #450): one statement ({@link AnomalyRepository#detect}) that raises the flags of the owner console for a
 * burst of copies by accounts younger than 48 hours. {@link CatalogSchedule} enqueues it once per UTC day ({@code anomaly:2026-10-12}). It reads the engagement table once (H: about
 * 300 000 rows, a fraction of a second, measured in the guide) and writes only the few flagged decks.
 */
@Component
public class CatalogAnomalyJob implements JobHandler {
    /** The queue of the daily anomaly job. */
    public static final String QUEUE = "catalog.anomaly";
    private static final Logger LOG = LoggerFactory.getLogger(CatalogAnomalyJob.class);

    private final AnomalyRepository repository;
    private final AnomalySettings settings;
    private final MeterRegistry meters;
    private final Clock clock;

    @Autowired
    CatalogAnomalyJob(AnomalyRepository repository, AnomalySettings settings, MeterRegistry meters) {
        this(repository, settings, meters, Clock.systemUTC());
    }

    CatalogAnomalyJob(AnomalyRepository repository, AnomalySettings settings, MeterRegistry meters, Clock clock) {
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
        int flagged = repository.detect(clock.instant(), settings);
        meters.counter("mnema_catalog_anomaly_flags_total").increment(flagged);
        if (flagged > 0) LOG.info("catalog_anomaly_flagged decks={}", flagged);
        return Slice.done(flagged);
    }

    @Override
    public void onEnded(Job job, JobState state) {
        meters.counter("mnema_catalog_anomaly_ended_total", "outcome", state.name().toLowerCase(Locale.ROOT)).increment();
        LOG.warn("catalog_anomaly_ended job_id={} state={}", job.id(), state);
    }
}
