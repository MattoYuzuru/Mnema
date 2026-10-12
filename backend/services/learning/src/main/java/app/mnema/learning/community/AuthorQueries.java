package app.mnema.learning.community;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The SQL of the decks of an author (Community/6, #453): his PUBLIC decks, listed or not, and, for a signed-in viewer, his invitation-only decks that viewer was granted. Both are
 * keyset pages in (update time, public code) order; the public part is the range of {@code public_deck_owner} ({@code V67}), the grant part starts from
 * {@code deck_access_grant_grantee} (the grants of ONE viewer, a handful) and probes the publication, the deck and the published revision by key.
 */
@Repository
class AuthorQueries {
    /** An invitation-only deck as the grantee sees it: what the published revision and the publication row say, nothing computed. */
    record Granted(UUID deckId, String code, UUID ownerId, String contentLanguage, String title, String description, int itemCount, int exerciseCount, Instant publishedAt) { }

    private final JdbcClient jdbc;

    AuthorQueries(JdbcClient jdbc) { this.jdbc = jdbc; }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    /** The author's decks in the projection, newest publication first, {@code limit} rows (the caller asks for one more than a page to know whether there is more). */
    List<PublicDeckRow> publicDecks(UUID owner, Instant afterAt, String afterCode, int limit) {
        boolean after = afterAt != null;
        var statement = jdbc.sql("SELECT " + CatalogQueries.D + " FROM app_learning.public_deck d WHERE d.owner_id = :owner"
                + (after ? " AND d.content_updated_at <= :t AND (d.content_updated_at, d.public_code COLLATE \"C\") < (:t, :c)" : "")
                + " ORDER BY d.content_updated_at DESC, d.public_code COLLATE \"C\" DESC LIMIT :limit").param("owner", owner);
        if (after) statement = statement.param("t", at(afterAt)).param("c", afterCode);
        return statement.param("limit", limit).query((row, ignored) -> CatalogRepository.ROW.mapRow(row, 0)).list();
    }

    /**
     * The INVITE decks of {@code author} that {@code viewer} holds a grant for: published, not hidden by moderation, not deleted. A deck whose level was lowered or raised
     * stops matching by itself (the level is read here, not remembered).
     */
    List<Granted> granted(UUID author, UUID viewer, Instant afterAt, String afterCode, int limit) {
        boolean after = afterAt != null;
        var statement = jdbc.sql("""
                SELECT p.deck_id, p.public_code, d.owner_id, p.content_language, r.title, r.description, r.member_count, r.exercise_count, p.published_at
                  FROM app_learning.deck_access_grant g
                  JOIN app_learning.deck_publication p ON p.deck_id = g.deck_id
                  JOIN app_learning.deck d ON d.deck_id = g.deck_id
                  JOIN app_learning.deck_revision r ON r.deck_id = p.deck_id AND r.revision_id = p.published_revision_id
                 WHERE g.grantee_id = :viewer AND d.owner_id = :author AND d.deleted_at IS NULL
                   AND p.visibility = 'INVITE' AND p.published_revision_id IS NOT NULL AND NOT p.hidden_by_moderation
                """ + (after ? " AND p.published_at <= :t AND (p.published_at, p.public_code COLLATE \"C\") < (:t, :c)" : "")
                + " ORDER BY p.published_at DESC, p.public_code COLLATE \"C\" DESC LIMIT :limit").param("viewer", viewer).param("author", author);
        if (after) statement = statement.param("t", at(afterAt)).param("c", afterCode);
        return statement.param("limit", limit).query((row, ignored) -> new Granted(row.getObject("deck_id", UUID.class), row.getString("public_code"),
                row.getObject("owner_id", UUID.class), row.getString("content_language"), row.getString("title"), row.getString("description"), row.getInt("member_count"),
                row.getInt("exercise_count"), row.getObject("published_at", OffsetDateTime.class).toInstant())).list();
    }

    /**
     * The statement of {@link #learningNow}: one aggregate over the {@code deck_engagement} rows of the author's PUBLIC decks (listed or not). The primary key
     * {@code (deck_id, account_id, kind)} serves it per deck after {@code public_deck_owner} has given the decks; the rows read are the study rows of those decks, so the cost is bounded by
     * the audience of one author.
     */
    static final String LEARNING_NOW_SQL = """
            SELECT count(DISTINCT e.account_id)::bigint
              FROM app_learning.public_deck d
              JOIN app_learning.deck_engagement e ON e.deck_id = d.deck_id AND e.kind = 'STUDY' AND e.last_at >= :since AND e.account_id <> d.owner_id
             WHERE d.owner_id = :author
            """;

    /**
     * «Учат сейчас» of an author: the DISTINCT non-owner learners with a study row (a completed scheduled session of a direct copy) since {@code since} across his public decks. A person who
     * studies three of his decks is one learner, which a sum of the per-deck counts would count three times. Raw: the caller applies {@link AudienceCounts}.
     */
    long learningNow(UUID author, java.time.Instant since) {
        return jdbc.sql(LEARNING_NOW_SQL).param("author", author).param("since", at(since)).query(Long.class).single();
    }
}
