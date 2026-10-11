package app.mnema.learning.persistence;

import app.mnema.learning.support.PostgresIntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V59 (#460) on data written at the V58 shape: the seed of the V51 test migrated up to V58 plus a second session of the same learner
 * (a practice attempt at the same exercise, a not-assessed one) and one of another learner. The anchor table is backfilled from the assessed
 * tombstones, nothing else changes, the constraint, index and guard inventory is exact, and the guards behave as designed.
 */
class StudySnapshotRetentionMigrationIntegrationTest extends PostgresIntegrationTest {
    private static final String SNAPSHOT_TABLES = "study_presentation,study_exposure,study_attempt_tombstone,study_evidence,study_transition,study_state,"
            + "study_policy_assignment,study_candidate,study_hint_reveal,study_transcript_accommodation";

    @Test
    void theAnchorTableIsBackfilledFromAssessedTombstonesAndNothingElseIsRewritten() throws Exception {
        String url = createDatabase("study_snapshot_retention_v58");
        flyway(url, "51").migrate();
        seedV51(url);
        flyway(url, "58").migrate();
        extraRows(url);
        JdbcClient jdbc = JdbcClient.create(new DriverManagerDataSource(url, username(), password()));
        String before = frozen(jdbc);
        assertThat(columns(jdbc, "study_session")).doesNotContain("presentations_purged_at");
        assertThat(tableExists(jdbc, "study_exercise_attempt")).isFalse();
        // the facts the backfill must reproduce: assessed tombstones (any mode) per (account, deck, exercise, epoch)
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.study_attempt_tombstone WHERE status='ASSESSED'").query(Long.class).single()).isEqualTo(3L);

        Flyway migrating = flyway(url, null);
        assertThat(migrating.migrate().success).isTrue();
        assertThat(migrating.info().current().getState().isApplied()).isTrue();
        assertThat(jdbc.sql("SELECT success FROM app_learning.flyway_schema_history WHERE version='59'").query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT description FROM app_learning.flyway_schema_history WHERE version='59'").query(String.class).single())
                .isEqualTo("study snapshot retention");

        var rows = jdbc.sql("SELECT account_id||' '||deck_id||' '||exercise_id||' '||learning_epoch||' '||attempts FROM app_learning.study_exercise_attempt")
                .query(String.class).list();
        assertThat(rows).containsExactlyInAnyOrder(id("o1") + " " + id("d1") + " " + id("e1") + " 0 2", id("o2") + " " + id("d2") + " " + id("f1") + " 0 1");
        // the not-assessed attempt (e2) and the objective of no tombstone left no fact
        assertThat(rows).noneMatch(row -> row.contains(id("e2").toString()));
        // every progress and snapshot table is byte-identical
        assertThat(frozen(jdbc)).isEqualTo(before);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.study_session WHERE presentations_purged_at IS NOT NULL").query(Long.class).single()).isZero();
    }

    @Test
    void theConstraintIndexAndGuardInventoryIsExact() throws Exception {
        String url = createDatabase("study_snapshot_retention_inventory");
        flyway(url, null).migrate();
        JdbcClient jdbc = JdbcClient.create(new DriverManagerDataSource(url, username(), password()));

        // the two keys that pinned the snapshot are gone; the keys around them are not
        assertThat(constraints(jdbc, "study_attempt_tombstone")).containsExactlyInAnyOrder(
                "study_attempt_tombstone_account_id_session_id_presentation__key UNIQUE (account_id, session_id, presentation_id)",
                "study_attempt_tombstone_pkey PRIMARY KEY (attempt_id)");
        assertThat(constraints(jdbc, "study_session")).containsExactlyInAnyOrder(
                "study_session_pkey PRIMARY KEY (account_id, session_id)",
                "study_session_command_id_key UNIQUE (command_id)",
                "study_session_session_id_key UNIQUE (session_id)",
                "study_session_account_id_source_session_id_fkey FOREIGN KEY (account_id, source_session_id) REFERENCES study_session(account_id, session_id)",
                "study_session_deck_id_deck_revision_id_deck_sequence_fkey FOREIGN KEY (deck_id, deck_revision_id, deck_sequence) REFERENCES deck_revision(deck_id, revision_id, sequence)",
                "study_session_reducer_config_id_fkey FOREIGN KEY (reducer_config_id) REFERENCES scheduler_config(config_id)");
        assertThat(constraints(jdbc, "study_exercise_attempt")).containsExactly(
                "study_exercise_attempt_pkey PRIMARY KEY (account_id, deck_id, exercise_id, learning_epoch)");
        // the evidence guard, the candidate key and the child tables still reference the presentation and the candidate
        assertThat(constraints(jdbc, "study_presentation")).contains(
                "study_presentation_candidate_fkey FOREIGN KEY (deck_id, generation_id, candidate_ordinal, exercise_id, exercise_revision_id, objective_id, objective_revision_id) REFERENCES study_candidate(deck_id, generation_id, candidate_ordinal, exercise_id, exercise_revision_id, objective_id, objective_revision_id)");
        for (String child : List.of("study_exposure", "study_hint_reveal", "study_transcript_accommodation", "study_assessment")) {
            assertThat(constraints(jdbc, child)).as(child).anyMatch(line -> line.contains("REFERENCES study_presentation(account_id, session_id, presentation_id)"));
        }

        // indexes
        assertThat(indexes(jdbc)).contains(
                "study_session_unpurged_age ON study_session USING btree (created_at, session_id) WHERE (presentations_purged_at IS NULL)",
                "study_session_unpurged_generation ON study_session USING btree (deck_id, exercise_generation_id) WHERE (presentations_purged_at IS NULL)",
                "study_session_unpurged_source ON study_session USING btree (account_id, source_session_id) WHERE ((presentations_purged_at IS NULL) AND (source_session_id IS NOT NULL))",
                "study_candidate_generation_sweep ON study_candidate_generation USING btree (swept_at NULLS FIRST, created_at) WHERE (evicting_since IS NULL)",
                "study_presentation_candidate ON study_presentation USING btree (deck_id, generation_id, candidate_ordinal)",
                "study_candidate_generation_evicting ON study_candidate_generation USING btree (evicting_since) WHERE (evicting_since IS NOT NULL)");
        assertThat(indexes(jdbc)).noneMatch(index -> index.startsWith("study_presentation_exercise "));
        // new columns
        assertThat(columns(jdbc, "study_session")).contains("presentations_purged_at");
        assertThat(columns(jdbc, "study_candidate_generation")).contains("evicting_since", "swept_at");
        // a plain nullable column: no CHECK that would make ADD COLUMN scan, no default
        assertThat(jdbc.sql("SELECT count(*) FROM pg_constraint WHERE conrelid='app_learning.study_session'::regclass AND contype='c' "
                + "AND pg_get_constraintdef(oid) LIKE '%presentations_purged_at%'").query(Long.class).single()).isZero();
        // the missing foreign key is documented where the column is
        assertThat(jdbc.sql("SELECT col_description('app_learning.study_session'::regclass, (SELECT attnum FROM pg_attribute WHERE "
                + "attrelid='app_learning.study_session'::regclass AND attname='exercise_generation_id'))").query(String.class).single())
                .contains("Intentionally NOT a foreign key");
        assertThat(columns(jdbc, "study_exercise_attempt")).containsExactly("account_id", "deck_id", "exercise_id", "learning_epoch", "attempts");

        // guards: exactly the five snapshot tables use the retention guard, the permanent ones keep the immutable guard, all enabled
        List<String> guards = jdbc.sql("""
                SELECT replace(c.relname||'.'||t.tgname||' -> '||p.proname||' '||t.tgenabled::text,'app_learning.','')
                  FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid JOIN pg_proc p ON p.oid=t.tgfoid
                 WHERE NOT t.tgisinternal AND c.relnamespace='app_learning'::regnamespace
                   AND (t.tgname LIKE '%immutable' AND c.relname IN ({TABLES}))
                """.replace("{TABLES}", quoted(SNAPSHOT_TABLES + ",study_restart_audit,scheduler_config"))).query(String.class).list();
        assertThat(guards).containsExactlyInAnyOrder(
                "study_presentation.study_presentation_immutable -> study_snapshot_retention_guard O",
                "study_exposure.study_exposure_immutable -> study_snapshot_retention_guard O",
                "study_hint_reveal.study_hint_reveal_immutable -> study_snapshot_retention_guard O",
                "study_transcript_accommodation.study_transcript_accommodation_immutable -> study_snapshot_retention_guard O",
                "study_candidate.study_candidate_immutable -> study_snapshot_retention_guard O",
                "study_evidence.study_evidence_immutable -> study_snapshot_immutable_guard O",
                "study_transition.study_transition_immutable -> study_snapshot_immutable_guard O",
                "study_policy_assignment.study_policy_assignment_immutable -> study_snapshot_immutable_guard O",
                "study_restart_audit.study_restart_audit_immutable -> study_snapshot_immutable_guard O",
                "scheduler_config.scheduler_config_immutable -> study_snapshot_immutable_guard O");
        assertThat(jdbc.sql("SELECT count(*) FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid WHERE NOT t.tgisinternal AND t.tgenabled<>'O' "
                + "AND c.relnamespace='app_learning'::regnamespace").query(Long.class).single()).isZero();
    }

    @Test
    void theGuardsLetOnlyADeleteInsideARetentionTransactionThrough() throws Exception {
        String url = createDatabase("study_snapshot_retention_guards");
        flyway(url, "51").migrate();
        seedV51(url);
        flyway(url, null).migrate();
        // a learner's presentation with an exposure and a receipt, an evidence and a transition, and a presentation with no dependents
        try (Connection connection = DriverManager.getConnection(url, username(), password())) {
            connection.setAutoCommit(false);
            // without the setting nothing is deleted or changed
            assertRefused(connection, "UPDATE app_learning.study_presentation SET nonce=nonce");
            assertRefused(connection, "DELETE FROM app_learning.study_presentation WHERE presentation_id='" + id("p3") + "'");
            assertRefused(connection, "DELETE FROM app_learning.study_exposure");
            assertRefused(connection, "DELETE FROM app_learning.study_candidate");
            assertRefused(connection, "UPDATE app_learning.study_candidate SET reuse_scope_id=reuse_scope_id");
            // with it a DELETE is let through, an UPDATE never, and the permanent tables never
            try (var statement = connection.createStatement()) {
                statement.execute("SELECT set_config('mnema.study_retention','on',true)");
                assertThat(statement.executeUpdate("DELETE FROM app_learning.study_presentation WHERE presentation_id='" + id("p3") + "'")).isOne();
                // a presentation whose receipt stays: the foreign key from the tombstone is gone, only the exposure has to go first
                assertThat(statement.executeUpdate("DELETE FROM app_learning.study_exposure WHERE presentation_id='" + id("p1") + "'")).isOne();
                assertThat(statement.executeUpdate("DELETE FROM app_learning.study_presentation WHERE presentation_id='" + id("p1") + "'")).isOne();
                try (var receipt = statement.executeQuery("SELECT count(*) FROM app_learning.study_attempt_tombstone WHERE presentation_id='"
                        + id("p1") + "'")) {
                    assertThat(receipt.next()).isTrue();
                    assertThat(receipt.getLong(1)).isOne();
                }
            }
            assertRefused(connection, "UPDATE app_learning.study_presentation SET nonce=nonce");
            assertRefused(connection, "DELETE FROM app_learning.study_evidence");
            assertRefused(connection, "DELETE FROM app_learning.study_transition");
            assertRefused(connection, "DELETE FROM app_learning.study_policy_assignment");
            assertRefused(connection, "UPDATE app_learning.study_evidence SET result=result");
            connection.rollback();
            // the setting died with the transaction
            assertRefused(connection, "DELETE FROM app_learning.study_presentation WHERE presentation_id='" + id("p3") + "'");
            connection.rollback();
        }
    }

    // ---------------------------------------------------------------------------------------------------------- helpers

    private static void assertRefused(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("SAVEPOINT guard");
            assertThatThrownBy(() -> statement.execute(sql)).as(sql).hasMessageContaining("Immutable study snapshot");
            statement.execute("ROLLBACK TO SAVEPOINT guard");
        }
    }

    private static String quoted(String names) { return "'" + String.join("','", names.split(",")) + "'"; }

    private static List<String> constraints(JdbcClient jdbc, String table) {
        return jdbc.sql("SELECT conname||' '||replace(pg_get_constraintdef(oid),'app_learning.','') FROM pg_constraint WHERE conrelid=:t::regclass "
                + "AND contype IN ('p','u','f')").param("t", "app_learning." + table).query(String.class).list();
    }

    private static List<String> indexes(JdbcClient jdbc) {
        return jdbc.sql("SELECT replace(replace(indexdef,'CREATE INDEX ',''),'app_learning.','') FROM pg_indexes WHERE schemaname='app_learning' "
                + "AND tablename LIKE 'study%'").query(String.class).list();
    }

    private static List<String> columns(JdbcClient jdbc, String table) {
        return jdbc.sql("SELECT column_name FROM information_schema.columns WHERE table_schema='app_learning' AND table_name=:t ORDER BY ordinal_position")
                .param("t", table).query(String.class).list();
    }

    private static boolean tableExists(JdbcClient jdbc, String table) {
        return jdbc.sql("SELECT to_regclass(:t) IS NOT NULL").param("t", "app_learning." + table).query(Boolean.class).single();
    }

    /** Every table V59 must leave alone, as one canonical text. */
    private static String frozen(JdbcClient jdbc) {
        StringBuilder all = new StringBuilder();
        for (String table : SNAPSHOT_TABLES.split(",")) {
            all.append(table).append('=').append(jdbc.sql("SELECT COALESCE(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text FROM app_learning."
                    + table + " t").query(String.class).single()).append('\n');
        }
        return all.toString();
    }

    private static Flyway flyway(String url, String target) {
        var configuration = Flyway.configure().dataSource(url, username(), password())
                .locations("classpath:db/learning/migration").schemas("app_learning").defaultSchema("app_learning")
                .createSchemas(true).cleanDisabled(true);
        if (target != null) configuration.target(target);
        return configuration.load();
    }

    private static UUID id(String name) { return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)); }

    private static final Pattern IN_JSON = Pattern.compile("\"\\{\\{(\\w+)}}\"");
    private static final Pattern TOKEN = Pattern.compile("\\{\\{(\\w+)}}");

    private static String expanded(String text) {
        text = IN_JSON.matcher(text).replaceAll(match -> "\"" + id(match.group(1)) + "\"");
        return TOKEN.matcher(text).replaceAll(match -> "'" + id(match.group(1)) + "'");
    }

    private void seedV51(String url) throws IOException, SQLException {
        try (var stream = StudySnapshotRetentionMigrationIntegrationTest.class.getResourceAsStream("/db/v51-exercises-and-study-seed.sql");
             Connection connection = DriverManager.getConnection(url, username(), password());
             var statement = connection.createStatement()) {
            statement.execute(expanded(new String(stream.readAllBytes(), StandardCharsets.UTF_8)));
        }
    }

    /** At the V58 shape, with foreign keys and triggers off: another session of o1 on the same candidates, and one of o2. */
    private void extraRows(String url) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, username(), password());
             var statement = connection.createStatement()) {
            statement.execute("SET session_replication_role = replica");
            statement.execute(expanded("""
                    INSERT INTO app_learning.study_session(account_id,session_id,deck_id,command_id,mode,status,timezone,local_study_date,deck_revision_id,
                        deck_sequence,exercise_generation_id,selection_policy_version,reducer_config_id,seed,budget,issued_count,batch_start,batch_size,
                        scan_cursor,wrapped,include_new,practice_order,source_session_id,row_version,created_at,expires_at,completed_at,max_new_objectives,
                        issued_new_objectives) VALUES
                     ({{o1}},{{ss1x}},{{d1}},{{c30}},'PRACTICE','COMPLETE','UTC',current_date,{{d1r9}},9,{{g1}},'deck-due-new-v3',
                      'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1',7,10,2,0,0,2,FALSE,TRUE,'SEEDED',NULL,0,now(),now()+interval '1 day',now(),0,0),
                     ({{o2}},{{ss2x}},{{d2}},{{c31}},'PRACTICE','COMPLETE','UTC',current_date,{{d2r3}},3,{{g2}},'deck-due-new-v3',
                      'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1',7,10,1,0,0,1,FALSE,TRUE,'SEEDED',NULL,0,now(),now()+interval '1 day',now(),0,0);
                    INSERT INTO app_learning.study_presentation(account_id,session_id,presentation_id,presentation_ordinal,nonce,deck_id,generation_id,
                        candidate_ordinal,exercise_id,exercise_revision_id,exercise_type,objective_id,objective_revision_id,learning_epoch,content,evaluator,
                        answer_key,issued_at,expires_at,reveal) VALUES
                     ({{o1}},{{ss1x}},{{px1}},0,'nonce-nonce-nonce-4',{{d1}},{{g1}},0,{{e1}},{{er1b}},'SELF_CHECK',{{ob1a}},{{obr1a1}},0,'{}','{}','{}',now(),now()+interval '1 day','{}'),
                     ({{o1}},{{ss1x}},{{px2}},1,'nonce-nonce-nonce-5',{{d1}},{{g1}},1,{{e2}},{{er2}},'FREE_RESPONSE',{{ob1b}},{{obr1b0}},0,'{}','{}','{}',now(),now()+interval '1 day','{}'),
                     ({{o2}},{{ss2x}},{{px3}},0,'nonce-nonce-nonce-6',{{d2}},{{g2}},0,{{f1}},{{fr1}},'SELF_CHECK',{{ob2}},{{obr2}},0,'{}','{}','{}',now(),now()+interval '1 day','{}');
                    INSERT INTO app_learning.study_attempt_tombstone(attempt_id,account_id,session_id,presentation_id,deck_id,payload_hash,mode,status,outcome,
                        receipt_expires_at,submitted_at) VALUES
                     ({{ax1}},{{o1}},{{ss1x}},{{px1}},{{d1}},decode(repeat('cd',32),'hex'),'PRACTICE','ASSESSED','{}',now()+interval '1 day',now()),
                     ({{ax2}},{{o1}},{{ss1x}},{{px2}},{{d1}},decode(repeat('ef',32),'hex'),'SCHEDULED','NOT_ASSESSED','{}',NULL,now()),
                     ({{ax3}},{{o2}},{{ss2x}},{{px3}},{{d2}},decode(repeat('12',32),'hex'),'PRACTICE','ASSESSED','{}',now()+interval '1 day',now());
                    """));
        }
    }
}
