package app.mnema.learning.community;

import app.mnema.learning.platform.jobs.Job;
import app.mnema.learning.platform.jobs.LearningJobs;
import app.mnema.learning.platform.jobs.Slice;
import app.mnema.learning.support.PostgresIntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.node.JsonNodeFactory;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(RecommendationMaintenanceIntegrationTest.DatabaseConfiguration.class)
class RecommendationMaintenanceIntegrationTest extends PostgresIntegrationTest {
    static final String DATABASE = createDatabase("recs_maintenance_" + UUID.randomUUID().toString().replace("-", ""));

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
    @Autowired private LearningJobs jobs;
    private Job job(String queue, long count) {
        return new Job(UUID.randomUUID(), queue, null, JsonNodeFactory.instance.objectNode(), null, count, 1);
    }

    @Test void utcMonthBoundaryKeepsExactlyCurrentAndPreviousTwelveAndPreparesTwo() {
        var clock = new IsolatedCatalogDatabase.TestClock(Instant.parse("2031-03-31T23:59:59Z"));
        var partitions = new CatalogEventPartitions(jdbc, clock);
        var tx = new TransactionTemplate(transactions);
        tx.executeWithoutResult(s -> partitions.run(job(partitions.queue(), 0)));
        assertThat(partitions.names()).hasSize(15).contains("catalog_event_203003", "catalog_event_203103", "catalog_event_203105");
        UUID account = UUID.randomUUID();
        for (String date : java.util.List.of("2030-03-01T00:00:00Z", "2030-04-01T00:00:00Z", "2031-03-31T23:59:59Z")) {
            jdbc.sql("INSERT INTO app_learning.catalog_event(occurred_at,account_id,deck_id,published_revision_id,kind,surface,rank_version) VALUES (:t,:a,uuidv7(),uuidv7(),'DETAIL_VIEW','catalog','catalog-v1')")
                    .param("t", Timestamp.from(Instant.parse(date))).param("a", account).update();
        }

        clock.advance(Duration.ofSeconds(1));
        tx.executeWithoutResult(s -> partitions.run(job(partitions.queue(), 0)));
        assertThat(partitions.names()).hasSize(15).contains("catalog_event_203004", "catalog_event_203106").doesNotContain("catalog_event_203003");
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.catalog_event WHERE account_id=:a").param("a", account).query(Long.class).single()).isEqualTo(2);
        assertThat(((Slice.Done)tx.execute(s -> partitions.run(job(partitions.queue(), 0)))).progressCount()).isZero();
        assertThat(jdbc.sql("SELECT current_setting('lock_timeout'),current_setting('statement_timeout')").query().singleRow().values()).doesNotContain("2s", "10s");
    }

    @Test void telemetryReceiptsExpireInBoundedSlicesButOtherScopesStay() {
        Instant now = Instant.now();
        UUID other = UUID.randomUUID();
        jdbc.sql("INSERT INTO app_learning.command_receipt(command_id,actor_id,command_scope,command_type,payload_hash,result,created_at) " + "SELECT uuidv7(),uuidv7(),'community.events','event.record',decode(repeat('01',32),'hex'),'{}',:t FROM generate_series(1,1001)")
                .param("t", Timestamp.from(now.minus(Duration.ofDays(8)))).update();
        jdbc.sql("INSERT INTO app_learning.command_receipt(command_id,actor_id,command_scope,command_type,payload_hash,result,created_at) VALUES (:id,uuidv7(),'deck.catalog','deck.create',decode(repeat('01',32),'hex'),'{}',:t)")
                .param("id", other).param("t", Timestamp.from(now.minus(Duration.ofDays(8)))).update();
        var retention = new CatalogEventReceiptRetention(jdbc, java.time.Clock.fixed(now, java.time.ZoneOffset.UTC));
        var tx = new TransactionTemplate(transactions);
        Slice first = tx.execute(s -> retention.run(job(retention.queue(), 0)));
        assertThat(first).isInstanceOf(Slice.Continue.class);
        assertThat(((Slice.Done)tx.execute(s -> retention.run(job(retention.queue(), 1000)))).progressCount()).isEqualTo(1001);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.command_receipt WHERE command_scope='community.events'").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.command_receipt WHERE command_id=:id").param("id", other).query(Long.class).single()).isEqualTo(1);
    }

    @Test void retentionAlwaysSchedulesAndNightlyInterestsOnlyWhenEitherSwitchIsOn() {
        jdbc.sql("DELETE FROM app_learning.learning_job WHERE queue IN ('catalog.events.partitions','catalog.events.receipts','catalog.interests.rebuild')").update();
        var clock = new IsolatedCatalogDatabase.TestClock(Instant.parse("2032-01-02T03:00:00Z"));
        var off = new RecommendationSchedule(jobs, new RecommendationSettings(false, false), clock);
        off.tick();
        off.tick();
        new RecommendationSchedule(jobs, new RecommendationSettings(false, false), clock).tick();
        assertThat(jdbc.sql("SELECT queue FROM app_learning.learning_job WHERE queue LIKE 'catalog.events.%' ORDER BY queue").query(String.class).list()).containsExactly("catalog.events.partitions", "catalog.events.receipts");
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.learning_job WHERE queue='catalog.interests.rebuild'").query(Long.class).single()).isZero();
        new RecommendationSchedule(jobs, new RecommendationSettings(true, false), clock).tick();
        assertThat(jdbc.sql("SELECT dedupe_key FROM app_learning.learning_job WHERE queue='catalog.interests.rebuild'").query(String.class).list()).containsExactly("interests:2032-01-02");
        clock.advance(Duration.ofDays(1));
        off.tick();
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.learning_job WHERE queue='catalog.events.partitions'").query(Long.class).single()).isEqualTo(2);
    }
}
