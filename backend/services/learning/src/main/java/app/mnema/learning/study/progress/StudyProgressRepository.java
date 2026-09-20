package app.mnema.learning.study.progress;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
class StudyProgressRepository {
    private final JdbcClient jdbc;

    StudyProgressRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    record Material(UUID memberKey, UUID itemRevisionId, int enabled, int introduced, int assessed,
                    boolean due, boolean allOnTrack, Instant lastAssessedAt, Instant nextDue) { }

    boolean ownsDeck(UUID actor, UUID deck) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM app_learning.deck WHERE owner_id=:actor AND deck_id=:deck)")
                .param("actor", actor).param("deck", deck).query(Boolean.class).single();
    }

    List<Material> page(UUID actor, UUID deck, UUID after, int limit, Instant asOf) {
        String cursor = after == null ? "" : " AND item.member_key > :after";
        var query = jdbc.sql("""
                SELECT item.member_key,item.revision_id,
                       COALESCE(progress.enabled,0) AS enabled,
                       COALESCE(progress.introduced,0) AS introduced,
                       COALESCE(progress.assessed,0) AS assessed,
                       COALESCE(progress.due,FALSE) AS due,
                       COALESCE(progress.all_on_track,FALSE) AS all_on_track,
                       progress.last_assessed_at,progress.next_due
                  FROM app_learning.deck_head_item item
                  LEFT JOIN LATERAL (
                       SELECT count(*)::integer AS enabled,
                              count(state.objective_id)::integer AS introduced,
                              count(*) FILTER (WHERE state.last_assessed_at IS NOT NULL)::integer AS assessed,
                              bool_or(state.next_due IS NOT NULL AND state.next_due<=:asOf) AS due,
                              bool_and(state.objective_id IS NOT NULL AND state.last_assessed_at IS NOT NULL
                                  AND state.level>=2 AND state.next_due>:asOf) AS all_on_track,
                              max(state.last_assessed_at) AS last_assessed_at,
                              min(state.next_due) AS next_due
                         FROM (
                              SELECT DISTINCT binding.objective_id
                                FROM app_learning.deck_head_exercise head
                                JOIN app_learning.exercise_revision revision
                                  ON revision.deck_id=head.deck_id AND revision.exercise_id=head.exercise_id
                                 AND revision.revision_id=head.revision_id AND revision.enabled
                                JOIN app_learning.exercise_content_binding binding
                                  ON binding.deck_id=head.deck_id AND binding.exercise_id=head.exercise_id
                                 AND binding.exercise_revision_id=head.revision_id AND binding.role='ASSESSED'
                               WHERE head.deck_id=item.deck_id AND binding.member_key=item.member_key
                         ) objective
                         LEFT JOIN app_learning.study_state state
                           ON state.account_id=:actor AND state.deck_id=item.deck_id
                          AND state.objective_id=objective.objective_id
                  ) progress ON TRUE
                 WHERE item.deck_id=:deck
                """ + cursor + " ORDER BY item.member_key LIMIT :limit")
                .param("actor", actor).param("deck", deck).param("asOf", java.sql.Timestamp.from(asOf))
                .param("limit", limit);
        if (after != null) query.param("after", after);
        return query.query((row, ignored) -> new Material(
                row.getObject("member_key", UUID.class), row.getObject("revision_id", UUID.class),
                row.getInt("enabled"), row.getInt("introduced"), row.getInt("assessed"),
                row.getBoolean("due"), row.getBoolean("all_on_track"),
                row.getTimestamp("last_assessed_at") == null ? null : row.getTimestamp("last_assessed_at").toInstant(),
                row.getTimestamp("next_due") == null ? null : row.getTimestamp("next_due").toInstant())).list();
    }

    Instant now() { return jdbc.sql("SELECT statement_timestamp()")
            .query(java.sql.Timestamp.class).single().toInstant(); }
}
