package app.mnema.learning.study.attempt;

import app.mnema.learning.catalog.exercise.ExerciseNewMarks;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The write half of a terminal attempt, shared by the synchronous submit and by the AI assessment that completes later: one
 * receipt per presentation and, for a scheduled assessed result, the evidence, the reducer transition, the state and the raw
 * response, all in the caller's transaction. It never evaluates anything and never opens a transaction of its own.
 */
@Component
class AttemptConclusion {
    private static final Duration RAW_RETENTION = Duration.ofDays(30);
    private static final Duration COMPACT_RECEIPT_RETENTION = Duration.ofHours(24);

    private final AttemptRepository repository;
    private final ExerciseNewMarks newMarks;
    private final BaselineReducer reducer = new BaselineReducer();

    AttemptConclusion(AttemptRepository repository, ExerciseNewMarks newMarks) {
        this.repository = repository;
        this.newMarks = newMarks;
    }

    /**
     * Makes {@code evaluation} the terminal outcome of {@code command}. The caller holds the attempt lock, the session lock
     * and the presentation row, and has checked expiry, nonce and that the presentation has no terminal receipt.
     *
     * @param evaluator the identity recorded with the evidence (the exercise's own, or {@code self-check} after a fallback)
     */
    AttemptService.SubmitResult conclude(UUID actor, UUID deck, UUID session, AttemptCommand command,
                                         AttemptRepository.Presentation presentation, AttemptEvaluation evaluation,
                                         JsonNode evaluator, byte[] hash, Instant now) {
        if (!presentation.mode().equals("SCHEDULED")) {
            ObjectNode outcome = feedbackOnly(command, presentation, evaluation);
            repository.insertReceipt(command, actor, deck, session, hash, presentation.mode(),
                    evaluation.status().name(), outcome, now, now.plus(COMPACT_RECEIPT_RETENTION));
            newMarks.clear(actor, deck, presentation.exerciseId());
            repository.completeSessionIfTerminal(actor, deck, session, now);
            return new AttemptService.SubmitResult(outcome, false, false);
        }
        if (evaluation.status() != AttemptEvaluation.Status.ASSESSED) {
            ObjectNode outcome = noTransition(command, presentation, evaluation);
            repository.insertReceipt(command, actor, deck, session, hash, presentation.mode(),
                    evaluation.status().name(), outcome, now, null);
            newMarks.clear(actor, deck, presentation.exerciseId());
            repository.completeSessionIfTerminal(actor, deck, session, now);
            return new AttemptService.SubmitResult(outcome, false, false);
        }

        AttemptRepository.State state = repository.stateForUpdate(actor, deck, presentation.objectiveId());
        if (state.learningEpoch() != presentation.learningEpoch()) {
            AttemptEvaluation oldEpoch = oldEpoch();
            ObjectNode outcome = noTransition(command, presentation, oldEpoch);
            repository.insertReceipt(command, actor, deck, session, hash, presentation.mode(),
                    oldEpoch.status().name(), outcome, now, null);
            newMarks.clear(actor, deck, presentation.exerciseId());
            repository.completeSessionIfTerminal(actor, deck, session, now);
            return new AttemptService.SubmitResult(outcome, false, false);
        }
        BaselineReducer.Transition transition = reducer.apply(new BaselineReducer.State(state.level(),
                state.correctStreak(), state.lapseCount()), evaluation.result(), evaluation.evidenceClass(), now);
        ObjectNode outcome = assessed(command, presentation, state, evaluation, transition);
        repository.insertReceipt(command, actor, deck, session, hash, presentation.mode(), "ASSESSED", outcome,
                now, null);
        repository.insertEvidence(command, presentation, evaluation, evaluator, now);
        repository.insertTransition(command, presentation, state, transition);
        repository.updateState(state, transition, presentation.configId());
        repository.insertRaw(command.attemptId(), command.payload().path("response"), now.plus(RAW_RETENTION));
        // the learner has met the exercise: its «Новое» mark goes with the terminal result, in this transaction
        newMarks.clear(actor, deck, presentation.exerciseId());
        repository.completeSessionIfTerminal(actor, deck, session, now);
        return new AttemptService.SubmitResult(outcome, false, false);
    }

    /** What the reducer would do with {@code result} now: the input of «next time I will ask more precisely». */
    BaselineReducer.Transition preview(AttemptRepository.State state, AttemptEvaluation.Result result,
                                       AttemptEvaluation.EvidenceClass evidence, Instant now) {
        return reducer.apply(new BaselineReducer.State(state.level(), state.correctStreak(), state.lapseCount()), result,
                evidence, now);
    }

    static AttemptEvaluation oldEpoch() {
        return new AttemptEvaluation(AttemptEvaluation.Status.NOT_ASSESSED, null, null,
                List.of("OLD_LEARNING_EPOCH"), JsonNodeFactory.instance.objectNode().put("result", "NOT_ASSESSED"));
    }

    private static ObjectNode assessed(AttemptCommand command, AttemptRepository.Presentation presentation,
                                       AttemptRepository.State state, AttemptEvaluation evaluation,
                                       BaselineReducer.Transition transition) {
        ObjectNode outcome = base(command, presentation, "ASSESSED");
        outcome.set("evidence", evidence(presentation, evaluation));
        outcome.set("feedback", evaluation.feedback().deepCopy());
        outcome.set("transition", transition(presentation, state, transition));
        return outcome;
    }

    static ObjectNode noTransition(AttemptCommand command, AttemptRepository.Presentation presentation,
                                   AttemptEvaluation evaluation) {
        ObjectNode outcome = base(command, presentation, evaluation.status().name());
        outcome.putNull("evidence");
        outcome.set("feedback", evaluation.feedback().deepCopy());
        outcome.putNull("transition");
        return outcome;
    }

    private static ObjectNode feedbackOnly(AttemptCommand command, AttemptRepository.Presentation presentation,
                                           AttemptEvaluation evaluation) {
        ObjectNode outcome = base(command, presentation, evaluation.status().name());
        outcome.putNull("evidence");
        outcome.set("feedback", evaluation.feedback().deepCopy());
        outcome.putNull("transition");
        outcome.put("canonicalEffects", false);
        return outcome;
    }

    static ObjectNode base(AttemptCommand command, AttemptRepository.Presentation presentation, String status) {
        return JsonNodeFactory.instance.objectNode().put("attemptId", command.attemptId().toString())
                .put("presentationId", presentation.presentationId().toString())
                .put("mode", presentation.mode()).put("status", status);
    }

    private static ObjectNode evidence(AttemptRepository.Presentation presentation, AttemptEvaluation evaluation) {
        ObjectNode value = JsonNodeFactory.instance.objectNode()
                .put("objectiveId", presentation.objectiveId().toString())
                .put("objectiveRevisionId", presentation.objectiveRevisionId().toString())
                .put("result", evaluation.result().name())
                .put("evidenceClass", evaluation.evidenceClass().name());
        var reasons = value.putArray("reasonCodes");
        evaluation.reasonCodes().forEach(reasons::add);
        return value;
    }

    private static ObjectNode transition(AttemptRepository.Presentation presentation, AttemptRepository.State state,
                                         BaselineReducer.Transition transition) {
        return JsonNodeFactory.instance.objectNode().put("learningEpoch", Long.toString(state.learningEpoch()))
                .put("sequence", Long.toString(state.transitionSequence() + 1))
                .put("beforeLevel", transition.beforeLevel()).put("afterLevel", transition.afterLevel())
                .put("acceptedAt", transition.acceptedAt().toString()).put("nextDue", transition.nextDue().toString())
                .put("reducerId", presentation.reducerId()).put("reducerVersion", presentation.reducerVersion())
                .put("configId", presentation.configId().toString()).put("configHash", presentation.configHash());
    }
}
