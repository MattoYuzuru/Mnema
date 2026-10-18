package app.mnema.learning.community;

import app.mnema.learning.library.TopicDirectory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The SQL of the public catalogue routes (Community/2, /6 and /7). Every statement reads LISTED rows of the projection ({@code public_deck WHERE listed}) except the author list and the
 * sitemap rules documented at each method, takes its order from an index of {@code V67}/{@code V68} and is bounded by a {@code LIMIT}; the plans are EXPLAINed by
 * {@code CatalogQueriesPlanTest} at 10^4 rows and by {@code CatalogScaleIntegrationTest} at the size it is told (10^5 by hand).
 *
 * <p>Filters are predicates on the current row of {@code public_deck} ({@code d}); only the topic of a RANKED list is read from the generation's copy ({@code r.topic_id}) so that the
 * partial indexes of {@code deck_rank} serve it. Every statement carries {@code listed} on the side whose index is partial, as the planner needs it written.
 */
@Repository
class CatalogQueries {
    /** The cap of the count behind {@code total} of a browse; one more row is read to know that it was reached. */
    static final int BROWSE_COUNT_CAP = 1_000;
    /** The best decks of a search that are paged. */
    static final int SEARCH_CANDIDATES = 200;
    /**
     * The most candidates one way of matching contributes. A query that matches more than this in one arm (a word in a third of the titles) is not specific: the arm keeps the first
     * {@value} rows the index returns (physical order, the order the bitmap heap scan reads) and the best 200 are chosen among those, so such a query costs a bounded scan, not a
     * scoring of everything it matches. A query below the cap is exact.
     */
    static final int ARM_LIMIT = 2_000;
    static final int FRESH_DAYS = CatalogQuery.FRESH_DAYS;
    /** The constant text part (before the weight 0.6) of a deck that is in a topic the query names, whatever else matched: just below a title hit (about 0.4). */
    static final double TOPIC_TEXT = 0.3;
    static final double TEXT_WEIGHT = 0.6;
    static final double SIMILARITY_WEIGHT = 0.2;
    static final double POPULARITY_WEIGHT = 0.2;
    /** The multiplier of a deck whose content language is set and differs from the query's. */
    static final double LANGUAGE_FACTOR = 0.85;
    private static final int RELEVANCE_SCALE = 1_000_000;
    /** The selected columns of {@code public_deck}, qualified with the alias {@code d}. */
    static final String D = Arrays.stream(CatalogRepository.COLUMNS.split(",")).map(String::strip).map(column -> "d." + column).reduce((a, b) -> a + ", " + b).orElseThrow();

    /** A row with its place in the list: the integer rank (popular, learning), the relevance in millionths (search), the shelf position, or 0 where the row itself is the key. */
    record Hit(PublicDeckRow row, long position) { }

    /** Rows read and the total behind them: for a browse up to {@code BROWSE_COUNT_CAP + 1}, for a search the number of candidates among the best {@code SEARCH_CANDIDATES}. */
    record Page(List<Hit> hits, int total) { }

    /** The counts of listed decks: per topic id, per target language, per level, and in all. */
    record Counted(int listed, Map<String, Integer> topics, Map<String, Integer> languages, Map<String, Integer> levels) { }

    private final JdbcClient jdbc;

    CatalogQueries(JdbcClient jdbc) { this.jdbc = jdbc; }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    // ---- filters ----

    /** A statement with its named parameters: what a query method runs and what the plan tests EXPLAIN. */
    static final class Sql {
        final StringBuilder text = new StringBuilder();
        final Map<String, Object> params = new LinkedHashMap<>();

        String sql() { return text.toString(); }

        Sql add(String fragment) {
            text.append(fragment);
            return this;
        }
    }

    /** The predicates of the filters after {@code WHERE listed}; {@code ranked} reads the topic from the generation's copy ({@code r}). */
    static void filters(Sql sql, CatalogQuery query, Instant now, boolean ranked) {
        if (query.topic() != null) {
            String column = (ranked ? "r." : "d.") + (query.topicIsParent() ? "parent_topic_id" : "topic_id");
            sql.add(" AND " + column + " = :topic");
            sql.params.put("topic", query.topic());
        }
        if (query.learn() != null) {
            sql.add(" AND d.target_language = :learn");
            sql.params.put("learn", query.learn());
        }
        if (query.level() != null) {
            sql.add(" AND d.level = :level");
            sql.params.put("level", query.level());
        }
        if (query.size() != null) {
            sql.add(switch (query.size()) {
                case "evening" -> " AND d.item_count < 50";
                case "month" -> " AND d.item_count BETWEEN 50 AND 300";
                default -> " AND d.item_count > 300";
            });
        }
        if (query.mediaMask() != 0) {
            sql.add(" AND (d.media_mask & :media) = :media");
            sql.params.put("media", query.mediaMask());
        }
        if (query.mechanicsMask() != 0) {
            sql.add(" AND (d.mechanics_mask & :mechanics) = :mechanics");
            sql.params.put("mechanics", query.mechanicsMask());
        }
        if (query.fresh()) {
            sql.add(" AND d.content_updated_at >= :since");
            sql.params.put("since", at(now.minus(java.time.Duration.ofDays(FRESH_DAYS))));
        }
    }

    private List<Hit> hits(Sql sql) {
        return jdbc.sql(sql.sql()).params(sql.params).query((row, ignored) -> new Hit(CatalogRepository.ROW.mapRow(row, 0), row.getLong("position"))).list();
    }

    private int countOf(Sql sql) {
        return jdbc.sql(sql.text.toString()).params(sql.params).query(Integer.class).single();
    }

    // ---- facets ----

    /** Whether at least one deck is listed: one probe of the partial index of listed decks. */
    boolean anyListed() {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM app_learning.public_deck WHERE listed)").query(Boolean.class).single();
    }

    /** One pass over the listed rows: grouping sets for the topics, the target languages, the levels and the total. */
    Counted counts() {
        Map<String, Integer> topics = new LinkedHashMap<>();
        Map<String, Integer> languages = new LinkedHashMap<>();
        Map<String, Integer> levels = new LinkedHashMap<>();
        int[] listed = {0};
        jdbc.sql("""
                SELECT topic_id, target_language, level, GROUPING(topic_id, target_language, level) AS g, count(*)::integer AS n
                  FROM app_learning.public_deck WHERE listed
                 GROUP BY GROUPING SETS ((topic_id), (target_language), (level), ())
                """).query((row, ignored) -> {
            int n = row.getInt("n");
            switch (row.getInt("g")) {
                case 0b011 -> { if (row.getString("topic_id") != null) topics.put(row.getString("topic_id"), n); }
                case 0b101 -> { if (row.getString("target_language") != null) languages.put(row.getString("target_language"), n); }
                case 0b110 -> { if (row.getString("level") != null) levels.put(row.getString("level"), n); }
                case 0b111 -> listed[0] = n;
                default -> { }
            }
            return null;
        }).list();
        return new Counted(listed[0], topics, languages, levels);
    }

    // ---- browse ----

    private static String from(String ranked) {
        return ranked == null ? " FROM app_learning.public_deck d WHERE d.listed"
                : " FROM app_learning.deck_rank r JOIN app_learning.public_deck d ON d.deck_id = r.deck_id WHERE r.generation = :g AND r.listed AND d.listed";
    }

    /**
     * The sort {@code popular} ({@code rank}) or {@code learning} ({@code learning_rank}) of generation {@code generation}: a walk of the partial index of {@code deck_rank} in rank
     * order with one key probe of {@code public_deck} per row, stopped by the {@code LIMIT}. {@code after} is the rank of the last row of the previous page (0 for the first).
     */
    Page rankPage(String column, long generation, CatalogQuery query, Instant now, long after, int limit) {
        return new Page(hits(rankPageSql(column, generation, query, now, after, limit)), countOf(rankCountSql(column, generation, query, now)));
    }

    Sql rankPageSql(String column, long generation, CatalogQuery query, Instant now, long after, int limit) {
        Sql page = new Sql().add("SELECT " + D + ", r." + column + " AS position" + from(column));
        page.params.put("g", generation);
        filters(page, query, now, true);
        if (after > 0) {
            page.add(" AND r." + column + " > :after");
            page.params.put("after", after);
        }
        page.add(" ORDER BY r." + column + " LIMIT :limit");
        page.params.put("limit", limit + 1);
        return page;
    }

    Sql rankCountSql(String column, long generation, CatalogQuery query, Instant now) {
        Sql count = new Sql().add("SELECT count(*)::integer FROM (SELECT 1" + from(column));
        count.params.put("g", generation);
        filters(count, query, now, true);
        return count.add(" LIMIT " + (BROWSE_COUNT_CAP + 1) + ") s");
    }

    /**
     * The sort {@code new} ({@code first_listed_at}) or {@code updated} ({@code content_updated_at}): an index range of {@code V67} in time order; the tiebreak is the public code, which
     * the index does not know, so the planner adds an incremental sort inside each group of equal times (a group is one deck unless two entered the catalogue in the same microsecond).
     * {@code afterAt} and {@code afterCode} are the key of the last row of the previous page (null for the first); the first condition is the index range, the row comparison the exact bound.
     */
    Page timePage(String column, CatalogQuery query, Instant now, Instant afterAt, String afterCode, int limit) {
        return new Page(hits(timePageSql(column, query, now, afterAt, afterCode, limit)), countOf(timeCountSql(query, now)));
    }

    Sql timePageSql(String column, CatalogQuery query, Instant now, Instant afterAt, String afterCode, int limit) {
        Sql page = new Sql().add("SELECT " + D + ", 0 AS position" + from(null));
        filters(page, query, now, false);
        if (afterAt != null) {
            page.add(" AND d." + column + " <= :t AND (d." + column + ", d.public_code COLLATE \"C\") < (:t, :c)");
            page.params.put("t", at(afterAt));
            page.params.put("c", afterCode);
        }
        page.add(" ORDER BY d." + column + " DESC, d.public_code COLLATE \"C\" DESC LIMIT :limit");
        page.params.put("limit", limit + 1);
        return page;
    }

    Sql timeCountSql(CatalogQuery query, Instant now) {
        Sql count = new Sql().add("SELECT count(*)::integer FROM (SELECT 1" + from(null));
        filters(count, query, now, false);
        return count.add(" LIMIT " + (BROWSE_COUNT_CAP + 1) + ") s");
    }

    // ---- search ----

    /**
     * The search (architecture section 10). Candidates are LISTED decks that match the filters and any of: the Russian-stemmed vector against {@code websearch_to_tsquery} of the text
     * extended (OR) with the names and synonyms of the topics it names, the unaccented vector against the same, the normalized title word-similar to the normalized text
     * ({@code %>}, threshold {@code pg_trgm.word_similarity_threshold}), or the deck's topic being one the text names (a top-level topic through its children). The relevance, in millionths
     * so that it can be a plain integer key, is
     * <pre>(0.6 * max(text rank, {@value #TOPIC_TEXT} if in a named topic) + 0.2 * word similarity + 0.2 * popularity percentile) * (0.85 if the language of the text is known and differs)</pre>
     * where the text rank is {@code max(ts_rank_cd(search_ru), ts_rank_cd(search_simple))} with normalization 1|32 (length and {@code r/(r+1)}), the percentile is the deck's {@code pop_pct}
     * in generation {@code generation} (0 for a deck it does not know, or for generation 0), and the multiplier applies to a deck whose content language is set and is not
     * {@code language} (no deck is lowered when {@code language} is null: the detector was not sure). The best {@value #SEARCH_CANDIDATES} by (relevance descending, public code) are paged by the keyset ({@code afterRelevance}, {@code afterCode}); {@code total} is how many there are.
     *
     * <p>The candidates are the UNION of one index scan per way of matching (the Russian vector, the unaccented vector, the title trigrams, and the btree of a named topic), each arm with
     * its single predicate (the GIN arms say nothing else, so that the only index path is the GIN one; the btree arms of a topic carry {@code listed}, the predicate of the partial indexes); the union is joined back to {@code public_deck} for the score and the filters, and to the rank generation by key.
     *
     * <p>The tsquery expressions are written inline with bound text parameters: {@code websearch_to_tsquery(regconfig, text)} is IMMUTABLE, so the planner folds them and estimates the
     * GIN indexes with the statistics of the vectors; a generic plan could not, which is why the transaction forces custom plans ({@link #prepareSearch}).
     */
    Page searchPage(CatalogQuery query, Instant now, TopicDirectory.Named named, List<String> parentTopics, List<String> leafTopics, String language, boolean trigram, long generation,
                    Long afterRelevance, String afterCode, int offset, int limit) {
        Sql sql = searchSql(query, now, named, parentTopics, leafTopics, language, trigram, generation, afterRelevance, afterCode, offset, limit);
        int[] total = {0};
        List<Hit> hits = jdbc.sql(sql.sql()).params(sql.params).query((row, ignored) -> {
            total[0] = row.getInt("total");
            return new Hit(CatalogRepository.ROW.mapRow(row, 0), row.getLong("position"));
        }).list();
        if (hits.isEmpty() && (afterRelevance != null || offset > 0)) {
            // a page past the end (the list shrank meanwhile) has no row to carry the total: the first row of the same list does
            return new Page(hits, searchPage(query, now, named, parentTopics, leafTopics, language, trigram, generation, null, null, 0, 1).total());
        }
        return new Page(hits, hits.isEmpty() ? 0 : total[0]);
    }

    /** The statement of {@link #searchPage}. */
    Sql searchSql(CatalogQuery query, Instant now, TopicDirectory.Named named, List<String> parentTopics, List<String> leafTopics, String language, boolean trigram, long generation,
                    Long afterRelevance, String afterCode, int offset, int limit) {
        List<String> texts = new ArrayList<>();
        texts.add(query.q());
        texts.addAll(named.texts());
        Sql sql = new Sql();
        StringBuilder ru = new StringBuilder("(");
        StringBuilder simple = new StringBuilder("(");
        for (int index = 0; index < texts.size(); index++) {
            if (index > 0) {
                ru.append(" || ");
                simple.append(" || ");
            }
            ru.append("websearch_to_tsquery('russian', :t").append(index).append(')');
            simple.append("websearch_to_tsquery('app_learning.simple_unaccent', :t").append(index).append(')');
            sql.params.put("t" + index, texts.get(index));
        }
        ru.append(')');
        simple.append(')');
        sql.params.put("qn", query.normalizedQuery());
        sql.params.put("lang", language);
        sql.params.put("g", generation);
        String topic = topicPredicate(sql, parentTopics, leafTopics);
        // The candidates are the UNION of one index scan per way to match (each arm has ONE indexable predicate and the partial predicate of the btree), joined back to the row for the
        // score and the filters. A single OR over the three predicates is planned as a scan of everything (the planner underestimates the cost of @@ and %>): see the class comment.
        StringBuilder arms = new StringBuilder("(SELECT deck_id FROM app_learning.public_deck WHERE search_ru @@ " + ru + " LIMIT " + ARM_LIMIT + ")"
                + " UNION (SELECT deck_id FROM app_learning.public_deck WHERE search_simple @@ " + simple + " LIMIT " + ARM_LIMIT + ")");
        if (trigram) arms.append(" UNION (SELECT deck_id FROM app_learning.public_deck WHERE title_norm OPERATOR(app_learning.%>) CAST(:qn AS text) LIMIT " + ARM_LIMIT + ")");
        if (!parentTopics.isEmpty()) arms.append(" UNION (SELECT deck_id FROM app_learning.public_deck WHERE listed AND parent_topic_id IN (:parentTopics) LIMIT " + ARM_LIMIT + ")");
        if (!leafTopics.isEmpty()) arms.append(" UNION (SELECT deck_id FROM app_learning.public_deck WHERE listed AND topic_id IN (:leafTopics) LIMIT " + ARM_LIMIT + ")");
        sql.add("SELECT k.* FROM (SELECT c.*, count(*) OVER () AS total FROM (SELECT s.* FROM (SELECT " + D
                + ", round((" + TEXT_WEIGHT + "::float8 * GREATEST(GREATEST(ts_rank_cd(d.search_ru, " + ru + ", 33), ts_rank_cd(d.search_simple, " + simple
                + ", 33))::float8, CASE WHEN " + topic + " THEN " + TOPIC_TEXT + "::float8 ELSE 0::float8 END)"
                + " + " + SIMILARITY_WEIGHT + "::float8 * app_learning.word_similarity(CAST(:qn AS text), d.title_norm)::float8"
                + " + " + POPULARITY_WEIGHT + "::float8 * COALESCE(r.pop_pct, 0)::float8)"
                + " * (CASE WHEN CAST(:lang AS text) IS NOT NULL AND d.content_language IS NOT NULL AND d.content_language <> CAST(:lang AS text) THEN " + LANGUAGE_FACTOR + "::float8 ELSE 1::float8 END) * " + RELEVANCE_SCALE
                + ")::bigint AS position, r.rank AS sort_rank, r.learning_rank AS sort_learning FROM (" + arms + ") h JOIN app_learning.public_deck d ON d.deck_id = h.deck_id"
                + " LEFT JOIN app_learning.deck_rank r ON r.generation = :g AND r.deck_id = d.deck_id WHERE d.listed");
        filters(sql, query, now, false);
        sql.add(") s ORDER BY s.position DESC, s.public_code COLLATE \"C\" ASC LIMIT " + SEARCH_CANDIDATES + ") c) k");
        String sort = query.sort();
        if (sort == null) {
            if (afterRelevance != null) {
                sql.add(" WHERE (k.position < :afterPosition OR (k.position = :afterPosition AND k.public_code COLLATE \"C\" > :afterCode))");
                sql.params.put("afterPosition", afterRelevance);
                sql.params.put("afterCode", afterCode);
            }
            sql.add(" ORDER BY k.position DESC, k.public_code COLLATE \"C\" ASC LIMIT :limit");
        } else {
            // the best 200 by relevance, re-ordered by the sort the client asked for: the generation's rank (a candidate it does not know is last, newest entrant first), or a time.
            // The position in that list is the cursor (OFFSET), so the order must be total: the public code is the last key
            String key = switch (sort) {
                case "popular" -> "k.sort_rank ASC NULLS LAST, k.first_listed_at DESC";
                case "learning" -> "k.sort_learning ASC NULLS LAST, k.first_listed_at DESC";
                case "new" -> "k.first_listed_at DESC";
                default -> "k.content_updated_at DESC, k.first_listed_at DESC";
            };
            sql.add(" ORDER BY " + key + ", k.public_code COLLATE \"C\" ASC OFFSET :offset LIMIT :limit");
            sql.params.put("offset", offset);
        }
        sql.params.put("limit", limit + 1);
        return sql;
    }

    private static String topicPredicate(Sql sql, List<String> parentTopics, List<String> leafTopics) {
        List<String> arms = new ArrayList<>();
        if (!parentTopics.isEmpty()) {
            arms.add("d.parent_topic_id IN (:parentTopics)");
            sql.params.put("parentTopics", parentTopics);
        }
        if (!leafTopics.isEmpty()) {
            arms.add("d.topic_id IN (:leafTopics)");
            sql.params.put("leafTopics", leafTopics);
        }
        return arms.isEmpty() ? "false" : "(" + String.join(" OR ", arms) + ")";
    }

    /**
     * The topics whose synonym is word-similar to one of {@code words} (pg_trgm {@code %>}, the threshold of the transaction: {@link #prepareSearch}), for the typos of a topic name:
     * «испанскй» and «spansh» name {@code spanish}. Only one-word synonyms of five letters or more are compared (a word that resembles one word of «испанский язык» or the short «eng» would name half the directory); a transposition of two letters is below the threshold. {@code topic_alias} is a few hundred rows, so this is a scan of it, one statement per search with such a word, none without.
     */
    List<String> similarTopics(List<String> words) {
        if (words.isEmpty()) return List.of();
        return jdbc.sql("SELECT DISTINCT a.topic_id FROM app_learning.topic_alias a JOIN unnest(CAST(:words AS text[])) AS w(word) ON a.alias_norm OPERATOR(app_learning.%>) w.word WHERE a.alias_norm !~ ' ' AND char_length(a.alias_norm) >= 5 ORDER BY a.topic_id")
                .param("words", "{" + String.join(",", words.stream().map(word -> "\"" + word.replace("\\", "\\\\").replace("\"", "\\\"") + "\"").toList()) + "}").query(String.class).list();
    }

    /** The plan of a statement as the database prints it ({@code analyze} executes it: the statements here are reads); for the plan tests and the evidence of the scale test. */
    List<String> explain(Sql sql, boolean analyze) {
        return jdbc.sql("EXPLAIN (" + (analyze ? "ANALYZE, BUFFERS, " : "") + "COSTS OFF) " + sql.sql()).params(sql.params).query(String.class).list();
    }

    /**
     * Transaction-local settings of the search, set before {@link #searchPage} in the same transaction: the trigram threshold, custom plans (see {@link #searchPage}) and
     * {@code enable_seqscan = off}. The last one is a measured decision, not a style: the planner costs {@code @@} and {@code %>} like a cheap operator, so on up to some tens of thousands of
     * rows it prefers to evaluate them on every row (about 1.7 microseconds a row: 20 ms at 10^4) over the three indexes that exist for exactly this (0.2 to 7 ms at 10^4 for the same
     * statement). The setting only discourages sequential scans, so a statement with no index path still runs; the other tables of the statement are read by key.
     */
    void prepareSearch(double threshold) {
        jdbc.sql("SELECT set_config('pg_trgm.word_similarity_threshold', :threshold, true), set_config('plan_cache_mode', 'force_custom_plan', true), set_config('enable_seqscan', 'off', true)")
                .param("threshold", Double.toString(threshold)).query().listOfRows();
    }

    // ---- shelves ----

    private List<PublicDeckRow> rows(String sql, Map<String, Object> params) {
        return jdbc.sql(sql).params(params).query((row, ignored) -> CatalogRepository.ROW.mapRow(row, 0)).list();
    }

    /** The owner's shelf: the picks whose decks are listed, in the owner's order (hidden, unpublished and demoted decks fall out). */
    List<PublicDeckRow> editorialShelf(int limit) {
        return rows("SELECT " + D + " FROM app_learning.editorial_pick e JOIN app_learning.public_deck d ON d.deck_id = e.deck_id WHERE d.listed ORDER BY e.position LIMIT :limit",
                Map.of("limit", limit));
    }

    /** Decks with at least one learner in the last 30 days of generation {@code generation}, by «Учат сейчас» and then rank: the walk of {@code deck_rank_learning} stops at the first deck without one. */
    List<PublicDeckRow> learningShelf(long generation, int limit) {
        return rows("SELECT " + D + " FROM app_learning.deck_rank r JOIN app_learning.public_deck d ON d.deck_id = r.deck_id"
                + " WHERE r.generation = :g AND r.listed AND r.learning_now_count >= 1 AND d.listed ORDER BY r.learning_rank LIMIT :limit", Map.of("g", generation, "limit", limit));
    }

    /** «Новое»: the newest entrants of the catalogue ({@code public_deck_new}). */
    List<PublicDeckRow> newShelf(int limit) {
        return rows("SELECT " + D + " FROM app_learning.public_deck d WHERE d.listed ORDER BY d.first_listed_at DESC, d.deck_id DESC LIMIT :limit", Map.of("limit", limit));
    }

    /** The popular decks of a leaf topic, in rank order: the equality on the topic picks its rows from {@code deck_rank_topic} and the top-N sort by {@code rank} is over one topic only. */
    List<PublicDeckRow> topicShelf(long generation, String topic, int limit) {
        return rows("SELECT " + D + " FROM app_learning.deck_rank r JOIN app_learning.public_deck d ON d.deck_id = r.deck_id"
                + " WHERE r.generation = :g AND r.listed AND r.topic_id = :topic AND d.listed ORDER BY r.rank LIMIT :limit",
                Map.of("g", generation, "topic", topic, "limit", limit));
    }

    // ---- sitemap ----

    /** One listed deck of the sitemap. */
    record SitemapEntry(String code, String title, Instant updatedAt) { }

    /** The newest {@code limit} listed decks ({@code public_deck_new}): an index range, so a catalogue past the cap is cut at the oldest. */
    List<SitemapEntry> sitemap(int limit) {
        return jdbc.sql("SELECT public_code, title, content_updated_at FROM app_learning.public_deck WHERE listed ORDER BY first_listed_at DESC, deck_id DESC LIMIT :limit")
                .param("limit", limit).query((row, ignored) -> new SitemapEntry(row.getString("public_code"), row.getString("title"),
                        row.getObject("content_updated_at", OffsetDateTime.class).toInstant())).list();
    }
}
