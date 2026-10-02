package app.mnema.learning.notification;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SQL of the notification center. Every method is owner-scoped; none opens a transaction, the callers do.
 * Visible means not dismissed and not expired; time is the database clock, as elsewhere in Learning.
 */
@Repository
class NotificationRepository {
    private static final String VISIBLE = "n.owner_id=:owner AND n.dismissed_at IS NULL AND n.expires_at>CURRENT_TIMESTAMP";
    private static final String COLUMNS = "n.notification_id,n.seq,n.kind,n.severity,n.params::text AS params,"
            + "n.route,n.created_at,n.expires_at";

    private final JdbcClient jdbc;

    NotificationRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    // ------------------------------------------------------------------ publish

    /**
     * Creates the owner's cursor row if needed and locks it until the transaction ends: the lock serializes every
     * publisher of one owner, which is what makes {@code seq} contiguous and commit-ordered.
     */
    void lockCursor(UUID owner) {
        jdbc.sql("INSERT INTO app_learning.notification_cursor(owner_id) VALUES (:owner) ON CONFLICT (owner_id) DO NOTHING")
                .param("owner", owner).update();
        jdbc.sql("SELECT last_seq FROM app_learning.notification_cursor WHERE owner_id=:owner FOR UPDATE")
                .param("owner", owner).query(Long.class).single();
    }

    /** True when a live notification already uses the key. An expired one no longer does, and is dropped here. */
    boolean dedupeKeyTaken(UUID owner, String key) {
        jdbc.sql("DELETE FROM app_learning.notification WHERE owner_id=:owner AND dedupe_key=:key "
                        + "AND expires_at<=CURRENT_TIMESTAMP")
                .param("owner", owner).param("key", key).update();
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM app_learning.notification WHERE owner_id=:owner AND dedupe_key=:key)")
                .param("owner", owner).param("key", key).query(Boolean.class).single();
    }

    /** Must run under {@link #lockCursor}. */
    long allocateSeq(UUID owner) {
        return jdbc.sql("UPDATE app_learning.notification_cursor SET last_seq=last_seq+1 WHERE owner_id=:owner "
                        + "RETURNING last_seq")
                .param("owner", owner).query(Long.class).single();
    }

    void insert(UUID owner, long seq, NotificationKind kind, String key, String paramsJson, NotificationRoute route,
                long retentionSeconds) {
        jdbc.sql("INSERT INTO app_learning.notification(notification_id,owner_id,seq,kind,severity,params,route,"
                        + "dedupe_key,created_at,expires_at) VALUES (:id,:owner,:seq,:kind,:severity,"
                        + "CAST(:params AS jsonb),:route,:key,CURRENT_TIMESTAMP,"
                        + "CURRENT_TIMESTAMP + (:retention * interval '1 second'))")
                .param("id", UUID.randomUUID()).param("owner", owner).param("seq", seq).param("kind", kind.name())
                .param("severity", kind.severity().name()).param("params", paramsJson).param("route", route.name())
                .param("key", key).param("retention", retentionSeconds).update();
    }

    /** Keeps at most {@code max} sequence slots: the 201st notification evicts the oldest. Must run under the lock. */
    void evictBeyond(UUID owner, long newestSeq, int max) {
        if (newestSeq <= max) return;
        jdbc.sql("DELETE FROM app_learning.notification WHERE owner_id=:owner AND seq<=:cutoff")
                .param("owner", owner).param("cutoff", newestSeq - max).update();
    }

    // --------------------------------------------------------------------- read

    /** One aggregate row (always present) that is the whole input of the list validator. */
    Stats stats(UUID owner) {
        return jdbc.sql("SELECT COALESCE(c.last_seq,0) AS latest,COALESCE(c.read_upto,0) AS read_upto,"
                        + "COUNT(n.seq) AS visible,COUNT(n.seq) FILTER (WHERE n.seq>COALESCE(c.read_upto,0)) AS unread,"
                        + "MIN(n.expires_at) AS earliest FROM (SELECT CAST(:owner AS uuid) AS owner_id) o "
                        + "LEFT JOIN app_learning.notification_cursor c ON c.owner_id=o.owner_id "
                        + "LEFT JOIN app_learning.notification n ON n.owner_id=o.owner_id AND n.dismissed_at IS NULL "
                        + "AND n.expires_at>CURRENT_TIMESTAMP GROUP BY c.last_seq,c.read_upto")
                .param("owner", owner)
                .query((row, ignored) -> new Stats(row.getLong("latest"), row.getLong("read_upto"),
                        row.getLong("visible"), row.getLong("unread"),
                        row.getTimestamp("earliest") == null ? null : row.getTimestamp("earliest").toInstant()))
                .single();
    }

    /** Newest first: visible notifications with {@code seq < before}, at most {@code limit}. */
    List<Row> newestBefore(UUID owner, long before, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM app_learning.notification n WHERE " + VISIBLE
                        + " AND n.seq<:before ORDER BY n.seq DESC LIMIT :limit")
                .param("owner", owner).param("before", before).param("limit", limit).query(NotificationRepository::row).list();
    }

    /** Catch-up: visible notifications with {@code seq > after}, oldest first, at most {@code limit}. */
    List<Row> oldestAfter(UUID owner, long after, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM app_learning.notification n WHERE " + VISIBLE
                        + " AND n.seq>:after ORDER BY n.seq ASC LIMIT :limit")
                .param("owner", owner).param("after", after).param("limit", limit).query(NotificationRepository::row).list();
    }

    private static Row row(java.sql.ResultSet row, int ignored) throws java.sql.SQLException {
        return new Row((UUID) row.getObject("notification_id"), row.getLong("seq"), row.getString("kind"),
                row.getString("severity"), row.getString("params"), row.getString("route"),
                row.getTimestamp("created_at").toInstant(), row.getTimestamp("expires_at").toInstant());
    }

    // ------------------------------------------------------------------ commands

    /**
     * Sets {@code dismissed_at} once. A repeat finds the row dismissed and changes nothing; an expired, evicted,
     * absent or foreign notification matches nothing.
     *
     * @return whether the notification exists for the owner and is (now) dismissed
     */
    boolean dismiss(UUID owner, UUID notification) {
        return jdbc.sql("UPDATE app_learning.notification SET dismissed_at=COALESCE(dismissed_at,CURRENT_TIMESTAMP) "
                        + "WHERE notification_id=:id AND owner_id=:owner AND (dismissed_at IS NOT NULL OR expires_at>CURRENT_TIMESTAMP)")
                .param("id", notification).param("owner", owner).update() == 1;
    }

    /** The owner's cursor row locked for update, if the owner ever received a notification. */
    Optional<CursorRow> lockedCursor(UUID owner) {
        return jdbc.sql("SELECT last_seq,read_upto FROM app_learning.notification_cursor WHERE owner_id=:owner FOR UPDATE")
                .param("owner", owner)
                .query((row, ignored) -> new CursorRow(row.getLong("last_seq"), row.getLong("read_upto"))).optional();
    }

    /** Monotonic maximum; the caller has validated {@code readUpto <= last_seq}. */
    void raiseReadUpto(UUID owner, long readUpto) {
        jdbc.sql("UPDATE app_learning.notification_cursor SET read_upto=GREATEST(read_upto,:readUpto) WHERE owner_id=:owner")
                .param("owner", owner).param("readUpto", readUpto).update();
    }

    // ----------------------------------------------------------------- retention

    /** Deletes up to {@code limit} expired notifications; rows locked by a concurrent worker are left to it. */
    int purgeExpired(int limit) {
        return jdbc.sql("DELETE FROM app_learning.notification WHERE notification_id IN ("
                        + "SELECT candidate.notification_id FROM app_learning.notification candidate "
                        + "WHERE candidate.expires_at<=CURRENT_TIMESTAMP ORDER BY candidate.expires_at,candidate.notification_id "
                        + "LIMIT :limit FOR UPDATE SKIP LOCKED)")
                .param("limit", limit).update();
    }

    record Stats(long latestSeq, long readUpto, long visible, long unread, Instant earliestExpiry) { }
    record CursorRow(long lastSeq, long readUpto) { }
    record Row(UUID id, long seq, String kind, String severity, String params, String route, Instant createdAt,
               Instant expiresAt) { }
}
