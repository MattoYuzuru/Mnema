package app.mnema.learning.generation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Read-only queries behind the prompt context of a draft: the deck brief, the material outline (from the
 * {@code item_preview} titles, never from full documents), the exemplar flag and the optional {@code pg_trgm} ranking.
 * The trigram functions are used only when the extension exists in this database ({@link #trigram}); without it the
 * top-K part of a large outline and the similar-title warning are skipped, and the rest works unchanged.
 */
@Repository
class ContextRepository {
    private static final Logger LOG = LoggerFactory.getLogger(ContextRepository.class);
    private static final Pattern IDENTIFIER = Pattern.compile("[a-z_][a-z0-9_]{0,62}");

    /** The deck's current title, description and counts. */
    record Brief(String title, String description, int items, int exercises) { }

    /** One current material: its head revision, where its content lives and its cached preview title (null if not cached). */
    record Head(UUID memberKey, UUID revisionId, UUID scopeId, UUID contentRootId, String title, boolean starred) { }

    /** A current objective of a material: its revision, its title and the mechanics of the enabled exercises that evidence it. */
    record ObjectiveLine(UUID objectiveId, UUID revisionId, String title, List<String> types) { }

    /** The content of a current enabled exercise of a material: its mechanic and the JSON of its content. */
    record ExerciseLine(String type, String content) { }

    /** A material whose title resembles another one. */
    record Similar(UUID memberKey, String title, double score) { }

    private final JdbcClient jdbc;
    private volatile Optional<String> trigramSchema;

    ContextRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<Brief> brief(UUID owner, UUID deck) {
        return jdbc.sql("SELECT r.title,r.description,r.member_count,r.exercise_count FROM app_learning.deck d "
                        + "JOIN app_learning.deck_revision r ON r.deck_id=d.deck_id AND r.revision_id=d.head_revision_id "
                        + "WHERE d.deck_id=:deck AND d.owner_id=:owner AND d.deleted_at IS NULL")
                .param("deck", deck).param("owner", owner).query((row, ignored) -> new Brief(row.getString("title"),
                        row.getString("description"), row.getInt("member_count"), row.getInt("exercise_count"))).optional();
    }

    private static final String HEADS = "SELECT h.member_key,h.revision_id,r.reuse_scope_id,r.content_root_id,p.title,"
            + "EXISTS(SELECT 1 FROM app_learning.deck_item_exemplar e WHERE e.deck_id=h.deck_id AND e.member_key=h.member_key) AS starred "
            + "FROM app_learning.deck_head_item h JOIN app_learning.item_revision r ON r.deck_id=h.deck_id "
            + "AND r.member_key=h.member_key AND r.revision_id=h.revision_id LEFT JOIN app_learning.item_preview p "
            + "ON p.deck_id=h.deck_id AND p.member_key=h.member_key AND p.revision_id=h.revision_id WHERE h.deck_id=:deck";

    private static Head head(java.sql.ResultSet row) throws java.sql.SQLException {
        return new Head(row.getObject("member_key", UUID.class), row.getObject("revision_id", UUID.class),
                row.getObject("reuse_scope_id", UUID.class), row.getObject("content_root_id", UUID.class), row.getString("title"),
                row.getBoolean("starred"));
    }

    int headCount(UUID deck) {
        return jdbc.sql("SELECT count(*)::integer FROM app_learning.deck_head_item WHERE deck_id=:deck").param("deck", deck)
                .query(Integer.class).single();
    }

    /** Current materials, most recently changed first. */
    List<Head> latest(UUID deck, int limit) {
        return jdbc.sql(HEADS + " ORDER BY h.updated_at DESC,h.member_key LIMIT :limit").param("deck", deck).param("limit", limit)
                .query((row, ignored) -> head(row)).list();
    }

    /** Every starred material, the most recently marked first. */
    List<Head> starred(UUID deck, int limit) {
        return jdbc.sql(HEADS + " AND EXISTS(SELECT 1 FROM app_learning.deck_item_exemplar s WHERE s.deck_id=h.deck_id "
                        + "AND s.member_key=h.member_key) ORDER BY (SELECT s.marked_at FROM app_learning.deck_item_exemplar s "
                        + "WHERE s.deck_id=h.deck_id AND s.member_key=h.member_key) DESC,h.member_key LIMIT :limit")
                .param("deck", deck).param("limit", limit).query((row, ignored) -> head(row)).list();
    }

    /** Enabled current exercises per material (a material without any is absent from the map). */
    Map<UUID, Integer> exerciseCounts(UUID deck, Collection<UUID> members) {
        Map<UUID, Integer> result = new HashMap<>();
        if (members.isEmpty()) return result;
        jdbc.sql("SELECT binding.member_key,count(*)::integer AS n FROM app_learning.exercise_content_binding binding "
                        + "JOIN app_learning.deck_head_exercise head ON head.deck_id=binding.deck_id "
                        + "AND head.exercise_id=binding.exercise_id AND head.revision_id=binding.exercise_revision_id "
                        + "JOIN app_learning.exercise_revision revision ON revision.deck_id=head.deck_id "
                        + "AND revision.exercise_id=head.exercise_id AND revision.revision_id=head.revision_id AND revision.enabled "
                        + "WHERE binding.deck_id=:deck AND binding.role='ASSESSED' AND binding.member_key IN (:members) "
                        + "GROUP BY binding.member_key").param("deck", deck).param("members", members)
                .query((row, ignored) -> result.put(row.getObject("member_key", UUID.class), row.getInt("n"))).list();
        return result;
    }

    /** The current heads of these materials (those that are still current materials of the deck), in no particular order. */
    List<Head> heads(UUID deck, Collection<UUID> members) {
        if (members.isEmpty()) return List.of();
        return jdbc.sql(HEADS + " AND h.member_key IN (:members)").param("deck", deck).param("members", members)
                .query((row, ignored) -> head(row)).list();
    }

    /** Enabled current exercises per material and mechanic ({@code memberKey -> {mechanic -> count}}); a material without any is absent. */
    Map<UUID, Map<String, Integer>> mechanicCounts(UUID deck, Collection<UUID> members) {
        Map<UUID, Map<String, Integer>> result = new HashMap<>();
        if (members.isEmpty()) return result;
        jdbc.sql("SELECT binding.member_key,revision.exercise_type,count(*)::integer AS n FROM app_learning.exercise_content_binding binding "
                        + "JOIN app_learning.deck_head_exercise head ON head.deck_id=binding.deck_id "
                        + "AND head.exercise_id=binding.exercise_id AND head.revision_id=binding.exercise_revision_id "
                        + "JOIN app_learning.exercise_revision revision ON revision.deck_id=head.deck_id "
                        + "AND revision.exercise_id=head.exercise_id AND revision.revision_id=head.revision_id AND revision.enabled "
                        + "WHERE binding.deck_id=:deck AND binding.role='ASSESSED' AND binding.member_key IN (:members) "
                        + "GROUP BY binding.member_key,revision.exercise_type ORDER BY binding.member_key,revision.exercise_type")
                .param("deck", deck).param("members", members)
                .query((row, ignored) -> result.computeIfAbsent(row.getObject("member_key", UUID.class), key -> new java.util.LinkedHashMap<>())
                        .put(row.getString("exercise_type"), row.getInt("n"))).list();
        return result;
    }

    /** The current objectives bound to a material, oldest first, with the mechanics of the exercises that evidence each one. */
    List<ObjectiveLine> objectives(UUID deck, UUID member, int limit) {
        return jdbc.sql("SELECT o.objective_id,r.revision_id,r.descriptor ->> 'title' AS title,"
                        + "COALESCE((SELECT string_agg(DISTINCT er.exercise_type, ',') FROM app_learning.exercise_content_binding b "
                        + "JOIN app_learning.deck_head_exercise he ON he.deck_id=b.deck_id AND he.exercise_id=b.exercise_id "
                        + "AND he.revision_id=b.exercise_revision_id JOIN app_learning.exercise_revision er ON er.deck_id=he.deck_id "
                        + "AND er.exercise_id=he.exercise_id AND er.revision_id=he.revision_id AND er.enabled "
                        + "WHERE b.deck_id=o.deck_id AND b.role='ASSESSED' AND b.objective_id=o.objective_id),'') AS types "
                        + "FROM app_learning.memory_objective o JOIN app_learning.objective_head h ON h.deck_id=o.deck_id "
                        + "AND h.objective_id=o.objective_id JOIN app_learning.objective_revision r ON r.deck_id=h.deck_id "
                        + "AND r.objective_id=h.objective_id AND r.revision_id=h.revision_id "
                        + "WHERE o.deck_id=:deck AND o.member_key=:member ORDER BY o.created_at,o.objective_id LIMIT :limit")
                .param("deck", deck).param("member", member).param("limit", limit)
                .query((row, ignored) -> new ObjectiveLine(row.getObject("objective_id", UUID.class),
                        row.getObject("revision_id", UUID.class), row.getString("title"),
                        row.getString("types").isEmpty() ? List.<String>of() : List.of(row.getString("types").split(",")))).list();
    }

    /** The newest current enabled exercises assessed on a material (the model must not repeat them), newest first. */
    List<ExerciseLine> exercises(UUID deck, UUID member, int limit) {
        return jdbc.sql("SELECT er.exercise_type,er.content::text AS content FROM app_learning.exercise_content_binding b "
                        + "JOIN app_learning.deck_head_exercise he ON he.deck_id=b.deck_id AND he.exercise_id=b.exercise_id "
                        + "AND he.revision_id=b.exercise_revision_id JOIN app_learning.exercise_revision er ON er.deck_id=he.deck_id "
                        + "AND er.exercise_id=he.exercise_id AND er.revision_id=he.revision_id AND er.enabled "
                        + "WHERE b.deck_id=:deck AND b.role='ASSESSED' AND b.member_key=:member ORDER BY he.ordinal DESC LIMIT :limit")
                .param("deck", deck).param("member", member).param("limit", limit)
                .query((row, ignored) -> new ExerciseLine(row.getString("exercise_type"), row.getString("content"))).list();
    }

    /**
     * The materials whose cached title best matches {@code query} (word similarity), best first; empty without
     * {@code pg_trgm}. Materials without a cached preview title are not ranked: Browse fills the cache lazily.
     */
    List<Head> closest(UUID deck, String query, int limit, Collection<UUID> excluded) {
        Optional<String> schema = trigram();
        if (schema.isEmpty() || limit < 1 || query.isBlank()) return List.of();
        String sql = HEADS + " AND p.title IS NOT NULL" + (excluded.isEmpty() ? "" : " AND h.member_key NOT IN (:excluded)")
                + " ORDER BY " + schema.get() + ".word_similarity(p.title,:query) DESC,h.member_key LIMIT :limit";
        var statement = jdbc.sql(sql).param("deck", deck).param("query", query).param("limit", limit);
        if (!excluded.isEmpty()) statement = statement.param("excluded", excluded);
        return statement.query((row, ignored) -> head(row)).list();
    }

    /** The current material of the deck whose title is most similar to {@code title}, if above {@code threshold}. */
    Optional<Similar> similarTitle(UUID deck, String title, double threshold) {
        Optional<String> schema = trigram();
        if (schema.isEmpty() || title.isBlank()) return Optional.empty();
        String function = schema.get() + ".similarity(p.title,:title)";
        return jdbc.sql("SELECT h.member_key,p.title," + function + " AS score FROM app_learning.deck_head_item h "
                        + "JOIN app_learning.item_preview p ON p.deck_id=h.deck_id AND p.member_key=h.member_key "
                        + "AND p.revision_id=h.revision_id WHERE h.deck_id=:deck AND " + function + ">=:threshold "
                        + "ORDER BY score DESC,h.member_key LIMIT 1")
                .param("deck", deck).param("title", title).param("threshold", threshold)
                .query((row, ignored) -> new Similar(row.getObject("member_key", UUID.class), row.getString("title"),
                        row.getDouble("score"))).optional();
    }

    /** The schema that holds {@code pg_trgm}, or empty when the extension is not installed (checked once). */
    Optional<String> trigram() {
        Optional<String> known = trigramSchema;
        if (known != null) return known;
        Optional<String> found = jdbc.sql("SELECT n.nspname FROM pg_extension e JOIN pg_namespace n ON n.oid=e.extnamespace "
                        + "WHERE e.extname='pg_trgm'").query(String.class).optional().filter(name -> IDENTIFIER.matcher(name).matches());
        if (found.isEmpty()) {
            LOG.info("generation_pg_trgm_unavailable top_k_outline=skipped similar_title_warning=skipped");
        }
        trigramSchema = found;
        return found;
    }
}
