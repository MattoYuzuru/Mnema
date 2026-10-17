package app.mnema.learning.community;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The SQL of the anomaly flags (Community/3, #450, migration {@code V68}): the daily detection and the owner console's list and dismissal. The table holds a handful of rows (a flag is
 * raised for a burst of copies by new accounts, not for a deck), so the list is a scan of it and the only index is the primary key.
 */
@Repository
class AnomalyRepository {
    /** One flag as the console shows it; {@code code}, {@code title} and {@code ownerId} are the deck's current ones while it is public and the last known ones otherwise. */
    record Flag(UUID deckId, String code, String title, UUID ownerId, Instant detectedAt, int windowForks, BigDecimal newShare, Instant dismissedAt) { }

    /** The position after a flag in the console order (open first, then newest first). */
    record After(boolean dismissed, Instant detectedAt, UUID deckId) { }

    private final JdbcClient jdbc;

    AnomalyRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /**
     * The detection, one statement. Per PUBLIC deck: the copies ({@code FORK} rows, never the owner's) made since {@code now - windowDays}, and for a deck that was dismissed only those
     * made AFTER the dismissal, so a dismissed burst can never raise the flag again and only a newer window that crosses the threshold on its own does. When there are at least
     * {@code minForks} of them and MORE than {@code newShareAbove} of them were made by accounts Identity called new (exact decimal arithmetic: 14 of 20 is 70 %, not above), the flag is
     * upserted: a new flag takes {@code detected_at = now}; an open one keeps its date and gets the fresh figures; a dismissed one is raised again (dismissal cleared, new date).
     * A flag whose burst is over is never cleared by the job: the owner decides.
     *
     * @return the number of flags raised or refreshed
     */
    int detect(Instant now, AnomalySettings settings) {
        return jdbc.sql("""
                INSERT INTO app_learning.catalog_anomaly AS a(deck_id, public_code, title, owner_id, detected_at, window_forks, new_share, dismissed_at)
                SELECT w.deck_id, p.public_code, p.title, p.owner_id, :now, w.forks, round(w.fresh::numeric / w.forks, 4), NULL
                  FROM (
                        SELECT e.deck_id, count(*)::integer AS forks, (count(*) FILTER (WHERE e.new_account))::integer AS fresh
                          FROM app_learning.deck_engagement e
                          LEFT JOIN app_learning.catalog_anomaly d ON d.deck_id = e.deck_id
                         WHERE e.kind = 'FORK' AND e.first_at >= :since AND (d.dismissed_at IS NULL OR e.first_at > d.dismissed_at)
                         GROUP BY e.deck_id
                        HAVING count(*) >= :minForks
                       ) w
                  JOIN app_learning.public_deck p ON p.deck_id = w.deck_id
                 WHERE w.fresh::numeric > w.forks * :share
                ON CONFLICT (deck_id) DO UPDATE SET
                    public_code = EXCLUDED.public_code, title = EXCLUDED.title, owner_id = EXCLUDED.owner_id,
                    window_forks = EXCLUDED.window_forks, new_share = EXCLUDED.new_share,
                    detected_at = CASE WHEN a.dismissed_at IS NULL THEN a.detected_at ELSE EXCLUDED.detected_at END,
                    dismissed_at = NULL
                """).param("now", at(now)).param("since", at(now.minus(Duration.ofDays(settings.windowDays)))).param("minForks", settings.minForks)
                .param("share", settings.newShareAbove).update();
    }

    /** One page of the console list: open flags first, then dismissed ones, newest detection first; {@code size + 1} rows are read so that the caller knows whether there is more. */
    List<Flag> page(After after, int size) {
        var query = jdbc.sql("""
                SELECT a.deck_id, COALESCE(p.public_code, a.public_code) AS code, COALESCE(p.title, a.title) AS title, COALESCE(p.owner_id, a.owner_id) AS owner_id,
                       a.detected_at, a.window_forks, a.new_share, a.dismissed_at
                  FROM app_learning.catalog_anomaly a LEFT JOIN app_learning.public_deck p ON p.deck_id = a.deck_id
                 WHERE :first
                    OR (a.dismissed_at IS NOT NULL)::integer > :dismissed
                    OR ((a.dismissed_at IS NOT NULL)::integer = :dismissed
                        AND (a.detected_at < :detectedAt OR (a.detected_at = :detectedAt AND a.deck_id < :deckId)))
                 ORDER BY (a.dismissed_at IS NOT NULL), a.detected_at DESC, a.deck_id DESC
                 LIMIT :limit
                """).param("first", after == null).param("dismissed", after != null && after.dismissed() ? 1 : 0)
                .param("detectedAt", at(after == null ? Instant.EPOCH : after.detectedAt())).param("deckId", after == null ? new UUID(0, 0) : after.deckId())
                .param("limit", size + 1);
        return query.query((row, ignored) -> flag(row)).list();
    }

    /** The flag whose CURRENT code (or last known one) is {@code code}, locked for the dismissal. */
    Optional<Flag> lockByCode(String code) {
        return jdbc.sql("""
                SELECT a.deck_id, COALESCE(p.public_code, a.public_code) AS code, COALESCE(p.title, a.title) AS title, COALESCE(p.owner_id, a.owner_id) AS owner_id,
                       a.detected_at, a.window_forks, a.new_share, a.dismissed_at
                  FROM app_learning.catalog_anomaly a LEFT JOIN app_learning.public_deck p ON p.deck_id = a.deck_id
                 WHERE COALESCE(p.public_code, a.public_code) = :code
                 ORDER BY a.detected_at DESC LIMIT 1 FOR UPDATE OF a
                """).param("code", code).query((row, ignored) -> flag(row)).optional();
    }

    /** Sets the dismissal once; false when the flag was already dismissed. */
    boolean dismiss(UUID deck, Instant now) {
        return jdbc.sql("UPDATE app_learning.catalog_anomaly SET dismissed_at = GREATEST(:now, detected_at) WHERE deck_id = :deck AND dismissed_at IS NULL")
                .param("now", at(now)).param("deck", deck).update() == 1;
    }

    private static Flag flag(java.sql.ResultSet row) throws java.sql.SQLException {
        OffsetDateTime dismissed = row.getObject("dismissed_at", OffsetDateTime.class);
        return new Flag(row.getObject("deck_id", UUID.class), row.getString("code"), row.getString("title"), row.getObject("owner_id", UUID.class),
                row.getObject("detected_at", OffsetDateTime.class).toInstant(), row.getInt("window_forks"), row.getBigDecimal("new_share"),
                dismissed == null ? null : dismissed.toInstant());
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
