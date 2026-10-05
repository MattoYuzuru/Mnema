package app.mnema.learning.speech;

import app.mnema.learning.ai.Transcription;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The SQL of speech inputs: rows, the audio beside them, the rate-limit uses and the queue. Every statement is one short unit; none holds a provider call. */
@Repository
class SpeechInputRepository {
    /** A stored input as the client sees it ({@code text} only when DONE). */
    record Row(UUID id, UUID ownerId, Transcription.Purpose purpose, String state, String text, Integer seconds, String lang, boolean garbled,
               String errorCode, Instant expiresAt, byte[] bodyHash) { }

    /** What the worker needs of a claimed input; the audio is read separately, only by the worker that holds the claim. */
    record Claim(UUID id, UUID ownerId, UUID token, Transcription.Purpose purpose, String langHint, UUID deckId, String mimeType, int declaredMs,
                 Instant deadlineAt) { }

    /** The audio of a claimed input and the script of a harness (only ever present with the Stub). */
    record Audio(byte[] bytes, String script) { }

    private static final String COLUMNS = "speech_input_id,owner_id,purpose,state,text,seconds,lang,garbled,error_code,expires_at,body_hash";

    private final JdbcClient jdbc;

    SpeechInputRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    // ------------------------------------------------------------------ admission (inside the caller's transaction)

    /** Serialises the admissions of one account until the transaction ends: the rate limit and the in-flight seconds are read under it. */
    void lock(UUID owner) {
        jdbc.sql("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended('speech.input:' || :owner, 0))) lock")
                .param("owner", owner.toString()).query(Integer.class).single();
    }

    Optional<Row> byKey(UUID owner, UUID key) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM app_learning.speech_input WHERE owner_id=:owner AND idempotency_key=:key")
                .param("owner", owner).param("key", key).query((row, ignored) -> row(row)).optional();
    }

    /**
     * The seconds of the account's uses of the last {@code windowSeconds}, or null when there is room: then it is the seconds until the oldest use
     * leaves the window (the {@code Retry-After}).
     */
    Long waitIfFull(UUID owner, long windowSeconds, int limit) {
        jdbc.sql("DELETE FROM app_learning.speech_input_use WHERE owner_id=:owner AND used_at < CURRENT_TIMESTAMP - interval '1 day'")
                .param("owner", owner).update();
        long used = jdbc.sql("SELECT count(*) FROM app_learning.speech_input_use WHERE owner_id=:owner "
                + "AND used_at > CURRENT_TIMESTAMP - (:seconds * interval '1 second')").param("owner", owner).param("seconds", windowSeconds)
                .query(Long.class).single();
        if (used < limit) return null;
        return jdbc.sql("SELECT GREATEST(1, CEIL(EXTRACT(EPOCH FROM (min(used_at) + (:seconds * interval '1 second') - CURRENT_TIMESTAMP))))::bigint "
                + "FROM app_learning.speech_input_use WHERE owner_id=:owner AND used_at > CURRENT_TIMESTAMP - (:seconds * interval '1 second')")
                .param("owner", owner).param("seconds", windowSeconds).query(Long.class).single();
    }

    /** The seconds (each clip rounded up) the account has admitted and not counted yet: its queued and running inputs. */
    long openSeconds(UUID owner) {
        return jdbc.sql("SELECT COALESCE(sum(CEIL(declared_ms / 1000.0)), 0)::bigint FROM app_learning.speech_input "
                + "WHERE owner_id=:owner AND state IN ('QUEUED','TRANSCRIBING')").param("owner", owner).query(Long.class).single();
    }

    /** Inserts the input (QUEUED), its audio and the use of the rate limit; returns the expiry. */
    Instant insert(UUID id, UUID owner, UUID key, byte[] bodyHash, Transcription.Purpose purpose, String lang, UUID deck, String mime, int declaredMs,
                   byte[] audio, String script, java.time.Duration deadline, java.time.Duration ttl) {
        Timestamp expires = jdbc.sql("INSERT INTO app_learning.speech_input(speech_input_id,owner_id,purpose,state,lang_hint,deck_id,mime_type,byte_length,"
                        + "declared_ms,idempotency_key,body_hash,created_at,deadline_at,expires_at) VALUES (:id,:owner,:purpose,'QUEUED',:lang,:deck,:mime,"
                        + ":bytes,:declared,:key,:hash,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP + (:deadline * interval '1 millisecond'),"
                        + "CURRENT_TIMESTAMP + (:ttl * interval '1 millisecond')) RETURNING expires_at")
                .param("id", id).param("owner", owner).param("purpose", purpose.name()).param("lang", lang).param("deck", deck).param("mime", mime)
                .param("bytes", audio.length).param("declared", declaredMs).param("key", key).param("hash", bodyHash)
                .param("deadline", deadline.toMillis()).param("ttl", ttl.toMillis()).query(Timestamp.class).single();
        jdbc.sql("INSERT INTO app_learning.speech_input_audio(speech_input_id,audio,script) VALUES (:id,:audio,:script)")
                .param("id", id).param("audio", audio).param("script", script).update();
        jdbc.sql("INSERT INTO app_learning.speech_input_use(owner_id,used_at) VALUES (:owner,CURRENT_TIMESTAMP)").param("owner", owner).update();
        return expires.toInstant();
    }

    boolean ownsDeck(UUID owner, UUID deck) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM app_learning.deck WHERE deck_id=:deck AND owner_id=:owner AND deleted_at IS NULL)")
                .param("deck", deck).param("owner", owner).query(Boolean.class).single();
    }

    // ------------------------------------------------------------------ reads and deletes

    /** The owner's live (unexpired) input; another owner's, an expired and an absent one are the same empty answer. */
    Optional<Row> find(UUID owner, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM app_learning.speech_input WHERE speech_input_id=:id AND owner_id=:owner AND expires_at > CURRENT_TIMESTAMP")
                .param("id", id).param("owner", owner).query((row, ignored) -> row(row)).optional();
    }

    /** Deletes the input and (by cascade) its audio; true when there was one. */
    boolean delete(UUID owner, UUID id) {
        return jdbc.sql("DELETE FROM app_learning.speech_input WHERE speech_input_id=:id AND owner_id=:owner").param("id", id).param("owner", owner).update() > 0;
    }

    // ------------------------------------------------------------------ the worker

    /** Claims the oldest QUEUED input that is not overdue (another instance's claim is skipped, never waited for). */
    Optional<Claim> claim(UUID token) {
        return jdbc.sql("UPDATE app_learning.speech_input SET state='TRANSCRIBING', claim_token=:token WHERE speech_input_id = ("
                        + "SELECT speech_input_id FROM app_learning.speech_input WHERE state='QUEUED' AND deadline_at > CURRENT_TIMESTAMP "
                        + "ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1) "
                        + "RETURNING speech_input_id,owner_id,purpose,lang_hint,deck_id,mime_type,declared_ms,deadline_at")
                .param("token", token).query((row, ignored) -> new Claim(row.getObject("speech_input_id", UUID.class), row.getObject("owner_id", UUID.class),
                        token, Transcription.Purpose.valueOf(row.getString("purpose")), row.getString("lang_hint"), row.getObject("deck_id", UUID.class),
                        row.getString("mime_type"), row.getInt("declared_ms"), row.getTimestamp("deadline_at").toInstant())).optional();
    }

    Optional<Audio> audio(UUID id) {
        return jdbc.sql("SELECT audio,script FROM app_learning.speech_input_audio WHERE speech_input_id=:id").param("id", id)
                .query((row, ignored) -> new Audio(row.getBytes("audio"), row.getString("script"))).optional();
    }

    /** Settles a claimed input as DONE; false when the claim is gone (the learner deleted the input, or the sweeper failed it as overdue). */
    boolean done(Claim claim, String text, int seconds, String lang, boolean garbled) {
        boolean written = jdbc.sql("UPDATE app_learning.speech_input SET state='DONE', text=:text, seconds=:seconds, lang=:lang, garbled=:garbled, claim_token=NULL "
                        + "WHERE speech_input_id=:id AND claim_token=:token AND state='TRANSCRIBING'")
                .param("text", text).param("seconds", seconds).param("lang", lang).param("garbled", garbled).param("id", claim.id()).param("token", claim.token())
                .update() > 0;
        if (written) dropAudio(claim.id());
        return written;
    }

    /** Settles a claimed input as FAILED with {@code errorCode}; false when the claim is gone. */
    boolean failed(Claim claim, String errorCode) {
        boolean written = jdbc.sql("UPDATE app_learning.speech_input SET state='FAILED', error_code=:code, claim_token=NULL "
                        + "WHERE speech_input_id=:id AND claim_token=:token AND state='TRANSCRIBING'")
                .param("code", errorCode).param("id", claim.id()).param("token", claim.token()).update() > 0;
        if (written) dropAudio(claim.id());
        return written;
    }

    private void dropAudio(UUID id) {
        jdbc.sql("DELETE FROM app_learning.speech_input_audio WHERE speech_input_id=:id").param("id", id).update();
    }

    // ------------------------------------------------------------------ the sweeper

    /** Fails every input that is not terminal after its deadline (a crashed worker, a provider that never answered): UNAVAILABLE. Returns their ids. */
    List<UUID> failOverdue() {
        return jdbc.sql("UPDATE app_learning.speech_input SET state='FAILED', error_code='UNAVAILABLE', claim_token=NULL "
                        + "WHERE state IN ('QUEUED','TRANSCRIBING') AND deadline_at < CURRENT_TIMESTAMP RETURNING speech_input_id")
                .query(UUID.class).list();
    }

    /** The safety net of the audio rule: no recording outlives the end of its transcription. Returns how many rows it removed. */
    int dropFinishedAudio() {
        return jdbc.sql("DELETE FROM app_learning.speech_input_audio a USING app_learning.speech_input s "
                + "WHERE a.speech_input_id=s.speech_input_id AND s.state IN ('DONE','FAILED')").update();
    }

    /** One bounded retention batch: rows past their expiry (with their audio, by cascade) and the rate-limit uses older than two hours. */
    int purge(int batch) {
        int rows = jdbc.sql("DELETE FROM app_learning.speech_input WHERE speech_input_id IN (SELECT speech_input_id FROM app_learning.speech_input "
                + "WHERE expires_at < CURRENT_TIMESTAMP ORDER BY expires_at LIMIT :batch)").param("batch", batch).update();
        jdbc.sql("DELETE FROM app_learning.speech_input_use WHERE used_at < CURRENT_TIMESTAMP - interval '2 hours'").update();
        return rows;
    }

    // ------------------------------------------------------------------ deck terms

    /** A current material of a deck: its identity, where its content is stored and its cached title (null until a title was ever shown). */
    record Head(UUID member, UUID revision, UUID scope, UUID root, String title) { }

    /** The current materials of the owner's deck, in a stable order; empty for a deck that is not the owner's. */
    List<Head> deckHeads(UUID owner, UUID deck, int limit) {
        return jdbc.sql("SELECT h.member_key,h.revision_id,r.reuse_scope_id,r.content_root_id,p.title FROM app_learning.deck d "
                        + "JOIN app_learning.deck_head_item h ON h.deck_id=d.deck_id "
                        + "JOIN app_learning.item_revision r ON r.deck_id=h.deck_id AND r.member_key=h.member_key AND r.revision_id=h.revision_id "
                        + "LEFT JOIN app_learning.item_preview p ON p.deck_id=h.deck_id AND p.member_key=h.member_key AND p.revision_id=h.revision_id "
                        + "WHERE d.deck_id=:deck AND d.owner_id=:owner AND d.deleted_at IS NULL ORDER BY h.member_key LIMIT :limit")
                .param("deck", deck).param("owner", owner).param("limit", limit)
                .query((row, ignored) -> new Head(row.getObject("member_key", UUID.class), row.getObject("revision_id", UUID.class),
                        row.getObject("reuse_scope_id", UUID.class), row.getObject("content_root_id", UUID.class), row.getString("title"))).list();
    }

    private static Row row(java.sql.ResultSet row) throws java.sql.SQLException {
        int seconds = row.getInt("seconds");
        Integer metered = row.wasNull() ? null : seconds;
        return new Row(row.getObject("speech_input_id", UUID.class), row.getObject("owner_id", UUID.class),
                Transcription.Purpose.valueOf(row.getString("purpose")), row.getString("state"), row.getString("text"), metered, row.getString("lang"),
                row.getBoolean("garbled"), row.getString("error_code"), row.getTimestamp("expires_at").toInstant(), row.getBytes("body_hash"));
    }
}
