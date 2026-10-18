package app.mnema.learning.onboarding;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * The SQL of the milestone backfill (OB-302). Per milestone with a restorable source there is one {@link Source}: the table and column that lead its owners by an index
 * (the cursor walks them in order) and a {@code history} query that answers, for the accounts in {@code (:from, :to]}, the progress the history shows, the time of the first
 * fact and, when the history reaches the target, the time of the fact that did. Its conditions mirror the live writers: purpose USER for authoring, SCHEDULED or PRACTICE for
 * sessions, media that APPEARS (a revision that references an asset its parent revision did not, a first revision with any), somebody else's USER deck for a community copy, origin rows only (a copy's inherited materials and exercises belong to the lineage's origin deck, and an accepted
 * update writes heads only), so nothing materialized or adopted counts. {@code RHYTHM_WEEK} has no history.
 *
 * <p>A batch is two statements: {@link #upperBound} finds the last of the next {@code batch} owners by a loose index scan (one index probe per owner), and {@link #write} merges
 * the history of the owners in the range with one {@code INSERT ... SELECT ... ON CONFLICT DO UPDATE}. The merge never lowers a row: {@code progress = GREATEST(existing, history)},
 * {@code first_at = LEAST(...)}, an existing {@code achieved_at} is kept and {@code source} of an existing row is never touched (a LIVE row stays LIVE); a row that would not change
 * is not offered to the insert at all, so a rerun takes no row lock. It publishes nothing. A rerun finds nothing to change. A row a live writer commits after the
 * statement's snapshot (a third exercise, a second date) may be under-counted by that batch; the next live event of the account corrects it.
 */
@Repository
class BackfillRepository {
    /** The owner column that leads an index of {@code table} and the history query of one milestone (parameters {@code :from} exclusive and {@code :to} inclusive). */
    record Source(MilestoneKey key, String table, String owner, String history) { }

    /** Rows written and, of them, the milestones that the backfill itself achieved. */
    record Written(long rows, long achieved) { }

    /** In registry order; the cursor of the job is the key and the last owner. */
    static final List<Source> SOURCES = List.of(
            new Source(MilestoneKey.GOAL_TOLD, "learning_profile", "owner_id", """
                    SELECT owner_id AS account_id, 1 AS progress, answered_at AS first_at, answered_at AS achieved_at
                      FROM app_learning.learning_profile WHERE owner_id > :from AND owner_id <= :to
                    """),
            new Source(MilestoneKey.FIRST_DECK, "deck", "owner_id", """
                    SELECT owner_id AS account_id, 1 AS progress, min(created_at) AS first_at, min(created_at) AS achieved_at
                      FROM app_learning.deck WHERE owner_id > :from AND owner_id <= :to GROUP BY owner_id
                    """),
            new Source(MilestoneKey.FIRST_SESSION, "study_session", "account_id", """
                    SELECT account_id, 1 AS progress, min(completed_at) AS first_at, min(completed_at) AS achieved_at
                      FROM app_learning.study_session WHERE account_id > :from AND account_id <= :to
                       AND status = 'COMPLETE' AND mode IN ('SCHEDULED', 'PRACTICE') GROUP BY account_id
                    """),
            new Source(MilestoneKey.OWN_MATERIAL, "deck", "owner_id", """
                    SELECT d.owner_id AS account_id, 1 AS progress, min(x.at) AS first_at, min(x.at) AS achieved_at
                      FROM app_learning.deck d
                      CROSS JOIN LATERAL (SELECT min(li.created_at) AS at FROM app_learning.learning_item li WHERE li.deck_id = d.deck_id) x
                     WHERE d.owner_id > :from AND d.owner_id <= :to AND d.purpose = 'USER' AND x.at IS NOT NULL GROUP BY d.owner_id
                    """),
            new Source(MilestoneKey.MEDIA_IN_MATERIAL, "deck", "owner_id", """
                    SELECT d.owner_id AS account_id, 1 AS progress, min(x.at) AS first_at, min(x.at) AS achieved_at
                      FROM app_learning.deck d
                      CROSS JOIN LATERAL (SELECT min(ir.created_at) AS at FROM app_learning.item_revision ir
                                           WHERE ir.deck_id = d.deck_id
                                             AND EXISTS (SELECT 1 FROM app_learning.content_media_ref r
                                                          WHERE r.reuse_scope_id = ir.reuse_scope_id AND r.member_key = ir.member_key AND r.revision_id = ir.revision_id
                                                            -- the parent's assets as a correlated array (a primary-key probe per revision): an anti-join here is planned as a hash over the whole table
                                                            AND r.asset_id <> ALL (ARRAY(SELECT p.asset_id FROM app_learning.content_media_ref p
                                                                                          WHERE p.reuse_scope_id = ir.reuse_scope_id AND p.member_key = ir.member_key
                                                                                            AND p.revision_id = ir.parent_revision_id)))) x
                     WHERE d.owner_id > :from AND d.owner_id <= :to AND d.purpose = 'USER' AND x.at IS NOT NULL GROUP BY d.owner_id
                    """),
            new Source(MilestoneKey.CAPTURE_NOTE, "capture_note", "owner_id", """
                    SELECT cn.owner_id AS account_id,
                           CASE WHEN bool_or(cn.converted_at IS NOT NULL OR cn.archived) THEN 2 ELSE 1 END AS progress,
                           min(cn.created_at) AS first_at,
                           min(COALESCE(cn.converted_at, cn.updated_at)) FILTER (WHERE cn.converted_at IS NOT NULL OR cn.archived) AS achieved_at
                      FROM app_learning.capture_note cn
                      CROSS JOIN LATERAL (SELECT 1 FROM app_learning.deck d WHERE d.deck_id = cn.deck_id AND d.purpose = 'USER') u
                     WHERE cn.owner_id > :from AND cn.owner_id <= :to GROUP BY cn.owner_id
                    """),
            new Source(MilestoneKey.OWN_EXERCISES, "deck", "owner_id", """
                    WITH e AS (
                        SELECT d.owner_id, ed.created_at, row_number() OVER (PARTITION BY d.owner_id ORDER BY ed.created_at, ed.exercise_id) AS n
                          FROM app_learning.deck d
                          CROSS JOIN LATERAL (SELECT created_at, exercise_id FROM app_learning.exercise_definition x WHERE x.deck_id = d.deck_id ORDER BY created_at, exercise_id LIMIT 3) ed
                         WHERE d.owner_id > :from AND d.owner_id <= :to AND d.purpose = 'USER')
                    SELECT owner_id AS account_id, count(*) AS progress, min(created_at) AS first_at, max(created_at) FILTER (WHERE n = 3) AS achieved_at
                      FROM e WHERE n <= 3 GROUP BY owner_id
                    """),
            new Source(MilestoneKey.AI_EXPLANATION, "study_evidence", "account_id", """
                    SELECT account_id, 1 AS progress, min(accepted_at) AS first_at, min(accepted_at) AS achieved_at
                      FROM app_learning.study_evidence WHERE account_id > :from AND account_id <= :to AND evaluator_id = 'ai-semantic' GROUP BY account_id
                    """),
            new Source(MilestoneKey.VOICE_ANSWER, "study_assessment", "account_id", """
                    SELECT account_id, 1 AS progress, min(created_at) AS first_at, min(created_at) AS achieved_at
                      FROM app_learning.study_assessment WHERE account_id > :from AND account_id <= :to AND answer_source = 'SPEECH' GROUP BY account_id
                    """),
            // The provenance outlives the Workshop session (retention purges the session, not the audit row): a session that is gone cannot be asked which deck it targeted.
            new Source(MilestoneKey.WORKSHOP_APPROVED, "generation_provenance", "owner_id", """
                    SELECT gp.owner_id AS account_id, 1 AS progress, min(gp.created_at) AS first_at, min(gp.created_at) AS achieved_at
                      FROM app_learning.generation_provenance gp
                      LEFT JOIN LATERAL (SELECT s.deck_id FROM app_learning.generation_session s WHERE s.session_id = gp.session_id) gs ON true
                      LEFT JOIN LATERAL (SELECT d.purpose FROM app_learning.deck d WHERE d.deck_id = gs.deck_id) d ON true
                     WHERE gp.owner_id > :from AND gp.owner_id <= :to AND (gs.deck_id IS NULL OR d.purpose = 'USER') GROUP BY gp.owner_id
                    """),
            new Source(MilestoneKey.TWO_DAYS, "study_session", "account_id", """
                    WITH days AS (
                        SELECT account_id, min(completed_at) AS at FROM app_learning.study_session
                         WHERE account_id > :from AND account_id <= :to
                           AND status = 'COMPLETE' AND mode IN ('SCHEDULED', 'PRACTICE') GROUP BY account_id, local_study_date),
                    ranked AS (SELECT account_id, at, row_number() OVER (PARTITION BY account_id ORDER BY at) AS n FROM days)
                    SELECT account_id, count(*) AS progress, min(at) AS first_at, max(at) FILTER (WHERE n = 2) AS achieved_at
                      FROM ranked WHERE n <= 2 GROUP BY account_id
                    """),
            new Source(MilestoneKey.COMMUNITY_COPY, "deck_fork", "owner_id", """
                    SELECT f.owner_id AS account_id, 1 AS progress, min(f.forked_at) AS first_at, min(f.forked_at) AS achieved_at
                      FROM app_learning.deck_fork f
                      -- the copy's own purpose (ForkService.copy writes USER, copyTemplate SAMPLE): the source may have become a template since the copy was made
                      CROSS JOIN LATERAL (SELECT 1 FROM app_learning.deck c WHERE c.deck_id = f.deck_id AND c.purpose = 'USER') c
                      CROSS JOIN LATERAL (SELECT 1 FROM app_learning.deck up WHERE up.deck_id = f.upstream_deck_id AND up.owner_id <> f.owner_id) u
                     WHERE f.owner_id > :from AND f.owner_id <= :to GROUP BY f.owner_id
                    """));

    /** The account id that precedes every account: the cursor before the first batch. */
    static final UUID START = new UUID(0L, 0L);

    private final JdbcClient jdbc;

    BackfillRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Whether the backfill of {@code version} has finished once (V74 {@code onboarding_backfill_run}): the enqueuer does not run it again. */
    boolean finished(int version) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM app_learning.onboarding_backfill_run WHERE registry_version = :v)").param("v", version).query(Boolean.class).single();
    }

    /** Records, in the last slice's transaction, that the backfill of {@code version} has finished. */
    void markFinished(int version) {
        jdbc.sql("INSERT INTO app_learning.onboarding_backfill_run(registry_version, finished_at) VALUES (:v, statement_timestamp()) ON CONFLICT (registry_version) DO NOTHING")
                .param("v", version).update();
    }

    /** The first statement of a slice: one transaction-scoped try-lock, so that two workers never backfill at once. */
    boolean lock() {
        return jdbc.sql("SELECT pg_try_advisory_xact_lock(hashtextextended('onboarding.backfill', 0))").query(Boolean.class).single();
    }

    /** The statement that finds the last of the next {@code batch} owners after {@code after}: a loose index scan, one probe of the owner index per owner. */
    static String upperBoundSql(Source source) {
        String t = "app_learning." + source.table();
        String c = source.owner();
        return "WITH RECURSIVE o(a, n) AS (SELECT (SELECT " + c + " FROM " + t + " WHERE " + c + " > :after ORDER BY " + c + " LIMIT 1), 1 "
                + "UNION ALL SELECT (SELECT " + c + " FROM " + t + " WHERE " + c + " > o.a ORDER BY " + c + " LIMIT 1), o.n + 1 FROM o WHERE o.a IS NOT NULL AND o.n < :batch) "
                + "SELECT a FROM o WHERE a IS NOT NULL ORDER BY n DESC LIMIT 1";
    }

    /** @return the last owner of the next batch, or null when no owner is left after {@code after} */
    UUID upperBound(Source source, UUID after, int batch) {
        return jdbc.sql(upperBoundSql(source)).param("after", after).param("batch", batch).query(UUID.class).optional().orElse(null);
    }

    /** The merge of one batch. */
    static String writeSql(Source source) {
        return """
                WITH h AS (""" + source.history() + """
                ),
                pre AS MATERIALIZED (
                    SELECT h.account_id, (o.achieved_at IS NOT NULL) AS had
                      FROM h LEFT JOIN app_learning.account_milestone o ON o.account_id = h.account_id AND o.milestone_key = CAST(:key AS text)
                       AND o.account_id > :from AND o.account_id <= :to
                ),
                w AS (
                    INSERT INTO app_learning.account_milestone AS m(account_id, milestone_key, progress, achieved_at, first_at, source)
                    SELECT h.account_id, CAST(:key AS text), CAST(LEAST(h.progress, CAST(:target AS integer)) AS smallint),
                           CASE WHEN h.progress >= CAST(:target AS integer) THEN h.achieved_at END, h.first_at, 'BACKFILL'
                      FROM h
                     -- a row the history would not change is not offered to the insert: ON CONFLICT DO UPDATE locks the conflicting row even when its WHERE is false,
                     -- so a rerun (or a LIVE row already ahead) would hold row locks of the whole batch until the slice commits
                     WHERE NOT EXISTS (SELECT 1 FROM app_learning.account_milestone o
                                        WHERE o.account_id = h.account_id AND o.milestone_key = CAST(:key AS text)
                                          AND o.progress >= LEAST(h.progress, CAST(:target AS integer)) AND o.first_at <= h.first_at)
                    ON CONFLICT (account_id, milestone_key) DO UPDATE
                       SET progress = GREATEST(m.progress, EXCLUDED.progress),
                           first_at = LEAST(m.first_at, EXCLUDED.first_at),
                           achieved_at = COALESCE(m.achieved_at, CASE WHEN GREATEST(m.progress, EXCLUDED.progress) >= CAST(:target AS integer) THEN EXCLUDED.achieved_at END)
                     WHERE m.progress < EXCLUDED.progress OR m.first_at > EXCLUDED.first_at
                    RETURNING m.account_id, m.achieved_at
                )
                SELECT count(*) AS written, count(*) FILTER (WHERE w.achieved_at IS NOT NULL AND NOT pre.had) AS achieved
                  FROM w JOIN pre ON pre.account_id = w.account_id
                """;
    }

    Written write(Source source, UUID after, UUID upper) {
        return jdbc.sql(writeSql(source)).param("from", after).param("to", upper).param("key", source.key().name()).param("target", source.key().target())
                .query((row, ignored) -> new Written(row.getLong("written"), row.getLong("achieved"))).single();
    }
}
