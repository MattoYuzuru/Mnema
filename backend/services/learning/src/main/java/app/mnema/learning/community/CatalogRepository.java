package app.mnema.learning.community;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads of the catalogue projection {@code public_deck} (V67). Deliberately small: search, ranking and the HTTP routes are Community/2, /3 and /6 and add their own statements here.
 * What exists now is what the first routes and the tests need: one row by deck, a keyset page of LISTED decks newest first («Новое»), and a keyset page of one author's decks
 * (listed or not, newest publication first: the author's page shows decks below the threshold). Every page is an index range ({@code public_deck_new}, {@code public_deck_owner});
 * the keyset is the sort key with the deck id as tiebreak, so a page never repeats or skips a row that does not change meanwhile.
 */
@Repository
class CatalogRepository {
    private static final String COLUMNS = """
            deck_id, public_code, owner_id, published_revision_id, title, description, topic_id, parent_topic_id, content_language, target_language, level, tags,
            item_count, exercise_count, media_mask, mechanics_mask, listed, first_listed_at, content_updated_at, title_norm, projected_revision_id, projected_at""";

    private static final RowMapper<PublicDeckRow> ROW = (row, ignored) -> new PublicDeckRow(row.getObject("deck_id", UUID.class), row.getString("public_code"),
            row.getObject("owner_id", UUID.class), row.getObject("published_revision_id", UUID.class), row.getString("title"), row.getString("description"),
            row.getString("topic_id"), row.getString("parent_topic_id"), row.getString("content_language"), row.getString("target_language"), row.getString("level"),
            tags(row), row.getInt("item_count"), row.getInt("exercise_count"), row.getInt("media_mask"), row.getInt("mechanics_mask"), row.getBoolean("listed"),
            instant(row.getTimestamp("first_listed_at")), instant(row.getTimestamp("content_updated_at")), row.getString("title_norm"),
            row.getObject("projected_revision_id", UUID.class), instant(row.getTimestamp("projected_at")));

    private final JdbcClient jdbc;

    CatalogRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }

    private static List<String> tags(java.sql.ResultSet row) throws SQLException {
        return List.of((String[]) row.getArray("tags").getArray());
    }

    Optional<PublicDeckRow> find(UUID deck) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM app_learning.public_deck WHERE deck_id = :deck").param("deck", deck).query(ROW).optional();
    }

    /** The statement of {@link #listedPage}; {@code after} is the keyset condition (empty for the first page) so the plan is the plain index range, never an OR on a parameter. */
    static String listedSql(boolean after) {
        return "SELECT " + COLUMNS + " FROM app_learning.public_deck WHERE listed" + (after ? " AND (first_listed_at, deck_id) < (:t, :d)" : "")
                + " ORDER BY first_listed_at DESC, deck_id DESC LIMIT :limit";
    }

    /** The statement of {@link #ownerPage}. */
    static String ownerSql(boolean after) {
        return "SELECT " + COLUMNS + " FROM app_learning.public_deck WHERE owner_id = :owner" + (after ? " AND (content_updated_at, deck_id) < (:t, :d)" : "")
                + " ORDER BY content_updated_at DESC, deck_id DESC LIMIT :limit";
    }

    /**
     * A page of listed decks, newest first by {@code first_listed_at}. {@code afterFirstListedAt} and {@code afterDeck} are the sort key of the last row of the previous
     * page (both null for the first page).
     */
    List<PublicDeckRow> listedPage(Instant afterFirstListedAt, UUID afterDeck, int limit) {
        boolean after = afterDeck != null;
        JdbcClient.StatementSpec statement = jdbc.sql(listedSql(after));
        if (after) statement = statement.param("t", Timestamp.from(afterFirstListedAt)).param("d", afterDeck);
        return statement.param("limit", limit).query(ROW).list();
    }

    /** A page of one author's decks, listed or not, newest publication first. */
    List<PublicDeckRow> ownerPage(UUID owner, Instant afterUpdatedAt, UUID afterDeck, int limit) {
        boolean after = afterDeck != null;
        JdbcClient.StatementSpec statement = jdbc.sql(ownerSql(after)).param("owner", owner);
        if (after) statement = statement.param("t", Timestamp.from(afterUpdatedAt)).param("d", afterDeck);
        return statement.param("limit", limit).query(ROW).list();
    }
}
