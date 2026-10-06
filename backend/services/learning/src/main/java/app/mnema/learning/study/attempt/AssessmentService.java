package app.mnema.learning.study.attempt;

import app.mnema.learning.capability.LearningCapabilities;
import app.mnema.learning.capability.SemanticAssessmentProvider.AnswerSource;
import app.mnema.learning.capability.SemanticAssessmentProvider.GradeOutcome;
import app.mnema.learning.capability.SemanticAssessmentProvider.GradeRequest;
import app.mnema.learning.catalog.exercise.EvaluatorPolicy;
import app.mnema.learning.catalog.exercise.ExerciseType;
import app.mnema.learning.catalog.exercise.Rubric;
import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.platform.id.UuidPolicy;
import app.mnema.learning.platform.idempotency.IdempotencyConflictException;
import app.mnema.learning.usage.Bucket;
import app.mnema.learning.usage.UsageLedger;
import app.mnema.learning.usage.UsageLimitReachedException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The AI assessment of a free explanation ({@code ai-semantic}) from the learner's side: an answer is accepted ({@code ASSESSING}),
 * graded by the model outside any transaction, and then becomes the terminal attempt; or the learner rates themselves.
 *
 * <p><b>States</b> ({@code study_assessment}): {@code ASSESSING} to {@code DONE} (the grade arrived), to {@code SELF_CHECK} (the learner
 * chose, or the grader was uncertain) or to {@code UNAVAILABLE} (provider failure, capability gone, fair-use spent, deadline);
 * {@code SELF_CHECK} and {@code UNAVAILABLE} both mean "the learner rates themselves" and end in {@code DONE} through
 * {@link #selfRate}, the same attempt: a presentation keeps one terminal receipt. Every arrow is a compare-and-set, so a grade
 * that arrives after the learner chose self-check, or after the deadline, is discarded.
 *
 * <p><b>Authority.</b> The model only gives verdicts. {@link SemanticPolicy} turns them into a judgement under a
 * {@link SemanticStrictness} that is a function of the objective's state; the evidence goes through the baseline reducer like any
 * other, and nothing here writes {@code StudyState} directly. Provider uncertainty is never a result. A grade is paid for
 * (one fair-use answer check) only when it is delivered, in the transaction that stores it.
 *
 * <p><b>Locks.</b> Every command takes the attempt advisory lock first, then the session row, the presentation row, the
 * assessment row and the objective's state row, in that order; the deadline sweeper only touches overdue assessment rows with
 * {@code SKIP LOCKED} and takes nothing else.
 */
@Service
public class AssessmentService {
    private static final Logger LOG = LoggerFactory.getLogger(AssessmentService.class);
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final JsonNode SELF_CHECK_EVALUATOR = JSON.objectNode().put("id", "self-check").put("version", "1");
    private static final Set<String> PUBLIC_REASONS = Set.of("LEARNER_CHOICE", "PROVIDER_UNCERTAIN", "USAGE_LIMIT",
            "CAPABILITY_UNAVAILABLE", "DEADLINE", "BUSY");
    /** Answers younger than this are polled fast, older ones slower: the client's backoff, as a hint. */
    private static final Duration FAST_POLL_WINDOW = Duration.ofSeconds(3);
    private static final int FAST_POLL_MS = 700;
    private static final int SLOW_POLL_MS = 1_500;
    private static final int SWEEP_BATCH = 100;
    /** At most this much of the question goes to the grader (4000 per block, up to 8 blocks, are allowed to author). */
    static final int MAX_PROMPT_CHARS = 16_000;
    private static final String PROMPT_FALLBACK = "(вопрос задан изображением или звуком)";

    private final AssessmentRepository assessments;
    private final AttemptRepository attempts;
    private final AttemptConclusion conclusion;
    private final LearningCapabilities capabilities;
    private final UsageLedger ledger;
    private final AssessmentSettings settings;
    private final ApplicationEventPublisher events;
    private final MeterRegistry meters;
    private final TransactionTemplate transaction;

    public AssessmentService(AssessmentRepository assessments, AttemptRepository attempts, AttemptConclusion conclusion,
                             LearningCapabilities capabilities, UsageLedger ledger, AssessmentSettings settings,
                             ApplicationEventPublisher events, PlatformTransactionManager transactions, MeterRegistry meters) {
        this.assessments = assessments;
        this.attempts = attempts;
        this.conclusion = conclusion;
        this.capabilities = capabilities;
        this.ledger = ledger;
        this.settings = settings;
        this.events = events;
        this.meters = meters;
        this.transaction = new TransactionTemplate(transactions);
        this.transaction.setTimeout(10);
    }

    /** True for a presentation whose answer the model grades. */
    static boolean semantic(AttemptRepository.Presentation presentation) {
        return presentation.exerciseType().equals(ExerciseType.FREE_RESPONSE.name())
                && EvaluatorPolicy.SEMANTIC.equals(presentation.evaluator().path("id").asString(""));
    }

    // ------------------------------------------------------------------------------------------------ submit side

    /** An exact retry of a submit whose answer is not terminal yet: the current state; a changed retry conflicts. */
    Optional<AttemptService.SubmitResult> replay(AttemptCommand command, UUID actor, UUID deck, UUID session, byte[] hash) {
        Optional<AssessmentRepository.Row> found = assessments.find(command.attemptId());
        if (found.isEmpty()) return Optional.empty();
        AssessmentRepository.Row row = found.orElseThrow();
        if (!row.accountId().equals(actor) || !row.deckId().equals(deck) || !row.sessionId().equals(session)
                || !row.presentationId().equals(command.presentationId())
                || !AttemptRepository.same(row.payloadHash(), hash)) {
            throw new IdempotencyConflictException();
        }
        AttemptRepository.Presentation presentation = attempts.presentation(actor, deck, session, row.presentationId())
                .orElseThrow(IllegalStateException::new);
        JsonNode view = view(row, presentation);
        // the grade may have been stored between the receipt lookup of the caller and this read: then it is the outcome, not «accepted»
        return Optional.of(new AttemptService.SubmitResult(view, true, !row.state().equals("DONE")));
    }

    /** Whether an answer under any attempt id still holds the presentation. */
    boolean holds(UUID actor, UUID session, UUID presentation) {
        return assessments.holds(actor, session, presentation);
    }

    /**
     * Accepts an answer to an {@code ai-semantic} presentation inside the submit transaction: derives the strictness, records
     * the answer as {@code ASSESSING} and (after the commit) starts the grading. When the capability is gone or the fair-use
     * limit is spent, or the account already has {@code learning.ai.assess.max-in-flight} answers being graded ({@code BUSY}, a soft cap:
     * two submits in different sessions at the same instant may both pass), the answer goes straight to self-check, never to an
     * error. Empty when the learning epoch of a scheduled
     * presentation is no longer current: nothing is graded and nothing is spent.
     */
    Optional<AttemptService.SubmitResult> begin(UUID actor, UUID deck, UUID session,
                                                AttemptRepository.Presentation presentation, AttemptCommand command,
                                                byte[] hash, AttemptCommand.TextResponse text, Instant now) {
        boolean scheduled = presentation.mode().equals("SCHEDULED");
        Optional<AttemptRepository.State> state = assessments.state(actor, deck, presentation.objectiveId());
        if (scheduled && state.map(found -> found.learningEpoch() != presentation.learningEpoch()).orElse(true)) {
            return Optional.empty();
        }
        boolean current = state.filter(found -> found.learningEpoch() == presentation.learningEpoch()).isPresent();
        SemanticStrictness strictness = SemanticStrictness.select(
                current && state.orElseThrow().transitionSequence() > 0,
                current ? state.orElseThrow().level() : 0, current ? state.orElseThrow().correctStreak() : 0,
                !assessments.attemptedExercise(actor, presentation.exerciseId(), presentation.learningEpoch()));
        String unavailable = !capabilities.aiAssessment().available() ? "CAPABILITY_UNAVAILABLE"
                : !ledger.fairUseFits(actor, Bucket.ASSESSMENT, 1) ? "USAGE_LIMIT"
                : assessments.inFlight(actor) >= settings.maxInFlight() ? "BUSY" : null;
        AssessmentRepository.Row row = new AssessmentRepository.Row(command.attemptId(), actor, session,
                presentation.presentationId(), deck, unavailable == null ? "ASSESSING" : "UNAVAILABLE", unavailable,
                strictness.name(), hash, text.answerSource(), command.payload().path("response"), command.confidence(),
                command.durationMs(), now, now.plus(settings.deadline()), presentation.expiresAt());
        assessments.insert(row, now);
        count(unavailable == null ? "ACCEPTED" : "SELF_CHECK", unavailable);
        if (unavailable == null) events.publishEvent(new AssessmentAccepted(command.attemptId()));
        LOG.info("assessment_accepted attempt_id={} presentation_id={} strictness={} state={} reason={}", command.attemptId(),
                presentation.presentationId(), strictness, row.state(), unavailable == null ? "-" : unavailable);
        return Optional.of(new AttemptService.SubmitResult(view(row, presentation), false, true));
    }

    // ----------------------------------------------------------------------------------------------- read, choose

    /** The attempt as it is now: the stored outcome once terminal, else the assessment or self-check view. */
    @Transactional(readOnly = true, timeout = 10)
    public JsonNode read(UUID actor, UUID deck, UUID session, UUID attemptId) {
        own(actor, deck);
        Optional<AttemptRepository.Receipt> receipt = attempts.receipt(attemptId);
        if (receipt.isPresent()) return terminal(receipt.orElseThrow(), actor, deck, session);
        AssessmentRepository.Row row = owned(assessments.find(attemptId), actor, deck, session);
        return view(row, presentation(row));
    }

    /**
     * «Оценить себя»: the learner stops waiting. Compare-and-set {@code ASSESSING} to {@code SELF_CHECK}; a grade that arrives
     * later is discarded. Idempotent: the answer is whatever the attempt is now (it may already be graded).
     */
    @Transactional(timeout = 10)
    public JsonNode selfCheck(UUID actor, UUID deck, UUID session, UUID attemptId) {
        own(actor, deck);
        attempts.lockAttempt(attemptId);
        Optional<AttemptRepository.Receipt> receipt = attempts.receipt(attemptId);
        if (receipt.isPresent()) return terminal(receipt.orElseThrow(), actor, deck, session);
        AssessmentRepository.Row row = owned(assessments.forUpdate(attemptId), actor, deck, session);
        if (row.state().equals("ASSESSING")) {
            assessments.transition(attemptId, "ASSESSING", "SELF_CHECK", "LEARNER_CHOICE", attempts.now());
            row = assessments.find(attemptId).orElseThrow();
            count("SELF_CHECK", "LEARNER_CHOICE");
        }
        return view(row, presentation(row));
    }

    /**
     * The learner's own rating of an answer the model did not grade: the same attempt becomes terminal with {@code SELF_REPORT}
     * evidence of the {@code LOW} class, through the same reducer as a plain self-check. An exact retry replays.
     *
     * @throws AssessmentStateConflictException the model is still grading (choose self-check first)
     */
    @Transactional(timeout = 10)
    public AttemptService.SubmitResult selfRate(UUID actor, UUID deck, UUID session, UUID attemptId,
                                                AttemptCommand.SelfRating rating) {
        own(actor, deck);
        attempts.lockAttempt(attemptId);
        Optional<AttemptRepository.Receipt> receipt = attempts.receipt(attemptId);
        if (receipt.isPresent()) {
            AttemptRepository.Receipt done = receipt.orElseThrow();
            if (!done.accountId().equals(actor) || !done.deckId().equals(deck) || !done.sessionId().equals(session)) {
                throw new ResourceNotFoundException();
            }
            JsonNode codes = done.outcome() == null ? null : done.outcome().path("evidence").path("reasonCodes");
            boolean same = codes != null && codes.isArray() && contains(codes, "AI_FALLBACK") && contains(codes, rating.name());
            if (!same) throw new IdempotencyConflictException();
            return new AttemptService.SubmitResult(done.outcome(), true, false);
        }
        AssessmentRepository.Row row = owned(assessments.forUpdate(attemptId), actor, deck, session);
        if (row.state().equals("ASSESSING")) throw new AssessmentStateConflictException();
        attempts.lockSession(actor, deck, session);
        AttemptRepository.Presentation presentation = attempts.presentationForUpdate(actor, deck, session, row.presentationId())
                .orElseThrow(ResourceNotFoundException::new);
        Instant now = attempts.now();
        if (!presentation.expiresAt().isAfter(now)) throw new PresentationExpiredException();
        Rubric rubric = rubric(presentation);
        AttemptEvaluation evaluation = selfRated(rating, rubric, presentation, row.reason());
        AttemptService.SubmitResult result = conclusion.conclude(actor, deck, session, command(row, presentation), presentation,
                evaluation, SELF_CHECK_EVALUATOR, row.payloadHash(), now);
        assessments.finish(attemptId, now);
        count("SELF_RATED", row.reason());
        return result;
    }

    // ------------------------------------------------------------------------------------------------- grading

    /**
     * The grading request of an answer that is still ASSESSING, nobody has taken and has time left, else empty; it takes the answer
     * ({@link AssessmentRepository#claim}), so that the accepting process and a worker process never grade it twice. A short transaction of its
     * own: the runner calls the provider afterwards with no transaction open.
     */
    @Transactional(timeout = 10)
    public Optional<GradeRequest> prepare(UUID attemptId) {
        AssessmentRepository.Row row = assessments.find(attemptId).orElse(null);
        if (row == null || !row.state().equals("ASSESSING") || row.response() == null) return Optional.empty();
        Duration left = Duration.between(attempts.now(), row.deadlineAt());
        if (left.compareTo(Duration.ofMillis(250)) < 0) return Optional.empty();
        AttemptRepository.Presentation presentation = attempts.presentation(row.accountId(), row.deckId(), row.sessionId(),
                row.presentationId()).orElse(null);
        if (presentation == null || !assessments.claim(attemptId)) return Optional.empty();
        AnswerSource source = AnswerSource.valueOf(row.answerSource());
        return Optional.of(new GradeRequest(row.accountId(), attemptId, promptText(presentation), "", rubric(presentation),
                row.response().path("text").asString(""), source, settings.feedbackLanguage(),
                SemanticStrictness.valueOf(row.strictness()).runs(), left));
    }

    /** Answers that wait for a grader (nobody has taken them): what the worker's sweep grades when the process that accepted them had no grader. */
    @Transactional(readOnly = true, timeout = 10)
    public List<UUID> awaitingGrader(int limit) {
        return assessments.unclaimed(limit);
    }

    /**
     * Stores what the grader returned, in a short transaction of its own: the grade becomes the terminal attempt, the answer goes
     * to self-check (the grader was uncertain) or to unavailable (it failed). Discarded when the answer is no longer
     * ASSESSING. A grade that the learner's fair-use no longer covers goes to self-check with {@code USAGE_LIMIT}.
     */
    public void complete(UUID attemptId, GradeOutcome outcome) {
        for (int attempt = 1; ; attempt++) {
            try {
                transaction.executeWithoutResult(status -> resolve(attemptId, outcome));
                return;
            } catch (UsageLimitReachedException limit) {
                transaction.executeWithoutResult(status -> end(attemptId, "USAGE_LIMIT"));
                return;
            } catch (RuntimeException failure) {
                // a stored result that could not be written (a deadlock, a dropped connection): once more, then the learner is not kept waiting
                LOG.warn("assessment_complete_failed attempt_id={} attempt={} error_type={}", attemptId, attempt,
                        failure.getClass().getSimpleName());
                if (attempt < 2) continue;
                try {
                    transaction.executeWithoutResult(status -> end(attemptId, "PROVIDER_UNAVAILABLE"));
                } catch (RuntimeException unwritable) {
                    LOG.warn("assessment_end_failed attempt_id={} error_type={}", attemptId, unwritable.getClass().getSimpleName());
                }
                return;
            }
        }
    }

    private void end(UUID attemptId, String reason) {
        attempts.lockAttempt(attemptId);
        if (assessments.transition(attemptId, "ASSESSING", "UNAVAILABLE", reason, attempts.now())) count("UNAVAILABLE", reason);
    }

    private void resolve(UUID attemptId, GradeOutcome outcome) {
        attempts.lockAttempt(attemptId);
        AssessmentRepository.Row row = assessments.find(attemptId).orElse(null);
        if (row == null || !row.state().equals("ASSESSING")) {
            LOG.info("assessment_result_discarded attempt_id={} state={}", attemptId, row == null ? "-" : row.state());
            count("DISCARDED", null);
            return;
        }
        attempts.lockSession(row.accountId(), row.deckId(), row.sessionId());
        row = assessments.forUpdate(attemptId).orElseThrow();
        if (!row.state().equals("ASSESSING")) return;
        Instant now = attempts.now();
        // The sweeper is eventual. Fence the absolute deadline under the row lock before any delivered grade or fair-use debit.
        if (!now.isBefore(row.deadlineAt())) {
            assessments.transition(attemptId, "ASSESSING", "UNAVAILABLE", "DEADLINE", now);
            count("UNAVAILABLE", "DEADLINE");
            LOG.info("assessment_result_discarded attempt_id={} state=UNAVAILABLE reason=DEADLINE", attemptId);
            count("DISCARDED", null);
            return;
        }
        if (outcome instanceof GradeOutcome.Unavailable unavailable) {
            assessments.transition(attemptId, "ASSESSING", "UNAVAILABLE", unavailable.reason(), now);
            count("UNAVAILABLE", unavailable.reason());
            return;
        }
        AttemptRepository.Presentation presentation = attempts.presentationForUpdate(row.accountId(), row.deckId(),
                row.sessionId(), row.presentationId()).orElseThrow(IllegalStateException::new);
        if (row.response() == null) {
            assessments.transition(attemptId, "ASSESSING", "UNAVAILABLE", "ANSWER_GONE", now);
            count("UNAVAILABLE", "ANSWER_GONE");
            return;
        }
        Rubric rubric = rubric(presentation);
        SemanticStrictness strictness = SemanticStrictness.valueOf(row.strictness());
        SemanticPolicy.Outcome result = SemanticPolicy.aggregate(rubric, strictness,
                ((GradeOutcome.Graded) outcome).runs(), row.answerSource().equals(AttemptCommand.SPEECH));
        if (result instanceof SemanticPolicy.Uncertain uncertain) {
            LOG.info("assessment_uncertain attempt_id={} strictness={} cause={}", attemptId, strictness, uncertain.reason());
            assessments.transition(attemptId, "ASSESSING", "SELF_CHECK", "PROVIDER_UNCERTAIN", now);
            count("UNCERTAIN", uncertain.reason().name());
            return;
        }
        SemanticPolicy.Graded graded = (SemanticPolicy.Graded) result;
        // paid only for a delivered grade, in this transaction; a refusal rolls it back and the caller falls back to self-check
        ledger.consume(row.accountId(), Bucket.ASSESSMENT, 1, "assessment:" + attemptId, attemptId.toString());
        AttemptEvaluation evaluation = evaluation(graded, strictness, rubric, presentation, row, now);
        conclusion.conclude(row.accountId(), row.deckId(), row.sessionId(), command(row, presentation), presentation, evaluation,
                presentation.evaluator(), row.payloadHash(), now);
        assessments.finish(attemptId, now);
        count("GRADED", strictness.name());
        Timer.builder("mnema_assessment_seconds").description("From the accepted answer to its stored grade").publishPercentiles(0.5, 0.95)
                .tag("strictness", strictness.name()).register(meters).record(Duration.between(row.createdAt(), now));
        LOG.info("assessment_done attempt_id={} strictness={} judgement={}", attemptId, strictness, graded.judgement());
    }

    private AttemptEvaluation evaluation(SemanticPolicy.Graded graded, SemanticStrictness strictness, Rubric rubric,
                                         AttemptRepository.Presentation presentation, AssessmentRepository.Row row,
                                         Instant now) {
        AttemptEvaluation.Result result = graded.result();
        AttemptEvaluation.EvidenceClass evidence = strictness.evidence();
        boolean nextStricter = false;
        if (presentation.mode().equals("SCHEDULED")) {
            AttemptRepository.State state = attempts.stateForUpdate(row.accountId(), row.deckId(), presentation.objectiveId());
            if (state.learningEpoch() == presentation.learningEpoch()) {
                BaselineReducer.Transition after = conclusion.preview(state, result, evidence, now);
                nextStricter = SemanticStrictness.select(true, after.afterLevel(), after.afterCorrectStreak(), false)
                        .stricterThan(strictness);
            }
        }
        List<String> rules = List.of("AI_SEMANTIC", "STRICTNESS_" + strictness.name(), SemanticPolicy.RUBRIC);
        ObjectNode feedback = JSON.objectNode().put("result", result.name());
        ArrayNode applied = feedback.putArray("appliedRules");
        rules.forEach(applied::add);
        feedback.put("reference", rubric.referenceAnswer());
        feedback.set("referenceContent", presentation.reveal().path("reference").deepCopy());
        ObjectNode assessment = feedback.putObject("assessment");
        assessment.put("strictness", strictness.name()).put("judgement", graded.judgement().name());
        ArrayNode covered = assessment.putArray("covered");
        graded.covered().forEach(point -> covered.addObject().put("criterionId", point.criterionId().toString())
                .put("description", point.description()).put("quote", point.quote()).put("partial", point.partial()));
        ArrayNode missing = assessment.putArray("missing");
        graded.missing().forEach(point -> missing.addObject().put("criterionId", point.criterionId().toString())
                .put("description", point.description()).put("partial", point.partial()));
        ArrayNode contradicted = assessment.putArray("contradicted");
        graded.contradicted().forEach(point -> contradicted.addObject().put("criterionId", point.criterionId().toString())
                .put("description", point.description()).put("note", point.note()));
        assessment.put("nextStricter", nextStricter);
        List<String> reasons = new ArrayList<>(rules);
        if (graded.injection()) reasons.add("INJECTION");
        if (row.answerSource().equals(AttemptCommand.SPEECH)) reasons.add("SPEECH");
        return new AttemptEvaluation(AttemptEvaluation.Status.ASSESSED, result, evidence, List.copyOf(reasons), feedback);
    }

    private static AttemptEvaluation selfRated(AttemptCommand.SelfRating rating, Rubric rubric,
                                               AttemptRepository.Presentation presentation, String reason) {
        AttemptEvaluation.Result result = switch (rating) {
            case FULL -> AttemptEvaluation.Result.CORRECT;
            case PARTIAL, HINTED -> AttemptEvaluation.Result.PARTIAL;
            case NOT_RECALLED -> AttemptEvaluation.Result.INCORRECT;
        };
        ObjectNode feedback = JSON.objectNode().put("result", result.name());
        feedback.putArray("appliedRules").add("SELF_REPORT");
        feedback.put("reference", rubric.referenceAnswer());
        feedback.set("referenceContent", presentation.reveal().path("reference").deepCopy());
        return new AttemptEvaluation(AttemptEvaluation.Status.ASSESSED, result, AttemptEvaluation.EvidenceClass.LOW,
                List.of("SELF_REPORT", rating.name(), "AI_FALLBACK", reason == null ? "UNKNOWN" : reason), feedback);
    }

    // ---------------------------------------------------------------------------------------------------- sweeper

    /** The deadline sweeper: answers past the deadline become UNAVAILABLE (the learner then rates themselves). */
    public int expireOverdue() {
        int expired = assessments.expireOverdue(SWEEP_BATCH);
        if (expired > 0) {
            LOG.info("assessment_deadline_passed count={}", expired);
            meters.counter("mnema_assessment_total", "outcome", "UNAVAILABLE", "reason", "DEADLINE").increment(expired);
        }
        return expired;
    }

    /** Retention: answers kept in assessment rows past the presentation's expiry are cleared. */
    public int clearExpiredAnswers(int limit) {
        return assessments.clearAnswers(attempts.now(), limit);
    }

    // ---------------------------------------------------------------------------------------------------- dispute

    /**
     * «Оспорить оценку»: takes an AI grade back. Allowed only while the AI transition is the last transition of the objective
     * (the compare-and-set is the objective's state row, locked): then a compensating transition restores the before-state
     * (append-only, reason {@code AI_DISPUTED}), the attempt becomes {@code NOT_ASSESSED} with {@code disputed: true}, and a
     * counts-only row is written for the owner. The learner's answer text is copied only with {@code shareExample}. An exact
     * retry (same command id) replays.
     *
     * @throws DisputeNotAllowedException not an AI grade, already disputed, or a later transition moved the objective on
     */
    @Transactional(timeout = 10)
    public AttemptService.SubmitResult dispute(UUID actor, UUID deck, UUID session, UUID attemptId, DisputeCommand command) {
        own(actor, deck);
        attempts.lockAttempt(attemptId);
        AttemptRepository.Receipt receipt = attempts.receipt(attemptId).filter(found -> found.accountId().equals(actor)
                && found.deckId().equals(deck) && found.sessionId().equals(session) && found.outcome() != null)
                .orElseThrow(ResourceNotFoundException::new);
        Optional<AssessmentRepository.Dispute> prior = assessments.dispute(attemptId);
        if (prior.isPresent()) {
            if (!prior.orElseThrow().commandId().equals(command.commandId())) throw new DisputeNotAllowedException();
            return new AttemptService.SubmitResult(receipt.outcome(), true, false);
        }
        if (assessments.disputeCommandUsed(command.commandId())) throw new IdempotencyConflictException();
        JsonNode outcome = receipt.outcome();
        JsonNode grade = outcome.path("feedback").path("assessment");
        if (!receipt.status().equals("ASSESSED") || !grade.isObject()) throw new DisputeNotAllowedException();
        AttemptRepository.Presentation presentation = attempts.presentation(actor, deck, session, receipt.presentationId())
                .orElseThrow(ResourceNotFoundException::new);
        Instant now = attempts.now();
        if (presentation.mode().equals("SCHEDULED")) compensate(actor, deck, attemptId, presentation, now);
        JsonNode example = null;
        if (command.shareExample()) {
            JsonNode raw = assessments.rawResponse(attemptId).orElse(null);
            if (raw != null) {
                ObjectNode shared = JSON.objectNode().put("answer", raw.path("text").asString(""))
                        .put("strictness", grade.path("strictness").asString(""))
                        .put("judgement", grade.path("judgement").asString(""));
                shared.set("assessment", grade.deepCopy());
                example = shared;
            }
        }
        assessments.insertDispute(attemptId, command.commandId(), actor, deck, presentation.exerciseId(),
                presentation.exerciseRevisionId(), grade.path("strictness").asString("S1"),
                grade.path("judgement").asString("INSUFFICIENT"), example, now);
        ObjectNode taken = disputed(outcome, presentation);
        assessments.markDisputed(attemptId, taken);
        count("DISPUTED", grade.path("strictness").asString("S1"));
        LOG.info("assessment_disputed attempt_id={} strictness={} judgement={} shared={}", attemptId,
                grade.path("strictness").asString(""), grade.path("judgement").asString(""), example != null);
        return new AttemptService.SubmitResult(taken, false, false);
    }

    private void compensate(UUID actor, UUID deck, UUID attemptId, AttemptRepository.Presentation presentation, Instant now) {
        AttemptRepository.State state = attempts.stateForUpdate(actor, deck, presentation.objectiveId());
        AssessmentRepository.Transition original = assessments.transitionOf(attemptId)
                .orElseThrow(DisputeNotAllowedException::new);
        // compare-and-set: nothing came after the AI transition, in the same epoch
        if (original.learningEpoch() != state.learningEpoch() || original.sequence() != state.transitionSequence()) {
            throw new DisputeNotAllowedException();
        }
        // the before-state exactly: when the earlier attempt was accepted and when the objective fell due after it; with none, the
        // objective was never assessed, so it is unassessed again and due at once (as after a restart)
        Optional<AssessmentRepository.Standing> standing = assessments.standingBefore(actor, deck, presentation.objectiveId(),
                original.learningEpoch(), original.sequence());
        Instant due = standing.map(AssessmentRepository.Standing::nextDue).orElse(now);
        Instant assessed = standing.map(AssessmentRepository.Standing::acceptedAt).orElse(null);
        BaselineReducer.Transition restore = new BaselineReducer.Transition(state.level(), original.beforeLevel(),
                state.correctStreak(), original.beforeStreak(), state.lapseCount(), original.beforeLapses(), now, due);
        assessments.insertCompensation(attemptId, original, state, restore, "AI_DISPUTED");
        assessments.restoreState(state, restore, assessed, due, original.configId(), now);
    }

    private static ObjectNode disputed(JsonNode outcome, AttemptRepository.Presentation presentation) {
        ObjectNode taken = JSON.objectNode().put("attemptId", outcome.path("attemptId").asString(""))
                .put("presentationId", outcome.path("presentationId").asString("")).put("mode", presentation.mode())
                .put("status", "NOT_ASSESSED").put("disputed", true);
        taken.putNull("evidence");
        ObjectNode feedback = taken.putObject("feedback").put("result", "NOT_ASSESSED");
        feedback.putArray("reasonCodes").add("AI_DISPUTED");
        JsonNode before = outcome.path("feedback");
        if (before.has("reference")) feedback.set("reference", before.path("reference").deepCopy());
        if (before.has("referenceContent")) feedback.set("referenceContent", before.path("referenceContent").deepCopy());
        taken.putNull("transition");
        if (!presentation.mode().equals("SCHEDULED")) taken.put("canonicalEffects", false);
        return taken;
    }

    /** {@code mnema_assessment_total{outcome,reason}}: what became of the answers (reasons are the stable codes of the contract). */
    private void count(String outcome, String reason) {
        meters.counter("mnema_assessment_total", "outcome", outcome, "reason", reason == null ? "NONE" : reason).increment();
    }

    // ------------------------------------------------------------------------------------------------------ views

    /**
     * The state a client polls. A {@code DONE} row means the attempt is terminal, so what is returned is its stored outcome, never a
     * self-check view: the receipt is read again here, because the grade can be stored after a caller looked the receipt up and before
     * it read the row.
     */
    private JsonNode view(AssessmentRepository.Row row, AttemptRepository.Presentation presentation) {
        if (row.state().equals("DONE")) {
            return attempts.receipt(row.attemptId()).map(AttemptRepository.Receipt::outcome)
                    .orElseThrow(() -> new IllegalStateException("A finished assessment has no receipt"));
        }
        ObjectNode view = JSON.objectNode().put("attemptId", row.attemptId().toString())
                .put("presentationId", row.presentationId().toString()).put("mode", presentation.mode());
        if (row.state().equals("ASSESSING")) {
            boolean young = Duration.between(row.createdAt(), attempts.now()).compareTo(FAST_POLL_WINDOW) < 0;
            return view.put("status", "ASSESSING").put("retryAfterMs", young ? FAST_POLL_MS : SLOW_POLL_MS);
        }
        view.put("status", "SELF_CHECK");
        view.put("reason", row.reason() != null && PUBLIC_REASONS.contains(row.reason()) ? row.reason() : "PROVIDER_UNAVAILABLE");
        Rubric rubric = rubric(presentation);
        ObjectNode selfCheck = view.putObject("selfCheck").put("reference", rubric.referenceAnswer());
        selfCheck.set("referenceContent", presentation.reveal().path("reference").deepCopy());
        ArrayNode criteria = selfCheck.putArray("criteria");
        rubric.criteria().forEach(point -> criteria.addObject().put("criterionId", point.criterionId().toString())
                .put("description", point.description()));
        return view;
    }

    private JsonNode terminal(AttemptRepository.Receipt receipt, UUID actor, UUID deck, UUID session) {
        if (!receipt.accountId().equals(actor) || !receipt.deckId().equals(deck) || !receipt.sessionId().equals(session)
                || receipt.outcome() == null) {
            throw new ResourceNotFoundException();
        }
        return receipt.outcome();
    }

    private AttemptRepository.Presentation presentation(AssessmentRepository.Row row) {
        return attempts.presentation(row.accountId(), row.deckId(), row.sessionId(), row.presentationId())
                .orElseThrow(ResourceNotFoundException::new);
    }

    private static boolean contains(JsonNode array, String value) {
        for (JsonNode item : array) if (value.equals(item.stringValue(null))) return true;
        return false;
    }

    private void own(UUID actor, UUID deck) {
        UuidPolicy.requireEntityId(actor, "actor");
        UuidPolicy.requireEntityId(deck, "deckId");
        if (!attempts.ownsDeck(actor, deck)) throw new ResourceNotFoundException();
    }

    private static AssessmentRepository.Row owned(Optional<AssessmentRepository.Row> found, UUID actor, UUID deck, UUID session) {
        return found.filter(row -> row.accountId().equals(actor) && row.deckId().equals(deck) && row.sessionId().equals(session))
                .orElseThrow(ResourceNotFoundException::new);
    }

    /** The submit command of a stored answer, rebuilt for the shared conclusion (the raw response is the stored one). */
    private static AttemptCommand command(AssessmentRepository.Row row, AttemptRepository.Presentation presentation) {
        if (row.response() == null) throw new IllegalStateException("The answer is no longer stored");
        ObjectNode payload = JSON.objectNode().put("attemptId", row.attemptId().toString())
                .put("presentationId", row.presentationId().toString()).put("nonce", presentation.nonce());
        payload.set("response", row.response().deepCopy());
        if (row.confidence() == null) payload.putNull("confidence"); else payload.put("confidence", row.confidence());
        payload.put("durationMs", row.durationMs());
        return new AttemptCommand(row.attemptId(), row.presentationId(), presentation.nonce(),
                new AttemptCommand.TextResponse(row.response().path("text").asString(""), row.answerSource()),
                row.confidence(), row.durationMs(), payload);
    }

    private Rubric rubric(AttemptRepository.Presentation presentation) {
        JsonNode policy = assessments.evaluatorPolicy(presentation.deckId(), presentation.exerciseId(),
                presentation.exerciseRevisionId()).orElseThrow(IllegalStateException::new);
        try {
            return Rubric.read(policy.path("rubric"));
        } catch (InvalidRequestException invalid) {
            throw new IllegalStateException("A stored rubric is invalid", invalid);
        }
    }

    /** The question as text; a pathological prompt (a quoted material of any length) is cut so that the grading prompt always fits. */
    private static String promptText(AttemptRepository.Presentation presentation) {
        List<String> parts = new ArrayList<>();
        for (JsonNode block : presentation.content().path("prompt")) {
            if ("TEXT".equals(block.path("kind").stringValue(null)) && !block.path("text").asString("").isBlank()) {
                parts.add(block.path("text").stringValue());
            }
        }
        String text = String.join("\n", parts);
        if (text.length() > MAX_PROMPT_CHARS) text = text.substring(0, MAX_PROMPT_CHARS);
        return parts.isEmpty() ? PROMPT_FALLBACK : text;
    }

    /** What a dispute asks: the command id (idempotency) and whether the learner shares the example (always false in the UI today). */
    record DisputeCommand(UUID commandId, boolean shareExample) { }
}
