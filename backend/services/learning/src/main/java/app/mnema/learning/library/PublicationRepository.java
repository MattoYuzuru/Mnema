package app.mnema.learning.library;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SQL of the owner's publication: the O(1) publication row, the journal range after the published revision, the titles of a few materials and the
 * non-commercial media check. Reads of the head touch the deck row and one revision row; nothing here is proportional to the number of copies.
 */
@Repository
class PublicationRepository {
    /**
     * The licenses that bar a deck from «Публичная» (architecture section 8), matched case-insensitively: a CC "NC" token ({@code CC BY-NC 4.0}, {@code cc-by-nc-4.0}) or
     * the words non-commercial ({@code NON-COMMERCIAL}). The {@code ~*} filter below and the jsonpath {@code flag "i"} of the partial index of V54 are the same regex and
     * must stay identical.
     */
    static final String NC_REGEX = "(^|[^A-Za-z])nc([^A-Za-z]|$)|non[- ]?commercial";
    /** The predicate of the partial index {@code generation_provenance_nc}, written as the index has it (a literal path, so the planner can prove the match). */
    static final String NC_PREDICATE = "jsonb_path_exists(p.media, '$[*] ? (@.license like_regex \"" + NC_REGEX + "\" flag \"i\")')";
    static final int MAX_BLOCKED = 50;

    /** The publication row with its metadata. */
    record Row(UUID deckId, DeckVisibility visibility, String publicCode, UUID publishedRevisionId, Instant publishedAt, long rowVersion,
               PublicationMetadata metadata, String releaseNote, boolean requestsEnabled) {
        /** The version on the wire: the stored version plus one, so that "0" can mean "no row yet". */
        long wireVersion() { return rowVersion + 1; }
    }

    /** The deck head the owner sees. */
    record Head(UUID deckId, UUID revisionId, long sequence, String title, String description, int memberCount, int exerciseCount) { }

    /** A revision of the deck: its place in the journal and its readable fields. */
    record Revision(UUID revisionId, long sequence, String title, String description) { }

    record Topic(String topicId, String parentId, String nameRu, String nameEn, int ordinal) { }

    record Alias(String aliasNorm, String topicId) { }

    /** A material ({@code kind = item}) or exercise ({@code kind = exercise}) of the head that uses blocked media. */
    record Blocked(String kind, UUID subjectId) { }

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final RowMapper<Row> ROW = (row, ignored) -> new Row(row.getObject("deck_id", UUID.class),
            DeckVisibility.valueOf(row.getString("visibility")), row.getString("public_code"), row.getObject("published_revision_id", UUID.class),
            instant(row.getTimestamp("published_at")), row.getLong("row_version"),
            new PublicationMetadata(row.getString("topic_id"), row.getString("content_language"), row.getString("target_language"),
                    row.getString("level"), tags(row)), row.getString("release_note"), row.getBoolean("requests_enabled"));

    private final JdbcClient jdbc;

    PublicationRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }

    private static List<String> tags(java.sql.ResultSet row) throws SQLException {
        java.sql.Array array = row.getArray("tags");
        return array == null ? List.of() : List.of((String[]) array.getArray());
    }

    /**
     * The owner's live deck with the fields of its head revision. {@code lock} takes a share lock on the deck row so that the head cannot move under the command.
     * Two statements on purpose: the lock is taken on the deck row ALONE. A join under {@code FOR SHARE} is re-evaluated after a wait for a concurrent editor save
     * against the row's new version, and its revision row may not be visible to that statement, which would answer 404 for a deck that exists. The revision is read by
     * the second statement, after the lock, with a fresh snapshot.
     */
    Optional<Head> head(UUID owner, UUID deck, boolean lock) {
        Optional<UUID> revision = jdbc.sql("SELECT head_revision_id FROM app_learning.deck WHERE deck_id = :deck AND owner_id = :owner AND deleted_at IS NULL"
                + (lock ? " FOR SHARE" : "")).param("deck", deck).param("owner", owner).query(UUID.class).optional();
        if (revision.isEmpty()) return Optional.empty();
        return jdbc.sql("""
                SELECT revision_id, sequence, title, description, member_count, exercise_count FROM app_learning.deck_revision
                 WHERE deck_id = :deck AND revision_id = :revision
                """).param("deck", deck).param("revision", revision.orElseThrow())
                .query((row, ignored) -> new Head(deck, row.getObject("revision_id", UUID.class), row.getLong("sequence"), row.getString("title"),
                        row.getString("description"), row.getInt("member_count"), row.getInt("exercise_count"))).optional();
    }

    Optional<Revision> revision(UUID deck, UUID revision) {
        return jdbc.sql("SELECT revision_id, sequence, title, description FROM app_learning.deck_revision WHERE deck_id = :deck AND revision_id = :revision")
                .param("deck", deck).param("revision", revision)
                .query((row, ignored) -> new Revision(row.getObject("revision_id", UUID.class), row.getLong("sequence"), row.getString("title"),
                        row.getString("description"))).optional();
    }

    /** The publication row; {@code lock} makes it the serialization point of a write. */
    Optional<Row> row(UUID deck, boolean lock) {
        return jdbc.sql("SELECT * FROM app_learning.deck_publication WHERE deck_id = :deck" + (lock ? " FOR UPDATE" : ""))
                .param("deck", deck).query(ROW).optional();
    }

    /** Creates the row; empty when another request created it first (the caller's version is stale). */
    Optional<Row> insert(UUID deck, DeckVisibility visibility, String code, UUID revision, PublicationMetadata metadata, String releaseNote,
                         boolean requestsEnabled) {
        return jdbc.sql("""
                INSERT INTO app_learning.deck_publication(deck_id, visibility, public_code, code_rotated_at, published_revision_id, published_at,
                        row_version, created_at, updated_at, topic_id, content_language, target_language, level, tags, release_note, requests_enabled)
                VALUES (:deck, :visibility, :code, statement_timestamp(), CAST(:revision AS uuid),
                        CASE WHEN CAST(:revision AS uuid) IS NULL THEN NULL ELSE statement_timestamp() END, 0, statement_timestamp(), statement_timestamp(),
                        :topic, :content, :target, :level, ARRAY(SELECT t FROM jsonb_array_elements_text(CAST(:tags AS jsonb)) WITH ORDINALITY AS e(t, n) ORDER BY n),
                        :note, :requests)
                ON CONFLICT (deck_id) DO NOTHING
                RETURNING *
                """).param("deck", deck).param("visibility", visibility.name()).param("code", code).param("revision", revision, java.sql.Types.OTHER)
                .param("topic", metadata.topicId()).param("content", metadata.contentLanguage()).param("target", metadata.targetLanguage())
                .param("level", metadata.level()).param("tags", JSON.writeValueAsString(metadata.tags())).param("note", releaseNote)
                .param("requests", requestsEnabled).query(ROW).optional();
    }

    /**
     * Writes the new state at the expected stored version; a non-null {@code newCode} rotates the public code and {@code moves} stamps a new publication
     * time. Empty when the version moved.
     */
    Optional<Row> update(UUID deck, long expectedRowVersion, DeckVisibility visibility, String newCode, UUID revision, boolean moves,
                         PublicationMetadata metadata, String releaseNote, boolean requestsEnabled) {
        return jdbc.sql("""
                UPDATE app_learning.deck_publication
                   SET visibility = :visibility,
                       public_code = COALESCE(CAST(:code AS text), public_code),
                       code_rotated_at = CASE WHEN CAST(:code AS text) IS NULL THEN code_rotated_at ELSE statement_timestamp() END,
                       published_revision_id = CAST(:revision AS uuid),
                       published_at = CASE WHEN :moves THEN statement_timestamp() ELSE published_at END,
                       topic_id = :topic, content_language = :content, target_language = :target, level = :level,
                       tags = ARRAY(SELECT t FROM jsonb_array_elements_text(CAST(:tags AS jsonb)) WITH ORDINALITY AS e(t, n) ORDER BY n),
                       release_note = :note, requests_enabled = :requests,
                       row_version = row_version + 1, updated_at = statement_timestamp()
                 WHERE deck_id = :deck AND row_version = :expected
                RETURNING *
                """).param("visibility", visibility.name()).param("code", newCode, java.sql.Types.VARCHAR).param("revision", revision, java.sql.Types.OTHER)
                .param("moves", moves).param("topic", metadata.topicId()).param("content", metadata.contentLanguage())
                .param("target", metadata.targetLanguage()).param("level", metadata.level()).param("tags", JSON.writeValueAsString(metadata.tags()))
                .param("note", releaseNote).param("requests", requestsEnabled).param("deck", deck).param("expected", expectedRowVersion)
                .query(ROW).optional();
    }

    /** Appends one outbox row for the catalog; the id is a uuidv7 drawn by the database. */
    void appendEvent(UUID deck, UUID revision, DeckVisibility visibility) {
        jdbc.sql("""
                INSERT INTO app_learning.deck_publication_event(deck_id, published_revision_id, visibility, occurred_at)
                VALUES (:deck, :revision, :visibility, statement_timestamp())
                """).param("deck", deck).param("revision", revision).param("visibility", visibility.name()).update();
    }

    /** Distinct materials plus distinct exercises changed after the given sequence of the deck: two index range scans. */
    int changesAfter(UUID deck, long sequence) {
        return jdbc.sql("""
                SELECT (SELECT count(DISTINCT member_key) FROM app_learning.deck_item_change WHERE deck_id = :deck AND deck_sequence > :sequence)
                     + (SELECT count(DISTINCT exercise_id) FROM app_learning.deck_exercise_change WHERE deck_id = :deck AND deck_sequence > :sequence)
                """).param("deck", deck).param("sequence", sequence).query(Integer.class).single();
    }

    /** Cached titles of up to {@code limit} head materials (the preview cache is filled by the owner's reads; a missing title is skipped). */
    List<String> headTitles(UUID deck, int limit) {
        return jdbc.sql("""
                SELECT p.title FROM app_learning.deck_head_item h
                  JOIN app_learning.item_preview p ON p.reuse_scope_id = h.reuse_scope_id AND p.member_key = h.member_key AND p.revision_id = h.revision_id
                 WHERE h.deck_id = :deck ORDER BY h.member_key LIMIT :limit
                """).param("deck", deck).param("limit", limit).query(String.class).list();
    }

    /**
     * The materials and exercises of the deck's head that use stock media under a non-commercial license (generation provenance of the owner). It starts
     * from the owner's provenance rows that name such a license (the partial index {@code generation_provenance_nc}; none for almost everybody), then probes
     * the media references of those assets, then the head.
     *
     * <p>Keyed by {@code owner_id}: provenance is the author's record of what HE added. When copies exist (Share/9/10) a copy owner's deck references the author's
     * assets without provenance rows of his own, so this lookup must be re-keyed by asset (the provenance of the asset's owner) before copies can be published.
     *
     * <p>V52-safe: the exercise head is joined by {@code (exercise_id, revision_id)} and filtered by {@code deck_id}, columns that exist before and after V52 (exercise ids are
     * UUIDs; under V52 the strict form adds {@code h.reuse_scope_id = r.reuse_scope_id}, which V53 does not have yet), and the media owner column is not read.
     */
    static final String BLOCKED_MEDIA_SQL = """
            WITH nc AS (
                SELECT DISTINCT CASE WHEN m.value ->> 'assetId' ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
                                     THEN CAST(m.value ->> 'assetId' AS uuid) END AS asset_id
                  FROM app_learning.generation_provenance p
                 CROSS JOIN LATERAL jsonb_array_elements(p.media) AS m(value)
                 WHERE p.owner_id = :owner
                   AND\s""" + NC_PREDICATE + """

                   AND (m.value ->> 'license') ~* :regex)
            SELECT 'item' AS kind, h.member_key AS subject
              FROM nc JOIN app_learning.content_media_ref r ON r.asset_id = nc.asset_id
              JOIN app_learning.deck_head_item h ON h.reuse_scope_id = r.reuse_scope_id AND h.member_key = r.member_key AND h.revision_id = r.revision_id
             WHERE h.deck_id = :deck
            UNION ALL
            SELECT 'exercise' AS kind, h.exercise_id AS subject
              FROM nc JOIN app_learning.exercise_media_ref r ON r.asset_id = nc.asset_id
              JOIN app_learning.deck_head_exercise h ON h.exercise_id = r.exercise_id AND h.revision_id = r.exercise_revision_id
             WHERE h.deck_id = :deck
             LIMIT :limit
            """;

    List<Blocked> blockedMedia(UUID owner, UUID deck, int limit) {
        return jdbc.sql(BLOCKED_MEDIA_SQL).param("owner", owner).param("deck", deck).param("regex", NC_REGEX).param("limit", limit)
                .query((row, ignored) -> new Blocked(row.getString("kind"), row.getObject("subject", UUID.class))).list();
    }

    List<Topic> topics() {
        return jdbc.sql("SELECT topic_id, parent_id, name_ru, name_en, ordinal FROM app_learning.topic ORDER BY ordinal, topic_id")
                .query((row, ignored) -> new Topic(row.getString("topic_id"), row.getString("parent_id"), row.getString("name_ru"), row.getString("name_en"),
                        row.getInt("ordinal"))).list();
    }

    List<Alias> aliases() {
        return jdbc.sql("SELECT alias_norm, topic_id FROM app_learning.topic_alias ORDER BY alias_norm, topic_id")
                .query((row, ignored) -> new Alias(row.getString("alias_norm"), row.getString("topic_id"))).list();
    }
}
