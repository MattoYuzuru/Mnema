package app.mnema.learning.study.attempt;

import app.mnema.learning.onboarding.SampleChecks;
import app.mnema.learning.support.SampleDeckFixtures;
import app.mnema.learning.support.StudyFixtures.Material;
import app.mnema.learning.usage.SponsoredOperation;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The free AI checks of the onboarding sample deck (OB-203) on the real AI assessment: the first three DELIVERED grades of an account in its sample deck are counted as the
 * sponsored operation {@code ONBOARDING_SAMPLE_CHECK} instead of the plan's fair-use bucket, per account and not per copy; the fourth is an ordinary check; a check that is not
 * delivered, and a check in any other deck, never touch the free ones.
 */
class SampleCheckAssessmentIntegrationTest extends AssessmentIntegrationTest {
    @Autowired private SampleChecks sampleChecks;

    private void sample(Case learner) {
        SampleDeckFixtures.markSample(jdbc, transactions, learner.deck());
    }

    private boolean inTransaction(java.util.function.BooleanSupplier body) {
        return Boolean.TRUE.equals(new TransactionTemplate(transactions).execute(status -> body.getAsBoolean()));
    }

    private long sponsored(UUID actor) {
        return jdbc.sql("SELECT count(*) FROM app_learning.usage_ledger_entry WHERE owner_id=:a AND operation='ONBOARDING_SAMPLE_CHECK'").param("a", actor).query(Long.class).single();
    }

    /** The learner answers the case's presentation and the grade is delivered. */
    private Case graded(Case learner) {
        UUID attempt = UUID.randomUUID();
        submit(learner, attempt, "Он перебирает планы, потом берёт самый дешёвый [[stub:assess-complete]]");
        assertThat(settled(learner, attempt).path("status").stringValue(null)).isEqualTo("ASSESSED");
        return learner;
    }

    @Test
    void theFirstThreeDeliveredGradesInTheSampleDeckAreFreeAndTheFourthIsAnOrdinaryCheck() {
        Case learner = issued();
        sample(learner);
        UUID actor = learner.actor();
        assertThat(sampleChecks.left(actor)).isEqualTo(3);

        for (int index = 1; index <= 3; index++) {
            graded(learner);
            assertThat(sponsored(actor)).as("free checks after grade " + index).isEqualTo(index);
            assertThat(checks(actor)).as("the plan's bucket after grade " + index).isZero();
            assertThat(sampleChecks.left(actor)).isEqualTo(3 - index);
            learner = sameObjective(learner);
        }
        graded(learner);
        assertThat(sponsored(actor)).as("a fourth is not free").isEqualTo(3);
        assertThat(checks(actor)).as("the fourth is paid from the plan, as ever").isOne();
        assertThat(sampleChecks.left(actor)).isZero();

        // what the ledger holds: 3 entries of 0 credits, tagged, without a bucket, and visible to the owner console report by their operation
        var rows = jdbc.sql("SELECT kind, credits, bucket, units, operation, reference FROM app_learning.usage_ledger_entry WHERE owner_id=:a AND operation='ONBOARDING_SAMPLE_CHECK'")
                .param("a", actor).query().listOfRows();
        assertThat(rows).hasSize(3).allSatisfy(row -> {
            assertThat(row).containsEntry("kind", "DEBIT").containsEntry("credits", 0).containsEntry("operation", SponsoredOperation.ONBOARDING_SAMPLE_CHECK.name());
            assertThat(row.get("bucket")).isNull();
            assertThat(row.get("units")).isNull();
            assertThat(row.get("reference")).isNotNull();
        });
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.usage_ledger_entry WHERE owner_id=:a AND idempotency_key LIKE 'sample-check:assessment:%'").param("a", actor)
                .query(Long.class).single()).isEqualTo(3);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.usage_ledger_entry WHERE owner_id=:a AND idempotency_key LIKE 'assessment:%'").param("a", actor)
                .query(Long.class).single()).isOne();
    }

    @Test
    void anotherDeckOfTheSameAccountIsNeverFree() {
        Case sampleCase = issued();
        sample(sampleCase);
        UUID actor = sampleCase.actor();
        // an ordinary deck of the same account, with its own semantic exercise
        Material other = fixtures.material(actor);
        fixtures.publish(other, semanticExercise(other), "Другая колода");
        Case ordinary = new Case(other, fixtures.issueOne(other));
        assertThat(ordinary.deck()).isNotEqualTo(sampleCase.deck());

        graded(ordinary);
        assertThat(sponsored(actor)).isZero();
        assertThat(checks(actor)).isOne();
        assertThat(sampleChecks.left(actor)).isEqualTo(3);
        graded(sampleCase);
        assertThat(sponsored(actor)).isOne();
        assertThat(checks(actor)).as("the sample check did not add to the plan's bucket").isOne();
    }

    @Test
    void theFreeChecksBelongToTheAccountAndADeletedCopyDoesNotGiveThreeMore() {
        Case first = issued();
        sample(first);
        UUID actor = first.actor();
        graded(first);
        graded(sameObjective(first));
        assertThat(sponsored(actor)).isEqualTo(2);

        // the copy is deleted and a new sample deck of the same account is made
        long version = Long.parseLong(decks.read(actor, first.deck()).path("rowVersion").stringValue(null));
        decks.delete(actor, first.deck(), version);
        Material second = fixtures.material(actor);
        fixtures.publish(second, semanticExercise(second), "Новая учебная колода");
        SampleDeckFixtures.markSample(jdbc, transactions, second.deck());
        Case next = new Case(second, fixtures.issueOne(second));

        assertThat(sampleChecks.left(actor)).as("one free check is left for the account, not three for the new copy").isOne();
        graded(next);
        assertThat(sponsored(actor)).isEqualTo(3);
        assertThat(checks(actor)).isZero();
        graded(sameObjective(next));
        assertThat(sponsored(actor)).isEqualTo(3);
        assertThat(checks(actor)).as("the next check is the plan's").isOne();
        assertThat(sampleChecks.left(actor)).isZero();
    }

    @Test
    void aCheckThatIsNotDeliveredIsNotCounted() {
        Case learner = issued();
        sample(learner);
        UUID actor = learner.actor();
        UUID attempt = UUID.randomUUID();
        submit(learner, attempt, "Не знаю [[stub:assess-unclear]]");
        assertThat(settled(learner, attempt).path("status").stringValue(null)).isEqualTo("SELF_CHECK");
        assertThat(sponsored(actor)).as("an uncertain grade costs nothing, not even a free check").isZero();
        assertThat(sampleChecks.left(actor)).isEqualTo(3);

        Case failing = sameObjective(learner);
        UUID failed = UUID.randomUUID();
        submit(failing, failed, "Ответ [[fake:fail]]");
        assertThat(settled(failing, failed).path("status").stringValue(null)).isIn("SELF_CHECK", "UNAVAILABLE");
        assertThat(sponsored(actor)).isZero();
        assertThat(checks(actor)).isZero();
        assertThat(sampleChecks.left(actor)).isEqualTo(3);
    }

    @Test
    void anExhaustedPlanDoesNotStopACoveredCheckButDoesStopThePaidOnes() {
        Case learner = issued();
        sample(learner);
        UUID actor = learner.actor();
        exhaustFairUse(actor);

        // the plan is spent, the sample deck's free checks are not: three answers are graded
        for (int index = 1; index <= 3; index++) {
            JsonNode outcome = settled(learner, submitted(learner));
            assertThat(outcome.path("status").stringValue(null)).as("answer " + index).isEqualTo("ASSESSED");
            learner = sameObjective(learner);
        }
        assertThat(sponsored(actor)).isEqualTo(3);
        // the fourth is an ordinary check and the plan has no room: the answer goes to self-check, with the ordinary reason
        UUID attempt = submitted(learner);
        JsonNode view = settled(learner, attempt);
        assertThat(view.path("status").stringValue(null)).isEqualTo("SELF_CHECK");
        assertThat(view.path("reason").stringValue(null)).isEqualTo("USAGE_LIMIT");
        assertThat(sponsored(actor)).isEqualTo(3);
    }

    private UUID submitted(Case learner) {
        UUID attempt = UUID.randomUUID();
        submit(learner, attempt, "Он перебирает планы, потом берёт самый дешёвый [[stub:assess-complete]]");
        return attempt;
    }

    // ---- the ledger operation itself ----

    @Test
    void theSponsoredConsumptionIsIdempotentByKeyAndBoundedPerAccountUnderParallelChecks() throws Exception {
        UUID actor = UUID.randomUUID();
        TransactionTemplate tx = new TransactionTemplate(transactions);
        int threads = 10;
        CountDownLatch go = new CountDownLatch(1);
        List<Callable<Boolean>> calls = new ArrayList<>();
        for (int index = 0; index < threads; index++) {
            String key = "sample-check:test:" + actor + ":" + index;
            calls.add(() -> {
                go.await();
                return tx.execute(status -> ledger.consumeSponsored(actor, SponsoredOperation.ONBOARDING_SAMPLE_CHECK, 3, key, null));
            });
        }
        List<Boolean> answers = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (Callable<Boolean> call : calls) futures.add(pool.submit(call));
            go.countDown();
            for (Future<Boolean> future : futures) answers.add(future.get());
        }
        assertThat(answers.stream().filter(answer -> answer).count()).as("exactly the limit is free, whoever wins").isEqualTo(3);
        assertThat(sponsored(actor)).isEqualTo(3);

        // a repeat of a key that was counted is still "free" (nothing more is written); a new key past the limit is not
        String counted = jdbc.sql("SELECT idempotency_key FROM app_learning.usage_ledger_entry WHERE owner_id=:a AND operation='ONBOARDING_SAMPLE_CHECK' LIMIT 1")
                .param("a", actor).query(String.class).single();
        assertThat(inTransaction(() -> ledger.consumeSponsored(actor, SponsoredOperation.ONBOARDING_SAMPLE_CHECK, 3, counted, null))).isTrue();
        assertThat(inTransaction(() -> ledger.consumeSponsored(actor, SponsoredOperation.ONBOARDING_SAMPLE_CHECK, 3, "sample-check:test:late", null))).isFalse();
        assertThat(sponsored(actor)).isEqualTo(3);
        // a key is one consumption of one account
        assertThatThrownBy(() -> tx.execute(status -> ledger.consumeSponsored(UUID.randomUUID(), SponsoredOperation.ONBOARDING_SAMPLE_CHECK, 3, counted, null)))
                .isInstanceOf(IllegalArgumentException.class);
        // a limit of 0 sponsors nothing; the writer joins the caller's transaction and refuses to run alone
        assertThat(inTransaction(() -> ledger.consumeSponsored(UUID.randomUUID(), SponsoredOperation.ONBOARDING_SAMPLE_CHECK, 0, "sample-check:test:zero", null))).isFalse();
        assertThatThrownBy(() -> ledger.consumeSponsored(actor, SponsoredOperation.ONBOARDING_SAMPLE_CHECK, 3, "sample-check:test:alone", null))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    @Test
    void aDeckThatIsNotTheAccountsLiveSampleDeckIsNotCovered() {
        Case learner = issued();
        UUID actor = learner.actor();
        TransactionTemplate tx = new TransactionTemplate(transactions);
        assertThat(sampleChecks.covers(actor, learner.deck())).as("an ordinary deck").isFalse();
        assertThat(inTransaction(() -> sampleChecks.consume(actor, learner.deck(), "sample-check:test:ordinary:" + actor, null))).isFalse();
        sample(learner);
        assertThat(sampleChecks.covers(actor, learner.deck())).isTrue();
        assertThat(sampleChecks.covers(UUID.randomUUID(), learner.deck())).as("somebody else's sample deck").isFalse();
        long version = Long.parseLong(decks.read(actor, learner.deck()).path("rowVersion").stringValue(null));
        decks.delete(actor, learner.deck(), version);
        assertThat(sampleChecks.covers(actor, learner.deck())).as("a deleted sample deck").isFalse();
        assertThat(sponsored(actor)).isZero();
    }
}
