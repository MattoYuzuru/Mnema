package app.mnema.learning.update.plan;

import app.mnema.learning.support.PostgresIntegrationTest;
import app.mnema.learning.update.FinalWrites;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Updates/4 (#443), migration {@code V63__apply_update_support.sql} on real data: the final writes of a stored command may be as large as the apply command needs (and no larger),
 * a row written before the migration is untouched, and the plan route's question about the newest job of an owner for a copy is answered by the index the migration adds.
 */
class ApplySchemaIntegrationTest extends PostgresIntegrationTest {
    private static Flyway flyway(String url, String target) {
        var configuration = Flyway.configure().dataSource(url, username(), password()).locations("classpath:db/learning/migration").schemas("app_learning")
                .defaultSchema("app_learning").createSchemas(true).cleanDisabled(true);
        if (target != null) configuration.target(target);
        return configuration.load();
    }

    private static void command(JdbcClient jdbc, UUID id, String finalWrites) {
        jdbc.sql("INSERT INTO app_learning.combined_publication(command_id,deck_id,actor_id,expected_head_revision_id,digest,unit_count,manifest_unit_count,final_writes) "
                + "VALUES (:id,gen_random_uuid(),gen_random_uuid(),gen_random_uuid(),decode(repeat('ab',32),'hex'),0,0,:writes)").param("id", id).param("writes", finalWrites).update();
    }

    @Test
    void v63RaisesTheLimitOfTheFinalWritesOfAStoredCommandKeepsItsRowsAndAddsTheIndexOfTheOwnersJobs() {
        String url = createDatabase("apply_update_v61");
        flyway(url, "61").migrate();
        JdbcClient jdbc = JdbcClient.create(new DriverManagerDataSource(url, username(), password()));
        UUID old = UUID.randomUUID();
        command(jdbc, old, "{\"type\":\"fork.apply\",\"body\":{}}");
        // before: 128 KiB
        assertThatThrownBy(() -> command(jdbc, UUID.randomUUID(), "x".repeat(131_073))).hasMessageContaining("combined_publication_final_writes_check");
        assertThat(jdbc.sql("SELECT count(*) FROM pg_indexes WHERE indexname='learning_job_combined_owner'").query(Long.class).single()).isZero();

        Flyway migrating = flyway(url, null);
        assertThat(migrating.migrate().success).isTrue();
        assertThat(migrating.info().applied()).anyMatch(info -> info.getVersion() != null && "63".equals(info.getVersion().getVersion()));
        assertThat(jdbc.sql("SELECT final_writes FROM app_learning.combined_publication WHERE command_id=:id").param("id", old).query(String.class).single())
                .isEqualTo("{\"type\":\"fork.apply\",\"body\":{}}");

        // after: the envelope of the biggest body the engine accepts fits, one byte more than the column's room does not
        int room = 4_198_400;
        UUID big = UUID.randomUUID();
        command(jdbc, big, "x".repeat(room));
        assertThat(jdbc.sql("SELECT octet_length(final_writes) FROM app_learning.combined_publication WHERE command_id=:id").param("id", big).query(Integer.class).single()).isEqualTo(room);
        assertThat(room).as("the column has room for the biggest body plus its envelope").isGreaterThan(FinalWrites.MAX_BYTES + 100);
        assertThatThrownBy(() -> command(jdbc, UUID.randomUUID(), "x".repeat(room + 1))).hasMessageContaining("combined_publication_final_writes_check");
        assertThat(jdbc.sql("SELECT count(*) FROM pg_constraint WHERE conname='combined_publication_final_writes_check' AND convalidated").query(Long.class).single()).isOne();

        // the log of the last successful apply of a copy: one row per copy, gone with the copy
        assertThat(jdbc.sql("SELECT string_agg(column_name || ':' || data_type, ',' ORDER BY ordinal_position) FROM information_schema.columns WHERE table_schema='app_learning' AND table_name='fork_apply_log'")
                .query(String.class).single()).isEqualTo("deck_id:uuid,applied_at:timestamp with time zone");
        assertThat(jdbc.sql("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid='app_learning.fork_apply_log'::regclass AND contype='f'").query(String.class).single())
                .contains("REFERENCES app_learning.deck_fork(deck_id)").contains("ON DELETE CASCADE");
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO app_learning.fork_apply_log(deck_id,applied_at) VALUES (gen_random_uuid(),now())").update()).hasMessageContaining("fork_apply_log");

        // the index of the owner's jobs on the queue of the combined publication, partial and in time order
        assertThat(jdbc.sql("SELECT indexdef FROM pg_indexes WHERE indexname='learning_job_combined_owner'").query(String.class).single())
                .contains("(account_id, created_at DESC, job_id DESC)").contains("deck.combined.publish");
        UUID owner = UUID.randomUUID();
        UUID deck = UUID.randomUUID();
        for (int index = 0; index < 3; index++) {
            jdbc.sql("INSERT INTO app_learning.learning_job(queue,dedupe_key,account_id,payload,state,max_attempts,run_after) VALUES ('deck.combined.publish',:k,:a,CAST(:p AS jsonb),'QUEUED',5,now())")
                    .param("k", UUID.randomUUID().toString()).param("a", owner).param("p", "{\"deckId\":\"" + deck + "\"}").update();
        }
        // a table of a few rows is read in one scan; with the scan off, the ordered, owner-bound question is answered by the index (one session for both statements)
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection(url, username(), password()); java.sql.Statement statement = connection.createStatement()) {
            statement.execute("SET enable_seqscan = off");
            StringBuilder plan = new StringBuilder();
            try (java.sql.ResultSet rows = statement.executeQuery("EXPLAIN SELECT j.dedupe_key FROM app_learning.learning_job j WHERE j.queue='deck.combined.publish' AND j.account_id='"
                    + owner + "' AND j.payload->>'deckId'='" + deck + "' ORDER BY j.created_at DESC, j.job_id DESC LIMIT 1")) {
                while (rows.next()) plan.append(rows.getString(1)).append('\n');
            }
            assertThat(plan.toString()).contains("learning_job_combined_owner").doesNotContain("Sort");
        } catch (java.sql.SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
