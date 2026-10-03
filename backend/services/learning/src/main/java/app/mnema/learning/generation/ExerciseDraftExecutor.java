package app.mnema.learning.generation;

import app.mnema.learning.ai.AiResult;
import app.mnema.learning.ai.AiRoute;
import app.mnema.learning.ai.OpaqueUserKey;
import app.mnema.learning.ai.OutputContract;
import app.mnema.learning.ai.TextGeneration;
import app.mnema.learning.ai.TextRequest;
import app.mnema.learning.ai.TextResponse;
import app.mnema.learning.ai.prompt.PromptException;
import app.mnema.learning.generation.ContextBuilder.SourceGoneException;
import app.mnema.learning.generation.ExerciseContexts.Request;
import app.mnema.learning.generation.Rows.Session;
import app.mnema.learning.generation.SessionLifecycle.ExerciseDraft;
import app.mnema.learning.generation.SessionLifecycle.Failure;
import app.mnema.learning.generation.SessionLifecycle.Proposal;
import app.mnema.learning.generation.StepExecutor.StepControl;
import app.mnema.learning.generation.exercise.ExerciseCode;
import app.mnema.learning.generation.exercise.ExerciseFinding;
import app.mnema.learning.generation.exercise.ExerciseIds;
import app.mnema.learning.generation.exercise.ExerciseRepairList;
import app.mnema.learning.generation.exercise.ExerciseValidator;
import app.mnema.learning.platform.json.ContentJsonReader;
import app.mnema.learning.usage.Reservation;
import app.mnema.learning.usage.UsageLedger;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * The {@code TEXT_DRAFT} step with {@code operation EXERCISES}: all the exercises of one target material, validated as a batch
 * ({@code contracts/generation/exercises/README.md}). One run is: {@link SessionLifecycle#beginExercises} (the artifacts become
 * GENERATING), the context from the database, the remaining reservation checked (no provider call when it cannot pay), then
 * the provider called <em>without a database transaction</em>. Every exercise of the answer is validated on its own
 * (schema, lint, compile, the publication parser, self-evaluation); the ones that pass are kept, and for the rest ONE repair
 * call lists the findings ({@code {index, code, path}}) and asks for exactly as many replacements, then one call on the strong
 * route, then the artifacts that still lack an exercise end {@code FAILED(INVALID_OUTPUT)} and are never shown. The valid exercises
 * are stored with their debit in one transaction, whole (there is no draft stream).
 *
 * <p>The answer asks for exactly {@code count} exercises: missing ones are repaired like invalid ones, extra ones are ignored.
 * Provider failures are the router's business first (it retries 429 and 5xx and falls back); what it hands back is mapped to a
 * step-level retry with backoff or a final failure by {@link ProviderFailures}. A retry starts the step over: nothing of an
 * earlier run was stored, so nothing is debited twice.
 */
@Component
class ExerciseDraftExecutor {
    /** The {@code operation} of the step input that selects this executor. */
    static final String OPERATION = "EXERCISES";
    private static final Logger LOG = LoggerFactory.getLogger(ExerciseDraftExecutor.class);
    private static final int ROUNDS = 3;
    private static final ContentJsonReader ANSWER = new ContentJsonReader(262_144, 24, 60_000);

    private final TextGeneration text;
    private final GenerationRepository repository;
    private final SessionLifecycle lifecycle;
    private final ExerciseContexts contexts;
    private final ExerciseValidator validator;
    private final UsageLedger ledger;
    private final ProviderKeys keys;
    private final GenerationSettings settings;
    private final MeterRegistry meters;

    ExerciseDraftExecutor(TextGeneration text, GenerationRepository repository, SessionLifecycle lifecycle, ExerciseContexts contexts,
                          ExerciseValidator validator, UsageLedger ledger, ProviderKeys keys, GenerationSettings settings,
                          MeterRegistry meters) {
        this.text = text;
        this.repository = repository;
        this.lifecycle = lifecycle;
        this.contexts = contexts;
        this.validator = validator;
        this.ledger = ledger;
        this.keys = keys;
        this.settings = settings;
        this.meters = meters;
    }

    void execute(StepClaim claim, StepControl control) {
        if (!lifecycle.beginExercises(claim)) return;
        Session session = repository.session(claim.sessionId()).orElse(null);
        if (session == null) return;
        List<UUID> artifacts = new ArrayList<>();
        claim.input().path("artifactIds").forEach(id -> artifacts.add(UUID.fromString(id.stringValue(""))));
        UUID member = UUID.fromString(claim.input().path("memberKey").stringValue(""));
        UUID revision = UUID.fromString(claim.input().path("itemRevisionId").stringValue(""));
        int count = claim.input().path("count").asInt(artifacts.size());

        Request request;
        try {
            request = contexts.build(session, member, revision, count, ExercisesSpec.read(session.spec()));
        } catch (SourceGoneException gone) {
            finish(claim, Failure.fail("SOURCE_UNAVAILABLE"));
            return;
        } catch (PromptException tooBig) {
            LOG.warn("generation_prompt_rejected step_id={} session_id={}", claim.stepId(), claim.sessionId());
            finish(claim, Failure.fail("INVALID_OUTPUT"));
            return;
        }

        int credits = claim.input().path("credits").asInt(0);
        // a retried artifact draws from its own reservation (step input), every other from the session's initial batch
        UUID reservationId = SessionReservations.forStep(session, claim.input());
        Optional<Reservation> reservation = reservationId == null ? Optional.empty() : ledger.reservation(claim.ownerId(), reservationId);
        if (reservation.isEmpty() || reservation.get().heldRemaining() < credits) {
            // The reservation cannot pay for these exercises: no provider call is made, a retry reserves again.
            finish(claim, Failure.fail("ESTIMATE_EXCEEDED"));
            return;
        }
        OpaqueUserKey key;
        try {
            key = keys.opaque(claim.ownerId());
        } catch (IllegalStateException notConfigured) {
            finish(claim, Failure.fail("PROVIDER_UNAVAILABLE"));
            return;
        }
        run(claim, control, session, artifacts, count, request, key);
    }

    private void run(StepClaim claim, StepControl control, Session session, List<UUID> artifacts, int count, Request request,
                     OpaqueUserKey key) {
        AiRoute route = AiRoute.TEXT_FAST;
        List<ExerciseValidator.Accepted> accepted = new ArrayList<>();
        String violations = null;
        long costMicros = 0;
        String modelRoute = "";
        for (int round = 0; round < ROUNDS; round++) {
            Duration remaining = Duration.between(Instant.now(), claim.deadlineAt());
            if (remaining.compareTo(Duration.ofMillis(250)) < 0) {
                finish(claim, Failure.fail("DEADLINE_EXCEEDED"));
                return;
            }
            if (round > 0) meters.counter("mnema_generation_repairs_total", "route", route.name().toLowerCase(Locale.ROOT)).increment();
            int needed = count - accepted.size();
            TextRequest call = new TextRequest(route, request.prompt().segments(), OutputContract.JSON, request.maxTokens(),
                    request.temperature(), min(remaining, Duration.ofHours(1)), key, null, claim.stepId(), claim.attempt());
            if (violations != null) call = call.withRepair(violations);
            AiResult<TextResponse> result;
            control.callStarted();
            try {
                result = text.generate(call);
            } finally {
                // An abort interrupts the thread only while it is inside the call; nothing may leak into the database calls below.
                control.callEnded();
            }
            if (control.lost()) return;
            if (control.cancelled()) {
                finish(claim, Failure.cancelled());
                return;
            }
            if (result instanceof AiResult.Failed<TextResponse> failed) {
                finish(claim, ProviderFailures.of(failed.failure(), claim, lifecycle));
                return;
            }
            TextResponse response = ((AiResult.Ok<TextResponse>) result).value();
            costMicros += response.costMicros();
            modelRoute = response.route().provider() + ":" + response.route().model();
            List<ExerciseFinding> findings = new ArrayList<>();
            if (response.finishReason() == TextResponse.FinishReason.LENGTH) {
                findings.add(new ExerciseFinding(-1, ExerciseCode.SCHEMA_INVALID, "$"));
            } else {
                validate(claim, response.text(), needed, request, accepted, findings);
            }
            if (accepted.size() >= count) {
                commit(claim, control, session, artifacts, accepted, request, modelRoute, costMicros, count);
                return;
            }
            findings.forEach(finding -> meters.counter("mnema_generation_exercise_findings_total", "code", finding.code().name()).increment());
            violations = ExerciseRepairList.format(findings, count - accepted.size());
            route = round == 0 ? route : AiRoute.TEXT_STRONG;
        }
        // every round is used: what passed is stored, the artifacts that got nothing end FAILED(INVALID_OUTPUT)
        meters.counter("mnema_generation_steps_total", "kind", TextDraftExecutor.KIND, "outcome", "invalid_output").increment();
        if (accepted.isEmpty()) {
            finish(claim, Failure.fail("INVALID_OUTPUT"));
            return;
        }
        commit(claim, control, session, artifacts, accepted, request, modelRoute, costMicros, count);
    }

    /**
     * Validates the exercises of one answer and appends the ones that pass. The first {@code needed} exercises count, extra ones are
     * ignored; an answer that is not the expected object, or holds fewer, fails the exercises it lacks.
     */
    private void validate(StepClaim claim, String answer, int needed, Request request, List<ExerciseValidator.Accepted> accepted,
                          List<ExerciseFinding> findings) {
        JsonNode root = parse(answer);
        JsonNode exercises = root == null ? null : root.path("exercises");
        if (exercises == null || !exercises.isArray() || exercises.isEmpty()) {
            findings.add(new ExerciseFinding(-1, ExerciseCode.SCHEMA_INVALID, "$"));
            return;
        }
        int considered = Math.min(needed, exercises.size());
        for (int index = 0; index < considered; index++) {
            ExerciseValidator.Verdict verdict = validator.validate(index, exercises.get(index), request.context(), ExerciseIds.random());
            switch (verdict) {
                case ExerciseValidator.Valid valid -> accepted.add(valid.exercise());
                case ExerciseValidator.Invalid invalid -> {
                    findings.addAll(invalid.findings());
                    if (invalid.findings().stream().anyMatch(finding -> finding.code() == ExerciseCode.COMMAND_REJECTED)) {
                        // a compiler or lint gap, not a fault of the model: logged without any content
                        LOG.warn("generation_exercise_command_rejected step_id={} session_id={} index={}", claim.stepId(), claim.sessionId(), index);
                    }
                }
            }
        }
        if (exercises.size() < needed) findings.add(new ExerciseFinding(-1, ExerciseCode.SCHEMA_INVALID, "exercises"));
    }

    /** The answer as one JSON object (a leading code fence of the model is tolerated), or null when it is not. */
    private static JsonNode parse(String answer) {
        String body = answer.strip();
        if (body.startsWith("```")) {
            int firstLine = body.indexOf('\n');
            int fence = body.lastIndexOf("```");
            if (firstLine > 0 && fence > firstLine) body = body.substring(firstLine + 1, fence).strip();
        }
        try {
            return ANSWER.read(body.getBytes(StandardCharsets.UTF_8));
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private void commit(StepClaim claim, StepControl control, Session session, List<UUID> artifacts,
                        List<ExerciseValidator.Accepted> accepted, Request request, String modelRoute, long providerCostMicros,
                        int count) {
        List<Proposal> proposals = new ArrayList<>();
        for (int index = 0; index < Math.min(accepted.size(), artifacts.size()); index++) {
            ExerciseValidator.Accepted exercise = accepted.get(index);
            proposals.add(new Proposal(artifacts.get(index), exercise.artifactCommand(), exercise.title()));
        }
        BigDecimal rubMicros = BigDecimal.valueOf(providerCostMicros).multiply(settings.usdRubRate()).setScale(0, RoundingMode.CEILING);
        ExerciseDraft draft = new ExerciseDraft(request.prompt().promptVersion(), modelRoute, proposals, rubMicros.longValueExact(),
                claim.input().path("credits").asInt(0), count);
        if (control.lost()) return;
        boolean stored = lifecycle.succeedExercises(claim, draft);
        meters.counter("mnema_generation_steps_total", "kind", TextDraftExecutor.KIND, "outcome", stored ? "succeeded" : "void").increment();
        LOG.info("generation_step_done step_id={} session_id={} kind={} attempt={} outcome={} cost_micros={} exercises={}", claim.stepId(),
                claim.sessionId(), TextDraftExecutor.KIND, claim.attempt(), stored ? "succeeded" : "void", providerCostMicros, proposals.size());
    }

    private void finish(StepClaim claim, Failure failure) {
        boolean stored = lifecycle.fail(claim, failure);
        LOG.info("generation_step_done step_id={} session_id={} kind={} attempt={} outcome={} error_code={} stored={}", claim.stepId(),
                claim.sessionId(), TextDraftExecutor.KIND, claim.attempt(), failure.kind().name().toLowerCase(Locale.ROOT),
                failure.errorCode() == null ? "-" : failure.errorCode(), stored);
        meters.counter("mnema_generation_steps_total", "kind", TextDraftExecutor.KIND, "outcome",
                failure.kind().name().toLowerCase(Locale.ROOT)).increment();
    }

    private static Duration min(Duration left, Duration right) { return left.compareTo(right) <= 0 ? left : right; }
}
