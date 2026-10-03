package app.mnema.learning.study.attempt;

import app.mnema.learning.catalog.exercise.AnswerKey;
import app.mnema.learning.catalog.exercise.ExerciseType;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.id.UuidPolicy;
import app.mnema.learning.platform.idempotency.IdempotencyConflictException;
import app.mnema.learning.platform.json.CanonicalJsonHasher;
import app.mnema.learning.media.MediaCatalog;
import tools.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.UUID;

@Service
public class AttemptService {
    private static final Logger LOG = LoggerFactory.getLogger(AttemptService.class);
    private static final Duration COMPACT_RECEIPT_RETENTION = Duration.ofHours(24);
    private final AttemptRepository repository;
    private final CanonicalJsonHasher hasher;
    private final MediaCatalog mediaCatalog;
    private final AttemptConclusion conclusion;
    private final AssessmentService assessments;

    public AttemptService(AttemptRepository repository, CanonicalJsonHasher hasher, MediaCatalog mediaCatalog,
                          AttemptConclusion conclusion, AssessmentService assessments) {
        this.repository = repository;
        this.hasher = hasher;
        this.mediaCatalog = mediaCatalog;
        this.conclusion = conclusion;
        this.assessments = assessments;
    }

    @Transactional(timeout = 10)
    public boolean checkPair(UUID actor, UUID deck, UUID session, PairCheckCommand command) {
        UuidPolicy.requireEntityId(actor, "actor");
        UuidPolicy.requireEntityId(deck, "deckId");
        UuidPolicy.requireEntityId(session, "sessionId");
        if (!repository.ownsDeck(actor, deck)) throw new ResourceNotFoundException();
        repository.lockSession(actor, deck, session);
        AttemptRepository.Presentation presentation = repository.presentationForUpdate(actor, deck, session,
                command.presentationId()).orElseThrow(ResourceNotFoundException::new);
        if (!presentation.nonce().equals(command.nonce())) throw new ResourceNotFoundException();
        var previous = repository.pairInteraction(actor, session, command);
        if (previous.isPresent()) return previous.orElseThrow();
        if (!presentation.expiresAt().isAfter(repository.now())) throw new PresentationExpiredException();
        if (repository.terminal(actor, session, command.presentationId()).isPresent()) throw new IdempotencyConflictException();
        if (!presentation.exerciseType().equals(ExerciseType.MATCH.name())) throw new InvalidRequestException();
        if (!mediaCatalog.exerciseMediaReady(actor, deck, presentation.exerciseId(), presentation.exerciseRevisionId())) {
            throw new InvalidRequestException();
        }
        // Both ids must be sides the learner was actually issued; the answer key decides correctness.
        var lefts = new HashSet<UUID>();
        var rights = new HashSet<UUID>();
        presentation.content().path("left").forEach(item -> lefts.add(UUID.fromString(item.path("itemId").stringValue(null))));
        presentation.content().path("right").forEach(item -> rights.add(UUID.fromString(item.path("itemId").stringValue(null))));
        if (!lefts.contains(command.leftId()) || !rights.contains(command.rightId())) throw new InvalidRequestException();
        AnswerKey.Match key = (AnswerKey.Match) AnswerKey.parse(ExerciseType.MATCH, presentation.answerKey());
        UUID expected = key.pairs().stream().filter(pair -> pair.leftId().equals(command.leftId()))
                .map(AnswerKey.Pair::rightId).findFirst().orElseThrow(InvalidRequestException::new);
        boolean correct = expected.equals(command.rightId());
        repository.insertPairInteraction(actor, session, command, correct,
                presentation.expiresAt().plus(COMPACT_RECEIPT_RETENTION));
        return correct;
    }

    /**
     * Records one answer. A deterministic answer is evaluated and concluded here; an answer to an {@code ai-semantic} exercise is
     * accepted and handed to {@link AssessmentService} (answer {@code 202}): the grading happens later, outside any transaction.
     */
    @Transactional(timeout = 10)
    public SubmitResult submit(UUID actor, UUID deck, UUID session, AttemptCommand command) {
        UuidPolicy.requireEntityId(actor, "actor");
        UuidPolicy.requireEntityId(deck, "deckId");
        UuidPolicy.requireEntityId(session, "sessionId");
        if (!repository.ownsDeck(actor, deck)) throw new ResourceNotFoundException();
        byte[] hash = hasher.hash(command.envelope(deck, session)).sha256();
        var fast = repository.receipt(command.attemptId());
        if (fast.isPresent()) return replay(fast.orElseThrow(), actor, deck, session, command, hash);
        var pendingFast = assessments.replay(command, actor, deck, session, hash);
        if (pendingFast.isPresent()) return pendingFast.orElseThrow();

        repository.lockAttempt(command.attemptId());
        var serialized = repository.receipt(command.attemptId());
        if (serialized.isPresent()) return replay(serialized.orElseThrow(), actor, deck, session, command, hash);
        var pending = assessments.replay(command, actor, deck, session, hash);
        if (pending.isPresent()) return pending.orElseThrow();
        repository.lockSession(actor, deck, session);
        AttemptRepository.Presentation presentation = repository.presentationForUpdate(actor, deck, session,
                command.presentationId()).orElseThrow(ResourceNotFoundException::new);
        Instant now = repository.now();
        if (!presentation.expiresAt().isAfter(now)) throw new PresentationExpiredException();
        if (!presentation.nonce().equals(command.nonce())) throw new ResourceNotFoundException();
        if (repository.terminal(actor, session, command.presentationId()).isPresent()) {
            throw new IdempotencyConflictException();
        }
        // An answer already being assessed under another attempt id owns the presentation until it ends.
        if (assessments.holds(actor, session, command.presentationId())) throw new IdempotencyConflictException();

        // Media is checked for every mechanic: an unavailable asset must never turn into a wrong answer.
        // The response shape is validated first inside the evaluation, so a malformed response consumes nothing.
        boolean mediaReady = command.response() instanceof AttemptCommand.CancelResponse
                || mediaCatalog.exerciseMediaReady(actor, deck, presentation.exerciseId(),
                        presentation.exerciseRevisionId());
        AttemptEvaluation evaluation;
        if (mediaReady && command.response() instanceof AttemptCommand.TextResponse text
                && AssessmentService.semantic(presentation)) {
            AttemptEvaluation.requireShape(subject(presentation), text);
            Optional<SubmitResult> accepted = assessments.begin(actor, deck, session, presentation, command, hash, text, now);
            if (accepted.isPresent()) return accepted.orElseThrow();
            // the learning epoch moved on: nothing is graded, nothing is spent
            evaluation = AttemptConclusion.oldEpoch();
        } else {
            evaluation = AttemptEvaluation.evaluate(subject(presentation), command.response(), mediaReady);
        }
        if (presentation.exerciseType().equals(ExerciseType.MATCH.name())
                && evaluation.result() == AttemptEvaluation.Result.CORRECT
                && repository.hasPairMistakes(actor, session, command.presentationId())) {
            evaluation = evaluation.withPairRetry();
        }
        LOG.debug("Study answer evaluated attemptId={} presentationId={} exerciseRevisionId={} "
                        + "evaluatorId={} evaluatorVersion={} appliedRules={} reasonCodes={} result={}",
                command.attemptId(), command.presentationId(), presentation.exerciseRevisionId(),
                presentation.evaluator().path("id").asString(""), presentation.evaluator().path("version").asString(""),
                evaluation.feedback().path("appliedRules"), evaluation.reasonCodes(),
                evaluation.feedback().path("result").asString(""));
        return conclusion.conclude(actor, deck, session, command, presentation, evaluation, presentation.evaluator(), hash, now);
    }

    private SubmitResult replay(AttemptRepository.Receipt receipt, UUID actor, UUID deck, UUID session,
                                AttemptCommand command, byte[] hash) {
        if (!receipt.accountId().equals(actor) || !receipt.deckId().equals(deck)
                || !receipt.sessionId().equals(session) || !receipt.presentationId().equals(command.presentationId())
                || !AttemptRepository.same(receipt.payloadHash(), hash) || receipt.outcome() == null) {
            throw new IdempotencyConflictException();
        }
        return new SubmitResult(receipt.outcome(), true, false);
    }

    static AttemptEvaluation.Subject subject(AttemptRepository.Presentation presentation) {
        return new AttemptEvaluation.Subject(
                ExerciseType.fromWire(presentation.exerciseType()).orElseThrow(IllegalStateException::new),
                presentation.evaluator(), presentation.answerKey(), presentation.content(), presentation.reveal(),
                new HashSet<>(presentation.hintedBlanks()), presentation.transcriptRevealed());
    }

    /**
     * What a submit answers: the stored or fresh outcome ({@code 200}), or, while an {@code ai-semantic} answer is assessed or
     * waits for the learner's own rating, its current state ({@code accepted}, {@code 202}).
     */
    public record SubmitResult(JsonNode outcome, boolean replayed, boolean accepted) {
        public SubmitResult { outcome = outcome.deepCopy(); }
        @Override public JsonNode outcome() { return outcome.deepCopy(); }
    }
}
