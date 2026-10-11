package app.mnema.learning.study.retention;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * SQL of the snapshot purge (#460, migration V59). It opens no transaction: {@link StudySnapshotRetention} does, and the
 * immutability guards of the five snapshot tables let a DELETE through only inside a transaction that called {@link #allowDeletes()}.
 */
@Repository
class StudySnapshotRepository {
    /** The tables whose rows hang from a presentation or its session by {@code (account_id, session_id)}; presentations go last. */
    private static final List<String> SESSION_CHILDREN = List.of("study_hint_reveal", "study_transcript_accommodation",
            "study_assessment", "study_pair_interaction", "study_exposure");
    private static final String SESSIONS = "unnest(CAST(:accounts AS uuid[]),CAST(:sessions AS uuid[])) AS s(account_id,session_id)";

    private final JdbcClient jdbc;

    StudySnapshotRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    record SessionRef(UUID accountId, UUID sessionId, UUID commandId, UUID deckId) { }

    record GenerationRef(UUID deckId, UUID generationId) { }

    /** Counts per table of one purged batch. */
    record Purged(int hints, int transcripts, int assessments, int pairs, int exposures, int presentations,
                  int receipts) { }

    void allowDeletes() {
        jdbc.sql("SELECT set_config('mnema.study_retention','on',true)").query().listOfRows();
    }

    /**
     * The oldest sessions past the horizon whose snapshot is still there; rows another pass holds (or a submit, a dispute, a reveal) are skipped.
     * A session a REPLAY child still reads is not taken: the child copies its presentations batch by batch for as long as the child lives.
     */
    List<SessionRef> lockOldest(Duration horizon, int limit) {
        return jdbc.sql("""
                SELECT account_id,session_id,command_id,deck_id FROM app_learning.study_session source
                 WHERE presentations_purged_at IS NULL
                   AND created_at < statement_timestamp() - make_interval(secs => :seconds)
                   AND NOT EXISTS (SELECT 1 FROM app_learning.study_session replay WHERE replay.account_id=source.account_id
                        AND replay.source_session_id=source.session_id AND replay.presentations_purged_at IS NULL)
                 ORDER BY created_at,session_id LIMIT :limit FOR UPDATE OF source SKIP LOCKED
                """).param("seconds", (double) horizon.toSeconds()).param("limit", limit)
                .query((row, ignored) -> new SessionRef(row.getObject("account_id", UUID.class),
                        row.getObject("session_id", UUID.class), row.getObject("command_id", UUID.class),
                        row.getObject("deck_id", UUID.class))).list();
    }

    Purged purge(List<SessionRef> sessions) {
        UUID[] accounts = sessions.stream().map(SessionRef::accountId).toArray(UUID[]::new);
        UUID[] ids = sessions.stream().map(SessionRef::sessionId).toArray(UUID[]::new);
        UUID[] commands = sessions.stream().map(SessionRef::commandId).toArray(UUID[]::new);
        int[] children = new int[SESSION_CHILDREN.size()];
        for (int index = 0; index < children.length; index++) children[index] = deleteBySession(SESSION_CHILDREN.get(index), accounts, ids);
        int presentations = deleteBySession("study_presentation", accounts, ids);
        // The start receipt keeps its command id, actor and payload hash (a retry of the same command still conflicts or replays);
        // only the presentations it carried go.
        int receipts = jdbc.sql("""
                UPDATE app_learning.command_receipt r
                   SET result=jsonb_build_object('sessionId',r.result->'sessionId','deckId',r.result->'deckId',
                       'mode',r.result->'mode','status',r.result->'status','compacted',TRUE)
                  FROM unnest(CAST(:commands AS uuid[])) AS c(command_id)
                 WHERE r.command_id=c.command_id AND r.command_type='study.start' AND r.result->'compacted' IS NULL
                """).param("commands", commands).update();
        jdbc.sql("""
                UPDATE app_learning.study_session session SET presentations_purged_at=statement_timestamp()
                  FROM {SESSIONS}
                 WHERE session.account_id=s.account_id AND session.session_id=s.session_id
                """.replace("{SESSIONS}", SESSIONS)).param("accounts", accounts).param("sessions", ids).update();
        return new Purged(children[0], children[1], children[2], children[3], children[4], presentations, receipts);
    }

    private int deleteBySession(String table, UUID[] accounts, UUID[] sessions) {
        // table names come from the constant list above, never from input
        return jdbc.sql("DELETE FROM app_learning." + table + " x USING " + SESSIONS
                + " WHERE x.account_id=s.account_id AND x.session_id=s.session_id")
                .param("accounts", accounts).param("sessions", sessions).update();
    }

    /** The generations of {@code decks} that no other transaction holds, locked (a start reads its generation FOR SHARE and keeps it until it commits). */
    List<GenerationRef> lockGenerationsOf(List<UUID> decks) {
        return jdbc.sql("""
                SELECT deck_id,generation_id FROM app_learning.study_candidate_generation
                 WHERE deck_id=ANY(CAST(:decks AS uuid[])) AND evicting_since IS NULL FOR UPDATE SKIP LOCKED
                """).param("decks", decks.toArray(UUID[]::new))
                .query((row, ignored) -> new GenerationRef(row.getObject(1, UUID.class), row.getObject(2, UUID.class))).list();
    }

    /**
     * The bounded sweep: the {@code limit} generations least recently looked at (never first, then by age) that are older than the horizon, locked.
     * Their {@code swept_at} is set so that the next pass looks at others: a generation the deck still uses cannot hold the front of the line.
     */
    List<GenerationRef> lockForSweep(Duration horizon, int limit) {
        List<GenerationRef> locked = jdbc.sql("""
                SELECT deck_id,generation_id FROM app_learning.study_candidate_generation
                 WHERE evicting_since IS NULL AND created_at < statement_timestamp() - make_interval(secs => :seconds)
                 ORDER BY swept_at NULLS FIRST,created_at LIMIT :limit FOR UPDATE SKIP LOCKED
                """).param("seconds", (double) horizon.toSeconds()).param("limit", limit)
                .query((row, ignored) -> new GenerationRef(row.getObject(1, UUID.class), row.getObject(2, UUID.class))).list();
        if (!locked.isEmpty()) {
            jdbc.sql("""
                    UPDATE app_learning.study_candidate_generation g SET swept_at=statement_timestamp()
                      FROM unnest(CAST(:decks AS uuid[]),CAST(:generations AS uuid[])) AS l(deck_id,generation_id)
                     WHERE g.deck_id=l.deck_id AND g.generation_id=l.generation_id
                    """).param("decks", locked.stream().map(GenerationRef::deckId).toArray(UUID[]::new))
                    .param("generations", locked.stream().map(GenerationRef::generationId).toArray(UUID[]::new)).update();
        }
        return locked;
    }

    /**
     * Makes the locked generations that nothing reads unreachable: no session of theirs still has its snapshot, and the deck's head does
     * not use their exercises root (or the deck is deleted). The root becomes a random id (it can never be adopted again) and
     * {@code evicting_since} says that {@link #deleteCandidates} owns them.
     */
    int markEvicting(List<GenerationRef> locked) {
        if (locked.isEmpty()) return 0;
        // a separate statement from the locks: its snapshot is taken after every lock was acquired, so a start that committed meanwhile is visible
        return jdbc.sql("""
                UPDATE app_learning.study_candidate_generation g SET evicting_since=statement_timestamp(),exercises_root_id=gen_random_uuid()
                  FROM unnest(CAST(:decks AS uuid[]),CAST(:generations AS uuid[])) AS l(deck_id,generation_id)
                 WHERE g.deck_id=l.deck_id AND g.generation_id=l.generation_id AND g.evicting_since IS NULL
                   AND NOT EXISTS (SELECT 1 FROM app_learning.study_session s WHERE s.deck_id=g.deck_id
                        AND s.exercise_generation_id=g.generation_id AND s.presentations_purged_at IS NULL)
                   AND NOT EXISTS (SELECT 1 FROM app_learning.deck d JOIN app_learning.deck_revision r
                            ON r.deck_id=d.deck_id AND r.revision_id=d.head_revision_id
                        WHERE d.deck_id=g.deck_id AND d.deleted_at IS NULL AND r.exercises_root_id=g.exercises_root_id)
                """).param("decks", locked.stream().map(GenerationRef::deckId).toArray(UUID[]::new))
                .param("generations", locked.stream().map(GenerationRef::generationId).toArray(UUID[]::new)).update();
    }

    /** The oldest generation being evicted that no other pass holds. */
    GenerationRef lockEvicting() {
        return jdbc.sql("""
                SELECT deck_id,generation_id FROM app_learning.study_candidate_generation
                 WHERE evicting_since IS NOT NULL ORDER BY evicting_since,generation_id LIMIT 1 FOR UPDATE SKIP LOCKED
                """).query((row, ignored) -> new GenerationRef(row.getObject(1, UUID.class), row.getObject(2, UUID.class)))
                .optional().orElse(null);
    }

    int deleteCandidates(UUID generation, int limit) {
        return jdbc.sql("""
                DELETE FROM app_learning.study_candidate WHERE (generation_id,candidate_ordinal) IN (
                    SELECT generation_id,candidate_ordinal FROM app_learning.study_candidate WHERE generation_id=:generation LIMIT :limit)
                """).param("generation", generation).param("limit", limit).update();
    }

    void deleteGeneration(GenerationRef generation) {
        jdbc.sql("DELETE FROM app_learning.study_candidate_generation WHERE deck_id=:deck AND generation_id=:generation")
                .param("deck", generation.deckId()).param("generation", generation.generationId()).update();
    }

    // ------------------------------------------------------------------------------------------------------ gauges

    /** Sessions past the horizon whose snapshot is still there (counted up to 100 000, through the partial index). */
    double backlog(Duration horizon) {
        return jdbc.sql("""
                SELECT count(*) FROM (SELECT 1 FROM app_learning.study_session WHERE presentations_purged_at IS NULL
                   AND created_at < statement_timestamp() - make_interval(secs => :seconds) LIMIT 100000) due
                """).param("seconds", (double) horizon.toSeconds()).query(Long.class).single();
    }

    /** Age of the oldest session that still has its snapshot, 0 when there is none. */
    double oldestAgeSeconds() {
        return jdbc.sql("""
                SELECT COALESCE(extract(epoch FROM statement_timestamp()-min(created_at)),0)::float8
                  FROM (SELECT created_at FROM app_learning.study_session WHERE presentations_purged_at IS NULL
                         ORDER BY created_at LIMIT 1) oldest
                """).query(Double.class).single();
    }
}
