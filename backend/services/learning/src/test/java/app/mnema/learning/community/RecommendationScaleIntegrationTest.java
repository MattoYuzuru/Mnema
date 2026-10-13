package app.mnema.learning.community;

import app.mnema.learning.library.TopicDirectory;
import app.mnema.learning.platform.idempotency.CommandReceiptService;
import app.mnema.learning.platform.jobs.Job;
import app.mnema.learning.platform.jobs.Slice;
import app.mnema.learning.support.PostgresIntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.node.JsonNodeFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Reproducible H shape, not a production benchmark: 5k catalogue, 200k copies, 100k profiles/2M interests,
 * 1M raw events and 100k telemetry receipts. Results include row bytes, index plans and 100k-profile extrapolation. */
@SpringBootTest
@Import(RecommendationScaleIntegrationTest.DatabaseConfiguration.class)
class RecommendationScaleIntegrationTest extends PostgresIntegrationTest {
    static final String DATABASE = createDatabase("recs_scale_" + UUID.randomUUID().toString().replace("-", ""));

    @TestConfiguration(proxyBeanMethods = false) static class DatabaseConfiguration {
        @Bean DynamicPropertyRegistrar database() {
            return registry -> {
                registry.add("spring.datasource.url", () -> DATABASE);
                registry.add("spring.flyway.url", () -> DATABASE);
            };
        }
    }

    @Autowired private JdbcClient jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private TopicDirectory topics;
    @Autowired private InterestRepository interests;
    @Autowired private RecommendationRepository recommendations;
    @Autowired private CatalogCards cards;
    @Autowired private CommandReceiptService receipts;
    private final List<String> evidence = new ArrayList<>();
    private long scalar(String query) {
        return jdbc.sql(query).query(Long.class).single();
    }

    private void timed(String label, Runnable work) {
        long start = System.nanoTime();
        work.run();
        evidence.add(label + "_ms=" + (System.nanoTime() - start)/1_000_000.0);
    }

    private List<String> plan(CatalogQueries.Sql query) {
        return jdbc.sql("EXPLAIN (ANALYZE,BUFFERS,COSTS OFF) " + query.sql()).params(query.params).query(String.class).list();
    }

    @Test void zeroAndHMeasurementsAreBoundedAndUseAccountCandidateIndexes() throws Exception {
        var tx = new TransactionTemplate(transactions);
        var now = Instant.now();
        UUID account = UUID.fromString("00000000-0000-4000-8000-000000000001");
        MockHttpServletRequest request = new MockHttpServletRequest();
        CatalogQuery query = CatalogQuery.recommendations(request, topics);
        timed("zero_candidates", () -> assertThat(recommendations.candidates(recommendations.candidatesSql(account, 0, List.of("spanish"), java.util.Set.of(), query, now, null))).isEmpty());
        timed("zero_night_slice", () -> assertThat(interests.activeAccounts(new UUID(0, 0), now.minus(java.time.Duration.ofDays(30)), 25)).isEmpty());
        timed("zero_partitions", () -> tx.executeWithoutResult(s -> new CatalogEventPartitions(jdbc).run(new Job(UUID.randomUUID(), CatalogEventPartitions.QUEUE, null, JsonNodeFactory.instance.objectNode(), null, 0, 1))));
        List<String> leaves = topics.entries().stream().filter(t -> topics.selectable(t.id())).map(TopicDirectory.Entry::id).limit(20).toList();
        String array = InterestRepository.array(leaves);
        timed("seed_catalogue_5000", () -> {
            CatalogSeed seed = new CatalogSeed(jdbc, transactions);  List<CatalogSeed.Spec> specs = new ArrayList<>();
            for (int i = 0; i < 5000; i++) specs.add(CatalogSeed.deck("Scale deck " + i).topic(leaves.get(i%leaves.size())).owner(UUID.nameUUIDFromBytes(("author" + (i%1000)).getBytes(java.nio.charset.StandardCharsets.UTF_8))));
            var decks = seed.insertAll(specs);  seed.rankInOrder(7001, now, decks);
        });
        timed("seed_100k_profiles_2m_interests_200k_copies", () -> tx.executeWithoutResult(s -> {
            jdbc.sql("SET LOCAL session_replication_role=replica").update();
            jdbc.sql("INSERT INTO app_learning.community_interest_profile(account_id,selected_topics,last_active_at) SELECT md5('account'||i)::uuid,CAST(:topics AS text[]),statement_timestamp() FROM generate_series(1,100000) i")
                    .param("topics", array).update();
            jdbc.sql("INSERT INTO app_learning.user_topic_interest(account_id,topic_id,weight) SELECT p.account_id,t,1 FROM app_learning.community_interest_profile p CROSS JOIN unnest(CAST(:topics AS text[])) t")
                    .param("topics", array).update();
            jdbc.sql("INSERT INTO app_learning.deck(deck_id,owner_id,reuse_scope_id,head_revision_id,row_version,created_at) SELECT md5('copy'||i)::uuid,md5('account'||((i-1)%100000+1))::uuid,uuidv7(),uuidv7(),0,statement_timestamp() FROM generate_series(1,200000) i").update();
            jdbc.sql("INSERT INTO app_learning.deck_fork(deck_id,owner_id,reuse_scope_id,upstream_deck_id,upstream_code,base_revision_id,forked_at,job_id,materialized_at) " + "SELECT d.deck_id,d.owner_id,d.reuse_scope_id,p.deck_id,p.public_code,p.published_revision_id,statement_timestamp(),uuidv7(),statement_timestamp() " + "FROM generate_series(1,200000) i JOIN app_learning.deck d ON d.deck_id=md5('copy'||i)::uuid JOIN (SELECT p.*,row_number() OVER (ORDER BY deck_id) n FROM app_learning.public_deck p) p ON p.n=((i-1)%5000+(i-1)/100000)%5000+1").update();
        }));
        timed("event_insert_1m", () -> jdbc.sql("INSERT INTO app_learning.catalog_event(account_id,deck_id,published_revision_id,topic_id,kind,surface,rank_version) " + "SELECT md5('account'||((i-1)%100000+1))::uuid,md5('public'||((i-1)%5000+1))::uuid,uuidv7(),'spanish','DETAIL_VIEW','catalog','catalog-v1' FROM generate_series(1,1000000) i").update());
        timed("receipt_insert_100k", () -> jdbc.sql("INSERT INTO app_learning.command_receipt(command_id,actor_id,command_scope,command_type,payload_hash,result) " + "SELECT uuidv7(),md5('account'||i)::uuid,'community.events','event.record',decode(repeat('01',32),'hex'),'{}' FROM generate_series(1,100000) i").update());
        timed("snapshot_insert_200k", () -> jdbc.sql("INSERT INTO app_learning.recommendation_snapshot(account_id,profile_version,generation,query_hash,codes,candidate_codes,explanation_topic) "
                + "SELECT p.account_id,0,7001,'synthetic-'||i,ARRAY(SELECT public_code FROM app_learning.public_deck ORDER BY deck_id LIMIT 12),"
                + "ARRAY(SELECT public_code FROM app_learning.public_deck ORDER BY deck_id LIMIT 100),'spanish' FROM app_learning.community_interest_profile p CROSS JOIN generate_series(1,2) i").update());
        timed("exposure_insert_140k", () -> jdbc.sql("INSERT INTO app_learning.deck_exposure_day(day,deck_id,surface,rank_version,impressions) "
                + "SELECT CURRENT_DATE-day,d.deck_id,surface,version,100 FROM app_learning.public_deck d CROSS JOIN generate_series(0,6) day "
                + "CROSS JOIN unnest(ARRAY['catalog','for-you']) surface CROSS JOIN unnest(ARRAY['catalog-v1','recs-v1']) version").update());
        jdbc.sql("ANALYZE app_learning.catalog_event").update();
        jdbc.sql("ANALYZE app_learning.public_deck").update();
        jdbc.sql("ANALYZE app_learning.deck_rank").update();
        jdbc.sql("ANALYZE app_learning.deck_fork").update();
        jdbc.sql("ANALYZE app_learning.community_interest_profile").update();
        jdbc.sql("ANALYZE app_learning.user_topic_interest").update();
        UUID h = jdbc.sql("SELECT md5('account1')::uuid").query(UUID.class).single();
        CatalogQueries.Sql candidates = recommendations.candidatesSql(h, 7001, List.of("english", "spanish", "german"), java.util.Set.of(), query, now, null);
        List<String> candidatePlan = plan(candidates);
        evidence.add("candidate_plan");
        evidence.addAll(candidatePlan);
        assertThat(String.join("\n", candidatePlan)).contains("deck_fork_live");
        assertThat(recommendations.candidates(candidates)).hasSizeLessThanOrEqualTo(100);
        List<Double> candidateTimes = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            long start = System.nanoTime();
            recommendations.candidates(candidates);
            candidateTimes.add((System.nanoTime() - start)/1_000_000.0);
        }

        candidateTimes.sort(Double::compare);
        evidence.add("candidate_p95_ms=" + candidateTimes.get(14));
        assertThat(candidateTimes.get(14)).isLessThan(200.0);
        Map<String, Double> model = leaves.stream().collect(java.util.stream.Collectors.toMap(topic -> topic, topic -> 1.0));
        var rankedCandidates = recommendations.candidates(candidates);
        List<Double> rankTimes = new ArrayList<>();
        for (int index = 0; index < 15; index++) {
            long rankStart = System.nanoTime();
            assertThat(RecommendationRanker.rank(rankedCandidates, model, java.util.Set.of(), now).decks()).hasSize(12);
            rankTimes.add((System.nanoTime() - rankStart) / 1_000_000.0);
        }
        rankTimes.sort(Double::compare);
        evidence.add("ranker_p95_ms=" + rankTimes.get(14));
        assertThat(rankTimes.get(14)).isLessThan(200.0);
        List<RecommendationRanker.Candidate> constrained = new ArrayList<>();
        for (int index = 0; index < 100; index++) {
            String topic = index < 8 ? "critical-" + index / 2 : "abundant";
            PublicDeckRow row = new PublicDeckRow(UUID.randomUUID(), "bounded" + index, UUID.randomUUID(), UUID.randomUUID(), "Synthetic", "Synthetic", topic, null, null, null,
                    null, List.of(), 20, 1, 0, 0, true, now, now, "synthetic", null, now);
            constrained.add(new RecommendationRanker.Candidate(row, 100 - index));
        }
        long constrainedStart = System.nanoTime();
        assertThat(RecommendationRanker.rank(constrained, Map.of("critical-0", 1.0, "abundant", 0.8), java.util.Set.of(), now).decks()).hasSize(12);
        double constrainedMs = (System.nanoTime() - constrainedStart) / 1_000_000.0;
        evidence.add("ranker_constrained_100_ms=" + constrainedMs);
        assertThat(constrainedMs).isLessThan(200.0);
        List<String> eventPlan = jdbc.sql("EXPLAIN (ANALYZE,BUFFERS,COSTS OFF) SELECT topic_id,deck_id FROM app_learning.catalog_event WHERE account_id=:a AND occurred_at>=statement_timestamp()-interval '13 months'")
                .param("a", h).query(String.class).list();
        evidence.add("account_event_plan");
        evidence.addAll(eventPlan);
        assertThat(String.join("\n", eventPlan)).contains("account_id").contains("Index");
        List<String> pagePlan = jdbc.sql("EXPLAIN (ANALYZE,BUFFERS,COSTS OFF) SELECT account_id FROM app_learning.community_interest_profile WHERE account_id>:after AND last_active_at>=statement_timestamp()-interval '30 days' ORDER BY account_id LIMIT 25")
                .param("after", new UUID(0, 0)).query(String.class).list();
        evidence.add("active_account_page_plan");
        evidence.addAll(pagePlan);
        var service = new InterestService(interests, new RecommendationSettings(true, true), topics, cards, receipts);
        var rebuild = new InterestRebuild(interests, service, new RecommendationSettings(true, true));
        long start = System.nanoTime();
        Slice slice = tx.execute(s -> rebuild.run(new Job(UUID.randomUUID(), InterestRebuild.QUEUE, null, JsonNodeFactory.instance.objectNode(), null, 0, 1)));
        double sliceMs = (System.nanoTime() - start)/1_000_000.0;
        assertThat(slice).isInstanceOf(Slice.Continue.class);
        assertThat(((Slice.Continue)slice).progressCount()).isEqualTo(25);
        evidence.add("night_slice_25_ms=" + sliceMs);
        evidence.add("night_100k_extrapolated_seconds=" + (sliceMs*4000/1000));
        long eventBytes = scalar("SELECT sum(pg_total_relation_size(inhrelid))::bigint FROM pg_inherits WHERE inhparent='app_learning.catalog_event'::regclass");
        long receiptBytes = scalar("SELECT pg_total_relation_size('app_learning.command_receipt')");
        long interestBytes = scalar("SELECT pg_total_relation_size('app_learning.user_topic_interest')");
        evidence.add("raw_rows_measured=1000000 raw_bytes_measured=" + eventBytes + " raw_bytes_per_row=" + eventBytes/1_000_000.0);
        evidence.add("raw_47m_extrapolated_bytes=" + (eventBytes*47));
        evidence.add("receipt_rows_measured=100000 receipt_bytes_measured=" + receiptBytes + " receipt_bytes_per_row=" + receiptBytes/100_000.0);
        evidence.add("receipt_7d_120k_events_per_day_extrapolated_bytes=" + (receiptBytes*8.4) + " exposures_extra_depend_on_batch_size_max48");
        evidence.add("snapshots_measured=" + scalar("SELECT count(*) FROM app_learning.recommendation_snapshot") + " snapshot_bytes_measured=" + scalar("SELECT pg_total_relation_size('app_learning.recommendation_snapshot')"));
        evidence.add("exposure_day_rows_measured=" + scalar("SELECT count(*) FROM app_learning.deck_exposure_day") + " exposure_bytes_measured=" + scalar("SELECT pg_total_relation_size('app_learning.deck_exposure_day')"));
        evidence.add("profiles_measured=" + scalar("SELECT count(*) FROM app_learning.community_interest_profile") + " interest_rows_measured=" + scalar("SELECT count(*) FROM app_learning.user_topic_interest") + " interest_bytes_measured=" + interestBytes);
        evidence.add("copies_measured=" + scalar("SELECT count(*) FROM app_learning.deck_fork") + " catalogue_measured=" + scalar("SELECT count(*) FROM app_learning.public_deck"));
        evidence.add("Measured local disposable PostgreSQL18/macOS Colima; 47M rows and total night are extrapolations, not measured production claims.");
        Files.createDirectories(Path.of("build"));
        Files.write(Path.of("build/community-recommendations-scale.txt"), evidence);
    }
}
