package app.mnema.learning.community;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/** The SQL of the owner's editorial shelf (Community/7, migration {@code V69}): a list of at most 24 rows under the lock of the single {@code editorial_shelf} row. */
@Repository
class EditorialRepository {
    /** The constant id of the shelf: the lock row, and the resource of the audit rows. */
    static final UUID SHELF = UUID.fromString("00000000-0000-7000-8000-00000000e001");

    /** An entry as the owner sees it: the code and title are the deck's current ones while it is in the catalogue, the last known ones of the publication otherwise. */
    record Entry(UUID deckId, String code, String title, boolean listed, Instant addedAt, UUID addedBy) { }

    /** A deck a code points to, if it is a PUBLIC published deck that is not deleted. */
    record Target(UUID deckId, String code) { }

    private final JdbcClient jdbc;

    EditorialRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    long version() {
        return jdbc.sql("SELECT row_version FROM app_learning.editorial_shelf WHERE shelf_id = :shelf").param("shelf", SHELF).query(Long.class).single();
    }

    /** The version, taking the lock that serializes two commands. */
    long lockVersion() {
        return jdbc.sql("SELECT row_version FROM app_learning.editorial_shelf WHERE shelf_id = :shelf FOR UPDATE").param("shelf", SHELF).query(Long.class).single();
    }

    void bump(Instant now) {
        jdbc.sql("UPDATE app_learning.editorial_shelf SET row_version = row_version + 1, updated_at = :now WHERE shelf_id = :shelf").param("shelf", SHELF).param("now", at(now)).update();
    }

    /** The list in the owner's order. */
    List<Entry> entries() {
        return jdbc.sql("""
                SELECT e.deck_id, COALESCE(pd.public_code, p.public_code) AS code, COALESCE(pd.title, r.title) AS title, COALESCE(pd.listed, FALSE) AS listed, e.added_at, e.added_by
                  FROM app_learning.editorial_pick e
                  JOIN app_learning.deck_publication p ON p.deck_id = e.deck_id
                  LEFT JOIN app_learning.public_deck pd ON pd.deck_id = e.deck_id
                  LEFT JOIN app_learning.deck_revision r ON r.deck_id = p.deck_id AND r.revision_id = p.published_revision_id
                 ORDER BY e.position
                """).query((row, ignored) -> new Entry(row.getObject("deck_id", UUID.class), row.getString("code"), row.getString("title"), row.getBoolean("listed"),
                row.getObject("added_at", OffsetDateTime.class).toInstant(), row.getObject("added_by", UUID.class))).list();
    }

    /** The PUBLIC published, not deleted decks among {@code codes} (a hidden or below-threshold deck is accepted: the shelf skips it and the owner sees {@code listed=false}). */
    List<Target> targets(List<String> codes) {
        if (codes.isEmpty()) return List.of();
        return jdbc.sql("""
                SELECT p.deck_id, p.public_code FROM app_learning.deck_publication p JOIN app_learning.deck d ON d.deck_id = p.deck_id
                 WHERE p.public_code IN (:codes) AND p.visibility = 'PUBLIC' AND p.published_revision_id IS NOT NULL AND d.deleted_at IS NULL
                """).param("codes", codes).query((row, ignored) -> new Target(row.getObject("deck_id", UUID.class), row.getString("public_code"))).list();
    }

    void clear() {
        jdbc.sql("DELETE FROM app_learning.editorial_pick").update();
    }

    void insert(UUID deck, int position, Instant addedAt, UUID addedBy) {
        jdbc.sql("INSERT INTO app_learning.editorial_pick(deck_id, position, added_at, added_by) VALUES (:deck, :position, :addedAt, :addedBy)")
                .param("deck", deck).param("position", position).param("addedAt", at(addedAt)).param("addedBy", addedBy).update();
    }
}
