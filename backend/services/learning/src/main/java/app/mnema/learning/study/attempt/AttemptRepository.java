package app.mnema.learning.study.attempt;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class AttemptRepository {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JdbcClient jdbc;

    AttemptRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    record Presentation(UUID accountId, UUID sessionId, UUID presentationId, UUID deckId, String mode,
                        String sessionStatus, String nonce, UUID exerciseId, UUID exerciseRevisionId,
                        String exerciseType, UUID objectiveId,
                        UUID objectiveRevisionId, long learningEpoch, JsonNode content, JsonNode reveal,
                        JsonNode evaluator, JsonNode answerKey, boolean transcriptRevealed, List<UUID> hintedBlanks,
                        UUID configId, String reducerId, String reducerVersion,
                        String configHash, Instant expiresAt) { }
    record Receipt(UUID attemptId, UUID accountId, UUID sessionId, UUID presentationId, UUID deckId,
                   byte[] payloadHash, String mode, String status, JsonNode outcome, Instant receiptExpiresAt) {
        Receipt { payloadHash = payloadHash.clone(); }
        @Override public byte[] payloadHash() { return payloadHash.clone(); }
    }
    record State(UUID accountId, UUID deckId, UUID objectiveId, long learningEpoch, int level,
                 int correctStreak, int lapseCount, long transitionSequence, long rowVersion) { }

    boolean ownsDeck(UUID actor, UUID deck) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM app_learning.deck WHERE owner_id=:actor AND deck_id=:deck AND deleted_at IS NULL)")
                .param("actor", actor).param("deck", deck).query(Boolean.class).single();
    }

    Optional<Boolean> pairInteraction(UUID actor, UUID session, PairCheckCommand command) {
        return jdbc.sql("""
                SELECT correct FROM app_learning.study_pair_interaction
                 WHERE account_id=:actor AND session_id=:session AND presentation_id=:presentation
                   AND left_id=:left AND right_id=:right
                """).param("actor", actor).param("session", session).param("presentation", command.presentationId())
                .param("left", command.leftId()).param("right", command.rightId()).query(Boolean.class).optional();
    }

    void insertPairInteraction(UUID actor, UUID session, PairCheckCommand command, boolean correct, Instant expiresAt) {
        jdbc.sql("""
                INSERT INTO app_learning.study_pair_interaction(account_id,session_id,presentation_id,left_id,right_id,correct,expires_at)
                VALUES (:actor,:session,:presentation,:left,:right,:correct,:expires) ON CONFLICT DO NOTHING
                """).param("actor", actor).param("session", session).param("presentation", command.presentationId())
                .param("left", command.leftId()).param("right", command.rightId()).param("correct", correct)
                .param("expires", Timestamp.from(expiresAt)).update();
    }

    boolean hasPairMistakes(UUID actor, UUID session, UUID presentation) {
        return jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM app_learning.study_pair_interaction
                  WHERE account_id=:actor AND session_id=:session AND presentation_id=:presentation AND NOT correct)
                """).param("actor", actor).param("session", session).param("presentation", presentation)
                .query(Boolean.class).single();
    }

    void lockAttempt(UUID attempt) {
        long key = attempt.getMostSignificantBits() ^ attempt.getLeastSignificantBits();
        jdbc.sql("SELECT pg_advisory_xact_lock(:key) IS NULL")
                .param("key", key).query(Boolean.class).single();
    }

    void lockSession(UUID actor, UUID deck, UUID session) {
        jdbc.sql("""
                SELECT session_id FROM app_learning.study_session
                 WHERE account_id=:actor AND deck_id=:deck AND session_id=:session FOR UPDATE
                """).param("actor", actor).param("deck", deck).param("session", session)
                .query(UUID.class).optional().orElseThrow(app.mnema.learning.platform.api.ResourceNotFoundException::new);
    }

    Optional<Receipt> receipt(UUID attempt) {
        return jdbc.sql("SELECT * FROM app_learning.study_attempt_tombstone WHERE attempt_id=:attempt")
                .param("attempt", attempt).query((row, ignored) -> new Receipt(
                        row.getObject("attempt_id", UUID.class), row.getObject("account_id", UUID.class),
                        row.getObject("session_id", UUID.class), row.getObject("presentation_id", UUID.class),
                        row.getObject("deck_id", UUID.class), row.getBytes("payload_hash"), row.getString("mode"),
                        row.getString("status"), row.getString("outcome") == null ? null : json(row.getString("outcome")),
                        row.getTimestamp("receipt_expires_at") == null ? null
                                : row.getTimestamp("receipt_expires_at").toInstant())).optional();
    }

    Optional<Receipt> terminal(UUID actor, UUID session, UUID presentation) {
        return jdbc.sql("""
                SELECT * FROM app_learning.study_attempt_tombstone
                 WHERE account_id=:actor AND session_id=:session AND presentation_id=:presentation
                """).param("actor", actor).param("session", session).param("presentation", presentation)
                .query((row, ignored) -> new Receipt(row.getObject("attempt_id", UUID.class),
                        row.getObject("account_id", UUID.class), row.getObject("session_id", UUID.class),
                        row.getObject("presentation_id", UUID.class), row.getObject("deck_id", UUID.class),
                        row.getBytes("payload_hash"), row.getString("mode"), row.getString("status"),
                        row.getString("outcome") == null ? null : json(row.getString("outcome")),
                        row.getTimestamp("receipt_expires_at") == null ? null
                                : row.getTimestamp("receipt_expires_at").toInstant())).optional();
    }

    Optional<Presentation> presentationForUpdate(UUID actor, UUID deck, UUID session, UUID presentation) {
        return jdbc.sql("""
                SELECT p.*,s.mode,s.status AS session_status,s.reducer_config_id,c.reducer_id,c.reducer_version,
                       c.config_hash, EXISTS(SELECT 1 FROM app_learning.study_transcript_accommodation a
                           WHERE a.account_id=p.account_id AND a.session_id=p.session_id
                             AND a.presentation_id=p.presentation_id) AS transcript_revealed,
                       COALESCE((SELECT jsonb_agg(h.blank_id::text ORDER BY h.revealed_at,h.blank_id)
                                   FROM app_learning.study_hint_reveal h
                                  WHERE h.account_id=p.account_id AND h.session_id=p.session_id
                                    AND h.presentation_id=p.presentation_id),'[]'::jsonb) AS hinted_blanks
                  FROM app_learning.study_presentation p
                  JOIN app_learning.study_session s ON s.account_id=p.account_id AND s.session_id=p.session_id
                  JOIN app_learning.scheduler_config c ON c.config_id=s.reducer_config_id
                 WHERE p.account_id=:actor AND p.deck_id=:deck AND p.session_id=:session
                   AND p.presentation_id=:presentation
                 FOR UPDATE OF p
                """).param("actor", actor).param("deck", deck).param("session", session)
                .param("presentation", presentation).query((row, ignored) -> new Presentation(
                        row.getObject("account_id", UUID.class), row.getObject("session_id", UUID.class),
                        row.getObject("presentation_id", UUID.class), row.getObject("deck_id", UUID.class),
                        row.getString("mode"), row.getString("session_status"), row.getString("nonce"),
                        row.getObject("exercise_id", UUID.class), row.getObject("exercise_revision_id", UUID.class),
                        row.getString("exercise_type"), row.getObject("objective_id", UUID.class),
                        row.getObject("objective_revision_id", UUID.class), row.getLong("learning_epoch"),
                        json(row.getString("content")), json(row.getString("reveal")),
                        json(row.getString("evaluator")), json(row.getString("answer_key")),
                        row.getBoolean("transcript_revealed"), blankIds(json(row.getString("hinted_blanks"))),
                        row.getObject("reducer_config_id", UUID.class), row.getString("reducer_id"),
                        row.getString("reducer_version"), row.getString("config_hash"),
                        row.getTimestamp("expires_at").toInstant())).optional();
    }

    State stateForUpdate(UUID actor, UUID deck, UUID objective) {
        return jdbc.sql("""
                SELECT * FROM app_learning.study_state
                 WHERE account_id=:actor AND deck_id=:deck AND objective_id=:objective FOR UPDATE
                """).param("actor", actor).param("deck", deck).param("objective", objective)
                .query((row, ignored) -> new State(row.getObject("account_id", UUID.class),
                        row.getObject("deck_id", UUID.class), row.getObject("objective_id", UUID.class),
                        row.getLong("learning_epoch"), row.getInt("level"), row.getInt("correct_streak"),
                        row.getInt("lapse_count"), row.getLong("transition_sequence"), row.getLong("row_version")))
                .single();
    }

    void insertReceipt(AttemptCommand command, UUID actor, UUID deck, UUID session, byte[] hash, String mode,
                       String status, JsonNode outcome, Instant now, Instant receiptExpiry) {
        jdbc.sql("""
                INSERT INTO app_learning.study_attempt_tombstone(attempt_id,account_id,session_id,presentation_id,
                    deck_id,payload_hash,mode,status,outcome,receipt_expires_at,submitted_at)
                VALUES (:attempt,:actor,:session,:presentation,:deck,:hash,:mode,:status,CAST(:outcome AS jsonb),
                    :receiptExpiry,:now)
                """).param("attempt", command.attemptId()).param("actor", actor).param("session", session)
                .param("presentation", command.presentationId()).param("deck", deck).param("hash", hash)
                .param("mode", mode).param("status", status).param("outcome", outcome.toString())
                .param("receiptExpiry", receiptExpiry == null ? null : receiptExpiry.atOffset(ZoneOffset.UTC),
                        java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                .param("now", Timestamp.from(now)).update();
    }

    void insertEvidence(AttemptCommand command, Presentation presentation, AttemptEvaluation evaluation,
                        Instant acceptedAt) {
        jdbc.sql("""
                INSERT INTO app_learning.study_evidence(attempt_id,account_id,deck_id,objective_id,
                    objective_revision_id,learning_epoch,result,evidence_class,reason_codes,evaluator_id,
                    evaluator_version,hints_used,confidence,duration_ms,accepted_at)
                VALUES (:attempt,:actor,:deck,:objective,:revision,:epoch,:result,:strength,
                    CAST(:reasons AS jsonb),:evaluatorId,:evaluatorVersion,CAST(:hints AS jsonb),:confidence,
                    :duration,:accepted)
                """).param("attempt", command.attemptId()).param("actor", presentation.accountId())
                .param("deck", presentation.deckId()).param("objective", presentation.objectiveId())
                .param("revision", presentation.objectiveRevisionId()).param("epoch", presentation.learningEpoch())
                .param("result", evaluation.result().name()).param("strength", evaluation.evidenceClass().name())
                .param("reasons", jsonArray(evaluation.reasonCodes()).toString())
                .param("evaluatorId", presentation.evaluator().path("id").textValue())
                .param("evaluatorVersion", presentation.evaluator().path("version").textValue())
                .param("hints", jsonArray(presentation.hintedBlanks().stream()
                        .map(blank -> "FIRST_LETTER:" + blank)::iterator).toString()).param("confidence", command.confidence(),
                        java.sql.Types.VARCHAR).param("duration", command.durationMs())
                .param("accepted", Timestamp.from(acceptedAt)).update();
    }

    void insertTransition(AttemptCommand command, Presentation presentation, State state,
                          BaselineReducer.Transition transition) {
        jdbc.sql("""
                INSERT INTO app_learning.study_transition(account_id,deck_id,objective_id,learning_epoch,
                    transition_sequence,attempt_id,before_level,after_level,before_correct_streak,
                    after_correct_streak,before_lapse_count,after_lapse_count,accepted_at,next_due,reducer_id,
                    reducer_version,reducer_config_id,config_hash)
                VALUES (:actor,:deck,:objective,:epoch,:sequence,:attempt,:beforeLevel,:afterLevel,:beforeStreak,
                    :afterStreak,:beforeLapses,:afterLapses,:accepted,:due,:reducer,:reducerVersion,:config,:hash)
                """).param("actor", presentation.accountId()).param("deck", presentation.deckId())
                .param("objective", presentation.objectiveId()).param("epoch", state.learningEpoch())
                .param("sequence", state.transitionSequence() + 1).param("attempt", command.attemptId())
                .param("beforeLevel", transition.beforeLevel()).param("afterLevel", transition.afterLevel())
                .param("beforeStreak", transition.beforeCorrectStreak())
                .param("afterStreak", transition.afterCorrectStreak())
                .param("beforeLapses", transition.beforeLapseCount()).param("afterLapses", transition.afterLapseCount())
                .param("accepted", Timestamp.from(transition.acceptedAt()))
                .param("due", Timestamp.from(transition.nextDue())).param("reducer", presentation.reducerId())
                .param("reducerVersion", presentation.reducerVersion()).param("config", presentation.configId())
                .param("hash", presentation.configHash()).update();
    }

    void updateState(State state, BaselineReducer.Transition transition, UUID config) {
        int changed = jdbc.sql("""
                UPDATE app_learning.study_state SET level=:level,correct_streak=:streak,lapse_count=:lapses,
                    last_assessed_at=:accepted,next_due=:due,reducer_config_id=:config,
                    transition_sequence=transition_sequence+1,row_version=row_version+1,updated_at=:accepted
                 WHERE account_id=:actor AND deck_id=:deck AND objective_id=:objective AND row_version=:version
                """).param("level", transition.afterLevel()).param("streak", transition.afterCorrectStreak())
                .param("lapses", transition.afterLapseCount()).param("accepted", Timestamp.from(transition.acceptedAt()))
                .param("due", Timestamp.from(transition.nextDue())).param("config", config)
                .param("actor", state.accountId()).param("deck", state.deckId()).param("objective", state.objectiveId())
                .param("version", state.rowVersion()).update();
        if (changed != 1) throw new IllegalStateException("Study state changed while locked");
    }

    void insertRaw(UUID attempt, JsonNode response, Instant expires) {
        jdbc.sql("""
                INSERT INTO app_learning.study_raw_response(attempt_id,response,expires_at)
                VALUES (:attempt,CAST(:response AS jsonb),:expires)
                """).param("attempt", attempt).param("response", response.toString())
                .param("expires", Timestamp.from(expires)).update();
    }

    void completeSessionIfTerminal(UUID actor, UUID deck, UUID session, Instant now) {
        jdbc.sql("""
                UPDATE app_learning.study_session s
                   SET status='COMPLETE',completed_at=:now,batch_start=issued_count,batch_size=0,
                       row_version=row_version+1
                 WHERE s.account_id=:actor AND s.deck_id=:deck AND s.session_id=:session AND s.status='ACTIVE'
                   AND (s.issued_count>=s.budget OR s.wrapped)
                   AND NOT EXISTS (
                       SELECT 1 FROM app_learning.study_presentation p
                        WHERE p.account_id=s.account_id AND p.session_id=s.session_id
                          AND p.presentation_ordinal>=s.batch_start
                          AND p.presentation_ordinal<s.batch_start+s.batch_size
                          AND NOT EXISTS (
                              SELECT 1 FROM app_learning.study_attempt_tombstone t
                               WHERE t.account_id=p.account_id AND t.session_id=p.session_id
                                 AND t.presentation_id=p.presentation_id
                          )
                   )
                """).param("actor", actor).param("deck", deck).param("session", session)
                .param("now", Timestamp.from(now)).update();
    }

    Instant now() { return jdbc.sql("SELECT statement_timestamp()")
            .query(Timestamp.class).single().toInstant(); }

    static boolean same(byte[] left, byte[] right) { return java.security.MessageDigest.isEqual(left, right); }

    private static JsonNode json(String value) {
        try { return JSON.readTree(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Invalid persisted JSON", exception); }
    }

    private static List<UUID> blankIds(JsonNode values) {
        List<UUID> result = new java.util.ArrayList<>();
        values.forEach(value -> result.add(UUID.fromString(value.textValue())));
        return List.copyOf(result);
    }

    private static JsonNode jsonArray(Iterable<String> values) {
        var array = JSON.createArrayNode();
        values.forEach(array::add);
        return array;
    }
}
