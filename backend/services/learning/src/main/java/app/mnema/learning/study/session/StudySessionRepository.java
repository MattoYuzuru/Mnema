package app.mnema.learning.study.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class StudySessionRepository {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JdbcClient jdbc;

    StudySessionRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    record DeckHead(UUID deckId, UUID ownerId, UUID revisionId, long sequence, UUID exercisesRootId,
                    int exerciseCount) { }
    record Generation(UUID deckId, UUID generationId, UUID ownerId, UUID deckRevisionId, long deckSequence,
                      UUID exercisesRootId, String status, UUID sourceCursor, int scannedCount, int candidateCount,
                      int expectedExerciseCount, long rowVersion) { }
    record SourceExercise(UUID exerciseId, UUID exerciseRevisionId, boolean enabled, UUID objectiveId,
                          UUID objectiveRevisionId, UUID memberKey) { }
    record Candidate(int ordinal, UUID exerciseId, UUID exerciseRevisionId, String type, UUID objectiveId,
                     UUID objectiveRevisionId, UUID memberKey, JsonNode prompt, JsonNode evaluator,
                     JsonNode answerContract) { }
    record Session(UUID accountId, UUID sessionId, UUID deckId, StudySessionCommand.Mode mode, String status,
                   String timezone, LocalDate localStudyDate, UUID deckRevisionId, long deckSequence,
                   UUID generationId, String policyVersion, UUID configId, String reducerId, String reducerVersion,
                   String configHash, long seed, int budget, int issuedCount, int batchStart, int batchSize,
                   int scanCursor, boolean wrapped, boolean includeNew, String practiceOrder, UUID sourceSessionId,
                   long rowVersion, Instant createdAt, Instant expiresAt, Instant completedAt) { }
    record Presentation(UUID presentationId, int ordinal, String nonce, int candidateOrdinal,
                        UUID exerciseId, UUID exerciseRevisionId, String type, UUID objectiveId,
                        UUID objectiveRevisionId, long learningEpoch, JsonNode prompt, JsonNode options,
                        JsonNode bindings, JsonNode evaluator, JsonNode answerContract, Instant issuedAt,
                        Instant expiresAt) { }
    record Material(UUID memberKey, UUID itemRevisionId, UUID scopeId, UUID contentRootId) { }

    private static final RowMapper<DeckHead> DECK = (row, ignored) -> new DeckHead(
            row.getObject("deck_id", UUID.class), row.getObject("owner_id", UUID.class),
            row.getObject("head_revision_id", UUID.class), row.getLong("row_version"),
            row.getObject("exercises_root_id", UUID.class), row.getInt("exercise_count"));
    private static final RowMapper<Generation> GENERATION = (row, ignored) -> new Generation(
            row.getObject("deck_id", UUID.class), row.getObject("generation_id", UUID.class),
            row.getObject("owner_id", UUID.class), row.getObject("deck_revision_id", UUID.class),
            row.getLong("deck_sequence"), row.getObject("exercises_root_id", UUID.class), row.getString("status"),
            row.getObject("source_cursor", UUID.class), row.getInt("scanned_count"), row.getInt("candidate_count"),
            row.getInt("expected_exercise_count"), row.getLong("row_version"));
    private static final RowMapper<Session> SESSION = (row, ignored) -> new Session(
            row.getObject("account_id", UUID.class), row.getObject("session_id", UUID.class),
            row.getObject("deck_id", UUID.class), StudySessionCommand.Mode.valueOf(row.getString("mode")),
            row.getString("status"), row.getString("timezone"), row.getObject("local_study_date", LocalDate.class),
            row.getObject("deck_revision_id", UUID.class), row.getLong("deck_sequence"),
            row.getObject("exercise_generation_id", UUID.class), row.getString("selection_policy_version"),
            row.getObject("reducer_config_id", UUID.class), row.getString("reducer_id"),
            row.getString("reducer_version"), row.getString("config_hash"), row.getLong("seed"),
            row.getInt("budget"), row.getInt("issued_count"), row.getInt("batch_start"), row.getInt("batch_size"),
            row.getInt("scan_cursor"), row.getBoolean("wrapped"), row.getBoolean("include_new"),
            row.getString("practice_order"), row.getObject("source_session_id", UUID.class), row.getLong("row_version"),
            row.getTimestamp("created_at").toInstant(), row.getTimestamp("expires_at").toInstant(),
            row.getTimestamp("completed_at") == null ? null : row.getTimestamp("completed_at").toInstant());
    private static final RowMapper<Presentation> PRESENTATION = (row, ignored) -> new Presentation(
            row.getObject("presentation_id", UUID.class), row.getInt("presentation_ordinal"), row.getString("nonce"),
            row.getInt("candidate_ordinal"), row.getObject("exercise_id", UUID.class),
            row.getObject("exercise_revision_id", UUID.class), row.getString("exercise_type"),
            row.getObject("objective_id", UUID.class), row.getObject("objective_revision_id", UUID.class),
            row.getLong("learning_epoch"), json(row.getString("prompt")), json(row.getString("options")),
            json(row.getString("bindings")), json(row.getString("evaluator")),
            json(row.getString("answer_contract")), row.getTimestamp("issued_at").toInstant(),
            row.getTimestamp("expires_at").toInstant());

    Optional<DeckHead> deck(UUID actor, UUID deck) {
        return jdbc.sql("""
                SELECT d.deck_id,d.owner_id,d.head_revision_id,d.row_version,r.exercises_root_id,r.exercise_count
                  FROM app_learning.deck d JOIN app_learning.deck_revision r
                    ON r.deck_id=d.deck_id AND r.revision_id=d.head_revision_id
                 WHERE d.owner_id=:actor AND d.deck_id=:deck
                """).param("actor", actor).param("deck", deck).query(DECK).optional();
    }

    Optional<Generation> generation(UUID deck, UUID exercisesRoot) {
        return jdbc.sql("""
                SELECT * FROM app_learning.study_candidate_generation
                 WHERE deck_id=:deck AND exercises_root_id=:root
                """).param("deck", deck).param("root", exercisesRoot).query(GENERATION).optional();
    }

    Optional<Generation> generationForUpdate(UUID deck, UUID generation) {
        return jdbc.sql("""
                SELECT * FROM app_learning.study_candidate_generation
                 WHERE deck_id=:deck AND generation_id=:generation FOR UPDATE
                """).param("deck", deck).param("generation", generation).query(GENERATION).optional();
    }

    void insertGeneration(DeckHead deck, UUID generation, Instant now) {
        jdbc.sql("""
                INSERT INTO app_learning.study_candidate_generation(deck_id,generation_id,owner_id,deck_revision_id,
                    deck_sequence,exercises_root_id,status,source_cursor,scanned_count,candidate_count,
                    expected_exercise_count,row_version,created_at,ready_at)
                VALUES (:deck,:generation,:owner,:revision,:sequence,:root,:status,NULL,0,0,:count,0,:now,:ready)
                ON CONFLICT (deck_id,exercises_root_id) DO NOTHING
                """).param("deck", deck.deckId()).param("generation", generation).param("owner", deck.ownerId())
                .param("revision", deck.revisionId()).param("sequence", deck.sequence())
                .param("root", deck.exercisesRootId()).param("status", deck.exerciseCount() == 0 ? "READY" : "PREPARING")
                .param("count", deck.exerciseCount()).param("now", Timestamp.from(now))
                .param("ready", deck.exerciseCount() == 0 ? now.atOffset(ZoneOffset.UTC) : null,
                        java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    List<SourceExercise> sourceBatch(Generation generation, int limit) {
        String cursor = generation.sourceCursor() == null ? "" : " AND x.exercise_id > :cursor";
        var query = jdbc.sql("""
                SELECT x.exercise_id,r.revision_id AS exercise_revision_id,r.enabled,
                       b.objective_id,b.objective_revision_id,b.member_key
                  FROM app_learning.exercise_definition x
                  JOIN LATERAL (
                       SELECT revision_id,enabled FROM app_learning.exercise_revision revision
                        WHERE revision.deck_id=x.deck_id AND revision.exercise_id=x.exercise_id
                          AND revision.deck_sequence<=:sequence
                        ORDER BY revision.deck_sequence DESC LIMIT 1
                  ) r ON TRUE
                  JOIN app_learning.exercise_content_binding b ON b.deck_id=x.deck_id
                    AND b.exercise_id=x.exercise_id AND b.exercise_revision_id=r.revision_id AND b.role='ASSESSED'
                 WHERE x.deck_id=:deck
                """ + cursor + " ORDER BY x.exercise_id LIMIT :limit")
                .param("sequence", generation.deckSequence()).param("deck", generation.deckId()).param("limit", limit);
        if (generation.sourceCursor() != null) query.param("cursor", generation.sourceCursor());
        return query.query((row, ignored) -> new SourceExercise(row.getObject("exercise_id", UUID.class),
                row.getObject("exercise_revision_id", UUID.class), row.getBoolean("enabled"),
                row.getObject("objective_id", UUID.class), row.getObject("objective_revision_id", UUID.class),
                row.getObject("member_key", UUID.class))).list();
    }

    void insertCandidate(UUID generation, int ordinal, Generation owner, SourceExercise source) {
        jdbc.sql("""
                INSERT INTO app_learning.study_candidate(generation_id,candidate_ordinal,deck_id,exercise_id,
                    exercise_revision_id,objective_id,objective_revision_id,member_key)
                VALUES (:generation,:ordinal,:deck,:exercise,:revision,:objective,:objectiveRevision,:member)
                """).param("generation", generation).param("ordinal", ordinal).param("deck", owner.deckId())
                .param("exercise", source.exerciseId()).param("revision", source.exerciseRevisionId())
                .param("objective", source.objectiveId()).param("objectiveRevision", source.objectiveRevisionId())
                .param("member", source.memberKey()).update();
    }

    void advanceGeneration(Generation generation, UUID cursor, int scanned, int candidates, boolean ready, Instant now) {
        jdbc.sql("""
                UPDATE app_learning.study_candidate_generation
                   SET source_cursor=:cursor,scanned_count=scanned_count+:scanned,
                       candidate_count=candidate_count+:candidates,status=:status,ready_at=:ready,
                       row_version=row_version+1
                 WHERE deck_id=:deck AND generation_id=:generation AND row_version=:version
                """).param("cursor", cursor, java.sql.Types.OTHER).param("scanned", scanned)
                .param("candidates", candidates).param("status", ready ? "READY" : "PREPARING")
                .param("ready", ready ? now.atOffset(ZoneOffset.UTC) : null, java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                .param("deck", generation.deckId()).param("generation", generation.generationId())
                .param("version", generation.rowVersion()).update();
    }

    Optional<Session> session(UUID actor, UUID deck, UUID session) { return session(actor, deck, session, false); }
    Optional<Session> sessionForUpdate(UUID actor, UUID deck, UUID session) { return session(actor, deck, session, true); }

    private Optional<Session> session(UUID actor, UUID deck, UUID session, boolean lock) {
        return jdbc.sql("""
                SELECT s.*,c.reducer_id,c.reducer_version,c.config_hash
                  FROM app_learning.study_session s JOIN app_learning.scheduler_config c
                    ON c.config_id=s.reducer_config_id
                 WHERE s.account_id=:actor AND s.deck_id=:deck AND s.session_id=:session
                """ + (lock ? " FOR UPDATE OF s" : ""))
                .param("actor", actor).param("deck", deck).param("session", session).query(SESSION).optional();
    }

    Optional<Session> replaySource(UUID actor, UUID deck, UUID session, LocalDate localDate) {
        return jdbc.sql("""
                SELECT s.*,c.reducer_id,c.reducer_version,c.config_hash
                  FROM app_learning.study_session s JOIN app_learning.scheduler_config c
                    ON c.config_id=s.reducer_config_id
                 WHERE s.account_id=:actor AND s.deck_id=:deck AND s.session_id=:session
                   AND s.status='COMPLETE' AND s.local_study_date=:studyDate
                """).param("actor", actor).param("deck", deck).param("session", session)
                .param("studyDate", localDate).query(SESSION).optional();
    }

    void insertSession(UUID actor, UUID session, UUID command, StudySessionCommand.Mode mode, String status,
                       String timezone, LocalDate localDate, UUID deck, UUID deckRevision, long deckSequence,
                       UUID generation, long seed, StudySessionCommand value, UUID sourceSession, Instant now,
                       Instant expires) {
        jdbc.sql("""
                INSERT INTO app_learning.study_session(account_id,session_id,deck_id,command_id,mode,status,timezone,
                    local_study_date,deck_revision_id,deck_sequence,exercise_generation_id,selection_policy_version,
                    reducer_config_id,seed,budget,issued_count,batch_start,batch_size,scan_cursor,wrapped,include_new,
                    practice_order,source_session_id,row_version,created_at,expires_at,completed_at)
                VALUES (:actor,:session,:deck,:command,:mode,:status,:timezone,:localDate,:deckRevision,:deckSequence,
                    :generation,'deck-due-new-v1','aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1',:seed,:budget,0,0,0,0,FALSE,
                    :includeNew,:practiceOrder,:source,0,:now,:expires,NULL)
                """).param("actor", actor).param("session", session).param("deck", deck).param("command", command)
                .param("mode", mode.name()).param("status", status).param("timezone", timezone)
                .param("localDate", localDate).param("deckRevision", deckRevision).param("deckSequence", deckSequence)
                .param("generation", generation).param("seed", seed).param("budget", value.maxPresentations())
                .param("includeNew", value.includeNew()).param("practiceOrder",
                        value.practiceOrder() == null ? null : value.practiceOrder().name(), java.sql.Types.VARCHAR)
                .param("source", sourceSession, java.sql.Types.OTHER).param("now", Timestamp.from(now))
                .param("expires", Timestamp.from(expires)).update();
    }

    List<Candidate> candidates(UUID generation, int from, int limit) {
        return jdbc.sql("""
                SELECT c.candidate_ordinal,c.exercise_id,c.exercise_revision_id,r.exercise_type,c.objective_id,
                       c.objective_revision_id,c.member_key,r.prompt_spec,r.evaluator_policy,o.answer_contract
                  FROM app_learning.study_candidate c
                  JOIN app_learning.exercise_revision r ON r.deck_id=c.deck_id AND r.exercise_id=c.exercise_id
                    AND r.revision_id=c.exercise_revision_id
                  JOIN app_learning.objective_revision o ON o.deck_id=c.deck_id AND o.objective_id=c.objective_id
                    AND o.revision_id=c.objective_revision_id
                 WHERE c.generation_id=:generation AND c.candidate_ordinal>=:from
                 ORDER BY c.candidate_ordinal LIMIT :limit
                """).param("generation", generation).param("from", from).param("limit", limit).query((row, ignored) ->
                new Candidate(row.getInt("candidate_ordinal"), row.getObject("exercise_id", UUID.class),
                        row.getObject("exercise_revision_id", UUID.class), row.getString("exercise_type"),
                        row.getObject("objective_id", UUID.class), row.getObject("objective_revision_id", UUID.class),
                        row.getObject("member_key", UUID.class), json(row.getString("prompt_spec")),
                        json(row.getString("evaluator_policy")), json(row.getString("answer_contract")))).list();
    }

    List<JsonNode> bindings(UUID deck, UUID exercise, UUID revision) {
        return jdbc.sql("""
                SELECT jsonb_build_object('bindingId',binding_id::text,'role',role,'memberKey',member_key::text,
                           'itemRevisionId',item_revision_id::text,'ordinal',binding_ordinal,
                           'nodeIds',to_jsonb(node_ids),'display',display_spec) AS binding
                  FROM app_learning.exercise_content_binding
                 WHERE deck_id=:deck AND exercise_id=:exercise AND exercise_revision_id=:revision
                 ORDER BY binding_ordinal
                """).param("deck", deck).param("exercise", exercise).param("revision", revision)
                .query((row, ignored) -> json(row.getString("binding"))).list();
    }

    Optional<Material> material(UUID deck, UUID member, UUID revision) {
        return jdbc.sql("""
                SELECT member_key,revision_id,reuse_scope_id,content_root_id FROM app_learning.item_revision
                 WHERE deck_id=:deck AND member_key=:member AND revision_id=:revision
                """).param("deck", deck).param("member", member).param("revision", revision)
                .query((row, ignored) -> new Material(row.getObject("member_key", UUID.class),
                        row.getObject("revision_id", UUID.class), row.getObject("reuse_scope_id", UUID.class),
                        row.getObject("content_root_id", UUID.class))).optional();
    }

    void insertPresentation(UUID actor, UUID session, UUID deck, UUID generation, Candidate candidate, UUID id,
                            int ordinal, String nonce, long learningEpoch, JsonNode prompt, JsonNode options, JsonNode bindings,
                            Instant now, Instant expires) {
        jdbc.sql("""
                INSERT INTO app_learning.study_presentation(account_id,session_id,presentation_id,presentation_ordinal,
                    nonce,deck_id,generation_id,candidate_ordinal,exercise_id,exercise_revision_id,exercise_type,
                    objective_id,objective_revision_id,learning_epoch,prompt,options,bindings,evaluator,answer_contract,
                    issued_at,expires_at)
                VALUES (:actor,:session,:id,:ordinal,:nonce,:deck,:generation,:candidateOrdinal,:exercise,
                    :exerciseRevision,:type,:objective,:objectiveRevision,:epoch,CAST(:prompt AS jsonb),CAST(:options AS jsonb),
                    CAST(:bindings AS jsonb),CAST(:evaluator AS jsonb),CAST(:answer AS jsonb),:now,:expires)
                """).param("actor", actor).param("session", session).param("id", id).param("ordinal", ordinal)
                .param("nonce", nonce).param("deck", deck).param("generation", generation)
                .param("candidateOrdinal", candidate.ordinal()).param("exercise", candidate.exerciseId())
                .param("exerciseRevision", candidate.exerciseRevisionId()).param("type", candidate.type())
                .param("objective", candidate.objectiveId()).param("objectiveRevision", candidate.objectiveRevisionId())
                .param("epoch", learningEpoch)
                .param("prompt", prompt.toString()).param("options", options.toString())
                .param("bindings", bindings.toString()).param("evaluator", candidate.evaluator().toString())
                .param("answer", candidate.answerContract().toString()).param("now", Timestamp.from(now))
                .param("expires", Timestamp.from(expires)).update();
    }

    long ensureState(UUID actor, UUID deck, UUID objective, UUID config, Instant now) {
        jdbc.sql("""
                INSERT INTO app_learning.study_policy_assignment(account_id,deck_id,objective_id,reducer_config_id,
                    assigned_at)
                VALUES (:actor,:deck,:objective,:config,:now)
                ON CONFLICT (account_id,deck_id,objective_id) DO NOTHING
                """).param("actor", actor).param("deck", deck).param("objective", objective)
                .param("config", config).param("now", Timestamp.from(now)).update();
        jdbc.sql("""
                INSERT INTO app_learning.study_state(account_id,deck_id,objective_id,learning_epoch,level,
                    correct_streak,lapse_count,last_assessed_at,next_due,reducer_config_id,transition_sequence,
                    row_version,introduced_at,updated_at)
                VALUES (:actor,:deck,:objective,0,0,0,0,NULL,NULL,:config,0,0,:now,:now)
                ON CONFLICT (account_id,deck_id,objective_id) DO NOTHING
                """).param("actor", actor).param("deck", deck).param("objective", objective)
                .param("config", config).param("now", Timestamp.from(now)).update();
        return jdbc.sql("""
                SELECT learning_epoch FROM app_learning.study_state
                 WHERE account_id=:actor AND deck_id=:deck AND objective_id=:objective
                """).param("actor", actor).param("deck", deck).param("objective", objective)
                .query(Long.class).single();
    }

    Optional<Long> stateEpoch(UUID actor, UUID deck, UUID objective) {
        return jdbc.sql("""
                SELECT learning_epoch FROM app_learning.study_state
                 WHERE account_id=:actor AND deck_id=:deck AND objective_id=:objective
                """).param("actor", actor).param("deck", deck).param("objective", objective)
                .query(Long.class).optional();
    }

    void insertExposure(UUID actor, UUID session, UUID presentation, UUID deck, UUID objective,
                        long epoch, Instant now) {
        jdbc.sql("""
                INSERT INTO app_learning.study_exposure(account_id,session_id,presentation_id,deck_id,objective_id,
                    learning_epoch,exposed_at)
                VALUES (:actor,:session,:presentation,:deck,:objective,:epoch,:now)
                """).param("actor", actor).param("session", session).param("presentation", presentation)
                .param("deck", deck).param("objective", objective).param("epoch", epoch)
                .param("now", Timestamp.from(now)).update();
    }

    void copyPresentation(UUID actor, UUID sourceSession, UUID targetSession, UUID deck, UUID generation,
                          Presentation source, UUID id, int ordinal, String nonce, Instant now, Instant expires) {
        jdbc.sql("""
                INSERT INTO app_learning.study_presentation(account_id,session_id,presentation_id,presentation_ordinal,
                    nonce,deck_id,generation_id,candidate_ordinal,exercise_id,exercise_revision_id,exercise_type,
                    objective_id,objective_revision_id,learning_epoch,prompt,options,bindings,evaluator,answer_contract,
                    issued_at,expires_at)
                VALUES (:actor,:target,:id,:ordinal,:nonce,:deck,:generation,:candidate,:exercise,:exerciseRevision,
                    :type,:objective,:objectiveRevision,:epoch,CAST(:prompt AS jsonb),CAST(:options AS jsonb),
                    CAST(:bindings AS jsonb),CAST(:evaluator AS jsonb),CAST(:answer AS jsonb),:now,:expires)
                """).param("actor", actor).param("target", targetSession).param("id", id).param("ordinal", ordinal)
                .param("nonce", nonce).param("deck", deck).param("generation", generation)
                .param("candidate", source.candidateOrdinal()).param("exercise", source.exerciseId())
                .param("exerciseRevision", source.exerciseRevisionId()).param("type", source.type())
                .param("objective", source.objectiveId()).param("objectiveRevision", source.objectiveRevisionId())
                .param("epoch", source.learningEpoch()).param("prompt", source.prompt().toString())
                .param("options", source.options().toString()).param("bindings", source.bindings().toString())
                .param("evaluator", source.evaluator().toString()).param("answer", source.answerContract().toString())
                .param("now", Timestamp.from(now)).param("expires", Timestamp.from(expires)).update();
    }

    List<Presentation> presentations(UUID actor, UUID session, int start, int limit) {
        return jdbc.sql("""
                SELECT * FROM app_learning.study_presentation
                 WHERE account_id=:actor AND session_id=:session AND presentation_ordinal>=:start
                 ORDER BY presentation_ordinal LIMIT :limit
                """).param("actor", actor).param("session", session).param("start", start).param("limit", limit)
                .query(PRESENTATION).list();
    }

    List<Presentation> pendingPresentations(UUID actor, UUID session, int start, int limit) {
        return jdbc.sql("""
                SELECT p.* FROM app_learning.study_presentation p
                 WHERE p.account_id=:actor AND p.session_id=:session AND p.presentation_ordinal>=:start
                   AND NOT EXISTS (
                       SELECT 1 FROM app_learning.study_attempt_tombstone t
                        WHERE t.account_id=p.account_id AND t.session_id=p.session_id
                          AND t.presentation_id=p.presentation_id
                   )
                 ORDER BY p.presentation_ordinal LIMIT :limit
                """).param("actor", actor).param("session", session).param("start", start).param("limit", limit)
                .query(PRESENTATION).list();
    }

    void updateSessionBatch(Session session, String status, int issued, int batchStart, int batchSize,
                            int scanCursor, boolean wrapped) {
        jdbc.sql("""
                UPDATE app_learning.study_session SET status=:status,issued_count=:issued,batch_start=:batchStart,
                    batch_size=:batchSize,scan_cursor=:cursor,wrapped=:wrapped,row_version=row_version+1
                 WHERE account_id=:actor AND session_id=:session AND row_version=:version
                """).param("status", status).param("issued", issued).param("batchStart", batchStart)
                .param("batchSize", batchSize).param("cursor", scanCursor).param("wrapped", wrapped)
                .param("actor", session.accountId()).param("session", session.sessionId())
                .param("version", session.rowVersion()).update();
    }

    Instant now() { return jdbc.sql("SELECT statement_timestamp()").query(Timestamp.class).single().toInstant(); }

    private static JsonNode json(String value) {
        try { return JSON.readTree(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Invalid persisted JSON", exception); }
    }
}
