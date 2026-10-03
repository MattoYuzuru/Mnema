package app.mnema.learning.generation;

import app.mnema.learning.ai.AiResult;
import app.mnema.learning.ai.AiRoute;
import app.mnema.learning.ai.OpaqueUserKey;
import app.mnema.learning.ai.OutputContract;
import app.mnema.learning.ai.TextGeneration;
import app.mnema.learning.ai.TextRequest;
import app.mnema.learning.ai.TextResponse;
import app.mnema.learning.ai.prompt.PromptException;
import app.mnema.learning.catalog.exercise.ExerciseCommand;
import app.mnema.learning.generation.ContextBuilder.SourceGoneException;
import app.mnema.learning.generation.ExerciseContexts.EditRequest;
import app.mnema.learning.generation.Rows.Artifact;
import app.mnema.learning.generation.Rows.Revision;
import app.mnema.learning.generation.Rows.Session;
import app.mnema.learning.generation.Rows.Turn;
import app.mnema.learning.generation.SessionLifecycle.Failure;
import app.mnema.learning.generation.StepExecutor.StepControl;
import app.mnema.learning.generation.exercise.ExerciseCode;
import app.mnema.learning.generation.exercise.ExerciseFinding;
import app.mnema.learning.generation.exercise.ExerciseIds;
import app.mnema.learning.generation.exercise.ExerciseRepairList;
import app.mnema.learning.generation.exercise.ExerciseValidator;
import app.mnema.learning.usage.Reservation;
import app.mnema.learning.usage.UsageLedger;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayInputStream;
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
 * The rewrite of ONE existing exercise on an {@code EDIT} step ({@code REVISE_EXERCISE}, #294), called by {@link EditExecutor} once the
 * turn is RUNNING. The model gets the exercise in the strict-JSON output form of the exercise pipeline (local IDs and handles rebuilt from
 * the stored command, {@link ExerciseContexts#buildEdit}), the pinned material and the owner's instruction, and answers one exercise of
 * the same mechanic. That answer goes through the same pipeline as a generated exercise (schema, lint, compile with the identifiers the
 * model kept, the publication parser, self-evaluation); a rejected one is sent back once with the findings, then once more on the strong
 * route, then the turn is {@code FAILED(INVALID_OUTPUT)}. The objective is never changed by a revision (the current one is reused), the
 * enabled flag stays, and the audio blocks of the prompt are put back where they were. No database transaction is open during the
 * provider call; the result, its debit and the events are one transaction ({@link EditLifecycle#succeed}).
 */
@Component
class ExerciseEditExecutor {
    private static final Logger LOG = LoggerFactory.getLogger(ExerciseEditExecutor.class);
    private static final int ROUNDS = 3;

    private final TextGeneration text;
    private final EditLifecycle edits;
    private final SessionLifecycle lifecycle;
    private final ExerciseContexts contexts;
    private final ExerciseValidator validator;
    private final UsageLedger ledger;
    private final ProviderKeys keys;
    private final GenerationSettings settings;
    private final MeterRegistry meters;

    ExerciseEditExecutor(TextGeneration text, EditLifecycle edits, SessionLifecycle lifecycle, ExerciseContexts contexts,
                         ExerciseValidator validator, UsageLedger ledger, ProviderKeys keys, GenerationSettings settings,
                         MeterRegistry meters) {
        this.text = text;
        this.edits = edits;
        this.lifecycle = lifecycle;
        this.contexts = contexts;
        this.validator = validator;
        this.ledger = ledger;
        this.keys = keys;
        this.settings = settings;
        this.meters = meters;
    }

    void run(StepClaim claim, StepControl control, Session session, Artifact artifact, Revision revision, Turn turn) {
        JsonNode pin = artifact.sourceRefs().path(0);
        EditRequest request;
        try {
            Optional<EditRequest> built = contexts.buildEdit(session, UUID.fromString(pin.path("memberKey").stringValue("")),
                    UUID.fromString(pin.path("itemRevisionId").stringValue("")), revision.payload().path("command"),
                    turn.instruction() == null ? "" : turn.instruction(), session.spec().path("outputLanguage").stringValue(MaterialsSpec.DEFAULT_LANGUAGE));
            if (built.isEmpty()) {
                // admission checked this very exercise; reaching here means the revision on the artifact has no output form: a gap, not the model's fault
                LOG.warn("generation_exercise_edit_context_unreadable step_id={} session_id={}", claim.stepId(), claim.sessionId());
                finish(claim, Failure.fail("INVALID_OUTPUT"));
                return;
            }
            request = built.get();
        } catch (SourceGoneException gone) {
            finish(claim, Failure.fail("SOURCE_UNAVAILABLE"));
            return;
        } catch (PromptException tooBig) {
            LOG.warn("generation_prompt_rejected step_id={} session_id={}", claim.stepId(), claim.sessionId());
            finish(claim, Failure.fail("INVALID_OUTPUT"));
            return;
        }

        int credits = claim.input().path("credits").asInt(0);
        String reservationId = claim.input().path("reservationId").stringValue(null);
        Optional<Reservation> reservation = reservationId == null ? Optional.empty()
                : ledger.reservation(claim.ownerId(), UUID.fromString(reservationId));
        if (reservation.isEmpty() || reservation.get().heldRemaining() < credits) {
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
        rounds(claim, control, revision, request, key);
    }

    private void rounds(StepClaim claim, StepControl control, Revision revision, EditRequest request, OpaqueUserKey key) {
        AiRoute route = AiRoute.TEXT_FAST;
        String violations = null;
        long costMicros = 0;
        for (int round = 0; round < ROUNDS; round++) {
            Duration remaining = Duration.between(Instant.now(), claim.deadlineAt());
            if (remaining.compareTo(Duration.ofMillis(250)) < 0) {
                finish(claim, Failure.fail("DEADLINE_EXCEEDED"));
                return;
            }
            if (round > 0) meters.counter("mnema_generation_repairs_total", "route", route.name().toLowerCase(Locale.ROOT)).increment();
            TextRequest call = new TextRequest(route, request.prompt().segments(), OutputContract.JSON, request.maxTokens(),
                    request.temperature(), min(remaining, Duration.ofHours(1)), key, null, claim.stepId(), claim.attempt());
            if (violations != null) call = call.withRepair(violations);
            AiResult<TextResponse> result;
            control.callStarted();
            try {
                result = text.generate(call);
            } finally {
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
            String modelRoute = response.route().provider() + ":" + response.route().model();
            List<ExerciseFinding> findings = new ArrayList<>();
            if (response.finishReason() == TextResponse.FinishReason.LENGTH) {
                findings.add(new ExerciseFinding(-1, ExerciseCode.SCHEMA_INVALID, "$"));
            } else {
                Optional<EditLifecycle.Result> built = validate(response.text(), request, revision, modelRoute, costMicros, findings);
                if (built.isPresent()) {
                    commit(claim, control, built.get());
                    return;
                }
            }
            findings.forEach(finding -> meters.counter("mnema_generation_exercise_findings_total", "code", finding.code().name()).increment());
            violations = ExerciseRepairList.format(findings, 1);
            route = round == 0 ? route : AiRoute.TEXT_STRONG;
        }
        meters.counter("mnema_generation_steps_total", "kind", EditExecutor.KIND, "outcome", "invalid_output").increment();
        finish(claim, Failure.fail("INVALID_OUTPUT"));
    }

    /** The revision as a result when the answer's first exercise passes the pipeline; else empty with the findings added. */
    private Optional<EditLifecycle.Result> validate(String answer, EditRequest request, Revision revision, String modelRoute,
                                                    long costMicros, List<ExerciseFinding> findings) {
        JsonNode root = ExerciseDraftExecutor.parse(answer);
        JsonNode exercises = root == null ? null : root.path("exercises");
        if (exercises == null || !exercises.isArray() || exercises.isEmpty()) {
            findings.add(new ExerciseFinding(-1, ExerciseCode.SCHEMA_INVALID, "$"));
            return Optional.empty();
        }
        ExerciseValidator.Verdict verdict = validator.validate(0, exercises.get(0), request.context(), ExerciseIds.random(),
                request.current().ids());
        if (verdict instanceof ExerciseValidator.Invalid invalid) {
            findings.addAll(invalid.findings());
            return Optional.empty();
        }
        ExerciseValidator.Accepted accepted = ((ExerciseValidator.Valid) verdict).exercise();
        JsonNode stored = revision.payload().path("command");
        ObjectNode exercise = request.current().withMedia((ObjectNode) accepted.command().path("exercise"));
        exercise.put("enabled", stored.path("exercise").path("enabled").booleanValue(true));
        ObjectNode command = Json.object();
        // a revision never moves the exercise to another objective: the one it has is reused
        command.set("objective", stored.path("objective").deepCopy());
        command.set("exercise", exercise);
        try {
            ObjectNode full = Json.object().put("commandId", ExerciseContexts.PLACEHOLDER_COMMAND.toString())
                    .put("expectedDeckRevisionId", ExerciseContexts.PLACEHOLDER_DECK_REVISION.toString());
            full.set("objective", command.get("objective"));
            full.set("exercise", exercise);
            ExerciseCommand.readCreate(new ByteArrayInputStream(full.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (RuntimeException rejected) {
            LOG.warn("generation_exercise_command_rejected step_id=- index=0");
            findings.add(ExerciseFinding.of(ExerciseCode.COMMAND_REJECTED, null));
            return Optional.empty();
        }
        BigDecimal rubMicros = BigDecimal.valueOf(costMicros).multiply(settings.usdRubRate()).setScale(0, RoundingMode.CEILING);
        ObjectNode validation = Json.object();
        validation.putArray("warnings");
        return Optional.of(EditLifecycle.Result.ofExercise(command, ExerciseValidator.title(exercise, accepted.objectiveTitle()), validation,
                request.prompt().promptVersion(), modelRoute, rubMicros.longValueExact()));
    }

    private void commit(StepClaim claim, StepControl control, EditLifecycle.Result result) {
        if (control.lost()) return;
        boolean stored = edits.succeed(claim, result);
        meters.counter("mnema_generation_steps_total", "kind", EditExecutor.KIND, "outcome", stored ? "succeeded" : "void").increment();
        LOG.info("generation_step_done step_id={} session_id={} kind={} attempt={} outcome={} operation=REVISE_EXERCISE", claim.stepId(),
                claim.sessionId(), EditExecutor.KIND, claim.attempt(), stored ? "succeeded" : "void");
    }

    private void finish(StepClaim claim, Failure failure) {
        boolean stored = edits.fail(claim, failure);
        LOG.info("generation_step_done step_id={} session_id={} kind={} attempt={} outcome={} error_code={} stored={} operation=REVISE_EXERCISE",
                claim.stepId(), claim.sessionId(), EditExecutor.KIND, claim.attempt(), failure.kind().name().toLowerCase(Locale.ROOT),
                failure.errorCode() == null ? "-" : failure.errorCode(), stored);
        meters.counter("mnema_generation_steps_total", "kind", EditExecutor.KIND, "outcome", failure.kind().name().toLowerCase(Locale.ROOT)).increment();
    }

    private static Duration min(Duration left, Duration right) { return left.compareTo(right) <= 0 ? left : right; }
}
