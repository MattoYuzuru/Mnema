package app.mnema.learning.persistence;

import app.mnema.learning.support.PostgresIntegrationTest;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V21 refuses old exercise data instead of reinterpreting it, and passes on an empty database. */
class ExerciseMechanicsMigrationIntegrationTest extends PostgresIntegrationTest {
    @Test
    void theWholeChainMigratesAnEmptyDatabaseToTheFiveMechanicSchema() {
        String url = createDatabase("migration_empty");
        Flyway latest = flyway(url, null);
        assertThat(latest.migrate().migrationsExecuted).isGreaterThanOrEqualTo(21);
        JdbcClient jdbc = JdbcClient.create(new DriverManagerDataSource(url, username(), password()));
        assertThat(jdbc.sql("SELECT count(*) FROM information_schema.columns WHERE table_schema='app_learning' "
                + "AND table_name='exercise_revision' AND column_name IN ('content','answer_key')")
                .query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM information_schema.tables WHERE table_schema='app_learning' "
                + "AND table_name IN ('study_hint_reveal','study_transcript_accommodation')")
                .query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void aDatabaseWithALegacyExerciseRowFailsClosedAndIsLeftUntouched() throws Exception {
        String url = createDatabase("migration_legacy");
        flyway(url, "20").migrate();
        // seed one legacy-typed revision; foreign keys and guards are not what this test is about
        seed(url, "INSERT INTO app_learning.exercise_revision(deck_id,exercise_id,revision_id,reuse_scope_id,"
                + "exercise_sequence,deck_revision_id,deck_sequence,command_id,exercise_type,schema_version,enabled,"
                + "prompt_spec,evaluator_policy,descriptor_root_id,created_at) VALUES ('" + UUID.randomUUID() + "','"
                + UUID.randomUUID() + "','" + UUID.randomUUID() + "','" + UUID.randomUUID() + "',0,'" + UUID.randomUUID()
                + "',1,'" + UUID.randomUUID() + "','TYPED',1,TRUE,'{}'::jsonb,'{}'::jsonb,'" + UUID.randomUUID()
                + "',CURRENT_TIMESTAMP)");
        JdbcClient jdbc = JdbcClient.create(new DriverManagerDataSource(url, username(), password()));

        assertThatThrownBy(() -> flyway(url, null).migrate()).isInstanceOf(FlywayException.class)
                .hasMessageContaining("fresh local Learning database is required");
        // nothing was deleted or rewritten and the old schema is still in place
        assertThat(jdbc.sql("SELECT exercise_type FROM app_learning.exercise_revision").query(String.class).single())
                .isEqualTo("TYPED");
        assertThat(jdbc.sql("SELECT count(*) FROM information_schema.columns WHERE table_schema='app_learning' "
                + "AND table_name='exercise_revision' AND column_name='prompt_spec'").query(Long.class).single()).isOne();
    }

    @Test
    void aLegacyObjectiveAloneAlsoBlocksTheMigration() throws Exception {
        String url = createDatabase("migration_objective");
        flyway(url, "20").migrate();
        seed(url, "INSERT INTO app_learning.objective_revision(deck_id,objective_id,revision_id,objective_sequence,"
                + "deck_revision_id,deck_sequence,command_id,answer_contract,created_at) VALUES ('" + UUID.randomUUID()
                + "','" + UUID.randomUUID() + "','" + UUID.randomUUID() + "',0,'" + UUID.randomUUID() + "',1,'"
                + UUID.randomUUID() + "','{\"schemaVersion\":1}'::jsonb,CURRENT_TIMESTAMP)");
        assertThatThrownBy(() -> flyway(url, null).migrate()).isInstanceOf(FlywayException.class)
                .hasMessageContaining("scripts/mnema-local-full-stack.sh reset --confirm-delete-local-data");
    }

    /** One connection with triggers and foreign keys off, so a minimal legacy row can be planted. */
    private static void seed(String url, String insert) throws Exception {
        try (var connection = java.sql.DriverManager.getConnection(url, username(), password());
             var statement = connection.createStatement()) {
            statement.execute("SET session_replication_role = replica");
            statement.execute(insert);
        }
    }

    private static Flyway flyway(String url, String target) {
        var configuration = Flyway.configure().dataSource(url, username(), password())
                .locations("classpath:db/learning/migration").schemas("app_learning").defaultSchema("app_learning")
                .createSchemas(true).cleanDisabled(true);
        if (target != null) configuration.target(target);
        return configuration.load();
    }
}
