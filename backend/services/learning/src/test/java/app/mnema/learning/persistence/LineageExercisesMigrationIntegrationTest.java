package app.mnema.learning.persistence;

import app.mnema.learning.support.PostgresIntegrationTest;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V52 (Share/5, #427) on data written at the V51 shape ({@code db/v51-exercises-and-study-seed.sql}: two decks in two lineages,
 * exercises of every mechanic, an exercise with two revisions and a media reference, objectives with history, Study sessions on
 * READY and PREPARING candidate generations, presentations, an assessed attempt with evidence and a transition, policy
 * assignments) plus thousands of exercise heads, so that the backfill fills pages and updates are not all in-page. Every row
 * of a per-deck table gets its deck's scope, the progress rows are byte-identical, an in-flight candidate generation starts
 * over, all constraints are validated and every immutability guard is enabled again. The inventory test fixes the exact key
 * constraints of the exercise and Study tables, so the next change has to change it consciously.
 */
class LineageExercisesMigrationIntegrationTest extends PostgresIntegrationTest {
    private static final List<String> PER_DECK = List.of("objective_head", "deck_head_exercise", "deck_exercise_change",
            "exercise_new_mark", "study_candidate_generation", "study_candidate", "study_policy_assignment");
    private static final List<String> LINEAGE = List.of("objective_revision", "exercise_content_binding", "exercise_media_ref");
    /** Enough extra heads to fill many heap pages (the backfill then cannot be a series of HOT updates). */
    private static final int BULK = 3_000;

    @Test
    void dataWrittenAtTheV51ShapeIsBackfilledByDeckAndEveryGuardIsEnabledAgain() throws Exception {
        String url = createDatabase("lineage_exercises_v51");
        flyway(url, "51").migrate();
        seedV51(url, true);
        JdbcClient jdbc = JdbcClient.create(new DriverManagerDataSource(url, username(), password()));
        String progressBefore = progress(jdbc);
        long headsBefore = count(jdbc, "deck_head_exercise");
        long candidatesBefore = count(jdbc, "study_candidate");
        assertThat(headsBefore).isEqualTo(9 + BULK);

        Flyway migrating = flyway(url, "52");
        assertThat(migrating.migrate().success).isTrue();
        assertThat(migrating.info().current().getVersion().getVersion()).isEqualTo("52");
        assertThat(migrating.info().current().getState().isApplied()).isTrue();

        // each per-deck and lineage row carries the scope of ITS deck; nothing was dropped or duplicated
        for (String table : concat(PER_DECK, LINEAGE)) {
            assertThat(jdbc.sql("SELECT count(*) FROM app_learning." + table + " t JOIN app_learning.deck d ON d.deck_id=t.deck_id "
                    + "WHERE t.reuse_scope_id=d.reuse_scope_id").query(Long.class).single())
                    .as(table).isEqualTo(count(jdbc, table));
            assertThat(count(jdbc, table)).as(table).isPositive();
        }
        assertThat(count(jdbc, "deck_head_exercise")).isEqualTo(headsBefore);
        assertThat(scopeOf(jdbc, "objective_head", "objective_id", id("ob2"))).isEqualTo(id("s2"));
        assertThat(scopeOf(jdbc, "exercise_new_mark", "exercise_id", id("e3"))).isEqualTo(id("s1"));
        // progress is byte-identical: state, transitions, evidence, sessions, presentations and the policy assignments
        assertThat(progress(jdbc)).isEqualTo(progressBefore);

        // the media reference keeps the asset owner and the origin deck
        assertThat(columns(jdbc, "exercise_media_ref")).contains("asset_owner_id", "reuse_scope_id", "deck_id").doesNotContain("owner_id");
        var reference = jdbc.sql("SELECT reuse_scope_id,asset_owner_id,deck_id FROM app_learning.exercise_media_ref")
                .query((row, ignored) -> List.of(row.getObject(1, UUID.class), row.getObject(2, UUID.class), row.getObject(3, UUID.class)))
                .single();
        assertThat(reference).containsExactly(id("s1"), id("o1"), id("d1"));

        // reads from the deck's heads through the lineage reach every revision the deck-keyed joins reached
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.deck_head_exercise h JOIN app_learning.exercise_revision r "
                + "ON r.reuse_scope_id=h.reuse_scope_id AND r.exercise_id=h.exercise_id AND r.revision_id=h.revision_id "
                + "JOIN app_learning.exercise_content_binding b ON b.reuse_scope_id=r.reuse_scope_id AND b.exercise_id=r.exercise_id "
                + "AND b.exercise_revision_id=r.revision_id AND b.role='ASSESSED'").query(Long.class).single()).isEqualTo(headsBefore);

        // the candidate cache: READY generations are untouched, the PREPARING one starts over from ordinal 0
        assertThat(jdbc.sql("SELECT scanned_count||'/'||candidate_count||'/'||row_version||'/'||status FROM app_learning.study_candidate_generation "
                + "WHERE generation_id=:g").param("g", id("g1")).query(String.class).single()).isEqualTo("7/7/3/READY");
        assertThat(jdbc.sql("SELECT scanned_count||'/'||candidate_count||'/'||row_version||'/'||status FROM app_learning.study_candidate_generation "
                + "WHERE generation_id=:g").param("g", id("g1p")).query(String.class).single()).isEqualTo("0/0/2/PREPARING");
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.study_candidate WHERE generation_id=:g").param("g", id("g1p"))
                .query(Long.class).single()).isZero();
        assertThat(count(jdbc, "study_candidate")).isEqualTo(candidatesBefore - 3);
        assertThat(columns(jdbc, "study_candidate_generation")).doesNotContain("source_cursor");
        // presentations and evidence got no scope column and no backfill
        assertThat(columns(jdbc, "study_presentation")).doesNotContain("reuse_scope_id");
        assertThat(columns(jdbc, "study_evidence")).doesNotContain("reuse_scope_id");

        // no leftovers of the deck-keyed design, every new column NOT NULL, every constraint validated
        assertThat(jdbc.sql("SELECT count(*) FROM information_schema.columns WHERE table_schema='app_learning' "
                + "AND column_name='reuse_scope_id' AND is_nullable='YES' AND table_name IN ('objective_revision',"
                + "'exercise_content_binding','exercise_media_ref','objective_head','deck_head_exercise','deck_exercise_change',"
                + "'exercise_new_mark','study_candidate_generation','study_candidate','study_policy_assignment')")
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM pg_constraint WHERE connamespace='app_learning'::regnamespace AND NOT convalidated")
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM pg_indexes WHERE schemaname='app_learning' AND indexname IN "
                + "('exercise_revision_snapshot_seek','item_revision_origin_key')").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM pg_constraint WHERE conname='item_revision_origin_key'").query(Long.class).single()).isZero();

        // all guards are enabled ('O' = fires in the origin role) and really refuse updates
        assertThat(jdbc.sql("""
                SELECT count(*) FROM pg_trigger t WHERE NOT t.tgisinternal AND t.tgenabled='O' AND t.tgname IN
                    ('memory_objective_immutable','objective_revision_immutable','exercise_definition_immutable',
                     'exercise_revision_immutable','exercise_revision_descriptor_guard','exercise_binding_immutable',
                     'exercise_binding_objective_guard','exercise_media_ref_guard','deck_exercise_change_immutable',
                     'study_candidate_immutable','study_policy_assignment_immutable','study_evidence_immutable',
                     'study_evidence_presentation_guard')
                """).query(Long.class).single()).isEqualTo(13L);
        assertThat(jdbc.sql("SELECT count(*) FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid "
                + "WHERE NOT t.tgisinternal AND t.tgenabled<>'O' AND c.relnamespace='app_learning'::regnamespace")
                .query(Long.class).single()).isZero();
        assertThatThrownBy(() -> jdbc.sql("UPDATE app_learning.objective_revision SET reuse_scope_id=reuse_scope_id").update())
                .hasMessageContaining("Immutable study authoring history");
        assertThatThrownBy(() -> jdbc.sql("UPDATE app_learning.exercise_content_binding SET reuse_scope_id=reuse_scope_id").update())
                .hasMessageContaining("Immutable study authoring history");
        assertThatThrownBy(() -> jdbc.sql("UPDATE app_learning.exercise_media_ref SET reuse_scope_id=reuse_scope_id").update())
                .hasMessageContaining("Immutable published exercise media reference");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM app_learning.deck_exercise_change").update())
                .hasMessageContaining("Immutable study authoring history");
        assertThatThrownBy(() -> jdbc.sql("UPDATE app_learning.study_candidate SET reuse_scope_id=reuse_scope_id").update())
                .hasMessageContaining("Immutable study snapshot");
        assertThatThrownBy(() -> jdbc.sql("UPDATE app_learning.study_policy_assignment SET reuse_scope_id=reuse_scope_id").update())
                .hasMessageContaining("Immutable study snapshot");

        // a per-deck row can never carry a scope that is not its deck's
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO app_learning.exercise_new_mark(deck_id,reuse_scope_id,exercise_id,owner_id,marked_at) "
                + "VALUES (:deck,:scope,:exercise,:owner,now())").param("deck", id("d1")).param("scope", id("s2"))
                .param("exercise", id("e2")).param("owner", id("o1")).update())
                .hasMessageContaining("exercise_new_mark_deck_scope_fkey");
        // a presentation must copy exactly the ids of its candidate: a revision of another lineage cannot be smuggled in
        assertThatThrownBy(() -> insertPresentation(jdbc, id("p9"), id("ss1p"), 0, id("g1"), 2, id("e3"), id("er3"), id("ob1a"),
                id("obr1b0"))).hasMessageContaining("study_presentation_candidate_fkey");
        assertThatThrownBy(() -> insertPresentation(jdbc, id("p9"), id("ss1p"), 0, id("g1"), 2, id("e3"), id("er4"), id("ob1a"),
                id("obr1a1"))).hasMessageContaining("study_presentation_candidate_fkey");
        insertPresentation(jdbc, id("p9"), id("ss1p"), 0, id("g1"), 2, id("e3"), id("er3"), id("ob1a"), id("obr1a1"));
        // evidence must state the objective revision of its presentation (insert guard instead of a column and a backfill)
        assertThatThrownBy(() -> insertEvidence(jdbc, id("a2"), id("obr1a0"))).hasMessageContaining("Evidence must belong to a scheduled, assessed attempt");
        jdbc.sql("INSERT INTO app_learning.study_attempt_tombstone(attempt_id,account_id,session_id,presentation_id,deck_id,payload_hash,mode,"
                + "status,outcome,receipt_expires_at,submitted_at) VALUES (:attempt,:o,:session,:presentation,:deck,decode(repeat('cd',32),'hex'),"
                + "'SCHEDULED','ASSESSED','{}'::jsonb,NULL,now())").param("attempt", id("a2")).param("o", id("o1"))
                .param("session", id("ss1p")).param("presentation", id("p9")).param("deck", id("d1")).update();
        assertThatThrownBy(() -> insertEvidence(jdbc, id("a2"), id("obr1a0"))).hasMessageContaining("Evidence must belong to a scheduled, assessed attempt");
        insertEvidence(jdbc, id("a2"), id("obr1a1"));
    }

    @Test
    void anEvidenceRowThatDoesNotMatchItsPresentationFailsTheMigrationClosed() throws Exception {
        String url = createDatabase("lineage_exercises_bad_evidence");
        flyway(url, "51").migrate();
        seedV51(url, false);
        try (var connection = java.sql.DriverManager.getConnection(url, username(), password());
             var statement = connection.createStatement()) {
            statement.execute("SET session_replication_role = replica");   // the immutability guard is not what this test is about
            statement.execute("UPDATE app_learning.study_evidence SET objective_revision_id='" + id("obr1a0") + "'");
        }
        assertThatThrownBy(() -> flyway(url, "52").migrate()).isInstanceOf(FlywayException.class)
                .hasMessageContaining("does not match its presentation");
        // one transaction: nothing was applied, the V51 shape is intact
        JdbcClient jdbc = JdbcClient.create(new DriverManagerDataSource(url, username(), password()));
        assertThat(columns(jdbc, "deck_head_exercise")).doesNotContain("reuse_scope_id");
        assertThat(columns(jdbc, "study_candidate_generation")).contains("source_cursor");
        assertThat(count(jdbc, "study_candidate")).isEqualTo(12L);
    }

    @Test
    void theGuardsRefuseWhatForeignKeysCannotSay() throws Exception {
        String url = createDatabase("lineage_exercises_guards");
        flyway(url, "51").migrate();
        seedV51(url, false);
        assertThat(flyway(url, "52").migrate().success).isTrue();
        JdbcClient jdbc = JdbcClient.create(new DriverManagerDataSource(url, username(), password()));

        // exercise_binding_objective_guard: the ASSESSED objective belongs to the assessed material of the SAME lineage
        String binding = "INSERT INTO app_learning.exercise_content_binding(deck_id,reuse_scope_id,exercise_id,exercise_revision_id,binding_id,"
                + "binding_ordinal,role,member_key,item_revision_id,objective_id,objective_revision_id,node_ids) "
                + "VALUES (:deck,:scope,:exercise,:revision,gen_random_uuid(),9,'ASSESSED',:member,:item,:objective,:objectiveRevision,'{}')";
        // wrong member: the objective of material m1a on a binding of material m1b
        assertThatThrownBy(() -> jdbc.sql(binding).param("deck", id("d1")).param("scope", id("s1")).param("exercise", id("e1"))
                .param("revision", id("er1a")).param("member", id("m1b")).param("item", id("ir1b0")).param("objective", id("ob1a"))
                .param("objectiveRevision", id("obr1a0")).update()).hasMessageContaining("Assessed objective must belong to the assessed material");
        // cross-scope: an objective of lineage s2 on a binding of lineage s1 (same member key is not enough)
        assertThatThrownBy(() -> jdbc.sql(binding).param("deck", id("d1")).param("scope", id("s1")).param("exercise", id("e1"))
                .param("revision", id("er1a")).param("member", id("m2")).param("item", id("ir2")).param("objective", id("ob2"))
                .param("objectiveRevision", id("obr2")).update()).hasMessageContaining("Assessed objective must belong to the assessed material");
        // an unknown objective too
        assertThatThrownBy(() -> jdbc.sql(binding).param("deck", id("d1")).param("scope", id("s1")).param("exercise", id("e1"))
                .param("revision", id("er1a")).param("member", id("m1a")).param("item", id("ir1a0")).param("objective", UUID.randomUUID())
                .param("objectiveRevision", UUID.randomUUID()).update()).hasMessageContaining("Assessed objective must belong to the assessed material");

        // study_evidence_presentation_guard: a scheduled, assessed attempt of the same account, deck and objective revision, and the
        // objective revision the presented exercise revision's ASSESSED binding names
        UUID good = attempt(jdbc, "good", "SCHEDULED", "ASSESSED", id("g1"), 0, id("e1"), id("er1b"), id("ob1a"), id("obr1a1"));
        String refused = "Evidence must belong to a scheduled, assessed attempt";
        assertThatThrownBy(() -> insertEvidence(jdbc, good, id("o2"), id("d1"), id("ob1a"), id("obr1a1"))).hasMessageContaining(refused);   // account
        assertThatThrownBy(() -> insertEvidence(jdbc, good, id("o1"), id("d2"), id("ob1a"), id("obr1a1"))).hasMessageContaining(refused);   // deck
        assertThatThrownBy(() -> insertEvidence(jdbc, good, id("o1"), id("d1"), id("ob1b"), id("obr1a1"))).hasMessageContaining(refused);   // objective
        assertThatThrownBy(() -> insertEvidence(jdbc, good, id("o1"), id("d1"), id("ob1a"), id("obr1a0"))).hasMessageContaining(refused);   // revision
        assertThatThrownBy(() -> insertEvidence(jdbc, UUID.randomUUID(), id("o1"), id("d1"), id("ob1a"), id("obr1a1")))
                .hasMessageContaining(refused);   // no attempt at all
        UUID practice = attempt(jdbc, "practice", "PRACTICE", "ASSESSED", id("g1"), 0, id("e1"), id("er1b"), id("ob1a"), id("obr1a1"));
        assertThatThrownBy(() -> insertEvidence(jdbc, practice, id("obr1a1"))).hasMessageContaining(refused);   // only SCHEDULED writes evidence
        UUID notAssessed = attempt(jdbc, "unassessed", "SCHEDULED", "NOT_ASSESSED", id("g1"), 0, id("e1"), id("er1b"), id("ob1a"), id("obr1a1"));
        assertThatThrownBy(() -> insertEvidence(jdbc, notAssessed, id("obr1a1"))).hasMessageContaining(refused);   // only an ASSESSED attempt
        // the candidate names an objective revision the exercise revision's ASSESSED binding does not (a stale candidate)
        jdbc.sql("INSERT INTO app_learning.study_candidate_generation(deck_id,reuse_scope_id,generation_id,owner_id,deck_revision_id,deck_sequence,"
                + "exercises_root_id,status,scanned_count,candidate_count,expected_exercise_count,row_version,created_at,ready_at) "
                + "VALUES (:deck,:scope,:generation,:o,:revision,9,:root,'PREPARING',1,1,7,1,now(),NULL)")
                .param("deck", id("d1")).param("scope", id("s1")).param("generation", id("g3")).param("o", id("o1"))
                .param("revision", id("d1r9")).param("root", id("root3")).update();
        jdbc.sql("INSERT INTO app_learning.study_candidate(generation_id,candidate_ordinal,deck_id,reuse_scope_id,exercise_id,exercise_revision_id,"
                + "objective_id,objective_revision_id,member_key) VALUES (:generation,0,:deck,:scope,:e,:er,:ob,:obr,:member)")
                .param("generation", id("g3")).param("deck", id("d1")).param("scope", id("s1")).param("e", id("e3")).param("er", id("er3"))
                .param("ob", id("ob1a")).param("obr", id("obr1a0")).param("member", id("m1a")).update();
        UUID stale = attempt(jdbc, "stale", "SCHEDULED", "ASSESSED", id("g3"), 0, id("e3"), id("er3"), id("ob1a"), id("obr1a0"));
        assertThatThrownBy(() -> insertEvidence(jdbc, stale, id("o1"), id("d1"), id("ob1a"), id("obr1a0"))).hasMessageContaining(refused);
        insertEvidence(jdbc, good, id("o1"), id("d1"), id("ob1a"), id("obr1a1"));

        // exercise_media_ready: the revision that declares an asset no reference pins is not ready after V52
        assertThat(jdbc.sql("SELECT app_learning.exercise_media_ready(:scope,:exercise,:revision)").param("scope", id("s2"))
                .param("exercise", id("f3")).param("revision", id("fr3")).query(Boolean.class).single()).isFalse();
        assertThat(jdbc.sql("SELECT app_learning.exercise_media_ready(:scope,:exercise,:revision)").param("scope", id("s2"))
                .param("exercise", id("f1")).param("revision", id("fr1")).query(Boolean.class).single()).isTrue();
    }

    @Test
    void theKeyConstraintsOfTheExerciseAndStudyTablesAreExactlyTheLineageSet() {
        String url = createDatabase("lineage_exercises_inventory");
        flyway(url, null).migrate();
        JdbcClient jdbc = JdbcClient.create(new DriverManagerDataSource(url, username(), password()));
        List<String> actual = jdbc.sql("""
                SELECT replace(conrelid::regclass::text,'app_learning.','') || ' ' || conname || ' '
                       || replace(pg_get_constraintdef(oid),'app_learning.','')
                  FROM pg_constraint
                 WHERE contype IN ('p','u','f')
                   AND (conrelid IN ('app_learning.memory_objective'::regclass,'app_learning.objective_revision'::regclass,
                        'app_learning.objective_head'::regclass,'app_learning.exercise_definition'::regclass,
                        'app_learning.exercise_revision'::regclass,'app_learning.exercise_content_binding'::regclass,
                        'app_learning.exercise_media_ref'::regclass,'app_learning.deck_head_exercise'::regclass,
                        'app_learning.deck_exercise_change'::regclass,'app_learning.exercise_new_mark'::regclass,
                        'app_learning.study_candidate_generation'::regclass,'app_learning.study_candidate'::regclass,
                        'app_learning.study_presentation'::regclass,'app_learning.study_evidence'::regclass,
                        'app_learning.study_policy_assignment'::regclass)
                        OR confrelid IN ('app_learning.memory_objective'::regclass,'app_learning.objective_revision'::regclass,
                        'app_learning.exercise_definition'::regclass,'app_learning.exercise_revision'::regclass,
                        'app_learning.study_candidate'::regclass))
                """).query(String.class).list().stream().sorted().toList();
        assertThat(actual).containsExactlyElementsOf(INVENTORY.lines().sorted().toList());
        // the lineage rows reference nothing of the reading decks: every key that names a deck column names the origin
        assertThat(actual.stream().filter(line -> line.contains("REFERENCES exercise_revision(deck_id")
                || line.contains("REFERENCES objective_revision(deck_id") || line.contains("REFERENCES memory_objective(deck_id")
                || line.contains("REFERENCES exercise_definition(deck_id"))).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM pg_indexes WHERE schemaname='app_learning' AND indexname IN "
                + "('exercise_binding_assessed_member','exercise_binding_item_revision','one_assessed_binding_per_exercise_revision',"
                + "'deck_exercise_change_revision','deck_exercise_change_previous_revision')").query(Long.class).single()).isEqualTo(5L);
    }

    private static final String INVENTORY = """
            deck_exercise_change deck_exercise_change_deck_id_deck_revision_id_deck_sequenc_fkey FOREIGN KEY (deck_id, deck_revision_id, deck_sequence) REFERENCES deck_revision(deck_id, revision_id, sequence) DEFERRABLE INITIALLY DEFERRED
            deck_exercise_change deck_exercise_change_deck_scope_fkey FOREIGN KEY (deck_id, reuse_scope_id) REFERENCES deck(deck_id, reuse_scope_id)
            deck_exercise_change deck_exercise_change_pkey PRIMARY KEY (deck_id, deck_revision_id)
            deck_exercise_change deck_exercise_change_previous_fkey FOREIGN KEY (reuse_scope_id, exercise_id, previous_revision_id) REFERENCES exercise_revision(reuse_scope_id, exercise_id, revision_id) DEFERRABLE INITIALLY DEFERRED
            deck_exercise_change deck_exercise_change_revision_fkey FOREIGN KEY (reuse_scope_id, exercise_id, revision_id) REFERENCES exercise_revision(reuse_scope_id, exercise_id, revision_id) DEFERRABLE INITIALLY DEFERRED
            deck_head_exercise deck_head_exercise_deck_id_ordinal_key UNIQUE (deck_id, ordinal) DEFERRABLE INITIALLY DEFERRED
            deck_head_exercise deck_head_exercise_deck_scope_fkey FOREIGN KEY (deck_id, reuse_scope_id) REFERENCES deck(deck_id, reuse_scope_id)
            deck_head_exercise deck_head_exercise_pkey PRIMARY KEY (deck_id, exercise_id)
            deck_head_exercise deck_head_exercise_revision_fkey FOREIGN KEY (reuse_scope_id, exercise_id, revision_id, exercise_sequence) REFERENCES exercise_revision(reuse_scope_id, exercise_id, revision_id, exercise_sequence) DEFERRABLE INITIALLY DEFERRED
            exercise_content_binding exercise_content_binding_item_revision_fkey FOREIGN KEY (reuse_scope_id, member_key, item_revision_id) REFERENCES item_revision(reuse_scope_id, member_key, revision_id)
            exercise_content_binding exercise_content_binding_lineage_ordinal_key UNIQUE (reuse_scope_id, exercise_id, exercise_revision_id, binding_ordinal)
            exercise_content_binding exercise_content_binding_objective_fkey FOREIGN KEY (reuse_scope_id, objective_id, objective_revision_id) REFERENCES objective_revision(reuse_scope_id, objective_id, revision_id)
            exercise_content_binding exercise_content_binding_pkey PRIMARY KEY (reuse_scope_id, exercise_id, exercise_revision_id, binding_id)
            exercise_content_binding exercise_content_binding_revision_fkey FOREIGN KEY (reuse_scope_id, exercise_id, exercise_revision_id) REFERENCES exercise_revision(reuse_scope_id, exercise_id, revision_id)
            exercise_definition exercise_definition_deck_id_reuse_scope_id_owner_id_fkey FOREIGN KEY (deck_id, reuse_scope_id, owner_id) REFERENCES deck(deck_id, reuse_scope_id, owner_id)
            exercise_definition exercise_definition_lineage_key UNIQUE (reuse_scope_id, exercise_id)
            exercise_definition exercise_definition_pkey PRIMARY KEY (deck_id, exercise_id)
            exercise_media_ref exercise_media_ref_asset_id_owner_id_fkey FOREIGN KEY (asset_id, asset_owner_id) REFERENCES media_asset(asset_id, owner_id)
            exercise_media_ref exercise_media_ref_pkey PRIMARY KEY (reuse_scope_id, exercise_id, exercise_revision_id, asset_id)
            exercise_media_ref exercise_media_ref_revision_fkey FOREIGN KEY (reuse_scope_id, exercise_id, exercise_revision_id) REFERENCES exercise_revision(reuse_scope_id, exercise_id, revision_id)
            exercise_new_mark exercise_new_mark_deck_id_owner_id_fkey FOREIGN KEY (deck_id, owner_id) REFERENCES deck(deck_id, owner_id)
            exercise_new_mark exercise_new_mark_deck_scope_fkey FOREIGN KEY (deck_id, reuse_scope_id) REFERENCES deck(deck_id, reuse_scope_id)
            exercise_new_mark exercise_new_mark_exercise_fkey FOREIGN KEY (reuse_scope_id, exercise_id) REFERENCES exercise_definition(reuse_scope_id, exercise_id)
            exercise_new_mark exercise_new_mark_pkey PRIMARY KEY (deck_id, exercise_id)
            exercise_revision exercise_revision_deck_id_deck_revision_id_deck_sequence_fkey FOREIGN KEY (deck_id, deck_revision_id, deck_sequence) REFERENCES deck_revision(deck_id, revision_id, sequence) DEFERRABLE INITIALLY DEFERRED
            exercise_revision exercise_revision_deck_id_exercise_id_exercise_sequence_key UNIQUE (deck_id, exercise_id, exercise_sequence)
            exercise_revision exercise_revision_exercise_fkey FOREIGN KEY (reuse_scope_id, exercise_id) REFERENCES exercise_definition(reuse_scope_id, exercise_id)
            exercise_revision exercise_revision_lineage_id_key UNIQUE (reuse_scope_id, revision_id)
            exercise_revision exercise_revision_lineage_sequence_key UNIQUE (reuse_scope_id, exercise_id, revision_id, exercise_sequence)
            exercise_revision exercise_revision_origin_deck_fkey FOREIGN KEY (deck_id, reuse_scope_id) REFERENCES deck(deck_id, reuse_scope_id)
            exercise_revision exercise_revision_parent_fkey FOREIGN KEY (reuse_scope_id, exercise_id, parent_revision_id, parent_exercise_sequence) REFERENCES exercise_revision(reuse_scope_id, exercise_id, revision_id, exercise_sequence)
            exercise_revision exercise_revision_pkey PRIMARY KEY (reuse_scope_id, exercise_id, revision_id)
            exercise_revision exercise_revision_reuse_scope_id_descriptor_root_id_fkey FOREIGN KEY (reuse_scope_id, descriptor_root_id) REFERENCES storage_object(reuse_scope_id, object_id)
            memory_objective memory_objective_deck_id_reuse_scope_id_owner_id_fkey FOREIGN KEY (deck_id, reuse_scope_id, owner_id) REFERENCES deck(deck_id, reuse_scope_id, owner_id)
            memory_objective memory_objective_item_fkey FOREIGN KEY (reuse_scope_id, member_key) REFERENCES learning_item(reuse_scope_id, member_key)
            memory_objective memory_objective_lineage_key UNIQUE (reuse_scope_id, objective_id)
            memory_objective memory_objective_lineage_member_key UNIQUE (reuse_scope_id, member_key, objective_id)
            memory_objective memory_objective_lineage_objective_key UNIQUE (reuse_scope_id, objective_key)
            memory_objective memory_objective_pkey PRIMARY KEY (deck_id, objective_id)
            objective_head objective_head_deck_scope_fkey FOREIGN KEY (deck_id, reuse_scope_id) REFERENCES deck(deck_id, reuse_scope_id)
            objective_head objective_head_pkey PRIMARY KEY (deck_id, objective_id)
            objective_head objective_head_revision_fkey FOREIGN KEY (reuse_scope_id, objective_id, revision_id, objective_sequence) REFERENCES objective_revision(reuse_scope_id, objective_id, revision_id, objective_sequence) DEFERRABLE INITIALLY DEFERRED
            objective_revision objective_revision_deck_id_deck_revision_id_deck_sequence_fkey FOREIGN KEY (deck_id, deck_revision_id, deck_sequence) REFERENCES deck_revision(deck_id, revision_id, sequence) DEFERRABLE INITIALLY DEFERRED
            objective_revision objective_revision_deck_id_objective_id_objective_sequence_key UNIQUE (deck_id, objective_id, objective_sequence)
            objective_revision objective_revision_lineage_id_key UNIQUE (reuse_scope_id, revision_id)
            objective_revision objective_revision_lineage_sequence_key UNIQUE (reuse_scope_id, objective_id, revision_id, objective_sequence)
            objective_revision objective_revision_objective_fkey FOREIGN KEY (reuse_scope_id, objective_id) REFERENCES memory_objective(reuse_scope_id, objective_id)
            objective_revision objective_revision_origin_deck_fkey FOREIGN KEY (deck_id, reuse_scope_id) REFERENCES deck(deck_id, reuse_scope_id)
            objective_revision objective_revision_parent_fkey FOREIGN KEY (reuse_scope_id, objective_id, parent_revision_id, parent_objective_sequence) REFERENCES objective_revision(reuse_scope_id, objective_id, revision_id, objective_sequence)
            objective_revision objective_revision_pkey PRIMARY KEY (reuse_scope_id, objective_id, revision_id)
            study_candidate study_candidate_deck_id_generation_id_fkey FOREIGN KEY (deck_id, generation_id) REFERENCES study_candidate_generation(deck_id, generation_id)
            study_candidate study_candidate_deck_scope_fkey FOREIGN KEY (deck_id, reuse_scope_id) REFERENCES deck(deck_id, reuse_scope_id)
            study_candidate study_candidate_exercise_fkey FOREIGN KEY (reuse_scope_id, exercise_id, exercise_revision_id) REFERENCES exercise_revision(reuse_scope_id, exercise_id, revision_id)
            study_candidate study_candidate_generation_id_exercise_revision_id_key UNIQUE (generation_id, exercise_revision_id)
            study_candidate study_candidate_objective_member_fkey FOREIGN KEY (reuse_scope_id, member_key, objective_id) REFERENCES memory_objective(reuse_scope_id, member_key, objective_id)
            study_candidate study_candidate_objective_revision_fkey FOREIGN KEY (reuse_scope_id, objective_id, objective_revision_id) REFERENCES objective_revision(reuse_scope_id, objective_id, revision_id)
            study_candidate study_candidate_pkey PRIMARY KEY (generation_id, candidate_ordinal)
            study_candidate study_candidate_presentation_key UNIQUE (deck_id, generation_id, candidate_ordinal, exercise_id, exercise_revision_id, objective_id, objective_revision_id)
            study_candidate_generation study_candidate_generation_deck_id_deck_revision_id_deck_s_fkey FOREIGN KEY (deck_id, deck_revision_id, deck_sequence) REFERENCES deck_revision(deck_id, revision_id, sequence)
            study_candidate_generation study_candidate_generation_deck_id_exercises_root_id_key UNIQUE (deck_id, exercises_root_id)
            study_candidate_generation study_candidate_generation_deck_scope_fkey FOREIGN KEY (deck_id, reuse_scope_id) REFERENCES deck(deck_id, reuse_scope_id)
            study_candidate_generation study_candidate_generation_generation_id_key UNIQUE (generation_id)
            study_candidate_generation study_candidate_generation_pkey PRIMARY KEY (deck_id, generation_id)
            study_evidence study_evidence_attempt_id_fkey FOREIGN KEY (attempt_id) REFERENCES study_attempt_tombstone(attempt_id)
            study_evidence study_evidence_pkey PRIMARY KEY (attempt_id)
            study_policy_assignment study_policy_assignment_account_id_deck_id_objective_id_red_key UNIQUE (account_id, deck_id, objective_id, reducer_config_id)
            study_policy_assignment study_policy_assignment_deck_scope_fkey FOREIGN KEY (deck_id, reuse_scope_id) REFERENCES deck(deck_id, reuse_scope_id)
            study_policy_assignment study_policy_assignment_objective_fkey FOREIGN KEY (reuse_scope_id, objective_id) REFERENCES memory_objective(reuse_scope_id, objective_id)
            study_policy_assignment study_policy_assignment_pkey PRIMARY KEY (account_id, deck_id, objective_id)
            study_policy_assignment study_policy_assignment_reducer_config_id_fkey FOREIGN KEY (reducer_config_id) REFERENCES scheduler_config(config_id)
            study_presentation study_presentation_account_id_session_id_fkey FOREIGN KEY (account_id, session_id) REFERENCES study_session(account_id, session_id)
            study_presentation study_presentation_account_id_session_id_objective_id_key UNIQUE (account_id, session_id, objective_id)
            study_presentation study_presentation_account_id_session_id_presentation_ordin_key UNIQUE (account_id, session_id, presentation_ordinal)
            study_presentation study_presentation_candidate_fkey FOREIGN KEY (deck_id, generation_id, candidate_ordinal, exercise_id, exercise_revision_id, objective_id, objective_revision_id) REFERENCES study_candidate(deck_id, generation_id, candidate_ordinal, exercise_id, exercise_revision_id, objective_id, objective_revision_id)
            study_presentation study_presentation_pkey PRIMARY KEY (account_id, session_id, presentation_id)
            study_presentation study_presentation_presentation_id_key UNIQUE (presentation_id)
            """;

    // --------------------------------------------------------------------------------------------------------- helpers

    private static void insertPresentation(JdbcClient jdbc, UUID presentation, UUID session, int ordinal, UUID generation,
                                           int candidateOrdinal, UUID exercise, UUID exerciseRevision, UUID objective,
                                           UUID objectiveRevision) {
        jdbc.sql("INSERT INTO app_learning.study_presentation(account_id,session_id,presentation_id,presentation_ordinal,nonce,deck_id,"
                + "generation_id,candidate_ordinal,exercise_id,exercise_revision_id,exercise_type,objective_id,objective_revision_id,"
                + "learning_epoch,content,evaluator,answer_key,issued_at,expires_at,reveal) VALUES (:o,:session,:presentation,:ordinal,"
                + "'nonce-nonce-nonce-9',:deck,:generation,:candidate,:exercise,:exerciseRevision,'CLOZE',:objective,:objectiveRevision,0,"
                + "'{}','{}','{}',now(),now()+interval '1 day','{}')")
                .param("o", id("o1")).param("session", session).param("presentation", presentation).param("ordinal", ordinal)
                .param("deck", id("d1")).param("generation", generation).param("candidate", candidateOrdinal)
                .param("exercise", exercise).param("exerciseRevision", exerciseRevision).param("objective", objective)
                .param("objectiveRevision", objectiveRevision).update();
    }

    private static void insertEvidence(JdbcClient jdbc, UUID attempt, UUID objectiveRevision) {
        insertEvidence(jdbc, attempt, id("o1"), id("d1"), id("ob1a"), objectiveRevision);
    }

    private static void insertEvidence(JdbcClient jdbc, UUID attempt, UUID account, UUID deck, UUID objective, UUID objectiveRevision) {
        jdbc.sql("INSERT INTO app_learning.study_evidence(attempt_id,account_id,deck_id,objective_id,objective_revision_id,learning_epoch,"
                + "result,evidence_class,reason_codes,evaluator_id,evaluator_version,hints_used,confidence,duration_ms,accepted_at) "
                + "VALUES (:attempt,:o,:deck,:objective,:revision,0,'CORRECT','HIGH','[]'::jsonb,'cloze','1','[]'::jsonb,NULL,1000,now())")
                .param("attempt", attempt).param("o", account).param("deck", deck).param("objective", objective)
                .param("revision", objectiveRevision).update();
    }

    /** A new session of o1 on deck d1 (a copy of ss1p) with one presentation of the candidate and an attempt on it. */
    private static UUID attempt(JdbcClient jdbc, String name, String mode, String status, UUID generation, int candidate, UUID exercise,
                                UUID exerciseRevision, UUID objective, UUID objectiveRevision) {
        UUID session = id("session-" + name);
        jdbc.sql("INSERT INTO app_learning.study_session(account_id,session_id,deck_id,command_id,mode,status,timezone,local_study_date,"
                + "deck_revision_id,deck_sequence,exercise_generation_id,selection_policy_version,reducer_config_id,seed,budget,issued_count,"
                + "batch_start,batch_size,scan_cursor,wrapped,include_new,practice_order,source_session_id,row_version,created_at,expires_at,"
                + "completed_at,max_new_objectives,issued_new_objectives) SELECT account_id,:session,deck_id,gen_random_uuid(),mode,status,"
                + "timezone,local_study_date,deck_revision_id,deck_sequence,exercise_generation_id,selection_policy_version,reducer_config_id,"
                + "seed,budget,issued_count,batch_start,batch_size,scan_cursor,wrapped,include_new,practice_order,source_session_id,"
                + "row_version,created_at,expires_at,completed_at,max_new_objectives,issued_new_objectives FROM app_learning.study_session "
                + "WHERE session_id=:template").param("session", session).param("template", id("ss1p")).update();
        insertPresentation(jdbc, id("presentation-" + name), session, 0, generation, candidate, exercise, exerciseRevision, objective,
                objectiveRevision);
        UUID attempt = id("attempt-" + name);
        jdbc.sql("INSERT INTO app_learning.study_attempt_tombstone(attempt_id,account_id,session_id,presentation_id,deck_id,payload_hash,mode,"
                + "status,outcome,receipt_expires_at,submitted_at) VALUES (:attempt,:o,:session,:presentation,:deck,decode(repeat('ef',32),'hex'),"
                + ":mode,:status,'{}'::jsonb,CASE WHEN :mode='SCHEDULED' THEN NULL ELSE now()+interval '1 day' END,now())")
                .param("attempt", attempt).param("o", id("o1")).param("session", session).param("presentation", id("presentation-" + name))
                .param("deck", id("d1")).param("mode", mode).param("status", status).update();
        return attempt;
    }

    /** Everything Study derives progress from, serialized: it must not change by a single byte. */
    private static String progress(JdbcClient jdbc) {
        return jdbc.sql("""
                SELECT jsonb_build_object(
                    'state',(SELECT jsonb_agg(to_jsonb(s) ORDER BY account_id,deck_id,objective_id) FROM app_learning.study_state s),
                    'transition',(SELECT jsonb_agg(to_jsonb(t) ORDER BY account_id,deck_id,objective_id,transition_sequence) FROM app_learning.study_transition t),
                    'evidence',(SELECT jsonb_agg(to_jsonb(e) ORDER BY attempt_id) FROM app_learning.study_evidence e),
                    'exposure',(SELECT jsonb_agg(to_jsonb(x) ORDER BY presentation_id) FROM app_learning.study_exposure x),
                    'session',(SELECT jsonb_agg(to_jsonb(s) ORDER BY session_id) FROM app_learning.study_session s),
                    'presentation',(SELECT jsonb_agg(to_jsonb(p) ORDER BY presentation_id) FROM app_learning.study_presentation p),
                    'assignment',(SELECT jsonb_agg(to_jsonb(a) - 'reuse_scope_id' ORDER BY account_id,deck_id,objective_id)
                                    FROM app_learning.study_policy_assignment a))::text
                """).query(String.class).single();
    }

    private static UUID scopeOf(JdbcClient jdbc, String table, String column, UUID value) {
        return jdbc.sql("SELECT reuse_scope_id FROM app_learning." + table + " WHERE " + column + "=:value").param("value", value)
                .query(UUID.class).single();
    }

    private static long count(JdbcClient jdbc, String table) {
        return jdbc.sql("SELECT count(*) FROM app_learning." + table).query(Long.class).single();
    }

    private static List<String> columns(JdbcClient jdbc, String table) {
        return jdbc.sql("SELECT column_name FROM information_schema.columns WHERE table_schema='app_learning' AND table_name=:table")
                .param("table", table).query(String.class).list();
    }

    private static List<String> concat(List<String> first, List<String> second) {
        return java.util.stream.Stream.concat(first.stream(), second.stream()).toList();
    }

    /** A stable UUID per name, the same expansion the seed file uses. */
    private static UUID id(String name) { return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)); }

    private static final Pattern IN_JSON = Pattern.compile("\"\\{\\{(\\w+)}}\"");
    private static final Pattern TOKEN = Pattern.compile("\\{\\{(\\w+)}}");

    /** The seed file with {@code {{name}}} replaced by the name's UUID (quoted for SQL, bare inside a JSON string). */
    private static String expandedSeed() throws IOException {
        try (var stream = LineageExercisesMigrationIntegrationTest.class.getResourceAsStream("/db/v51-exercises-and-study-seed.sql")) {
            String text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            text = IN_JSON.matcher(text).replaceAll(match -> "\"" + id(match.group(1)) + "\"");
            return TOKEN.matcher(text).replaceAll(match -> "'" + id(match.group(1)) + "'");
        }
    }

    /**
     * Rows as V51 left them. Foreign keys and triggers are off for the seed ({@code session_replication_role = replica});
     * V52 validates every new foreign key against these rows. {@code bulk} adds thousands of consistent exercise heads.
     */
    private void seedV51(String url, boolean bulk) throws Exception {
        try (var connection = java.sql.DriverManager.getConnection(url, username(), password());
             var statement = connection.createStatement()) {
            statement.execute(expandedSeed());
            if (bulk) {
                statement.execute("""
                        CREATE TEMP TABLE bulk AS SELECT g, gen_random_uuid() AS ex, gen_random_uuid() AS rev FROM generate_series(1,%d) g;
                        INSERT INTO app_learning.exercise_definition(deck_id,exercise_id,owner_id,reuse_scope_id,created_at)
                          SELECT d.deck_id,ex,d.owner_id,d.reuse_scope_id,now() FROM bulk, app_learning.deck d WHERE d.deck_id='%s';
                        INSERT INTO app_learning.exercise_revision(deck_id,exercise_id,revision_id,reuse_scope_id,exercise_sequence,deck_revision_id,
                            deck_sequence,command_id,exercise_type,schema_version,enabled,content,evaluator_policy,descriptor_root_id,created_at,answer_key)
                          SELECT d.deck_id,ex,rev,d.reuse_scope_id,0,d.head_revision_id,9,gen_random_uuid(),'SELF_CHECK',2,true,'{}','{}',
                                 gen_random_uuid(),now(),'{"kind":"SELF_REPORT"}' FROM bulk, app_learning.deck d WHERE d.deck_id='%s';
                        INSERT INTO app_learning.exercise_content_binding(deck_id,exercise_id,exercise_revision_id,binding_id,
                            binding_ordinal,role,member_key,item_revision_id,objective_id,objective_revision_id,node_ids)
                          SELECT '%s',ex,rev,gen_random_uuid(),0,'ASSESSED','%s','%s','%s','%s',ARRAY[]::uuid[] FROM bulk;
                        INSERT INTO app_learning.deck_head_exercise(deck_id,exercise_id,revision_id,exercise_sequence,ordinal,updated_at)
                          SELECT '%s',ex,rev,0,1000+g,now() FROM bulk;
                        """.formatted(BULK, id("d1"), id("d1"), id("d1"), id("m1a"), id("ir1a1"), id("ob1a"), id("obr1a1"), id("d1")));
            }
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
