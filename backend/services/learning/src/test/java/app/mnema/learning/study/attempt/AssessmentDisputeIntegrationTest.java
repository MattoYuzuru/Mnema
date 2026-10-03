package app.mnema.learning.study.attempt;

import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.platform.idempotency.IdempotencyConflictException;
import app.mnema.learning.support.StudyFixtures;
import app.mnema.learning.support.StudyFixtures.Issued;
import app.mnema.learning.support.StudyFixtures.Material;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static app.mnema.learning.support.StudyFixtures.blocks;
import static app.mnema.learning.support.StudyFixtures.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * «Оспорить оценку»: the AI evidence is taken back by an append-only compensating transition, only while it is the last
 * transition of its objective; the dispute is counted for the owner without the answer unless the learner shares it.
 */
class AssessmentDisputeIntegrationTest extends AssessmentIntegrationTest {

    private AssessmentService.DisputeCommand dispute(boolean share) {
        return new AssessmentService.DisputeCommand(UUID.randomUUID(), share);
    }

    private record Progress(int level, int streak, int lapses, long sequence) { }

    private Progress state(Case learner) {
        return jdbc.sql("SELECT level,correct_streak,lapse_count,transition_sequence FROM app_learning.study_state WHERE account_id=:a")
                .param("a", learner.actor()).query((row, number) -> new Progress(row.getInt("level"), row.getInt("correct_streak"),
                        row.getInt("lapse_count"), row.getLong("transition_sequence"))).single();
    }

    @Test
    void aDisputeRestoresTheBeforeStateWithACompensatingTransitionAndKeepsHistory() {
        Case learner = issued();
        Progress before = state(learner);
        UUID attempt = UUID.randomUUID();
        submit(learner, attempt, "Планы выполнения [[stub:assess-complete]]");
        JsonNode graded = settled(learner, attempt);
        assertThat(graded.path("transition").path("afterLevel").intValue()).isEqualTo(1);
        assertThat(state(learner)).isEqualTo(new Progress(1, 1, 0, 1));

        AssessmentService.DisputeCommand command = dispute(false);
        AttemptService.SubmitResult result = assessments.dispute(learner.actor(), learner.deck(), learner.session(), attempt, command);
        JsonNode outcome = result.outcome();
        assertThat(result.replayed()).isFalse();
        assertThat(outcome.path("status").stringValue(null)).isEqualTo("NOT_ASSESSED");
        assertThat(outcome.path("disputed").booleanValue()).isTrue();
        assertThat(outcome.path("evidence").isNull()).isTrue();
        assertThat(outcome.path("transition").isNull()).isTrue();
        assertThat(outcome.path("feedback").path("reasonCodes")).extracting(JsonNode::stringValue).containsExactly("AI_DISPUTED");
        assertThat(outcome.path("feedback").path("reference").stringValue(null)).isEqualTo(REFERENCE);
        assertThat(outcome.path("feedback").has("assessment")).as("the grade is gone").isFalse();

        // progress did not change: the before-state is back, the history only grew
        assertThat(state(learner)).isEqualTo(new Progress(before.level(), 0, 0, 2));
        var transitions = jdbc.sql("SELECT transition_sequence,kind,attempt_id,compensates_attempt_id,reason_code,before_level,after_level "
                + "FROM app_learning.study_transition WHERE account_id=:a ORDER BY transition_sequence").param("a", learner.actor())
                .query().listOfRows();
        assertThat(transitions).hasSize(2);
        assertThat(transitions.get(0)).containsEntry("kind", "ATTEMPT").containsEntry("attempt_id", attempt);
        assertThat(transitions.get(1)).containsEntry("kind", "COMPENSATION").containsEntry("compensates_attempt_id", attempt)
                .containsEntry("reason_code", "AI_DISPUTED");
        assertThat(((Number) transitions.get(1).get("before_level")).intValue()).isEqualTo(1);
        assertThat(((Number) transitions.get(1).get("after_level")).intValue()).isZero();
        assertThat(transitions.get(1).get("attempt_id")).isNull();
        // the AI evidence stays as the audit trail; the receipt says NOT_ASSESSED
        assertThat(count("study_evidence", learner.actor())).isOne();
        assertThat(jdbc.sql("SELECT status FROM app_learning.study_attempt_tombstone WHERE attempt_id=:id").param("id", attempt)
                .query(String.class).single()).isEqualTo("NOT_ASSESSED");
        // the counts-only journal row, no answer text
        Map<String, Object> row = jdbc.sql("SELECT * FROM app_learning.study_assessment_dispute WHERE attempt_id=:id")
                .param("id", attempt).query().singleRow();
        assertThat(row).containsEntry("strictness", "S1").containsEntry("judgement", "COMPLETE").containsEntry("share_example", false);
        assertThat(row.get("example")).isNull();
        assertThat(row.toString()).doesNotContain("Планы выполнения");

        // reading and replaying show the disputed outcome; the same command replays, another one cannot dispute again
        assertThat(assessments.read(learner.actor(), learner.deck(), learner.session(), attempt)).isEqualTo(outcome);
        assertThat(submit(learner, attempt, "Планы выполнения [[stub:assess-complete]]").outcome()).isEqualTo(outcome);
        AttemptService.SubmitResult replay = assessments.dispute(learner.actor(), learner.deck(), learner.session(), attempt, command);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.outcome()).isEqualTo(outcome);
        assertThatThrownBy(() -> assessments.dispute(learner.actor(), learner.deck(), learner.session(), attempt, dispute(false)))
                .isInstanceOf(DisputeNotAllowedException.class);
        assertThat(count("study_transition", learner.actor())).isEqualTo(2);
    }

    @Test
    void aDisputeIsRefusedOnceALaterTransitionMovedTheObjectiveOn() {
        Case first = issued();
        UUID one = UUID.randomUUID();
        submit(first, one, "Планы выполнения [[stub:assess-complete]]");
        settled(first, one);
        Case second = sameObjective(first);
        UUID two = UUID.randomUUID();
        submit(second, two, "Планы выполнения [[stub:assess-complete]]");
        settled(second, two);
        Progress state = state(first);

        // the first grade is no longer the last transition: taking it back would rewrite history
        assertThatThrownBy(() -> assessments.dispute(first.actor(), first.deck(), first.session(), one, dispute(false)))
                .isInstanceOf(DisputeNotAllowedException.class);
        assertThat(state(first)).isEqualTo(state);
        assertThat(count("study_transition", first.actor())).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.study_assessment_dispute WHERE account_id=:a").param("a", first.actor())
                .query(Long.class).single()).isZero();

        // the last one can be taken back, and the objective returns to the state after the first
        assessments.dispute(second.actor(), second.deck(), second.session(), two, dispute(false));
        assertThat(state(first)).isEqualTo(new Progress(1, 1, 0, 3));
        // the objective is due as it was after the first attempt (its interval), not lost
        assertThat(jdbc.sql("SELECT next_due FROM app_learning.study_state WHERE account_id=:a").param("a", first.actor())
                .query(java.sql.Timestamp.class).single()).isNotNull();
    }

    @Test
    void aDisputeIsRefusedAfterARestartOfTheObjective() {
        Case learner = issued();
        UUID attempt = UUID.randomUUID();
        submit(learner, attempt, "Планы выполнения [[stub:assess-complete]]");
        settled(learner, attempt);
        jdbc.sql("UPDATE app_learning.study_state SET learning_epoch=learning_epoch+1 WHERE account_id=:a")
                .param("a", learner.actor()).update();
        assertThatThrownBy(() -> assessments.dispute(learner.actor(), learner.deck(), learner.session(), attempt, dispute(false)))
                .isInstanceOf(DisputeNotAllowedException.class);
    }

    @Test
    void theAnswerIsSharedOnlyWhenTheLearnerSaysSo() {
        Case learner = issued();
        UUID attempt = UUID.randomUUID();
        submit(learner, attempt, "Планы выполнения запроса [[stub:assess-offtopic]]");
        settled(learner, attempt);
        assessments.dispute(learner.actor(), learner.deck(), learner.session(), attempt, dispute(true));
        Map<String, Object> row = jdbc.sql("SELECT * FROM app_learning.study_assessment_dispute WHERE attempt_id=:id")
                .param("id", attempt).query().singleRow();
        assertThat(row).containsEntry("share_example", true).containsEntry("judgement", "INSUFFICIENT");
        assertThat(row.get("example").toString()).contains("Планы выполнения запроса").contains("INSUFFICIENT");
        // a graded INCORRECT is taken back like any other: the objective returns to level 0 with no lapse counted
        assertThat(state(learner)).isEqualTo(new Progress(0, 0, 0, 2));
    }

    @Test
    void onlyAnAiGradeCanBeDisputedAndOnlyByItsOwner() {
        // a deterministic attempt
        Material material = fixtures.material();
        fixtures.publish(material, fixtures.freeResponse(material, blocks(text("Q")), blocks(), "память"));
        Issued plain = fixtures.issueOne(material);
        UUID typed = UUID.randomUUID();
        attempts.submit(material.actor(), material.deck(), plain.session(), StudyFixtures.attempt(typed, plain,
                StudyFixtures.textResponse("память")));
        assertThatThrownBy(() -> assessments.dispute(material.actor(), material.deck(), plain.session(), typed, dispute(false)))
                .isInstanceOf(DisputeNotAllowedException.class);

        // a self-rated fallback is the learner's own word, not an AI grade
        Case learner = issued();
        UUID attempt = UUID.randomUUID();
        submit(learner, attempt, "Не знаю [[stub:assess-unclear]]");
        settled(learner, attempt);
        assessments.selfRate(learner.actor(), learner.deck(), learner.session(), attempt, AttemptCommand.SelfRating.FULL);
        assertThatThrownBy(() -> assessments.dispute(learner.actor(), learner.deck(), learner.session(), attempt, dispute(false)))
                .isInstanceOf(DisputeNotAllowedException.class);

        // a stranger, an unknown attempt, an attempt that is not terminal
        Case graded = issued();
        UUID real = UUID.randomUUID();
        submit(graded, real, "Планы выполнения [[stub:assess-complete]]");
        settled(graded, real);
        UUID stranger = UUID.randomUUID();
        assertThatThrownBy(() -> assessments.dispute(stranger, graded.deck(), graded.session(), real, dispute(false)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> assessments.dispute(graded.actor(), graded.deck(), graded.session(), UUID.randomUUID(), dispute(false)))
                .isInstanceOf(ResourceNotFoundException.class);
        // the command id of one dispute cannot be used for another attempt
        AssessmentService.DisputeCommand used = dispute(false);
        assessments.dispute(graded.actor(), graded.deck(), graded.session(), real, used);
        Case other = issued();
        UUID otherAttempt = UUID.randomUUID();
        submit(other, otherAttempt, "Планы выполнения [[stub:assess-complete]]");
        settled(other, otherAttempt);
        assertThatThrownBy(() -> assessments.dispute(other.actor(), other.deck(), other.session(), otherAttempt, used))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void aPracticeGradeCanBeDisputedAndChangesNoState() {
        Case first = issued();
        UUID one = UUID.randomUUID();
        submit(first, one, "Планы выполнения [[stub:assess-complete]]");
        settled(first, one);
        Progress state = state(first);
        Case practice = another(first, "PRACTICE");
        UUID attempt = UUID.randomUUID();
        submit(practice, attempt, "Планы выполнения [[stub:assess-complete]]");
        settled(practice, attempt);
        AttemptService.SubmitResult result = assessments.dispute(practice.actor(), practice.deck(), practice.session(), attempt, dispute(false));
        assertThat(result.outcome().path("disputed").booleanValue()).isTrue();
        assertThat(result.outcome().path("canonicalEffects").booleanValue()).isFalse();
        assertThat(state(first)).isEqualTo(state);
        assertThat(count("study_transition", first.actor())).isOne();
        assertThat(List.copyOf(jdbc.sql("SELECT attempt_id FROM app_learning.study_assessment_dispute WHERE account_id=:a")
                .param("a", first.actor()).query(UUID.class).list())).containsExactly(attempt);
    }
}
