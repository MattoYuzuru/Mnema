package app.mnema.learning.community;

import app.mnema.learning.platform.jobs.Job;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.node.JsonNodeFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Community/3 (#450), the load conclusion "H" measured: 5 000 public decks, one viral deck of 20 000 copies and about 310 000 {@code deck_engagement} rows (the 300 000 of the RFC),
 * on a database of its own. The rows are synthetic (the foreign keys of {@code public_deck} are not checked while they are written, with {@code session_replication_role = replica}; the rank
 * reads the columns, never the decks). The hourly rank job is one transaction; its duration, the plan of its statement ({@code EXPLAIN (ANALYZE, BUFFERS)}), the daily anomaly query and the
 * cascade of the retention are measured and written to {@code build/community-rank-scale.txt}; the assertions are generous ceilings, the figures are in the guide.
 */
@SpringBootTest
class CatalogRankScaleIntegrationTest extends IsolatedCatalogDatabase {
    private static final Instant NOW = Instant.parse("2026-10-12T12:00:00Z");
    private static final int DECKS = 5_000;
    private static final int FORKS_PER_DECK = 40;
    private static final int VIRAL = 20_000;

    @Autowired private JdbcClient jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private RankRepository repository;
    @Autowired private AnomalyRepository anomalies;

    @AfterEach
    void clean() {
        jdbc.sql("TRUNCATE app_learning.deck_engagement, app_learning.deck_rank, app_learning.rank_generation, app_learning.catalog_anomaly").update();
        jdbc.sql("DELETE FROM app_learning.public_deck").update();
    }

    private <T> T tx(java.util.function.Supplier<T> body) {
        return new TransactionTemplate(transactions).execute(status -> body.get());
    }

    @Test
    void aHundredThousandUserCatalogueIsRankedInASingleHourlyStatementAndFlaggedInASecond() throws Exception {
        reset(jdbc);
        StringBuilder report = new StringBuilder();
        long seedStart = System.nanoTime();
        tx(() -> {
            jdbc.sql("SET LOCAL session_replication_role = replica").update();
            jdbc.sql("""
                    WITH t AS (SELECT topic_id, parent_id, row_number() OVER (ORDER BY topic_id) AS n FROM app_learning.topic WHERE parent_id IS NOT NULL),
                         c AS (SELECT count(*) AS k FROM t)
                    INSERT INTO app_learning.public_deck(deck_id, public_code, owner_id, published_revision_id, title, description, topic_id, parent_topic_id, item_count, exercise_count,
                                                         first_listed_at, content_updated_at, title_norm, projected_at)
                    SELECT gen_random_uuid(), translate(substr(md5(g::text), 1, 10), '0', '1'), gen_random_uuid(), gen_random_uuid(), 'Колода ' || g, 'Описание колоды ' || g,
                           t.topic_id, t.parent_id, 300, 100, CAST(:now AS timestamptz), CAST(:now AS timestamptz) - (g % 400) * interval '1 day', 'колода ' || g, CAST(:now AS timestamptz)
                      FROM generate_series(1, :decks) g CROSS JOIN c JOIN t ON t.n = (g % c.k) + 1
                    """).param("now", java.sql.Timestamp.from(NOW)).param("decks", DECKS).update();
            // (the lateral subquery refers to the row of generate_series on purpose: without a reference the planner evaluates random() once for the whole statement)
            // 40 copies per deck over the last 90 days (30 % by accounts that were not trusted, 10 % by new accounts), 40 % of the copiers study (1 to 8 study days)
            jdbc.sql("""
                    WITH forks AS (
                        INSERT INTO app_learning.deck_engagement(deck_id, account_id, kind, first_at, last_at, trust, new_account)
                        SELECT d.deck_id, gen_random_uuid(), 'FORK', t.at, t.at, CASE WHEN random() < 0.3 THEN 0.25 ELSE 1.0 END, random() < 0.1
                          FROM app_learning.public_deck d CROSS JOIN generate_series(1, :forks) s
                          CROSS JOIN LATERAL (SELECT CAST(:now AS timestamptz) - (random() + s.s * 0) * interval '90 days' AS at) t
                        RETURNING deck_id, account_id, first_at)
                    INSERT INTO app_learning.deck_engagement(deck_id, account_id, kind, first_at, last_at, study_days, last_study_date)
                    SELECT deck_id, account_id, 'STUDY', first_at, LEAST(CAST(:now AS timestamptz), first_at + random() * interval '60 days'), 1 + floor(random() * 8)::integer,
                           CAST(CAST(:now AS timestamptz) AS date)
                      FROM forks WHERE random() < 0.4
                    """).param("now", java.sql.Timestamp.from(NOW)).param("forks", FORKS_PER_DECK).update();
            // one viral deck: 20 000 copies in the last 30 days
            jdbc.sql("""
                    INSERT INTO app_learning.deck_engagement(deck_id, account_id, kind, first_at, last_at, trust, new_account)
                    SELECT (SELECT deck_id FROM app_learning.public_deck ORDER BY deck_id LIMIT 1), gen_random_uuid(), 'FORK', t.at, t.at, 1.0, random() < 0.1
                      FROM generate_series(1, :viral) s CROSS JOIN LATERAL (SELECT CAST(:now AS timestamptz) - (random() + s.s * 0) * interval '30 days' AS at) t
                    """).param("now", java.sql.Timestamp.from(NOW)).param("viral", VIRAL).update();
            // the viral deck was updated now (freshness 1.0), so that its lead over the random decks does not depend on the dice
            jdbc.sql("UPDATE app_learning.public_deck SET content_updated_at = CAST(:now AS timestamptz) WHERE deck_id = (SELECT deck_id FROM app_learning.public_deck ORDER BY deck_id LIMIT 1)")
                    .param("now", java.sql.Timestamp.from(NOW)).update();
            // one attack: 30 copies by new accounts yesterday on another deck
            jdbc.sql("""
                    INSERT INTO app_learning.deck_engagement(deck_id, account_id, kind, first_at, last_at, trust, new_account)
                    SELECT (SELECT deck_id FROM app_learning.public_deck ORDER BY deck_id DESC LIMIT 1), gen_random_uuid(), 'FORK', CAST(:now AS timestamptz) - interval '1 day',
                           CAST(:now AS timestamptz) - interval '1 day', 0.25, TRUE
                      FROM generate_series(1, 30)
                    """).param("now", java.sql.Timestamp.from(NOW)).update();
            return null;
        });
        jdbc.sql("ANALYZE app_learning.public_deck").update();
        jdbc.sql("ANALYZE app_learning.deck_engagement").update();
        long rows = jdbc.sql("SELECT count(*) FROM app_learning.deck_engagement").query(Long.class).single();
        report.append("seed: ").append(DECKS).append(" decks, ").append(rows).append(" engagement rows in ").append(millis(seedStart)).append(" ms\n");
        assertThat(rows).isBetween(285_000L, 330_000L);

        CatalogRank job = new CatalogRank(repository, RankSettings.defaults(), new SimpleMeterRegistry(), new TestClock(NOW));
        List<Long> seconds = new java.util.ArrayList<>();
        for (int run = 1; run <= 3; run++) {
            long start = System.nanoTime();
            tx(() -> job.run(new Job(UUID.randomUUID(), CatalogRank.QUEUE, null, JsonNodeFactory.instance.objectNode(), null, 0, 1)));
            seconds.add(millis(start));
        }
        report.append("rank job (open, one INSERT ... SELECT, complete, prune) at ").append(DECKS).append(" decks: ").append(seconds).append(" ms for three consecutive hourly generations\n");
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.deck_rank WHERE generation = 3").query(Long.class).single()).isEqualTo(DECKS);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.deck_rank WHERE generation = 3 AND listed AND rank IS NOT NULL").query(Long.class).single()).isEqualTo(DECKS);
        assertThat(jdbc.sql("SELECT max(rank) FROM app_learning.deck_rank WHERE generation = 3").query(Integer.class).single()).isEqualTo(DECKS);
        // the viral deck leads: 20 000 recent copies
        String top = String.join("; ", jdbc.sql("SELECT deck_id || ' score=' || score || ' added=' || added_count || ' learning=' || learning_now_count || ' activated=' || activated_count "
                + "FROM app_learning.deck_rank WHERE generation = 3 ORDER BY rank LIMIT 3").query(String.class).list());
        assertThat(jdbc.sql("SELECT d.deck_id = (SELECT deck_id FROM app_learning.public_deck ORDER BY deck_id LIMIT 1) FROM app_learning.deck_rank d WHERE generation = 3 AND rank = 1")
                .query(Boolean.class).single()).as("the viral deck is %s; the top: %s", jdbc.sql("SELECT deck_id FROM app_learning.public_deck ORDER BY deck_id LIMIT 1").query(UUID.class).single(), top).isTrue();

        // EXPLAIN ANALYZE executes the statement: open a generation for it and roll everything back
        String plan = new TransactionTemplate(transactions).execute(status -> {
            jdbc.sql("SET LOCAL lock_timeout = '5s'").update();
            long generation = repository.open(NOW);
            String text = String.join("\n", repository.statement("EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) ", generation, NOW, RankSettings.defaults()).query(String.class).list());
            status.setRollbackOnly();
            return text;
        });
        assertThat(plan).contains("Execution Time");
        // the window clauses repeat the whole score expression: keep the shape of the plan, cut the long lines
        report.append("\nEXPLAIN (ANALYZE, BUFFERS, COSTS OFF) of the INSERT ... SELECT (long expression lines cut at 150 characters):\n")
                .append(String.join("\n", plan.lines().map(line -> line.length() > 150 ? line.substring(0, 150) + " ..." : line).toList())).append('\n');
        String generations = size("app_learning.deck_rank");

        long anomalyStart = System.nanoTime();
        int flagged = tx(() -> anomalies.detect(NOW, AnomalySettings.defaults()));
        long anomalyMillis = millis(anomalyStart);
        report.append("\nanomaly query over ").append(rows).append(" rows: ").append(anomalyMillis).append(" ms, ").append(flagged).append(" flag(s)\n");
        String attack = jdbc.sql("SELECT e.deck_id || ' forks=' || count(*) || ' new=' || count(*) FILTER (WHERE e.new_account) || ' first=' || min(e.first_at) || ' owner=' || (SELECT count(*) FROM app_learning.public_deck p WHERE p.deck_id = e.deck_id) "
                + "FROM app_learning.deck_engagement e WHERE e.kind = 'FORK' AND e.first_at >= :since GROUP BY e.deck_id ORDER BY count(*) FILTER (WHERE e.new_account) DESC LIMIT 2")
                .param("since", java.sql.Timestamp.from(NOW.minus(Duration.ofDays(7)))).query(String.class).list().toString();
        assertThat(flagged).as(attack).isEqualTo(1);

        // retention: the cascade of a generation of 5 000 rows (five indexes)
        long pruneStart = System.nanoTime();
        int pruned = tx(() -> repository.prune(NOW.plus(Duration.ofHours(25)), Duration.ofHours(24)));
        report.append("prune of ").append(pruned).append(" old generation(s) with ").append(DECKS).append(" rows each: ").append(millis(pruneStart)).append(" ms\n");
        assertThat(pruned).isEqualTo(2);

        report.append("sizes: deck_rank ").append(generations).append(" with its five indexes for three generations of ").append(DECKS).append(" rows; deck_engagement ")
                .append(size("app_learning.deck_engagement")).append(" for ").append(rows).append(" rows\n");
        Files.writeString(Path.of("build/community-rank-scale.txt"), report.toString());
        System.out.println(report);
        assertThat(seconds.getLast()).as("the hourly job at H").isLessThan(20_000);
        assertThat(anomalyMillis).isLessThan(20_000);
    }

    private String size(String table) {
        return jdbc.sql("SELECT pg_size_pretty(pg_total_relation_size(:t::regclass))").param("t", table).query(String.class).single();
    }

    private static long millis(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
    }
}
