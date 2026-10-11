package app.mnema.learning.library;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Access rows: one lookup per request by code (the unique index of {@code deck_publication}) or by deck id, one probe of the grant key, and the
 * owner's writes. Every statement that answers a non-owner starts at {@code deck_publication}, never at a content table.
 */
@Repository
class LibraryRepository {
    private static final String DECK = """
            SELECT d.deck_id, d.owner_id, d.reuse_scope_id, COALESCE(p.visibility, 'PRIVATE') AS visibility, p.public_code,
                   p.published_revision_id, p.published_at, r.title, r.description, r.members_root_id, r.exercises_root_id,
                   r.member_count, r.exercise_count
            """;
    private static final RowMapper<DeckRef> DECK_ROW = (row, ignored) -> {
        UUID revision = row.getObject("published_revision_id", UUID.class);
        return new DeckRef(row.getObject("deck_id", UUID.class), row.getObject("owner_id", UUID.class),
                row.getObject("reuse_scope_id", UUID.class), DeckVisibility.valueOf(row.getString("visibility")),
                row.getString("public_code"), revision == null ? null : new DeckRef.Published(revision,
                row.getTimestamp("published_at").toInstant(), row.getString("title"), row.getString("description"),
                row.getObject("members_root_id", UUID.class), row.getObject("exercises_root_id", UUID.class),
                row.getInt("member_count"), row.getInt("exercise_count")));
    };
    private static final RowMapper<Publication> PUBLICATION = (row, ignored) -> new Publication(
            row.getObject("deck_id", UUID.class), DeckVisibility.valueOf(row.getString("visibility")), row.getString("public_code"),
            row.getTimestamp("code_rotated_at").toInstant(), row.getObject("published_revision_id", UUID.class),
            instant(row.getTimestamp("published_at")), row.getLong("row_version"), row.getTimestamp("created_at").toInstant(),
            row.getTimestamp("updated_at").toInstant());

    private final JdbcClient jdbc;

    LibraryRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }

    /** The live deck behind a public code (one unique-index probe, then the deck key); a deleted deck has no answer. */
    Optional<DeckRef> byCode(String code) {
        return jdbc.sql(DECK + """
                  FROM app_learning.deck_publication p
                  JOIN app_learning.deck d ON d.deck_id = p.deck_id AND d.deleted_at IS NULL
                  LEFT JOIN app_learning.deck_revision r ON r.deck_id = p.deck_id AND r.revision_id = p.published_revision_id
                 WHERE p.public_code = :code
                """).param("code", code).query(DECK_ROW).optional();
    }

    /** The live deck by id, private or not. */
    Optional<DeckRef> byId(UUID deck) {
        return jdbc.sql(DECK + """
                  FROM app_learning.deck d
                  LEFT JOIN app_learning.deck_publication p ON p.deck_id = d.deck_id
                  LEFT JOIN app_learning.deck_revision r ON r.deck_id = p.deck_id AND r.revision_id = p.published_revision_id
                 WHERE d.deck_id = :deck AND d.deleted_at IS NULL
                """).param("deck", deck).query(DECK_ROW).optional();
    }

    boolean hasGrant(UUID deck, UUID account) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM app_learning.deck_access_grant WHERE deck_id = :deck AND grantee_id = :account)")
                .param("deck", deck).param("account", account).query(Boolean.class).single();
    }

    boolean ownsLiveDeck(UUID owner, UUID deck) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM app_learning.deck WHERE deck_id = :deck AND owner_id = :owner AND deleted_at IS NULL)")
                .param("deck", deck).param("owner", owner).query(Boolean.class).single();
    }

    /** The publication row, locked until the end of the transaction so the version check and the write cannot interleave. */
    Optional<Publication> lockPublication(UUID deck) {
        return jdbc.sql("SELECT * FROM app_learning.deck_publication WHERE deck_id = :deck FOR UPDATE")
                .param("deck", deck).query(PUBLICATION).optional();
    }

    Optional<Publication> publication(UUID owner, UUID deck) {
        return jdbc.sql("""
                SELECT p.* FROM app_learning.deck_publication p JOIN app_learning.deck d ON d.deck_id = p.deck_id
                 WHERE p.deck_id = :deck AND d.owner_id = :owner AND d.deleted_at IS NULL
                """).param("deck", deck).param("owner", owner).query(PUBLICATION).optional();
    }

    Publication insert(UUID deck, DeckVisibility visibility, String code) {
        return jdbc.sql("""
                INSERT INTO app_learning.deck_publication(deck_id, visibility, public_code, code_rotated_at, row_version, created_at, updated_at)
                VALUES (:deck, :visibility, :code, statement_timestamp(), 0, statement_timestamp(), statement_timestamp())
                RETURNING *
                """).param("deck", deck).param("visibility", visibility.name()).param("code", code).query(PUBLICATION).single();
    }

    /** Sets the level at the expected version; a non-null {@code newCode} also rotates the code. Empty when the version moved. */
    Optional<Publication> update(UUID deck, DeckVisibility visibility, String newCode, long expected) {
        return jdbc.sql("""
                UPDATE app_learning.deck_publication
                   SET visibility = :visibility,
                       public_code = COALESCE(:code, public_code),
                       code_rotated_at = CASE WHEN CAST(:code AS text) IS NULL THEN code_rotated_at ELSE statement_timestamp() END,
                       row_version = row_version + 1, updated_at = statement_timestamp()
                 WHERE deck_id = :deck AND row_version = :expected
                RETURNING *
                """).param("visibility", visibility.name()).param("code", newCode, java.sql.Types.VARCHAR).param("deck", deck)
                .param("expected", expected).query(PUBLICATION).optional();
    }

    void grant(UUID deck, UUID grantee, GrantRole role, UUID grantedBy) {
        jdbc.sql("""
                INSERT INTO app_learning.deck_access_grant(deck_id, grantee_id, role, granted_by, granted_at)
                VALUES (:deck, :grantee, :role, :by, statement_timestamp()) ON CONFLICT (deck_id, grantee_id) DO NOTHING
                """).param("deck", deck).param("grantee", grantee).param("role", role.name()).param("by", grantedBy).update();
    }

    boolean revoke(UUID deck, UUID grantee) {
        return jdbc.sql("DELETE FROM app_learning.deck_access_grant WHERE deck_id = :deck AND grantee_id = :grantee")
                .param("deck", deck).param("grantee", grantee).update() == 1;
    }
}
