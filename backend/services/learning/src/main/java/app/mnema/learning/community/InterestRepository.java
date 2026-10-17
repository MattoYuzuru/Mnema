package app.mnema.learning.community;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Account-keyed data and row lock shared by every writer. TODO(#409; Learning account purge): delete
 * profiles (cascades interests/snapshots), catalog_event by account/time index, and receipts; retain anonymous exposures. */
@Repository
class InterestRepository {
    record Profile(UUID account, long version, boolean onboarding, Instant since, boolean bootstrap, List<String> selected) { }

    private final JdbcClient jdbc;

    InterestRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Instant now() {
        return jdbc.sql("SELECT statement_timestamp()").query(Timestamp.class).single().toInstant();
    }

    Profile lock(UUID account) {
        jdbc.sql("INSERT INTO app_learning.community_interest_profile(account_id) VALUES (:a) ON CONFLICT DO NOTHING")
                .param("a", account).update();
        return jdbc.sql("SELECT * FROM app_learning.community_interest_profile WHERE account_id=:a FOR UPDATE").param("a", account)
                .query((r, ignored) -> new Profile(account, r.getLong("row_version"), r.getBoolean("onboarding_complete"), r.getTimestamp("signals_since").toInstant(),
                        r.getBoolean("bootstrap_existing"), Arrays.asList((String[]) r.getArray("selected_topics").getArray()))).single();
    }

    Map<String, Double> interests(UUID account) {
        Map<String, Double> result = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT topic_id,weight
                FROM app_learning.user_topic_interest
                WHERE account_id=:a
                ORDER BY weight DESC,topic_id
                """)
                .param("a", account).query((r, ignored) -> result.put(r.getString("topic_id"), r.getDouble("weight"))).list();
        return result;
    }

    void active(UUID account) {
        jdbc.sql("UPDATE app_learning.community_interest_profile SET last_active_at=statement_timestamp() WHERE account_id=:a").param("a", account).update();
    }

    void selection(UUID account, List<String> topics) {
        jdbc.sql("""
                UPDATE app_learning.community_interest_profile
                SET selected_topics=CAST(:t AS text[]), onboarding_complete=true, row_version=row_version+1
                WHERE account_id=:a
                """)
                .param("a", account).param("t", array(topics)).update();
    }

    void reset(UUID account) {
        jdbc.sql("DELETE FROM app_learning.user_topic_interest WHERE account_id=:a").param("a", account).update();
        jdbc.sql("DELETE FROM app_learning.catalog_event WHERE account_id=:a").param("a", account).update();
        jdbc.sql("DELETE FROM app_learning.recommendation_snapshot WHERE account_id=:a").param("a", account).update();
        jdbc.sql("""
                UPDATE app_learning.community_interest_profile
                SET selected_topics='{}', onboarding_complete=true, bootstrap_existing=false, signals_since=statement_timestamp(), last_active_at=statement_timestamp(), row_version=row_version+1
                WHERE account_id=:a
                """)
                .param("a", account).update();
    }

    /** Caller holds the account row lock; invalidates frozen recommendation questions once per effective signal. */
    void bump(UUID account) {
        jdbc.sql("UPDATE app_learning.community_interest_profile SET row_version=row_version+1 WHERE account_id=:a").param("a", account).update();
    }

    void replace(UUID account, Map<String, Double> values, boolean bump) {
        jdbc.sql("DELETE FROM app_learning.user_topic_interest WHERE account_id=:a").param("a", account).update();
        values.forEach((topic, weight) -> jdbc.sql("INSERT INTO app_learning.user_topic_interest(account_id,topic_id,weight) VALUES (:a,:t,:w)")
                .param("a", account).param("t", topic).param("w", weight.floatValue()).update());
        if (bump) bump(account);
    }

    /** Live direct copies including detached ones; metadata is only from the current public source, never private copy text. A copy that is not an ordinary deck (the onboarding sample deck) is not an interest. */
    List<String> copyTopics(Profile profile) {
        return jdbc.sql("""
                SELECT p.topic_id
                FROM app_learning.deck_fork f
                JOIN app_learning.public_deck p ON p.deck_id=f.upstream_deck_id
                AND p.listed
                WHERE f.owner_id=:a
                AND f.released_at IS NULL
                AND (:bootstrap OR f.forked_at>=:since)
                AND p.topic_id IS NOT NULL
                AND EXISTS (SELECT 1
                FROM app_learning.deck d
                WHERE d.deck_id=f.deck_id
                AND d.deleted_at IS NULL
                AND d.purpose='USER')
                """)
                .param("a", profile.account()).param("bootstrap", profile.bootstrap()).param("since", Timestamp.from(profile.since())).query(String.class).list();
    }

    String goal(Profile profile) {
        return jdbc.sql("SELECT goal FROM app_learning.learning_profile WHERE owner_id=:a AND (:bootstrap OR answered_at>=:since)")
                .param("a", profile.account()).param("bootstrap", profile.bootstrap()).param("since", Timestamp.from(profile.since())).query(String.class).optional().orElse(null);
    }

    /** Bounded 13 calendar month window, account/time index and no content reads. Repeated opens of one source have one vote. */
    Map<String, Double> eventTopics(Profile profile, Instant retainedFrom, Instant now) {
        Map<String, Double> result = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT topic_id,sum(w)::float8 AS weight
                FROM (SELECT topic_id,deck_id,max(CASE WHEN kind='STUDY_START' THEN 3 ELSE 1 END) AS w
                FROM app_learning.catalog_event
                WHERE account_id=:a
                AND occurred_at>=:since
                AND occurred_at>=:retained
                AND occurred_at<=:now
                AND kind IN ('STUDY_START','DETAIL_VIEW','SEARCH_CLICK')
                AND topic_id IS NOT NULL
                GROUP BY topic_id,deck_id) s
                GROUP BY topic_id
                """)
                .param("a", profile.account()).param("since", Timestamp.from(profile.since())).param("retained", Timestamp.from(retainedFrom)).param("now", Timestamp.from(now))
                .query((r, ignored) -> result.put(r.getString("topic_id"), r.getDouble("weight"))).list();
        return result;
    }

    List<UUID> activeAccounts(UUID after, Instant from, int batch) {
        return jdbc.sql("""
                SELECT account_id
                FROM app_learning.community_interest_profile
                WHERE last_active_at>=:from
                AND account_id>:after
                ORDER BY account_id
                LIMIT :limit
                """)
                .param("from", Timestamp.from(from)).param("after", after).param("limit", batch).query(UUID.class).list();
    }

    static String array(List<String> values) {
        return "{" + String.join(",", values.stream().map(v -> "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"").toList()) + "}";
    }
}
