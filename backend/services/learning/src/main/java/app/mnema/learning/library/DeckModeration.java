package app.mnema.learning.library;

import app.mnema.learning.community.CatalogOutbox;
import app.mnema.learning.platform.id.UuidPolicy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The deck side of moderation (Share/15, #437): the HIDDEN flag of {@code deck_publication} and what the owner console and the owner's notice read about a
 * deck. A hidden deck is invisible to everybody but its owner ({@link DeckAccess}, the invitation redemption); copies other people made are untouched.
 * The flag is not part of the owner's publication command: republishing, changing the level or rotating the code never clears it, and only
 * {@link #unhide} does. Changing it bumps the publication row version (the guard trigger demands it), so an open publication editor gets a 412 and reloads;
 * it also appends one {@code deck_publication_event} row and enqueues the catalogue projection ({@link CatalogOutbox}) when the deck has a published revision, so the projector (Community/1) re-evaluates the deck.
 *
 * <p>Writers run inside the decision's transaction ({@code MANDATORY}) and lock the publication row first.
 */
@Service
public class DeckModeration {
    /** A deck as the console sees it (no content except the published title). */
    public record Info(UUID deckId, UUID ownerId, UUID scopeId, boolean live, String publicCode, DeckVisibility visibility, boolean published, String title,
                       int memberCount, int exerciseCount, boolean hidden, Instant hiddenAt) { }

    /** What the owner of a deck sees on the hub. */
    public record Notice(UUID deckId, boolean hidden, Instant hiddenAt, int materials, int exercises) { }

    private final JdbcClient jdbc;
    private final CatalogOutbox outbox;

    DeckModeration(JdbcClient jdbc, CatalogOutbox outbox) {
        this.jdbc = jdbc;
        this.outbox = outbox;
    }

    /** The deck behind an id, deleted or not, with its publication; empty for an id that never was a deck. */
    public Optional<Info> deck(UUID deckId) {
        UuidPolicy.requireEntityId(deckId, "deckId");
        return jdbc.sql("""
                SELECT d.deck_id, d.owner_id, d.reuse_scope_id, d.deleted_at IS NULL AS live, p.public_code, COALESCE(p.visibility, 'PRIVATE') AS visibility,
                       p.published_revision_id IS NOT NULL AS published, r.title, COALESCE(r.member_count, 0) AS member_count,
                       COALESCE(r.exercise_count, 0) AS exercise_count, COALESCE(p.hidden_by_moderation, FALSE) AS hidden, p.hidden_at
                  FROM app_learning.deck d
                  LEFT JOIN app_learning.deck_publication p ON p.deck_id = d.deck_id
                  LEFT JOIN app_learning.deck_revision r ON r.deck_id = p.deck_id AND r.revision_id = p.published_revision_id
                 WHERE d.deck_id = :deck
                """).param("deck", deckId).query((row, ignored) -> new Info(row.getObject("deck_id", UUID.class), row.getObject("owner_id", UUID.class),
                row.getObject("reuse_scope_id", UUID.class), row.getBoolean("live"), row.getString("public_code"),
                DeckVisibility.valueOf(row.getString("visibility")), row.getBoolean("published"), row.getString("title"), row.getInt("member_count"),
                row.getInt("exercise_count"), row.getBoolean("hidden"), instant(row.getTimestamp("hidden_at")))).optional();
    }

    /** Locks the deck's publication row until the end of the caller's transaction: the published revision and the flag cannot move meanwhile. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockPublication(UUID deckId) {
        UuidPolicy.requireEntityId(deckId, "deckId");
        jdbc.sql("SELECT 1 FROM app_learning.deck_publication WHERE deck_id = :deck FOR UPDATE").param("deck", deckId).query(Integer.class).optional();
    }

    /** Hides the deck. @return whether the flag changed (false: it was already hidden, or the deck has no publication row) */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean hide(UUID deckId, Instant now) {
        UuidPolicy.requireEntityId(deckId, "deckId");
        return flip(deckId, true, now);
    }

    /** Lifts the flag. @return whether it changed (false: the deck was not hidden) */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean unhide(UUID deckId) {
        UuidPolicy.requireEntityId(deckId, "deckId");
        return flip(deckId, false, null);
    }

    private boolean flip(UUID deckId, boolean hidden, Instant now) {
        // lock the row first: the decision's other writes assume nobody else flips it meanwhile
        Optional<Boolean> current = jdbc.sql("SELECT hidden_by_moderation FROM app_learning.deck_publication WHERE deck_id = :deck FOR UPDATE")
                .param("deck", deckId).query(Boolean.class).optional();
        if (current.isEmpty() || current.orElseThrow() == hidden) return false;
        record Flipped(UUID revision, DeckVisibility visibility) { }
        Flipped flipped = jdbc.sql("""
                UPDATE app_learning.deck_publication
                   SET hidden_by_moderation = :hidden, hidden_at = CAST(:at AS timestamptz), row_version = row_version + 1, updated_at = statement_timestamp()
                 WHERE deck_id = :deck
                RETURNING published_revision_id, visibility
                """).param("hidden", hidden).param("at", hidden ? Timestamp.from(now) : null, java.sql.Types.TIMESTAMP).param("deck", deckId)
                .query((row, ignored) -> new Flipped(row.getObject("published_revision_id", UUID.class), DeckVisibility.valueOf(row.getString("visibility")))).single();
        // the outbox row and the projection job in this very transaction: the catalogue removes a hidden deck (and brings it back) as soon as the job runs
        if (hidden) outbox.withdrawn(deckId);
        if (flipped.revision() != null) outbox.publicationChanged(deckId, flipped.revision(), flipped.visibility());
        return true;
    }

    /**
     * The notice of a live deck of {@code owner}: whether it is hidden and how many of its HEAD units are taken down right now (the source and a copy
     * alike; a copy whose learner edited the unit holds another revision and does not count). Empty when the deck is not the owner's.
     */
    @Transactional(readOnly = true, timeout = 5)
    public Optional<Notice> notice(UUID owner, UUID deckId) {
        UuidPolicy.requireEntityId(owner, "owner");
        UuidPolicy.requireEntityId(deckId, "deckId");
        return jdbc.sql("""
                SELECT d.deck_id, COALESCE(p.hidden_by_moderation, FALSE) AS hidden, p.hidden_at,
                       (SELECT count(*) FROM app_learning.content_takedown t
                          JOIN app_learning.deck_head_item h ON h.deck_id = d.deck_id AND h.reuse_scope_id = t.reuse_scope_id
                           AND h.member_key = t.unit_key AND h.revision_id = t.revision_id
                         WHERE t.reuse_scope_id = d.reuse_scope_id AND t.unit_kind = 'ITEM')::integer AS materials,
                       (SELECT count(*) FROM app_learning.content_takedown t
                          JOIN app_learning.deck_head_exercise h ON h.deck_id = d.deck_id AND h.reuse_scope_id = t.reuse_scope_id
                           AND h.exercise_id = t.unit_key AND h.revision_id = t.revision_id
                         WHERE t.reuse_scope_id = d.reuse_scope_id AND t.unit_kind = 'EXERCISE')::integer AS exercises
                  FROM app_learning.deck d LEFT JOIN app_learning.deck_publication p ON p.deck_id = d.deck_id
                 WHERE d.deck_id = :deck AND d.owner_id = :owner AND d.deleted_at IS NULL
                """).param("deck", deckId).param("owner", owner).query((row, ignored) -> new Notice(row.getObject("deck_id", UUID.class),
                row.getBoolean("hidden"), instant(row.getTimestamp("hidden_at")), row.getInt("materials"), row.getInt("exercises"))).optional();
    }

    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
}
