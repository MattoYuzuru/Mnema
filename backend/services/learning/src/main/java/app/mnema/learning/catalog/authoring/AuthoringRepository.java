package app.mnema.learning.catalog.authoring;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class AuthoringRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    AuthoringRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    Instant now() {
        return jdbc.sql("SELECT statement_timestamp()").query(Timestamp.class).single().toInstant();
    }

    boolean ownsDeck(UUID actor, UUID deck) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM app_learning.deck WHERE owner_id=:actor AND deck_id=:deck)")
                .param("actor", actor).param("deck", deck).query(Boolean.class).single();
    }

    boolean ownsBase(UUID actor, UUID deck, UUID member, UUID revision) {
        return jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM app_learning.item_revision r
                    WHERE r.owner_id=:actor AND r.deck_id=:deck AND r.member_key=:member AND r.revision_id=:revision)
                """).param("actor", actor).param("deck", deck).param("member", member).param("revision", revision)
                .query(Boolean.class).single();
    }

    void lockOwner(UUID actor) {
        long key = actor.getMostSignificantBits() ^ actor.getLeastSignificantBits() ^ 0x44524146544c494dL;
        jdbc.sql("SELECT pg_advisory_xact_lock(:key)").param("key", key).query((row, number) -> number).single();
    }

    long activeDraftCount(UUID actor) {
        return jdbc.sql("SELECT count(*) FROM app_learning.editing_draft WHERE owner_id=:actor "
                        + "AND expires_at>statement_timestamp()")
                .param("actor", actor).query(Long.class).single();
    }

    int deleteExpiredDrafts(UUID actor) {
        return jdbc.sql("DELETE FROM app_learning.editing_draft WHERE owner_id=:actor "
                        + "AND expires_at<=statement_timestamp()")
                .param("actor", actor).update();
    }

    long activeDraftBytes(UUID actor, UUID except) {
        if (except == null) {
            return jdbc.sql("SELECT COALESCE(sum(content_bytes),0) FROM app_learning.editing_draft "
                            + "WHERE owner_id=:actor AND expires_at>statement_timestamp()")
                    .param("actor", actor).query(Long.class).single();
        }
        return jdbc.sql("""
                SELECT COALESCE(sum(content_bytes),0) FROM app_learning.editing_draft
                 WHERE owner_id=:actor AND expires_at>statement_timestamp()
                   AND draft_id<>:except
                """).param("actor", actor).param("except", except)
                .query(Long.class).single();
    }

    int storedDocumentBytes(JsonNode document) {
        return jdbc.sql("SELECT octet_length(CAST(:document AS jsonb)::text)")
                .param("document", write(document)).query(Integer.class).single();
    }

    void insertDraft(UUID id, UUID actor, AuthoringCommands.DraftCreate command, Instant time) {
        jdbc.sql("""
                INSERT INTO app_learning.editing_draft(draft_id,owner_id,deck_id,member_key,base_revision_id,
                    row_version,document,created_at,acknowledged_at,expires_at)
                VALUES (:id,:actor,:deck,:member,:base,0,CAST(:document AS jsonb),:time,:time,
                    CAST(:time AS timestamptz) + interval '30 days')
                """).param("id", id).param("actor", actor).param("deck", command.deckId())
                .param("member", command.memberKey(), java.sql.Types.OTHER)
                .param("base", command.baseRevisionId(), java.sql.Types.OTHER)
                .param("document", write(command.document().toJson())).param("time", Timestamp.from(time)).update();
    }

    int updateDraft(UUID actor, UUID id, long expected, JsonNode document, Instant time) {
        return jdbc.sql("""
                UPDATE app_learning.editing_draft SET row_version=row_version+1,document=CAST(:document AS jsonb),
                    acknowledged_at=:time,expires_at=CAST(:time AS timestamptz) + interval '30 days'
                 WHERE owner_id=:actor AND draft_id=:id AND row_version=:expected
                   AND expires_at>statement_timestamp()
                """).param("document", write(document)).param("time", Timestamp.from(time)).param("actor", actor)
                .param("id", id).param("expected", expected).update();
    }

    int deleteDraft(UUID actor, UUID id, long expected) {
        return jdbc.sql("DELETE FROM app_learning.editing_draft WHERE owner_id=:actor AND draft_id=:id "
                        + "AND row_version=:expected AND expires_at>statement_timestamp()")
                .param("actor", actor).param("id", id).param("expected", expected).update();
    }

    Optional<DraftRecord> draft(UUID actor, UUID id) {
        return jdbc.sql("""
                SELECT draft_id,owner_id,deck_id,member_key,base_revision_id,row_version,document::text,content_bytes,
                       created_at,acknowledged_at,expires_at
                  FROM app_learning.editing_draft
                 WHERE owner_id=:actor AND draft_id=:id AND expires_at>statement_timestamp()
                """).param("actor", actor).param("id", id).query(this::draft).optional();
    }

    List<DraftRecord> drafts(UUID actor, AuthoringCursor cursor, int limit) {
        Instant time = cursor == null ? Instant.parse("9999-12-31T23:59:59.999999Z") : cursor.createdAt();
        UUID id = cursor == null ? new UUID(-1L, -1L) : cursor.id();
        return jdbc.sql("""
                SELECT draft_id,owner_id,deck_id,member_key,base_revision_id,row_version,document::text,content_bytes,
                       created_at,acknowledged_at,expires_at
                  FROM app_learning.editing_draft
                 WHERE owner_id=:actor AND expires_at>statement_timestamp()
                   AND (created_at<:time OR (created_at=:time AND draft_id<:id))
                 ORDER BY created_at DESC,draft_id DESC LIMIT :limit
                """).param("actor", actor).param("time", Timestamp.from(time)).param("id", id)
                .param("limit", limit + 1).query(this::draft).list();
    }

    long captureCount(UUID actor) {
        return jdbc.sql("SELECT count(*) FROM app_learning.capture_note WHERE owner_id=:actor")
                .param("actor", actor).query(Long.class).single();
    }

    long captureBytes(UUID actor, UUID except) {
        if (except == null) {
            return jdbc.sql("SELECT COALESCE(sum(content_bytes),0) FROM app_learning.capture_note WHERE owner_id=:actor")
                    .param("actor", actor).query(Long.class).single();
        }
        return jdbc.sql("SELECT COALESCE(sum(content_bytes),0) FROM app_learning.capture_note "
                        + "WHERE owner_id=:actor AND note_id<>:except")
                .param("actor", actor).param("except", except).query(Long.class).single();
    }

    void insertCapture(UUID id, UUID actor, AuthoringCommands.CaptureCreate command, Instant time) {
        jdbc.sql("""
                INSERT INTO app_learning.capture_note(note_id,owner_id,deck_id,row_version,source,note_text,
                    archived,created_at,updated_at) VALUES (:id,:actor,:deck,0,:source,:text,false,:time,:time)
                """).param("id", id).param("actor", actor).param("deck", command.deckId())
                .param("source", command.source()).param("text", command.text())
                .param("time", Timestamp.from(time)).update();
    }

    int updateCapture(UUID actor, UUID id, long expected, String source, String text, Instant time) {
        return jdbc.sql("""
                UPDATE app_learning.capture_note SET row_version=row_version+1,source=:source,note_text=:text,
                    updated_at=:time WHERE owner_id=:actor AND note_id=:id AND row_version=:expected
                    AND conversion_command_id IS NULL
                """).param("source", source).param("text", text).param("time", Timestamp.from(time))
                .param("actor", actor).param("id", id).param("expected", expected).update();
    }

    int archiveCapture(UUID actor, UUID id, long expected, boolean archived, Instant time) {
        return jdbc.sql("""
                UPDATE app_learning.capture_note SET row_version=row_version+1,archived=:archived,updated_at=:time
                 WHERE owner_id=:actor AND note_id=:id AND row_version=:expected
                """).param("archived", archived).param("time", Timestamp.from(time)).param("actor", actor)
                .param("id", id).param("expected", expected).update();
    }

    int deleteCapture(UUID actor, UUID id, long expected) {
        return jdbc.sql("DELETE FROM app_learning.capture_note WHERE owner_id=:actor AND note_id=:id AND row_version=:expected")
                .param("actor", actor).param("id", id).param("expected", expected).update();
    }

    Optional<CaptureRecord> capture(UUID actor, UUID id) {
        return capture(actor, id, false);
    }

    Optional<CaptureRecord> lockedCapture(UUID actor, UUID id) {
        return capture(actor, id, true);
    }

    private Optional<CaptureRecord> capture(UUID actor, UUID id, boolean locked) {
        String lock = locked ? " FOR UPDATE" : "";
        return jdbc.sql("""
                SELECT note_id,owner_id,deck_id,row_version,source,note_text,content_bytes,archived,created_at,updated_at,
                       conversion_command_id,conversion_payload_hash,converted_member_key,converted_revision_id,
                       converted_at,conversion_result::text
                  FROM app_learning.capture_note WHERE owner_id=:actor AND note_id=:id
                """ + lock).param("actor", actor).param("id", id).query(this::capture).optional();
    }

    List<CaptureRecord> captures(UUID actor, AuthoringCursor cursor, int limit) {
        Instant time = cursor == null ? Instant.parse("9999-12-31T23:59:59.999999Z") : cursor.createdAt();
        UUID id = cursor == null ? new UUID(-1L, -1L) : cursor.id();
        return jdbc.sql("""
                SELECT note_id,owner_id,deck_id,row_version,source,note_text,content_bytes,archived,created_at,updated_at,
                       conversion_command_id,conversion_payload_hash,converted_member_key,converted_revision_id,
                       converted_at,conversion_result::text
                  FROM app_learning.capture_note WHERE owner_id=:actor
                   AND (created_at<:time OR (created_at=:time AND note_id<:id))
                 ORDER BY created_at DESC,note_id DESC LIMIT :limit
                """).param("actor", actor).param("time", Timestamp.from(time)).param("id", id)
                .param("limit", limit + 1).query(this::capture).list();
    }

    int convertCapture(UUID actor, UUID id, long expected, UUID command, byte[] hash,
                       UUID member, UUID revision, JsonNode result, Instant time) {
        return jdbc.sql("""
                UPDATE app_learning.capture_note SET row_version=row_version+1,updated_at=:time,
                    conversion_command_id=:command,conversion_payload_hash=:hash,converted_member_key=:member,
                    converted_revision_id=:revision,converted_at=:time,conversion_result=CAST(:result AS jsonb)
                 WHERE owner_id=:actor AND note_id=:id AND row_version=:expected AND conversion_command_id IS NULL
                """).param("time", Timestamp.from(time)).param("command", command).param("hash", hash)
                .param("member", member).param("revision", revision).param("result", write(result))
                .param("actor", actor).param("id", id).param("expected", expected).update();
    }

    private DraftRecord draft(ResultSet row, int ignored) throws SQLException {
        return new DraftRecord(row.getObject("draft_id", UUID.class), row.getObject("owner_id", UUID.class),
                row.getObject("deck_id", UUID.class), row.getObject("member_key", UUID.class),
                row.getObject("base_revision_id", UUID.class), row.getLong("row_version"), read(row.getString("document")),
                row.getInt("content_bytes"), row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("acknowledged_at").toInstant(), row.getTimestamp("expires_at").toInstant());
    }

    private CaptureRecord capture(ResultSet row, int ignored) throws SQLException {
        String result = row.getString("conversion_result");
        Timestamp converted = row.getTimestamp("converted_at");
        return new CaptureRecord(row.getObject("note_id", UUID.class), row.getObject("owner_id", UUID.class),
                row.getObject("deck_id", UUID.class), row.getLong("row_version"), row.getString("source"),
                row.getString("note_text"), row.getInt("content_bytes"), row.getBoolean("archived"),
                row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant(),
                row.getObject("conversion_command_id", UUID.class), row.getBytes("conversion_payload_hash"),
                row.getObject("converted_member_key", UUID.class), row.getObject("converted_revision_id", UUID.class),
                converted == null ? null : converted.toInstant(), result == null ? null : read(result));
    }

    private String write(JsonNode value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalArgumentException("JSON cannot be stored", exception); }
    }

    private JsonNode read(String value) {
        try { return json.readTree(value); }
        catch (JsonProcessingException exception) { throw new DataRetrievalFailureException("Stored JSON is invalid", exception); }
    }
}
