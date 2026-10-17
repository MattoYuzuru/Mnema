package app.mnema.learning.community;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Bounded indexed candidate unions and frozen public-code ordering; no query text or weights in snapshots. */
@Repository
class RecommendationRepository {
    record Snapshot(UUID id, UUID account, long version, long generation, String hash, List<String> codes, List<String> candidates, String explanation, Instant created) { }

    private final JdbcClient jdbc;

    RecommendationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    CatalogQueries.Sql candidatesSql(UUID account, long generation, List<String> topics, java.util.Set<String> parents, CatalogQuery query, Instant now, List<UUID> search) {
        CatalogQueries.Sql sql = new CatalogQueries.Sql();
        sql.params.put("a", account);
        sql.params.put("g", generation);
        String exclusion = " AND d.owner_id<>:a AND NOT EXISTS (SELECT 1 FROM app_learning.deck_fork f WHERE f.owner_id=:a AND f.upstream_deck_id=d.deck_id AND f.released_at IS NULL "
                + "AND EXISTS (SELECT 1 FROM app_learning.deck copy WHERE copy.deck_id=f.deck_id AND copy.deleted_at IS NULL))";
        CatalogQueries.Sql filters = new CatalogQueries.Sql();
        CatalogQueries.filters(filters, query, now, false);
        sql.params.putAll(filters.params);
        String predicate = exclusion + filters.sql();
        if (search != null) {
            if (search.isEmpty()) predicate += " AND false";
            else {
                predicate += " AND d.deck_id IN (:search)";
                sql.params.put("search", search);
            }
        }

        List<String> arms = new ArrayList<>();
        for (int index = 0; index < topics.size(); index++) {
            sql.params.put("t" + index, topics.get(index));
            String topicColumn = parents.contains(topics.get(index)) ? "parent_topic_id" : "topic_id";
            arms.add("(SELECT d.deck_id FROM app_learning.deck_rank r JOIN app_learning.public_deck d ON d.deck_id=r.deck_id WHERE r.generation=:g AND r.listed AND d.listed AND r."
                    + topicColumn + "=:t" + index + " AND d." + topicColumn + "=:t" + index + predicate + " ORDER BY r.rank LIMIT 20)");
        }

        arms.add("(SELECT d.deck_id FROM app_learning.public_deck d WHERE d.listed" + predicate + " ORDER BY d.first_listed_at DESC,d.deck_id DESC LIMIT 20)");
        arms.add("(SELECT d.deck_id FROM app_learning.deck_rank r JOIN app_learning.public_deck d ON d.deck_id=r.deck_id WHERE r.generation=:g AND r.listed AND d.listed"
                + predicate + " ORDER BY r.rank LIMIT 20)");
        return sql.add("SELECT " + CatalogQueries.D + ",COALESCE(r.score,0)::float8 AS base_score FROM (" + String.join(" UNION ", arms)
                + ") c JOIN app_learning.public_deck d ON d.deck_id=c.deck_id "
                + "LEFT JOIN app_learning.deck_rank r ON r.generation=:g AND r.deck_id=d.deck_id ORDER BY d.public_code COLLATE \"C\"");
    }

    List<RecommendationRanker.Candidate> candidates(CatalogQueries.Sql sql) {
        return jdbc.sql(sql.sql()).params(sql.params).query((r, ignored) -> new RecommendationRanker.Candidate(CatalogRepository.ROW.mapRow(r, 0), r.getDouble("base_score"))).list();
    }

    List<PublicDeckRow> eligible(UUID account, List<String> codes) {
        if (codes.isEmpty()) return List.of();
        return jdbc.sql("SELECT " + CatalogQueries.D + " FROM app_learning.public_deck d WHERE d.listed AND d.public_code IN (:codes) AND d.owner_id<>:a "
                + "AND NOT EXISTS (SELECT 1 FROM app_learning.deck_fork f WHERE f.owner_id=:a AND f.upstream_deck_id=d.deck_id AND f.released_at IS NULL "
                + "AND EXISTS (SELECT 1 FROM app_learning.deck copy WHERE copy.deck_id=f.deck_id AND copy.deleted_at IS NULL))")
                .param("a", account).param("codes", codes).query(CatalogRepository.ROW).list();
    }

    private Optional<Snapshot> snapshot(String predicate, Map<String, Object> params) {
        return jdbc.sql("SELECT * FROM app_learning.recommendation_snapshot WHERE " + predicate).params(params).query((r, ignored) -> new Snapshot(r.getObject("snapshot_id",
                UUID.class), r.getObject("account_id", UUID.class), r.getLong("profile_version"), r.getLong("generation"), r.getString("query_hash"),
                Arrays.asList((String[])r.getArray("codes").getArray()), Arrays.asList((String[])r.getArray("candidate_codes").getArray()), r.getString("explanation_topic"),
                r.getTimestamp("created_at").toInstant())).optional();
    }

    Optional<Snapshot> snapshot(UUID id) {
        return snapshot("snapshot_id=:id", Map.of("id", id));
    }

    Optional<Snapshot> snapshot(UUID account, long version, long generation, String hash) {
        return snapshot("account_id=:a AND profile_version=:v AND generation=:g AND query_hash=:h", Map.of("a", account, "v", version, "g", generation, "h", hash));
    }

    void discardExpired(UUID account, long version, long generation, String hash, Instant now) {
        jdbc.sql("""
                DELETE
                FROM app_learning.recommendation_snapshot
                WHERE account_id=:a
                AND profile_version=:v
                AND generation=:g
                AND query_hash=:h
                AND created_at<=:before
                """)
                .param("a", account).param("v", version).param("g", generation).param("h", hash).param("before", Timestamp.from(now.minus(java.time.Duration.ofHours(24)))).update();
    }

    Snapshot freeze(UUID account, long version, long generation, String hash, RecommendationRanker.Result result, List<String> candidates, Instant now) {
        // A bounded per-account cache of normalized questions; invalidated by the same profile lock as all signals.
        jdbc.sql("""
                DELETE
                FROM app_learning.recommendation_snapshot
                WHERE snapshot_id IN (SELECT snapshot_id
                FROM app_learning.recommendation_snapshot
                WHERE account_id=:a
                ORDER BY created_at DESC OFFSET 31)
                """)
                .param("a", account).update();
        jdbc.sql("""
                INSERT INTO app_learning.recommendation_snapshot(account_id,profile_version,generation,query_hash,codes,candidate_codes,explanation_topic,created_at)
                VALUES (:a,:v,:g,:h,CAST(:c AS text[]),CAST(:cc AS text[]),:t,:n)
                ON CONFLICT (account_id,profile_version,generation,query_hash) DO NOTHING
                """)
                .param("a", account).param("v", version).param("g", generation).param("h", hash).param("c", InterestRepository.array(result.decks().stream().map(PublicDeckRow::publicCode).toList()))
                .param("cc", InterestRepository.array(candidates)).param("t", result.explanation()).param("n", Timestamp.from(now)).update();
        return snapshot(account, version, generation, hash).orElseThrow();
    }

    /** Confident language vocabulary: explicit language leaves and current public direct copies only. */
    java.util.Set<String> languages(InterestRepository.Profile profile) {
        var result = new java.util.HashSet<String>();
        Map<String, String> vocabulary = Map.ofEntries(Map.entry("english", "en"), Map.entry("spanish", "es"), Map.entry("french", "fr"), Map.entry("german", "de"),
                Map.entry("italian", "it"), Map.entry("portuguese", "pt"), Map.entry("latin", "la"), Map.entry("russian", "ru"), Map.entry("chinese", "zh"), Map.entry("japanese",
                "ja"), Map.entry("korean", "ko"), Map.entry("arabic", "ar"), Map.entry("turkish", "tr"));
        profile.selected().forEach(t -> {
            if (vocabulary.containsKey(t)) result.add(vocabulary.get(t));
        });
        result.addAll(jdbc.sql("""
                SELECT DISTINCT p.target_language
                FROM app_learning.deck_fork f
                JOIN app_learning.public_deck p ON p.deck_id=f.upstream_deck_id
                WHERE f.owner_id=:a
                AND f.released_at IS NULL
                AND p.listed
                AND p.target_language IS NOT NULL
                AND (:bootstrap OR f.forked_at>=:since)
                AND EXISTS (SELECT 1
                FROM app_learning.deck d
                WHERE d.deck_id=f.deck_id
                AND d.deleted_at IS NULL)
                """).param("a",
                profile.account()).param("bootstrap", profile.bootstrap()).param("since", Timestamp.from(profile.since())).query(String.class).list());
        return java.util.Set.copyOf(result);
    }
}
