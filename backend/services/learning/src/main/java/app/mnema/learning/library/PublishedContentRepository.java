package app.mnema.learning.library;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only lookups that resolve what a published manifest points at. Every statement starts from the immutable roots of the published revision (or from
 * keys the manifest itself produced), never from a deck's head or journal, so a later edit of the author cannot change what a non-owner reads.
 *
 * <p>The exercise statement reads the deck-keyed exercise tables as of V51; it is the one place to follow when exercises move to lineage keys (Share/5).
 */
@Repository
class PublishedContentRepository {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcClient jdbc;

    PublishedContentRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** The revision of a material that a manifest entry (member, descriptor root) stands for. */
    record ItemRevision(UUID memberKey, UUID descriptorRootId, UUID revisionId, UUID contentRootId) { }

    List<ItemRevision> itemRevisions(UUID scope, Collection<UUID> members, Collection<UUID> descriptors) {
        if (members.isEmpty()) return List.of();
        return jdbc.sql("""
                SELECT member_key, descriptor_root_id, revision_id, content_root_id FROM app_learning.item_revision
                 WHERE reuse_scope_id = :scope AND member_key IN (:members) AND descriptor_root_id IN (:descriptors)
                """).param("scope", scope).param("members", members).param("descriptors", descriptors)
                .query((row, ignored) -> new ItemRevision(row.getObject("member_key", UUID.class),
                        row.getObject("descriptor_root_id", UUID.class), row.getObject("revision_id", UUID.class),
                        row.getObject("content_root_id", UUID.class))).list();
    }

    /** A material's place in a members manifest: its authoring ordinal and the descriptor root the manifest holds for it. */
    record Located(int ordinal, UUID descriptorRootId) { }

    /**
     * Finds one material by key in a members manifest. One recursive traversal of the manifest pages of the root (the same one the owner's single-item read
     * uses); it never follows descriptors, content or any other root, so a member that is not in THIS manifest is absent.
     */
    Optional<Located> locate(UUID scope, UUID root, int count, UUID member) {
        return jdbc.sql("""
                WITH RECURSIVE pages(object_id,tree_height,base,expected_count) AS (
                    SELECT CAST(:root AS uuid),(root.payload->>'treeHeight')::integer,0::bigint,CAST(:count AS bigint)
                      FROM app_learning.storage_object root
                     WHERE root.reuse_scope_id=:scope AND root.object_id=:root AND root.sealed
                       AND root.kind='page' AND root.encoding_version=1
                       AND root.payload->>'role'='members' AND root.payload->>'codec'='1'
                       AND root.dag_rank=:leafRank+(root.payload->>'treeHeight')::integer
                    UNION ALL
                    SELECT child.object_id,parent.tree_height-1,parent.base+link.prior_count,link.child_count
                      FROM pages parent
                      JOIN app_learning.storage_object page ON page.reuse_scope_id=:scope AND page.object_id=parent.object_id
                      JOIN LATERAL (
                          SELECT edge.child_id,edge.logical_key,
                                 (page.payload->'counts'->edge.ordinal)::bigint AS child_count,
                                 COALESCE(sum((page.payload->'counts'->edge.ordinal)::bigint) OVER
                                   (ORDER BY edge.ordinal ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING),0)::bigint AS prior_count,
                                 sum((page.payload->'counts'->edge.ordinal)::bigint) OVER () AS total_count
                            FROM app_learning.storage_edge edge
                           WHERE edge.reuse_scope_id=:scope AND edge.parent_id=parent.object_id
                      ) link ON link.logical_key IS NULL AND link.child_count>0 AND link.total_count=parent.expected_count
                      JOIN app_learning.storage_object child ON child.reuse_scope_id=:scope AND child.object_id=link.child_id
                       AND child.sealed AND child.kind='page' AND child.encoding_version=1
                       AND child.payload->>'role'='members' AND child.payload->>'codec'='1'
                       AND (child.payload->>'treeHeight')::integer=parent.tree_height-1
                       AND child.dag_rank=:leafRank+parent.tree_height-1
                     WHERE parent.tree_height>0 AND parent.tree_height<=:maxHeight
                       AND jsonb_array_length(page.payload->'counts')=page.edge_count
                )
                SELECT (page.base+edge.ordinal)::integer AS ordinal, edge.child_id AS descriptor_id FROM pages page
                  JOIN app_learning.storage_edge edge ON edge.reuse_scope_id=:scope AND edge.parent_id=page.object_id
                 WHERE page.tree_height=0 AND edge.logical_key=:member AND page.base+edge.ordinal<:count
                """).param("root", root).param("scope", scope).param("count", count).param("member", member)
                .param("leafRank", ManifestPages.MEMBERS.leafRank()).param("maxHeight", ManifestPages.MEMBERS.maximumHeight())
                .query((row, ignored) -> new Located(row.getInt("ordinal"), row.getObject("descriptor_id", UUID.class))).optional();
    }

    /** One exercise of a published exercises manifest: identity, mechanic, state and the question parts of its content only (the statement selects the prompt and passage, never the answer key, options or reference). */
    record ExerciseRow(UUID exerciseId, UUID descriptorRootId, UUID revisionId, String type, boolean enabled, JsonNode question) { }

    List<ExerciseRow> exercises(UUID deck, Collection<UUID> exercises, Collection<UUID> descriptors) {
        if (exercises.isEmpty()) return List.of();
        return jdbc.sql("""
                SELECT r.exercise_id, r.descriptor_root_id, r.revision_id, r.exercise_type, r.enabled,
                       jsonb_build_object('prompt', r.content -> 'prompt', 'passage', r.content -> 'passage') AS question
                  FROM app_learning.exercise_revision r
                 WHERE r.deck_id = :deck AND r.exercise_id IN (:exercises) AND r.descriptor_root_id IN (:descriptors)
                """).param("deck", deck).param("exercises", exercises).param("descriptors", descriptors)
                .query((row, ignored) -> new ExerciseRow(row.getObject("exercise_id", UUID.class),
                        row.getObject("descriptor_root_id", UUID.class), row.getObject("revision_id", UUID.class),
                        row.getString("exercise_type"), row.getBoolean("enabled"), json(row.getString("question")))).list();
    }

    /** Cached titles of the given revisions in one statement (a revision without a cached title is absent), keyed {@code member/revision}. */
    java.util.Map<String, String> cachedTitles(UUID scope, Collection<UUID> members, Collection<UUID> revisions) {
        java.util.Map<String, String> result = new java.util.HashMap<>();
        if (members.isEmpty()) return result;
        jdbc.sql("""
                SELECT member_key, revision_id, title FROM app_learning.item_preview
                 WHERE reuse_scope_id = :scope AND member_key IN (:members) AND revision_id IN (:revisions)
                """).param("scope", scope).param("members", members).param("revisions", revisions)
                .query((row, ignored) -> result.put(row.getObject("member_key", UUID.class) + "/" + row.getObject("revision_id", UUID.class),
                        row.getString("title"))).list();
        return result;
    }

    /** A title worked out from a document: written to the rebuildable cache after the read, by {@link #storeTitles}. */
    record NewTitle(UUID member, UUID revision, String title) { }

    /** Stores derived titles (idempotent; the same rows a concurrent reader would store). Runs outside the read transaction. */
    void storeTitles(UUID scope, List<NewTitle> titles) {
        for (NewTitle title : titles) {
            jdbc.sql("""
                    INSERT INTO app_learning.item_preview(reuse_scope_id, member_key, revision_id, title)
                    VALUES (:scope, :member, :revision, :title) ON CONFLICT DO NOTHING
                    """).param("scope", scope).param("member", title.member()).param("revision", title.revision())
                    .param("title", title.title()).update();
        }
    }

    private static JsonNode json(String value) {
        try { return JSON.readTree(value); }
        catch (JacksonException failure) { throw new IllegalStateException("Stored exercise content is not JSON", failure); }
    }
}
