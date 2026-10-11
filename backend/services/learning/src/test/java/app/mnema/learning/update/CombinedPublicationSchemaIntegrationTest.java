package app.mnema.learning.update;

import app.mnema.learning.support.PostgresIntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.TreeSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V60 (Updates/2, #441) against a real PostgreSQL: it is applied on data written at the V58 shape, it changes exactly the constraints and indexes the migration says it
 * changes (the inventory diff below), and what it loosened really is loosened (many exercises per revision, a re-adopted revision journaled twice, origin sequence numbers
 * that repeat) while what it kept still holds (a deck revision writes at most one revision of a member).
 */
class CombinedPublicationSchemaIntegrationTest extends PostgresIntegrationTest {
    private static final String TABLES = "'app_learning.item_revision'::regclass,'app_learning.exercise_revision'::regclass,'app_learning.objective_revision'::regclass,"
            + "'app_learning.deck_item_change'::regclass,'app_learning.deck_exercise_change'::regclass";

    private final UUID scope = UUID.randomUUID();
    private final UUID owner = UUID.randomUUID();
    private final UUID deck = UUID.randomUUID();
    private final UUID otherDeck = UUID.randomUUID();
    private final UUID member = UUID.randomUUID();
    private final UUID exercise = UUID.randomUUID();
    private final UUID objective = UUID.randomUUID();
    private final UUID revision1 = UUID.randomUUID();
    private final UUID revision2 = UUID.randomUUID();
    private final UUID deckRevision1 = UUID.randomUUID();
    private final UUID deckRevision2 = UUID.randomUUID();
    private final UUID deckRevision3 = UUID.randomUUID();

    @Test
    void v60ChangesExactlyTheConstraintsAndIndexesItSaysAndKeepsEveryRow() throws Exception {
        String url = createDatabase("combined_publication_v58");
        flyway(url, "58").migrate();
        seed(url);
        JdbcClient jdbc = JdbcClient.create(new DriverManagerDataSource(url, username(), password()));
        TreeSet<String> before = inventory(jdbc);
        long journal = count(jdbc, "deck_item_change");
        long exerciseJournal = count(jdbc, "deck_exercise_change");
        assertThat(journal).isEqualTo(3L);
        assertThat(exerciseJournal).isEqualTo(2L);

        Flyway migrating = flyway(url, null);
        assertThat(migrating.migrate().success).isTrue();
        assertThat(migrating.info().applied()).anyMatch(info -> info.getVersion() != null && "60".equals(info.getVersion().getVersion()));
        TreeSet<String> after = inventory(jdbc);

        TreeSet<String> removed = new TreeSet<>(before);
        removed.removeAll(after);
        TreeSet<String> added = new TreeSet<>(after);
        added.removeAll(before);
        assertThat(removed).containsExactly(
                "deck_exercise_change constraint deck_exercise_change_pkey PRIMARY KEY (deck_id, deck_revision_id)",
                "deck_item_change constraint deck_item_change_change_ordinal_check CHECK (((change_ordinal >= 0) AND (change_ordinal <= 99)))",
                "deck_item_change index CREATE UNIQUE INDEX deck_item_change_revision ON app_learning.deck_item_change USING btree (deck_id, member_key, revision_id) WHERE (revision_id IS NOT NULL)",
                "exercise_revision constraint exercise_revision_deck_id_exercise_id_exercise_sequence_key UNIQUE (deck_id, exercise_id, exercise_sequence)",
                "item_revision constraint item_revision_deck_id_member_key_item_sequence_key UNIQUE (deck_id, member_key, item_sequence)",
                "objective_revision constraint objective_revision_deck_id_objective_id_objective_sequence_key UNIQUE (deck_id, objective_id, objective_sequence)");
        assertThat(added).containsExactly(
                "deck_exercise_change constraint deck_exercise_change_pkey PRIMARY KEY (deck_id, deck_revision_id, exercise_id)",
                "deck_item_change constraint deck_item_change_change_ordinal_check CHECK (((change_ordinal >= 0) AND (change_ordinal <= 32767)))",
                "deck_item_change index CREATE INDEX deck_item_change_revision ON app_learning.deck_item_change USING btree (deck_id, member_key, revision_id) WHERE (revision_id IS NOT NULL)");
        // the parent chain, the lineage keys and the kept origin key are all still there
        assertThat(after).contains(
                "item_revision constraint item_revision_deck_id_member_key_deck_revision_id_key UNIQUE (deck_id, member_key, deck_revision_id)",
                "item_revision constraint item_revision_lineage_id_key UNIQUE (reuse_scope_id, revision_id)",
                "exercise_revision constraint exercise_revision_lineage_id_key UNIQUE (reuse_scope_id, revision_id)",
                "objective_revision constraint objective_revision_lineage_id_key UNIQUE (reuse_scope_id, revision_id)");
        assertThat(jdbc.sql("SELECT count(*) FROM pg_constraint WHERE contype='f' AND conname IN ('item_revision_parent_fkey','exercise_revision_parent_fkey','objective_revision_parent_fkey')")
                .query(Long.class).single()).isEqualTo(3L);
        assertThat(after.stream().filter(line -> line.contains("CHECK") && line.contains("parent_") && line.contains("_sequence - ")).count()).isEqualTo(3L);

        // no row was touched
        assertThat(count(jdbc, "deck_item_change")).isEqualTo(journal);
        assertThat(count(jdbc, "deck_exercise_change")).isEqualTo(exerciseJournal);
        assertThat(jdbc.sql("SELECT max(change_ordinal) FROM app_learning.deck_item_change").query(Integer.class).single()).isEqualTo(99);
        // every constraint is validated, every guard is enabled
        assertThat(jdbc.sql("SELECT count(*) FROM pg_constraint WHERE connamespace='app_learning'::regnamespace AND NOT convalidated").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid WHERE NOT t.tgisinternal AND t.tgenabled<>'O' "
                + "AND c.relnamespace='app_learning'::regnamespace").query(Long.class).single()).isZero();
        // the immutability of the journals is the old one
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM app_learning.deck_exercise_change").update()).hasMessageContaining("Immutable study authoring history");
        assertThatThrownBy(() -> jdbc.sql("UPDATE app_learning.deck_item_change SET to_ordinal=to_ordinal").update()).hasMessageContaining("Immutable item change");
    }

    @Test
    void whatV60LoosenedIsLooseAndWhatItKeptStillHolds() throws Exception {
        String url = createDatabase("combined_publication_v60");
        flyway(url, null).migrate();
        JdbcClient jdbc = JdbcClient.create(new DriverManagerDataSource(url, username(), password()));
        try (Connection connection = DriverManager.getConnection(url, username(), password()); Statement statement = connection.createStatement()) {
            statement.execute("SET session_replication_role = replica");

            // many exercises in ONE deck revision; the same exercise twice in it is still refused
            exerciseChange(statement, deckRevision1, UUID.randomUUID());
            exerciseChange(statement, deckRevision1, UUID.randomUUID());
            UUID repeated = UUID.randomUUID();
            exerciseChange(statement, deckRevision2, repeated);
            assertThatThrownBy(() -> exerciseChange(statement, deckRevision2, repeated)).isInstanceOf(SQLException.class).hasMessageContaining("deck_exercise_change_pkey");

            // the journal of one combined revision may be long: ordinals up to the SMALLINT ceiling, no further
            for (int ordinal : new int[] {99, 100, 5_000, 32_767}) itemChange(statement, deckRevision1, ordinal, member, revision1);
            assertThatThrownBy(() -> itemChange(statement, deckRevision1, 32_768, UUID.randomUUID(), revision1)).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> itemChange(statement, deckRevision1, -1, UUID.randomUUID(), revision1)).isInstanceOf(SQLException.class);
            // a revision adopted again is journaled again: the same (deck, member, revision) in another deck revision (and the index is a plain index)
            itemChange(statement, deckRevision2, 0, member, revision1);
            itemChange(statement, deckRevision3, 0, member, revision1);
            assertThat(jdbc.sql("SELECT count(*) FROM app_learning.deck_item_change WHERE deck_id=:d AND member_key=:m AND revision_id=:r").param("d", deck).param("m", member)
                    .param("r", revision1).query(Long.class).single()).isEqualTo(6L);
            assertThat(jdbc.sql("SELECT indexdef FROM pg_indexes WHERE indexname='deck_item_change_revision'").query(String.class).single()).doesNotContain("UNIQUE");
            // ... but one deck revision still has one row per (deck revision, ordinal)
            assertThatThrownBy(() -> itemChange(statement, deckRevision3, 0, UUID.randomUUID(), revision1)).isInstanceOf(SQLException.class).hasMessageContaining("deck_item_change_pkey");

            // origin sequence numbers may repeat: the copy's own sequence 1 next to the author's sequence 1 (same deck here for brevity), for all three kinds of revision
            itemRevision(statement, deck, revision1, 1, deckRevision1);
            itemRevision(statement, deck, revision2, 1, deckRevision2);
            exerciseRevision(statement, UUID.randomUUID(), 1, deckRevision1);
            exerciseRevision(statement, UUID.randomUUID(), 1, deckRevision1);
            objectiveRevision(statement, UUID.randomUUID(), 1, deckRevision1);
            objectiveRevision(statement, UUID.randomUUID(), 1, deckRevision1);
            // ... while a deck revision still writes at most one revision of a member (adoption writes none), and the lineage id stays unique
            assertThatThrownBy(() -> itemRevision(statement, deck, UUID.randomUUID(), 2, deckRevision1)).isInstanceOf(SQLException.class)
                    .hasMessageContaining("item_revision_deck_id_member_key_deck_revision_id_key");
            assertThatThrownBy(() -> itemRevision(statement, otherDeck, UUID.randomUUID(), revision1, 3, deckRevision3)).isInstanceOf(SQLException.class)
                    .hasMessageContaining("item_revision_lineage_id_key");
        }

        // the stored command of a job: one shape per kind, and the units go with the command
        UUID command = UUID.randomUUID();
        jdbc.sql("INSERT INTO app_learning.combined_publication(command_id,deck_id,actor_id,expected_head_revision_id,digest,unit_count,manifest_unit_count,metadata) "
                + "VALUES (:c,:d,:a,:h,decode(repeat('ab',32),'hex'),3,2,'{\"title\":\"t\",\"description\":\"d\"}'::jsonb)").param("c", command).param("d", deck).param("a", owner)
                .param("h", deckRevision1).update();
        // the final writes are TEXT: kept byte for byte (jsonb would have sorted the keys and printed 100 for 1e2)
        jdbc.sql("UPDATE app_learning.combined_publication SET final_writes = '{\"b\":1e2,\"a\":1}' WHERE command_id=:c").param("c", command).update();
        assertThat(jdbc.sql("SELECT final_writes FROM app_learning.combined_publication WHERE command_id=:c").param("c", command).query(String.class).single())
                .isEqualTo("{\"b\":1e2,\"a\":1}");
        assertThat(jdbc.sql("SELECT string_agg(indexname,',' ORDER BY indexname) FROM pg_indexes WHERE schemaname='app_learning' AND tablename='combined_publication'")
                .query(String.class).single()).isEqualTo("combined_publication_actor,combined_publication_created,combined_publication_deck,combined_publication_pkey");
        unit(jdbc, command, 0, "ITEM_REPLACE", revision1, 3, null);
        unit(jdbc, command, 1, "ITEM_MOVE", null, 4, 2);
        unit(jdbc, command, 2, "OBJECTIVE_ADOPT", revision2, null, null);
        assertThatThrownBy(() -> unit(jdbc, command, 3, "ITEM_REPLACE", null, 3, null)).hasMessageContaining("combined_publication_unit_check");
        assertThatThrownBy(() -> unit(jdbc, command, 3, "ITEM_MOVE", null, 3, null)).hasMessageContaining("combined_publication_unit_check");
        assertThatThrownBy(() -> unit(jdbc, command, 3, "OBJECTIVE_ADOPT", revision1, 1, null)).hasMessageContaining("combined_publication_unit_check");
        assertThatThrownBy(() -> unit(jdbc, command, 3, "ITEM_REMOVE", revision1, 1, null)).hasMessageContaining("combined_publication_unit_check");
        assertThatThrownBy(() -> unit(jdbc, command, 3, "SOMETHING", null, 1, null)).hasMessageContaining("kind");
        assertThatThrownBy(() -> unit(jdbc, command, 0, "ITEM_REMOVE", null, 1, null)).hasMessageContaining("combined_publication_unit_pkey");
        assertThatThrownBy(() -> unit(jdbc, UUID.randomUUID(), 0, "ITEM_REMOVE", null, 1, null)).hasMessageContaining("combined_publication_unit_command_id_fkey");
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO app_learning.combined_publication(command_id,deck_id,actor_id,expected_head_revision_id,digest,unit_count,manifest_unit_count) "
                + "VALUES (gen_random_uuid(),:d,:a,:h,decode('abab','hex'),1,1)").param("d", deck).param("a", owner).param("h", deckRevision1).update())
                .hasMessageContaining("digest");
        assertThat(jdbc.sql("DELETE FROM app_learning.combined_publication WHERE command_id=:c").param("c", command).update()).isOne();
        assertThat(count(jdbc, "combined_publication_unit")).isZero();
    }

    // ---- helpers ----

    private static void unit(JdbcClient jdbc, UUID command, int unit, String kind, UUID revision, Integer ordinal, Integer to) {
        jdbc.sql("INSERT INTO app_learning.combined_publication_unit(command_id,unit_ordinal,kind,key_id,revision_id,ordinal,to_ordinal) VALUES (:c,:u,:k,gen_random_uuid(),:r,:o,:t)")
                .param("c", command).param("u", unit).param("k", kind).param("r", revision, java.sql.Types.OTHER).param("o", ordinal, java.sql.Types.INTEGER)
                .param("t", to, java.sql.Types.INTEGER).update();
    }

    private void seed(String url) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, username(), password()); Statement statement = connection.createStatement()) {
            statement.execute("SET session_replication_role = replica");
            for (int ordinal : new int[] {0, 5, 99}) {
                itemChange(statement, UUID.randomUUID(), ordinal, UUID.randomUUID(), UUID.randomUUID());
            }
            exerciseChange(statement, deckRevision1, UUID.randomUUID());
            exerciseChange(statement, deckRevision2, UUID.randomUUID());
        }
    }

    private void itemChange(Statement statement, UUID deckRevision, int ordinal, UUID changedMember, UUID revision) throws SQLException {
        statement.execute("INSERT INTO app_learning.deck_item_change(deck_id,reuse_scope_id,deck_revision_id,deck_sequence,change_ordinal,member_key,change_kind,"
                + "previous_revision_id,revision_id,from_ordinal,to_ordinal) VALUES ('" + deck + "','" + scope + "','" + deckRevision + "',1," + ordinal + ",'" + changedMember
                + "','create',NULL,'" + revision + "',NULL,0)");
    }

    private void exerciseChange(Statement statement, UUID deckRevision, UUID changedExercise) throws SQLException {
        statement.execute("INSERT INTO app_learning.deck_exercise_change(deck_id,reuse_scope_id,deck_revision_id,deck_sequence,exercise_id,previous_revision_id,revision_id,ordinal) "
                + "VALUES ('" + deck + "','" + scope + "','" + deckRevision + "',1,'" + changedExercise + "',NULL,'" + UUID.randomUUID() + "',0)");
    }

    private void itemRevision(Statement statement, UUID origin, UUID revision, int sequence, UUID deckRevision) throws SQLException {
        itemRevision(statement, origin, member, revision, sequence, deckRevision);
    }

    private void itemRevision(Statement statement, UUID origin, UUID ofMember, UUID revision, int sequence, UUID deckRevision) throws SQLException {
        statement.execute("INSERT INTO app_learning.item_revision(deck_id,member_key,revision_id,reuse_scope_id,owner_id,item_sequence,parent_revision_id,parent_item_sequence,"
                + "deck_revision_id,deck_sequence,command_id,format_version,content_root_id,descriptor_root_id,created_at) VALUES ('" + origin + "','" + ofMember + "','" + revision
                + "','" + scope + "','" + owner + "'," + sequence + ",'" + UUID.randomUUID() + "'," + (sequence - 1) + ",'" + deckRevision + "',1,'" + UUID.randomUUID()
                + "',1,'" + UUID.randomUUID() + "','" + UUID.randomUUID() + "',now())");
    }

    private void exerciseRevision(Statement statement, UUID exerciseId, int sequence, UUID deckRevision) throws SQLException {
        statement.execute("INSERT INTO app_learning.exercise_revision(deck_id,exercise_id,revision_id,reuse_scope_id,exercise_sequence,parent_revision_id,parent_exercise_sequence,"
                + "deck_revision_id,deck_sequence,command_id,exercise_type,schema_version,enabled,content,answer_key,evaluator_policy,descriptor_root_id,created_at) VALUES ('"
                + deck + "','" + exercise + "','" + exerciseId + "','" + scope + "'," + sequence + ",'" + UUID.randomUUID() + "'," + (sequence - 1) + ",'" + deckRevision
                + "',1,'" + UUID.randomUUID() + "','SELF_CHECK',2,true,'{}','{\"kind\":\"SELF_REPORT\"}','{}','" + UUID.randomUUID() + "',now())");
    }

    private void objectiveRevision(Statement statement, UUID revision, int sequence, UUID deckRevision) throws SQLException {
        statement.execute("INSERT INTO app_learning.objective_revision(deck_id,reuse_scope_id,objective_id,revision_id,objective_sequence,parent_revision_id,parent_objective_sequence,"
                + "deck_revision_id,deck_sequence,command_id,descriptor,created_at) VALUES ('" + deck + "','" + scope + "','" + objective + "','" + revision + "'," + sequence
                + ",'" + UUID.randomUUID() + "'," + (sequence - 1) + ",'" + deckRevision + "',1,'" + UUID.randomUUID() + "','{\"schemaVersion\":1,\"title\":\"t\"}',now())");
    }

    /** The key constraints and the plain indexes of the five tables, one line each (a constraint's own index is not listed twice). */
    private static TreeSet<String> inventory(JdbcClient jdbc) {
        return new TreeSet<>(jdbc.sql("SELECT replace(conrelid::regclass::text,'app_learning.','') || ' constraint ' || conname || ' ' || replace(pg_get_constraintdef(oid),'app_learning.','') "
                + "FROM pg_constraint WHERE conrelid IN (" + TABLES + ") AND contype IN ('p','u','c') "
                + "UNION ALL SELECT replace(i.indrelid::regclass::text,'app_learning.','') || ' index ' || pg_get_indexdef(i.indexrelid) FROM pg_index i "
                + "WHERE i.indrelid IN (" + TABLES + ") AND NOT EXISTS (SELECT 1 FROM pg_constraint c WHERE c.conindid = i.indexrelid)").query(String.class).list());
    }

    private static long count(JdbcClient jdbc, String table) {
        return jdbc.sql("SELECT count(*) FROM app_learning." + table).query(Long.class).single();
    }

    private static Flyway flyway(String url, String target) {
        var configuration = Flyway.configure().dataSource(url, username(), password()).locations("classpath:db/learning/migration").schemas("app_learning")
                .defaultSchema("app_learning").createSchemas(true).cleanDisabled(true);
        if (target != null) configuration.target(target);
        return configuration.load();
    }
}
