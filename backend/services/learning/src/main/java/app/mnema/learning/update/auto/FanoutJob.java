package app.mnema.learning.update.auto;

import app.mnema.learning.platform.jobs.Job;
import app.mnema.learning.platform.jobs.JobHandler;
import app.mnema.learning.platform.jobs.JobStatus;
import app.mnema.learning.platform.jobs.LearningJobs;
import app.mnema.learning.platform.jobs.PermanentJobException;
import app.mnema.learning.platform.jobs.Slice;
import app.mnema.learning.update.auto.FanoutRepository.Candidate;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The handler of the queue {@value AutoUpdateFanout#FANOUT_QUEUE} (Updates/6 part B, #445): the fan-out of one publication (payload {@code {deckId, revisionId}}, a system job).
 * Each slice takes the allowance of {@link AutoThrottle}, reads one bounded page of the source's automatic copies ({@link FanoutRepository}), and for every ACTIVE one enqueues the
 * automatic apply {@value AutoUpdateFanout#AUTO_QUEUE} (dedupe key {@code copyId:revisionId}, account = the copy's owner, so the executor's limit of one running job per account
 * and queue applies). The cursor is the last copy looked at, so a crash or a rollback repeats at most one slice and the dedupe keys make the repeat harmless. When the allowance is
 * spent the slice commits its cursor and the job goes back to the queue until some of the minute is free ({@link Slice#proceedAfter}): no thread or connection waits.
 *
 * <p>A fan-out whose revision is no longer the one the source publishes ends at once (the newer publication's fan-out covers it).
 *
 * <p>With the kill switch off ({@code learning.updates.auto.enabled}) the job ends at once, enqueueing nothing. A source with no automatic copies, or none that is active,
 * costs one empty page.
 */
@Component
class FanoutJob implements JobHandler {
    private static final Logger LOG = LoggerFactory.getLogger(FanoutJob.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final FanoutRepository repository;
    private final LearningJobs jobs;
    private final AutoUpdateSettings settings;
    private final AutoThrottle throttle;
    private final MeterRegistry meters;

    FanoutJob(FanoutRepository repository, LearningJobs jobs, AutoUpdateSettings settings, AutoThrottle throttle, MeterRegistry meters) {
        this.repository = repository;
        this.jobs = jobs;
        this.settings = settings;
        this.throttle = throttle;
        this.meters = meters;
    }

    @Override
    public String queue() { return AutoUpdateFanout.FANOUT_QUEUE; }

    @Override
    public Slice run(Job job) {
        long enqueued = job.progressCount();
        if (!settings.enabled()) {
            meters.counter("mnema_deck_update_fanout_total", "outcome", "disabled").increment();
            return Slice.done(enqueued);
        }
        UUID source = uuid(job, "deckId");
        UUID revision = uuid(job, "revisionId");
        UUID after = job.progress() == null ? null : optional(job.progress().path("after").stringValue(null));
        // a newer publication has its own fan-out, which covers every copy this one would: two overlapping fan-outs (a long, paced one and the next publication) never hand
        // the same copies out twice under different revisions
        if (!repository.published(source).map(revision::equals).orElse(false)) {
            meters.counter("mnema_deck_update_fanout_total", "outcome", "superseded").increment();
            return Slice.done(enqueued);
        }
        recordAge(job);

        int granted = throttle.acquire(settings.sliceSize());
        if (granted == 0) {
            meters.counter("mnema_deck_update_fanout_total", "outcome", "paced").increment();
            Duration wait = throttle.retryIn();
            return Slice.proceedAfter(cursor(after), enqueued, wait.isZero() ? Duration.ofSeconds(1) : wait);
        }
        int scan = settings.scanSize();
        Instant since = throttle.clock().instant().minus(settings.activeWindow());
        List<Candidate> page = repository.page(source, revision, after, since, scan);
        int used = 0;
        int idle = 0;
        UUID last = after;
        boolean stopped = false;
        for (Candidate candidate : page) {
            if (candidate.active()) {
                if (used == granted) {
                    stopped = true;
                    break;
                }
                jobs.enqueue(AutoUpdateFanout.AUTO_QUEUE, candidate.deckId() + ":" + revision, candidate.ownerId(), AutoUpdateFanout.payload(candidate.deckId(), revision));
                used++;
            } else {
                idle++;
            }
            last = candidate.deckId();
        }
        throttle.giveBack(granted - used);
        if (used > 0) meters.counter("mnema_deck_update_fanout_copies_total", "outcome", "enqueued").increment(used);
        if (idle > 0) meters.counter("mnema_deck_update_fanout_copies_total", "outcome", "inactive").increment(idle);
        enqueued += used;
        if (!stopped && page.size() < scan) {
            meters.counter("mnema_deck_update_fanout_total", "outcome", "done").increment();
            LOG.info("deck_update_fanout_done source_deck_id={} revision_id={} enqueued={}", source, revision, enqueued);
            return Slice.done(enqueued);
        }
        return Slice.proceed(cursor(last), enqueued);
    }

    /** How long the fan-out has been going: the oldest figure of this summary is the queue age of §14. */
    private void recordAge(Job job) {
        Optional<JobStatus> status = jobs.status(job.id());
        status.ifPresent(found -> meters.summary("mnema_deck_update_fanout_age_seconds")
                .record(Math.max(0, Duration.between(found.createdAt(), throttle.clock().instant()).toMillis() / 1000.0)));
    }

    private static ObjectNode cursor(UUID after) {
        ObjectNode node = JSON.createObjectNode();
        if (after == null) node.putNull("after"); else node.put("after", after.toString());
        return node;
    }

    private static UUID uuid(Job job, String field) {
        UUID found = optional(job.payload().path(field).stringValue(null));
        if (found == null) throw new PermanentJobException("payload_invalid");
        return found;
    }

    private static UUID optional(String text) {
        if (text == null) return null;
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException invalid) {
            throw new PermanentJobException("payload_invalid");
        }
    }
}
