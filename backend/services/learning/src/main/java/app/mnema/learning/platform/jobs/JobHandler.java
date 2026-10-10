package app.mnema.learning.platform.jobs;

/**
 * The work of one queue. A handler is a Spring bean; the executor offers it every job of {@link #queue()} and nothing else.
 *
 * <p><b>A slice is one short, bounded unit.</b> {@link #run} is called inside a database transaction that the executor opened and in which it holds the
 * row lock of the job: the handler's writes (through the ordinary {@code JdbcClient}, which joins that transaction) commit together with the
 * progress or the end of the job, or roll back together with it. It must therefore not call anything outside the database (a provider, object storage)
 * and must not run longer than a fraction of the queue's lease; a long job is many slices (a batch of rows and a cursor). Run again after a crash
 * or a lost lease, a slice starts from {@link Job#progress()} and sees exactly the effects of the slices that committed.
 *
 * <p><b>All writes belong to the slice's transaction.</b> The exactly-once guarantee holds only for what the handler writes through that transaction:
 * no {@code REQUIRES_NEW}, no {@code @Async}, no second {@code DataSource} or connection, no write that survives a rollback. Each running slice also holds
 * one pooled connection for its whole duration, which is why the executor caps all slices of the process at {@code learning.jobs.max-concurrency}.
 *
 * <p>A handler throws {@link PermanentJobException} for a failure no retry will cure (the job ends {@code FAILED}), {@link RetryableJobException} (or
 * any other runtime exception) for one that may pass (the slice rolls back and the job is requeued with a backoff until
 * {@code learning.jobs.<queue>.max-attempts}). A message of the exception is never stored; only its code is.
 */
public interface JobHandler {
    /** The queue this handler serves: {@code ^[a-z][a-z0-9_.-]{0,62}$}. At most one handler per queue. */
    String queue();

    /** Performs one bounded unit of work for {@code job} and says whether more remains. */
    Slice run(Job job);

    /**
     * Called when the job ends {@code FAILED} (a permanent error, the last attempt, the last lost lease) or {@code CANCELLED}, in the very transaction that
     * settles it, so a consumer can compensate (mark the copy as failed, release what the job reserved) atomically with the end of the job. {@code job}
     * carries the last committed {@link Job#progress()}. It is not called for {@code SUCCEEDED} (the last slice already did the work) nor for a retry.
     *
     * <p>If it throws, the settlement is rolled back and repeated without the call, so a faulty hook can never keep a job from ending; the failure is logged
     * and counted ({@code outcome="hook_failed"}) and the compensation is lost. Keep it small and idempotent.
     */
    default void onEnded(Job job, JobState state) {
        // nothing to compensate by default
    }
}
