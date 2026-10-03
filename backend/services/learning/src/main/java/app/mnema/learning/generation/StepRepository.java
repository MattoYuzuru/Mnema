package app.mnema.learning.generation;

import app.mnema.learning.generation.Rows.Step;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The durable step queue. {@link #pickDue} claims with {@code FOR UPDATE SKIP LOCKED}; a claim sets a new lease token and
 * every later write names it ({@code lease_token = :token}), so a worker that lost its lease writes nothing. The
 * statements here touch only {@code generation_step}: the session lock is never taken inside a claim, so a claim cannot
 * wait on a cancellation and a cancellation cannot wait on a claim for longer than one statement.
 */
@Repository
class StepRepository {
    private static final String COLUMNS = "step_id,session_id,artifact_id,owner_id,kind,capability,state,attempts,lease_token,"
            + "lease_until,next_attempt_at,deadline_at,started_at,first_claimed_at,cancel_requested,input::text AS input,error_code,created_at";
    private static final RowMapper<Step> STEP = (row, ignored) -> new Step(row.getObject("step_id", UUID.class),
            row.getObject("session_id", UUID.class), row.getObject("artifact_id", UUID.class),
            row.getObject("owner_id", UUID.class), row.getString("kind"), row.getString("capability"),
            row.getString("state"), row.getInt("attempts"), row.getObject("lease_token", UUID.class),
            optional(row, "lease_until"), GenerationRepository.instant(row, "next_attempt_at"), optional(row, "deadline_at"),
            optional(row, "started_at"), optional(row, "first_claimed_at"), row.getBoolean("cancel_requested"), Json.read(row.getString("input")),
            row.getString("error_code"), GenerationRepository.instant(row, "created_at"));

    private final JdbcClient jdbc;

    StepRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static Instant optional(java.sql.ResultSet row, String column) throws java.sql.SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    void insert(UUID stepId, UUID sessionId, UUID artifactId, UUID owner, String kind, String capability, JsonNode input,
                String idempotencyKey) {
        jdbc.sql("INSERT INTO app_learning.generation_step(step_id,session_id,artifact_id,owner_id,kind,capability,state,priority,input,"
                        + "idempotency_key,next_attempt_at,created_at,updated_at) VALUES (:id,:session,:artifact,:owner,:kind,"
                        + ":capability,'READY',CASE WHEN :kind='EDIT' THEN 10 ELSE 0 END,CAST(:input AS jsonb),:key,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)")
                .param("id", stepId).param("session", sessionId).param("artifact", artifactId).param("owner", owner)
                .param("kind", kind).param("capability", capability).param("input", Json.write(input))
                .param("key", idempotencyKey).update();
    }

    Optional<Step> step(UUID stepId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM app_learning.generation_step WHERE step_id=:id")
                .param("id", stepId).query(STEP).optional();
    }

    /**
     * The next due step of one of {@code kinds}, locked {@code FOR UPDATE SKIP LOCKED}: READY, due, in a RUNNING session,
     * and of an owner below the soft cap of concurrently running steps.
     */
    Optional<Step> pickDue(Collection<String> kinds, int accountCap) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM app_learning.generation_step s WHERE s.state='READY' "
                        + "AND s.next_attempt_at<=CURRENT_TIMESTAMP AND s.kind IN (:kinds) "
                        + "AND (SELECT count(*) FROM app_learning.generation_step r WHERE r.owner_id=s.owner_id "
                        + "AND r.state='RUNNING')<:cap AND EXISTS (SELECT 1 FROM app_learning.generation_session g "
                        + "WHERE g.session_id=s.session_id AND g.state IN ('RUNNING','REVIEW')) "
                        + "ORDER BY s.priority DESC,s.next_attempt_at,s.created_at,s.step_id LIMIT 1 FOR UPDATE OF s SKIP LOCKED")
                .param("kinds", kinds).param("cap", accountCap).query(STEP).optional();
    }

    /** READY to RUNNING: a new fencing token, a lease, {@code attempts + 1} and the run's deadline. */
    Step claim(UUID stepId, UUID token, long leaseSeconds, long deadlineSeconds, long lifetimeSeconds) {
        return jdbc.sql("UPDATE app_learning.generation_step SET state='RUNNING',lease_token=:token,"
                        + "lease_until=CURRENT_TIMESTAMP + (:lease * interval '1 second'),attempts=attempts+1,"
                        + "started_at=CURRENT_TIMESTAMP,first_claimed_at=COALESCE(first_claimed_at,CURRENT_TIMESTAMP),"
                        + "deadline_at=LEAST(CURRENT_TIMESTAMP + (:deadline * interval '1 second'),"
                        + "COALESCE(first_claimed_at,CURRENT_TIMESTAMP) + (:lifetime * interval '1 second')),"
                        + "error_code=NULL,updated_at=CURRENT_TIMESTAMP WHERE step_id=:id AND state='READY' RETURNING " + COLUMNS)
                .param("token", token).param("lease", leaseSeconds).param("deadline", deadlineSeconds).param("lifetime", lifetimeSeconds).param("id", stepId)
                .query(STEP).single();
    }

    /** A step the daily burst parks: it stays READY and becomes due at {@code until}. */
    void defer(UUID stepId, Instant until) {
        jdbc.sql("UPDATE app_learning.generation_step SET next_attempt_at=:until,updated_at=CURRENT_TIMESTAMP "
                        + "WHERE step_id=:id AND state='READY'")
                .param("until", OffsetDateTime.ofInstant(until, ZoneOffset.UTC)).param("id", stepId).update();
    }

    /**
     * Extends the lease if the token still holds.
     *
     * @return empty when the lease is gone (expired and recovered, finished or cancelled elsewhere), else whether a
     *         cancellation was requested
     */
    Optional<Boolean> heartbeat(UUID stepId, UUID token, long leaseSeconds) {
        return jdbc.sql("UPDATE app_learning.generation_step SET lease_until=CURRENT_TIMESTAMP + (:lease * interval '1 second'),"
                        + "updated_at=CURRENT_TIMESTAMP WHERE step_id=:id AND lease_token=:token AND state='RUNNING' "
                        + "RETURNING cancel_requested")
                .param("lease", leaseSeconds).param("id", stepId).param("token", token).query(Boolean.class).optional();
    }

    /** The step if {@code token} still holds its lease; locks the row for the result write that follows. */
    Optional<Step> lockHeld(UUID stepId, UUID token) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM app_learning.generation_step WHERE step_id=:id AND lease_token=:token "
                        + "AND state='RUNNING' FOR UPDATE").param("id", stepId).param("token", token).query(STEP).optional();
    }

    /** RUNNING steps whose lease ran out, oldest first; a plain read, each is recovered under the session lock. */
    List<UUID> expiredRunning(int limit) {
        return jdbc.sql("SELECT step_id FROM app_learning.generation_step WHERE state='RUNNING' "
                        + "AND lease_until<CURRENT_TIMESTAMP ORDER BY lease_until LIMIT :limit")
                .param("limit", limit).query(UUID.class).list();
    }

    Optional<Step> lockExpired(UUID stepId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM app_learning.generation_step WHERE step_id=:id AND state='RUNNING' "
                        + "AND lease_until<CURRENT_TIMESTAMP FOR UPDATE").param("id", stepId).query(STEP).optional();
    }

    /** Terminal state of a held step; the lease is released with it. */
    void finish(UUID stepId, String state, String errorCode, String outputRef) {
        jdbc.sql("UPDATE app_learning.generation_step SET state=:state,error_code=:error,output_ref=:output,lease_token=NULL,"
                        + "lease_until=NULL,updated_at=CURRENT_TIMESTAMP WHERE step_id=:id")
                .param("state", state).param("error", errorCode).param("output", outputRef).param("id", stepId).update();
    }

    /** Back to READY, due after {@code delaySeconds}: a retryable failure or a recovered lease. */
    void requeue(UUID stepId, long delaySeconds, String errorCode) {
        jdbc.sql("UPDATE app_learning.generation_step SET state='READY',error_code=:error,lease_token=NULL,lease_until=NULL,"
                        + "next_attempt_at=CURRENT_TIMESTAMP + (:delay * interval '1 second'),updated_at=CURRENT_TIMESTAMP "
                        + "WHERE step_id=:id").param("error", errorCode).param("delay", delaySeconds).param("id", stepId).update();
    }

    /** Waiting steps of a cancelled session become CANCELLED; returns how many. */
    int cancelWaiting(UUID sessionId) {
        return jdbc.sql("UPDATE app_learning.generation_step SET state='CANCELLED',updated_at=CURRENT_TIMESTAMP "
                        + "WHERE session_id=:id AND state IN ('WAITING_DEPENDENCIES','READY','WAITING_EXTERNAL')")
                .param("id", sessionId).update();
    }

    /** Running steps of a cancelled session get {@code cancel_requested}; their heartbeat aborts the provider call. */
    int requestCancel(UUID sessionId) {
        return jdbc.sql("UPDATE app_learning.generation_step SET cancel_requested=TRUE,updated_at=CURRENT_TIMESTAMP "
                        + "WHERE session_id=:id AND state='RUNNING'").param("id", sessionId).update();
    }

    /** Steps that drive the "writing" indicators: READY, RUNNING or WAITING_EXTERNAL. */
    List<Step> activeSteps(UUID sessionId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM app_learning.generation_step WHERE session_id=:id "
                        + "AND state IN ('READY','RUNNING','WAITING_EXTERNAL') ORDER BY created_at,step_id")
                .param("id", sessionId).query(STEP).list();
    }

    /** {@code sessionId -> retry reservation ids} of the steps of these sessions (one statement; absent sessions have none). */
    java.util.Map<UUID, List<UUID>> reservationIds(Collection<UUID> sessions) {
        java.util.Map<UUID, List<UUID>> result = new java.util.HashMap<>();
        if (sessions.isEmpty()) return result;
        jdbc.sql("SELECT DISTINCT session_id,(input->>'reservationId')::uuid AS reservation FROM app_learning.generation_step "
                        + "WHERE session_id IN (:ids) AND input->>'reservationId' IS NOT NULL").param("ids", sessions)
                .query((row, ignored) -> result.computeIfAbsent(row.getObject("session_id", UUID.class), key -> new java.util.ArrayList<>())
                        .add(row.getObject("reservation", UUID.class))).list();
        return result;
    }

    /** How many TEXT_DRAFT steps the artifact has had: the next one is numbered after them. */
    int draftCount(UUID artifactId) {
        return jdbc.sql("SELECT count(*)::integer FROM app_learning.generation_step WHERE artifact_id=:id AND kind='TEXT_DRAFT'")
                .param("id", artifactId).query(Integer.class).single();
    }

    /**
     * The artifact is regenerated from scratch: its waiting media steps (of the revision being replaced) are cancelled and
     * running ones asked to stop.
     */
    void cancelMedia(UUID artifactId) {
        jdbc.sql("UPDATE app_learning.generation_step SET state='CANCELLED',updated_at=CURRENT_TIMESTAMP WHERE artifact_id=:id "
                        + "AND kind NOT IN ('TEXT_DRAFT','EDIT') AND state IN ('WAITING_DEPENDENCIES','READY','WAITING_EXTERNAL')")
                .param("id", artifactId).update();
        jdbc.sql("UPDATE app_learning.generation_step SET cancel_requested=TRUE,updated_at=CURRENT_TIMESTAMP WHERE artifact_id=:id "
                        + "AND kind NOT IN ('TEXT_DRAFT','EDIT') AND state='RUNNING'").param("id", artifactId).update();
    }

    /** The media steps of these slots (of one artifact) are not needed any more: waiting ones are cancelled, running ones asked to stop. */
    void cancelMediaOfSlots(UUID artifactId, Collection<String> slotKeys) {
        if (slotKeys.isEmpty()) return;
        jdbc.sql("UPDATE app_learning.generation_step SET state='CANCELLED',updated_at=CURRENT_TIMESTAMP WHERE artifact_id=:id "
                        + "AND kind NOT IN ('TEXT_DRAFT','EDIT') AND input->>'slotKey' IN (:keys) "
                        + "AND state IN ('WAITING_DEPENDENCIES','READY','WAITING_EXTERNAL')").param("id", artifactId).param("keys", slotKeys).update();
        jdbc.sql("UPDATE app_learning.generation_step SET cancel_requested=TRUE,updated_at=CURRENT_TIMESTAMP WHERE artifact_id=:id "
                        + "AND kind NOT IN ('TEXT_DRAFT','EDIT') AND input->>'slotKey' IN (:keys) AND state='RUNNING'")
                .param("id", artifactId).param("keys", slotKeys).update();
    }

    /** The holds of the edit steps that still work (READY or RUNNING): a session leaving RUNNING must not release them. */
    List<UUID> openEditReservations(UUID sessionId) {
        return jdbc.sql("SELECT (input->>'reservationId')::uuid FROM app_learning.generation_step WHERE session_id=:id AND kind='EDIT' "
                        + "AND state IN ('READY','RUNNING') AND input->>'reservationId' IS NOT NULL").param("id", sessionId)
                .query(UUID.class).list();
    }

    /** A READY step, locked, for a decision taken without a claim (its lifetime ran out while it waited). */
    Optional<Step> lockReady(UUID stepId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM app_learning.generation_step WHERE step_id=:id AND state='READY' FOR UPDATE")
                .param("id", stepId).query(STEP).optional();
    }

    /**
     * Gives a RUNNING step back on a clean shutdown: READY at once, lease cleared, and the attempt it was claimed with not
     * counted against the cap. Only the holder of {@code token} can do it.
     */
    int release(UUID stepId, UUID token) {
        return jdbc.sql("UPDATE app_learning.generation_step SET state='READY',lease_token=NULL,lease_until=NULL,"
                        + "attempts=GREATEST(attempts-1,0),next_attempt_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP "
                        + "WHERE step_id=:id AND lease_token=:token AND state='RUNNING'")
                .param("id", stepId).param("token", token).update();
    }

    /** Age in seconds of the oldest due READY step (zero when nothing waits): the queue-age gauge. */
    double oldestDueAgeSeconds() {
        return jdbc.sql("SELECT COALESCE(EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP - min(next_attempt_at))),0)::double precision "
                + "FROM app_learning.generation_step WHERE state='READY' AND next_attempt_at<=CURRENT_TIMESTAMP")
                .query(Double.class).single();
    }
}
