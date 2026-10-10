package app.mnema.learning.platform.jobs;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.node.ObjectNode;

import java.util.Optional;
import java.util.UUID;

/**
 * The entry point of the generic background job executor (community decks architecture section 2): what a command or another module uses to hand
 * work to the workers, to show its progress and to cancel it. It exists in every runtime role; the {@link JobExecutor} that runs the jobs exists only
 * in {@code worker} and {@code all}, so an {@code api} process enqueues and reads but never executes.
 *
 * <p>A job is identified by {@code (queue, dedupeKey)}, which the caller derives from what the work is for (a command id, "{deck}:{revision}"): enqueueing
 * the same pair again returns the same job and creates nothing, so a retried request or a replayed command cannot start the work twice. Jobs are
 * claimed per queue under the queue's policy ({@link JobSettings}) with at most one running job per account and queue.
 */
@Service
public class LearningJobs {
    private final JobRepository repository;
    private final JobSettings settings;
    private final ObjectProvider<JobExecutor> executor;

    LearningJobs(JobRepository repository, JobSettings settings, ObjectProvider<JobExecutor> executor) {
        this.repository = repository;
        this.settings = settings;
        this.executor = executor;
    }

    /**
     * Enqueues a job, in the caller's transaction when there is one, so a command can create its own rows and its job atomically; the workers are woken
     * only after that transaction commits.
     *
     * <p><b>Idempotency horizon.</b> The key is remembered as long as the job row exists: while the job runs, and then for
     * {@code learning.jobs.retention} (7 days) after it ended. Past that horizon retention deletes the row and the same key creates a new job, so a caller
     * whose duplicates can arrive later than that needs a guard of its own. A job that ended {@code FAILED} or {@code CANCELLED} is not replaced by
     * enqueueing its key again (the existing job is returned); use {@link #retry} for that.
     *
     * @param queue the resource class, {@code ^[a-z][a-z0-9_.-]{0,62}$}; its policy is {@code learning.jobs.<queue>.*}
     * @param dedupeKey the identity of the work within the queue, 1-200 characters
     * @param accountId the subject of the per-account limit of one running job per queue; {@code null} for a system job
     * @param payload the immutable input, an object of at most 16 KiB; never a secret or content that belongs in a table of its own
     * @return the id of the new job, or of the job that already held {@code (queue, dedupeKey)}
     * @throws JobIdentityConflictException when the existing job was enqueued for another account or with another payload
     * @throws IllegalArgumentException when an argument is out of its bounds
     */
    @Transactional
    public UUID enqueue(String queue, String dedupeKey, UUID accountId, ObjectNode payload) {
        JobCodes.queue(queue);
        if (dedupeKey == null || dedupeKey.isEmpty() || dedupeKey.length() > 200) throw new IllegalArgumentException("dedupeKey must be 1-200 characters");
        String text = JobJson.write(payload, "payload");
        int maxAttempts = settings.queue(queue).maxAttempts();
        for (int round = 0; round < 3; round++) {
            Optional<UUID> inserted = repository.insert(queue, dedupeKey, accountId, text, maxAttempts);
            if (inserted.isPresent()) {
                wakeAfterCommit();
                return inserted.get();
            }
            Optional<JobRepository.Existing> existing = repository.existing(queue, dedupeKey, accountId, text);
            if (existing.isPresent()) {
                if (!existing.get().same()) throw new JobIdentityConflictException(queue);
                return existing.get().id();
            }
            // the holder of the key was deleted by retention between the two statements: the key is free again
        }
        throw new IllegalStateException("Could not enqueue a job");
    }

    /** The state, progress and attempts of a job; empty for an unknown or already purged job. */
    @Transactional(readOnly = true)
    public Optional<JobStatus> status(UUID jobId) {
        return repository.status(jobId);
    }

    /**
     * Cancels a job, in one statement (no claim can slip in between a check and the write). A queued job is cancelled at once; a running one is asked to stop
     * and ends {@code CANCELLED} before its next slice (the slice in flight completes and commits); a finished one is left as it is.
     *
     * @return the state the job is in now; empty for an unknown job
     * @throws org.springframework.dao.CannotAcquireLockException when a slice holds the job's row for longer than 2 s; nothing changed, try again
     */
    @Transactional
    public Optional<JobState> cancel(UUID jobId) {
        Optional<JobState> cancelled = repository.cancel(jobId);
        if (cancelled.isPresent()) return cancelled;
        return repository.status(jobId).map(JobStatus::state);
    }

    /**
     * Requeues a job that ended {@code FAILED} or {@code CANCELLED}, under the same id and key: its failures are forgotten ({@code attempts = 0}), its cursor
     * is kept, so the handler resumes where the job stopped. Any other state is left alone.
     *
     * @return whether the job was requeued
     */
    @Transactional
    public boolean retry(UUID jobId) {
        boolean requeued = repository.retry(jobId);
        if (requeued) wakeAfterCommit();
        return requeued;
    }

    private void wakeAfterCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            executor.ifAvailable(JobExecutor::wake);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                executor.ifAvailable(JobExecutor::wake);
            }
        });
    }
}
