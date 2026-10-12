package app.mnema.learning.update.history;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The SQL of «Что нового» (Updates/6 part B, #445, migration V65): the log of the applies of a copy and, for the units of one apply, the journals the combined publication wrote
 * in the same transaction ({@code deck_item_change}, {@code deck_exercise_change}: primary-key ranges of ONE deck revision), the objective rows of the log, and the heads those
 * units stand on now. Every statement is addressed by the copy's own deck id (the owner predicate is the first statement of the service); nothing here writes except {@link #markSeen}.
 */
@Repository
class HistoryRepository {
    /** The owner's live copy: its lineage scope and its state. */
    record Copy(UUID scopeId, boolean preparing, boolean detached) { }

    /** An entry of the log. */
    record Entry(UUID deckRevisionId, UUID upstreamRevisionId, String mode, Instant appliedAt, boolean seen, int applied, int pending) { }

    /** A journal row of a material that the apply created, replaced or removed ({@code ordinal}: the position the operation named, for a removal the one it had). */
    record ItemRow(UUID key, UUID previous, UUID revision, int ordinal) { }

    record ExerciseRow(UUID id, UUID previous, UUID revision, int ordinal) { }

    record ObjectiveRow(UUID id, UUID previous, UUID revision) { }

    private final JdbcClient jdbc;

    HistoryRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    Optional<Copy> copy(UUID owner, UUID deck) {
        return jdbc.sql("""
                SELECT d.reuse_scope_id,f.materialized_at IS NULL AS preparing,f.detached_at IS NOT NULL AS detached
                  FROM app_learning.deck d JOIN app_learning.deck_fork f ON f.deck_id=d.deck_id AND f.released_at IS NULL
                 WHERE d.owner_id=:owner AND d.deck_id=:deck AND d.deleted_at IS NULL
                """).param("owner", owner).param("deck", deck)
                .query((row, ignored) -> new Copy(row.getObject("reuse_scope_id", UUID.class), row.getBoolean("preparing"), row.getBoolean("detached"))).optional();
    }

    private static Entry entry(java.sql.ResultSet row) throws java.sql.SQLException {
        return new Entry(row.getObject("deck_revision_id", UUID.class), row.getObject("upstream_revision_id", UUID.class), row.getString("mode"),
                row.getTimestamp("applied_at").toInstant(), row.getTimestamp("seen_at") != null, row.getInt("applied_units"), row.getInt("pending_units"));
    }

    private static final String ENTRY = "SELECT deck_revision_id,upstream_revision_id,mode,applied_at,seen_at,applied_units,pending_units FROM app_learning.fork_update_log WHERE deck_id=:deck";

    /** The newest entries, newest first (the index {@code fork_update_log_history}). */
    List<Entry> entries(UUID deck, int limit) {
        return jdbc.sql(ENTRY + " ORDER BY applied_at DESC,deck_revision_id DESC LIMIT :limit").param("deck", deck).param("limit", limit).query((row, ignored) -> entry(row)).list();
    }

    Optional<Entry> entry(UUID deck, UUID revision) {
        return jdbc.sql(ENTRY + " AND deck_revision_id=:revision").param("deck", deck).param("revision", revision).query((row, ignored) -> entry(row)).optional();
    }

    /** Idempotent: the first call sets the time, later calls change nothing. Whether the entry exists. */
    boolean markSeen(UUID deck, UUID revision) {
        return jdbc.sql("UPDATE app_learning.fork_update_log SET seen_at=COALESCE(seen_at,clock_timestamp()) WHERE deck_id=:deck AND deck_revision_id=:revision")
                .param("deck", deck).param("revision", revision).update() == 1;
    }

    // ---- the journals of one apply ----

    List<ItemRow> items(UUID deck, UUID revision, Collection<UUID> keys) {
        return jdbc.sql("""
                SELECT member_key,previous_revision_id,revision_id,COALESCE(from_ordinal,to_ordinal) AS ordinal FROM app_learning.deck_item_change
                 WHERE deck_id=:deck AND deck_revision_id=:revision AND change_kind <> 'reorder' AND member_key IN (SELECT CAST(k AS uuid) FROM unnest(CAST(:keys AS text[])) AS t(k)) ORDER BY change_ordinal
                """).param("deck", deck).param("revision", revision).param("keys", text(keys))
                .query((row, ignored) -> new ItemRow(row.getObject("member_key", UUID.class), row.getObject("previous_revision_id", UUID.class), row.getObject("revision_id", UUID.class),
                        row.getInt("ordinal"))).list();
    }

    List<ExerciseRow> exercises(UUID deck, UUID revision, Collection<UUID> ids) {
        return jdbc.sql("""
                SELECT exercise_id,previous_revision_id,revision_id,ordinal FROM app_learning.deck_exercise_change
                 WHERE deck_id=:deck AND deck_revision_id=:revision AND exercise_id IN (SELECT CAST(k AS uuid) FROM unnest(CAST(:ids AS text[])) AS t(k)) ORDER BY exercise_id
                """).param("deck", deck).param("revision", revision).param("ids", text(ids))
                .query((row, ignored) -> new ExerciseRow(row.getObject("exercise_id", UUID.class), row.getObject("previous_revision_id", UUID.class),
                        row.getObject("revision_id", UUID.class), row.getInt("ordinal"))).list();
    }

    // ---- the journals of SEVERAL applies, one statement each (the history page) ----

    /** Materials each of these applies created, replaced or removed, in the order of its journal (a bare move is not here), at most {@code limit} per apply. */
    Map<UUID, List<ItemRow>> items(UUID deck, Collection<UUID> revisions, int limit) {
        Map<UUID, List<ItemRow>> result = new HashMap<>();
        jdbc.sql("""
                SELECT c.deck_revision_id,c.member_key,c.previous_revision_id,c.revision_id,COALESCE(c.from_ordinal,c.to_ordinal) AS ordinal
                  FROM unnest(CAST(:revisions AS text[])) AS r(rev)
                 CROSS JOIN LATERAL (SELECT * FROM app_learning.deck_item_change x WHERE x.deck_id=:deck AND x.deck_revision_id=CAST(r.rev AS uuid) AND x.change_kind <> 'reorder'
                                      ORDER BY x.change_ordinal LIMIT :limit) c
                 ORDER BY c.deck_revision_id,c.change_ordinal
                """).param("deck", deck).param("revisions", text(revisions)).param("limit", limit)
                .query((row, ignored) -> result.computeIfAbsent(row.getObject("deck_revision_id", UUID.class), key -> new ArrayList<>())
                        .add(new ItemRow(row.getObject("member_key", UUID.class), row.getObject("previous_revision_id", UUID.class), row.getObject("revision_id", UUID.class),
                                row.getInt("ordinal")))).list();
        return result;
    }

    Map<UUID, List<ExerciseRow>> exercises(UUID deck, Collection<UUID> revisions, int limit) {
        Map<UUID, List<ExerciseRow>> result = new HashMap<>();
        jdbc.sql("""
                SELECT c.deck_revision_id,c.exercise_id,c.previous_revision_id,c.revision_id,c.ordinal
                  FROM unnest(CAST(:revisions AS text[])) AS r(rev)
                 CROSS JOIN LATERAL (SELECT * FROM app_learning.deck_exercise_change x WHERE x.deck_id=:deck AND x.deck_revision_id=CAST(r.rev AS uuid)
                                      ORDER BY x.exercise_id LIMIT :limit) c
                 ORDER BY c.deck_revision_id,c.exercise_id
                """).param("deck", deck).param("revisions", text(revisions)).param("limit", limit)
                .query((row, ignored) -> result.computeIfAbsent(row.getObject("deck_revision_id", UUID.class), key -> new ArrayList<>())
                        .add(new ExerciseRow(row.getObject("exercise_id", UUID.class), row.getObject("previous_revision_id", UUID.class), row.getObject("revision_id", UUID.class),
                                row.getInt("ordinal")))).list();
        return result;
    }

    Map<UUID, List<ObjectiveRow>> objectives(UUID deck, Collection<UUID> revisions, int limit) {
        Map<UUID, List<ObjectiveRow>> result = new HashMap<>();
        jdbc.sql("""
                SELECT c.deck_revision_id,c.objective_id,c.previous_revision_id,c.revision_id
                  FROM unnest(CAST(:revisions AS text[])) AS r(rev)
                 CROSS JOIN LATERAL (SELECT * FROM app_learning.fork_update_log_objective x WHERE x.deck_id=:deck AND x.deck_revision_id=CAST(r.rev AS uuid)
                                      ORDER BY x.objective_id LIMIT :limit) c
                 ORDER BY c.deck_revision_id,c.objective_id
                """).param("deck", deck).param("revisions", text(revisions)).param("limit", limit)
                .query((row, ignored) -> result.computeIfAbsent(row.getObject("deck_revision_id", UUID.class), key -> new ArrayList<>())
                        .add(new ObjectiveRow(row.getObject("objective_id", UUID.class), row.getObject("previous_revision_id", UUID.class), row.getObject("revision_id", UUID.class))))
                .list();
        return result;
    }

    /** The applies among these that moved a material without changing it (the ORDER unit left a trace). */
    Set<UUID> reordered(UUID deck, Collection<UUID> revisions) {
        Set<UUID> result = new HashSet<>();
        jdbc.sql("""
                SELECT r.rev FROM unnest(CAST(:revisions AS text[])) AS r(rev)
                 WHERE EXISTS (SELECT 1 FROM app_learning.deck_item_change x WHERE x.deck_id=:deck AND x.deck_revision_id=CAST(r.rev AS uuid) AND x.change_kind='reorder')
                """).param("deck", deck).param("revisions", text(revisions)).query((row, ignored) -> result.add(UUID.fromString(row.getString(1)))).list();
        return result;
    }

    /** The new title of the deck for each of these applies that changed the title or the description against its parent. */
    Map<UUID, String> retitled(UUID deck, Collection<UUID> revisions) {
        Map<UUID, String> result = new HashMap<>();
        jdbc.sql("""
                SELECT r.revision_id,r.title FROM app_learning.deck_revision r JOIN app_learning.deck_revision p ON p.deck_id=r.deck_id AND p.revision_id=r.parent_revision_id
                 WHERE r.deck_id=:deck AND r.revision_id IN (SELECT CAST(k AS uuid) FROM unnest(CAST(:revisions AS text[])) AS t(k)) AND (r.title <> p.title OR r.description <> p.description)
                """).param("deck", deck).param("revisions", text(revisions)).query((row, ignored) -> result.put(row.getObject(1, UUID.class), row.getString(2))).list();
        return result;
    }

    // ---- where the units stand now ----

    Map<UUID, UUID> itemHeads(UUID deck, Collection<UUID> keys) {
        return heads("SELECT member_key AS k,revision_id FROM app_learning.deck_head_item WHERE deck_id=:deck AND member_key IN (SELECT CAST(k AS uuid) FROM unnest(CAST(:keys AS text[])) AS t(k))", deck, keys);
    }

    Map<UUID, UUID> exerciseHeads(UUID deck, Collection<UUID> keys) {
        return heads("SELECT exercise_id AS k,revision_id FROM app_learning.deck_head_exercise WHERE deck_id=:deck AND exercise_id IN (SELECT CAST(k AS uuid) FROM unnest(CAST(:keys AS text[])) AS t(k))", deck, keys);
    }

    Map<UUID, UUID> objectiveHeads(UUID deck, Collection<UUID> keys) {
        return heads("SELECT objective_id AS k,revision_id FROM app_learning.objective_head WHERE deck_id=:deck AND objective_id IN (SELECT CAST(k AS uuid) FROM unnest(CAST(:keys AS text[])) AS t(k))", deck, keys);
    }

    private Map<UUID, UUID> heads(String sql, UUID deck, Collection<UUID> keys) {
        Map<UUID, UUID> result = new HashMap<>();
        if (keys.isEmpty()) return result;
        jdbc.sql(sql).param("deck", deck).param("keys", text(keys))
                .query((row, ignored) -> result.put(row.getObject("k", UUID.class), row.getObject("revision_id", UUID.class))).list();
        return result;
    }

    static String[] text(Collection<UUID> ids) { return ids.stream().map(UUID::toString).toArray(String[]::new); }
}
