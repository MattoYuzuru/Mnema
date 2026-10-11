package app.mnema.learning.update.plan;

import app.mnema.learning.moderation.ContentTakedowns;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The reads of the update plan. Nothing here writes. Every lineage row is reached through the copy's own scope and a key the caller got from a manifest, a head or a decision row of
 * the copy, never from a request: a revision id is not access ({@code ItemRevisionVisibility}), and the plan names exactly the revisions its three sides hold. Statements that answer
 * many keys take arrays and {@code unnest} them (chunks of {@value #CHUNK}), so a plan of N changed units is a handful of statements and not N round trips.
 */
@Repository
class PlanRepository {
    static final int CHUNK = 1_000;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String BLOCKED_ITEM = ContentTakedowns.blockedItem("r.reuse_scope_id", "r.member_key", "r.revision_id");
    private static final String BLOCKED_EXERCISE = ContentTakedowns.blockedExerciseOrQuote("r.reuse_scope_id", "r.exercise_id", "r.revision_id");
    /** The longest parent chain the ancestry test follows; a longer chain is "not an ancestor" (a conflict, the conservative answer). */
    static final int MAX_CHAIN = 10_000;

    private final JdbcClient jdbc;

    PlanRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** A pair of ids: a key and a descriptor or revision. */
    record Pair(UUID key, UUID second) { }

    /** The copy of the owner: its head revision with the manifests, and its link to the source. */
    record Copy(UUID deckId, UUID scopeId, UUID headRevisionId, String title, String description, UUID membersRootId, UUID exercisesRootId, int memberCount, int exerciseCount,
                UUID upstreamDeckId, String upstreamCode, UUID baseRevisionId, boolean detached, boolean materialized) { }

    /** A deck revision of the source: what the base and the published revision pin. */
    record SourceRevision(UUID revisionId, String title, String description, UUID membersRootId, UUID exercisesRootId, int memberCount, int exerciseCount) { }

    /** A lineage revision of a material or an exercise ({@code contentRootId} is null for an exercise). */
    record Rev(UUID key, UUID revisionId, long sequence, UUID descriptorRootId, UUID contentRootId, boolean blocked) { }

    /** The ASSESSED/CUE/OPTION/CONTEXT binding of an exercise revision. */
    record Binding(UUID exerciseId, UUID exerciseRevisionId, String role, UUID memberKey, UUID itemRevisionId, UUID objectiveId, UUID objectiveRevisionId, List<UUID> nodeIds) { }

    /** The content of an exercise revision, the learner-relevant part of it. */
    record ExerciseBody(UUID exerciseId, UUID revisionId, long sequence, String type, boolean enabled, JsonNode content, JsonNode answerKey, JsonNode evaluatorPolicy) { }

    /** An objective revision with its title. */
    record ObjectiveRev(UUID objectiveId, UUID revisionId, long sequence, String title) { }

    // ---- the copy and its source ----

    Optional<Copy> copy(UUID owner, UUID deck) {
        return jdbc.sql("""
                SELECT d.deck_id,d.reuse_scope_id,d.head_revision_id,r.title,r.description,r.members_root_id,r.exercises_root_id,r.member_count,r.exercise_count,
                       f.upstream_deck_id,f.upstream_code,f.base_revision_id,f.detached_at IS NOT NULL AS detached,f.materialized_at IS NOT NULL AS materialized
                  FROM app_learning.deck d JOIN app_learning.deck_fork f ON f.deck_id=d.deck_id AND f.released_at IS NULL
                  JOIN app_learning.deck_revision r ON r.deck_id=d.deck_id AND r.revision_id=d.head_revision_id
                 WHERE d.owner_id=:owner AND d.deck_id=:deck AND d.deleted_at IS NULL
                """).param("owner", owner).param("deck", deck)
                .query((row, ignored) -> new Copy(row.getObject("deck_id", UUID.class), row.getObject("reuse_scope_id", UUID.class),
                        row.getObject("head_revision_id", UUID.class), row.getString("title"), row.getString("description"),
                        row.getObject("members_root_id", UUID.class), row.getObject("exercises_root_id", UUID.class), row.getInt("member_count"), row.getInt("exercise_count"),
                        row.getObject("upstream_deck_id", UUID.class), row.getString("upstream_code"), row.getObject("base_revision_id", UUID.class),
                        row.getBoolean("detached"), row.getBoolean("materialized"))).optional();
    }

    /** A deck revision of the source by id (the base, a decision's revision). The deck predicate keeps the read inside the source the copy follows. */
    Optional<SourceRevision> sourceRevision(UUID source, UUID revision) {
        return jdbc.sql("""
                SELECT revision_id,title,description,members_root_id,exercises_root_id,member_count,exercise_count
                  FROM app_learning.deck_revision WHERE deck_id=:deck AND revision_id=:revision
                """).param("deck", source).param("revision", revision)
                .query((row, ignored) -> new SourceRevision(row.getObject("revision_id", UUID.class), row.getString("title"), row.getString("description"),
                        row.getObject("members_root_id", UUID.class), row.getObject("exercises_root_id", UUID.class), row.getInt("member_count"), row.getInt("exercise_count")))
                .optional();
    }

    /** The release note the author wrote with the publication of exactly this revision (null when there is none, or when another revision has been published since). */
    Optional<String> releaseNote(UUID source, UUID revision) {
        return jdbc.sql("SELECT release_note FROM app_learning.deck_publication WHERE deck_id=:deck AND published_revision_id=:revision")
                .param("deck", source).param("revision", revision).query(String.class).optional();
    }

    List<Decision> decisions(UUID deck) {
        return jdbc.sql("""
                SELECT unit_kind,unit_key,decision,resolved_upstream_revision_id,resolved_upstream_absent,pending_base_revision_id,pending_base_absent
                  FROM app_learning.fork_unit_decision WHERE deck_id=:deck ORDER BY unit_kind,unit_key
                """).param("deck", deck)
                .query((row, ignored) -> new Decision(UnitKind.valueOf(row.getString("unit_kind")), row.getObject("unit_key", UUID.class),
                        Decision.Verdict.valueOf(row.getString("decision")), row.getObject("resolved_upstream_revision_id", UUID.class), row.getBoolean("resolved_upstream_absent"),
                        row.getObject("pending_base_revision_id", UUID.class), row.getBoolean("pending_base_absent"))).list();
    }

    // ---- materials ----

    Map<Pair, Rev> itemsByDescriptor(UUID scope, Collection<Pair> pairs) {
        Map<Pair, Rev> result = new HashMap<>();
        for (List<Pair> chunk : chunks(pairs)) {
            jdbc.sql("SELECT r.member_key,r.revision_id,r.item_sequence,r.descriptor_root_id,r.content_root_id," + BLOCKED_ITEM + " AS blocked "
                            + "FROM unnest(CAST(:keys AS text[]),CAST(:seconds AS text[])) AS v(member_key,descriptor) "
                            + "JOIN app_learning.item_revision r ON r.reuse_scope_id=:scope AND r.member_key=CAST(v.member_key AS uuid) AND r.descriptor_root_id=CAST(v.descriptor AS uuid)")
                    .param("scope", scope).param("keys", keys(chunk)).param("seconds", seconds(chunk))
                    .query((row, ignored) -> {
                        Rev rev = item(row);
                        return result.put(new Pair(rev.key(), rev.descriptorRootId()), rev);
                    }).list();
        }
        return result;
    }

    Map<Pair, Rev> itemsById(UUID scope, Collection<Pair> pairs) {
        Map<Pair, Rev> result = new HashMap<>();
        for (List<Pair> chunk : chunks(pairs)) {
            jdbc.sql("SELECT r.member_key,r.revision_id,r.item_sequence,r.descriptor_root_id,r.content_root_id," + BLOCKED_ITEM + " AS blocked "
                            + "FROM unnest(CAST(:keys AS text[]),CAST(:seconds AS text[])) AS v(member_key,revision) "
                            + "JOIN app_learning.item_revision r ON r.reuse_scope_id=:scope AND r.member_key=CAST(v.member_key AS uuid) AND r.revision_id=CAST(v.revision AS uuid)")
                    .param("scope", scope).param("keys", keys(chunk)).param("seconds", seconds(chunk))
                    .query((row, ignored) -> {
                        Rev rev = item(row);
                        return result.put(new Pair(rev.key(), rev.revisionId()), rev);
                    }).list();
        }
        return result;
    }

    /** The copy's own head revision of each material, by member key. */
    Map<UUID, Rev> itemHeads(UUID deck, Collection<UUID> members) {
        Map<UUID, Rev> result = new HashMap<>();
        for (List<UUID> chunk : chunksOf(members)) {
            jdbc.sql("SELECT r.member_key,r.revision_id,r.item_sequence,r.descriptor_root_id,r.content_root_id," + BLOCKED_ITEM + " AS blocked "
                            + "FROM app_learning.deck_head_item h JOIN app_learning.item_revision r ON r.reuse_scope_id=h.reuse_scope_id AND r.member_key=h.member_key "
                            + "AND r.revision_id=h.revision_id WHERE h.deck_id=:deck AND h.member_key=ANY(CAST(CAST(:keys AS text[]) AS uuid[]))")
                    .param("deck", deck).param("keys", text(chunk))
                    .query((row, ignored) -> {
                        Rev rev = item(row);
                        return result.put(rev.key(), rev);
                    }).list();
        }
        return result;
    }

    private static Rev item(java.sql.ResultSet row) throws java.sql.SQLException {
        return new Rev(row.getObject("member_key", UUID.class), row.getObject("revision_id", UUID.class), row.getLong("item_sequence"),
                row.getObject("descriptor_root_id", UUID.class), row.getObject("content_root_id", UUID.class), row.getBoolean("blocked"));
    }

    /** The cached titles of material revisions ({@code item_preview}); a revision without a cached title is simply absent from the answer. */
    Map<Pair, String> cachedTitles(UUID scope, Collection<Pair> pairs) {
        Map<Pair, String> result = new HashMap<>();
        for (List<Pair> chunk : chunks(pairs)) {
            jdbc.sql("SELECT p.member_key,p.revision_id,p.title FROM unnest(CAST(:keys AS text[]),CAST(:seconds AS text[])) AS v(member_key,revision) "
                            + "JOIN app_learning.item_preview p ON p.reuse_scope_id=:scope AND p.member_key=CAST(v.member_key AS uuid) AND p.revision_id=CAST(v.revision AS uuid)")
                    .param("scope", scope).param("keys", keys(chunk)).param("seconds", seconds(chunk))
                    .query((row, ignored) -> result.put(new Pair(row.getObject("member_key", UUID.class), row.getObject("revision_id", UUID.class)), row.getString("title"))).list();
        }
        return result;
    }

    // ---- exercises ----

    Map<Pair, Rev> exercisesByDescriptor(UUID scope, Collection<Pair> pairs) {
        Map<Pair, Rev> result = new HashMap<>();
        for (List<Pair> chunk : chunks(pairs)) {
            jdbc.sql("SELECT r.exercise_id,r.revision_id,r.exercise_sequence,r.descriptor_root_id," + BLOCKED_EXERCISE + " AS blocked "
                            + "FROM unnest(CAST(:keys AS text[]),CAST(:seconds AS text[])) AS v(exercise_id,descriptor) "
                            + "JOIN app_learning.exercise_revision r ON r.reuse_scope_id=:scope AND r.exercise_id=CAST(v.exercise_id AS uuid) AND r.descriptor_root_id=CAST(v.descriptor AS uuid)")
                    .param("scope", scope).param("keys", keys(chunk)).param("seconds", seconds(chunk))
                    .query((row, ignored) -> {
                        Rev rev = exercise(row);
                        return result.put(new Pair(rev.key(), rev.descriptorRootId()), rev);
                    }).list();
        }
        return result;
    }

    Map<Pair, Rev> exercisesById(UUID scope, Collection<Pair> pairs) {
        Map<Pair, Rev> result = new HashMap<>();
        for (List<Pair> chunk : chunks(pairs)) {
            jdbc.sql("SELECT r.exercise_id,r.revision_id,r.exercise_sequence,r.descriptor_root_id," + BLOCKED_EXERCISE + " AS blocked "
                            + "FROM unnest(CAST(:keys AS text[]),CAST(:seconds AS text[])) AS v(exercise_id,revision) "
                            + "JOIN app_learning.exercise_revision r ON r.reuse_scope_id=:scope AND r.exercise_id=CAST(v.exercise_id AS uuid) AND r.revision_id=CAST(v.revision AS uuid)")
                    .param("scope", scope).param("keys", keys(chunk)).param("seconds", seconds(chunk))
                    .query((row, ignored) -> {
                        Rev rev = exercise(row);
                        return result.put(new Pair(rev.key(), rev.revisionId()), rev);
                    }).list();
        }
        return result;
    }

    Map<UUID, Rev> exerciseHeads(UUID deck, Collection<UUID> exercises) {
        Map<UUID, Rev> result = new HashMap<>();
        for (List<UUID> chunk : chunksOf(exercises)) {
            jdbc.sql("SELECT r.exercise_id,r.revision_id,r.exercise_sequence,r.descriptor_root_id," + BLOCKED_EXERCISE + " AS blocked "
                            + "FROM app_learning.deck_head_exercise h JOIN app_learning.exercise_revision r ON r.reuse_scope_id=h.reuse_scope_id AND r.exercise_id=h.exercise_id "
                            + "AND r.revision_id=h.revision_id WHERE h.deck_id=:deck AND h.exercise_id=ANY(CAST(CAST(:keys AS text[]) AS uuid[]))")
                    .param("deck", deck).param("keys", text(chunk))
                    .query((row, ignored) -> {
                        Rev rev = exercise(row);
                        return result.put(rev.key(), rev);
                    }).list();
        }
        return result;
    }

    private static Rev exercise(java.sql.ResultSet row) throws java.sql.SQLException {
        return new Rev(row.getObject("exercise_id", UUID.class), row.getObject("revision_id", UUID.class), row.getLong("exercise_sequence"),
                row.getObject("descriptor_root_id", UUID.class), null, row.getBoolean("blocked"));
    }

    Map<Pair, ExerciseBody> exerciseBodies(UUID scope, Collection<Pair> pairs) {
        Map<Pair, ExerciseBody> result = new HashMap<>();
        for (List<Pair> chunk : chunks(pairs)) {
            jdbc.sql("SELECT r.exercise_id,r.revision_id,r.exercise_sequence,r.exercise_type,r.enabled,r.content,r.answer_key,r.evaluator_policy "
                            + "FROM unnest(CAST(:keys AS text[]),CAST(:seconds AS text[])) AS v(exercise_id,revision) "
                            + "JOIN app_learning.exercise_revision r ON r.reuse_scope_id=:scope AND r.exercise_id=CAST(v.exercise_id AS uuid) AND r.revision_id=CAST(v.revision AS uuid)")
                    .param("scope", scope).param("keys", keys(chunk)).param("seconds", seconds(chunk))
                    .query((row, ignored) -> {
                        ExerciseBody body = new ExerciseBody(row.getObject("exercise_id", UUID.class), row.getObject("revision_id", UUID.class), row.getLong("exercise_sequence"),
                                row.getString("exercise_type"), row.getBoolean("enabled"), json(row.getString("content")), json(row.getString("answer_key")),
                                json(row.getString("evaluator_policy")));
                        return result.put(new Pair(body.exerciseId(), body.revisionId()), body);
                    }).list();
        }
        return result;
    }

    /** Only the {@code content} of exercise revisions (the question): a title never reads the answer key or the evaluator policy. */
    Map<Pair, JsonNode> exerciseContents(UUID scope, Collection<Pair> pairs) {
        Map<Pair, JsonNode> result = new HashMap<>();
        for (List<Pair> chunk : chunks(pairs)) {
            jdbc.sql("SELECT r.exercise_id,r.revision_id,r.content "
                            + "FROM unnest(CAST(:keys AS text[]),CAST(:seconds AS text[])) AS v(exercise_id,revision) "
                            + "JOIN app_learning.exercise_revision r ON r.reuse_scope_id=:scope AND r.exercise_id=CAST(v.exercise_id AS uuid) AND r.revision_id=CAST(v.revision AS uuid)")
                    .param("scope", scope).param("keys", keys(chunk)).param("seconds", seconds(chunk))
                    .query((row, ignored) -> result.put(new Pair(row.getObject("exercise_id", UUID.class), row.getObject("revision_id", UUID.class)), json(row.getString("content")))).list();
        }
        return result;
    }

    Map<Pair, List<Binding>> bindings(UUID scope, Collection<Pair> exerciseRevisions) {
        Map<Pair, List<Binding>> result = new HashMap<>();
        for (List<Pair> chunk : chunks(exerciseRevisions)) {
            jdbc.sql("SELECT b.exercise_id,b.exercise_revision_id,b.role,b.member_key,b.item_revision_id,b.objective_id,b.objective_revision_id,b.node_ids "
                            + "FROM unnest(CAST(:keys AS text[]),CAST(:seconds AS text[])) AS v(exercise_id,revision) "
                            + "JOIN app_learning.exercise_content_binding b ON b.reuse_scope_id=:scope AND b.exercise_id=CAST(v.exercise_id AS uuid) "
                            + "AND b.exercise_revision_id=CAST(v.revision AS uuid) ORDER BY b.exercise_id,b.exercise_revision_id,b.binding_ordinal")
                    .param("scope", scope).param("keys", keys(chunk)).param("seconds", seconds(chunk))
                    .query((row, ignored) -> {
                        java.sql.Array nodes = row.getArray("node_ids");
                        Binding binding = new Binding(row.getObject("exercise_id", UUID.class), row.getObject("exercise_revision_id", UUID.class), row.getString("role"),
                                row.getObject("member_key", UUID.class), row.getObject("item_revision_id", UUID.class), row.getObject("objective_id", UUID.class),
                                row.getObject("objective_revision_id", UUID.class), nodes == null ? List.of() : List.of((UUID[]) nodes.getArray()));
                        return result.computeIfAbsent(new Pair(binding.exerciseId(), binding.exerciseRevisionId()), ignoredKey -> new ArrayList<>()).add(binding);
                    }).list();
        }
        return result;
    }

    // ---- objectives ----

    /** The objective revision the copy's head holds for each objective. */
    Map<UUID, UUID> objectiveHeads(UUID deck, Collection<UUID> objectives) {
        Map<UUID, UUID> result = new HashMap<>();
        for (List<UUID> chunk : chunksOf(objectives)) {
            jdbc.sql("SELECT objective_id,revision_id FROM app_learning.objective_head WHERE deck_id=:deck AND objective_id=ANY(CAST(CAST(:keys AS text[]) AS uuid[]))")
                    .param("deck", deck).param("keys", text(chunk))
                    .query((row, ignored) -> result.put(row.getObject("objective_id", UUID.class), row.getObject("revision_id", UUID.class))).list();
        }
        return result;
    }

    Map<Pair, ObjectiveRev> objectiveRevisions(UUID scope, Collection<Pair> pairs) {
        Map<Pair, ObjectiveRev> result = new HashMap<>();
        for (List<Pair> chunk : chunks(pairs)) {
            jdbc.sql("SELECT r.objective_id,r.revision_id,r.objective_sequence,r.descriptor ->> 'title' AS title "
                            + "FROM unnest(CAST(:keys AS text[]),CAST(:seconds AS text[])) AS v(objective_id,revision) "
                            + "JOIN app_learning.objective_revision r ON r.reuse_scope_id=:scope AND r.objective_id=CAST(v.objective_id AS uuid) AND r.revision_id=CAST(v.revision AS uuid)")
                    .param("scope", scope).param("keys", keys(chunk)).param("seconds", seconds(chunk))
                    .query((row, ignored) -> {
                        ObjectiveRev rev = new ObjectiveRev(row.getObject("objective_id", UUID.class), row.getObject("revision_id", UUID.class), row.getLong("objective_sequence"),
                                row.getString("title"));
                        return result.put(new Pair(rev.objectiveId(), rev.revisionId()), rev);
                    }).list();
        }
        return result;
    }

    /**
     * For each (objective, target, head): whether {@code head} is {@code target} or one of its ancestors by the parent chain (a bounded recursive walk of primary-key probes
     * that stops at the head). The key of the answer is (objective, target).
     */
    Map<Pair, Boolean> ancestors(UUID scope, Collection<AncestorQuery> queries) {
        Map<Pair, Boolean> result = new HashMap<>();
        for (List<AncestorQuery> chunk : chunks(queries)) {
            jdbc.sql("""
                    WITH RECURSIVE pairs AS (
                        SELECT CAST(v.objective AS uuid) AS objective_id, CAST(v.target AS uuid) AS target, CAST(v.head AS uuid) AS head
                          FROM unnest(CAST(:objectives AS text[]),CAST(:targets AS text[]),CAST(:heads AS text[])) AS v(objective,target,head)),
                    walk AS (
                        SELECT p.objective_id,p.target,p.head,r.revision_id,r.parent_revision_id,0 AS depth
                          FROM pairs p JOIN app_learning.objective_revision r ON r.reuse_scope_id=:scope AND r.objective_id=p.objective_id AND r.revision_id=p.target
                        UNION ALL
                        SELECT w.objective_id,w.target,w.head,r.revision_id,r.parent_revision_id,w.depth+1
                          FROM walk w JOIN app_learning.objective_revision r ON r.reuse_scope_id=:scope AND r.objective_id=w.objective_id AND r.revision_id=w.parent_revision_id
                         WHERE w.revision_id<>w.head AND w.depth<:depth)
                    SELECT objective_id,target,bool_or(revision_id=head) AS ancestor FROM walk GROUP BY objective_id,target
                    """).param("scope", scope).param("depth", MAX_CHAIN)
                    .param("objectives", text(chunk.stream().map(AncestorQuery::objective).toList()))
                    .param("targets", text(chunk.stream().map(AncestorQuery::target).toList()))
                    .param("heads", text(chunk.stream().map(AncestorQuery::head).toList()))
                    .query((row, ignored) -> result.put(new Pair(row.getObject("objective_id", UUID.class), row.getObject("target", UUID.class)), row.getBoolean("ancestor"))).list();
        }
        return result;
    }

    record AncestorQuery(UUID objective, UUID target, UUID head) { }

    // ---- helpers ----

    private static JsonNode json(String value) {
        try { return JSON.readTree(value); }
        catch (JacksonException exception) { throw new IllegalStateException("Invalid persisted JSON", exception); }
    }

    private static String[] text(Collection<UUID> ids) { return ids.stream().map(UUID::toString).toArray(String[]::new); }

    private static String[] keys(Collection<Pair> pairs) { return pairs.stream().map(pair -> pair.key().toString()).toArray(String[]::new); }

    private static String[] seconds(Collection<Pair> pairs) { return pairs.stream().map(pair -> pair.second().toString()).toArray(String[]::new); }

    private static <T> List<List<T>> chunks(Collection<T> values) {
        List<T> all = List.copyOf(values);
        List<List<T>> result = new ArrayList<>();
        for (int from = 0; from < all.size(); from += CHUNK) result.add(all.subList(from, Math.min(all.size(), from + CHUNK)));
        return result;
    }

    private static List<List<UUID>> chunksOf(Collection<UUID> values) { return chunks(values); }
}
