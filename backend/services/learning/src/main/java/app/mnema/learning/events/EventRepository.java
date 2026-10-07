package app.mnema.learning.events;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class EventRepository {
    private static final String COLUMNS = "event_id,title,body_markdown,event_date,published,published_at,row_version,created_at,updated_at";
    private static final RowMapper<EventRecord> ROW = (row, number) -> new EventRecord(
            row.getObject("event_id", UUID.class), row.getString("title"), row.getString("body_markdown"),
            row.getObject("event_date", LocalDate.class), row.getBoolean("published"),
            row.getTimestamp("published_at") == null ? null : row.getTimestamp("published_at").toInstant(),
            row.getLong("row_version"), row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant());
    private final JdbcClient jdbc;

    EventRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    List<EventRecord> page(boolean publicOnly, EventRequests.Cursor cursor) {
        String predicate = publicOnly ? " WHERE published" : " WHERE TRUE";
        if (cursor != null) predicate += " AND (event_date,event_id) < (:date,:id)";
        var query = jdbc.sql("SELECT " + COLUMNS + " FROM app_learning.product_event" + predicate
                + " ORDER BY event_date DESC,event_id DESC LIMIT 51");
        if (cursor != null) query.param("date", cursor.date()).param("id", cursor.eventId());
        return query.query(ROW).list();
    }

    Optional<EventRecord> find(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM app_learning.product_event WHERE event_id=:id")
                .param("id", id).query(ROW).optional();
    }

    EventRecord create(UUID id, EventRequests.Command command) {
        return values(jdbc.sql("""
                INSERT INTO app_learning.product_event(event_id,title,body_markdown,event_date,published,published_at,created_at,updated_at)
                SELECT :id,:title,:body,:date,:published,CASE WHEN :published THEN now ELSE NULL END,now,now
                FROM (SELECT clock_timestamp() AS now) AS time
                RETURNING
                """ + COLUMNS), id, command).query(ROW).single();
    }

    int replace(UUID id, long version, EventRequests.Command command) {
        return values(jdbc.sql("""
                UPDATE app_learning.product_event
                SET title=:title,body_markdown=:body,event_date=:date,published=:published,
                    published_at=CASE WHEN :published THEN COALESCE(published_at,clock_timestamp()) ELSE published_at END,
                    row_version=row_version+1,updated_at=GREATEST(updated_at,clock_timestamp())
                WHERE event_id=:id AND row_version=:version
                """), id, command).param("version", version).update();
    }

    int delete(UUID id, long version) {
        return jdbc.sql("DELETE FROM app_learning.product_event WHERE event_id=:id AND row_version=:version")
                .param("id", id).param("version", version).update();
    }

    private static JdbcClient.StatementSpec values(JdbcClient.StatementSpec query, UUID id, EventRequests.Command command) {
        return query.param("id", id).param("title", command.title()).param("body", command.bodyMarkdown())
                .param("date", command.eventDate()).param("published", command.published());
    }
}
