package app.mnema.learning.generation;

import app.mnema.learning.ai.AiCapability;
import app.mnema.learning.ai.AiResult;
import app.mnema.learning.ai.AiRoute;
import app.mnema.learning.ai.OpaqueUserKey;
import app.mnema.learning.ai.OutputContract;
import app.mnema.learning.ai.TextGeneration;
import app.mnema.learning.ai.TextRequest;
import app.mnema.learning.ai.TextResponse;
import app.mnema.learning.ai.prompt.PromptException;
import app.mnema.learning.catalog.content.NativeDocument;
import app.mnema.learning.catalog.content.NativeDocumentPreview;
import app.mnema.learning.catalog.content.NativeDocumentReader;
import app.mnema.learning.generation.ContextBuilder.SourceGoneException;
import app.mnema.learning.generation.EditContexts.EditContext;
import app.mnema.learning.generation.Rows.Artifact;
import app.mnema.learning.generation.Rows.Revision;
import app.mnema.learning.generation.Rows.Session;
import app.mnema.learning.generation.Rows.Turn;
import app.mnema.learning.generation.SessionLifecycle.Failure;
import app.mnema.learning.generation.mbm.MbmAutoFixer;
import app.mnema.learning.generation.mbm.MbmCode;
import app.mnema.learning.generation.mbm.MbmCompiler;
import app.mnema.learning.generation.mbm.MbmFinding;
import app.mnema.learning.generation.mbm.MbmRepairList;
import app.mnema.learning.generation.mbm.MbmResult;
import app.mnema.learning.generation.mbm.MbmUnsupportedContentException;
import app.mnema.learning.generation.mbm.RandomIdAllocator;
import app.mnema.learning.usage.Reservation;
import app.mnema.learning.usage.UsageLedger;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * The {@code EDIT} step: one rewrite of a run of blocks of a proposed material ({@code ai/prompts/v1/edit.md}, architecture section 7).
 *
 * <p>Order of a run: {@link EditLifecycle#begin} (the turn is RUNNING), the context from the database, the turn's hold checked (no
 * provider call when it cannot pay), then the provider is called <em>without a database transaction</em>, not streaming. The answer
 * is repaired deterministically, compiled by {@link MbmCompiler} in edit mode (the handles of the target blocks keep their node IDs;
 * a handle that is not a target is an error; no media) and spliced into the current document: every block outside the target is the
 * very same JSON, and the result must be read by {@code NativeDocumentReader}. A rejected answer is sent back once with the compact
 * error list, then once more on the strong route; a third rejection fails the turn with {@code INVALID_OUTPUT}, the artifact is
 * PROPOSED on the revision it had and nothing is debited. A valid rewrite is stored with its debit, slots and events in one transaction.
 */
@Component
class EditExecutor implements StepExecutor {
    static final String KIND = "EDIT";
    private static final Logger LOG = LoggerFactory.getLogger(EditExecutor.class);
    private static final int ROUNDS = 3;

    private final TextGeneration text;
    private final GenerationRepository repository;
    private final EditLifecycle edits;
    private final SessionLifecycle lifecycle;
    private final EditContexts contexts;
    private final UsageLedger ledger;
    private final ProviderKeys keys;
    private final GenerationSettings settings;
    private final MeterRegistry meters;
    private final MbmCompiler compiler = new MbmCompiler();

    EditExecutor(TextGeneration text, GenerationRepository repository, EditLifecycle edits, SessionLifecycle lifecycle,
                 EditContexts contexts, UsageLedger ledger, ProviderKeys keys, GenerationSettings settings, MeterRegistry meters) {
        this.text = text;
        this.repository = repository;
        this.edits = edits;
        this.lifecycle = lifecycle;
        this.contexts = contexts;
        this.ledger = ledger;
        this.keys = keys;
        this.settings = settings;
        this.meters = meters;
    }

    @Override public String kind() { return KIND; }

    @Override public AiCapability capability() { return AiCapability.TEXT; }

    @Override
    public void execute(StepClaim claim, StepControl control) {
        Optional<Turn> began = edits.begin(claim);
        if (began.isEmpty()) return;
        Turn turn = began.get();
        Session session = repository.session(claim.sessionId()).orElse(null);
        Artifact artifact = repository.artifact(claim.sessionId(), claim.artifactId()).orElse(null);
        Revision revision = artifact == null ? null : repository.revision(artifact.artifactId(),
                UUID.fromString(claim.input().path("revisionId").stringValue(""))).orElse(null);
        if (session == null || artifact == null || revision == null) return;

        EditContext context;
        try {
            context = contexts.build(session, artifact, revision, turn);
        } catch (SourceGoneException gone) {
            finish(claim, Failure.fail("SOURCE_UNAVAILABLE"));
            return;
        } catch (PromptException tooBig) {
            LOG.warn("generation_prompt_rejected step_id={} session_id={}", claim.stepId(), claim.sessionId());
            finish(claim, Failure.fail("INVALID_OUTPUT"));
            return;
        } catch (MbmUnsupportedContentException | IllegalStateException | IllegalArgumentException unreadable) {
            // the target was validated at admission against this very revision: reaching here is a gap of the renderer, never the model's fault
            LOG.warn("generation_edit_context_unreadable step_id={} session_id={} error_type={}", claim.stepId(), claim.sessionId(),
                    unreadable.getClass().getSimpleName());
            finish(claim, Failure.fail("INVALID_OUTPUT"));
            return;
        }

        int credits = claim.input().path("credits").asInt(0);
        String reservationId = claim.input().path("reservationId").stringValue(null);
        Optional<Reservation> reservation = reservationId == null ? Optional.empty()
                : ledger.reservation(claim.ownerId(), UUID.fromString(reservationId));
        if (reservation.isEmpty() || reservation.get().heldRemaining() < credits) {
            // the turn's own hold cannot pay: no provider call is made
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
        run(claim, control, artifact, revision, context, key);
    }

    private void run(StepClaim claim, StepControl control, Artifact artifact, Revision revision, EditContext context, OpaqueUserKey key) {
        AiRoute route = AiRoute.TEXT_FAST;
        String violations = null;
        long costMicros = 0;
        for (int round = 0; round < ROUNDS; round++) {
            Duration remaining = Duration.between(Instant.now(), claim.deadlineAt());
            if (remaining.compareTo(Duration.ofMillis(250)) < 0) {
                finish(claim, Failure.fail("DEADLINE_EXCEEDED"));
                return;
            }
            if (round > 0) {
                meters.counter("mnema_generation_repairs_total", "route", route.name().toLowerCase(Locale.ROOT)).increment();
            }
            TextRequest request = new TextRequest(route, context.prompt().segments(), OutputContract.MBM_TEXT, context.maxTokens(),
                    context.temperature(), min(remaining, Duration.ofHours(1)), key, null, claim.stepId(), claim.attempt());
            if (violations != null) request = request.withRepair(violations);
            AiResult<TextResponse> result;
            control.callStarted();
            try {
                result = text.generate(request);
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
            String modelRoute = response.route().provider() + ":" + response.route().model();
            if (response.finishReason() == TextResponse.FinishReason.LENGTH) {
                violations = "ответ оборван по лимиту длины: перепиши короче и закончи";
                route = next(route, round);
                continue;
            }
            MbmResult compiled = compiler.compile(MbmAutoFixer.fix(unwrap(unescape(response.text()))).text(), context.options(),
                    new RandomIdAllocator());
            if (compiled instanceof MbmResult.Success success) {
                Optional<EditLifecycle.Result> built = splice(artifact, revision, context, success, modelRoute, costMicros);
                if (built.isPresent()) {
                    commit(claim, control, built.get());
                    return;
                }
                violations = MbmRepairList.format(List.of(new MbmFinding(1, null, MbmCode.MBM_DOCUMENT_TOO_LARGE, null)));
            } else {
                violations = MbmRepairList.format(((MbmResult.Failure) compiled).errors());
            }
            route = next(route, round);
        }
        meters.counter("mnema_generation_steps_total", "kind", KIND, "outcome", "invalid_output").increment();
        finish(claim, Failure.fail("INVALID_OUTPUT"));
    }

    /** The fast route repairs once; the next rejection goes to the strong route. */
    private static AiRoute next(AiRoute current, int round) {
        return round == 0 ? current : AiRoute.TEXT_STRONG;
    }

    /**
     * The compiled range spliced into the current document, read by {@code NativeDocumentReader}; empty when the document would
     * not be accepted (too large, too many nodes). Blocks outside the target are carried over unchanged, and the media blocks of
     * the target stay where the rewrite ends.
     */
    private Optional<EditLifecycle.Result> splice(Artifact artifact, Revision revision, EditContext context, MbmResult.Success success,
                                                  String modelRoute, long providerCostMicros) {
        String promptVersion = context.prompt().promptVersion();
        List<JsonNode> rewritten = EditDocument.blocks(success.document());
        JsonNode merged = EditDocument.replace(revision.payload().path("document"), context.target().from(), context.target().to(),
                rewritten, context.target().media());
        NativeDocument validated;
        try {
            validated = new NativeDocumentReader().read(merged.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IllegalArgumentException rejected) {
            return Optional.empty();
        }
        String title = NativeDocumentPreview.title(validated);
        ObjectNode validation = Json.object();
        ArrayNode warnings = validation.putArray("warnings");
        success.warnings().forEach(warning -> warnings.addObject().put("code", warning.code().name()).put("line", warning.line()));
        if (!firstBlockIsTitle(merged)) warnings.addObject().put("code", "TITLE_MISSING");
        // the earlier proposal's similar-title warning stays while the title it was about does
        if (title.equals(artifact.title())) {
            for (JsonNode earlier : revision.validation().path("warnings")) {
                if (earlier.path("code").stringValue("").equals("SIMILAR_TITLE")) warnings.add(earlier.deepCopy());
            }
        }
        BigDecimal rubMicros = BigDecimal.valueOf(providerCostMicros).multiply(settings.usdRubRate()).setScale(0, RoundingMode.CEILING);
        return Optional.of(new EditLifecycle.Result(merged, title, validation, promptVersion, modelRoute, rubMicros.longValueExact(),
                EditDocument.handles(merged)));
    }

    private void commit(StepClaim claim, StepControl control, EditLifecycle.Result result) {
        if (control.lost()) return;
        boolean stored = edits.succeed(claim, result);
        meters.counter("mnema_generation_steps_total", "kind", KIND, "outcome", stored ? "succeeded" : "void").increment();
        LOG.info("generation_step_done step_id={} session_id={} kind={} attempt={} outcome={}", claim.stepId(), claim.sessionId(), KIND,
                claim.attempt(), stored ? "succeeded" : "void");
    }

    private void finish(StepClaim claim, Failure failure) {
        boolean stored = edits.fail(claim, failure);
        LOG.info("generation_step_done step_id={} session_id={} kind={} attempt={} outcome={} error_code={} stored={}", claim.stepId(),
                claim.sessionId(), KIND, claim.attempt(), failure.kind().name().toLowerCase(Locale.ROOT),
                failure.errorCode() == null ? "-" : failure.errorCode(), stored);
        meters.counter("mnema_generation_steps_total", "kind", KIND, "outcome", failure.kind().name().toLowerCase(Locale.ROOT)).increment();
    }

    /**
     * The prompt layer escapes {@code & < > "} in everything it shows the model, so the model sees the entities and may answer with them;
     * unescaping the answer once is the exact inverse, so a character the author wrote comes back as the author wrote it.
     */
    static String unescape(String text) {
        return text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&");
    }

    /** An answer wrapped in the {@code <target>} tags it was shown in is still the answer. */
    static String unwrap(String text) {
        String stripped = text.strip();
        if (stripped.startsWith("<target>") && stripped.endsWith("</target>")) {
            return stripped.substring("<target>".length(), stripped.length() - "</target>".length()).strip();
        }
        return text;
    }

    private static boolean firstBlockIsTitle(JsonNode document) {
        JsonNode first = document.path("root").path("content").path(0);
        return first.path("type").stringValue("").equals("heading") && first.path("attrs").path("level").asInt(0) == 1;
    }

    private static Duration min(Duration left, Duration right) { return left.compareTo(right) <= 0 ? left : right; }
}
