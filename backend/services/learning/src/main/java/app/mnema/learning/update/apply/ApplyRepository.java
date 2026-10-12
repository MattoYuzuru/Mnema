package app.mnema.learning.update.apply;

import app.mnema.learning.update.apply.ApplyRows.Row;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The SQL of the apply command (Updates/4, #443). The reads take only ids the plan named (the copy's own head, the published revision of its source); the writes are the
 * ones of the final transaction: the base of the copy, its decisions. Statements that write many rows take arrays and {@code unnest} them.
 */
@Repository
class ApplyRepository {
    /** A deck revision with the roots of its two manifests. */
    record Revision(UUID revisionId, String title, String description, UUID membersRootId, UUID exercisesRootId, int memberCount, int exerciseCount) { }

    /** The copy's reuse scope and the deck it follows. */
    record Copy(UUID scopeId, UUID upstreamDeckId) { }

    /** The row of the copy's link to its source, locked. */
    record Pins(UUID upstreamDeckId, UUID baseRevisionId) { }

    private final JdbcClient jdbc;

    ApplyRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    // ---- reads ----

    Optional<Copy> copy(UUID owner, UUID deck) {
        return jdbc.sql("""
                SELECT d.reuse_scope_id,f.upstream_deck_id
                  FROM app_learning.deck d JOIN app_learning.deck_fork f ON f.deck_id=d.deck_id AND f.released_at IS NULL
                 WHERE d.owner_id=:owner AND d.deck_id=:deck AND d.deleted_at IS NULL
                """).param("owner", owner).param("deck", deck)
                .query((row, ignored) -> new Copy(row.getObject("reuse_scope_id", UUID.class), row.getObject("upstream_deck_id", UUID.class))).optional();
    }

    Optional<Revision> revision(UUID deck, UUID revision) {
        return jdbc.sql("""
                SELECT revision_id,title,description,members_root_id,exercises_root_id,member_count,exercise_count
                  FROM app_learning.deck_revision WHERE deck_id=:deck AND revision_id=:revision
                """).param("deck", deck).param("revision", revision)
                .query((row, ignored) -> new Revision(row.getObject("revision_id", UUID.class), row.getString("title"), row.getString("description"),
                        row.getObject("members_root_id", UUID.class), row.getObject("exercises_root_id", UUID.class), row.getInt("member_count"), row.getInt("exercise_count")))
                .optional();
    }

    /**
     * For each of these materials, the exercises of the copy's HEAD that have a binding (any role) to it: what the combined publication refuses to leave quoting a removed
     * material. Chunks of 1 000 members.
     */
    Map<UUID, Set<UUID>> boundHeadExercises(UUID deck, UUID scope, Collection<UUID> members) {
        Map<UUID, Set<UUID>> result = new HashMap<>();
        List<UUID> all = List.copyOf(members);
        for (int from = 0; from < all.size(); from += 1_000) {
            List<UUID> chunk = all.subList(from, Math.min(all.size(), from + 1_000));
            jdbc.sql("""
                    SELECT DISTINCT b.member_key,b.exercise_id
                      FROM unnest(CAST(:members AS text[])) AS v(member_key)
                      JOIN app_learning.exercise_content_binding b ON b.reuse_scope_id=:scope AND b.member_key=CAST(v.member_key AS uuid)
                      JOIN app_learning.deck_head_exercise h ON h.deck_id=:deck AND h.reuse_scope_id=b.reuse_scope_id AND h.exercise_id=b.exercise_id
                       AND h.revision_id=b.exercise_revision_id
                    """).param("deck", deck).param("scope", scope).param("members", chunk.stream().map(UUID::toString).toArray(String[]::new))
                    .query((row, ignored) -> result.computeIfAbsent(row.getObject(1, UUID.class), key -> new HashSet<>()).add(row.getObject(2, UUID.class))).list();
        }
        return result;
    }

    Optional<UUID> published(UUID source) {
        return jdbc.sql("SELECT published_revision_id FROM app_learning.deck_publication WHERE deck_id=:deck").param("deck", source).query(UUID.class).optional();
    }

    // ---- writes (the final transaction of the publication) ----

    /** Locks the copy's link to its source (an apply of the same copy waits here) and reads the base; empty when the copy is not a live, attached, materialized one. */
    Optional<Pins> lock(UUID deck) {
        return jdbc.sql("""
                SELECT upstream_deck_id,base_revision_id FROM app_learning.deck_fork
                 WHERE deck_id=:deck AND released_at IS NULL AND detached_at IS NULL AND materialized_at IS NOT NULL FOR UPDATE
                """).param("deck", deck)
                .query((row, ignored) -> new Pins(row.getObject("upstream_deck_id", UUID.class), row.getObject("base_revision_id", UUID.class))).optional();
    }

    /** The head revision of the copy's deck under a share lock: nobody commits an edit of the deck until this transaction ends. */
    Optional<UUID> lockHead(UUID deck) {
        return jdbc.sql("SELECT head_revision_id FROM app_learning.deck WHERE deck_id=:deck FOR SHARE").param("deck", deck).query(UUID.class).optional();
    }

    /** Notes that an apply command of the copy succeeded just now (the next one replaces it). */
    void logApply(UUID deck) {
        jdbc.sql("INSERT INTO app_learning.fork_apply_log(deck_id,applied_at) VALUES (:deck,clock_timestamp()) ON CONFLICT (deck_id) DO UPDATE SET applied_at=EXCLUDED.applied_at")
                .param("deck", deck).update();
    }

    boolean moveBase(UUID deck, UUID expected, UUID base) {
        return jdbc.sql("UPDATE app_learning.deck_fork SET base_revision_id=:base WHERE deck_id=:deck AND base_revision_id=:expected")
                .param("deck", deck).param("expected", expected).param("base", base).update() == 1;
    }

    void deletePending(UUID deck) {
        jdbc.sql("DELETE FROM app_learning.fork_unit_decision WHERE deck_id=:deck AND decision='PENDING'").param("deck", deck).update();
    }

    void delete(UUID deck, List<Row> rows) {
        if (rows.isEmpty()) return;
        jdbc.sql("""
                DELETE FROM app_learning.fork_unit_decision d USING unnest(CAST(:kinds AS text[]),CAST(:keys AS text[])) AS v(unit_kind,unit_key)
                 WHERE d.deck_id=:deck AND d.unit_kind=v.unit_kind AND d.unit_key=CAST(v.unit_key AS uuid)
                """).param("deck", deck).param("kinds", rows.stream().map(row -> row.kind().name()).toArray(String[]::new))
                .param("keys", rows.stream().map(row -> row.key().toString()).toArray(String[]::new)).update();
    }

    void upsert(UUID deck, List<Row> rows) {
        if (rows.isEmpty()) return;
        jdbc.sql("""
                INSERT INTO app_learning.fork_unit_decision(deck_id,reuse_scope_id,unit_kind,unit_key,decision,resolved_upstream_revision_id,resolved_upstream_absent,
                                                            pending_base_revision_id,pending_base_absent,decided_at)
                SELECT d.deck_id,d.reuse_scope_id,v.unit_kind,CAST(v.unit_key AS uuid),v.decision,
                       CASE WHEN v.resolved IN ('','-') THEN NULL ELSE CAST(v.resolved AS uuid) END, v.resolved='-',
                       CASE WHEN v.base IN ('','-') THEN NULL ELSE CAST(v.base AS uuid) END, v.base='-',
                       clock_timestamp()
                  FROM app_learning.deck d
                 CROSS JOIN unnest(CAST(:kinds AS text[]),CAST(:keys AS text[]),CAST(:decisions AS text[]),CAST(:resolved AS text[]),CAST(:bases AS text[]))
                            AS v(unit_kind,unit_key,decision,resolved,base)
                 WHERE d.deck_id=:deck
                ON CONFLICT (deck_id,unit_kind,unit_key) DO UPDATE SET decision=EXCLUDED.decision,resolved_upstream_revision_id=EXCLUDED.resolved_upstream_revision_id,
                    resolved_upstream_absent=EXCLUDED.resolved_upstream_absent,pending_base_revision_id=EXCLUDED.pending_base_revision_id,
                    pending_base_absent=EXCLUDED.pending_base_absent,decided_at=EXCLUDED.decided_at
                """).param("deck", deck).param("kinds", rows.stream().map(row -> row.kind().name()).toArray(String[]::new))
                .param("keys", rows.stream().map(row -> row.key().toString()).toArray(String[]::new))
                .param("decisions", rows.stream().map(row -> row.op().name()).toArray(String[]::new))
                .param("resolved", rows.stream().map(Row::resolved).toArray(String[]::new))
                .param("bases", rows.stream().map(Row::base).toArray(String[]::new)).update();
    }
}
