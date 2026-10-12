package app.mnema.learning.update.apply;

import app.mnema.learning.support.PostgresIntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Updates/6 part B (#445): «Что нового» keeps the newest {@value ApplyRepository#LOG_KEEP} entries of a copy; the apply that overflows it deletes the older ones and no other copy's.
 * The repository runs on a migrated database of its own (no Spring context); the rows are written with the keys switched off for the transaction (a copy with 60 real revisions is
 * not what is tested) and the entries are told apart by the clock.
 */
class UpdateLogRetentionIntegrationTest extends PostgresIntegrationTest {
    @Test
    void anApplyThatOverflowsTheLogDeletesTheOldestEntriesAndNoOtherCopysEntries() {
        String url = createDatabase("fork_update_log_retention");
        Flyway.configure().dataSource(url, username(), password()).locations("classpath:db/learning/migration").schemas("app_learning").defaultSchema("app_learning")
                .createSchemas(true).cleanDisabled(true).load().migrate();
        DriverManagerDataSource data = new DriverManagerDataSource(url, username(), password());
        JdbcClient jdbc = JdbcClient.create(data);
        ApplyRepository repository = new ApplyRepository(jdbc);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(data));
        UUID deck = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID upstream = UUID.randomUUID();
        List<UUID> revisions = new ArrayList<>();
        tx.executeWithoutResult(status -> {
            jdbc.sql("SET LOCAL session_replication_role = replica").update();
            repository.logUpdate(other, UUID.randomUUID(), upstream, "MANUAL", 1, 0);
            for (int index = 0; index < 49; index++) {
                UUID revision = UUID.randomUUID();
                revisions.add(revision);
                repository.logUpdate(deck, revision, upstream, index % 2 == 0 ? "AUTO" : "MANUAL", index, 0);
            }
        });
        assertThat(count(jdbc, deck)).as("49 entries are all kept").isEqualTo(49);
        for (int index = 49; index < 60; index++) {
            UUID revision = UUID.randomUUID();
            revisions.add(revision);
            tx.executeWithoutResult(status -> {
                jdbc.sql("SET LOCAL session_replication_role = replica").update();
                repository.logUpdate(deck, revision, upstream, "AUTO", 0, 0);
            });
            assertThat(count(jdbc, deck)).isEqualTo(Math.min(50, index + 1));
        }
        List<UUID> kept = jdbc.sql("SELECT deck_revision_id FROM app_learning.fork_update_log WHERE deck_id=:d ORDER BY applied_at DESC,deck_revision_id DESC").param("d", deck)
                .query(UUID.class).list();
        assertThat(kept).as("the newest fifty, newest first").containsExactlyElementsOf(revisions.reversed().subList(0, 50));
        assertThat(count(jdbc, other)).as("another copy's entries are not touched").isOne();
    }

    private static long count(JdbcClient jdbc, UUID deck) {
        return jdbc.sql("SELECT count(*) FROM app_learning.fork_update_log WHERE deck_id=:d").param("d", deck).query(Long.class).single();
    }
}
