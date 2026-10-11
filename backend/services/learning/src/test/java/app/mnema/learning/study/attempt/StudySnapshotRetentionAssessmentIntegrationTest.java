package app.mnema.learning.study.attempt;

import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.study.retention.StudyRetentionPass;
import app.mnema.learning.study.retention.StudyRetentionSettings;
import app.mnema.learning.study.retention.StudySnapshotRetention;
import org.springframework.transaction.support.TransactionTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the snapshot retention (#460, V59) must not change in the AI assessment: the first-attempt cap of the strictness, and what a
 * dispute does on a fresh attempt and on one whose presentation is gone.
 */
class StudySnapshotRetentionAssessmentIntegrationTest extends AssessmentIntegrationTest {
    private static final String SHALLOW = "Планы выполнения [[stub:assess-shallow]]";
    @Autowired private StudyRetentionPass pass;
    @Autowired private AssessmentRepository assessmentRows;
    @Autowired private StudySnapshotRetention snapshots;
    @Autowired private StudyRetentionSettings settings;

    private UUID exerciseOf(Case learner) {
        return jdbc.sql("SELECT exercise_id FROM app_learning.study_presentation WHERE presentation_id=:p")
                .param("p", learner.issued().id()).query(UUID.class).single();
    }

    private void ageAndPurge(Case learner) {
        jdbc.sql("UPDATE app_learning.study_session SET created_at=statement_timestamp()-interval '9 days' WHERE account_id=:a")
                .param("a", learner.actor()).update();
        pass.run();
        assertThat(count("study_presentation", learner.actor())).as("the snapshot is gone").isZero();
    }

    private long attempts(Case learner) {
        return jdbc.sql("SELECT COALESCE(sum(attempts),0) FROM app_learning.study_exercise_attempt WHERE account_id=:a")
                .param("a", learner.actor()).query(Long.class).single();
    }

    @Test
    void theFirstAttemptCapOfTheStrictnessIsTheSameBeforeAndAfterAPurge() {
        // control: three attempts at one exercise, the third is graded at S3 (a correct streak of two) with nothing purged
        Case control = issued();
        UUID one = UUID.randomUUID();
        submit(control, one, SHALLOW);
        settled(control, one);
        Case controlSecond = sameObjective(control);
        UUID controlSecondAttempt = UUID.randomUUID();
        submit(controlSecond, controlSecondAttempt, SHALLOW);
        settled(controlSecond, controlSecondAttempt);
        Case controlThird = sameObjective(controlSecond);
        UUID controlAttempt = UUID.randomUUID();
        submit(controlThird, controlAttempt, SHALLOW);
        JsonNode expected = settled(controlThird, controlAttempt);
        assertThat(expected.path("feedback").path("assessment").path("strictness").stringValue(null)).isEqualTo("S3");

        // the same story, with the snapshot of the first two attempts purged before the third
        Case learner = issued();
        UUID exercise = exerciseOf(learner);
        UUID first = UUID.randomUUID();
        submit(learner, first, SHALLOW);
        settled(learner, first);
        Case second = sameObjective(learner);
        UUID secondAttempt = UUID.randomUUID();
        submit(second, secondAttempt, SHALLOW);
        settled(second, secondAttempt);
        assertThat(attempts(learner)).isEqualTo(2);
        assertThat(assessmentRows.attemptedExercise(learner.actor(), learner.deck(), exercise, 0)).isTrue();

        ageAndPurge(learner);
        assertThat(attempts(learner)).as("the fact outlives the presentations").isEqualTo(2);
        assertThat(assessmentRows.attemptedExercise(learner.actor(), learner.deck(), exercise, 0)).isTrue();
        assertThat(assessmentRows.attemptedExercise(learner.actor(), learner.deck(), exercise, 1)).as("another epoch").isFalse();
        assertThat(assessmentRows.attemptedExercise(learner.actor(), learner.deck(), UUID.randomUUID(), 0)).as("another exercise").isFalse();
        assertThat(assessmentRows.attemptedExercise(UUID.randomUUID(), learner.deck(), exercise, 0)).as("another learner").isFalse();

        Case third = sameObjective(second);
        UUID thirdAttempt = UUID.randomUUID();
        submit(third, thirdAttempt, SHALLOW);
        JsonNode actual = settled(third, thirdAttempt);
        for (String field : new String[] {"strictness", "judgement"}) {
            assertThat(actual.path("feedback").path("assessment").path(field).stringValue(null))
                    .as(field).isEqualTo(expected.path("feedback").path("assessment").path(field).stringValue(null));
        }
        assertThat(actual.path("evidence").path("result").stringValue(null)).isEqualTo(expected.path("evidence").path("result").stringValue(null));
        assertThat(actual.path("evidence").path("evidenceClass").stringValue(null))
                .isEqualTo(expected.path("evidence").path("evidenceClass").stringValue(null));
        assertThat(actual.path("transition").path("afterLevel").intValue()).isEqualTo(expected.path("transition").path("afterLevel").intValue());
        assertThat(actual.path("feedback").path("assessment").path("strictness").stringValue(null)).isEqualTo("S3");
        assertThat(attempts(learner)).isEqualTo(3);
    }

    @Test
    void aDisputeOnAFreshAttemptWorksAndLowersTheFirstAttemptFactExactlyAsTheTombstoneStatusDid() {
        Case learner = issued();
        UUID exercise = exerciseOf(learner);
        UUID attempt = UUID.randomUUID();
        submit(learner, attempt, "Планы выполнения [[stub:assess-complete]]");
        settled(learner, attempt);
        assertThat(assessmentRows.attemptedExercise(learner.actor(), learner.deck(), exercise, 0)).isTrue();

        AttemptService.SubmitResult disputed = assessments.dispute(learner.actor(), learner.deck(), learner.session(), attempt,
                new AssessmentService.DisputeCommand(UUID.randomUUID(), false));
        assertThat(disputed.outcome().path("disputed").booleanValue()).isTrue();
        // the disputed attempt is a NOT_ASSESSED receipt: it no longer counts as an attempt at the exercise
        assertThat(assessmentRows.attemptedExercise(learner.actor(), learner.deck(), exercise, 0)).isFalse();
        assertThat(attempts(learner)).isZero();
    }

    @Test
    void aDisputeLowersACounterOfTwoToOneAndTheExerciseStaysAttempted() {
        Case first = issued();
        UUID exercise = exerciseOf(first);
        UUID one = UUID.randomUUID();
        submit(first, one, "Планы выполнения [[stub:assess-complete]]");
        settled(first, one);
        Case second = sameObjective(first);
        UUID two = UUID.randomUUID();
        submit(second, two, "Планы выполнения [[stub:assess-complete]]");
        settled(second, two);
        assertThat(attempts(first)).isEqualTo(2);
        assessments.dispute(second.actor(), second.deck(), second.session(), two, new AssessmentService.DisputeCommand(UUID.randomUUID(), false));
        assertThat(attempts(first)).isOne();
        assertThat(assessmentRows.attemptedExercise(first.actor(), first.deck(), exercise, 0)).isTrue();
    }

    @Test
    void aDisputeOnAPurgedPresentationIsTheOpaque404ButAnExactRetryStillAnswers() {
        Case learner = issued();
        UUID attempt = UUID.randomUUID();
        AttemptService.SubmitResult submitted = submit(learner, attempt, "Планы выполнения [[stub:assess-complete]]");
        assertThat(submitted.accepted()).isTrue();
        JsonNode outcome = settled(learner, attempt);
        assertThat(outcome.path("status").stringValue(null)).isEqualTo("ASSESSED");
        ageAndPurge(learner);

        AssessmentService.DisputeCommand command = new AssessmentService.DisputeCommand(UUID.randomUUID(), false);
        assertThatThrownBy(() -> assessments.dispute(learner.actor(), learner.deck(), learner.session(), attempt, command))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(jdbc.sql("SELECT status FROM app_learning.study_attempt_tombstone WHERE attempt_id=:a").param("a", attempt)
                .query(String.class).single()).as("nothing was taken back").isEqualTo("ASSESSED");
        assertThat(count("study_transition", learner.actor())).isOne();
        // the stored outcome still answers an exact retry and a read of the attempt
        AttemptService.SubmitResult retry = submit(learner, attempt, "Планы выполнения [[stub:assess-complete]]");
        assertThat(retry.replayed()).isTrue();
        assertThat(retry.outcome().path("status").stringValue(null)).isEqualTo("ASSESSED");
    }

    @Test
    void aPurgeSkipsTheSessionWhileADisputeHoldsItAndTakesItAfterwards() throws Exception {
        Case learner = issued();
        UUID attempt = UUID.randomUUID();
        submit(learner, attempt, "Планы выполнения [[stub:assess-complete]]");
        settled(learner, attempt);
        UUID exercise = exerciseOf(learner);
        jdbc.sql("UPDATE app_learning.study_session SET created_at=statement_timestamp()-interval '9 days' WHERE account_id=:a")
                .param("a", learner.actor()).update();
        TransactionTemplate tx = new TransactionTemplate(transactions);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            tx.executeWithoutResult(status -> {
                // the dispute reads the presentation under the session lock and keeps it until this transaction ends
                assessments.dispute(learner.actor(), learner.deck(), learner.session(), attempt,
                        new AssessmentService.DisputeCommand(UUID.randomUUID(), false));
                try {
                    executor.submit(() -> tx.execute(inner -> snapshots.purgeSessions())).get(20, java.util.concurrent.TimeUnit.SECONDS);
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
                assertThat(jdbc.sql("SELECT presentations_purged_at IS NULL FROM app_learning.study_session WHERE session_id=:s")
                        .param("s", learner.session()).query(Boolean.class).single()).as("skipped, not waited for").isTrue();
                assertThat(count("study_presentation", learner.actor())).isOne();
            });
        }
        // the dispute committed with everything it needs; the next pass purges the session
        assertThat(assessmentRows.attemptedExercise(learner.actor(), learner.deck(), exercise, 0)).isFalse();
        pass.run();
        assertThat(count("study_presentation", learner.actor())).isZero();
        assertThat(jdbc.sql("SELECT status FROM app_learning.study_attempt_tombstone WHERE attempt_id=:a").param("a", attempt)
                .query(String.class).single()).isEqualTo("NOT_ASSESSED");
    }

    @Test
    void theDisputeBookkeepingDoesNotNeedThePresentationAnyMore() {
        Case learner = issued();
        UUID attempt = UUID.randomUUID();
        submit(learner, attempt, "Планы выполнения [[stub:assess-complete]]");
        settled(learner, attempt);
        UUID exercise = exerciseOf(learner);
        assertThat(attempts(learner)).isOne();
        ageAndPurge(learner);
        // the presentation is gone: the counter and the receipt are still handled from what the caller loaded earlier
        assessmentRows.markDisputed(attempt, JSON.createObjectNode().put("status", "NOT_ASSESSED"), learner.actor(), learner.deck(), exercise, 0);
        assertThat(attempts(learner)).isZero();
        assertThat(assessmentRows.attemptedExercise(learner.actor(), learner.deck(), exercise, 0)).isFalse();
        assertThat(jdbc.sql("SELECT status FROM app_learning.study_attempt_tombstone WHERE attempt_id=:a").param("a", attempt)
                .query(String.class).single()).isEqualTo("NOT_ASSESSED");
    }

    @Test
    void theBacklogGaugeIsThereWhileTheSwitchIsOffSoTheBacklogCanBeSeenBeforeEnablingIt() {
        assertThat(settings.enabled()).as("the test configuration turns the switch off").isFalse();
        Case learner = issued();
        jdbc.sql("UPDATE app_learning.study_session SET created_at=statement_timestamp()-interval '9 days' WHERE account_id=:a")
                .param("a", learner.actor()).update();
        var backlog = meters.get("mnema_study_retention_backlog_sessions").gauge();
        // the gauge caches for 5 seconds; a new read after the cache window sees the session
        Double seen = eventually(() -> backlog.value() >= 1 ? backlog.value() : null, "the backlog gauge counts the old session");
        assertThat(seen).isGreaterThanOrEqualTo(1.0);
        assertThat(meters.get("mnema_study_retention_oldest_unpurged_age_seconds").gauge().value()).isGreaterThanOrEqualTo(0.0);
        pass.run();
    }
}
