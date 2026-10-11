package app.mnema.learning.fork;

import app.mnema.learning.catalog.content.pages.CountedPageTypes.Entry;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The SQL of the materialization job. Every head row is derived from a manifest entry (a member or exercise key and the descriptor object the PUBLISHED manifest holds for it):
 * the lineage revision is found by {@code (scope, key, descriptor_root_id)}, never by "the newest" or "the source's head", so a row the author wrote after publishing cannot be
 * picked up. A statement reports how many rows it wrote; the caller compares that with the entries it sent and fails the job on any difference.
 */
@Repository
class MaterializationRepository {
    private final JdbcClient jdbc;

    MaterializationRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** The copy and the manifest it pins (its revision 0), with its row locked for the slice. */
    record Copy(UUID deckId, UUID scopeId, UUID membersRootId, UUID exercisesRootId, int memberCount, int exerciseCount, boolean materialized,
                boolean gone, Instant forkedAt) { }

    Optional<Copy> lock(UUID deck) {
        return jdbc.sql("""
                SELECT f.deck_id, f.reuse_scope_id, r.members_root_id, r.exercises_root_id, r.member_count, r.exercise_count,
                       f.materialized_at IS NOT NULL AS materialized, (d.deleted_at IS NOT NULL OR f.released_at IS NOT NULL) AS gone, f.forked_at
                  FROM app_learning.deck_fork f JOIN app_learning.deck d ON d.deck_id = f.deck_id
                  JOIN app_learning.deck_revision r ON r.deck_id = f.deck_id AND r.sequence = 0
                 WHERE f.deck_id = :deck FOR UPDATE OF f
                """).param("deck", deck)
                .query((row, ignored) -> new Copy(row.getObject("deck_id", UUID.class), row.getObject("reuse_scope_id", UUID.class),
                        row.getObject("members_root_id", UUID.class), row.getObject("exercises_root_id", UUID.class), row.getInt("member_count"),
                        row.getInt("exercise_count"), row.getBoolean("materialized"), row.getBoolean("gone"), row.getTimestamp("forked_at").toInstant()))
                .optional();
    }

    /** Names this transaction as the materialization of {@code deck}: the head tables accept writes for it (trigger {@code deck_fork_preparing_guard}). */
    void allowHeadWrites(UUID deck) {
        jdbc.sql("SELECT set_config('mnema.fork_materializing', :deck, true)").param("deck", deck.toString()).query().listOfRows();
    }

    /** Writes the head of each material of a manifest page; returns how many entries found their revision. */
    int insertItemHeads(UUID deck, UUID scope, List<Entry> entries) {
        Map<String, Object> params = new HashMap<>();
        StringBuilder values = new StringBuilder();
        for (int i = 0; i < entries.size(); i++) {
            values.append(i == 0 ? "" : ",").append("(CAST(:k").append(i).append(" AS uuid), CAST(:d").append(i).append(" AS uuid))");
            params.put("k" + i, entries.get(i).key());
            params.put("d" + i, entries.get(i).target().objectId());
        }
        params.put("deck", deck);
        params.put("scope", scope);
        return jdbc.sql("""
                INSERT INTO app_learning.deck_head_item(deck_id, reuse_scope_id, member_key, revision_id, item_sequence, updated_at)
                SELECT :deck, r.reuse_scope_id, r.member_key, r.revision_id, r.item_sequence, statement_timestamp()
                  FROM (VALUES\s""" + values + """
                ) AS v(member_key, descriptor_root_id)
                  JOIN app_learning.item_revision r ON r.reuse_scope_id = :scope AND r.member_key = v.member_key
                   AND r.descriptor_root_id = v.descriptor_root_id
                """).params(params).update();
    }

    /** Writes the head of each exercise of a manifest page at its manifest ordinal ({@code firstOrdinal + index}); returns how many entries found their revision. */
    int insertExerciseHeads(UUID deck, UUID scope, List<Entry> entries, int firstOrdinal) {
        Map<String, Object> params = valueParams(entries, firstOrdinal);
        params.put("deck", deck);
        params.put("scope", scope);
        return jdbc.sql("""
                INSERT INTO app_learning.deck_head_exercise(deck_id, reuse_scope_id, exercise_id, revision_id, exercise_sequence, ordinal, updated_at)
                SELECT :deck, r.reuse_scope_id, r.exercise_id, r.revision_id, r.exercise_sequence, v.ordinal, statement_timestamp()
                  FROM (VALUES\s""" + values(entries.size()) + """
                ) AS v(exercise_id, descriptor_root_id, ordinal)
                  JOIN app_learning.exercise_revision r ON r.reuse_scope_id = :scope AND r.exercise_id = v.exercise_id
                   AND r.descriptor_root_id = v.descriptor_root_id
                """).params(params).update();
    }

    /**
     * Writes the objective head of every objective that an ASSESSED binding of these exercises names, at the objective revision the binding pins. When two exercises of the
     * deck pin different revisions of one objective the newest wins (the revision the source's own head would hold); a later slice may raise it, never lower it.
     */
    void upsertObjectiveHeads(UUID deck, UUID scope, List<Entry> entries) {
        Map<String, Object> params = valueParams(entries, 0);
        params.put("deck", deck);
        params.put("scope", scope);
        jdbc.sql("""
                INSERT INTO app_learning.objective_head(deck_id, reuse_scope_id, objective_id, revision_id, objective_sequence, updated_at)
                SELECT :deck, :scope, x.objective_id, x.revision_id, x.objective_sequence, statement_timestamp()
                  FROM (SELECT DISTINCT ON (b.objective_id) b.objective_id, o.revision_id, o.objective_sequence
                          FROM (VALUES\s""" + values(entries.size()) + """
                        ) AS v(exercise_id, descriptor_root_id, ordinal)
                          JOIN app_learning.exercise_revision r ON r.reuse_scope_id = :scope AND r.exercise_id = v.exercise_id
                           AND r.descriptor_root_id = v.descriptor_root_id
                          JOIN app_learning.exercise_content_binding b ON b.reuse_scope_id = r.reuse_scope_id AND b.exercise_id = r.exercise_id
                           AND b.exercise_revision_id = r.revision_id AND b.role = 'ASSESSED'
                          JOIN app_learning.objective_revision o ON o.reuse_scope_id = b.reuse_scope_id AND o.objective_id = b.objective_id
                           AND o.revision_id = b.objective_revision_id
                         ORDER BY b.objective_id, o.objective_sequence DESC) x
                ON CONFLICT (deck_id, objective_id) DO UPDATE
                   SET revision_id = EXCLUDED.revision_id, objective_sequence = EXCLUDED.objective_sequence, updated_at = EXCLUDED.updated_at
                 WHERE app_learning.objective_head.objective_sequence < EXCLUDED.objective_sequence
                """).params(params).update();
    }

    private static String values(int count) {
        StringBuilder values = new StringBuilder();
        for (int i = 0; i < count; i++) {
            values.append(i == 0 ? "" : ",").append("(CAST(:e").append(i).append(" AS uuid), CAST(:d").append(i).append(" AS uuid), CAST(:o").append(i).append(" AS integer))");
        }
        return values.toString();
    }

    private static Map<String, Object> valueParams(List<Entry> entries, int firstOrdinal) {
        Map<String, Object> params = new HashMap<>();
        for (int i = 0; i < entries.size(); i++) {
            params.put("e" + i, entries.get(i).key());
            params.put("d" + i, entries.get(i).target().objectId());
            params.put("o" + i, firstOrdinal + i);
        }
        return params;
    }

    /** What the heads of the copy hold, against what the manifest and the bindings say they must. */
    record Verification(int items, int exercises, int firstOrdinal, int lastOrdinal, int expectedObjectives, int objectives, int objectiveMismatches) { }

    Verification verify(UUID deck) {
        return jdbc.sql("""
                WITH expected AS (
                    SELECT DISTINCT ON (b.objective_id) b.objective_id, o.revision_id, o.objective_sequence
                      FROM app_learning.deck_head_exercise h
                      JOIN app_learning.exercise_content_binding b ON b.reuse_scope_id = h.reuse_scope_id AND b.exercise_id = h.exercise_id
                       AND b.exercise_revision_id = h.revision_id AND b.role = 'ASSESSED'
                      JOIN app_learning.objective_revision o ON o.reuse_scope_id = b.reuse_scope_id AND o.objective_id = b.objective_id
                       AND o.revision_id = b.objective_revision_id
                     WHERE h.deck_id = :deck
                     ORDER BY b.objective_id, o.objective_sequence DESC)
                SELECT (SELECT count(*) FROM app_learning.deck_head_item WHERE deck_id = :deck)::integer AS items,
                       (SELECT count(*) FROM app_learning.deck_head_exercise WHERE deck_id = :deck)::integer AS exercises,
                       COALESCE((SELECT min(ordinal) FROM app_learning.deck_head_exercise WHERE deck_id = :deck), 0) AS first_ordinal,
                       COALESCE((SELECT max(ordinal) FROM app_learning.deck_head_exercise WHERE deck_id = :deck), -1) AS last_ordinal,
                       (SELECT count(*) FROM expected)::integer AS expected_objectives,
                       (SELECT count(*) FROM app_learning.objective_head WHERE deck_id = :deck)::integer AS objectives,
                       (SELECT count(*) FROM expected e LEFT JOIN app_learning.objective_head oh
                          ON oh.deck_id = :deck AND oh.objective_id = e.objective_id
                        WHERE oh.revision_id IS DISTINCT FROM e.revision_id OR oh.objective_sequence IS DISTINCT FROM e.objective_sequence)::integer AS mismatches
                """).param("deck", deck)
                .query((row, ignored) -> new Verification(row.getInt("items"), row.getInt("exercises"), row.getInt("first_ordinal"), row.getInt("last_ordinal"),
                        row.getInt("expected_objectives"), row.getInt("objectives"), row.getInt("mismatches"))).single();
    }

    /** Ends the preparing state; the same transaction ends the job. */
    void markMaterialized(UUID deck) {
        int updated = jdbc.sql("UPDATE app_learning.deck_fork SET materialized_at = statement_timestamp() WHERE deck_id = :deck AND materialized_at IS NULL")
                .param("deck", deck).update();
        if (updated != 1) throw new IllegalStateException("The copy was materialized already");
    }
}
