package app.mnema.learning.onboarding;

import app.mnema.learning.notification.NotificationKind;
import app.mnema.learning.notification.NotificationPublisher;
import app.mnema.learning.notification.NotificationRoute;
import app.mnema.learning.fork.ForkWorlds.Source;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The recorder on a real PostgreSQL (OB-301): the single-statement upsert (target, saturation, stage order, the time written once), the transaction it joins (a rolled-back action leaves
 * no milestone and no notification), parallel identical events (one row, one notification), the order of the notification (last write, after every other one) and its gate
 * (published only for a visible milestone while the tour is switched on; the row is written either way). The context is the one of the sample-deck tests: tour on, speech to text on,
 * the AI assessment, the Workshop and the catalogue off, so 9 of the 13 milestones are visible.
 */
class MilestoneRecorderIntegrationTest extends SampleDeckIntegrationTest {
    /** Visible in this context: every milestone but AI_EXPLANATION, WORKSHOP_APPROVED, RHYTHM_WEEK and COMMUNITY_COPY. */
    private static final int TOTAL = 9;

    @Autowired private MilestoneRecorder recorder;
    @Autowired private NotificationPublisher publisher;
    @Autowired private MeterRegistry meters;

    private record Stored(int progress, Instant achievedAt, Instant firstAt, String source) { }

    private Stored stored(UUID account, MilestoneKey key) {
        return jdbc.sql("SELECT progress, achieved_at, first_at, source FROM app_learning.account_milestone WHERE account_id = :a AND milestone_key = :k")
                .param("a", account).param("k", key.name())
                .query((row, ignored) -> new Stored(row.getInt("progress"), row.getTimestamp("achieved_at") == null ? null : row.getTimestamp("achieved_at").toInstant(),
                        row.getTimestamp("first_at").toInstant(), row.getString("source"))).optional().orElse(null);
    }

    private List<String> announced(UUID account) {
        return jdbc.sql("SELECT dedupe_key || ' ' || params::text || ' ' || route || ' ' || severity FROM app_learning.notification WHERE owner_id = :a AND kind = 'PATH_MILESTONE' ORDER BY seq")
                .param("a", account).query(String.class).list();
    }

    private double metric(MilestoneKey key) {
        return meters.counter("mnema_path_milestone_total", "key", key.name(), "source", "LIVE").count();
    }

    private void inTransaction(Runnable work) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> work.run());
    }

    @Test
    void theFirstCallReachesAMilestoneOfTargetOneOnceAndAnnouncesItOnce() {
        UUID account = UUID.randomUUID();
        double before = metric(MilestoneKey.GOAL_TOLD);

        inTransaction(() -> recorder.reach(account, MilestoneKey.GOAL_TOLD, 1));

        Stored row = stored(account, MilestoneKey.GOAL_TOLD);
        assertThat(row.progress()).isOne();
        assertThat(row.achievedAt()).isNotNull().isEqualTo(row.firstAt());
        assertThat(row.source()).isEqualTo("LIVE");
        assertThat(announced(account)).containsExactly("path:GOAL_TOLD {\"key\": \"GOAL_TOLD\", \"done\": 1, \"total\": " + TOTAL + "} PATH INFO");
        assertThat(metric(MilestoneKey.GOAL_TOLD)).isEqualTo(before + 1);

        // the same event again, in this transaction and in a later one: the time is written once and nothing is announced or counted again
        inTransaction(() -> {
            recorder.reach(account, MilestoneKey.GOAL_TOLD, 1);
            recorder.reach(account, MilestoneKey.GOAL_TOLD, 1);
        });
        assertThat(stored(account, MilestoneKey.GOAL_TOLD)).isEqualTo(row);
        assertThat(announced(account)).hasSize(1);
        assertThat(metric(MilestoneKey.GOAL_TOLD)).isEqualTo(before + 1);
        assertThat(count("SELECT count(*) FROM app_learning.account_milestone WHERE account_id = :p0", account)).isOne();
    }

    @Test
    void aCounterGrowsToTheTargetAndSaturatesThereWithTheTimeSetAtTheLastStep() {
        UUID account = UUID.randomUUID();
        double before = metric(MilestoneKey.OWN_EXERCISES);
        List<Integer> progress = new ArrayList<>();
        List<Boolean> achieved = new ArrayList<>();
        for (int step = 0; step < 6; step++) {
            inTransaction(() -> recorder.increment(account, MilestoneKey.OWN_EXERCISES));
            Stored row = stored(account, MilestoneKey.OWN_EXERCISES);
            progress.add(row.progress());
            achieved.add(row.achievedAt() != null);
        }
        assertThat(progress).containsExactly(1, 2, 3, 3, 3, 3);
        assertThat(achieved).containsExactly(false, false, true, true, true, true);
        assertThat(announced(account)).containsExactly("path:OWN_EXERCISES {\"key\": \"OWN_EXERCISES\", \"done\": 1, \"total\": " + TOTAL + "} PATH INFO");
        assertThat(metric(MilestoneKey.OWN_EXERCISES)).isEqualTo(before + 1);
        Stored done = stored(account, MilestoneKey.OWN_EXERCISES);
        assertThat(done.achievedAt()).isAfter(done.firstAt());
    }

    @Test
    void anIncrementInsideOneTransactionCountsEveryStep() {
        UUID account = UUID.randomUUID();
        inTransaction(() -> {
            recorder.increment(account, MilestoneKey.OWN_EXERCISES);
            recorder.increment(account, MilestoneKey.OWN_EXERCISES);
        });
        assertThat(stored(account, MilestoneKey.OWN_EXERCISES).progress()).isEqualTo(2);
        assertThat(stored(account, MilestoneKey.OWN_EXERCISES).achievedAt()).isNull();
        assertThat(announced(account)).as("a partial step is not announced").isEmpty();
    }

    @Test
    void theStagesOfANoteNeverGoBackAndALevelAboveTheTargetIsTheTarget() {
        UUID account = UUID.randomUUID();
        // sorted out before it was ever recorded as saved: the second stage is the milestone
        inTransaction(() -> recorder.reach(account, MilestoneKey.CAPTURE_NOTE, 2));
        assertThat(stored(account, MilestoneKey.CAPTURE_NOTE)).extracting(Stored::progress).isEqualTo(2);
        assertThat(stored(account, MilestoneKey.CAPTURE_NOTE).achievedAt()).isNotNull();
        // a later note that is only saved changes nothing
        inTransaction(() -> recorder.reach(account, MilestoneKey.CAPTURE_NOTE, 1));
        assertThat(stored(account, MilestoneKey.CAPTURE_NOTE).progress()).isEqualTo(2);
        assertThat(announced(account)).hasSize(1);

        UUID staged = UUID.randomUUID();
        inTransaction(() -> recorder.reach(staged, MilestoneKey.CAPTURE_NOTE, 1));
        assertThat(stored(staged, MilestoneKey.CAPTURE_NOTE).progress()).isOne();
        assertThat(stored(staged, MilestoneKey.CAPTURE_NOTE).achievedAt()).as("stage 1 is progress, not the milestone").isNull();
        assertThat(announced(staged)).isEmpty();
        inTransaction(() -> recorder.reach(staged, MilestoneKey.CAPTURE_NOTE, 1));
        assertThat(stored(staged, MilestoneKey.CAPTURE_NOTE).progress()).isOne();
        inTransaction(() -> recorder.reach(staged, MilestoneKey.CAPTURE_NOTE, 7));
        assertThat(stored(staged, MilestoneKey.CAPTURE_NOTE).progress()).isEqualTo(2);
        assertThat(stored(staged, MilestoneKey.CAPTURE_NOTE).achievedAt()).isNotNull();
        assertThat(announced(staged)).hasSize(1);
        assertThatThrownBy(() -> inTransaction(() -> recorder.reach(UUID.randomUUID(), MilestoneKey.GOAL_TOLD, 0))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aRolledBackActionLeavesNoMilestoneNoNotificationAndNoCount() {
        UUID account = UUID.randomUUID();
        double before = metric(MilestoneKey.GOAL_TOLD);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            recorder.reach(account, MilestoneKey.GOAL_TOLD, 1);
            recorder.increment(account, MilestoneKey.OWN_EXERCISES);
            status.setRollbackOnly();
        });
        assertThat(stored(account, MilestoneKey.GOAL_TOLD)).isNull();
        assertThat(stored(account, MilestoneKey.OWN_EXERCISES)).isNull();
        assertThat(announced(account)).isEmpty();
        assertThat(metric(MilestoneKey.GOAL_TOLD)).isEqualTo(before);

        // an action that fails after the call: the exception rolls everything back
        assertThatThrownBy(() -> inTransaction(() -> {
            recorder.reach(account, MilestoneKey.FIRST_DECK, 1);
            throw new IllegalStateException("the domain write failed");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(stored(account, MilestoneKey.FIRST_DECK)).isNull();
        assertThat(announced(account)).isEmpty();
        assertThat(count("SELECT count(*) FROM app_learning.notification_cursor WHERE owner_id = :p0", account)).as("the publisher took no lock").isZero();

        // and the same events afterwards behave as the first ones
        inTransaction(() -> recorder.reach(account, MilestoneKey.GOAL_TOLD, 1));
        assertThat(stored(account, MilestoneKey.GOAL_TOLD).achievedAt()).isNotNull();
        assertThat(announced(account)).hasSize(1);
    }

    @Test
    void aRecorderCallOutsideATransactionFailsLoudlyAndWritesNothing() {
        UUID account = UUID.randomUUID();
        assertThatThrownBy(() -> recorder.reach(account, MilestoneKey.GOAL_TOLD, 1)).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> recorder.increment(account, MilestoneKey.OWN_EXERCISES)).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> recorder.reachInUserDeck(account, UUID.randomUUID(), MilestoneKey.CAPTURE_NOTE, 1)).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> recorder.sessionCompleted(account, "SCHEDULED", java.time.LocalDate.now())).isInstanceOf(IllegalTransactionStateException.class);
        assertThat(count("SELECT count(*) FROM app_learning.account_milestone WHERE account_id = :p0", account)).isZero();
    }

    @Test
    void parallelIdenticalEventsGiveOneRowAndOneNotification() throws Exception {
        UUID account = UUID.randomUUID();
        double before = metric(MilestoneKey.FIRST_DECK);
        int threads = 12;
        CountDownLatch go = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<?>> runs = new ArrayList<>();
            for (int index = 0; index < threads; index++) {
                runs.add(pool.submit(() -> {
                    go.await();
                    inTransaction(() -> recorder.reach(account, MilestoneKey.FIRST_DECK, 1));
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> run : runs) run.get();
        }
        assertThat(count("SELECT count(*) FROM app_learning.account_milestone WHERE account_id = :p0", account)).isOne();
        assertThat(stored(account, MilestoneKey.FIRST_DECK).achievedAt()).isNotNull();
        assertThat(announced(account)).hasSize(1);
        assertThat(count("SELECT count(*) FROM app_learning.notification WHERE owner_id = :p0", account)).isOne();
        assertThat(metric(MilestoneKey.FIRST_DECK)).isEqualTo(before + 1);
    }

    @Test
    void parallelIncrementsOfACounterSaturateAtTheTargetAndAnnounceOnce() throws Exception {
        UUID account = UUID.randomUUID();
        int threads = 10;
        CountDownLatch go = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<?>> runs = new ArrayList<>();
            for (int index = 0; index < threads; index++) {
                runs.add(pool.submit(() -> {
                    go.await();
                    inTransaction(() -> recorder.increment(account, MilestoneKey.OWN_EXERCISES));
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> run : runs) run.get();
        }
        assertThat(stored(account, MilestoneKey.OWN_EXERCISES).progress()).isEqualTo(3);
        assertThat(stored(account, MilestoneKey.OWN_EXERCISES).achievedAt()).isNotNull();
        assertThat(announced(account)).hasSize(1);
    }

    @Test
    void severalMilestonesOfOneTransactionAreSeparateNotificationsWithTheSameCountsAndTheyAreTheLastWrite() {
        UUID account = UUID.randomUUID();
        inTransaction(() -> {
            recorder.reach(account, MilestoneKey.GOAL_TOLD, 1);
            recorder.reach(account, MilestoneKey.FIRST_DECK, 1);
            // another writer of the same transaction that runs after the recorder
            recorder.reach(account, MilestoneKey.OWN_MATERIAL, 1);
        });
        assertThat(announced(account)).containsExactly(
                "path:GOAL_TOLD {\"key\": \"GOAL_TOLD\", \"done\": 3, \"total\": " + TOTAL + "} PATH INFO",
                "path:FIRST_DECK {\"key\": \"FIRST_DECK\", \"done\": 3, \"total\": " + TOTAL + "} PATH INFO",
                "path:OWN_MATERIAL {\"key\": \"OWN_MATERIAL\", \"done\": 3, \"total\": " + TOTAL + "} PATH INFO");
        // done counts what is achieved: a partial milestone is not
        UUID other = UUID.randomUUID();
        inTransaction(() -> {
            recorder.reach(other, MilestoneKey.CAPTURE_NOTE, 1);
            recorder.reach(other, MilestoneKey.TWO_DAYS, 2);
        });
        assertThat(announced(other)).containsExactly("path:TWO_DAYS {\"key\": \"TWO_DAYS\", \"done\": 1, \"total\": " + TOTAL + "} PATH INFO");
    }

    @Test
    void theNotificationIsPublishedWhenTheOwnerCursorIsAlreadyLockedByAnotherPublisherOfTheSameTransaction() {
        UUID account = UUID.randomUUID();
        // a producer that published earlier in the same transaction: the recorder's publication follows it, with the next sequence number
        inTransaction(() -> {
            publisher.publish(account, NotificationKind.PATH_MILESTONE, "path:OTHER", java.util.Map.of("key", "OTHER", "done", 0, "total", 1), NotificationRoute.PATH);
            recorder.reach(account, MilestoneKey.GOAL_TOLD, 1);
        });
        assertThat(jdbc.sql("SELECT dedupe_key FROM app_learning.notification WHERE owner_id = :a ORDER BY seq").param("a", account).query(String.class).list())
                .containsExactly("path:OTHER", "path:GOAL_TOLD");
    }

    @Test
    void aMilestoneTheConfigurationHidesIsRecordedButNotAnnounced() {
        UUID account = UUID.randomUUID();
        double before = metric(MilestoneKey.COMMUNITY_COPY);
        // the catalogue is off in this context: the copy milestone is hidden from the path, but it is a fact of the account's history
        inTransaction(() -> recorder.reach(account, MilestoneKey.COMMUNITY_COPY, 1));
        assertThat(stored(account, MilestoneKey.COMMUNITY_COPY).achievedAt()).isNotNull();
        assertThat(announced(account)).isEmpty();
        assertThat(metric(MilestoneKey.COMMUNITY_COPY)).as("the fact is counted").isEqualTo(before + 1);
        JsonNode view = json(onboarding(account, NONE));
        List<String> hidden = new ArrayList<>();
        view.path("path").path("hidden").forEach(key -> hidden.add(key.stringValue()));
        List<String> shown = new ArrayList<>();
        view.path("milestones").forEach(milestone -> shown.add(milestone.path("key").stringValue()));
        assertThat(hidden).containsExactly("AI_EXPLANATION", "WORKSHOP_APPROVED", "RHYTHM_WEEK", "COMMUNITY_COPY");
        assertThat(shown).doesNotContain("COMMUNITY_COPY").hasSize(TOTAL);
        assertThat(view.path("path").path("total").intValue()).isEqualTo(TOTAL);
        assertThat(view.path("path").path("done").intValue()).as("a hidden milestone counts in neither done nor total").isZero();
    }

    @Test
    void aMilestoneOfADeckIsRecordedOnlyForTheAccountsOwnDeckOfPurposeUser() {
        UUID owner = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        UUID own = worlds.shared.deck(owner, "Моя");
        Source source = template(3, 1);
        UUID sample = sample(owner);
        inTransaction(() -> recorder.reachInUserDeck(owner, sample, MilestoneKey.CAPTURE_NOTE, 1));
        assertThat(stored(owner, MilestoneKey.CAPTURE_NOTE)).as("the sample deck").isNull();
        inTransaction(() -> recorder.reachInUserDeck(owner, source.deck(), MilestoneKey.CAPTURE_NOTE, 1));
        assertThat(stored(owner, MilestoneKey.CAPTURE_NOTE)).as("somebody else's deck").isNull();
        inTransaction(() -> recorder.reachInUserDeck(owner, UUID.randomUUID(), MilestoneKey.CAPTURE_NOTE, 1));
        assertThat(stored(owner, MilestoneKey.CAPTURE_NOTE)).as("no such deck").isNull();
        inTransaction(() -> recorder.reachInUserDeck(stranger, own, MilestoneKey.CAPTURE_NOTE, 1));
        assertThat(stored(stranger, MilestoneKey.CAPTURE_NOTE)).as("the deck is the owner's, not the caller's").isNull();
        inTransaction(() -> recorder.reachInUserDeck(owner, own, MilestoneKey.CAPTURE_NOTE, 1));
        assertThat(stored(owner, MilestoneKey.CAPTURE_NOTE).progress()).isOne();
        inTransaction(() -> recorder.reachInUserDeck(owner, own, MilestoneKey.CAPTURE_NOTE, 2));
        assertThat(stored(owner, MilestoneKey.CAPTURE_NOTE).achievedAt()).isNotNull();
        // the own deck made the first deck, the note is the second announcement
        assertThat(announced(owner)).extracting(text -> text.substring(0, text.indexOf(' '))).containsExactly("path:FIRST_DECK", "path:CAPTURE_NOTE");
    }
}
