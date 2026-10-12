package app.mnema.learning.update.auto;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The one read of the fan-out (Updates/6 part B, #445): a page of the copies of ONE source that follow it automatically, in the order of the copy id.
 *
 * <p>The page is the keyset {@code (upstream_deck_id, deck_id) > (source, after)} of the partial index {@code deck_fork_auto_fanout} (V65), which holds exactly the live, attached,
 * materialized copies with {@code auto_update}; a copy whose base is the revision being fanned out has nothing to take and is skipped by the page itself. "Active" is decided for
 * the copies of the page only, one probe each, by the cheapest signals that already exist and cost the hot paths nothing: the copy was made inside the window ({@code forked_at},
 * the row the page already reads), or its owner started a Study session of THIS copy inside the window (the index {@code study_session_resume (account_id, deck_id, created_at DESC)}
 * answers it with one entry). No table is written to track activity.
 */
@Repository
class FanoutRepository {
    /** A copy of the page; {@code active} says whether its owner is worth an automatic apply. */
    record Candidate(UUID deckId, UUID ownerId, boolean active) { }

    private final JdbcClient jdbc;

    FanoutRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** The revision the source has published NOW (primary-key probe); empty when it has none. */
    java.util.Optional<UUID> published(UUID source) {
        return jdbc.sql("SELECT published_revision_id FROM app_learning.deck_publication WHERE deck_id=:source").param("source", source).query(UUID.class).optional();
    }

    /** At most {@code scan} copies of the source after {@code after} (null: from the start), in the order of the copy id. */
    List<Candidate> page(UUID source, UUID revision, UUID after, Instant activeSince, int scan) {
        return jdbc.sql("""
                WITH page AS (
                    SELECT f.deck_id,f.owner_id,f.forked_at
                      FROM app_learning.deck_fork f
                     WHERE f.upstream_deck_id=:source AND f.deck_id > :after
                       AND f.auto_update AND f.materialized_at IS NOT NULL AND f.detached_at IS NULL AND f.released_at IS NULL
                       AND f.base_revision_id <> :revision
                     ORDER BY f.deck_id LIMIT :scan)
                SELECT p.deck_id,p.owner_id,
                       (p.forked_at >= :since OR EXISTS (SELECT 1 FROM app_learning.study_session s
                                                          WHERE s.account_id=p.owner_id AND s.deck_id=p.deck_id AND s.created_at >= :since)) AS active
                  FROM page p ORDER BY p.deck_id
                """).param("source", source).param("revision", revision).param("after", after == null ? new UUID(0L, 0L) : after)
                .param("since", Timestamp.from(activeSince)).param("scan", scan)
                .query((row, ignored) -> new Candidate(row.getObject("deck_id", UUID.class), row.getObject("owner_id", UUID.class), row.getBoolean("active"))).list();
    }
}
