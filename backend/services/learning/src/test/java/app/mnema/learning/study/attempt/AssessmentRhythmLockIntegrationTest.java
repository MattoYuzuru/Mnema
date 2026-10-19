package app.mnema.learning.study.attempt;

import app.mnema.learning.onboarding.WeeklyRhythm;
import app.mnema.learning.usage.Bucket;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The lock order of the AI assessment against the weekly rhythm (OB-401): paying for the grade may publish {@code USAGE_EXHAUSTED}, which takes the owner's notification cursor row until
 * the commit, and concluding the answer may complete the session and take the rhythm lock; a rhythm writer of the same account holds that lock and publishes {@code RHYTHM_WEEK}
 * (the cursor) at its commit. So the assessment takes the rhythm lock before it pays. A writer holds the lock here and the grade is released into it: while the grade queues for the
 * lock it must hold nothing of the cursor (a {@code NOWAIT} probe of the row succeeds), and once the writer commits the grade is concluded, the usage notice published, and no
 * deadlock was needed to get there.
 */
@TestPropertySource(properties = {"learning.onboarding.enabled=true", "learning.onboarding.eligible-from=2026-01-01T00:00:00Z", "learning.onboarding.rhythm.enabled=true"})
class AssessmentRhythmLockIntegrationTest extends AssessmentIntegrationTest {
    @Autowired private WeeklyRhythm rhythm;

    private long waitingForAnAdvisoryLock() {
        return jdbc.sql("SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND NOT granted").query(Long.class).single();
    }

    @Test
    void aGradeQueuedForTheRhythmLockHoldsNothingOfTheOwnersNotificationCursor() throws Exception {
        Case learner = issued();
        UUID actor = learner.actor();
        TransactionTemplate tx = new TransactionTemplate(transactions);
        // the allowance is one check from its day's end: the grade's payment crosses it and publishes USAGE_EXHAUSTED
        for (int index = 0; index < 39; index++) {
            int number = index;
            tx.executeWithoutResult(status -> ledger.consume(actor, Bucket.ASSESSMENT, 1, "almost:" + actor + ":" + number, null));
        }
        // the cursor row exists (and is committed), so that the probe below can tell a held lock from an absent row
        jdbc.sql("INSERT INTO app_learning.notification_cursor(owner_id) VALUES (:a) ON CONFLICT (owner_id) DO NOTHING").param("a", actor).update();
        UUID attempt = UUID.randomUUID();
        submit(learner, attempt, "Планы [[fake:block]] [[stub:assess-complete]]");
        assertThat(provider.entered.await(10, TimeUnit.SECONDS)).isTrue();
        // a rhythm writer of the account: it holds the lock, and would publish at its commit
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        CompletableFuture<Void> writer = CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
            rhythm.lock(actor);
            locked.countDown();
            try {
                commit.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }));
        try {
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            long before = waitingForAnAdvisoryLock();
            provider.release.countDown();
            eventually(() -> waitingForAnAdvisoryLock() > before ? Boolean.TRUE : null, "the grade waits for the rhythm lock");

            // the grade is queued for the lock: the owner's cursor row is free (a held one would fail the probe at once)
            Boolean free = tx.execute(status -> jdbc.sql("SELECT true FROM app_learning.notification_cursor WHERE owner_id=:a FOR UPDATE NOWAIT")
                    .param("a", actor).query(Boolean.class).single());
            assertThat(free).isTrue();
            assertThat(state(attempt)).as("not concluded before the writer lets go").isEqualTo("ASSESSING");
        } finally {
            commit.countDown();
        }
        writer.get(10, TimeUnit.SECONDS);

        JsonNode view = settled(learner, attempt);
        assertThat(view.path("status").stringValue(null)).isEqualTo("ASSESSED");
        assertThat(state(attempt)).isEqualTo("DONE");
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.notification WHERE owner_id=:a AND kind='USAGE_EXHAUSTED'").param("a", actor).query(Long.class).single())
                .as("the payment crossed the allowance and published").isOne();
    }
}
