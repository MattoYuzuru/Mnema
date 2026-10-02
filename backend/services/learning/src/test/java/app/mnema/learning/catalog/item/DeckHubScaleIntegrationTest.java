package app.mnema.learning.catalog.item;

import app.mnema.learning.catalog.deck.DeckInsightsService;
import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.study.session.StudySessionService;
import app.mnema.learning.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Scale of the hub reads. Insights run over a Deck of 10 000 materials with 30 000 exercises and must stay inside the
 * request budget (the 10 s transaction timeout, target well under one second). Publishing 10 000 materials through the
 * production path takes about 13 minutes (measured: 770 s), so that deck is a real one-material Deck whose head
 * projection (what insights read) is extended set-based, with exercise and study rows cloned from one real exercise.
 * The exerciseCount sort needs the immutable member tree, so it is verified on a Deck of {@value #REAL_MATERIALS} really
 * published materials: a full scan has no duplicates or gaps and each page is timed. Timings are printed.
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DeckHubScaleIntegrationTest extends PostgresIntegrationTest {
    private static final int MATERIALS = 10_000;
    private static final int REAL_MATERIALS = 300;
    private static final int COVERED = 7_500;
    private static final int PER_MATERIAL = 4;
    /** Generous regression guard; the contract budget is the 10 s request timeout. */
    private static final long BUDGET_MS = 3_000;

    @Autowired private DeckService decks;
    @Autowired private ItemService items;
    @Autowired private ExerciseService exercises;
    @Autowired private StudySessionService sessions;
    @Autowired private MediaCatalog media;
    @Autowired private JdbcClient jdbc;
    @Autowired private DeckInsightsService insights;
    @Autowired private ItemBulkDeleteService deletions;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private ItemRepository repository;

    private HubFixtures fixtures;
    private UUID actor;
    private UUID wide;
    private UUID real;

    @BeforeAll
    void buildDecks() {
        fixtures = new HubFixtures(decks, items, exercises, sessions, media, jdbc);
        actor = UUID.randomUUID();
        wide = fixtures.deck(actor);
        HubFixtures.Created first = fixtures.seed(actor, wide, 1).getFirst();
        fixtures.exercise(actor, wide, first, "SELF_CHECK", true);
        long started = System.nanoTime();
        new TransactionTemplate(transactions).executeWithoutResult(ignored -> {
            jdbc.sql("SET LOCAL session_replication_role = replica").update();
            // Head projection only: insights never read the immutable roots, item revisions or learning_item rows.
            jdbc.sql("""
                    INSERT INTO app_learning.deck_head_item(deck_id,member_key,revision_id,item_sequence,updated_at)
                    SELECT :deck,gen_random_uuid(),gen_random_uuid(),0,statement_timestamp() FROM generate_series(1,:extra)
                    """).param("deck", wide).param("extra", MATERIALS - 1).update();
            cloneExercises(wide);
        });
        System.out.printf("hub-scale: %d materials / %d exercises seeded set-based in %d ms%n", MATERIALS,
                COVERED * PER_MATERIAL, (System.nanoTime() - started) / 1_000_000);

        real = fixtures.deck(actor);
        started = System.nanoTime();
        HubFixtures.Created template = fixtures.seed(actor, real, REAL_MATERIALS).getFirst();
        System.out.printf("hub-scale: %d materials published through the production path in %d ms%n", REAL_MATERIALS,
                (System.nanoTime() - started) / 1_000_000);
        fixtures.exercise(actor, real, template, "SELF_CHECK", true);
        new TransactionTemplate(transactions).executeWithoutResult(ignored -> {
            jdbc.sql("SET LOCAL session_replication_role = replica").update();
            cloneExercises(real);
        });
    }

    @Test
    void insightsAnswerWithinTheBudgetAndEqualTheSums() {
        insights.read(actor, wide, "Europe/Moscow");   // warm the plan cache and the pool
        long best = Long.MAX_VALUE;
        JsonNode result = null;
        for (int run = 0; run < 3; run++) {
            long started = System.nanoTime();
            result = insights.read(actor, wide, "Europe/Moscow");
            best = Math.min(best, (System.nanoTime() - started) / 1_000_000);
        }
        System.out.printf("hub-scale: insights over %d materials / %d exercises best of 3 = %d ms%n", MATERIALS,
                COVERED * PER_MATERIAL, best);
        assertThat(best).isLessThan(BUDGET_MS);
        assertThat(result.path("coverage").path("total").intValue()).isEqualTo(MATERIALS);
        // The one really published exercise sits on a random material: it is covered either way, +1 when outside the clones.
        assertThat(result.path("coverage").path("withExercises").intValue()).isBetween(COVERED, COVERED + 1);
        assertThat(result.path("exercisesByMechanic").path("FREE_RESPONSE").intValue()).isEqualTo(COVERED);
        assertThat(result.path("states").valueStream().mapToInt(JsonNode::intValue).sum()).isEqualTo(MATERIALS);
        assertThat(result.path("exercisesByMechanic").valueStream().mapToInt(JsonNode::intValue).sum())
                .isEqualTo(COVERED * PER_MATERIAL + 1);
        assertThat(result.path("states").path("NOT_STARTED").intValue()).isGreaterThan(MATERIALS - COVERED - 1);
        assertThat(result.path("states").path("DUE").intValue()).isPositive();
        assertThat(result.path("states").path("ON_TRACK").intValue()).isPositive();
        assertThat(result.path("states").path("LEARNING").intValue()).isPositive();
        assertThat(result.path("dueByDay").get(0).path("materials").intValue()).isEqualTo(result.path("states").path("DUE").intValue());
    }

    @Test
    void sortedPagesStayInsideTheBudgetAndAFullScanHasNoDuplicatesOrGaps() {
        // First scan lazily fills the item_preview projection (an expense of the first read of any Browse page).
        String warm = null;
        do {
            JsonNode page = items.list(actor, real, "100", warm, "exerciseCount", null);
            warm = page.path("nextCursor").stringValue(null);
        } while (warm != null);
        var head = repository.deck(actor, real).orElseThrow();
        long sql = Long.MAX_VALUE;
        for (int run = 0; run < 3; run++) {
            long begin = System.nanoTime();
            repository.sortedMembers(head, null, null, 26);
            sql = Math.min(sql, (System.nanoTime() - begin) / 1_000_000);
        }
        System.out.printf("hub-scale: sorted-member statement over %d materials best of 3 = %d ms%n", REAL_MATERIALS, sql);
        long started = System.nanoTime();
        JsonNode first = items.list(actor, real, "25", null, "exerciseCount", null);
        long firstMs = (System.nanoTime() - started) / 1_000_000;
        assertThat(first.path("items").get(0).path("exerciseCount").intValue()).isZero();   // 75 of 300 have none

        Set<String> seen = new HashSet<>();
        String cursor = null;
        int pages = 0;
        int count = -1;
        int ordinal = -1;
        long worst = 0;
        do {
            started = System.nanoTime();
            JsonNode page = items.list(actor, real, "25", cursor, "exerciseCount", null);
            worst = Math.max(worst, (System.nanoTime() - started) / 1_000_000);
            for (JsonNode item : page.path("items")) {
                int itemCount = item.path("exerciseCount").intValue();
                int itemOrdinal = item.path("ordinal").intValue();
                assertThat(itemCount).isGreaterThanOrEqualTo(count);
                if (itemCount == count) assertThat(itemOrdinal).isGreaterThan(ordinal);
                count = itemCount;
                ordinal = itemOrdinal;
                assertThat(seen.add(item.path("memberKey").stringValue(null))).isTrue();
            }
            cursor = page.path("nextCursor").stringValue(null);
            pages++;
        } while (cursor != null);
        System.out.printf("hub-scale: sorted list over %d materials first page %d ms, worst of %d pages %d ms%n",
                REAL_MATERIALS, firstMs, pages, worst);
        assertThat(seen).hasSize(REAL_MATERIALS);
        assertThat(pages).isEqualTo(REAL_MATERIALS / 25);
        assertThat(worst).isLessThan(BUDGET_MS);
    }

    @Test
    void insightsAndAnAllInDeckSelectionNeverReadTheTreeOfAWideDeck() {
        // A 10 000-member Deck revision (metadata only) is rejected by the cap before the member root is read.
        String revision = fixtures.head(actor, wide).path("revisionId").stringValue(null);
        long version = fixtures.version(actor, wide);
        new TransactionTemplate(transactions).executeWithoutResult(ignored -> {
            jdbc.sql("SET LOCAL session_replication_role = replica").update();
            jdbc.sql("""
                    UPDATE app_learning.deck_revision SET member_count=:count WHERE deck_id=:deck AND revision_id=CAST(:revision AS uuid)
                    """).param("count", MATERIALS).param("deck", wide).param("revision", revision).update();
        });
        String body = "{\"commandId\":\"" + UUID.randomUUID() + "\",\"expectedDeckRevisionId\":\"" + revision
                + "\",\"allInDeck\":true}";
        assertThatThrownBy(() -> deletions.delete(actor, wide, version, BulkDeleteCommand.read(
                new java.io.ByteArrayInputStream(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)))))
                .isInstanceOf(BulkSelectionTooLargeException.class);
    }

    private int totalHeads(UUID deck) {
        return jdbc.sql("SELECT count(*) FROM app_learning.deck_head_item WHERE deck_id=:deck").param("deck", deck)
                .query(Integer.class).single();
    }

    /**
     * Clones one real exercise into {@value #PER_MATERIAL} enabled exercises for each of the first {@value #COVERED}
     * materials (at most the Deck's own, 75 %), with one objective per material and varied study states. Runs with replication role 'replica' so the
     * immutable-history and descriptor triggers and the FK checks do not fire; this is test seeding only.
     */
    private void cloneExercises(UUID deck) {
        jdbc.sql("SET LOCAL session_replication_role = replica").update();
        jdbc.sql("""
                CREATE TEMP TABLE hub_seed ON COMMIT DROP AS
                SELECT item.member_key,item.revision_id AS item_revision_id,gen_random_uuid() AS objective_id,
                       gen_random_uuid() AS objective_revision_id,
                       row_number() OVER (ORDER BY item.member_key) AS rn
                  FROM app_learning.deck_head_item item
                 WHERE item.deck_id=:deck
                """).param("deck", deck).update();
        jdbc.sql("DELETE FROM hub_seed WHERE rn>:covered").param("covered", Math.min(COVERED, totalHeads(deck) * 3 / 4)).update();
        jdbc.sql("""
                CREATE TEMP TABLE hub_exercise ON COMMIT DROP AS
                SELECT seed.*,k,gen_random_uuid() AS exercise_id,gen_random_uuid() AS exercise_revision_id,
                       row_number() OVER (ORDER BY seed.rn,k) + 1000 AS ordinal
                  FROM hub_seed seed CROSS JOIN generate_series(0,:per-1) AS k
                """).param("per", PER_MATERIAL).update();
        jdbc.sql("""
                INSERT INTO app_learning.memory_objective(deck_id,objective_id,objective_key,member_key,owner_id,reuse_scope_id,created_at)
                SELECT :deck,objective_id,gen_random_uuid(),member_key,deck.owner_id,deck.reuse_scope_id,statement_timestamp()
                  FROM hub_seed CROSS JOIN app_learning.deck deck WHERE deck.deck_id=:deck
                """).param("deck", deck).update();
        jdbc.sql("""
                INSERT INTO app_learning.objective_revision(deck_id,objective_id,revision_id,objective_sequence,deck_revision_id,
                    deck_sequence,command_id,descriptor,created_at)
                SELECT :deck,seed.objective_id,seed.objective_revision_id,0,deck.head_revision_id,deck.row_version,gen_random_uuid(),
                       '{"schemaVersion":"1","title":"Objective"}'::jsonb,statement_timestamp()
                  FROM hub_seed seed CROSS JOIN app_learning.deck deck WHERE deck.deck_id=:deck
                """).param("deck", deck).update();
        jdbc.sql("""
                INSERT INTO app_learning.objective_head(deck_id,objective_id,revision_id,objective_sequence,updated_at)
                SELECT :deck,objective_id,objective_revision_id,0,statement_timestamp() FROM hub_seed
                """).param("deck", deck).update();
        jdbc.sql("""
                INSERT INTO app_learning.exercise_definition(deck_id,exercise_id,owner_id,reuse_scope_id,created_at)
                SELECT :deck,exercise.exercise_id,deck.owner_id,deck.reuse_scope_id,statement_timestamp()
                  FROM hub_exercise exercise CROSS JOIN app_learning.deck deck WHERE deck.deck_id=:deck
                """).param("deck", deck).update();
        jdbc.sql("""
                INSERT INTO app_learning.exercise_revision(deck_id,exercise_id,revision_id,reuse_scope_id,exercise_sequence,
                    deck_revision_id,deck_sequence,command_id,exercise_type,schema_version,enabled,content,evaluator_policy,
                    descriptor_root_id,created_at,answer_key)
                SELECT :deck,exercise.exercise_id,exercise.exercise_revision_id,template.reuse_scope_id,0,
                       template.deck_revision_id,template.deck_sequence,gen_random_uuid(),
                       (ARRAY['SELF_CHECK','FREE_RESPONSE','CHOICE','CLOZE'])[exercise.k+1],template.schema_version,TRUE,
                       template.content,template.evaluator_policy,template.descriptor_root_id,statement_timestamp(),template.answer_key
                  FROM hub_exercise exercise
                 CROSS JOIN (SELECT * FROM app_learning.exercise_revision WHERE deck_id=:deck LIMIT 1) template
                """).param("deck", deck).update();
        jdbc.sql("""
                INSERT INTO app_learning.exercise_content_binding(deck_id,exercise_id,exercise_revision_id,binding_id,binding_ordinal,
                    role,member_key,item_revision_id,objective_id,objective_revision_id,node_ids)
                SELECT :deck,exercise_id,exercise_revision_id,gen_random_uuid(),0,'ASSESSED',member_key,item_revision_id,objective_id,
                       objective_revision_id,ARRAY[]::uuid[]
                  FROM hub_exercise
                """).param("deck", deck).update();
        jdbc.sql("""
                INSERT INTO app_learning.deck_head_exercise(deck_id,exercise_id,revision_id,exercise_sequence,ordinal,updated_at)
                SELECT :deck,exercise_id,exercise_revision_id,0,ordinal,statement_timestamp() FROM hub_exercise
                """).param("deck", deck).update();
        jdbc.sql("""
                INSERT INTO app_learning.study_policy_assignment(account_id,deck_id,objective_id,reducer_config_id,assigned_at)
                SELECT :actor,:deck,objective_id,(SELECT config_id FROM app_learning.scheduler_config LIMIT 1),statement_timestamp()
                  FROM hub_seed WHERE rn%5<>0
                """).param("actor", actor).param("deck", deck).update();
        jdbc.sql("""
                INSERT INTO app_learning.study_state(account_id,deck_id,objective_id,learning_epoch,level,correct_streak,lapse_count,
                    last_assessed_at,next_due,reducer_config_id,transition_sequence,row_version,introduced_at,updated_at)
                SELECT :actor,:deck,objective_id,0,CASE WHEN rn%5=1 THEN 0 ELSE 3 END,0,0,
                       CASE WHEN rn%5=1 THEN NULL ELSE statement_timestamp() END,
                       CASE rn%5 WHEN 1 THEN NULL WHEN 2 THEN statement_timestamp()-INTERVAL '3 hours'
                                 WHEN 3 THEN statement_timestamp()+INTERVAL '2 days' ELSE statement_timestamp()+INTERVAL '5 days' END,
                       (SELECT config_id FROM app_learning.scheduler_config LIMIT 1),CASE WHEN rn%5=1 THEN 0 ELSE 1 END,0,
                       statement_timestamp(),statement_timestamp()
                  FROM hub_seed WHERE rn%5<>0
                """).param("actor", actor).param("deck", deck).update();
    }
}
