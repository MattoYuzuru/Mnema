package app.mnema.learning.community;

import app.mnema.learning.catalog.content.pages.CountedPageTypes.Entry;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The SQL of the catalogue projector and of the rebuild. Every statement is keyed by one deck (a primary-key or short index range) except {@link #publicDecksAfter}, which is a
 * keyset page of the rebuild. Nothing here reads a deck's head or journal: the projection is built from the deck's PUBLISHED revision only.
 *
 * <p>TODO(account-deletion task #409; owner: Learning's account-purge epic): the purge of an account must delete the account's {@code public_deck} rows ({@code WHERE owner_id = :account}) and
 * their {@code catalog_listing} rows (by the account's decks), before it deletes the decks the rows reference, so that the author's public decks leave the catalogue with the account; the rows are derived data and hold the
 * title, description and tags of the account's decks. Learning has no account purge path yet; see "Retention" in the Learning guide.
 */
@Repository
class ProjectionRepository {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** Sorts before every deck id. */
    private static final UUID NIL = new UUID(0L, 0L);

    /** What the deck is right now when it belongs in the catalogue: its lineage scope, the published revision with its exercise manifest and its title. */
    record Published(UUID deckId, UUID scopeId, UUID revisionId, UUID exercisesRootId, int exerciseCount, String title) { }

    /** The row as the projector left it: which revision its metadata and its mechanics were derived from. */
    record Projected(UUID publishedRevisionId, UUID projectedRevisionId, boolean listed) {
        Projected(UUID publishedRevisionId, UUID projectedRevisionId) { this(publishedRevisionId, projectedRevisionId, false); }
    }

    /** What the catalogue keeps about a deck apart from its row ({@code catalog_listing}); the instants and revisions are null until known. */
    record Listing(java.time.Instant firstListedAt, UUID scannedRevisionId, int mechanicsMask, UUID mediaRevisionId, int mediaMask) { }

    /** A group of exercises of one published manifest slice: the mechanic, whether the revision pins audio, whether it is enabled, how many. */
    record ExerciseKind(String type, boolean audio, boolean enabled, int count) { }

    private final JdbcClient jdbc;

    ProjectionRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** Serializes the jobs of one deck for the rest of the transaction; another deck's job is not affected. */
    void lock(UUID deck) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended('catalog.project:' || :deck, 0))").param("deck", deck.toString()).query().listOfRows();
    }

    static final String PUBLISHED_SQL = """
            SELECT d.deck_id, d.reuse_scope_id, p.published_revision_id, r.exercises_root_id, r.exercise_count, r.title
              FROM app_learning.deck d
              JOIN app_learning.deck_publication p ON p.deck_id = d.deck_id
              JOIN app_learning.deck_revision r ON r.deck_id = p.deck_id AND r.revision_id = p.published_revision_id
             WHERE d.deck_id = :deck AND d.deleted_at IS NULL AND p.visibility = 'PUBLIC' AND NOT p.hidden_by_moderation""";

    /**
     * The deck as the catalogue wants it, or empty when it does not belong (not PUBLIC, no published revision, hidden by moderation, deleted), judged by this statement's own
     * snapshot. Nothing is locked: a deletion, a hiding or a lowering that commits after this read has deleted the row itself and enqueued a job of its own (see {@link CatalogOutbox}),
     * which runs after it and converges whatever this slice wrote.
     */
    Optional<Published> published(UUID deck) {
        return jdbc.sql(PUBLISHED_SQL).param("deck", deck).query((row, ignored) -> new Published(row.getObject("deck_id", UUID.class), row.getObject("reuse_scope_id", UUID.class),
                row.getObject("published_revision_id", UUID.class), row.getObject("exercises_root_id", UUID.class), row.getInt("exercise_count"),
                row.getString("title"))).optional();
    }

    /** Removes the deck from the catalogue. @return whether a row existed */
    boolean remove(UUID deck) {
        return jdbc.sql("DELETE FROM app_learning.public_deck WHERE deck_id = :deck").param("deck", deck).update() > 0;
    }

    /**
     * The top-level MIME types of the verified sources of the READY assets that the MATERIALS of the published revision reference ({@code published_media} is kept
     * by the publication command in the transaction that moves the pointer): one range of the deck's rows and a primary-key probe per asset.
     */
    Set<String> mediaTypes(UUID deck) {
        return new LinkedHashSet<>(jdbc.sql(MEDIA_TYPES_SQL).param("deck", deck).query(String.class).list());
    }

    static final String MEDIA_TYPES_SQL = """
            SELECT DISTINCT split_part(b.mime_type, '/', 1)
              FROM app_learning.published_media pm
              JOIN app_learning.media_asset a ON a.asset_id = pm.asset_id AND a.state = 'READY'
              JOIN app_learning.media_blob b ON b.blob_id = a.source_blob_id
             WHERE pm.deck_id = :deck""";

    /**
     * Makes sure the deck has its {@code catalog_listing} row and, the first time the published revision passes the catalogue threshold ({@code catalog_listed}, the very function of
     * the generated column), stamps {@code first_listed_at} with now. The stamp is set ONCE: an already stamped row is never touched, so demotion and promotion, hiding and republishing
     * cannot bring the deck back to the top of «Новое». Written only while the publication still names {@code published.revisionId()}.
     */
    void touchListing(Published published) {
        jdbc.sql(TOUCH_LISTING_SQL).param("deck", published.deckId()).param("revision", published.revisionId()).update();
    }

    static final String TOUCH_LISTING_SQL = """
            INSERT INTO app_learning.catalog_listing AS l (deck_id, first_listed_at)
            SELECT d.deck_id, CASE WHEN app_learning.catalog_listed(r.member_count, r.exercise_count, r.description) THEN statement_timestamp() END
              FROM app_learning.deck d
              JOIN app_learning.deck_publication p ON p.deck_id = d.deck_id
              JOIN app_learning.deck_revision r ON r.deck_id = p.deck_id AND r.revision_id = p.published_revision_id
             WHERE d.deck_id = :deck AND p.published_revision_id = :revision
               AND d.deleted_at IS NULL AND p.visibility = 'PUBLIC' AND NOT p.hidden_by_moderation
            ON CONFLICT (deck_id) DO UPDATE SET first_listed_at = EXCLUDED.first_listed_at
             WHERE l.first_listed_at IS NULL AND EXCLUDED.first_listed_at IS NOT NULL""";

    /** The deck's {@code catalog_listing} row, or empty when {@link #touchListing} wrote none (the publication moved). */
    Optional<Listing> listing(UUID deck) {
        return jdbc.sql("SELECT first_listed_at, scanned_revision_id, mechanics_mask, media_revision_id, media_mask FROM app_learning.catalog_listing WHERE deck_id = :deck")
                .param("deck", deck).query((row, ignored) -> new Listing(instant(row.getTimestamp("first_listed_at")), row.getObject("scanned_revision_id", UUID.class),
                        row.getInt("mechanics_mask"), row.getObject("media_revision_id", UUID.class), row.getInt("media_mask"))).optional();
    }

    private static java.time.Instant instant(java.sql.Timestamp value) { return value == null ? null : value.toInstant(); }

    /** Remembers the media bits of {@code revision}: they are recomputed only when the published revision changes, not on every edit of a tag. */
    void storeMedia(UUID deck, UUID revision, int mediaMask) {
        jdbc.sql("UPDATE app_learning.catalog_listing SET media_revision_id = :revision, media_mask = :mask WHERE deck_id = :deck")
                .param("revision", revision).param("mask", mediaMask).param("deck", deck).update();
    }

    /**
     * Creates or refreshes the row from the published revision {@code published.revisionId()}. The date, the media bits and the scan result (mechanics and the revision they were
     * scanned for) are COPIED from {@code catalog_listing}, so a row made again after a hiding or a lowering is complete at once when its revision was scanned before. Nothing is written
     * when nothing the row is built from differs, so a replayed job leaves no dead tuple and no new vector. The statement re-checks that the publication still names the revision
     * the caller read: if it moved, nothing is written and the job that the move enqueued does the work.
     */
    void upsert(Published published, String titleNorm) {
        jdbc.sql(UPSERT_SQL).param("deck", published.deckId()).param("revision", published.revisionId()).param("norm", titleNorm).update();
    }

    static final String UPSERT_SQL = """
            INSERT INTO app_learning.public_deck AS c (deck_id, public_code, owner_id, published_revision_id, title, description, topic_id, parent_topic_id,
                    content_language, target_language, level, tags, item_count, exercise_count, media_mask, mechanics_mask, projected_revision_id, first_listed_at,
                    content_updated_at, title_norm, projected_at)
            SELECT d.deck_id, p.public_code, d.owner_id, p.published_revision_id, r.title, r.description, p.topic_id, t.parent_id,
                   p.content_language, p.target_language, p.level, p.tags, r.member_count, r.exercise_count, l.media_mask, l.mechanics_mask, l.scanned_revision_id,
                   l.first_listed_at, p.published_at, :norm, statement_timestamp()
              FROM app_learning.deck d
              JOIN app_learning.deck_publication p ON p.deck_id = d.deck_id
              JOIN app_learning.deck_revision r ON r.deck_id = p.deck_id AND r.revision_id = p.published_revision_id
              JOIN app_learning.catalog_listing l ON l.deck_id = d.deck_id
              LEFT JOIN app_learning.topic t ON t.topic_id = p.topic_id
             WHERE d.deck_id = :deck AND p.published_revision_id = :revision
               AND d.deleted_at IS NULL AND p.visibility = 'PUBLIC' AND NOT p.hidden_by_moderation
            ON CONFLICT (deck_id) DO UPDATE SET
                   public_code = EXCLUDED.public_code, owner_id = EXCLUDED.owner_id, published_revision_id = EXCLUDED.published_revision_id, title = EXCLUDED.title,
                   description = EXCLUDED.description, topic_id = EXCLUDED.topic_id, parent_topic_id = EXCLUDED.parent_topic_id,
                   content_language = EXCLUDED.content_language, target_language = EXCLUDED.target_language, level = EXCLUDED.level, tags = EXCLUDED.tags,
                   item_count = EXCLUDED.item_count, exercise_count = EXCLUDED.exercise_count, media_mask = EXCLUDED.media_mask, mechanics_mask = EXCLUDED.mechanics_mask,
                   projected_revision_id = EXCLUDED.projected_revision_id, first_listed_at = EXCLUDED.first_listed_at,
                   content_updated_at = EXCLUDED.content_updated_at, title_norm = EXCLUDED.title_norm, projected_at = EXCLUDED.projected_at
             WHERE (c.public_code, c.owner_id, c.published_revision_id, c.title, c.description, c.topic_id, c.parent_topic_id, c.content_language, c.target_language,
                    c.level, c.tags, c.item_count, c.exercise_count, c.media_mask, c.mechanics_mask, c.projected_revision_id, c.first_listed_at, c.content_updated_at, c.title_norm)
                   IS DISTINCT FROM
                   (EXCLUDED.public_code, EXCLUDED.owner_id, EXCLUDED.published_revision_id, EXCLUDED.title, EXCLUDED.description, EXCLUDED.topic_id,
                    EXCLUDED.parent_topic_id, EXCLUDED.content_language, EXCLUDED.target_language, EXCLUDED.level, EXCLUDED.tags, EXCLUDED.item_count,
                    EXCLUDED.exercise_count, EXCLUDED.media_mask, EXCLUDED.mechanics_mask, EXCLUDED.projected_revision_id, EXCLUDED.first_listed_at,
                    EXCLUDED.content_updated_at, EXCLUDED.title_norm)""";

    /** The revisions the deck's row was derived from, or empty when it has no row. */
    Optional<Projected> projected(UUID deck) {
        return jdbc.sql("SELECT published_revision_id, projected_revision_id, listed FROM app_learning.public_deck WHERE deck_id = :deck").param("deck", deck)
                .query((row, ignored) -> new Projected(row.getObject("published_revision_id", UUID.class), row.getObject("projected_revision_id", UUID.class), row.getBoolean("listed"))).optional();
    }

    /**
     * Groups the exercises that the given manifest entries stand for by what the catalogue asks about them. The pairs {@code (exercise, descriptor root)} are matched EXACTLY
     * (a descriptor object can be shared by revisions of different exercises, so two independent {@code IN} lists could match a pair no manifest holds). The audio flag is an
     * {@code EXISTS} on the primary key of {@code exercise_media_ref} (scope, exercise, revision).
     */
    List<ExerciseKind> exerciseKinds(UUID scope, List<Entry> entries) {
        ArrayNode pairs = JSON.createArrayNode();
        for (Entry entry : entries) pairs.addObject().put("k", entry.key().toString()).put("d", entry.target().objectId().toString());
        return jdbc.sql(KINDS_SQL).param("pairs", pairs.toString()).param("scope", scope)
                .query((row, ignored) -> new ExerciseKind(row.getString("exercise_type"), row.getBoolean("audio"), row.getBoolean("enabled"), row.getInt("n"))).list();
    }

    /**
     * The join order is PINNED instead of left to the planner: the pairs drive a lateral lookup of the revision by the primary key prefix {@code (scope, exercise)}, fenced with
     * {@code OFFSET 0}. A planner that flattened it could read every exercise of the whole lineage for each chunk of 100 entries and hash-join the pairs against it, which for a
     * deck of 10^5 exercises is a quadratic scan (the same fence as the media probes of Share/9).
     */
    static final String KINDS_SQL = """
            SELECT x.exercise_type, x.enabled, x.audio, count(*)::integer AS n
              FROM jsonb_to_recordset(CAST(:pairs AS jsonb)) AS m(k uuid, d uuid)
             CROSS JOIN LATERAL (
                   SELECT r.exercise_type, r.enabled,
                          EXISTS (SELECT 1 FROM app_learning.exercise_media_ref ref
                                   WHERE ref.reuse_scope_id = r.reuse_scope_id AND ref.exercise_id = r.exercise_id
                                     AND ref.exercise_revision_id = r.revision_id AND ref.media_kind = 'audio') AS audio
                     FROM app_learning.exercise_revision r
                    WHERE r.reuse_scope_id = :scope AND r.exercise_id = m.k AND r.descriptor_root_id = m.d
                   OFFSET 0) x
             GROUP BY x.exercise_type, x.enabled, x.audio""";

    /**
     * Records the result of a finished scan: in {@code catalog_listing}, keyed by the revision it was done for (a fact about that revision, kept whatever happens to the row),
     * and in the deck's row, only while the row still shows that revision.
     *
     * @return whether the row was written
     */
    boolean finishScan(UUID deck, UUID revision, int mechanicsMask) {
        jdbc.sql("""
                UPDATE app_learning.catalog_listing SET scanned_revision_id = :revision, mechanics_mask = :mask, scanned_at = statement_timestamp()
                 WHERE deck_id = :deck
                """).param("mask", mechanicsMask).param("revision", revision).param("deck", deck).update();
        return jdbc.sql("""
                UPDATE app_learning.public_deck
                   SET mechanics_mask = :mask, projected_revision_id = :revision, projected_at = statement_timestamp()
                 WHERE deck_id = :deck AND published_revision_id = :revision
                """).param("mask", mechanicsMask).param("revision", revision).param("deck", deck).update() > 0;
    }

    /** Rows whose scan is behind their revision for longer than {@code age}: the lag of the projection (the partial index {@code public_deck_lag} holds only these). */
    long lagging(java.time.Duration age) {
        return jdbc.sql(LAGGING_SQL).param("age", java.sql.Timestamp.from(java.time.Instant.now().minus(age))).query(Long.class).single();
    }

    static final String LAGGING_SQL = """
            SELECT count(*) FROM app_learning.public_deck
             WHERE projected_revision_id IS DISTINCT FROM published_revision_id AND projected_at < :age""";

    /** The next page of the rebuild: decks in id order that are PUBLIC or have a catalogue row (a stale one to remove). */
    List<UUID> publicDecksAfter(UUID after, int limit) {
        return jdbc.sql(REBUILD_PAGE_SQL).param("after", after == null ? NIL : after).param("limit", limit).query(UUID.class).list();
    }

    /** A join, not a subquery over the catalogue: both sides are read in primary-key order and the page stops at its limit, whatever the size of {@code public_deck}. */
    static final String REBUILD_PAGE_SQL = """
            SELECT p.deck_id
              FROM app_learning.deck_publication p
              LEFT JOIN app_learning.public_deck c ON c.deck_id = p.deck_id
             WHERE p.deck_id > :after AND (p.visibility = 'PUBLIC' OR c.deck_id IS NOT NULL)
             ORDER BY p.deck_id LIMIT :limit""";
}
