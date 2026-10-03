package app.mnema.learning.generation;

import app.mnema.learning.generation.Rows.Artifact;
import app.mnema.learning.generation.Rows.Event;
import app.mnema.learning.generation.Rows.EventDraft;
import app.mnema.learning.generation.Rows.Revision;
import app.mnema.learning.generation.Rows.Session;
import app.mnema.learning.generation.Rows.Slot;
import app.mnema.learning.generation.Rows.Source;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * SQL of sessions, artifacts, revisions, media slots and events. Every state-changing statement runs inside a service
 * transaction that took {@link #lockSession} first: the session row is the one lock of the aggregate (it also allocates
 * the event {@code seq}, see {@code contracts/generation/events.json}), and rows below it are locked after it.
 */
@Repository
class GenerationRepository {
    static final List<String> ARTIFACT_STATES = List.of("QUEUED", "GENERATING", "PROPOSED", "REVISING", "FAILED", "REJECTED",
            "STALE", "PUBLISHED", "HANDED_OFF");
    private static final String SESSION_COLUMNS = "session_id,owner_id,deck_id,kind,state,end_reason,spec::text AS spec,"
            + "reservation_id,row_version,last_event_seq,created_at,last_activity_at,expires_at";
    private static final String ARTIFACT_COLUMNS = "artifact_id,session_id,owner_id,target_kind,ordinal,state,error_code,"
            + "repin_status,title,current_revision_id,revision_count,draft_generation,source_refs::text AS source_refs,"
            + "published_ref::text AS published_ref,row_version";
    /** An artifact is approvable now: PROPOSED and every media slot of its current revision READY or REMOVED. */
    private static final String APPROVABLE = "a.state='PROPOSED' AND NOT EXISTS (SELECT 1 FROM app_learning.generation_media_slot m "
            + "WHERE m.artifact_id=a.artifact_id AND m.revision_id=a.current_revision_id AND m.state NOT IN ('READY','REMOVED'))";

    /** The deck of a session is not tombstoned: the sessions of a deleted deck are as absent as the deck. */
    private static final String LIVE_DECK = " AND EXISTS (SELECT 1 FROM app_learning.deck d WHERE d.deck_id=generation_session.deck_id "
            + "AND d.deleted_at IS NULL)";
    private static final RowMapper<Session> SESSION = (row, ignored) -> new Session(row.getObject("session_id", UUID.class),
            row.getObject("owner_id", UUID.class), row.getObject("deck_id", UUID.class), row.getString("kind"),
            row.getString("state"), row.getString("end_reason"), Json.read(row.getString("spec")),
            row.getObject("reservation_id", UUID.class), row.getLong("row_version"), row.getLong("last_event_seq"),
            instant(row, "created_at"), instant(row, "last_activity_at"), instant(row, "expires_at"));

    private static final RowMapper<Artifact> ARTIFACT = (row, ignored) -> new Artifact(row.getObject("artifact_id", UUID.class),
            row.getObject("session_id", UUID.class), row.getObject("owner_id", UUID.class), row.getString("target_kind"),
            row.getInt("ordinal"), row.getString("state"), row.getString("error_code"), row.getString("repin_status"),
            row.getString("title"), row.getObject("current_revision_id", UUID.class), row.getInt("revision_count"),
            row.getInt("draft_generation"), Json.read(row.getString("source_refs")),
            row.getString("published_ref") == null ? null : Json.read(row.getString("published_ref")), row.getLong("row_version"));

    private static final RowMapper<Revision> REVISION = (row, ignored) -> new Revision(row.getObject("revision_id", UUID.class),
            row.getObject("artifact_id", UUID.class), row.getInt("revision_no"), row.getString("cause"),
            Json.read(row.getString("payload")), Json.read(row.getString("handles")), row.getString("prompt_version"),
            row.getString("model_route"), Json.read(row.getString("validation")), instant(row, "created_at"));

    private static final RowMapper<Slot> SLOT = (row, ignored) -> new Slot(row.getObject("artifact_id", UUID.class),
            row.getString("slot_key"), row.getObject("revision_id", UUID.class), row.getObject("node_id", UUID.class),
            row.getString("kind"), Json.read(row.getString("spec")), row.getObject("asset_id", UUID.class),
            row.getString("state"), row.getString("error_code"));

    private final JdbcClient jdbc;

    GenerationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    static Instant instant(ResultSet row, String column) throws SQLException {
        return row.getObject(column, OffsetDateTime.class).toInstant();
    }

    // ------------------------------------------------------------------ ownership

    boolean deckOwned(UUID owner, UUID deck) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM app_learning.deck WHERE deck_id=:deck AND owner_id=:owner AND deleted_at IS NULL)")
                .param("deck", deck).param("owner", owner).query(Boolean.class).single();
    }

    /** Serializes the admission of one owner, so two creates cannot both pass the active-session limit. */
    void lockAdmission(UUID owner) {
        jdbc.sql("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended('generation.admission:' || :owner, 0))) lock")
                .param("owner", owner.toString()).query(Integer.class).single();
    }

    // ------------------------------------------------------------------- sessions

    void insertSession(Session session, Duration retention) {
        jdbc.sql("INSERT INTO app_learning.generation_session(session_id,owner_id,deck_id,kind,state,spec,reservation_id,"
                        + "row_version,last_event_seq,created_at,last_activity_at,expires_at) VALUES (:id,:owner,:deck,:kind,:state,"
                        + "CAST(:spec AS jsonb),:reservation,0,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,"
                        + "CURRENT_TIMESTAMP + (:retention * interval '1 second'))")
                .param("id", session.sessionId()).param("owner", session.ownerId()).param("deck", session.deckId())
                .param("kind", session.kind()).param("state", session.state()).param("spec", Json.write(session.spec()))
                .param("reservation", session.reservationId()).param("retention", retention.toSeconds()).update();
    }

    Optional<Session> lockSession(UUID sessionId) {
        return jdbc.sql("SELECT " + SESSION_COLUMNS + " FROM app_learning.generation_session WHERE session_id=:id FOR UPDATE")
                .param("id", sessionId).query(SESSION).optional();
    }

    Optional<Session> session(UUID sessionId) {
        return jdbc.sql("SELECT " + SESSION_COLUMNS + " FROM app_learning.generation_session WHERE session_id=:id")
                .param("id", sessionId).query(SESSION).optional();
    }

    /** The session of this owner and deck; any other combination is empty, the one opaque 404. */
    Optional<Session> session(UUID owner, UUID deck, UUID sessionId) {
        return jdbc.sql("SELECT " + SESSION_COLUMNS + " FROM app_learning.generation_session "
                        + "WHERE session_id=:id AND owner_id=:owner AND deck_id=:deck" + LIVE_DECK)
                .param("id", sessionId).param("owner", owner).param("deck", deck).query(SESSION).optional();
    }

    /**
     * Writes the session row of a transaction that holds its lock: the new state, a version bump when anything visible
     * changed, activity and expiry, and {@code events} consecutive event numbers.
     *
     * @return {@code {firstSeq, rowVersion}} after the update
     */
    long[] update(UUID sessionId, String state, String endReason, boolean touch, int events, Duration retention) {
        return jdbc.sql("UPDATE app_learning.generation_session SET state=:state,end_reason=:reason,"
                        + "row_version=row_version+:bump,last_event_seq=last_event_seq+:events,"
                        + "last_activity_at=CASE WHEN :touch THEN CURRENT_TIMESTAMP ELSE last_activity_at END,"
                        + "expires_at=CASE WHEN :touch THEN CURRENT_TIMESTAMP + (:retention * interval '1 second') ELSE expires_at END "
                        + "WHERE session_id=:id RETURNING last_event_seq-:events+1 AS first_seq,row_version")
                .param("state", state).param("reason", endReason).param("bump", touch ? 1 : 0).param("events", events)
                .param("touch", touch).param("retention", retention.toSeconds()).param("id", sessionId)
                .query((row, ignored) -> new long[] {row.getLong("first_seq"), row.getLong("row_version")}).single();
    }

    /** Appends events with numbers {@code firstSeq}, {@code firstSeq + 1}, ... in this transaction. */
    void insertEvents(UUID sessionId, long firstSeq, List<EventDraft> events) {
        long seq = firstSeq;
        for (EventDraft event : events) {
            jdbc.sql("INSERT INTO app_learning.generation_event(session_id,seq,artifact_id,type,payload) "
                            + "VALUES (:session,:seq,:artifact,:type,CAST(:payload AS jsonb))")
                    .param("session", sessionId).param("seq", seq++).param("artifact", event.artifactId())
                    .param("type", event.type()).param("payload", Json.write(event.payload())).update();
        }
    }

    void setReservation(UUID sessionId, UUID reservation) {
        jdbc.sql("UPDATE app_learning.generation_session SET reservation_id=:reservation WHERE session_id=:id")
                .param("reservation", reservation).param("id", sessionId).update();
    }

    /** The live sessions that count toward the admission limit (contract decision 3), oldest first. */
    List<UUID> activeSessionIds(UUID owner) {
        return jdbc.sql("SELECT s.session_id FROM app_learning.generation_session s WHERE s.owner_id=:owner "
                        + "AND EXISTS (SELECT 1 FROM app_learning.deck d WHERE d.deck_id=s.deck_id AND d.deleted_at IS NULL) AND ("
                        + "s.state IN ('PLANNING','PLAN_READY','RUNNING') OR (s.state='REVIEW' AND EXISTS ("
                        + "SELECT 1 FROM app_learning.generation_artifact a WHERE a.session_id=s.session_id "
                        + "AND a.state IN ('PROPOSED','REVISING','STALE')))) ORDER BY s.created_at,s.session_id")
                .param("owner", owner).query(UUID.class).list();
    }

    /** Sessions newest first; {@code deck} narrows to one deck, {@code activeOnly} to the four live states. */
    List<Session> page(UUID owner, UUID deck, boolean activeOnly, Instant afterActivity, UUID afterSession, int limit) {
        StringBuilder sql = new StringBuilder("SELECT " + SESSION_COLUMNS + " FROM app_learning.generation_session "
                + "WHERE owner_id=:owner" + LIVE_DECK);
        if (deck != null) sql.append(" AND deck_id=:deck");
        if (activeOnly) sql.append(" AND state IN ('PLANNING','PLAN_READY','RUNNING','REVIEW')");
        if (afterActivity != null) sql.append(" AND (last_activity_at,session_id) < (:activity,:session)");
        sql.append(" ORDER BY last_activity_at DESC,session_id DESC LIMIT :limit");
        var query = jdbc.sql(sql.toString()).param("owner", owner).param("limit", limit);
        if (deck != null) query = query.param("deck", deck);
        if (afterActivity != null) {
            query = query.param("activity", OffsetDateTime.ofInstant(afterActivity, java.time.ZoneOffset.UTC))
                    .param("session", afterSession);
        }
        return query.query(SESSION).list();
    }

    /** The owner's sessions that the notification center counts as work in progress. */
    int activeWork(UUID owner) {
        return jdbc.sql("SELECT count(*)::integer FROM app_learning.generation_session WHERE owner_id=:owner "
                + "AND state IN ('PLANNING','RUNNING')" + LIVE_DECK).param("owner", owner).query(Integer.class).single();
    }

    /** Sessions of {@code RUNNING} that hold a reservation, for its periodic renewal. */
    List<Session> runningWithReservation(UUID after, int limit) {
        return jdbc.sql("SELECT " + SESSION_COLUMNS + " FROM app_learning.generation_session WHERE state='RUNNING' "
                        + "AND reservation_id IS NOT NULL AND session_id>:after ORDER BY session_id LIMIT :limit")
                .param("after", after).param("limit", limit).query(SESSION).list();
    }

    // -------------------------------------------------------------------- sources

    void insertSource(UUID sessionId, UUID owner, Source source) {
        jdbc.sql("INSERT INTO app_learning.generation_session_source(session_id,owner_id,ordinal,role,type,note_id,"
                        + "note_row_version,member_key,item_revision_id) VALUES (:session,:owner,:ordinal,:role,:type,:note,"
                        + ":noteVersion,:member,:revision)")
                .param("session", sessionId).param("owner", owner).param("ordinal", source.ordinal())
                .param("role", source.role()).param("type", source.type()).param("note", source.noteId())
                .param("noteVersion", source.noteRowVersion()).param("member", source.memberKey())
                .param("revision", source.itemRevisionId()).update();
    }

    List<Source> sources(UUID sessionId) {
        return jdbc.sql("SELECT ordinal,role,type,note_id,note_row_version,member_key,item_revision_id "
                        + "FROM app_learning.generation_session_source WHERE session_id=:id ORDER BY ordinal")
                .param("id", sessionId).query((row, ignored) -> new Source(row.getInt("ordinal"), row.getString("role"),
                        row.getString("type"), row.getObject("note_id", UUID.class), row.getObject("note_row_version", Long.class),
                        row.getObject("member_key", UUID.class), row.getObject("item_revision_id", UUID.class))).list();
    }

    // ------------------------------------------------------------ used notes

    /** A note a published or handed-off artifact was written from, with the row version the session pinned. */
    record UsedNote(UUID noteId, long pinnedRowVersion) { }

    /**
     * The distinct NOTE sources of the artifacts that left the Workshop as material or draft, by note id, that no artifact
     * still in play pins: archiving bumps the note's row version, which would turn a sibling that still pins it STALE.
     * "In play" is QUEUED, GENERATING, PROPOSED, REVISING, STALE and a retryable FAILED.
     */
    List<UsedNote> usedNotes(UUID sessionId) {
        return jdbc.sql("SELECT note_id,pin FROM (SELECT ref->>'noteId' AS note_id,max((ref->>'noteRowVersion')::bigint) AS pin "
                        + "FROM app_learning.generation_artifact a,jsonb_array_elements(a.source_refs) ref "
                        + "WHERE a.session_id=:id AND a.state IN ('PUBLISHED','HANDED_OFF') AND ref->>'type'='NOTE' "
                        + "GROUP BY ref->>'noteId') used WHERE NOT EXISTS (SELECT 1 FROM app_learning.generation_artifact b,"
                        + "jsonb_array_elements(b.source_refs) other WHERE b.session_id=:id AND other->>'type'='NOTE' "
                        + "AND other->>'noteId'=used.note_id AND (b.state IN ('QUEUED','GENERATING','PROPOSED','REVISING','STALE') "
                        + "OR (b.state='FAILED' AND b.error_code IS DISTINCT FROM 'REFUSAL'))) ORDER BY note_id")
                .param("id", sessionId).query((row, ignored) -> new UsedNote(UUID.fromString(row.getString("note_id")),
                        row.getLong("pin"))).list();
    }

    /** What a note is now: its row version and whether it is archived. Deleted notes are absent from the result. */
    record NoteState(long rowVersion, boolean archived) { }

    /** The owner's notes of this deck by id; {@code lock} takes row locks in id order (the archive that follows is a CAS). */
    Map<UUID, NoteState> noteStates(UUID owner, UUID deck, Collection<UUID> notes, boolean lock) {
        Map<UUID, NoteState> result = new HashMap<>();
        if (notes.isEmpty()) return result;
        jdbc.sql("SELECT note_id,row_version,archived FROM app_learning.capture_note WHERE owner_id=:owner AND deck_id=:deck "
                        + "AND note_id IN (:ids) ORDER BY note_id" + (lock ? " FOR UPDATE" : ""))
                .param("owner", owner).param("deck", deck).param("ids", notes)
                .query((row, ignored) -> result.put(row.getObject("note_id", UUID.class),
                        new NoteState(row.getLong("row_version"), row.getBoolean("archived")))).list();
        return result;
    }

    // ------------------------------------------------------------------ retention

    /** Live sessions whose {@code expires_at} has passed: they become EXPIRED and stay readable for a while. */
    List<UUID> dueForExpiry(int limit) {
        return jdbc.sql("SELECT session_id FROM app_learning.generation_session WHERE state IN ('PLANNING','PLAN_READY','RUNNING','REVIEW') "
                        + "AND expires_at<=CURRENT_TIMESTAMP ORDER BY expires_at,session_id LIMIT :limit")
                .param("limit", limit).query(UUID.class).list();
    }

    /** Sessions to delete: CLOSED and CANCELLED ones at {@code expires_at}, EXPIRED ones {@code grace} later. */
    List<UUID> dueForPurge(Duration grace, int limit) {
        return jdbc.sql("SELECT session_id FROM app_learning.generation_session WHERE (state IN ('CLOSED','CANCELLED') "
                        + "AND expires_at<=CURRENT_TIMESTAMP) OR (state='EXPIRED' AND expires_at+(:grace * interval '1 second')<=CURRENT_TIMESTAMP) "
                        + "ORDER BY expires_at,session_id LIMIT :limit")
                .param("grace", grace.toSeconds()).param("limit", limit).query(UUID.class).list();
    }

    /** The session's expiry has passed now (the database clock decides, as in the queries that found it). */
    boolean expiredNow(UUID sessionId) {
        return jdbc.sql("SELECT expires_at<=CURRENT_TIMESTAMP FROM app_learning.generation_session WHERE session_id=:id")
                .param("id", sessionId).query(Boolean.class).optional().orElse(false);
    }

    /** An EXPIRED session whose readable window ({@code grace} after expiry) has passed now. */
    boolean purgeDueNow(UUID sessionId, Duration grace) {
        return jdbc.sql("SELECT expires_at+(:grace * interval '1 second')<=CURRENT_TIMESTAMP FROM app_learning.generation_session "
                        + "WHERE session_id=:id").param("grace", grace.toSeconds()).param("id", sessionId)
                .query(Boolean.class).optional().orElse(false);
    }

    /**
     * Events of sessions that ended (CLOSED, CANCELLED, EXPIRED) more than {@code after} ago: nobody polls them any more
     * (architecture section 5). An EXPIRED session ended at its expiry, the others at their last activity.
     */
    int deleteOldEvents(Duration after, int limit) {
        return jdbc.sql("DELETE FROM app_learning.generation_event e WHERE (e.session_id,e.seq) IN (SELECT e2.session_id,e2.seq "
                        + "FROM app_learning.generation_event e2 JOIN app_learning.generation_session s ON s.session_id=e2.session_id "
                        + "WHERE s.state IN ('CLOSED','CANCELLED','EXPIRED') AND CASE WHEN s.state='EXPIRED' THEN s.expires_at "
                        + "ELSE s.last_activity_at END+(:after * interval '1 second')<=CURRENT_TIMESTAMP LIMIT :limit)")
                .param("after", after.toSeconds()).param("limit", limit).update();
    }

    /**
     * Live sessions that expire within {@code lead} (and not yet), by id after {@code after}, that still need a warning: a
     * proposal remains that an expiry would delete, and no notification exists for this expiry date yet (the dedupe key of
     * {@code contracts/notifications}); the pages of one sweep.
     */
    List<Session> expiring(Duration lead, UUID after, int limit) {
        return jdbc.sql("SELECT " + SESSION_COLUMNS + " FROM app_learning.generation_session WHERE state IN "
                        + "('PLANNING','PLAN_READY','RUNNING','REVIEW') AND expires_at>CURRENT_TIMESTAMP "
                        + "AND expires_at<=CURRENT_TIMESTAMP+(:lead * interval '1 second') AND session_id>:after "
                        + "AND EXISTS (SELECT 1 FROM app_learning.generation_artifact a WHERE a.session_id=generation_session.session_id "
                        + "AND a.state IN ('PROPOSED','REVISING','STALE')) "
                        + "AND NOT EXISTS (SELECT 1 FROM app_learning.notification n WHERE n.owner_id=generation_session.owner_id "
                        + "AND n.dedupe_key='generation:'||generation_session.session_id||':expiring:'"
                        + "||to_char(generation_session.expires_at AT TIME ZONE 'UTC','YYYY-MM-DD')) "
                        + "ORDER BY session_id LIMIT :limit")
                .param("lead", lead.toSeconds()).param("after", after).param("limit", limit).query(SESSION).list();
    }

    /** The artifacts the user could still act on: what an expiry would delete. */
    int pendingArtifacts(UUID sessionId) {
        return jdbc.sql("SELECT count(*)::integer FROM app_learning.generation_artifact WHERE session_id=:id "
                + "AND state IN ('PROPOSED','REVISING','STALE')").param("id", sessionId).query(Integer.class).single();
    }

    /**
     * The session ends as EXPIRED without moving its expiry or its activity: {@code events} consecutive event numbers and a
     * version bump, nothing else. @return the first event number
     */
    long expire(UUID sessionId, int events) {
        return jdbc.sql("UPDATE app_learning.generation_session SET state='EXPIRED',end_reason='EXPIRED',row_version=row_version+1,"
                        + "last_event_seq=last_event_seq+:events WHERE session_id=:id RETURNING last_event_seq-:events+1")
                .param("events", events).param("id", sessionId).query(Long.class).single();
    }

    // ------------------------------------------------------------------ artifacts

    void insertArtifact(UUID artifactId, UUID sessionId, UUID owner, int ordinal, JsonNode sourceRefs) {
        jdbc.sql("INSERT INTO app_learning.generation_artifact(artifact_id,session_id,owner_id,target_kind,ordinal,state,"
                        + "source_refs,row_version,created_at,updated_at) VALUES (:id,:session,:owner,'ITEM',:ordinal,'QUEUED',"
                        + "CAST(:refs AS jsonb),0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)")
                .param("id", artifactId).param("session", sessionId).param("owner", owner).param("ordinal", ordinal)
                .param("refs", Json.write(sourceRefs)).update();
    }

    List<Artifact> artifacts(UUID sessionId) {
        return jdbc.sql("SELECT " + ARTIFACT_COLUMNS + " FROM app_learning.generation_artifact WHERE session_id=:id ORDER BY ordinal")
                .param("id", sessionId).query(ARTIFACT).list();
    }

    Optional<Artifact> artifact(UUID sessionId, UUID artifactId) {
        return jdbc.sql("SELECT " + ARTIFACT_COLUMNS + " FROM app_learning.generation_artifact "
                        + "WHERE session_id=:session AND artifact_id=:id")
                .param("session", sessionId).param("id", artifactId).query(ARTIFACT).optional();
    }

    /**
     * Moves an artifact to {@code state}. {@code revision} and {@code title} change only when given. The caller holds
     * the session lock, so the version check is a sanity guard.
     *
     * @return the artifact after the update
     */
    Artifact transition(Artifact artifact, String state, String errorCode, UUID revision, String title, int revisionCount) {
        return jdbc.sql("UPDATE app_learning.generation_artifact SET state=:state,error_code=:error,"
                        + "current_revision_id=COALESCE(:revision,current_revision_id),title=COALESCE(:title,title),"
                        + "revision_count=:revisions,repin_status=CASE WHEN :state='PROPOSED' THEN NULL ELSE repin_status END,"
                        + "row_version=row_version+1,updated_at=CURRENT_TIMESTAMP "
                        + "WHERE artifact_id=:id AND row_version=:version RETURNING " + ARTIFACT_COLUMNS)
                .param("state", state).param("error", errorCode).param("revision", revision).param("title", title)
                .param("revisions", revisionCount).param("id", artifact.artifactId()).param("version", artifact.rowVersion())
                .query(ARTIFACT).optional().orElseThrow(() -> new IllegalStateException("Artifact changed under the session lock"));
    }

    /**
     * PROPOSED to PUBLISHED: the catalog reference and the id of the publication command are written with the state.
     *
     * @return the artifact after the update
     */
    Artifact publish(Artifact artifact, JsonNode publishedRef, UUID publicationCommandId) {
        return jdbc.sql("UPDATE app_learning.generation_artifact SET state='PUBLISHED',error_code=NULL,repin_status=NULL,"
                        + "published_ref=CAST(:ref AS jsonb),publication_command_id=:command,row_version=row_version+1,"
                        + "updated_at=CURRENT_TIMESTAMP WHERE artifact_id=:id AND row_version=:version RETURNING " + ARTIFACT_COLUMNS)
                .param("ref", Json.write(publishedRef)).param("command", publicationCommandId)
                .param("id", artifact.artifactId()).param("version", artifact.rowVersion())
                .query(ARTIFACT).optional().orElseThrow(() -> new IllegalStateException("Artifact changed under the session lock"));
    }

    /** PROPOSED to STALE: a pinned source changed; no re-pin job exists yet, so the user decides. */
    Artifact markStale(Artifact artifact) {
        return jdbc.sql("UPDATE app_learning.generation_artifact SET state='STALE',repin_status='NEEDS_USER_DECISION',"
                        + "row_version=row_version+1,updated_at=CURRENT_TIMESTAMP WHERE artifact_id=:id AND row_version=:version "
                        + "RETURNING " + ARTIFACT_COLUMNS)
                .param("id", artifact.artifactId()).param("version", artifact.rowVersion())
                .query(ARTIFACT).optional().orElseThrow(() -> new IllegalStateException("Artifact changed under the session lock"));
    }

    /** FAILED or STALE back to QUEUED for a retry: the error and the re-pin status are cleared, the pins replaced. */
    Artifact requeue(Artifact artifact, JsonNode sourceRefs) {
        return jdbc.sql("UPDATE app_learning.generation_artifact SET state='QUEUED',error_code=NULL,repin_status=NULL,"
                        + "source_refs=CAST(:refs AS jsonb),row_version=row_version+1,updated_at=CURRENT_TIMESTAMP "
                        + "WHERE artifact_id=:id AND row_version=:version RETURNING " + ARTIFACT_COLUMNS)
                .param("refs", Json.write(sourceRefs)).param("id", artifact.artifactId()).param("version", artifact.rowVersion())
                .query(ARTIFACT).optional().orElseThrow(() -> new IllegalStateException("Artifact changed under the session lock"));
    }

    /** Raises the draft generation counter to at least {@code generation} (it never decreases). */
    void raiseDraftGeneration(UUID artifactId, int generation) {
        jdbc.sql("UPDATE app_learning.generation_artifact SET draft_generation=GREATEST(draft_generation,:generation) "
                + "WHERE artifact_id=:id").param("generation", generation).param("id", artifactId).update();
    }

    /** Counts per artifact state of one session (absent states are zero). */
    Map<String, Integer> artifactCounts(UUID sessionId) {
        Map<String, Integer> counts = zeroCounts();
        jdbc.sql("SELECT state,count(*)::integer AS n FROM app_learning.generation_artifact WHERE session_id=:id GROUP BY state")
                .param("id", sessionId).query((row, ignored) -> counts.put(row.getString("state"), row.getInt("n"))).list();
        return counts;
    }

    /** {@code state -> n} for several sessions in one statement. */
    Map<UUID, Map<String, Integer>> artifactCounts(Collection<UUID> sessions) {
        Map<UUID, Map<String, Integer>> result = new HashMap<>();
        for (UUID session : sessions) result.put(session, zeroCounts());
        if (sessions.isEmpty()) return result;
        jdbc.sql("SELECT session_id,state,count(*)::integer AS n FROM app_learning.generation_artifact "
                        + "WHERE session_id IN (:ids) GROUP BY session_id,state").param("ids", sessions)
                .query((row, ignored) -> result.get(row.getObject("session_id", UUID.class)).put(row.getString("state"), row.getInt("n")))
                .list();
        return result;
    }

    private static Map<String, Integer> zeroCounts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String state : ARTIFACT_STATES) counts.put(state, 0);
        return counts;
    }

    int approvableCount(UUID sessionId) {
        return jdbc.sql("SELECT count(*)::integer FROM app_learning.generation_artifact a WHERE a.session_id=:id AND " + APPROVABLE)
                .param("id", sessionId).query(Integer.class).single();
    }

    Map<UUID, Integer> approvableCounts(Collection<UUID> sessions) {
        Map<UUID, Integer> result = new HashMap<>();
        for (UUID session : sessions) result.put(session, 0);
        if (sessions.isEmpty()) return result;
        jdbc.sql("SELECT a.session_id,count(*)::integer AS n FROM app_learning.generation_artifact a "
                        + "WHERE a.session_id IN (:ids) AND " + APPROVABLE + " GROUP BY a.session_id").param("ids", sessions)
                .query((row, ignored) -> result.put(row.getObject("session_id", UUID.class), row.getInt("n"))).list();
        return result;
    }

    /** Artifact error codes of FAILED artifacts with their counts, most frequent first (ties by name). */
    List<String> failureCodes(UUID sessionId) {
        return jdbc.sql("SELECT error_code FROM app_learning.generation_artifact WHERE session_id=:id AND state='FAILED' "
                        + "AND error_code IS NOT NULL GROUP BY error_code ORDER BY count(*) DESC,error_code")
                .param("id", sessionId).query(String.class).list();
    }

    /** FAILED artifacts the user can retry (every error code but REFUSAL); they keep a session in REVIEW. */
    int retryableFailures(UUID sessionId) {
        return jdbc.sql("SELECT count(*)::integer FROM app_learning.generation_artifact WHERE session_id=:id AND state='FAILED' "
                + "AND error_code IS DISTINCT FROM 'REFUSAL'").param("id", sessionId).query(Integer.class).single();
    }

    /** The head of the owner's live deck: its revision and row version, for the preconditions of an approval. */
    Optional<DeckHead> deckHead(UUID owner, UUID deck) {
        return jdbc.sql("SELECT head_revision_id,row_version FROM app_learning.deck WHERE deck_id=:deck AND owner_id=:owner "
                        + "AND deleted_at IS NULL").param("deck", deck).param("owner", owner)
                .query((row, ignored) -> new DeckHead(row.getObject("head_revision_id", UUID.class), row.getLong("row_version"))).optional();
    }

    record DeckHead(UUID revisionId, long version) { }

    boolean ownsArtifact(UUID owner, UUID deck, UUID sessionId, UUID artifactId) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM app_learning.generation_artifact a JOIN app_learning.generation_session s "
                        + "ON s.session_id=a.session_id AND s.owner_id=a.owner_id WHERE a.artifact_id=:artifact "
                        + "AND s.session_id=:session AND s.owner_id=:owner AND s.deck_id=:deck "
                        + "AND EXISTS (SELECT 1 FROM app_learning.deck d WHERE d.deck_id=s.deck_id AND d.deleted_at IS NULL))")
                .param("artifact", artifactId).param("session", sessionId).param("owner", owner).param("deck", deck)
                .query(Boolean.class).single();
    }

    // ------------------------------------------------------------------ revisions

    void insertRevision(Revision revision, UUID sessionId, UUID owner) {
        jdbc.sql("INSERT INTO app_learning.generation_artifact_revision(revision_id,artifact_id,session_id,owner_id,"
                        + "revision_no,cause,payload,handles,prompt_version,model_route,validation,created_at) VALUES (:id,:artifact,"
                        + ":session,:owner,:no,:cause,CAST(:payload AS jsonb),CAST(:handles AS jsonb),:prompt,:route,"
                        + "CAST(:validation AS jsonb),CURRENT_TIMESTAMP)")
                .param("id", revision.revisionId()).param("artifact", revision.artifactId()).param("session", sessionId)
                .param("owner", owner).param("no", revision.revisionNo()).param("cause", revision.cause())
                .param("payload", Json.write(revision.payload())).param("handles", Json.write(revision.handles()))
                .param("prompt", revision.promptVersion()).param("route", revision.modelRoute())
                .param("validation", Json.write(revision.validation())).update();
    }

    Optional<Revision> revision(UUID artifactId, UUID revisionId) {
        return jdbc.sql("SELECT revision_id,artifact_id,revision_no,cause,payload::text AS payload,handles::text AS handles,"
                        + "prompt_version,model_route,validation::text AS validation,created_at "
                        + "FROM app_learning.generation_artifact_revision WHERE artifact_id=:artifact AND revision_id=:id")
                .param("artifact", artifactId).param("id", revisionId).query(REVISION).optional();
    }

    /** Revision list of an artifact without payloads: {@code {revisionId, cause, createdAt}}, oldest first. */
    List<Revision> revisionList(UUID artifactId) {
        return jdbc.sql("SELECT revision_id,artifact_id,revision_no,cause,'{}'::text AS payload,'{}'::text AS handles,"
                        + "prompt_version,model_route,'{}'::text AS validation,created_at "
                        + "FROM app_learning.generation_artifact_revision WHERE artifact_id=:artifact ORDER BY revision_no")
                .param("artifact", artifactId).query(REVISION).list();
    }

    // ---------------------------------------------------------------- media slots

    void insertSlot(Slot slot, UUID sessionId, UUID owner) {
        jdbc.sql("INSERT INTO app_learning.generation_media_slot(artifact_id,slot_key,session_id,owner_id,revision_id,node_id,"
                        + "kind,spec,asset_id,state,created_at,updated_at) VALUES (:artifact,:key,:session,:owner,:revision,:node,"
                        + ":kind,CAST(:spec AS jsonb),:asset,'PENDING',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)")
                .param("artifact", slot.artifactId()).param("key", slot.slotKey()).param("session", sessionId)
                .param("owner", owner).param("revision", slot.revisionId()).param("node", slot.nodeId())
                .param("kind", slot.kind()).param("spec", Json.write(slot.spec())).param("asset", slot.assetId()).update();
    }

    List<Slot> slots(UUID artifactId, UUID revisionId) {
        return jdbc.sql("SELECT artifact_id,slot_key,revision_id,node_id,kind,spec::text AS spec,asset_id,state,error_code "
                        + "FROM app_learning.generation_media_slot WHERE artifact_id=:artifact AND revision_id=:revision "
                        + "ORDER BY created_at,slot_key").param("artifact", artifactId).param("revision", revisionId)
                .query(SLOT).list();
    }

    /**
     * Ends every PENDING or GENERATING slot of the session as FAILED with {@code errorCode} and returns them.
     */
    List<Slot> failOpenSlots(UUID sessionId, String errorCode) {
        return jdbc.sql("UPDATE app_learning.generation_media_slot SET state='FAILED',error_code=:code,updated_at=CURRENT_TIMESTAMP "
                        + "WHERE session_id=:id AND state IN ('PENDING','GENERATING') RETURNING artifact_id,slot_key,revision_id,node_id,"
                        + "kind,spec::text AS spec,asset_id,state,error_code")
                .param("code", errorCode).param("id", sessionId).query(SLOT).list();
    }

    /** Ends every PENDING or GENERATING slot of one artifact as FAILED with {@code errorCode} and returns them. */
    List<Slot> failOpenSlotsOf(UUID artifactId, String errorCode) {
        return jdbc.sql("UPDATE app_learning.generation_media_slot SET state='FAILED',error_code=:code,updated_at=CURRENT_TIMESTAMP "
                        + "WHERE artifact_id=:id AND state IN ('PENDING','GENERATING') RETURNING artifact_id,slot_key,revision_id,node_id,"
                        + "kind,spec::text AS spec,asset_id,state,error_code")
                .param("code", errorCode).param("id", artifactId).query(SLOT).list();
    }

    /** {@code {total, ready, failed}} of the slots of each artifact's current revision. */
    Map<UUID, int[]> slotCounts(Collection<UUID> artifacts) {
        Map<UUID, int[]> result = new HashMap<>();
        if (artifacts.isEmpty()) return result;
        jdbc.sql("SELECT m.artifact_id,count(*)::integer AS total,count(*) FILTER (WHERE m.state='READY')::integer AS ready,"
                        + "count(*) FILTER (WHERE m.state='FAILED')::integer AS failed FROM app_learning.generation_media_slot m "
                        + "JOIN app_learning.generation_artifact a ON a.artifact_id=m.artifact_id "
                        + "AND a.current_revision_id=m.revision_id WHERE m.artifact_id IN (:ids) GROUP BY m.artifact_id")
                .param("ids", artifacts).query((row, ignored) -> result.put(row.getObject("artifact_id", UUID.class),
                        new int[] {row.getInt("total"), row.getInt("ready"), row.getInt("failed")})).list();
        return result;
    }

    /**
     * A retried artifact is written again from scratch: the slots of its earlier revisions (their keys and pre-allocated
     * assets would collide with the new ones) and the media holds on their assets go.
     */
    void dropMedia(UUID artifactId) {
        jdbc.sql("DELETE FROM app_learning.generation_media_ref WHERE artifact_id=:id").param("id", artifactId).update();
        jdbc.sql("DELETE FROM app_learning.generation_media_slot WHERE artifact_id=:id").param("id", artifactId).update();
    }

    /** The media holds of an artifact that left the Workshop (published, handed off): the catalog or the draft holds now. */
    void releaseMediaHolds(UUID artifactId) {
        jdbc.sql("DELETE FROM app_learning.generation_media_ref WHERE artifact_id=:id").param("id", artifactId).update();
    }

    /** The turns of an artifact that is regenerated no longer apply. */
    void cancelTurns(UUID artifactId) {
        jdbc.sql("UPDATE app_learning.generation_artifact_turn SET status='CANCELLED' WHERE artifact_id=:id "
                + "AND status IN ('QUEUED','RUNNING')").param("id", artifactId).update();
    }

    /** Distinct model routes and prompt versions of the revisions of an artifact: the provenance of what is published. */
    Provenance provenance(UUID artifactId) {
        List<String> routes = jdbc.sql("SELECT DISTINCT model_route FROM app_learning.generation_artifact_revision "
                + "WHERE artifact_id=:id ORDER BY 1").param("id", artifactId).query(String.class).list();
        List<String> prompts = jdbc.sql("SELECT DISTINCT prompt_version FROM app_learning.generation_artifact_revision "
                + "WHERE artifact_id=:id ORDER BY 1").param("id", artifactId).query(String.class).list();
        return new Provenance(routes, prompts);
    }

    record Provenance(List<String> modelRoutes, List<String> promptVersions) { }

    /** Origin of a published artifact: audit and economics only, never returned by any API. */
    void insertProvenance(UUID owner, UUID sessionId, UUID artifactId, UUID revisionId, JsonNode publishedRef,
                          Provenance provenance) {
        jdbc.sql("INSERT INTO app_learning.generation_provenance(provenance_id,owner_id,session_id,artifact_id,revision_id,"
                        + "published_ref,model_routes,prompt_versions,created_at) VALUES (:id,:owner,:session,:artifact,:revision,"
                        + "CAST(:ref AS jsonb),CAST(:routes AS text[]),CAST(:prompts AS text[]),CURRENT_TIMESTAMP)")
                .param("id", UUID.randomUUID()).param("owner", owner).param("session", sessionId).param("artifact", artifactId)
                .param("revision", revisionId).param("ref", Json.write(publishedRef))
                .param("routes", arrayLiteral(provenance.modelRoutes())).param("prompts", arrayLiteral(provenance.promptVersions()))
                .update();
    }

    private static String arrayLiteral(List<String> values) {
        StringBuilder text = new StringBuilder("{");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) text.append(',');
            text.append('"').append(values.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return text.append('}').toString();
    }

    /** Deletes the session and, by cascade, its sources, artifacts, revisions, slots, holds, steps and events. */
    void deleteSession(UUID sessionId) {
        jdbc.sql("DELETE FROM app_learning.generation_session WHERE session_id=:id").param("id", sessionId).update();
    }

    // --------------------------------------------------------------------- events

    /** Events with {@code seq > after}, ascending, at most {@code limit}. */
    List<Event> events(UUID sessionId, long after, int limit) {
        return jdbc.sql("SELECT seq,type,artifact_id,payload::text AS payload,occurred_at FROM app_learning.generation_event "
                        + "WHERE session_id=:id AND seq>:after ORDER BY seq LIMIT :limit")
                .param("id", sessionId).param("after", after).param("limit", limit)
                .query((row, ignored) -> new Event(row.getLong("seq"), row.getString("type"),
                        row.getObject("artifact_id", UUID.class), Json.read(row.getString("payload")),
                        instant(row, "occurred_at"))).list();
    }

    // ------------------------------------------------------------------- read pins

    /** The notes of the owner's deck by id: {@code noteId -> rowVersion}. Other owners' and decks' notes are absent. */
    Map<UUID, Long> noteVersions(UUID owner, UUID deck, Collection<UUID> notes) {
        Map<UUID, Long> result = new HashMap<>();
        if (notes.isEmpty()) return result;
        jdbc.sql("SELECT note_id,row_version FROM app_learning.capture_note WHERE owner_id=:owner AND deck_id=:deck "
                        + "AND note_id IN (:ids)").param("owner", owner).param("deck", deck).param("ids", notes)
                .query((row, ignored) -> result.put(row.getObject("note_id", UUID.class), row.getLong("row_version"))).list();
        return result;
    }

    /** The material heads of the owner's deck: {@code memberKey -> head revision id}. */
    Map<UUID, UUID> headRevisions(UUID owner, UUID deck, Collection<UUID> members) {
        Map<UUID, UUID> result = new HashMap<>();
        if (members.isEmpty()) return result;
        jdbc.sql("SELECT h.member_key,h.revision_id FROM app_learning.deck_head_item h JOIN app_learning.deck d "
                        + "ON d.deck_id=h.deck_id WHERE d.owner_id=:owner AND d.deleted_at IS NULL AND h.deck_id=:deck "
                        + "AND h.member_key IN (:ids)").param("owner", owner).param("deck", deck).param("ids", members)
                .query((row, ignored) -> result.put(row.getObject("member_key", UUID.class), row.getObject("revision_id", UUID.class)))
                .list();
        return result;
    }

    /** Whether the material revision exists in the owner's deck (a head or a historical revision). */
    boolean itemRevisionExists(UUID owner, UUID deck, UUID member, UUID revision) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM app_learning.item_revision r JOIN app_learning.deck d ON d.deck_id=r.deck_id "
                        + "WHERE d.owner_id=:owner AND d.deleted_at IS NULL AND r.deck_id=:deck AND r.member_key=:member "
                        + "AND r.revision_id=:revision)").param("owner", owner).param("deck", deck).param("member", member)
                .param("revision", revision).query(Boolean.class).single();
    }

    /**
     * Copies the text of the pinned note into the session's snapshot ({@code V29}); the row version is part of the copy's
     * condition, so a note that moved since the pin was checked is not snapshotted. Idempotent for an existing snapshot.
     *
     * @return false when the note is not at {@code rowVersion} (or is not the owner's) and no snapshot exists
     */
    boolean snapshotNote(UUID sessionId, UUID owner, UUID noteId, long rowVersion) {
        jdbc.sql("INSERT INTO app_learning.generation_note_snapshot(session_id,owner_id,note_id,note_row_version,note_text) "
                        + "SELECT :session,:owner,n.note_id,n.row_version,n.note_text FROM app_learning.capture_note n "
                        + "WHERE n.note_id=:note AND n.owner_id=:owner AND n.row_version=:version ON CONFLICT DO NOTHING")
                .param("session", sessionId).param("owner", owner).param("note", noteId).param("version", rowVersion).update();
        return pinnedNoteText(sessionId, noteId, rowVersion).isPresent();
    }

    /** The text of a note at the row version a session pinned, or empty when no snapshot exists. */
    Optional<String> pinnedNoteText(UUID sessionId, UUID noteId, long rowVersion) {
        return jdbc.sql("SELECT note_text FROM app_learning.generation_note_snapshot WHERE session_id=:session AND note_id=:note "
                        + "AND note_row_version=:version").param("session", sessionId).param("note", noteId)
                .param("version", rowVersion).query(String.class).optional();
    }

    /** A note as it is now: version, archive flag and text. Deleted notes are absent from the result. */
    record NoteLook(long rowVersion, boolean archived, String text) { }

    Map<UUID, NoteLook> noteLooks(UUID owner, UUID deck, Collection<UUID> notes) {
        Map<UUID, NoteLook> result = new HashMap<>();
        if (notes.isEmpty()) return result;
        jdbc.sql("SELECT note_id,row_version,archived,note_text FROM app_learning.capture_note WHERE owner_id=:owner "
                        + "AND deck_id=:deck AND note_id IN (:ids)").param("owner", owner).param("deck", deck).param("ids", notes)
                .query((row, ignored) -> result.put(row.getObject("note_id", UUID.class),
                        new NoteLook(row.getLong("row_version"), row.getBoolean("archived"), row.getString("note_text")))).list();
        return result;
    }
}
