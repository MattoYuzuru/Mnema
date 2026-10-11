package app.mnema.learning.update;

import app.mnema.learning.platform.jobs.Job;
import app.mnema.learning.platform.jobs.JobHandler;
import app.mnema.learning.platform.jobs.JobState;
import app.mnema.learning.platform.jobs.Slice;
import org.springframework.stereotype.Component;

/**
 * The handler of the queue {@value CombinedPublication#QUEUE}: a combined publication above the synchronous limit (Updates/2, #441). Everything it does is
 * {@link CombinedPublication#slice}; the executor owns the lease, the per-account limit of one running command per owner, the retries and the exactly-once commit of a slice
 * with its cursor. Policy: {@code learning.jobs.deck.combined.publish.*} (enabled, concurrency, lease, max-attempts, backoff) and {@code learning.updates.*}.
 */
@Component
class CombinedPublicationJob implements JobHandler {
    private final CombinedPublication publication;

    CombinedPublicationJob(CombinedPublication publication) { this.publication = publication; }

    @Override
    public String queue() { return CombinedPublication.QUEUE; }

    @Override
    public Slice run(Job job) { return publication.slice(job); }

    @Override
    public void onEnded(Job job, JobState state) { publication.ended(job, state); }
}
