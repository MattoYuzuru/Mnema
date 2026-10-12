package app.mnema.learning.update.plan;

import app.mnema.learning.support.PostgresIntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Updates/6 part B (#445), migration {@code V65__fork_update_log.sql} on real data: it only adds (nothing that exists is rewritten), the log refuses every shape that is not an entry,
 * the log and its objective rows go with the copy and with the revision, and the three questions the feature asks of the database are answered by the indexes the migration adds.
 */
class AutoUpdateSchemaIntegrationTest extends PostgresIntegrationTest {
    private static Flyway flyway(String url, String target) {
        var configuration = Flyway.configure().dataSource(url, username(), password()).locations("classpath:db/learning/migration").schemas("app_learning")
                .defaultSchema("app_learning").createSchemas(true).cleanDisabled(true);
        if (target != null) configuration.target(target);
        return configuration.load();
    }

    private static String columns(JdbcClient jdbc, String table) {
        return jdbc.sql("SELECT string_agg(column_name || ':' || data_type || ':' || is_nullable, ',' ORDER BY ordinal_position) FROM information_schema.columns "
                + "WHERE table_schema='app_learning' AND table_name=:t").param("t", table).query(String.class).single();
    }

    private static void entry(JdbcClient jdbc, UUID deck, UUID revision, String mode, int applied, int pending) {
        jdbc.sql("INSERT INTO app_learning.fork_update_log(deck_id,deck_revision_id,upstream_revision_id,mode,applied_units,pending_units,applied_at) "
                + "VALUES (:d,:r,gen_random_uuid(),:mode,:a,:p,clock_timestamp())").param("d", deck).param("r", revision).param("mode", mode).param("a", applied).param("p", pending).update();
    }

    @Test
    void v65OnlyAddsTheLogTheObjectiveRowsAndTwoPartialIndexesAndTouchesNothingThatExists() {
        String url = createDatabase("fork_update_log_v64");
        flyway(url, "64").migrate();
        JdbcClient jdbc = JdbcClient.create(new DriverManagerDataSource(url, username(), password()));
        assertThat(jdbc.sql("SELECT count(*) FROM information_schema.tables WHERE table_schema='app_learning' AND table_name LIKE 'fork_update_log%'").query(Long.class).single()).isZero();
        String forkColumns = columns(jdbc, "deck_fork");
        String jobIndexes = jdbc.sql("SELECT string_agg(indexdef,';' ORDER BY indexname) FROM pg_indexes WHERE schemaname='app_learning' AND tablename IN ('deck_fork','fork_apply_log','fork_unit_decision')")
                .query(String.class).single();

        Flyway migrating = flyway(url, null);
        assertThat(migrating.migrate().success).isTrue();
        assertThat(migrating.info().applied()).anyMatch(info -> info.getVersion() != null && "65".equals(info.getVersion().getVersion()));
        assertThat(columns(jdbc, "deck_fork")).as("no column of a table that exists changed").isEqualTo(forkColumns);
        String after = jdbc.sql("SELECT string_agg(indexdef,';' ORDER BY indexname) FROM pg_indexes WHERE schemaname='app_learning' AND tablename IN ('deck_fork','fork_apply_log','fork_unit_decision')")
                .query(String.class).single();
        assertThat(List.of(after.split(";"))).as("every index that existed is as it was, one is new").containsAll(List.of(jobIndexes.split(";"))).hasSize(jobIndexes.split(";").length + 1);
        assertThat(after).contains("deck_fork_auto_fanout");

        assertThat(columns(jdbc, "fork_update_log")).isEqualTo("deck_id:uuid:NO,deck_revision_id:uuid:NO,upstream_revision_id:uuid:NO,mode:text:NO,applied_units:integer:NO,pending_units:integer:NO,"
                + "applied_at:timestamp with time zone:NO,seen_at:timestamp with time zone:YES");
        assertThat(columns(jdbc, "fork_update_log_objective")).isEqualTo("deck_id:uuid:NO,deck_revision_id:uuid:NO,objective_id:uuid:NO,previous_revision_id:uuid:YES,revision_id:uuid:NO");
        assertThat(jdbc.sql("SELECT indexdef FROM pg_indexes WHERE indexname='fork_update_log_unseen_auto'").query(String.class).single())
                .contains("(deck_id, applied_at DESC)").contains("mode = 'AUTO'").contains("seen_at IS NULL");
        assertThat(jdbc.sql("SELECT indexdef FROM pg_indexes WHERE indexname='deck_fork_auto_fanout'").query(String.class).single())
                .contains("(upstream_deck_id, deck_id)").contains("auto_update").contains("materialized_at IS NOT NULL").contains("detached_at IS NULL").contains("released_at IS NULL");
        assertThat(jdbc.sql("SELECT indexdef FROM pg_indexes WHERE indexname='fork_update_log_history'").query(String.class).single()).contains("(deck_id, applied_at DESC, deck_revision_id DESC)");
        // the four foreign keys are the ones that make the rows go with the copy, the revision and the entry
        assertThat(jdbc.sql("SELECT string_agg(conname || ' ' || CAST(confdeltype AS text), ',' ORDER BY conname) FROM pg_constraint WHERE contype='f' AND conrelid IN "
                + "(CAST('app_learning.fork_update_log' AS regclass),CAST('app_learning.fork_update_log_objective' AS regclass))").query(String.class).single())
                .isEqualTo("fork_update_log_fork_fkey c,fork_update_log_objective_log_fkey c,fork_update_log_revision_fkey c");
    }

    @Test
    void theLogRefusesEveryShapeThatIsNotAnEntryOfAnApply() {
        String url = createDatabase("fork_update_log_shapes");
        flyway(url, null).migrate();
        DriverManagerDataSource source = new DriverManagerDataSource(url, username(), password());
        JdbcClient jdbc = JdbcClient.create(source);
        // the keys need real parents; the shape rules are what is tested here, so the foreign keys are switched off for these statements and checked at the end
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        UUID deck = UUID.randomUUID();
        UUID revision = UUID.randomUUID();
        tx.executeWithoutResult(status -> {
            jdbc.sql("SET LOCAL session_replication_role = replica").update();
            entry(jdbc, deck, revision, "AUTO", 3, 2);
            entry(jdbc, deck, UUID.randomUUID(), "MANUAL", 0, 0);
            assertThat(jdbc.sql("SELECT count(*) FROM app_learning.fork_update_log WHERE deck_id=:d").param("d", deck).query(Long.class).single()).isEqualTo(2);
        });
        for (String bad : new String[] {"'MANUALLY'", "''", "NULL", "'auto'"}) {
            assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                jdbc.sql("SET LOCAL session_replication_role = replica").update();
                jdbc.sql("INSERT INTO app_learning.fork_update_log(deck_id,deck_revision_id,upstream_revision_id,mode,applied_units,pending_units,applied_at) "
                        + "VALUES (gen_random_uuid(),gen_random_uuid(),gen_random_uuid()," + bad + ",1,1,clock_timestamp())").update();
            })).as(bad).hasMessageContaining("");
        }
        for (String[] counts : new String[][] {{"-1", "0"}, {"0", "-1"}}) {
            assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                jdbc.sql("SET LOCAL session_replication_role = replica").update();
                jdbc.sql("INSERT INTO app_learning.fork_update_log(deck_id,deck_revision_id,upstream_revision_id,mode,applied_units,pending_units,applied_at) "
                        + "VALUES (gen_random_uuid(),gen_random_uuid(),gen_random_uuid(),'AUTO'," + counts[0] + "," + counts[1] + ",clock_timestamp())").update();
            })).hasMessageContaining("check");
        }
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            jdbc.sql("SET LOCAL session_replication_role = replica").update();
            jdbc.sql("INSERT INTO app_learning.fork_update_log(deck_id,deck_revision_id,upstream_revision_id,mode,applied_units,pending_units,applied_at) "
                    + "VALUES (gen_random_uuid(),gen_random_uuid(),gen_random_uuid(),'AUTO',0,0,'infinity')").update();
        })).hasMessageContaining("check");
        // one entry per revision of a copy
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            jdbc.sql("SET LOCAL session_replication_role = replica").update();
            entry(jdbc, deck, revision, "AUTO", 1, 1);
        })).hasMessageContaining("fork_update_log_pkey");
        // without the switch the keys are enforced: no copy, no entry
        assertThatThrownBy(() -> entry(jdbc, UUID.randomUUID(), UUID.randomUUID(), "AUTO", 1, 1)).hasMessageContaining("fork_update_log_fork_fkey");
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO app_learning.fork_update_log_objective(deck_id,deck_revision_id,objective_id,previous_revision_id,revision_id) "
                + "VALUES (:d,:r,gen_random_uuid(),NULL,gen_random_uuid())").param("d", UUID.randomUUID()).param("r", UUID.randomUUID()).update())
                .hasMessageContaining("fork_update_log_objective_log_fkey");
    }

    @Test
    void theThreeQuestionsOfTheFeatureAreAnsweredByTheirPartialIndexes() {
        String url = createDatabase("fork_update_log_plans");
        flyway(url, null).migrate();
        DriverManagerDataSource data = new DriverManagerDataSource(url, username(), password());
        JdbcClient jdbc = JdbcClient.create(data);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(data));
        UUID source = UUID.randomUUID();
        UUID deck = UUID.randomUUID();
        tx.executeWithoutResult(status -> {
            jdbc.sql("SET LOCAL session_replication_role = replica").update();
            // 400 copies of one source, half of them automatic; 60 entries of one copy
            jdbc.sql("""
                    INSERT INTO app_learning.deck_fork(deck_id,reuse_scope_id,owner_id,upstream_deck_id,upstream_code,base_revision_id,forked_at,job_id,auto_update,materialized_at)
                    SELECT gen_random_uuid(),gen_random_uuid(),gen_random_uuid(),:source,'Kq7xT3mNpR',gen_random_uuid(),clock_timestamp(),gen_random_uuid(),n % 2 = 0,clock_timestamp()
                      FROM generate_series(1,400) n
                    """).param("source", source).update();
            for (int index = 0; index < 60; index++) entry(jdbc, deck, UUID.randomUUID(), index % 3 == 0 ? "AUTO" : "MANUAL", index, 0);
            // 3 000 Study sessions of 30 owners over 100 decks: the activity probe is the owner's sessions of ONE deck
            jdbc.sql("""
                    INSERT INTO app_learning.study_session(account_id,session_id,deck_id,command_id,mode,status,timezone,local_study_date,deck_revision_id,deck_sequence,
                        exercise_generation_id,selection_policy_version,reducer_config_id,seed,budget,issued_count,batch_start,batch_size,scan_cursor,wrapped,include_new,
                        row_version,created_at,expires_at,max_new_objectives,issued_new_objectives)
                    SELECT a.account,gen_random_uuid(),d.deck,gen_random_uuid(),'SCHEDULED','ACTIVE','UTC',current_date,gen_random_uuid(),0,gen_random_uuid(),'v1',gen_random_uuid(),1,10,0,0,0,0,
                           FALSE,FALSE,0,now() - (n % 90) * interval '1 day',now() + interval '1 day',5,0
                      FROM generate_series(1,3000) n
                      JOIN LATERAL (SELECT ('00000000-0000-4000-8000-' || lpad((n % 30)::text,12,'0'))::uuid AS account) a ON TRUE
                      JOIN LATERAL (SELECT ('00000000-0000-4000-9000-' || lpad((n % 100)::text,12,'0'))::uuid AS deck) d ON TRUE
                    """).update();
            jdbc.sql("ANALYZE app_learning.study_session").update();
            jdbc.sql("ANALYZE app_learning.deck_fork").update();
            jdbc.sql("ANALYZE app_learning.fork_update_log").update();
        });
        String fanout = plan(tx, jdbc, "SELECT f.deck_id FROM app_learning.deck_fork f WHERE f.upstream_deck_id=:p AND f.deck_id > :a AND f.auto_update AND f.materialized_at IS NOT NULL "
                + "AND f.detached_at IS NULL AND f.released_at IS NULL ORDER BY f.deck_id LIMIT 10", source, new UUID(0, 0));
        assertThat(fanout).contains("deck_fork_auto_fanout").doesNotContain("Sort");
        String notice = plan(tx, jdbc, "SELECT l.deck_revision_id FROM app_learning.fork_update_log l WHERE l.deck_id=:p AND l.mode='AUTO' AND l.seen_at IS NULL ORDER BY l.applied_at DESC LIMIT 1",
                deck, null);
        assertThat(notice).contains("fork_update_log_unseen_auto").doesNotContain("Sort");
        String history = plan(tx, jdbc, "SELECT l.deck_revision_id FROM app_learning.fork_update_log l WHERE l.deck_id=:p ORDER BY l.applied_at DESC, l.deck_revision_id DESC LIMIT 5", deck, null);
        assertThat(history).contains("fork_update_log_history").doesNotContain("Sort");
        UUID owner = UUID.fromString("00000000-0000-4000-8000-000000000003");
        UUID studied = UUID.fromString("00000000-0000-4000-9000-000000000003");
        String activity = tx.execute(status -> {
            jdbc.sql("SET LOCAL enable_seqscan = off").update();
            return String.join("\n", jdbc.sql("EXPLAIN SELECT 1 FROM app_learning.study_session s WHERE s.account_id=:o AND s.deck_id=:d AND s.created_at >= now() - interval '30 days'")
                    .param("o", owner).param("d", studied).query(String.class).list());
        });
        assertThat(activity).as("the activity signal is the existing index of the owner's sessions of that deck").contains("study_session_resume");
        // the index holds only what the feature asks for
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.deck_fork WHERE upstream_deck_id=:s AND auto_update").param("s", source).query(Long.class).single()).isEqualTo(200L);
    }

    private static String plan(TransactionTemplate tx, JdbcClient jdbc, String sql, UUID first, UUID second) {
        return tx.execute(status -> {
            jdbc.sql("SET LOCAL enable_seqscan = off").update();
            jdbc.sql("SET LOCAL enable_bitmapscan = off").update();
            var statement = jdbc.sql("EXPLAIN " + sql).param("p", first);
            if (second != null) statement = statement.param("a", second);
            List<String> lines = statement.query(String.class).list();
            return String.join("\n", lines);
        });
    }
}
