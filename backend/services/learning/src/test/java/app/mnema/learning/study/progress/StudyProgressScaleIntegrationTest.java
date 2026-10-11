package app.mnema.learning.study.progress;

import app.mnema.learning.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Share/5 (#427): the progress page of a Deck of 10 000 materials and 30 000 exercises. Its cost is a page, not the Deck: the page of heads
 * is cut first and only those rows get their coverage, found from the lineage bindings of the Deck's scope by
 * {@code (scope, member)}. Timings are printed, not asserted; the assertions are correctness over the whole walk (no gap, no
 * duplicate, coverage per material) which a plan that read the wrong rows would break.
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StudyProgressScaleIntegrationTest extends PostgresIntegrationTest {
    private static final int MATERIALS = 10_000;
    private static final int COVERED = 7_500;
    private static final int PER_MATERIAL = 4;
    private static final int PAGE = 100;

    @Autowired private StudyProgressRepository repository;
    @Autowired private JdbcClient jdbc;
    @Autowired private PlatformTransactionManager transactions;
    private final UUID actor = UUID.randomUUID();
    private final UUID deck = UUID.randomUUID();
    private final UUID scope = UUID.randomUUID();

    @BeforeAll
    void seed() {
        new TransactionTemplate(transactions).executeWithoutResult(ignored -> {
            jdbc.sql("SET LOCAL session_replication_role = replica").update();   // set-based seeding: no triggers, no FK checks
            jdbc.sql("INSERT INTO app_learning.deck(deck_id,owner_id,reuse_scope_id,head_revision_id,row_version,created_at) "
                    + "VALUES (:deck,:actor,:scope,gen_random_uuid(),1,now())").param("deck", deck).param("actor", actor)
                    .param("scope", scope).update();
            jdbc.sql("CREATE TEMP TABLE m ON COMMIT DROP AS SELECT g, gen_random_uuid() AS member, gen_random_uuid() AS rev "
                    + "FROM generate_series(1,:n) g").param("n", MATERIALS).update();
            jdbc.sql("INSERT INTO app_learning.deck_head_item(deck_id,reuse_scope_id,member_key,revision_id,item_sequence,updated_at) "
                    + "SELECT :deck,:scope,member,rev,0,now() FROM m").param("deck", deck).param("scope", scope).update();
            jdbc.sql("INSERT INTO app_learning.item_revision(deck_id,member_key,revision_id,reuse_scope_id,owner_id,item_sequence,"
                    + "deck_revision_id,deck_sequence,command_id,format_version,content_root_id,descriptor_root_id,created_at) "
                    + "SELECT :deck,member,rev,:scope,:actor,0,gen_random_uuid(),1,gen_random_uuid(),1,gen_random_uuid(),gen_random_uuid(),now() FROM m")
                    .param("deck", deck).param("scope", scope).param("actor", actor).update();
            jdbc.sql("CREATE TEMP TABLE e ON COMMIT DROP AS SELECT x.g, k, gen_random_uuid() AS ex, gen_random_uuid() AS er, "
                    + "CASE WHEN k=0 THEN gen_random_uuid() END AS ob, m.member, m.rev FROM generate_series(1,:covered) x(g) "
                    + "CROSS JOIN generate_series(0,:per-1) k JOIN m ON m.g=x.g").param("covered", COVERED).param("per", PER_MATERIAL).update();
            // one objective per material, shared by its four exercises
            jdbc.sql("UPDATE e SET ob=(SELECT first.ob FROM e first WHERE first.g=e.g AND first.k=0) WHERE k>0").update();
            jdbc.sql("INSERT INTO app_learning.exercise_definition(deck_id,exercise_id,owner_id,reuse_scope_id,created_at) "
                    + "SELECT :deck,ex,:actor,:scope,now() FROM e").param("deck", deck).param("actor", actor).param("scope", scope).update();
            jdbc.sql("INSERT INTO app_learning.exercise_revision(deck_id,exercise_id,revision_id,reuse_scope_id,exercise_sequence,"
                    + "deck_revision_id,deck_sequence,command_id,exercise_type,schema_version,enabled,content,evaluator_policy,"
                    + "descriptor_root_id,created_at,answer_key) SELECT :deck,ex,er,:scope,0,gen_random_uuid(),1,gen_random_uuid(),"
                    + "'FREE_RESPONSE',2,true,'{}','{}',gen_random_uuid(),now(),'{\"kind\":\"TEXT\"}' FROM e")
                    .param("deck", deck).param("scope", scope).update();
            jdbc.sql("INSERT INTO app_learning.exercise_content_binding(deck_id,reuse_scope_id,exercise_id,exercise_revision_id,binding_id,"
                    + "binding_ordinal,role,member_key,item_revision_id,objective_id,objective_revision_id,node_ids) "
                    + "SELECT :deck,:scope,ex,er,gen_random_uuid(),0,'ASSESSED',member,rev,ob,gen_random_uuid(),'{}' FROM e")
                    .param("deck", deck).param("scope", scope).update();
            jdbc.sql("INSERT INTO app_learning.deck_head_exercise(deck_id,reuse_scope_id,exercise_id,revision_id,exercise_sequence,ordinal,"
                    + "updated_at) SELECT :deck,:scope,ex,er,0,(row_number() OVER ())::int,now() FROM e")
                    .param("deck", deck).param("scope", scope).update();
            // every material with a multiple-of-5 index has progress: introduced and assessed, due or on track
            jdbc.sql("INSERT INTO app_learning.study_state(account_id,deck_id,objective_id,learning_epoch,level,correct_streak,lapse_count,"
                    + "last_assessed_at,next_due,reducer_config_id,transition_sequence,row_version,introduced_at,updated_at) "
                    + "SELECT :actor,:deck,ob,0,3,1,0,now(),CASE WHEN g%10=0 THEN now()-interval '1 day' ELSE now()+interval '2 days' END,"
                    + "(SELECT config_id FROM app_learning.scheduler_config LIMIT 1),1,1,now(),now() FROM e WHERE k=0 AND g%5=0")
                    .param("actor", actor).param("deck", deck).update();
        });
        jdbc.sql("ANALYZE").update();
    }

    @Test
    void aFullWalkOfTheProgressPagesHasNoGapNoDuplicateAndTheRightCoveragePerMaterial() {
        Set<UUID> seen = new HashSet<>();
        UUID after = null;
        long worst = 0;
        long total = 0;
        int pages = 0;
        int covered = 0;
        int introduced = 0;
        int due = 0;
        Instant asOf = repository.now();
        while (true) {
            long started = System.nanoTime();
            List<StudyProgressRepository.Material> page = repository.page(actor, deck, after, PAGE + 1, asOf);
            long elapsed = (System.nanoTime() - started) / 1_000_000;
            worst = Math.max(worst, elapsed);
            total += elapsed;
            pages++;
            List<StudyProgressRepository.Material> rows = new ArrayList<>(page.size() > PAGE ? page.subList(0, PAGE) : page);
            for (StudyProgressRepository.Material material : rows) {
                assertThat(seen.add(material.memberKey())).isTrue();
                assertThat(material.enabled()).isLessThanOrEqualTo(1);   // one objective per covered material
                covered += material.enabled();
                introduced += material.introduced();
                if (material.due()) due++;
            }
            if (page.size() <= PAGE) break;
            after = rows.getLast().memberKey();
            assertThat(after).isNotNull();
        }
        System.out.printf("progress-scale: %d materials / %d exercises, %d pages of %d, worst page %d ms, mean %d ms%n", MATERIALS,
                COVERED * PER_MATERIAL, pages, PAGE, worst, total / pages);
        assertThat(seen).hasSize(MATERIALS);
        assertThat(pages).isEqualTo(MATERIALS / PAGE + (MATERIALS % PAGE == 0 ? 0 : 1));
        assertThat(covered).isEqualTo(COVERED);
        assertThat(introduced).isEqualTo(COVERED / 5);
        assertThat(due).isEqualTo(COVERED / 10);
    }
}
