package app.mnema.learning.study.attempt;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.platform.idempotency.IdempotencyConflictException;
import app.mnema.learning.support.StudyFixtures;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The submit-to-result path of an {@code ai-semantic} answer: accepted at once ({@code 202}, {@code ASSESSING}), graded outside any
 * transaction, concluded through the baseline reducer; uncertainty, failure, the deadline, the fair-use limit and the learner's
 * own choice all end in self-check with the attempt kept.
 */
class AssessmentFlowIntegrationTest extends AssessmentIntegrationTest {

    @Test
    void aCompleteAnswerIsAcceptedAtOnceGradedAndConcludedThroughTheReducer() {
        Case learner = issued();
        UUID attempt = UUID.randomUUID();
        AttemptService.SubmitResult accepted = submit(learner, attempt, "Он перебирает планы, потом берёт самый дешёвый [[stub:assess-complete]]");

        assertThat(accepted.accepted()).isTrue();
        assertThat(accepted.replayed()).isFalse();
        JsonNode view = accepted.outcome();
        assertThat(view.path("status").stringValue(null)).isEqualTo("ASSESSING");
        assertThat(view.path("retryAfterMs").intValue()).isEqualTo(700);
        assertThat(view.propertyNames()).containsExactlyInAnyOrder("attemptId", "presentationId", "mode", "status", "retryAfterMs");
        // nothing is terminal yet: no receipt, no evidence, no transition
        assertThat(count("study_attempt_tombstone", learner.actor())).isZero();

        JsonNode outcome = settled(learner, attempt);
        assertThat(outcome.path("status").stringValue(null)).isEqualTo("ASSESSED");
        assertThat(outcome.path("evidence").path("result").stringValue(null)).isEqualTo("CORRECT");
        assertThat(outcome.path("evidence").path("evidenceClass").stringValue(null)).isEqualTo("LOW");
        assertThat(outcome.path("evidence").path("reasonCodes")).extracting(JsonNode::stringValue)
                .containsExactly("AI_SEMANTIC", "STRICTNESS_S1", "RUBRIC_V1");
        JsonNode feedback = outcome.path("feedback");
        assertThat(feedback.path("result").stringValue(null)).isEqualTo("CORRECT");
        assertThat(feedback.path("reference").stringValue(null)).isEqualTo(REFERENCE);
        assertThat(feedback.path("referenceContent")).hasSize(1);
        JsonNode assessment = feedback.path("assessment");
        assertThat(assessment.path("strictness").stringValue(null)).isEqualTo("S1");
        assertThat(assessment.path("judgement").stringValue(null)).isEqualTo("COMPLETE");
        assertThat(assessment.path("covered")).hasSize(4);
        assertThat(assessment.path("covered").get(0).path("quote").stringValue(null)).isNotBlank();
        assertThat(assessment.path("missing")).isEmpty();
        assertThat(assessment.path("contradicted")).isEmpty();
        assertThat(assessment.path("nextStricter").booleanValue()).isFalse();
        assertThat(outcome.path("transition").path("beforeLevel").intValue()).isZero();
        assertThat(outcome.path("transition").path("afterLevel").intValue()).isEqualTo(1);

        assertThat(state(attempt)).isEqualTo("DONE");
        assertThat(jdbc.sql("SELECT response IS NULL FROM app_learning.study_assessment WHERE attempt_id=:id").param("id", attempt)
                .query(Boolean.class).single()).as("the answer is kept only in the 30-day raw response").isTrue();
        assertThat(count("study_attempt_tombstone", learner.actor())).isOne();
        assertThat(count("study_evidence", learner.actor())).isOne();
        assertThat(count("study_transition", learner.actor())).isOne();
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.study_raw_response WHERE attempt_id=:id").param("id", attempt)
                .query(Long.class).single()).isOne();
        assertThat(checks(learner.actor())).as("a delivered grade costs one fair-use check").isOne();
        // the first strictness-S1 attempt is a single run
        assertThat(provider.callsOf(attempt)).hasSize(1);
        assertThat(provider.callsOf(attempt).getFirst().temperature()).isEqualTo(0.2);
        // the same attempt id replays the stored outcome
        AttemptService.SubmitResult replay = submit(learner, attempt, "Он перебирает планы, потом берёт самый дешёвый [[stub:assess-complete]]");
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.accepted()).isFalse();
        assertThat(replay.outcome()).isEqualTo(outcome);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.study_attempt_tombstone WHERE account_id=:a").param("a", learner.actor())
                .query(Long.class).single()).isOne();
    }

    @Test
    void thePancakeRecipeIsIncorrectEvenAtTheLenientLevel() {
        Case learner = issued();
        UUID attempt = UUID.randomUUID();
        submit(learner, attempt, "Смешайте муку, молоко и яйца, жарьте блины на сковороде");
        JsonNode outcome = settled(learner, attempt);
        assertThat(outcome.path("evidence").path("result").stringValue(null)).isEqualTo("INCORRECT");
        assertThat(outcome.path("evidence").path("evidenceClass").stringValue(null)).isEqualTo("LOW");
        assertThat(outcome.path("feedback").path("assessment").path("judgement").stringValue(null)).isEqualTo("INSUFFICIENT");
        assertThat(outcome.path("feedback").path("assessment").path("covered")).isEmpty();
        assertThat(outcome.path("feedback").path("assessment").path("missing")).hasSize(4);
    }

    @Test
    void theLenientLevelAcceptsAShallowAnswerAndTheStrictOnesAskForMore() {
        Case first = issued();
        // attempt 1: no assessed attempt yet: S1 accepts (all core points at least partly, one fully)
        UUID one = UUID.randomUUID();
        submit(first, one, "Планы выполнения [[stub:assess-shallow]]");
        JsonNode firstOutcome = settled(first, one);
        assertThat(firstOutcome.path("feedback").path("assessment").path("strictness").stringValue(null)).isEqualTo("S1");
        assertThat(firstOutcome.path("feedback").path("assessment").path("judgement").stringValue(null)).isEqualTo("COMPLETE");
        assertThat(firstOutcome.path("evidence").path("evidenceClass").stringValue(null)).isEqualTo("LOW");
        assertThat(firstOutcome.path("transition").path("afterLevel").intValue()).isEqualTo(1);

        // attempt 2: level 1 is still S1; the lenient policy raises the objective to level 2
        makeDue(first);
        Case second = sameObjective(first);
        UUID two = UUID.randomUUID();
        submit(second, two, "Планы выполнения [[stub:assess-shallow]]");
        JsonNode secondOutcome = settled(second, two);
        assertThat(secondOutcome.path("feedback").path("assessment").path("strictness").stringValue(null)).isEqualTo("S1");
        assertThat(secondOutcome.path("transition").path("afterLevel").intValue()).isEqualTo(2);
        assertThat(secondOutcome.path("feedback").path("assessment").path("nextStricter").booleanValue())
                .as("level 2 is the next, stricter level").isTrue();

        // attempt 3: two correct answers in a row are a streak of 2, which is S3 (level 2 alone would be S2): every core point is
        // needed, so the same answer is only partial, with the stronger evidence class
        makeDue(first);
        Case third = sameObjective(second);
        UUID three = UUID.randomUUID();
        submit(third, three, "Планы выполнения [[stub:assess-shallow]]");
        JsonNode thirdOutcome = settled(third, three);
        assertThat(thirdOutcome.path("feedback").path("assessment").path("strictness").stringValue(null)).isEqualTo("S3");
        assertThat(thirdOutcome.path("feedback").path("assessment").path("judgement").stringValue(null)).isEqualTo("PARTIAL");
        assertThat(thirdOutcome.path("evidence").path("result").stringValue(null)).isEqualTo("PARTIAL");
        assertThat(thirdOutcome.path("evidence").path("evidenceClass").stringValue(null)).isEqualTo("MEDIUM");
        assertThat(thirdOutcome.path("transition").path("afterLevel").intValue()).isEqualTo(1);
        assertThat(thirdOutcome.path("feedback").path("assessment").path("missing")).isNotEmpty();
        // two runs at the strict levels, at the pair temperature
        assertThat(provider.callsOf(three)).hasSize(2).allSatisfy(call -> assertThat(call.temperature()).isEqualTo(0.3));
        assertThat(provider.callsOf(three)).extracting(AssessmentTestConfiguration.Call::attempt).containsExactlyInAnyOrder(1, 2);
    }

    @Test
    void theGraderRunsOutsideAnyTransactionOnTheAssessRouteWithNoPersonalData() {
        Case learner = issued();
        UUID attempt = UUID.randomUUID();
        submit(learner, attempt, "Пишите мне на anna@example.com или +7 912 345 67 89 [[stub:assess-complete]]");
        JsonNode outcome = settled(learner, attempt);
        assertThat(outcome.path("status").stringValue(null)).isEqualTo("ASSESSED");
        AssessmentTestConfiguration.Call call = provider.callsOf(attempt).getFirst();
        assertThat(call.transactionAtCall()).as("no transaction while the model thinks").isFalse();
        assertThat(call.connectionsAtCall()).as("no pooled connection is held either").isZero();
        assertThat(call.route()).isEqualTo(app.mnema.learning.ai.AiRoute.ASSESS);
        assertThat(call.prompt()).doesNotContain("anna@example.com", "912 345").contains("[email]", "[phone]");
        assertThat(call.prompt()).doesNotContain(learner.actor().toString());
        // each call is journaled by the router, with identifiers and counts only
        var rows = jdbc.sql("SELECT * FROM app_learning.ai_provider_call WHERE step_id=:id").param("id", attempt).query().listOfRows();
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst()).containsEntry("capability", "ASSESS").containsEntry("outcome", "OK");
        assertThat(rows.getFirst().toString()).doesNotContain("перебирает", "anna");
    }

    @Test
    void anUncertainGraderSendsTheLearnerToSelfCheckAndTheRatingBecomesTheAttempt() {
        Case learner = issued();
        UUID attempt = UUID.randomUUID();
        submit(learner, attempt, "Не знаю [[stub:assess-unclear]]");
        JsonNode view = settled(learner, attempt);
        assertThat(view.path("status").stringValue(null)).isEqualTo("SELF_CHECK");
        assertThat(view.path("reason").stringValue(null)).isEqualTo("PROVIDER_UNCERTAIN");
        JsonNode selfCheck = view.path("selfCheck");
        assertThat(selfCheck.path("reference").stringValue(null)).isEqualTo(REFERENCE);
        assertThat(selfCheck.path("referenceContent")).hasSize(1);
        assertThat(selfCheck.path("criteria")).hasSize(4);
        assertThat(selfCheck.path("criteria").get(0).propertyNames()).containsExactlyInAnyOrder("criterionId", "description");
        assertThat(state(attempt)).isEqualTo("SELF_CHECK");
        assertThat(count("study_attempt_tombstone", learner.actor())).as("the attempt is not terminal yet").isZero();
        assertThat(checks(learner.actor())).as("an uncertain grade is not charged").isZero();
        // a retry of the submit shows the same state; a different attempt id for the presentation conflicts
        AttemptService.SubmitResult again = submit(learner, attempt, "Не знаю [[stub:assess-unclear]]");
        assertThat(again.accepted()).isTrue();
        assertThat(again.replayed()).isTrue();
        assertThat(again.outcome().path("status").stringValue(null)).isEqualTo("SELF_CHECK");
        assertThatThrownBy(() -> submit(learner, UUID.randomUUID(), "другой ответ")).isInstanceOf(IdempotencyConflictException.class);

        AttemptService.SubmitResult rated = assessments.selfRate(learner.actor(), learner.deck(), learner.session(), attempt,
                AttemptCommand.SelfRating.PARTIAL);
        JsonNode outcome = rated.outcome();
        assertThat(rated.accepted()).isFalse();
        assertThat(outcome.path("status").stringValue(null)).isEqualTo("ASSESSED");
        assertThat(outcome.path("evidence").path("result").stringValue(null)).isEqualTo("PARTIAL");
        assertThat(outcome.path("evidence").path("evidenceClass").stringValue(null)).isEqualTo("LOW");
        assertThat(outcome.path("evidence").path("reasonCodes")).extracting(JsonNode::stringValue)
                .containsExactly("SELF_REPORT", "PARTIAL", "AI_FALLBACK", "PROVIDER_UNCERTAIN");
        assertThat(outcome.path("feedback").path("reference").stringValue(null)).isEqualTo(REFERENCE);
        assertThat(outcome.path("feedback").has("assessment")).isFalse();
        assertThat(state(attempt)).isEqualTo("DONE");
        // one terminal receipt for the presentation, evidence of the self-check evaluator, the reducer ran once
        assertThat(count("study_attempt_tombstone", learner.actor())).isOne();
        assertThat(jdbc.sql("SELECT evaluator_id FROM app_learning.study_evidence WHERE attempt_id=:id").param("id", attempt)
                .query(String.class).single()).isEqualTo("self-check");
        assertThat(count("study_transition", learner.actor())).isOne();
        // an exact retry replays, a different rating conflicts, the original submit replays the outcome
        assertThat(assessments.selfRate(learner.actor(), learner.deck(), learner.session(), attempt,
                AttemptCommand.SelfRating.PARTIAL).replayed()).isTrue();
        assertThatThrownBy(() -> assessments.selfRate(learner.actor(), learner.deck(), learner.session(), attempt,
                AttemptCommand.SelfRating.FULL)).isInstanceOf(IdempotencyConflictException.class);
        assertThat(submit(learner, attempt, "Не знаю [[stub:assess-unclear]]").replayed()).isTrue();
    }

    @Test
    void disagreeingRunsAtAStrictLevelAreUncertainAndTheGarbledFlagOfATypedAnswerIsIgnored() {
        Case first = issued();
        UUID one = UUID.randomUUID();
        submit(first, one, "Планы выполнения [[stub:assess-shallow]]");
        settled(first, one);
        makeDue(first);
        Case second = sameObjective(first);
        UUID two = UUID.randomUUID();
        submit(second, two, "Планы выполнения [[stub:assess-shallow]]");
        settled(second, two);
        makeDue(first);
        // level 2 is S2: two runs; the first says complete, the second off topic
        Case third = sameObjective(second);
        UUID three = UUID.randomUUID();
        submit(third, three, "Что-то [[stub:assess-disagree]]");
        JsonNode view = settled(third, three);
        assertThat(view.path("status").stringValue(null)).isEqualTo("SELF_CHECK");
        assertThat(view.path("reason").stringValue(null)).isEqualTo("PROVIDER_UNCERTAIN");
        assertThat(provider.callsOf(three)).hasSize(2);
        assertThat(count("study_attempt_tombstone", third.actor())).isEqualTo(2);

        // «garbled speech» is a property of a transcript: a typed answer cannot be a recognition error, so the flag is ignored and it is graded
        // (the SPEECH path itself is the policy's: SemanticPolicyTest; no speech-to-text capability exists to publish such an exercise yet)
        Case typed = issued();
        UUID asr = UUID.randomUUID();
        submit(typed, asr, "ну типа планы [[stub:assess-asr]]");
        assertThat(settled(typed, asr).path("status").stringValue(null)).isEqualTo("ASSESSED");
        assertThat(checks(typed.actor())).isOne();
    }

    @Test
    void aProviderFailureOrOutputThatNeverFitsIsSelfCheckAndNeverALearnerError() {
        for (String marker : List.of("[[fake:fail]]", "[[fake:garbage]]", "[[stub:timeout]]")) {
            Case learner = issued();
            UUID attempt = UUID.randomUUID();
            AttemptService.SubmitResult accepted = submit(learner, attempt, "Ответ " + marker);
            assertThat(accepted.outcome().path("status").stringValue(null)).as(marker).isEqualTo("ASSESSING");
            JsonNode view = settled(learner, attempt);
            assertThat(view.path("status").stringValue(null)).as(marker).isEqualTo("SELF_CHECK");
            assertThat(view.path("reason").stringValue(null)).as(marker).isEqualTo("PROVIDER_UNAVAILABLE");
            assertThat(view.toString()).as("no provider internals").doesNotContain("fake_failure", "TIMEOUT", "INVALID_OUTPUT");
            assertThat(state(attempt)).isEqualTo("UNAVAILABLE");
            assertThat(checks(learner.actor())).as(marker).isZero();
            // the attempt is not lost: the learner rates themselves
            JsonNode outcome = assessments.selfRate(learner.actor(), learner.deck(), learner.session(), attempt,
                    AttemptCommand.SelfRating.FULL).outcome();
            assertThat(outcome.path("evidence").path("result").stringValue(null)).isEqualTo("CORRECT");
            assertThat(outcome.path("evidence").path("evidenceClass").stringValue(null)).isEqualTo("LOW");
            assertThat(state(attempt)).isEqualTo("DONE");
        }
        // an invalid answer is repaired once before it is given up on
        Case learner = issued();
        UUID attempt = UUID.randomUUID();
        submit(learner, attempt, "Ответ [[fake:garbage]]");
        settled(learner, attempt);
        assertThat(provider.callsOf(attempt)).extracting(AssessmentTestConfiguration.Call::repair).containsExactly(false, true);
    }

    @Test
    void theLearnerCanChooseSelfCheckAndALateGradeIsDiscarded() throws Exception {
        Case learner = issued();
        UUID attempt = UUID.randomUUID();
        submit(learner, attempt, "Планы [[fake:block]] [[stub:assess-complete]]");
        assertThat(provider.entered.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        // the model is still thinking; at 5 s the client offers «Оценить себя»
        assertThat(assessments.read(learner.actor(), learner.deck(), learner.session(), attempt).path("status").stringValue(null))
                .isEqualTo("ASSESSING");
        JsonNode chosen = assessments.selfCheck(learner.actor(), learner.deck(), learner.session(), attempt);
        assertThat(chosen.path("status").stringValue(null)).isEqualTo("SELF_CHECK");
        assertThat(chosen.path("reason").stringValue(null)).isEqualTo("LEARNER_CHOICE");
        // idempotent
        assertThat(assessments.selfCheck(learner.actor(), learner.deck(), learner.session(), attempt)).isEqualTo(chosen);

        double before = discarded();
        provider.release.countDown();
        // the late grade finds the row no longer ASSESSING: nothing is written
        awaitDiscarded(before);
        assertThat(state(attempt)).isEqualTo("SELF_CHECK");
        assertThat(count("study_attempt_tombstone", learner.actor())).isZero();
        assertThat(count("study_evidence", learner.actor())).isZero();
        assertThat(checks(learner.actor())).isZero();
        assertThat(assessments.selfRate(learner.actor(), learner.deck(), learner.session(), attempt,
                AttemptCommand.SelfRating.NOT_RECALLED).outcome().path("evidence").path("result").stringValue(null))
                .isEqualTo("INCORRECT");
    }

    @Test
    void aSelfRatingWhileTheModelStillGradesIsAConflict() throws Exception {
        Case learner = issued();
        UUID attempt = UUID.randomUUID();
        submit(learner, attempt, "Планы [[fake:block]] [[stub:assess-complete]]");
        assertThat(provider.entered.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> assessments.selfRate(learner.actor(), learner.deck(), learner.session(), attempt,
                AttemptCommand.SelfRating.FULL)).isInstanceOf(AssessmentStateConflictException.class);
        // a stranger neither reads nor changes the attempt
        UUID stranger = UUID.randomUUID();
        assertThatThrownBy(() -> assessments.read(stranger, learner.deck(), learner.session(), attempt))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> assessments.selfCheck(stranger, learner.deck(), learner.session(), attempt))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> assessments.selfCheck(learner.actor(), learner.deck(), learner.session(), UUID.randomUUID()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void theDeadlineSweeperTurnsAStuckAnswerIntoSelfCheckAndTheAttemptIsKept() throws Exception {
        Case learner = issued();
        UUID attempt = UUID.randomUUID();
        Instant started = Instant.now();
        submit(learner, attempt, "Планы [[fake:block]] [[stub:assess-complete]]");
        assertThat(provider.entered.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        JsonNode view = settled(learner, attempt);
        assertThat(java.time.Duration.between(started, Instant.now())).as("not before the 4 s deadline").isGreaterThanOrEqualTo(java.time.Duration.ofSeconds(3));
        assertThat(view.path("status").stringValue(null)).isEqualTo("SELF_CHECK");
        assertThat(view.path("reason").stringValue(null)).isEqualTo("DEADLINE");
        assertThat(state(attempt)).isEqualTo("UNAVAILABLE");
        // the provider answers after the deadline: discarded
        double before = discarded();
        provider.release.countDown();
        awaitDiscarded(before);
        assertThat(state(attempt)).isEqualTo("UNAVAILABLE");
        assertThat(count("study_attempt_tombstone", learner.actor())).isZero();
        assertThat(checks(learner.actor())).isZero();
        assertThat(assessments.selfRate(learner.actor(), learner.deck(), learner.session(), attempt,
                AttemptCommand.SelfRating.HINTED).outcome().path("evidence").path("result").stringValue(null)).isEqualTo("PARTIAL");
    }

    @Test
    void anExhaustedFairUseAllowanceGoesStraightToSelfCheckWithoutACall() {
        Case learner = issued();
        exhaustFairUse(learner.actor());
        UUID attempt = UUID.randomUUID();
        AttemptService.SubmitResult result = submit(learner, attempt, "Планы выполнения [[stub:assess-complete]]");
        assertThat(result.accepted()).isTrue();
        assertThat(result.outcome().path("status").stringValue(null)).isEqualTo("SELF_CHECK");
        assertThat(result.outcome().path("reason").stringValue(null)).isEqualTo("USAGE_LIMIT");
        assertThat(result.outcome().path("selfCheck").path("criteria")).hasSize(4);
        assertThat(provider.callsOf(attempt)).isEmpty();
        assertThat(state(attempt)).isEqualTo("UNAVAILABLE");
        assertThat(reason(attempt)).isEqualTo("USAGE_LIMIT");
        assertThat(assessments.selfRate(learner.actor(), learner.deck(), learner.session(), attempt,
                AttemptCommand.SelfRating.FULL).outcome().path("status").stringValue(null)).isEqualTo("ASSESSED");
    }

    @Test
    void aGradeThatTheAllowanceNoLongerCoversEndsInSelfCheckWithoutAGrade() throws Exception {
        Case learner = issued();
        UUID attempt = UUID.randomUUID();
        submit(learner, attempt, "Планы [[fake:block]] [[stub:assess-complete]]");
        assertThat(provider.entered.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        // the allowance is spent while the model thinks (other answers of the learner were graded meanwhile)
        exhaustFairUse(learner.actor());
        provider.release.countDown();
        JsonNode view = settled(learner, attempt);
        assertThat(view.path("status").stringValue(null)).isEqualTo("SELF_CHECK");
        assertThat(view.path("reason").stringValue(null)).isEqualTo("USAGE_LIMIT");
        assertThat(count("study_attempt_tombstone", learner.actor())).isZero();
        assertThat(count("study_transition", learner.actor())).isZero();
    }

    @Test
    void idempotencyChangedRetriesAndSecondAttemptsOfAHeldPresentation() throws Exception {
        Case learner = issued();
        UUID attempt = UUID.randomUUID();
        submit(learner, attempt, "Планы [[fake:block]]");
        assertThat(provider.entered.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        AttemptService.SubmitResult retry = submit(learner, attempt, "Планы [[fake:block]]");
        assertThat(retry.replayed()).isTrue();
        assertThat(retry.accepted()).isTrue();
        assertThat(retry.outcome().path("status").stringValue(null)).isEqualTo("ASSESSING");
        assertThatThrownBy(() -> submit(learner, attempt, "совсем другой текст")).isInstanceOf(IdempotencyConflictException.class);
        assertThatThrownBy(() -> submit(learner, UUID.randomUUID(), "Планы [[fake:block]]")).isInstanceOf(IdempotencyConflictException.class);
        // a CANCEL under another attempt id conflicts too while the first answer holds the presentation
        assertThatThrownBy(() -> attempts.submit(learner.actor(), learner.deck(), learner.session(), StudyFixtures.attempt(
                UUID.randomUUID(), learner.issued(), JSON.createObjectNode().put("kind", "CANCEL"))))
                .isInstanceOf(IdempotencyConflictException.class);
        // only one assessment row, one answer worth of calls
        assertThat(count("study_assessment", learner.actor())).isOne();
        assertThat(provider.callsOf(attempt)).hasSize(1);
        // another learner cannot use the attempt id
        Case other = issued();
        assertThatThrownBy(() -> submit(other, attempt, "Планы [[fake:block]]")).isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void aCancelWithoutAnAssessmentIsAPlainNotAssessedAndAMalformedResponseConsumesNothing() {
        Case learner = issued();
        UUID cancel = UUID.randomUUID();
        AttemptService.SubmitResult cancelled = attempts.submit(learner.actor(), learner.deck(), learner.session(),
                StudyFixtures.attempt(cancel, learner.issued(), JSON.createObjectNode().put("kind", "CANCEL")));
        assertThat(cancelled.accepted()).isFalse();
        assertThat(cancelled.outcome().path("status").stringValue(null)).isEqualTo("NOT_ASSESSED");
        assertThat(count("study_assessment", learner.actor())).isZero();
        // only this attempt's calls: answers of other tests may still be graded in the background against the shared double
        assertThat(provider.callsOf(cancel)).isEmpty();

        Case wrong = issued();
        assertThatThrownBy(() -> attempts.submit(wrong.actor(), wrong.deck(), wrong.session(), StudyFixtures.attempt(
                UUID.randomUUID(), wrong.issued(), JSON.createObjectNode().put("kind", "SELF_CHECK").put("rating", "FULL"))))
                .isInstanceOf(InvalidRequestException.class);
        // speech is accepted only where the exercise takes it (this one takes typed text)
        assertThatThrownBy(() -> attempts.submit(wrong.actor(), wrong.deck(), wrong.session(), StudyFixtures.attempt(
                UUID.randomUUID(), wrong.issued(), JSON.createObjectNode().put("kind", "TEXT").put("text", "планы")
                        .put("answerSource", "SPEECH")))).isInstanceOf(InvalidRequestException.class);
        assertThat(count("study_assessment", wrong.actor())).isZero();
    }

    @Test
    void anAccountWithTooManyAnswersBeingGradedGetsSelfCheckForTheNext() throws Exception {
        Case first = issued();
        List<Case> cases = new java.util.ArrayList<>(List.of(first));
        for (int index = 0; index < 3; index++) cases.add(another(first, "SCHEDULED"));
        List<UUID> attemptIds = new java.util.ArrayList<>();
        for (int index = 0; index < 3; index++) {
            UUID id = UUID.randomUUID();
            attemptIds.add(id);
            assertThat(submit(cases.get(index), id, "Планы [[fake:block]] [[stub:assess-complete]]").outcome().path("status").stringValue(null))
                    .isEqualTo("ASSESSING");
        }
        assertThat(provider.entered.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        // the fourth waits for nothing: straight to self-check, the attempt kept, nothing sent to the provider
        UUID fourth = UUID.randomUUID();
        AttemptService.SubmitResult busy = submit(cases.get(3), fourth, "Планы выполнения [[stub:assess-complete]]");
        assertThat(busy.accepted()).isTrue();
        assertThat(busy.outcome().path("status").stringValue(null)).isEqualTo("SELF_CHECK");
        assertThat(busy.outcome().path("reason").stringValue(null)).isEqualTo("BUSY");
        assertThat(provider.callsOf(fourth)).isEmpty();
        assertThat(assessments.selfRate(first.actor(), first.deck(), cases.get(3).session(), fourth, AttemptCommand.SelfRating.FULL)
                .outcome().path("status").stringValue(null)).isEqualTo("ASSESSED");
        // when they are done the account can have answers graded again
        provider.release.countDown();
        for (int index = 0; index < 3; index++) settled(cases.get(index), attemptIds.get(index));
        Case again = another(first, "SCHEDULED");
        UUID next = UUID.randomUUID();
        assertThat(submit(again, next, "Планы выполнения [[stub:assess-complete]]").outcome().path("status").stringValue(null)).isEqualTo("ASSESSING");
        assertThat(settled(again, next).path("status").stringValue(null)).isEqualTo("ASSESSED");
    }

    @Test
    void aStaleLearningEpochIsNotGradedAndNotCharged() {
        Case learner = issued();
        jdbc.sql("UPDATE app_learning.study_state SET learning_epoch=learning_epoch+1 WHERE account_id=:actor")
                .param("actor", learner.actor()).update();
        UUID attempt = UUID.randomUUID();
        AttemptService.SubmitResult result = submit(learner, attempt, "Планы выполнения [[stub:assess-complete]]");
        assertThat(result.accepted()).isFalse();
        assertThat(result.outcome().path("status").stringValue(null)).isEqualTo("NOT_ASSESSED");
        assertThat(result.outcome().path("feedback").toString()).doesNotContain("assessment");
        assertThat(provider.callsOf(attempt)).isEmpty();
        assertThat(count("study_assessment", learner.actor())).isZero();
        assertThat(checks(learner.actor())).isZero();
    }

    @Test
    void thePresentationNeverCarriesTheRubricAndAnInFlightAnswerIsVisibleToAReload() throws Exception {
        Case learner = issued();
        String issuedJson = learner.issued().json().toString();
        assertThat(issuedJson).doesNotContain("rubric", "criteria", "referenceAnswer", "misconception", "Ориентир:", REFERENCE,
                "Перебирает альтернативные", "planner");
        assertThat(learner.issued().json().has("assessment")).isFalse();
        UUID attempt = UUID.randomUUID();
        submit(learner, attempt, "Планы [[fake:block]]");
        assertThat(provider.entered.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        // a reload of the session shows which attempt is in flight, still without reference or criteria
        JsonNode reloaded = sessions.read(learner.actor(), learner.deck(), learner.session());
        JsonNode presentation = reloaded.path("presentations").get(0);
        assertThat(presentation.path("assessment").path("attemptId").stringValue(null)).isEqualTo(attempt.toString());
        assertThat(presentation.path("assessment").path("status").stringValue(null)).isEqualTo("ASSESSING");
        assertThat(reloaded.toString()).doesNotContain("rubric", "criteria", "referenceAnswer", REFERENCE, "Ориентир:");
        // after the learner chose self-check the reload says so
        assessments.selfCheck(learner.actor(), learner.deck(), learner.session(), attempt);
        assertThat(sessions.read(learner.actor(), learner.deck(), learner.session()).path("presentations").get(0)
                .path("assessment").path("status").stringValue(null)).isEqualTo("SELF_CHECK");
        // once terminal the presentation is not pending any more
        provider.release.countDown();
        assessments.selfRate(learner.actor(), learner.deck(), learner.session(), attempt, AttemptCommand.SelfRating.FULL);
        assertThat(sessions.read(learner.actor(), learner.deck(), learner.session()).path("presentations")).isEmpty();
    }

    @Test
    void answersKeptInAssessmentRowsAreClearedPastTheirExpiry() {
        Case learner = issued();
        UUID attempt = UUID.randomUUID();
        submit(learner, attempt, "Не знаю [[stub:assess-unclear]]");
        settled(learner, attempt);
        assertThat(jdbc.sql("SELECT response IS NOT NULL FROM app_learning.study_assessment WHERE attempt_id=:id").param("id", attempt)
                .query(Boolean.class).single()).as("kept while the learner may still rate themselves").isTrue();
        assertThat(assessments.clearExpiredAnswers(500)).as("nothing of this learner is due yet").isZero();
        jdbc.sql("UPDATE app_learning.study_assessment SET expires_at=now() - interval '1 hour' WHERE attempt_id=:id")
                .param("id", attempt).update();
        assertThat(assessments.clearExpiredAnswers(500)).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.sql("SELECT response IS NULL FROM app_learning.study_assessment WHERE attempt_id=:id").param("id", attempt)
                .query(Boolean.class).single()).isTrue();
        assertThat(state(attempt)).as("the state row stays, it holds no text").isEqualTo("SELF_CHECK");
    }

    @Test
    void aPracticeAnswerIsGradedForFeedbackOnlyAndWritesNoCanonicalEffects() {
        Case first = issued();
        UUID one = UUID.randomUUID();
        submit(first, one, "Планы выполнения [[stub:assess-complete]]");
        settled(first, one);
        long transitions = count("study_transition", first.actor());
        Case practice = another(first, "PRACTICE");
        UUID attempt = UUID.randomUUID();
        submit(practice, attempt, "Планы выполнения [[stub:assess-complete]]");
        JsonNode outcome = settled(practice, attempt);
        assertThat(outcome.path("mode").stringValue(null)).isEqualTo("PRACTICE");
        assertThat(outcome.path("canonicalEffects").booleanValue()).isFalse();
        assertThat(outcome.path("evidence").isNull()).isTrue();
        assertThat(outcome.path("transition").isNull()).isTrue();
        assertThat(outcome.path("feedback").path("assessment").path("judgement").stringValue(null)).isEqualTo("COMPLETE");
        assertThat(count("study_transition", first.actor())).isEqualTo(transitions);
        assertThat(checks(first.actor())).as("the practice grade is a delivered grade too").isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.study_raw_response WHERE attempt_id=:id").param("id", attempt)
                .query(Long.class).single()).isZero();
    }
}
