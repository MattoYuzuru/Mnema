package app.mnema.learning.community;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * The SQL of one rank generation (Community/3, #450, migration {@code V68}): every statement runs in the transaction of the job slice that calls it, and the generation is built by
 * <b>one set-based {@code INSERT ... SELECT}</b> over {@code public_deck} and {@code deck_engagement} (window functions for the ranks; nothing is computed row by row in Java).
 * The formula is RFC 11.2, see {@link RankSettings}.
 */
@Repository
class RankRepository {
    private final JdbcClient jdbc;

    RankRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /**
     * Serializes the builders of generations of the whole database (a transaction-level advisory lock, so two workers never number the same generation) and opens the next one:
     * {@code max + 1}, not completed. It becomes visible to readers only with the commit of the transaction that also completes it.
     */
    long open(Instant now) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended('catalog.rank', 0))").query().listOfRows();
        return jdbc.sql("""
                INSERT INTO app_learning.rank_generation(generation, started_at)
                SELECT COALESCE(max(generation), 0) + 1, :now FROM app_learning.rank_generation
                RETURNING generation
                """).param("now", at(now)).query(Long.class).single();
    }

    /**
     * Writes the rank of every {@code public_deck} row for {@code generation} and returns the number of rows. Per deck: the non-owner accounts with an event (a copy, a study
     * session) are folded to one line each ({@code per_account}), then to the sums of the formula ({@code per_deck}); the score is computed once and the three window functions
     * order the LISTED decks only ({@code PARTITION BY listed}; the other partition's values are discarded by the {@code CASE}). A deck without engagement scores 0 and is ordered by
     * {@code deck_id DESC} (a stable, arbitrary order). {@code trust} of a copy row that is missing (only a STUDY row exists) is the untrusted weight.
     */
    int fill(long generation, Instant now, RankSettings settings) {
        return statement("", generation, now, settings).update();
    }

    /** The statement of {@link #fill} behind {@code prefix} (empty, or {@code EXPLAIN (ANALYZE, BUFFERS) } for the evidence of the plan), bound to its parameters. */
    JdbcClient.StatementSpec statement(String prefix, long generation, Instant now, RankSettings settings) {
        return jdbc.sql(prefix + """
                WITH per_account AS (
                    SELECT e.deck_id, e.account_id,
                           max(e.first_at) FILTER (WHERE e.kind = 'FORK') AS fork_at,
                           COALESCE(max(e.trust) FILTER (WHERE e.kind = 'FORK'), :untrusted)::double precision AS trust,
                           COALESCE(max(e.study_days) FILTER (WHERE e.kind = 'STUDY'), 0) AS study_days,
                           max(e.last_at) FILTER (WHERE e.kind = 'STUDY') AS study_at
                      FROM app_learning.deck_engagement e
                      JOIN app_learning.public_deck p ON p.deck_id = e.deck_id AND e.account_id <> p.owner_id
                     GROUP BY e.deck_id, e.account_id
                ), per_deck AS (
                    SELECT a.deck_id,
                           sum(a.trust
                               * (CASE WHEN a.fork_at >= :since THEN CAST(:forkWeight AS double precision) ELSE 0 END
                                  + CASE WHEN a.study_days >= :activationDays AND a.study_at >= :since THEN CAST(:activatedWeight AS double precision) ELSE 0 END)
                               * power(2.0::double precision, -(GREATEST(0, extract(epoch FROM (CAST(:now AS timestamptz) - GREATEST(a.fork_at, a.study_at)))) / 86400.0)
                                                              / CAST(:halfLife AS double precision))) AS s,
                           (count(*) FILTER (WHERE a.fork_at IS NOT NULL))::integer AS forks,
                           (count(*) FILTER (WHERE a.study_days >= :activationDays))::integer AS activated,
                           (count(*) FILTER (WHERE a.study_at >= :since))::integer AS learning_now
                      FROM per_account a
                     GROUP BY a.deck_id
                ), scored AS (
                    SELECT p.deck_id, p.topic_id, p.parent_topic_id, p.listed,
                           COALESCE(d.forks, 0) AS forks, COALESCE(d.activated, 0) AS activated, COALESCE(d.learning_now, 0) AS learning_now,
                           ln(1 + COALESCE(d.s, 0))
                           * (0.5 + (COALESCE(d.activated, 0) + CAST(:priorWeight AS double precision) * CAST(:priorRate AS double precision))
                                    / (COALESCE(d.forks, 0) + CAST(:priorWeight AS double precision)))
                           * (CAST(:freshFloor AS double precision) + (1 - CAST(:freshFloor AS double precision))
                              * power(2.0::double precision, -(GREATEST(0, extract(epoch FROM (CAST(:now AS timestamptz) - p.content_updated_at))) / 86400.0)
                                                             / CAST(:freshHalfLife AS double precision))) AS score
                      FROM app_learning.public_deck p
                      LEFT JOIN per_deck d ON d.deck_id = p.deck_id
                )
                INSERT INTO app_learning.deck_rank(generation, deck_id, score, rank, learning_rank, pop_pct, added_count, learning_now_count, activated_count,
                                                   topic_id, parent_topic_id, listed)
                SELECT :generation, s.deck_id, s.score,
                       CASE WHEN s.listed THEN (row_number() OVER (PARTITION BY s.listed ORDER BY s.score DESC, s.deck_id DESC))::integer END,
                       CASE WHEN s.listed THEN (row_number() OVER (PARTITION BY s.listed ORDER BY s.learning_now DESC, s.score DESC, s.deck_id DESC))::integer END,
                       CASE WHEN s.listed THEN (percent_rank() OVER (PARTITION BY s.listed ORDER BY s.score))::real ELSE 0 END,
                       s.forks, s.learning_now, s.activated, s.topic_id, s.parent_topic_id, s.listed
                  FROM scored s
                """)
                .param("generation", generation).param("now", at(now)).param("since", at(now.minus(Duration.ofDays(settings.windowDays))))
                .param("untrusted", (double) DeckEngagement.UNTRUSTED_WEIGHT).param("forkWeight", settings.forkWeight).param("activatedWeight", settings.activatedWeight)
                .param("activationDays", settings.activationDays).param("halfLife", settings.halfLifeDays)
                .param("priorWeight", settings.priorWeight).param("priorRate", settings.priorRate)
                .param("freshFloor", settings.freshFloor).param("freshHalfLife", settings.freshHalfLifeDays);
    }

    /** Makes the generation the current one for readers (in the same transaction as its rows). */
    void complete(long generation, int decks, Instant now) {
        jdbc.sql("UPDATE app_learning.rank_generation SET completed_at = :now, deck_count = :decks WHERE generation = :generation")
                .param("now", at(now)).param("decks", decks).param("generation", generation).update();
    }

    /**
     * Deletes the generations completed before {@code now - retention} except the newest completed one (the list always has a current generation, however late the next job
     * is), and any generation that never completed and is older than that. {@code deck_rank} follows by {@code ON DELETE CASCADE}.
     */
    int prune(Instant now, Duration retention) {
        return jdbc.sql("""
                DELETE FROM app_learning.rank_generation g
                 WHERE ((g.completed_at IS NOT NULL AND g.completed_at < :cutoff
                         AND g.generation < (SELECT max(c.generation) FROM app_learning.rank_generation c WHERE c.completed_at IS NOT NULL))
                        OR (g.completed_at IS NULL AND g.started_at < :cutoff))
                """).param("cutoff", at(now.minus(retention))).update();
    }

    /** The newest completed generation and when it completed; empty before the first. One probe of the primary key from the top. */
    record Newest(long generation, Instant completedAt) { }

    java.util.Optional<Newest> newest() {
        return jdbc.sql("SELECT generation, completed_at FROM app_learning.rank_generation WHERE completed_at IS NOT NULL ORDER BY generation DESC LIMIT 1")
                .query((row, ignored) -> new Newest(row.getLong("generation"), row.getObject("completed_at", OffsetDateTime.class).toInstant())).optional();
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
