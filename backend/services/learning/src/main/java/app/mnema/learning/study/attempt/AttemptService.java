package app.mnema.learning.study.attempt;

import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.platform.id.UuidPolicy;
import app.mnema.learning.platform.idempotency.IdempotencyConflictException;
import app.mnema.learning.platform.json.CanonicalJsonHasher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
public class AttemptService {
    private static final Duration RAW_RETENTION = Duration.ofDays(30);
    private static final Duration COMPACT_RECEIPT_RETENTION = Duration.ofHours(24);
    private final AttemptRepository repository;
    private final CanonicalJsonHasher hasher;
    private final BaselineReducer reducer = new BaselineReducer();

    public AttemptService(AttemptRepository repository, CanonicalJsonHasher hasher) {
        this.repository = repository;
        this.hasher = hasher;
    }

    @Transactional(timeout = 10)
    public SubmitResult submit(UUID actor, UUID deck, UUID session, AttemptCommand command) {
        UuidPolicy.requireEntityId(actor, "actor");
        UuidPolicy.requireEntityId(deck, "deckId");
        UuidPolicy.requireEntityId(session, "sessionId");
        if (!repository.ownsDeck(actor, deck)) throw new ResourceNotFoundException();
        byte[] hash = hasher.hash(command.envelope(deck, session)).sha256();
        var fast = repository.receipt(command.attemptId());
        if (fast.isPresent()) return replay(fast.orElseThrow(), actor, deck, session, command, hash);

        repository.lockAttempt(command.attemptId());
        var serialized = repository.receipt(command.attemptId());
        if (serialized.isPresent()) return replay(serialized.orElseThrow(), actor, deck, session, command, hash);
        repository.lockSession(actor, deck, session);
        AttemptRepository.Presentation presentation = repository.presentationForUpdate(actor, deck, session,
                command.presentationId()).orElseThrow(ResourceNotFoundException::new);
        Instant now = repository.now();
        if (!presentation.expiresAt().isAfter(now)) throw new PresentationExpiredException();
        if (!presentation.nonce().equals(command.nonce())) throw new ResourceNotFoundException();
        if (repository.terminal(actor, session, command.presentationId()).isPresent()) {
            throw new IdempotencyConflictException();
        }

        AttemptEvaluation evaluation = AttemptEvaluation.evaluate(presentation.exerciseType(),
                presentation.evaluator(), presentation.answerContract(), presentation.bindings(), command);
        if (!presentation.mode().equals("SCHEDULED")) {
            ObjectNode outcome = feedbackOnly(command, presentation, evaluation);
            repository.insertReceipt(command, actor, deck, session, hash, presentation.mode(),
                    evaluation.status().name(), outcome, now, now.plus(COMPACT_RECEIPT_RETENTION));
            repository.completeSessionIfTerminal(actor, deck, session, now);
            return new SubmitResult(outcome, false);
        }
        if (evaluation.status() != AttemptEvaluation.Status.ASSESSED) {
            ObjectNode outcome = noTransition(command, presentation, evaluation);
            repository.insertReceipt(command, actor, deck, session, hash, presentation.mode(),
                    evaluation.status().name(), outcome, now, null);
            repository.completeSessionIfTerminal(actor, deck, session, now);
            return new SubmitResult(outcome, false);
        }

        AttemptRepository.State state = repository.stateForUpdate(actor, deck, presentation.objectiveId());
        if (state.learningEpoch() != presentation.learningEpoch()) {
            AttemptEvaluation oldEpoch = new AttemptEvaluation(AttemptEvaluation.Status.NOT_ASSESSED, null, null,
                    java.util.List.of("OLD_LEARNING_EPOCH"),
                    JsonNodeFactory.instance.objectNode().put("result", "NOT_ASSESSED"));
            ObjectNode outcome = noTransition(command, presentation, oldEpoch);
            repository.insertReceipt(command, actor, deck, session, hash, presentation.mode(),
                    oldEpoch.status().name(), outcome, now, null);
            repository.completeSessionIfTerminal(actor, deck, session, now);
            return new SubmitResult(outcome, false);
        }
        BaselineReducer.Transition transition = reducer.apply(new BaselineReducer.State(state.level(),
                state.correctStreak(), state.lapseCount()), evaluation.result(), evaluation.evidenceClass(), now);
        ObjectNode outcome = assessed(command, presentation, state, evaluation, transition);
        repository.insertReceipt(command, actor, deck, session, hash, presentation.mode(), "ASSESSED", outcome,
                now, null);
        repository.insertEvidence(command, presentation, evaluation, now);
        repository.insertTransition(command, presentation, state, transition);
        repository.updateState(state, transition, presentation.configId());
        repository.insertRaw(command.attemptId(), command.payload().path("response"), now.plus(RAW_RETENTION));
        repository.completeSessionIfTerminal(actor, deck, session, now);
        return new SubmitResult(outcome, false);
    }

    private SubmitResult replay(AttemptRepository.Receipt receipt, UUID actor, UUID deck, UUID session,
                                AttemptCommand command, byte[] hash) {
        if (!receipt.accountId().equals(actor) || !receipt.deckId().equals(deck)
                || !receipt.sessionId().equals(session) || !receipt.presentationId().equals(command.presentationId())
                || !AttemptRepository.same(receipt.payloadHash(), hash) || receipt.outcome() == null) {
            throw new IdempotencyConflictException();
        }
        return new SubmitResult(receipt.outcome(), true);
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

    private static ObjectNode noTransition(AttemptCommand command, AttemptRepository.Presentation presentation,
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

    private static ObjectNode base(AttemptCommand command, AttemptRepository.Presentation presentation,
                                   String status) {
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

    public record SubmitResult(JsonNode outcome, boolean replayed) {
        public SubmitResult { outcome = outcome.deepCopy(); }
        @Override public JsonNode outcome() { return outcome.deepCopy(); }
    }
}
