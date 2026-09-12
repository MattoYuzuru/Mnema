package app.mnema.learning.catalog.deck;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class DeckRepository {
    private static final String CURRENT = """
            SELECT d.deck_id, d.head_revision_id, d.reuse_scope_id, d.row_version,
                   r.title, r.description, d.created_at, r.created_at AS updated_at,
                   r.members_root_id, r.exercises_root_id, r.member_count, r.exercise_count
              FROM app_learning.deck d JOIN app_learning.deck_revision r
                ON r.deck_id = d.deck_id AND r.revision_id = d.head_revision_id
             WHERE d.owner_id = :actor
            """;
    private static final RowMapper<DeckRecord> ROW = (row, number) -> new DeckRecord(
            row.getObject("deck_id", UUID.class), row.getObject("head_revision_id", UUID.class),
            row.getObject("reuse_scope_id", UUID.class), row.getLong("row_version"),
            row.getString("title"), row.getString("description"), row.getTimestamp("created_at").toInstant(),
            row.getTimestamp("updated_at").toInstant(), row.getObject("members_root_id", UUID.class),
            row.getObject("exercises_root_id", UUID.class), row.getInt("member_count"), row.getInt("exercise_count"));
    private final JdbcClient jdbc;

    DeckRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    Optional<DeckRecord> find(UUID actor, UUID deck) {
        return jdbc.sql(CURRENT + " AND d.deck_id = :deck").param("actor", actor).param("deck", deck).query(ROW).optional();
    }

    List<DeckRecord> page(UUID actor, DeckCursor cursor, int limit) {
        String predicate = cursor == null ? "" : " AND (d.created_at, d.deck_id) < (:time, :deck)";
        var query = jdbc.sql(CURRENT + predicate + " ORDER BY d.created_at DESC, d.deck_id DESC LIMIT :limit")
                .param("actor", actor).param("limit", limit + 1);
        if (cursor != null) query.param("time", Timestamp.from(cursor.createdAt())).param("deck", cursor.deckId());
        return query.query(ROW).list();
    }

    Instant now() { return jdbc.sql("SELECT statement_timestamp()").query(Timestamp.class).single().toInstant(); }

    void insertDeck(UUID deck, UUID actor, UUID scope, UUID revision, Instant time) {
        jdbc.sql("""
                INSERT INTO app_learning.deck(deck_id, owner_id, reuse_scope_id, head_revision_id, row_version, created_at)
                VALUES (:deck, :actor, :scope, :revision, 0, :time)
                """).param("deck", deck).param("actor", actor).param("scope", scope).param("revision", revision)
                .param("time", Timestamp.from(time)).update();
    }

    void insertRevision(DeckRecord row, UUID actor, UUID parent, DeckCommand command, UUID membersPin, UUID exercisesPin) {
        jdbc.sql("""
                INSERT INTO app_learning.deck_revision(deck_id, revision_id, reuse_scope_id, owner_id, sequence,
                    parent_revision_id, parent_sequence, command_id, title, description, created_at,
                    members_root_id, exercises_root_id, members_pin_id, exercises_pin_id, member_count, exercise_count)
                VALUES (:deck, :revision, :scope, :actor, :sequence, :parent, :parentSequence, :command, :title,
                    :description, :time, :members, :exercises, :membersPin, :exercisesPin, :memberCount, :exerciseCount)
                """).param("deck", row.deckId()).param("revision", row.revisionId()).param("scope", row.scopeId())
                .param("actor", actor).param("sequence", row.rowVersion()).param("parent", parent, java.sql.Types.OTHER)
                .param("parentSequence", parent == null ? null : row.rowVersion() - 1, java.sql.Types.BIGINT)
                .param("command", command.commandId()).param("title", row.title()).param("description", row.description())
                .param("time", Timestamp.from(row.updatedAt())).param("members", row.membersRootId())
                .param("exercises", row.exercisesRootId()).param("membersPin", membersPin).param("exercisesPin", exercisesPin)
                .param("memberCount", row.memberCount()).param("exerciseCount", row.exerciseCount()).update();
    }

    int advance(UUID actor, UUID deck, UUID revision, long expected) {
        return jdbc.sql("""
                UPDATE app_learning.deck SET head_revision_id = :revision, row_version = row_version + 1
                WHERE owner_id = :actor AND deck_id = :deck AND row_version = :expected
                """).param("revision", revision).param("actor", actor).param("deck", deck).param("expected", expected).update();
    }
}
