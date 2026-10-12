package app.mnema.learning.update.plan;

import app.mnema.learning.update.apply.ApplyService;
import app.mnema.learning.update.plan.PlanWorlds.Copy;
import app.mnema.learning.update.plan.PlanWorlds.Source;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Updates/6 part B (#445), the load: the fan-out of a source with 2 000 automatic copies (30 % of them active) and the automatic apply of an update of 10 units, measured on a real
 * PostgreSQL with the DEFAULT settings of the fan-out (slices of 25 copies, pages of 200) and the job settings, except the pace (not paced here: the pace is a rate, tested with a test clock). The figures are printed ({@code AUTO_SCALE}) for
 * the guide; the assertions are loose bounds that catch a regression to a scan or to a statement per copy of the page, not a stopwatch.
 */
class AutoUpdateScaleIntegrationTest extends AutoUpdateContext {
    @Test
    void aFanOutOver2000CopiesAndAnAutomaticApplyOfTenUnitsStayInsideTheirBudgets() {
        {
            // 12 materials; the author will fix ten of them
            Source source = worlds.source("LINK", 12, 0);
            List<Copy> copies = new ArrayList<>();
            for (int index = 0; index < 3; index++) copies.add(auto.autoCopy(source, hand));
            for (int index = 0; index < 10; index++) worlds.editText(source.author(), source.deck(), source.materials().get(index).member(), "author " + index);
            // 2 000 copies of the source that follow it automatically, 30 % made inside the window and the rest 60 days ago; the keys are switched off for the statement
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                jdbc.sql("SET LOCAL session_replication_role = replica").update();
                jdbc.sql("""
                        INSERT INTO app_learning.deck_fork(deck_id,reuse_scope_id,owner_id,upstream_deck_id,upstream_code,base_revision_id,forked_at,job_id,auto_update,materialized_at)
                        SELECT gen_random_uuid(),gen_random_uuid(),gen_random_uuid(),:source,'Kq7xT3mNpR',gen_random_uuid(),
                               CASE WHEN n % 10 < 3 THEN clock_timestamp() ELSE clock_timestamp() - interval '60 days' END,gen_random_uuid(),TRUE,clock_timestamp()
                          FROM generate_series(1,2000) n
                        """).param("source", source.deck()).update();
                jdbc.sql("ANALYZE app_learning.deck_fork").update();
            });
            worlds.publish(source.author(), source.deck(), "LINK");
            UUID published = worlds.published(source.deck());

            long started = System.nanoTime();
            assertThat(hand.runOne(AutoWorlds.FANOUT)).isTrue();
            double fanoutMillis = (System.nanoTime() - started) / 1e6;
            var fanout = auto.jobs(AutoWorlds.FANOUT, source.deck()).getFirst();
            assertThat(fanout.state()).isEqualTo("SUCCEEDED");
            // the three real copies were made inside the window as well: 600 + 3 of the synthetic ones are active
            long handed = auto.autoJobs(published).size();
            assertThat(handed).isEqualTo(fanout.progressCount()).isEqualTo(600 + 3);
            assertThat(fanoutMillis).as("2 000 copies in pages of 500, 603 jobs enqueued").isLessThan(30_000);

            // the automatic apply of ten units, three copies, warm after the first
            List<Double> applyMillis = new ArrayList<>();
            for (Copy copy : copies) {
                assertThat(apply.plan(copy).units()).hasSize(10);
                long begin = System.nanoTime();
                ApplyService.AutoResult result = new TransactionTemplate(transactions).execute(status -> applies.applyAuto(copy.owner(), copy.deck()));
                applyMillis.add((System.nanoTime() - begin) / 1e6);
                assertThat(result.outcome()).isEqualTo(ApplyService.AutoOutcome.APPLIED);
                assertThat(result.accepted()).isEqualTo(10);
                assertThat(apply.plan(copy).state()).isEqualTo(UpdatePlan.State.UP_TO_DATE);
            }
            double best = applyMillis.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
            // an idle fan-out (a source whose copies are all idle) costs one bounded page per slice
            System.out.printf("AUTO_SCALE fanout_2000_copies_603_enqueued_ms=%.0f auto_apply_10_units_ms=%s best=%.0f%n", fanoutMillis, applyMillis, best);
            assertThat(best).as("an automatic apply of ten units").isLessThan(5_000);
        }
    }
}
