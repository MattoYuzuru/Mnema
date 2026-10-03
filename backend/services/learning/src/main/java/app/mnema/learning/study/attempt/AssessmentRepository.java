package app.mnema.learning.study.attempt;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * SQL of the AI assessment of free explanations ({@code study_assessment}, {@code study_assessment_dispute} and the
 * compensating transitions). It opens no transaction: every caller of a write has one. Every state change is a
 * compare-and-set on the previous state, so two racing writers (the grader, «Оценить себя», the deadline sweeper) never both win.
 *
 * <p>TODO(account-deletion task; owner: the epic that adds Learning's account purge): Learning has no account purge path yet, so no
 * {@code study_assessment} or {@code study_assessment_dispute} row is removed when an account is deleted. Both are keyed by
 * {@code account_id}; the purge must delete the dispute rows (they may hold a shared example) and then the assessment rows (they may hold
 * an answer until the presentation expires) before the attempt tombstones they reference.
 */
@Repository
class AssessmentRepository {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JdbcClient jdbc;

    AssessmentRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** One answer in assessment; {@code response} is the stored submit response, null once the attempt is terminal or expired. */
    record Row(UUID attemptId, UUID accountId, UUID sessionId, UUID presentationId, UUID deckId, String state, String reason,
               String strictness, byte[] payloadHash, String answerSource, JsonNode response, String confidence,
               int durationMs, Instant createdAt, Instant deadlineAt, Instant expiresAt) {
        Row { payloadHash = payloadHash.clone(); }

        @Override public byte[] payloadHash() { return payloadHash.clone(); }

        /** No answer text and no hash. */
        @Override
        public String toString() {
            return "Row[attemptId=" + attemptId + ", state=" + state + ", reason=" + reason + ", strictness=" + strictness + "]";
        }
    }

    /** The AI transition of an attempt, with everything its compensation copies. */
    record Transition(UUID accountId, UUID deckId, UUID objectiveId, long learningEpoch, long sequence, int beforeLevel,
                      int afterLevel, int beforeStreak, int beforeLapses, String reducerId, String reducerVersion,
                      UUID configId, String configHash) { }

    record Dispute(UUID attemptId, UUID commandId) { }

    private static final String COLUMNS = "attempt_id,account_id,session_id,presentation_id,deck_id,state,reason,strictness,"
            + "payload_hash,answer_source,response,confidence,duration_ms,created_at,deadline_at,expires_at";

    private static Row row(java.sql.ResultSet result, int number) throws java.sql.SQLException {
        String response = result.getString("response");
        return new Row(result.getObject("attempt_id", UUID.class), result.getObject("account_id", UUID.class),
                result.getObject("session_id", UUID.class), result.getObject("presentation_id", UUID.class),
                result.getObject("deck_id", UUID.class), result.getString("state"), result.getString("reason"),
                result.getString("strictness"), result.getBytes("payload_hash"), result.getString("answer_source"),
                response == null ? null : json(response), result.getString("confidence"), result.getInt("duration_ms"),
                result.getTimestamp("created_at").toInstant(), result.getTimestamp("deadline_at").toInstant(),
                result.getTimestamp("expires_at").toInstant());
    }

    // ------------------------------------------------------------------------------------------------ assessment row

    Optional<Row> find(UUID attempt) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM app_learning.study_assessment WHERE attempt_id=:attempt")
                .param("attempt", attempt).query(AssessmentRepository::row).optional();
    }

    Optional<Row> forUpdate(UUID attempt) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM app_learning.study_assessment WHERE attempt_id=:attempt FOR UPDATE")
                .param("attempt", attempt).query(AssessmentRepository::row).optional();
    }

    /** True when the presentation is held by an assessment (of any attempt id) that has not ended. */
    boolean holds(UUID actor, UUID session, UUID presentation) {
        return jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM app_learning.study_assessment
                 WHERE account_id=:actor AND session_id=:session AND presentation_id=:presentation AND state<>'DONE')
                """).param("actor", actor).param("session", session).param("presentation", presentation)
                .query(Boolean.class).single();
    }

    void insert(Row row, Instant now) {
        jdbc.sql("""
                INSERT INTO app_learning.study_assessment(attempt_id,account_id,session_id,presentation_id,deck_id,state,
                    reason,strictness,payload_hash,answer_source,response,confidence,duration_ms,created_at,deadline_at,
                    expires_at,resolved_at)
                VALUES (:attempt,:actor,:session,:presentation,:deck,:state,:reason,:strictness,:hash,:source,
                    CAST(:response AS jsonb),:confidence,:duration,:created,:deadline,:expires,:resolved)
                """).param("attempt", row.attemptId()).param("actor", row.accountId()).param("session", row.sessionId())
                .param("presentation", row.presentationId()).param("deck", row.deckId()).param("state", row.state())
                .param("reason", row.reason(), java.sql.Types.VARCHAR).param("strictness", row.strictness())
                .param("hash", row.payloadHash()).param("source", row.answerSource())
                .param("response", row.response().toString()).param("confidence", row.confidence(), java.sql.Types.VARCHAR)
                .param("duration", row.durationMs()).param("created", Timestamp.from(row.createdAt()))
                .param("deadline", Timestamp.from(row.deadlineAt())).param("expires", Timestamp.from(row.expiresAt()))
                .param("resolved", row.state().equals("ASSESSING") ? null : Timestamp.from(now), java.sql.Types.TIMESTAMP)
                .update();
    }

    /** Compare-and-set of the state; false when the row is no longer in {@code from}. */
    boolean transition(UUID attempt, String from, String to, String reason, Instant now) {
        return jdbc.sql("""
                UPDATE app_learning.study_assessment SET state=:to,reason=:reason,resolved_at=:now
                 WHERE attempt_id=:attempt AND state=:from
                """).param("to", to).param("reason", reason, java.sql.Types.VARCHAR).param("now", Timestamp.from(now))
                .param("attempt", attempt).param("from", from).update() == 1;
    }

    /** The attempt became terminal: the state is DONE and the answer text is no longer kept here. */
    boolean finish(UUID attempt, Instant now) {
        return jdbc.sql("""
                UPDATE app_learning.study_assessment SET state='DONE',response=NULL,resolved_at=COALESCE(resolved_at,:now)
                 WHERE attempt_id=:attempt AND state IN ('ASSESSING','SELF_CHECK','UNAVAILABLE')
                """).param("now", Timestamp.from(now)).param("attempt", attempt).update() == 1;
    }

    /**
     * The deadline sweeper: answers still ASSESSING past their deadline become UNAVAILABLE. Rows another transaction is
     * resolving are skipped, not waited for (a transaction that holds one will end it by itself).
     *
     * @return how many answers were made unavailable
     */
    int expireOverdue(int limit) {
        return jdbc.sql("""
                UPDATE app_learning.study_assessment SET state='UNAVAILABLE',reason='DEADLINE',resolved_at=statement_timestamp()
                 WHERE attempt_id IN (
                       SELECT candidate.attempt_id FROM app_learning.study_assessment candidate
                        WHERE candidate.state='ASSESSING' AND candidate.deadline_at<=statement_timestamp()
                        ORDER BY candidate.deadline_at LIMIT :limit FOR UPDATE SKIP LOCKED)
                """).param("limit", limit).update();
    }

    /** Retention: answers past the presentation's expiry are not kept (the state row stays, it holds no text). */
    int clearAnswers(Instant asOf, int limit) {
        return jdbc.sql("""
                UPDATE app_learning.study_assessment SET response=NULL
                 WHERE attempt_id IN (
                       SELECT candidate.attempt_id FROM app_learning.study_assessment candidate
                        WHERE candidate.response IS NOT NULL AND candidate.expires_at<=:asOf
                        ORDER BY candidate.expires_at LIMIT :limit FOR UPDATE SKIP LOCKED)
                """).param("asOf", Timestamp.from(asOf)).param("limit", limit).update();
    }

    // ---------------------------------------------------------------------------------------------- strictness input

    Optional<AttemptRepository.State> state(UUID actor, UUID deck, UUID objective) {
        return jdbc.sql("""
                SELECT * FROM app_learning.study_state
                 WHERE account_id=:actor AND deck_id=:deck AND objective_id=:objective
                """).param("actor", actor).param("deck", deck).param("objective", objective)
                .query((row, ignored) -> new AttemptRepository.State(row.getObject("account_id", UUID.class),
                        row.getObject("deck_id", UUID.class), row.getObject("objective_id", UUID.class),
                        row.getLong("learning_epoch"), row.getInt("level"), row.getInt("correct_streak"),
                        row.getInt("lapse_count"), row.getLong("transition_sequence"), row.getLong("row_version")))
                .optional();
    }

    /** Whether the learner already has an assessed attempt at this exercise in this epoch (in any mode). */
    boolean attemptedExercise(UUID actor, UUID exercise, long epoch) {
        return jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM app_learning.study_attempt_tombstone t
                    JOIN app_learning.study_presentation p ON p.account_id=t.account_id AND p.session_id=t.session_id
                     AND p.presentation_id=t.presentation_id
                   WHERE t.account_id=:actor AND p.exercise_id=:exercise AND p.learning_epoch=:epoch AND t.status='ASSESSED')
                """).param("actor", actor).param("exercise", exercise).param("epoch", epoch)
                .query(Boolean.class).single();
    }

    /** The immutable evaluator policy of the exercise revision the presentation was issued from (the rubric lives here). */
    Optional<JsonNode> evaluatorPolicy(UUID deck, UUID exercise, UUID revision) {
        return jdbc.sql("""
                SELECT evaluator_policy FROM app_learning.exercise_revision
                 WHERE deck_id=:deck AND exercise_id=:exercise AND revision_id=:revision
                """).param("deck", deck).param("exercise", exercise).param("revision", revision)
                .query((row, ignored) -> json(row.getString("evaluator_policy"))).optional();
    }

    // ------------------------------------------------------------------------------------------------------ dispute

    Optional<Transition> transitionOf(UUID attempt) {
        return jdbc.sql("""
                SELECT * FROM app_learning.study_transition WHERE attempt_id=:attempt
                """).param("attempt", attempt).query((row, ignored) -> new Transition(
                        row.getObject("account_id", UUID.class), row.getObject("deck_id", UUID.class),
                        row.getObject("objective_id", UUID.class), row.getLong("learning_epoch"),
                        row.getLong("transition_sequence"), row.getInt("before_level"), row.getInt("after_level"),
                        row.getInt("before_correct_streak"), row.getInt("before_lapse_count"), row.getString("reducer_id"),
                        row.getString("reducer_version"), row.getObject("reducer_config_id", UUID.class),
                        row.getString("config_hash"))).optional();
    }

    /** The {@code next_due} of the transition before {@code sequence}: when the before-state was due. */
    Optional<Instant> dueBefore(UUID actor, UUID deck, UUID objective, long epoch, long sequence) {
        return jdbc.sql("""
                SELECT next_due FROM app_learning.study_transition
                 WHERE account_id=:actor AND deck_id=:deck AND objective_id=:objective AND learning_epoch=:epoch
                   AND transition_sequence=:sequence
                """).param("actor", actor).param("deck", deck).param("objective", objective).param("epoch", epoch)
                .param("sequence", sequence - 1).query((row, ignored) -> row.getTimestamp("next_due").toInstant()).optional();
    }

    /**
     * Appends the compensating transition: it restores the before-state of {@code original} (the transition of {@code attempt}),
     * belongs to no attempt itself and says what it compensates. {@code current} is the state it moves away from (the after-state of the AI transition).
     */
    void insertCompensation(UUID attempt, Transition original, AttemptRepository.State current,
                            BaselineReducer.Transition restore, String reason) {
        jdbc.sql("""
                INSERT INTO app_learning.study_transition(account_id,deck_id,objective_id,learning_epoch,transition_sequence,
                    attempt_id,before_level,after_level,before_correct_streak,after_correct_streak,before_lapse_count,
                    after_lapse_count,accepted_at,next_due,reducer_id,reducer_version,reducer_config_id,config_hash,kind,
                    compensates_attempt_id,reason_code)
                VALUES (:actor,:deck,:objective,:epoch,:sequence,NULL,:beforeLevel,:afterLevel,:beforeStreak,:afterStreak,
                    :beforeLapses,:afterLapses,:accepted,:due,:reducer,:reducerVersion,:config,:hash,'COMPENSATION',
                    :compensates,:reason)
                """).param("actor", original.accountId()).param("deck", original.deckId())
                .param("objective", original.objectiveId()).param("epoch", original.learningEpoch())
                .param("sequence", current.transitionSequence() + 1).param("beforeLevel", restore.beforeLevel())
                .param("afterLevel", restore.afterLevel()).param("beforeStreak", restore.beforeCorrectStreak())
                .param("afterStreak", restore.afterCorrectStreak()).param("beforeLapses", restore.beforeLapseCount())
                .param("afterLapses", restore.afterLapseCount()).param("accepted", Timestamp.from(restore.acceptedAt()))
                .param("due", Timestamp.from(restore.nextDue())).param("reducer", original.reducerId())
                .param("reducerVersion", original.reducerVersion()).param("config", original.configId())
                .param("hash", original.configHash()).param("compensates", attempt).param("reason", reason)
                .update();
    }

    Optional<Dispute> dispute(UUID attempt) {
        return jdbc.sql("SELECT attempt_id,command_id FROM app_learning.study_assessment_dispute WHERE attempt_id=:attempt")
                .param("attempt", attempt).query((row, ignored) -> new Dispute(row.getObject("attempt_id", UUID.class),
                        row.getObject("command_id", UUID.class))).optional();
    }

    boolean disputeCommandUsed(UUID command) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM app_learning.study_assessment_dispute WHERE command_id=:command)")
                .param("command", command).query(Boolean.class).single();
    }

    void insertDispute(UUID attempt, UUID command, UUID actor, UUID deck, UUID exercise, UUID exerciseRevision,
                       String strictness, String judgement, JsonNode example, Instant now) {
        jdbc.sql("""
                INSERT INTO app_learning.study_assessment_dispute(attempt_id,command_id,account_id,deck_id,exercise_id,
                    exercise_revision_id,strictness,judgement,share_example,example,created_at)
                VALUES (:attempt,:command,:actor,:deck,:exercise,:revision,:strictness,:judgement,:share,
                    CAST(:example AS jsonb),:now)
                """).param("attempt", attempt).param("command", command).param("actor", actor).param("deck", deck)
                .param("exercise", exercise).param("revision", exerciseRevision).param("strictness", strictness)
                .param("judgement", judgement).param("share", example != null)
                .param("example", example == null ? null : example.toString(), java.sql.Types.VARCHAR)
                .param("now", Timestamp.from(now)).update();
    }

    /** The scheduled raw response text (kept 30 days), for an explicitly shared example only. */
    Optional<JsonNode> rawResponse(UUID attempt) {
        return jdbc.sql("SELECT response FROM app_learning.study_raw_response WHERE attempt_id=:attempt")
                .param("attempt", attempt).query((row, ignored) -> json(row.getString("response"))).optional();
    }

    /** The grade was taken back: the receipt now says NOT_ASSESSED with {@code disputed}. */
    void markDisputed(UUID attempt, JsonNode outcome) {
        jdbc.sql("""
                UPDATE app_learning.study_attempt_tombstone SET status='NOT_ASSESSED',outcome=CAST(:outcome AS jsonb)
                 WHERE attempt_id=:attempt
                """).param("outcome", outcome.toString()).param("attempt", attempt).update();
    }

    private static JsonNode json(String value) {
        try { return JSON.readTree(value); }
        catch (JacksonException exception) { throw new IllegalStateException("Invalid persisted JSON", exception); }
    }
}
