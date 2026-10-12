package app.mnema.learning.update.history;

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
 * The SQL of returning units of an apply (Updates/6 part B, #445): the copy's head and manifests, the places of the exercises, the bindings that tie the units of one apply
 * together, the objective heads the result must have, and the writes of the final transaction (the decision rows). Every read is addressed by the copy's own deck id or by the
 * revisions the apply's journal named.
 */
@Repository
class RevertRepository {
    /** The copy's head revision and its two manifests. */
    record Head(UUID revisionId, UUID membersRootId, int memberCount, int exerciseCount) { }

    /** The head of an exercise and its place. */
    record ExerciseHead(UUID revisionId, int ordinal) { }

    /** A pair joined by a binding: the exercise (revision adopted by the apply, or replaced by it) and a material of the same apply. */
    record Link(UUID exerciseId, UUID memberKey) { }

    private final JdbcClient jdbc;

    RevertRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    Optional<Head> head(UUID deck) {
        return jdbc.sql("""
                SELECT r.revision_id,r.members_root_id,r.member_count,r.exercise_count FROM app_learning.deck d
                  JOIN app_learning.deck_revision r ON r.deck_id=d.deck_id AND r.revision_id=d.head_revision_id WHERE d.deck_id=:deck
                """).param("deck", deck)
                .query((row, ignored) -> new Head(row.getObject("revision_id", UUID.class), row.getObject("members_root_id", UUID.class), row.getInt("member_count"),
                        row.getInt("exercise_count"))).optional();
    }

    /** The revision the apply was made from (its parent): the manifests the apply's journal ordinals are places in. */
    Optional<Head> parent(UUID deck, UUID revision) {
        return jdbc.sql("""
                SELECT p.revision_id,p.members_root_id,p.member_count,p.exercise_count FROM app_learning.deck_revision r
                  JOIN app_learning.deck_revision p ON p.deck_id=r.deck_id AND p.revision_id=r.parent_revision_id WHERE r.deck_id=:deck AND r.revision_id=:revision
                """).param("deck", deck).param("revision", revision)
                .query((row, ignored) -> new Head(row.getObject("revision_id", UUID.class), row.getObject("members_root_id", UUID.class), row.getInt("member_count"),
                        row.getInt("exercise_count"))).optional();
    }

    Map<UUID, ExerciseHead> exerciseHeads(UUID deck, Collection<UUID> ids) {
        Map<UUID, ExerciseHead> result = new HashMap<>();
        if (ids.isEmpty()) return result;
        jdbc.sql("""
                SELECT exercise_id,revision_id,ordinal FROM app_learning.deck_head_exercise
                 WHERE deck_id=:deck AND exercise_id IN (SELECT CAST(k AS uuid) FROM unnest(CAST(:ids AS text[])) AS t(k))
                """).param("deck", deck).param("ids", HistoryRepository.text(ids))
                .query((row, ignored) -> result.put(row.getObject("exercise_id", UUID.class), new ExerciseHead(row.getObject("revision_id", UUID.class), row.getInt("ordinal")))).list();
        return result;
    }

    /**
     * The exercises and materials of ONE apply that its bindings tie together, restricted to pairs that touch the given units: an exercise of the apply (in the revision the apply
     * made, or the one it replaced when it removed it) bound, in any role, to a material the same apply created, replaced or removed.
     */
    Set<Link> links(UUID deck, UUID scope, UUID revision, Collection<UUID> exercises, Collection<UUID> members) {
        Set<Link> result = new HashSet<>();
        jdbc.sql("""
                SELECT DISTINCT c.exercise_id,b.member_key
                  FROM app_learning.deck_exercise_change c
                  JOIN app_learning.exercise_content_binding b ON b.reuse_scope_id=:scope AND b.exercise_id=c.exercise_id
                   AND b.exercise_revision_id=COALESCE(c.revision_id,c.previous_revision_id)
                  JOIN app_learning.deck_item_change i ON i.deck_id=c.deck_id AND i.deck_revision_id=c.deck_revision_id AND i.member_key=b.member_key AND i.change_kind <> 'reorder'
                 WHERE c.deck_id=:deck AND c.deck_revision_id=:revision
                   AND (c.exercise_id IN (SELECT CAST(k AS uuid) FROM unnest(CAST(:exercises AS text[])) AS t(k))
                        OR b.member_key IN (SELECT CAST(k AS uuid) FROM unnest(CAST(:members AS text[])) AS t(k)))
                """).param("deck", deck).param("scope", scope).param("revision", revision).param("exercises", HistoryRepository.text(exercises))
                .param("members", HistoryRepository.text(members))
                .query((row, ignored) -> result.add(new Link(row.getObject("exercise_id", UUID.class), row.getObject("member_key", UUID.class)))).list();
        return result;
    }

    /** The exercises of the copy's HEAD that have a binding, in any role, to one of these materials. */
    Set<UUID> headExercisesBoundTo(UUID deck, UUID scope, Collection<UUID> members) {
        Set<UUID> result = new HashSet<>();
        if (members.isEmpty()) return result;
        jdbc.sql("""
                SELECT DISTINCT b.exercise_id FROM app_learning.deck_head_exercise h
                  JOIN app_learning.exercise_content_binding b ON b.reuse_scope_id=h.reuse_scope_id AND b.exercise_id=h.exercise_id AND b.exercise_revision_id=h.revision_id
                 WHERE h.deck_id=:deck AND b.reuse_scope_id=:scope AND b.member_key IN (SELECT CAST(k AS uuid) FROM unnest(CAST(:members AS text[])) AS t(k))
                """).param("deck", deck).param("scope", scope).param("members", HistoryRepository.text(members))
                .query((row, ignored) -> result.add(row.getObject(1, UUID.class))).list();
        return result;
    }

    /** The objectives (ASSESSED bindings) of these exercise revisions. */
    Set<UUID> assessedObjectives(UUID scope, Map<UUID, UUID> exerciseRevisions) {
        Set<UUID> result = new HashSet<>();
        if (exerciseRevisions.isEmpty()) return result;
        List<UUID> ids = List.copyOf(exerciseRevisions.keySet());
        jdbc.sql("""
                SELECT DISTINCT b.objective_id FROM unnest(CAST(:exercises AS text[]),CAST(:revisions AS text[])) AS v(exercise_id,revision_id)
                  JOIN app_learning.exercise_content_binding b ON b.reuse_scope_id=:scope AND b.exercise_id=CAST(v.exercise_id AS uuid)
                   AND b.exercise_revision_id=CAST(v.revision_id AS uuid) AND b.role='ASSESSED'
                """).param("scope", scope).param("exercises", HistoryRepository.text(ids))
                .param("revisions", ids.stream().map(id -> exerciseRevisions.get(id).toString()).toArray(String[]::new))
                .query((row, ignored) -> result.add(row.getObject(1, UUID.class))).list();
        return result;
    }

    /**
     * The head each of these objectives must have once the exercises of the copy are {@code head exercises except `left`, plus `adopted` at their revisions}: the NEWEST revision
     * (by sequence) that an ASSESSED binding of those exercises pins, the rule the combined publication verifies. An objective no such exercise pins is absent from the answer.
     */
    Map<UUID, UUID> expectedObjectiveHeads(UUID deck, UUID scope, Collection<UUID> objectives, Collection<UUID> left, Map<UUID, UUID> adopted) {
        Map<UUID, UUID> result = new HashMap<>();
        if (objectives.isEmpty()) return result;
        List<UUID> ids = List.copyOf(adopted.keySet());
        jdbc.sql("""
                SELECT DISTINCT ON (b.objective_id) b.objective_id,b.objective_revision_id
                  FROM app_learning.exercise_content_binding b
                  JOIN app_learning.objective_revision o ON o.reuse_scope_id=b.reuse_scope_id AND o.objective_id=b.objective_id AND o.revision_id=b.objective_revision_id
                 WHERE b.reuse_scope_id=:scope AND b.role='ASSESSED'
                   AND b.objective_id IN (SELECT CAST(k AS uuid) FROM unnest(CAST(:objectives AS text[])) AS t(k))
                   AND ((b.exercise_id,b.exercise_revision_id) IN (
                            SELECT h.exercise_id,h.revision_id FROM app_learning.deck_head_exercise h
                             WHERE h.deck_id=:deck AND h.exercise_id NOT IN (SELECT CAST(k AS uuid) FROM unnest(CAST(:left AS text[])) AS t(k)))
                        OR (b.exercise_id,b.exercise_revision_id) IN (
                            SELECT CAST(v.exercise_id AS uuid),CAST(v.revision_id AS uuid) FROM unnest(CAST(:exercises AS text[]),CAST(:revisions AS text[])) AS v(exercise_id,revision_id)))
                 ORDER BY b.objective_id,o.objective_sequence DESC
                """).param("deck", deck).param("scope", scope).param("objectives", HistoryRepository.text(objectives)).param("left", HistoryRepository.text(left))
                .param("exercises", HistoryRepository.text(ids)).param("revisions", ids.stream().map(id -> adopted.get(id).toString()).toArray(String[]::new))
                .query((row, ignored) -> result.put(row.getObject(1, UUID.class), row.getObject(2, UUID.class))).list();
        return result;
    }

    // ---- the final transaction ----

    /** Locks the copy's link (a concurrent apply or return of the same copy waits); empty when it is not a live, attached, materialized copy. */
    boolean lockCopy(UUID deck) {
        return jdbc.sql("""
                SELECT 1 FROM app_learning.deck_fork WHERE deck_id=:deck AND released_at IS NULL AND detached_at IS NULL AND materialized_at IS NOT NULL FOR UPDATE
                """).param("deck", deck).query(Integer.class).optional().isPresent();
    }

    Optional<UUID> lockHead(UUID deck) {
        return jdbc.sql("SELECT head_revision_id FROM app_learning.deck WHERE deck_id=:deck FOR SHARE").param("deck", deck).query(UUID.class).optional();
    }

    /** One decision row per returned unit: the decision, and the author's state it covers ({@code -} for "the author had removed it"). */
    void decide(UUID deck, List<RevertWrites.Row> rows) {
        if (rows.isEmpty()) return;
        jdbc.sql("""
                INSERT INTO app_learning.fork_unit_decision(deck_id,reuse_scope_id,unit_kind,unit_key,decision,resolved_upstream_revision_id,resolved_upstream_absent,
                                                            pending_base_revision_id,pending_base_absent,decided_at)
                SELECT d.deck_id,d.reuse_scope_id,v.unit_kind,CAST(v.unit_key AS uuid),v.decision,
                       CASE WHEN v.resolved='-' THEN NULL ELSE CAST(v.resolved AS uuid) END,v.resolved='-',NULL,FALSE,clock_timestamp()
                  FROM app_learning.deck d
                 CROSS JOIN unnest(CAST(:kinds AS text[]),CAST(:keys AS text[]),CAST(:decisions AS text[]),CAST(:resolved AS text[])) AS v(unit_kind,unit_key,decision,resolved)
                 WHERE d.deck_id=:deck
                ON CONFLICT (deck_id,unit_kind,unit_key) DO UPDATE SET decision=EXCLUDED.decision,resolved_upstream_revision_id=EXCLUDED.resolved_upstream_revision_id,
                    resolved_upstream_absent=EXCLUDED.resolved_upstream_absent,pending_base_revision_id=NULL,pending_base_absent=FALSE,decided_at=EXCLUDED.decided_at
                """).param("deck", deck).param("kinds", rows.stream().map(row -> row.kind().name()).toArray(String[]::new))
                .param("keys", rows.stream().map(row -> row.key().toString()).toArray(String[]::new))
                .param("decisions", rows.stream().map(RevertWrites.Row::decision).toArray(String[]::new))
                .param("resolved", rows.stream().map(RevertWrites.Row::resolved).toArray(String[]::new)).update();
    }
}
