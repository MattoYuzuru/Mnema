package app.mnema.learning.catalog.deck;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only aggregates behind the Deck hub insights. Everything derives from current projections of ONE Deck; no
 * attempt or evidence row is read. The per-material study state uses exactly the rule of {@code study-progress}
 * (a material without enabled exercises is NOT_STARTED), so insights never invents a second state vocabulary.
 */
@Repository
class DeckInsightsRepository {
    private final JdbcClient jdbc;

    DeckInsightsRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    record Head(UUID revisionId, long version) { }

    /** Materials grouped by state, coverage and due-day bucket ({@code 0..6} or {@code 7} = outside the window, null = none). */
    record MaterialGroup(String state, boolean covered, Integer bucket, int materials) { }

    record Mechanic(String type, int exercises) { }

    record Captures(int open, Instant oldestOpenCreatedAt) { }

    Optional<Head> head(UUID actor, UUID deck) {
        return jdbc.sql("SELECT head_revision_id,row_version FROM app_learning.deck "
                        + "WHERE owner_id=:actor AND deck_id=:deck AND deleted_at IS NULL")
                .param("actor", actor).param("deck", deck)
                .query((row, ignored) -> new Head(row.getObject("head_revision_id", UUID.class), row.getLong("row_version")))
                .optional();
    }

    /**
     * Every statement here aggregates the WHOLE Deck, so only hash or merge joins are appropriate. On tables that were
     * just bulk-loaded the planner has no statistics, estimates one row per CTE and picks a nested loop that rescans a
     * materialized set once per outer row (measured: 2.1 s for 10 000 materials, quadratic in Deck size). Transaction-local.
     */
    void preferSetJoins() { jdbc.sql("SET LOCAL enable_nestloop = off").update(); }

    Instant now() { return jdbc.sql("SELECT statement_timestamp()").query(Timestamp.class).single().toInstant(); }

    List<MaterialGroup> materials(UUID actor, UUID deck, Instant asOf, String zone, LocalDate today) {
        return jdbc.sql("""
                WITH exercise AS MATERIALIZED (
                    SELECT binding.member_key,binding.objective_id
                      FROM app_learning.deck_head_exercise head
                      JOIN app_learning.exercise_revision revision ON revision.reuse_scope_id=head.reuse_scope_id
                       AND revision.exercise_id=head.exercise_id AND revision.revision_id=head.revision_id AND revision.enabled
                      JOIN app_learning.exercise_content_binding binding ON binding.reuse_scope_id=head.reuse_scope_id
                       AND binding.exercise_id=head.exercise_id AND binding.exercise_revision_id=head.revision_id
                       AND binding.role='ASSESSED'
                     WHERE head.deck_id=:deck
                ), objective AS MATERIALIZED (
                    SELECT member_key,objective_id FROM exercise GROUP BY member_key,objective_id
                ), exercise_count AS MATERIALIZED (
                    SELECT member_key,count(*)::integer AS exercises FROM exercise GROUP BY member_key
                ), per_member AS MATERIALIZED (
                    SELECT item.member_key,
                           count(objective.objective_id)::integer AS enabled,
                           count(state.objective_id)::integer AS introduced,
                           count(state.last_assessed_at)::integer AS assessed,
                           bool_or(state.next_due IS NOT NULL AND state.next_due<=:asOf) AS due,
                           bool_and(state.objective_id IS NOT NULL AND state.last_assessed_at IS NOT NULL
                               AND state.level>=2 AND state.next_due>:asOf) AS all_on_track,
                           min(state.next_due) AS next_due
                      FROM app_learning.deck_head_item item
                      LEFT JOIN objective ON objective.member_key=item.member_key
                      LEFT JOIN app_learning.study_state state ON state.account_id=:actor AND state.deck_id=item.deck_id
                       AND state.objective_id=objective.objective_id
                     WHERE item.deck_id=:deck
                     GROUP BY item.member_key
                ), classified AS (
                    SELECT CASE WHEN member.enabled=0 OR member.introduced=0 THEN 'NOT_STARTED'
                                WHEN COALESCE(member.due,FALSE) THEN 'DUE'
                                WHEN member.introduced<member.enabled OR member.assessed<member.introduced
                                     OR NOT COALESCE(member.all_on_track,FALSE) THEN 'LEARNING'
                                ELSE 'ON_TRACK' END AS state,
                           COALESCE(count.exercises,0)>0 AS covered,
                           CASE WHEN member.next_due IS NULL THEN NULL
                                ELSE LEAST(GREATEST((member.next_due AT TIME ZONE CAST(:zone AS text))::date
                                                    - CAST(:today AS date),0),7) END AS bucket
                      FROM per_member member LEFT JOIN exercise_count count ON count.member_key=member.member_key
                )
                SELECT state,covered,bucket,count(*)::integer AS materials
                  FROM classified GROUP BY state,covered,bucket
                """).param("actor", actor).param("deck", deck).param("asOf", Timestamp.from(asOf))
                .param("zone", zone).param("today", today)
                .query((row, ignored) -> new MaterialGroup(row.getString("state"), row.getBoolean("covered"),
                        row.getObject("bucket", Integer.class), row.getInt("materials"))).list();
    }

    /** Enabled current exercises of live materials per mechanic; mechanics without exercises are absent. */
    List<Mechanic> mechanics(UUID deck) {
        return jdbc.sql("""
                SELECT revision.exercise_type,count(*)::integer AS exercises
                  FROM app_learning.deck_head_exercise head
                  JOIN app_learning.exercise_revision revision ON revision.reuse_scope_id=head.reuse_scope_id
                   AND revision.exercise_id=head.exercise_id AND revision.revision_id=head.revision_id AND revision.enabled
                  JOIN app_learning.exercise_content_binding binding ON binding.reuse_scope_id=head.reuse_scope_id
                   AND binding.exercise_id=head.exercise_id AND binding.exercise_revision_id=head.revision_id
                   AND binding.role='ASSESSED'
                  JOIN app_learning.deck_head_item item ON item.deck_id=head.deck_id AND item.member_key=binding.member_key
                 WHERE head.deck_id=:deck GROUP BY revision.exercise_type
                """).param("deck", deck)
                .query((row, ignored) -> new Mechanic(row.getString("exercise_type"), row.getInt("exercises"))).list();
    }

    /** This Deck's notes that are neither archived nor converted, the same rule as the capture inbox. */
    Captures captures(UUID actor, UUID deck) {
        return jdbc.sql("""
                SELECT count(*)::integer AS open,min(created_at) AS oldest FROM app_learning.capture_note
                 WHERE owner_id=:actor AND deck_id=:deck AND NOT archived AND conversion_command_id IS NULL
                """).param("actor", actor).param("deck", deck)
                .query((row, ignored) -> new Captures(row.getInt("open"),
                        row.getTimestamp("oldest") == null ? null : row.getTimestamp("oldest").toInstant()))
                .single();
    }
}
