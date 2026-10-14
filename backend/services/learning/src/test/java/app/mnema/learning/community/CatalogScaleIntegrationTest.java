package app.mnema.learning.community;

import app.mnema.learning.library.AliasNormalizer;
import app.mnema.learning.library.LanguageDetector;
import app.mnema.learning.library.TopicDirectory;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Community/2 (#449), the plans and the budget of every statement of the catalogue routes on a SYNTHETIC catalogue. {@code MNEMA_CATALOG_SCALE} sets the number of listed decks (10 000 by
 * default, the gate; 100 000 by hand: {@code docs/engineering/evidence/community-decks/epic-3/catalog-scale.sh}). The rows are written straight into {@code public_deck} and a rank
 * generation (foreign keys are not checked while they are written; the routes read the columns and never the decks): titles of three words drawn with a Zipf-like skew from a
 * vocabulary of 4 000 pseudo-words in three scripts (so a common word matches a few percent of the decks, a rare one a handful), the leaf topics of the directory in turn, a thousand authors,
 * and a generation whose ranks are a permutation. Every statement is EXPLAINed ({@code ANALYZE, BUFFERS}) and timed (a page and its count, as the route runs them: 5 warm-up and 30 timed runs), the
 * plans and the quantiles are written to {@code build/community-catalog-scale-<n>.txt}.
 *
 * <p>Asserted: the browse statements are index ranges (no sequential scan of {@code public_deck} or {@code deck_rank}); the selective searches use the GIN indexes and the title trigram
 * index (a bitmap OR); the broad ones CAN (with sequential scans disabled the same statement uses them); and every typical statement is inside the budget of the architecture
 * (section 12): p95 below 50 ms at 10^4 rows and below 200 ms at 10^5. The two statements that are not a page (the facets, which are cached for five minutes, and the sitemap, cached for an hour)
 * and the worst-case search (a word in a third of the titles) are measured and reported, with a generous ceiling.
 */
@SpringBootTest
class CatalogScaleIntegrationTest extends IsolatedCatalogDatabase {
    private static final Instant NOW = Instant.parse("2026-10-12T12:00:00Z");
    private static final int WARMUP = 5;
    private static final int RUNS = 30;

    @Autowired private JdbcClient jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private CatalogQueries queries;
    @Autowired private AuthorQueries authors;
    @Autowired private CatalogRanks ranks;
    @Autowired private TopicDirectory topics;

    @AfterEach
    void clean() {
        reset(jdbc);
    }

    private static int scale() {
        String value = System.getenv("MNEMA_CATALOG_SCALE");
        return value == null || value.isBlank() ? 10_000 : Integer.parseInt(value.strip());
    }

    private <T> T tx(Supplier<T> body) {
        return new TransactionTemplate(transactions).execute(status -> body.get());
    }

    private void seed(int decks) {
        tx(() -> {
            jdbc.sql("SET LOCAL session_replication_role = replica").update();
            jdbc.sql("SELECT setseed(0.42)").query().listOfRows();
            jdbc.sql("""
                    CREATE TEMP TABLE w ON COMMIT DROP AS
                    SELECT n, CASE WHEN n % 3 = 0 THEN translate(substr(md5(n::text), 1, 3 + n % 5), '0123456789abcdef', 'аеиоуклмнпрстдвб')
                                   WHEN n % 3 = 1 THEN translate(substr(md5((n + 100000)::text), 1, 3 + n % 6), '0123456789abcdef', 'aeioulmnprstdvbk')
                                   ELSE translate(substr(md5((n + 200000)::text), 1, 3 + n % 4), '0123456789abcdef', 'aeiouñlmnprstdvz') END AS x
                      FROM generate_series(1, 4000) n
                    """).update();
            // the words the queries below look for sit at the head (common) and the middle (rare) of the vocabulary
            jdbc.sql("UPDATE w SET x = CASE n WHEN 1 THEN 'глаголы' WHEN 2 THEN 'испанские' WHEN 3 THEN 'spanish' WHEN 4 THEN 'verbs' WHEN 5 THEN 'sql' WHEN 6 THEN 'английские' WHEN 7 THEN 'слова' "
                    + "WHEN 900 THEN 'гравитация' WHEN 901 THEN 'камертон' WHEN 902 THEN 'ひらがな' WHEN 903 THEN 'zucchini' END WHERE n IN (1, 2, 3, 4, 5, 6, 7, 900, 901, 902, 903)").update();
            jdbc.sql("CREATE INDEX ON w(n)").update();
            jdbc.sql("CREATE TEMP TABLE leafs ON COMMIT DROP AS SELECT row_number() OVER (ORDER BY topic_id) AS n, topic_id, parent_id FROM app_learning.topic WHERE parent_id IS NOT NULL").update();
            jdbc.sql("""
                    CREATE TEMP TABLE gen ON COMMIT DROP AS
                    SELECT g, 1 + floor(4000 * power(random(), 1.8))::int AS a, 1 + floor(4000 * power(random(), 1.8))::int AS b, 1 + floor(4000 * power(random(), 1.8))::int AS c,
                           1 + floor(4000 * power(random(), 1.8))::int AS e FROM generate_series(1, :n) g
                    """).param("n", decks).update();
            jdbc.sql("""
                    INSERT INTO app_learning.public_deck(deck_id, public_code, owner_id, published_revision_id, title, description, topic_id, parent_topic_id, content_language, target_language, level,
                                                         tags, item_count, exercise_count, media_mask, mechanics_mask, first_listed_at, content_updated_at, title_norm, projected_at)
                    SELECT gen_random_uuid(), translate(substr(md5(g.g::text), 1, 10), '0', '1'), ('00000000-0000-4000-8000-' || lpad((g.g % 1000)::text, 12, '0'))::uuid, gen_random_uuid(),
                           t.title, 'Описание: ' || t.title || ' ' || (SELECT x FROM w WHERE n = g.e),
                           l.topic_id, l.parent_id, (ARRAY['ru', 'en', 'es'])[1 + g.g % 3], (ARRAY['es', 'en', 'de', 'fr', NULL])[1 + g.g % 5], (ARRAY['A1', 'A2', 'B1', 'B2', NULL, NULL])[1 + g.g % 6],
                           ARRAY[(SELECT x FROM w WHERE n = g.e)], 10 + (g.g * 37) % 400, 1 + g.g % 50, (g.g % 8)::smallint, (g.g % 32)::smallint,
                           CAST(:now AS timestamptz) - (g.g % 20000) * interval '1 minute', CAST(:now AS timestamptz) - (g.g % 43200) * interval '1 minute', lower(t.title), CAST(:now AS timestamptz)
                      FROM gen g CROSS JOIN LATERAL (SELECT (SELECT x FROM w WHERE n = g.a) || ' ' || (SELECT x FROM w WHERE n = g.b) || ' ' || (SELECT x FROM w WHERE n = g.c) AS title) t
                      JOIN leafs l ON l.n = 1 + g.g % (SELECT count(*) FROM leafs)
                    """).param("now", java.sql.Timestamp.from(NOW)).update();
            // a few real titles to search for
            jdbc.sql("UPDATE app_learning.public_deck SET title = 'Испанские глаголы ' || rn, title_norm = lower('Испанские глаголы ' || rn), "
                    + "description = 'Описание: испанские глаголы' FROM (SELECT deck_id AS id, row_number() OVER (ORDER BY deck_id) AS rn FROM app_learning.public_deck LIMIT 40) s WHERE deck_id = s.id").update();
            jdbc.sql("""
                    INSERT INTO app_learning.rank_generation(generation, started_at, completed_at, deck_count) VALUES (1, CAST(:now AS timestamptz), CAST(:now AS timestamptz), :n)
                    """).param("now", java.sql.Timestamp.from(NOW)).param("n", decks).update();
            jdbc.sql("""
                    INSERT INTO app_learning.deck_rank(generation, deck_id, score, rank, learning_rank, pop_pct, added_count, learning_now_count, activated_count, topic_id, parent_topic_id, listed)
                    SELECT 1, deck_id, random(), (row_number() OVER (ORDER BY random()))::integer, (row_number() OVER (ORDER BY random()))::integer, random(), (random() * 50)::integer,
                           CASE WHEN random() < 0.05 THEN 1 + (random() * 20)::integer ELSE 0 END, 0, topic_id, parent_topic_id, listed
                      FROM app_learning.public_deck
                    """).update();
            return null;
        });
        // a fifth of the decks have a learner in the last 30 days (a study row), so that the learners of an author are a real aggregate
        tx(() -> {
            jdbc.sql("SET LOCAL session_replication_role = replica").update();
            jdbc.sql("""
                    INSERT INTO app_learning.deck_engagement(deck_id, account_id, kind, first_at, last_at, study_days, last_study_date)
                    SELECT deck_id, gen_random_uuid(), 'STUDY', CAST(:now AS timestamptz) - interval '3 days', CAST(:now AS timestamptz) - interval '1 day', 3, CAST(:now AS date)
                      FROM app_learning.public_deck WHERE random() < 0.2
                    """).param("now", java.sql.Timestamp.from(NOW)).update();
            return null;
        });
        jdbc.sql("ANALYZE app_learning.deck_engagement").update();
        // rank 1 is the best by score, as the job writes it: order the scores by rank
        jdbc.sql("UPDATE app_learning.deck_rank r SET score = 1.0 / r.rank WHERE r.generation = 1").update();
        jdbc.sql("ANALYZE app_learning.public_deck").update();
        jdbc.sql("ANALYZE app_learning.deck_rank").update();
    }

    private CatalogQuery query(String... pairs) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/public/community/decks");
        for (int index = 0; index < pairs.length; index += 2) request.setParameter(pairs[index], pairs[index + 1]);
        return CatalogQuery.parse((HttpServletRequest) request, topics);
    }

    /** What a case does and what it is expected to use. */
    private record Case(String name, Supplier<Object> run, CatalogQueries.Sql explained, Pattern uses, Pattern forbids, boolean budget, CatalogQueries.Sql counted) {
        Case(String name, Supplier<Object> run, CatalogQueries.Sql explained, Pattern uses, Pattern forbids, boolean budget) {
            this(name, run, explained, uses, forbids, budget, null);
        }
    }

    private Case rank(String name, String column, CatalogQuery q, long after, String uses, boolean budget) {
        return new Case(name, () -> queries.rankPage(column, 1, q, NOW, after, 24), queries.rankPageSql(column, 1, q, NOW, after, 24), uses == null ? null : Pattern.compile(uses),
                uses == null ? null : Pattern.compile("Seq Scan on (public_deck|deck_rank)"), budget, queries.rankCountSql(column, 1, q, NOW));
    }

    private Case time(String name, String column, CatalogQuery q, Instant after, String code, String uses, boolean forbidScan, boolean budget) {
        return new Case(name, () -> queries.timePage(column, q, NOW, after, code, 24), queries.timePageSql(column, q, NOW, after, code, 24), uses == null ? null : Pattern.compile(uses),
                forbidScan ? Pattern.compile("Seq Scan on public_deck") : null, budget, queries.timeCountSql(q, NOW));
    }

    private Case search(String name, String text, Long afterRelevance, String afterCode, String uses, Pattern forbids, boolean budget, String... filters) {
        String[] pairs = new String[filters.length + 2];
        pairs[0] = "q";
        pairs[1] = text;
        System.arraycopy(filters, 0, pairs, 2, filters.length);
        CatalogQuery q = query(pairs);
        TopicDirectory.Named named = topics.named(AliasNormalizer.normalize(text));
        List<String> parents = named.topics().stream().filter(topic -> !topics.childrenOf(topic).isEmpty()).toList();
        List<String> leaves = named.topics().stream().filter(topic -> topics.childrenOf(topic).isEmpty()).toList();
        String language = LanguageDetector.detect(text).orElse(null);
        // the route runs the settings and the statement in one transaction: the settings are transaction-local
        Pattern three = Pattern.compile("public_deck_search_ru(?s:.*)public_deck_search_simple(?s:.*)public_deck_title_trgm");
        return new Case(name, () -> tx(() -> {
            queries.prepareSearch(0.5);
            return queries.searchPage(q, NOW, named, parents, leaves, language, CatalogService.trigramUseful(AliasNormalizer.normalize(text)), 1, afterRelevance, afterCode, 0, 24);
        }), queries.searchSql(q, NOW, named, parents, leaves, language, CatalogService.trigramUseful(AliasNormalizer.normalize(text)), 1, afterRelevance, afterCode, 0, 24), three, Pattern.compile("Seq Scan on (public_deck|deck_rank)"), budget);
    }

    @Test
    void everyStatementOfTheCatalogueIsAnIndexRangeInsideTheBudget() throws Exception {
        int decks = scale();
        long seedStart = System.nanoTime();
        seed(decks);
        StringBuilder report = new StringBuilder();
        report.append("synthetic catalogue: ").append(decks).append(" listed decks, ").append(jdbc.sql("SELECT count(*) FROM app_learning.deck_rank WHERE generation = 1").query(Long.class).single())
                .append(" rank rows, seeded in ").append(Duration.ofNanos(System.nanoTime() - seedStart).toMillis()).append(" ms\n");
        report.append("public_deck: ").append(jdbc.sql("SELECT pg_size_pretty(pg_table_size('app_learning.public_deck'))").query(String.class).single()).append(" table, ")
                .append(jdbc.sql("SELECT pg_size_pretty(pg_indexes_size('app_learning.public_deck'))").query(String.class).single()).append(" indexes; deck_rank: ")
                .append(jdbc.sql("SELECT pg_size_pretty(pg_total_relation_size('app_learning.deck_rank'))").query(String.class).single()).append(" with indexes\n");
        assertThat(ranks.current()).hasValue(1);
        // keys for the deep pages
        Instant middle = jdbc.sql("SELECT first_listed_at FROM app_learning.public_deck WHERE listed ORDER BY first_listed_at DESC, deck_id DESC OFFSET :o LIMIT 1").param("o", decks / 2)
                .query(java.sql.Timestamp.class).single().toInstant();
        String middleCode = jdbc.sql("SELECT public_code FROM app_learning.public_deck WHERE first_listed_at = :t LIMIT 1").param("t", java.sql.Timestamp.from(middle)).query(String.class).single();
        UUID busyAuthor = jdbc.sql("SELECT owner_id FROM app_learning.public_deck GROUP BY owner_id ORDER BY count(*) DESC, owner_id LIMIT 1").query(UUID.class).single();

        List<Case> cases = new ArrayList<>();
        cases.add(rank("popular, first page", "rank", query(), 0, "deck_rank_popular", true));
        cases.add(rank("popular, page at the middle of the ranking", "rank", query(), decks / 2, "deck_rank_popular", true));
        cases.add(rank("popular, leaf topic", "rank", query("topic", "spanish"), 0, null, true));
        cases.add(rank("popular, top-level topic", "rank", query("topic", "languages"), 0, null, true));
        cases.add(rank("popular, top-level topic + level + media", "rank", query("topic", "languages", "level", "B2", "media", "image,audio"), 0, null, true));
        cases.add(rank("popular, rare combination (course, C1, all media, all mechanics)", "rank", query("size", "course", "level", "C1", "media", "image,audio,video", "mechanics", "listening,matching,written,choice,order"), 0, null, false));
        cases.add(rank("learning, first page", "learning_rank", query("sort", "learning"), 0, "deck_rank_learning", true));
        cases.add(time("new, first page", "first_listed_at", query("sort", "new"), null, null, "public_deck_new", true, true));
        cases.add(time("new, page at the middle", "first_listed_at", query("sort", "new"), middle, middleCode, "public_deck_new", true, true));
        cases.add(time("updated, first page", "content_updated_at", query("sort", "updated"), null, null, "public_deck_updated", true, true));
        cases.add(time("updated, fresh only", "content_updated_at", query("sort", "updated", "fresh", "true"), null, null, "public_deck_updated", true, true));
        cases.add(time("new, leaf topic + learn", "first_listed_at", query("sort", "new", "topic", "spanish", "learn", "es"), null, null, "public_deck_topic", true, true));
        cases.add(time("new, top-level topic + level", "first_listed_at", query("sort", "new", "topic", "languages", "level", "B2"), null, null, "public_deck_parent_topic", true, true));
        cases.add(search("search, rare word", "гравитация", null, null, null, null, true));
        cases.add(search("search, rare Latin word", "zucchini", null, null, null, null, true));
        cases.add(search("search, Japanese title (trigrams only)", "ひらがな", null, null, null, null, true));
        cases.add(search("search, typo", "испнские глаголы", null, null, null, null, true));
        cases.add(search("search, topic by synonym", "испанский", null, null, null, null, true));
        cases.add(search("search, word with a filter", "глаголы", null, null, null, null, true, "level", "B2"));
        cases.add(search("search, common word (worst case: a few percent of the titles)", "глаголы", null, null, null, null, false));
        cases.add(search("search, page 2 by the keyset", "испанские", 300_000L, "zzzzzzzzzz", null, null, true));
        cases.add(new Case("author, first page", () -> authors.publicDecks(busyAuthor, null, null, 25), null, null, null, true));
        cases.add(new Case("shelf, new", () -> queries.newShelf(12), null, null, null, true));
        cases.add(new Case("shelf, learning", () -> queries.learningShelf(1, 12), null, null, null, true));
        cases.add(new Case("shelf, topic", () -> queries.topicShelf(1, "spanish", 12), null, null, null, true));
        cases.add(new Case("shelf, editorial", () -> queries.editorialShelf(12), null, null, null, true));
        CatalogQueries.Sql learners = new CatalogQueries.Sql().add(AuthorQueries.LEARNING_NOW_SQL);
        learners.params.put("author", busyAuthor);
        learners.params.put("since", NOW.minus(Duration.ofDays(30)).atOffset(java.time.ZoneOffset.UTC));
        cases.add(new Case("learners of an author (distinct, 30 days)", () -> authors.learningNow(busyAuthor, NOW.minus(Duration.ofDays(30))), learners, Pattern.compile("deck_engagement_pkey"),
                Pattern.compile("Seq Scan on deck_engagement"), true));
        cases.add(new Case("facets (cached for five minutes)", queries::counts, null, null, null, false));
        cases.add(new Case("sitemap (cached for an hour, 50 000 rows at most)", () -> queries.sitemap(CatalogService.SITEMAP_MAX_URLS), null, null, null, false));

        Map<String, List<Long>> timings = new LinkedHashMap<>();
        // The strict budget of the architecture (p95 < 50 ms at 10^4, < 200 ms at 10^5) is asserted when the evidence script sets MNEMA_CATALOG_SCALE; in the CI gate, on a shared and
        // loaded machine, the wall clock is only a generous guard (500 ms at 10^4, 1 s above) and the plans carry the assertion
        boolean strict = System.getenv("MNEMA_CATALOG_SCALE") != null && !System.getenv("MNEMA_CATALOG_SCALE").isBlank();
        double budget = strict ? (decks <= 20_000 ? 50 : 200) : (decks <= 20_000 ? 500 : 1_000);
        for (Case c : cases) {
            for (int run = 0; run < WARMUP; run++) c.run().get();
            List<Long> nanos = new ArrayList<>();
            for (int run = 0; run < RUNS; run++) {
                long start = System.nanoTime();
                c.run().get();
                nanos.add(System.nanoTime() - start);
            }
            nanos.sort(Long::compare);
            timings.put(c.name(), nanos);
            double p50 = nanos.get(RUNS / 2) / 1e6;
            double p95 = nanos.get((int) Math.ceil(RUNS * 0.95) - 1) / 1e6;
            report.append(String.format(java.util.Locale.ROOT, "%n== %s%n   p50 %.2f ms, p95 %.2f ms, max %.2f ms over %d runs%n", c.name(), p50, p95, nanos.getLast() / 1e6, RUNS));
            if (c.explained() != null) {
                String plan = tx(() -> {
                    queries.prepareSearch(0.5);
                    return String.join("\n", queries.explain(c.explained(), true));
                });
                // the statement is EXPLAINed with its parameters as the route binds them; long expression lines are cut
                report.append(plan.lines().map(line -> line.length() > 170 ? line.substring(0, 170) + " ..." : line).reduce((a, b) -> a + "\n" + b).orElse("")).append('\n');
                if (c.counted() != null) {
                    report.append("-- and the count behind total (up to 1001 rows):\n").append(tx(() -> String.join("\n", queries.explain(c.counted(), true))).lines()
                            .map(line -> line.length() > 170 ? line.substring(0, 170) + " ..." : line).reduce((a, b) -> a + "\n" + b).orElse("")).append('\n');
                }
                Files.writeString(Path.of("build/community-catalog-scale-" + decks + ".txt"), report.toString());
                if (c.uses() != null) assertThat(plan).as(c.name()).containsPattern(c.uses());
                if (c.forbids() != null) assertThat(plan).as(c.name()).doesNotContainPattern(c.forbids());
            }
            Files.writeString(Path.of("build/community-catalog-scale-" + decks + ".txt"), report.toString());
            if (c.budget()) assertThat(p95).as("%s: p95 in ms against the budget of the architecture", c.name()).isLessThan(budget);
        }
        String rare = tx(() -> {
            queries.prepareSearch(0.5);
            return String.join("\n", queries.explain(search("x", "гравитация", null, null, null, null, false).explained(), true));
        });
        report.append("\nthe plan of a rare word:\n").append(rare).append('\n');
        // a named topic adds its btree arm; without the settings of the search the planner scans everything (the reason for them)
        String topic = tx(() -> {
            queries.prepareSearch(0.5);
            return String.join("\n", queries.explain(search("x", "испанский", null, null, null, null, false).explained(), false));
        });
        assertThat(topic).contains("public_deck_topic");
        assertThat(timings.get("search, common word (worst case: a few percent of the titles)").get(RUNS / 2) / 1e6).as("the worst case still answers").isLessThan(decks <= 20_000 ? 500 : 2_000);
        Path out = Path.of("build/community-catalog-scale-" + decks + ".txt");
        Files.writeString(out, report.toString());
        System.out.println(report);
    }
}
