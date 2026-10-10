package app.mnema.learning.platform.jobs;

import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.node.ObjectNode;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The SQL of {@code app_learning.learning_job}. Every statement is one short unit and none holds a handler call. Every write of a running job is
 * conditional on {@code lease_token}: a worker whose lease was lost (expired and swept, or the job cancelled meanwhile) changes nothing.
 */
@Repository
class JobRepository {
    /** A job as a worker holds it after the claim (its payload and identity; the progress is read under the lock of each slice). */
    record Claimed(UUID id, String queue, UUID accountId, UUID token, ObjectNode payload, int attempt) {
    }

    /** The row of a running job under the lock of a slice. */
    record Locked(ObjectNode progress, long progressCount, boolean cancelRequested) {
    }

    /** A job moved by a sweep or a settlement: its id, queue, the state it is in now and the job as {@link JobHandler#onEnded} sees it (its {@code attempt} is the one that just failed). */
    record Moved(UUID id, String queue, JobState state, Job job) {
    }

    private static final String COLUMNS = "job_id,queue,account_id,state,attempts,max_attempts,progress_count,progress::text AS progress,cancel_requested,"
            + "last_error,created_at,updated_at,finished_at";
    private static final String CANCEL_LOCK_TIMEOUT = "2s";
    private static final int CLAIM_RETRIES = 5;
    private static final int DEPTH_CAP = 100_000;

    private final JdbcClient jdbc;

    JobRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ enqueue and read (the caller's transaction)

    /** Inserts the job {@code QUEUED} and due now; empty when {@code (queue, dedupeKey)} already exists (committed, or in a concurrent transaction that committed). */
    Optional<UUID> insert(String queue, String dedupeKey, UUID accountId, String payload, int maxAttempts) {
        return jdbc.sql("""
                INSERT INTO app_learning.learning_job(queue,dedupe_key,account_id,payload,state,max_attempts,run_after)
                VALUES (:queue,:key,:account,CAST(:payload AS jsonb),'QUEUED',:max,clock_timestamp())
                ON CONFLICT (queue, dedupe_key) DO NOTHING RETURNING job_id
                """).param("queue", queue).param("key", dedupeKey).param("account", accountId).param("payload", payload).param("max", maxAttempts)
                .query(UUID.class).optional();
    }

    /** The job already holding {@code (queue, dedupeKey)} and whether it was enqueued for the same account and payload. */
    Optional<Existing> existing(String queue, String dedupeKey, UUID accountId, String payload) {
        return jdbc.sql("""
                SELECT job_id, account_id IS NOT DISTINCT FROM :account AND payload = CAST(:payload AS jsonb) AS same
                FROM app_learning.learning_job WHERE queue=:queue AND dedupe_key=:key
                """).param("queue", queue).param("key", dedupeKey).param("account", accountId).param("payload", payload)
                .query((row, ignored) -> new Existing(row.getObject("job_id", UUID.class), row.getBoolean("same"))).optional();
    }

    record Existing(UUID id, boolean same) {
    }

    Optional<JobStatus> status(UUID jobId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM app_learning.learning_job WHERE job_id=:id").param("id", jobId)
                .query((row, ignored) -> status(row)).optional();
    }

    /**
     * Cancels in one statement, so no claim can slip between a check and a write: a {@code QUEUED} job ends {@code CANCELLED} at once, a {@code RUNNING} one
     * gets {@code cancel_requested}. The row lock of a slice in flight is waited for at most {@value #CANCEL_LOCK_TIMEOUT}; then the statement fails with a
     * lock timeout ({@code CannotAcquireLockException}) and nothing changed. Empty when the job is finished or unknown.
     */
    Optional<JobState> cancel(UUID jobId) {
        String previous = jdbc.sql("SELECT current_setting('lock_timeout')").query(String.class).single();
        jdbc.sql("SELECT set_config('lock_timeout', :value, true)").param("value", CANCEL_LOCK_TIMEOUT).query(String.class).single();
        // on a lock timeout the transaction is aborted and rolled back by the caller, which also drops the setting: it is restored only after success
        Optional<JobState> cancelled;
        try {
            cancelled = jdbc.sql("""
                    UPDATE app_learning.learning_job SET
                        state = CASE WHEN state = 'QUEUED' THEN 'CANCELLED' ELSE state END,
                        cancel_requested = cancel_requested OR state = 'RUNNING',
                        finished_at = CASE WHEN state = 'QUEUED' THEN clock_timestamp() ELSE finished_at END,
                        updated_at = clock_timestamp()
                    WHERE job_id=:id AND state IN ('QUEUED','RUNNING') RETURNING state
                    """).param("id", jobId).query((row, ignored) -> JobState.valueOf(row.getString("state"))).optional();
        } catch (UncategorizedSQLException failure) {
            // Spring does not map SQLSTATE 55P03 (lock_not_available) of the PostgreSQL driver
            if (failure.getSQLException() != null && "55P03".equals(failure.getSQLException().getSQLState())) {
                throw new CannotAcquireLockException("The job is locked by a slice in flight", failure);
            }
            throw failure;
        }
        jdbc.sql("SELECT set_config('lock_timeout', :value, true)").param("value", previous).query(String.class).single();
        return cancelled;
    }

    /** Requeues a {@code FAILED} or {@code CANCELLED} job under its own key: its failures are forgotten, its cursor kept. False for any other state. */
    boolean retry(UUID jobId) {
        return jdbc.sql("UPDATE app_learning.learning_job SET state='QUEUED', attempts=0, cancel_requested=FALSE, last_error=NULL, finished_at=NULL, "
                + "run_after=clock_timestamp(), updated_at=clock_timestamp() WHERE job_id=:id AND state IN ('FAILED','CANCELLED')")
                .param("id", jobId).update() > 0;
    }

    // ------------------------------------------------------------------ the worker

    /**
     * Claims the oldest due {@code QUEUED} job of {@code queue} whose account has no job running in it, skipping rows another worker holds. The
     * per-account limit of one is enforced by {@code learning_job_account_running}: of two claims of the same account that both pass the check, the
     * second one fails on the index, and the claim simply looks again (the account is then blocked by the winner). {@code attempts} counts failures, so the
     * claim does not change it; the {@code attempt} of the result is the number of this try.
     */
    Optional<Claimed> claim(String queue, UUID token, Duration lease) {
        for (int attempt = 0; attempt < CLAIM_RETRIES; attempt++) {
            try {
                return jdbc.sql("""
                        UPDATE app_learning.learning_job SET state='RUNNING', lease_token=:token,
                            lease_until=clock_timestamp() + (:lease * interval '1 millisecond'), updated_at=clock_timestamp()
                        WHERE job_id = (SELECT j.job_id FROM app_learning.learning_job j
                                         WHERE j.queue=:queue AND j.state='QUEUED' AND j.run_after <= now()
                                           AND (j.account_id IS NULL OR NOT EXISTS (SELECT 1 FROM app_learning.learning_job r
                                                 WHERE r.queue=j.queue AND r.account_id=j.account_id AND r.state='RUNNING'))
                                         ORDER BY j.run_after, j.job_id FOR UPDATE OF j SKIP LOCKED LIMIT 1)
                        RETURNING job_id, account_id, payload::text AS payload, attempts + 1 AS attempt
                        """).param("queue", queue).param("token", token).param("lease", lease.toMillis())
                        .query((row, ignored) -> new Claimed(row.getObject("job_id", UUID.class), queue, row.getObject("account_id", UUID.class), token,
                                JobJson.read(row.getString("payload")), row.getInt("attempt"))).optional();
            } catch (DuplicateKeyException lost) {
                // another worker took a job of the same account at the same moment: look again, that account is blocked now
            }
        }
        return Optional.empty();
    }

    /** Locks the running job of {@code token} for the transaction of a slice; empty when the lease is gone (the slice must not run). */
    Optional<Locked> lock(UUID jobId, UUID token) {
        return jdbc.sql("SELECT progress::text AS progress, progress_count, cancel_requested FROM app_learning.learning_job "
                        + "WHERE job_id=:id AND lease_token=:token AND state='RUNNING' FOR UPDATE").param("id", jobId).param("token", token)
                .query((row, ignored) -> new Locked(JobJson.read(row.getString("progress")), row.getLong("progress_count"), row.getBoolean("cancel_requested")))
                .optional();
    }

    /** Commits the cursor of a slice and renews the lease; progress forgives earlier failures ({@code attempts = 0}). The caller holds the row lock, so false means a defect, not a race. */
    boolean progress(UUID jobId, UUID token, String progress, long count, Duration lease) {
        return jdbc.sql("UPDATE app_learning.learning_job SET progress=CAST(:progress AS jsonb), progress_count=:count, attempts=0, "
                        + "lease_until=clock_timestamp() + (:lease * interval '1 millisecond'), updated_at=clock_timestamp() "
                        + "WHERE job_id=:id AND lease_token=:token AND state='RUNNING'")
                .param("progress", progress).param("count", count).param("lease", lease.toMillis()).param("id", jobId).param("token", token).update() > 0;
    }

    boolean succeed(UUID jobId, UUID token, long count) {
        return jdbc.sql("UPDATE app_learning.learning_job SET state='SUCCEEDED', progress_count=:count, attempts=0, lease_token=NULL, lease_until=NULL, "
                        + "last_error=NULL, finished_at=clock_timestamp(), updated_at=clock_timestamp() WHERE job_id=:id AND lease_token=:token AND state='RUNNING'")
                .param("count", count).param("id", jobId).param("token", token).update() > 0;
    }

    /** Ends a running job whose cancellation was requested. */
    boolean cancelled(UUID jobId, UUID token) {
        return jdbc.sql("UPDATE app_learning.learning_job SET state='CANCELLED', lease_token=NULL, lease_until=NULL, finished_at=clock_timestamp(), "
                        + "updated_at=clock_timestamp() WHERE job_id=:id AND lease_token=:token AND state='RUNNING'")
                .param("id", jobId).param("token", token).update() > 0;
    }

    /** Gives a job back that did not fail (a clean shutdown, a transaction that could not even start): requeued after {@code delay}, no attempt spent. */
    boolean release(UUID jobId, UUID token, Duration delay) {
        return jdbc.sql("UPDATE app_learning.learning_job SET state='QUEUED', lease_token=NULL, lease_until=NULL, "
                        + "run_after=clock_timestamp() + (:delay * interval '1 millisecond'), updated_at=clock_timestamp() "
                        + "WHERE job_id=:id AND lease_token=:token AND state='RUNNING'")
                .param("delay", delay.toMillis()).param("id", jobId).param("token", token).update() > 0;
    }

    private static final String SETTLE = """
            UPDATE app_learning.learning_job j SET
                state = n.next,
                attempts = j.attempts + 1,
                run_after = CASE WHEN n.next = 'QUEUED'
                    THEN clock_timestamp() + (LEAST(CAST(:capMs AS double precision), CAST(:baseMs AS double precision) * power(2, LEAST(j.attempts, 30)))
                         * (0.8 + 0.2 * random()) * interval '1 millisecond') ELSE j.run_after END,
                lease_token = NULL, lease_until = NULL,
                last_error = CASE WHEN n.next = 'CANCELLED' THEN j.last_error ELSE :code END,
                finished_at = CASE WHEN n.next = 'QUEUED' THEN NULL ELSE clock_timestamp() END,
                updated_at = clock_timestamp()
            FROM n WHERE j.job_id = n.job_id
            RETURNING j.job_id, j.queue, j.state, j.account_id, j.payload::text AS payload, j.progress::text AS progress, j.progress_count, j.attempts
            """;
    private static final String NEXT = """
            SELECT job_id, CASE WHEN cancel_requested THEN 'CANCELLED' WHEN :permanent OR attempts + 1 >= max_attempts THEN 'FAILED' ELSE 'QUEUED' END AS next
            FROM app_learning.learning_job
            """;

    /**
     * Ends the failed attempt of the job of {@code token}: {@code QUEUED} again with an exponential delay ({@code backoff} doubled per failure, up to
     * {@code backoffMax}, each delay shortened by up to 20 % at random so that retries of a burst do not return together), or {@code FAILED} when
     * permanent or out of attempts, or {@code CANCELLED} when a cancellation was waiting. Empty when the lease is gone.
     */
    Optional<Moved> settleFailure(UUID jobId, UUID token, String code, boolean permanent, Duration backoff, Duration backoffMax) {
        return jdbc.sql("WITH n AS (" + NEXT + " WHERE job_id=:id AND lease_token=:token AND state='RUNNING' FOR UPDATE) " + SETTLE)
                .param("id", jobId).param("token", token).param("code", code).param("permanent", permanent)
                .param("baseMs", backoff.toMillis()).param("capMs", backoffMax.toMillis()).query(JobRepository::moved).optional();
    }

    /** Whether any job of {@code queue} has an expired lease: one probe of the lease index, the whole idle cost of the sweep. */
    boolean hasExpired(String queue) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM app_learning.learning_job WHERE queue=:queue AND state='RUNNING' AND lease_until < now())")
                .param("queue", queue).query(Boolean.class).single();
    }

    /**
     * Settles up to {@code limit} jobs of {@code queue} whose lease expired (a crashed worker), like a retryable failure with the code {@code lease_expired}.
     * {@code now()} is stable within the statement, so the lease index answers the condition.
     */
    List<Moved> expireLeases(String queue, Duration backoff, Duration backoffMax, int limit) {
        return jdbc.sql("WITH n AS (" + NEXT + " WHERE job_id IN (SELECT job_id FROM app_learning.learning_job WHERE queue=:queue AND state='RUNNING' "
                        + "AND lease_until < now() ORDER BY lease_until, job_id LIMIT :limit FOR UPDATE SKIP LOCKED)) " + SETTLE)
                .param("queue", queue).param("code", "lease_expired").param("permanent", false).param("baseMs", backoff.toMillis())
                .param("capMs", backoffMax.toMillis()).param("limit", limit).query(JobRepository::moved).list();
    }

    /**
     * Deletes up to {@code batch} finished jobs older than {@code retention}, oldest first, skipping rows another sweeper holds; returns how many.
     * The cutoff is {@code now()} (stable in the statement), so the finished index answers it and an idle pass reads nothing.
     */
    int purge(Duration retention, int batch) {
        return jdbc.sql("DELETE FROM app_learning.learning_job WHERE job_id IN (SELECT job_id FROM app_learning.learning_job "
                        + "WHERE state IN ('SUCCEEDED','FAILED','CANCELLED') AND finished_at < now() - (:retention * interval '1 millisecond') "
                        + "ORDER BY finished_at, job_id LIMIT :batch FOR UPDATE SKIP LOCKED)").param("retention", retention.toMillis()).param("batch", batch).update();
    }

    // ------------------------------------------------------------------ metrics

    /** Due jobs of the queue, counted up to {@value #DEPTH_CAP} by the head of the due index. */
    long depth(String queue) {
        return jdbc.sql("SELECT count(*) FROM (SELECT 1 FROM app_learning.learning_job WHERE queue=:queue AND state='QUEUED' "
                + "AND run_after <= now() LIMIT " + DEPTH_CAP + ") due").param("queue", queue).query(Long.class).single();
    }

    /** How long the oldest due job of the queue has been due; 0 when none. */
    double oldestAgeSeconds(String queue) {
        return jdbc.sql("SELECT COALESCE(EXTRACT(EPOCH FROM (clock_timestamp() - min(run_after))), 0)::float8 FROM (SELECT run_after "
                + "FROM app_learning.learning_job WHERE queue=:queue AND state='QUEUED' AND run_after <= now() ORDER BY run_after LIMIT 1) head")
                .param("queue", queue).query(Double.class).single();
    }

    private static Moved moved(ResultSet row, int ignored) throws SQLException {
        UUID id = row.getObject("job_id", UUID.class);
        String queue = row.getString("queue");
        return new Moved(id, queue, JobState.valueOf(row.getString("state")), new Job(id, queue, row.getObject("account_id", UUID.class),
                JobJson.read(row.getString("payload")), JobJson.read(row.getString("progress")), row.getLong("progress_count"), row.getInt("attempts")));
    }

    private static JobStatus status(ResultSet row) throws SQLException {
        return new JobStatus(row.getObject("job_id", UUID.class), row.getString("queue"), row.getObject("account_id", UUID.class),
                JobState.valueOf(row.getString("state")), row.getInt("attempts"), row.getInt("max_attempts"), row.getLong("progress_count"),
                JobJson.read(row.getString("progress")), row.getBoolean("cancel_requested"), row.getString("last_error"),
                instant(row, "created_at"), instant(row, "updated_at"), instant(row, "finished_at"));
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
