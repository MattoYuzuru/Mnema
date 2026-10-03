package app.mnema.learning.generation;

import app.mnema.learning.ai.AiCapability;
import app.mnema.learning.ai.AiFailure;
import app.mnema.learning.ai.AiProperties;
import app.mnema.learning.ai.AiResult;
import app.mnema.learning.ai.AiRoute;
import app.mnema.learning.ai.OpaqueUserKey;
import app.mnema.learning.ai.OutputContract;
import app.mnema.learning.ai.TextGeneration;
import app.mnema.learning.ai.TextRequest;
import app.mnema.learning.ai.TextResponse;
import app.mnema.learning.ai.UserKeys;
import app.mnema.learning.ai.prompt.PromptException;
import app.mnema.learning.catalog.content.NativeDocument;
import app.mnema.learning.catalog.content.NativeDocumentPreview;
import app.mnema.learning.catalog.content.NativeDocumentReader;
import app.mnema.learning.generation.ContextBuilder.SourceGoneException;
import app.mnema.learning.generation.Rows.Artifact;
import app.mnema.learning.generation.Rows.Session;
import app.mnema.learning.generation.SessionLifecycle.Draft;
import app.mnema.learning.generation.SessionLifecycle.Failure;
import app.mnema.learning.generation.mbm.MbmAutoFixer;
import app.mnema.learning.generation.mbm.MbmCompiler;
import app.mnema.learning.generation.mbm.MbmFinding;
import app.mnema.learning.generation.mbm.MbmRepairList;
import app.mnema.learning.generation.mbm.MbmResult;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The {@code TEXT_DRAFT} step: one material from a prompt and pinned sources.
 *
 * <p>Order of a run: {@link SessionLifecycle#begin} (the artifact becomes GENERATING), the context is read from the
 * database, the remaining reservation is checked (no provider call is made when it cannot pay), then the provider is
 * called <em>without a database transaction</em>, streaming; checkpoints are short transactions of their own. The answer
 * is repaired deterministically ({@link MbmAutoFixer}), compiled ({@link MbmCompiler}, which ends in
 * {@code NativeDocumentReader}) and, if rejected, sent back once with the compact error list, then once more on the
 * strong route; a third rejection is {@code FAILED(INVALID_OUTPUT)} and nothing is debited. A valid draft is stored
 * with its debit, slots, child steps and events in one transaction.
 *
 * <p>Provider failures are the router's business first (it retries 429 and 5xx, repairs invalid JSON and falls back);
 * what it hands back is mapped here to a step-level retry with backoff or a final failure.
 */
@Component
class TextDraftExecutor implements StepExecutor {
    static final String KIND = "TEXT_DRAFT";
    private static final Logger LOG = LoggerFactory.getLogger(TextDraftExecutor.class);
    private static final UserKeys STUB_KEYS = UserKeys.withSecret("stub-user-key-secret-not-for-production", "stub");
    private static final int ROUNDS = 3;

    private final TextGeneration text;
    private final GenerationRepository repository;
    private final ContextRepository contextRepository;
    private final SessionLifecycle lifecycle;
    private final ContextBuilder contexts;
    private final UsageLedger ledger;
    private final UserKeys userKeys;
    private final AiProperties ai;
    private final GenerationSettings settings;
    private final MeterRegistry meters;
    private final MbmCompiler compiler = new MbmCompiler();

    TextDraftExecutor(TextGeneration text, GenerationRepository repository, ContextRepository contextRepository,
                      SessionLifecycle lifecycle, ContextBuilder contexts, UsageLedger ledger, UserKeys userKeys,
                      AiProperties ai, GenerationSettings settings, MeterRegistry meters) {
        this.text = text;
        this.repository = repository;
        this.contextRepository = contextRepository;
        this.lifecycle = lifecycle;
        this.contexts = contexts;
        this.ledger = ledger;
        this.userKeys = userKeys;
        this.ai = ai;
        this.settings = settings;
        this.meters = meters;
    }

    @Override public String kind() { return KIND; }

    @Override public AiCapability capability() { return AiCapability.TEXT; }

    @Override
    public void execute(StepClaim claim, StepControl control) {
        Optional<Integer> began = lifecycle.begin(claim);
        if (began.isEmpty()) return;
        Session session = repository.session(claim.sessionId()).orElse(null);
        Artifact artifact = repository.artifact(claim.sessionId(), claim.artifactId()).orElse(null);
        if (session == null || artifact == null) return;
        MaterialsSpec spec = MaterialsSpec.read(session.spec());

        DraftContext context;
        try {
            context = contexts.build(session, artifact, spec);
        } catch (SourceGoneException gone) {
            finish(claim, Failure.fail("SOURCE_UNAVAILABLE"));
            return;
        } catch (PromptException tooBig) {
            LOG.warn("generation_prompt_rejected step_id={} session_id={}", claim.stepId(), claim.sessionId());
            finish(claim, Failure.fail("INVALID_OUTPUT"));
            return;
        }

        int credits = claim.input().path("credits").asInt(0);
        Optional<Reservation> reservation = session.reservationId() == null ? Optional.empty()
                : ledger.reservation(claim.ownerId(), session.reservationId());
        if (reservation.isEmpty() || reservation.get().heldRemaining() < credits) {
            // The reservation cannot pay for this material: no provider call is made, a retry reserves again.
            finish(claim, Failure.fail("ESTIMATE_EXCEEDED"));
            return;
        }

        OpaqueUserKey key;
        try {
            key = (userKeys.configured() ? userKeys : stubKeys()).opaque(claim.ownerId());
        } catch (IllegalStateException notConfigured) {
            finish(claim, Failure.fail("PROVIDER_UNAVAILABLE"));
            return;
        }
        DraftStreamer streamer = new DraftStreamer(context.options(),
                (generation, start, blocks) -> lifecycle.checkpoint(claim, generation, start, blocks),
                () -> control.cancelled() || control.lost(), settings.stream().checkpointInterval(),
                settings.stream().maxEventBytes(), began.get());
        run(claim, control, session, context, key, streamer);
    }

    private UserKeys stubKeys() {
        if (!AiProperties.STUB.equals(ai.provider())) throw new IllegalStateException("learning.ai.user-key.secret is not configured");
        return STUB_KEYS;
    }

    private void run(StepClaim claim, StepControl control, Session session, DraftContext context, OpaqueUserKey key,
                     DraftStreamer streamer) {
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
                streamer.restart();
                meters.counter("mnema_generation_repairs_total", "route", route.name().toLowerCase(java.util.Locale.ROOT)).increment();
            }
            TextRequest request = new TextRequest(route, context.prompt().segments(), OutputContract.MBM_TEXT,
                    context.maxTokens(), context.temperature(), min(remaining, Duration.ofHours(1)), key, streamer,
                    claim.stepId(), claim.attempt());
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
            if (control.cancelled() || streamer.aborted()) {
                if (control.lost()) return;
                finish(claim, Failure.cancelled());
                return;
            }
            if (result instanceof AiResult.Failed<TextResponse> failed) {
                finish(claim, failure(failed.failure(), claim));
                return;
            }
            TextResponse response = ((AiResult.Ok<TextResponse>) result).value();
            costMicros += response.costMicros();
            String route0 = response.route().provider() + ":" + response.route().model();
            if (response.finishReason() == TextResponse.FinishReason.LENGTH) {
                violations = "ответ оборван по лимиту длины: напиши короче и закончи документ";
                route = next(route, round);
                continue;
            }
            MbmResult compiled = compiler.compile(MbmAutoFixer.fix(response.text()).text(), context.options(), new RandomIdAllocator());
            if (compiled instanceof MbmResult.Success success) {
                commit(claim, control, session, context, success, route0, costMicros);
                return;
            }
            List<MbmFinding> errors = ((MbmResult.Failure) compiled).errors();
            violations = MbmRepairList.format(errors);
            route = next(route, round);
        }
        meters.counter("mnema_generation_steps_total", "kind", KIND, "outcome", "invalid_output").increment();
        finish(claim, Failure.fail("INVALID_OUTPUT"));
    }

    /** The fast route repairs once; the next rejection goes to the strong route. */
    private static AiRoute next(AiRoute current, int round) {
        return round == 0 ? current : AiRoute.TEXT_STRONG;
    }

    private void commit(StepClaim claim, StepControl control, Session session, DraftContext context, MbmResult.Success success,
                        String modelRoute, long providerCostMicros) {
        JsonNode document = success.document();
        NativeDocument validated = new NativeDocumentReader().read(document.toString().getBytes(StandardCharsets.UTF_8));
        String title = NativeDocumentPreview.title(validated);
        Map<String, UUID> handles = new LinkedHashMap<>();
        int number = 1;
        for (JsonNode block : document.path("root").path("content")) {
            handles.put("b" + number++, UUID.fromString(block.path("id").stringValue("")));
        }
        ObjectNode validation = Json.object();
        ArrayNode warnings = validation.putArray("warnings");
        success.warnings().forEach(warning -> warnings.addObject().put("code", warning.code().name()).put("line", warning.line()));
        if (!firstBlockIsTitle(document)) warnings.addObject().put("code", "TITLE_MISSING");
        similar(session, title).ifPresent(warnings::add);

        BigDecimal rubMicros = BigDecimal.valueOf(providerCostMicros).multiply(settings.usdRubRate()).setScale(0, RoundingMode.CEILING);
        long ledgerCost = rubMicros.longValueExact();
        Draft draft = new Draft(context.prompt().promptVersion(), modelRoute, document, success.slots(), validation, title,
                ledgerCost, claim.input().path("operation").stringValue("MATERIAL_MEDIUM"),
                claim.input().path("credits").asInt(0), handles);
        if (control.lost()) return;
        boolean stored = lifecycle.succeed(claim, draft);
        meters.counter("mnema_generation_steps_total", "kind", KIND, "outcome", stored ? "succeeded" : "void").increment();
        LOG.info("generation_step_done step_id={} session_id={} kind={} attempt={} outcome={} cost_micros={} nodes={}", claim.stepId(),
                claim.sessionId(), KIND, claim.attempt(), stored ? "succeeded" : "void", providerCostMicros, success.nodeCount());
    }

    private static boolean firstBlockIsTitle(JsonNode document) {
        JsonNode first = document.path("root").path("content").path(0);
        return first.path("type").stringValue("").equals("heading") && first.path("attrs").path("level").asInt(0) == 1;
    }

    /** A material in the deck with a very similar title: shown to the user as "Похоже на ...". Skipped without pg_trgm. */
    private Optional<ObjectNode> similar(Session session, String title) {
        try {
            return contextRepository.similarTitle(session.deckId(), title, settings.similarTitle()).map(found ->
                    Json.object().put("code", "SIMILAR_TITLE").put("memberKey", found.memberKey().toString())
                            .put("title", found.title()));
        } catch (RuntimeException unavailable) {
            LOG.warn("generation_similar_title_skipped error_type={}", unavailable.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** Maps what the provider layer gave up with to a step-level retry or a final failure. */
    private Failure failure(AiFailure failure, StepClaim claim) {
        return switch (failure) {
            case AiFailure.RateLimited limited -> Failure.retry("PROVIDER_UNAVAILABLE",
                    max(limited.retryAfter(), lifecycle.backoff(claim.attempt())));
            case AiFailure.Transient ignored -> Failure.retry("PROVIDER_UNAVAILABLE", lifecycle.backoff(claim.attempt()));
            case AiFailure.CircuitOpen ignored -> Failure.retry("PROVIDER_UNAVAILABLE", lifecycle.backoff(claim.attempt()));
            case AiFailure.Timeout ignored -> Instant.now().isBefore(claim.deadlineAt())
                    ? Failure.retry("PROVIDER_UNAVAILABLE", lifecycle.backoff(claim.attempt())) : Failure.fail("DEADLINE_EXCEEDED");
            case AiFailure.InvalidOutput ignored -> Failure.fail("INVALID_OUTPUT");
            case AiFailure.Refusal ignored -> Failure.fail("REFUSAL");
            case AiFailure.BudgetExhausted ignored -> Failure.fail("PROVIDER_UNAVAILABLE");
            case AiFailure.NotConfigured ignored -> Failure.fail("PROVIDER_UNAVAILABLE");
        };
    }

    private void finish(StepClaim claim, Failure failure) {
        boolean stored = lifecycle.fail(claim, failure);
        LOG.info("generation_step_done step_id={} session_id={} kind={} attempt={} outcome={} error_code={} stored={}", claim.stepId(),
                claim.sessionId(), KIND, claim.attempt(), failure.kind().name().toLowerCase(java.util.Locale.ROOT),
                failure.errorCode() == null ? "-" : failure.errorCode(), stored);
        meters.counter("mnema_generation_steps_total", "kind", KIND, "outcome", failure.kind().name().toLowerCase(java.util.Locale.ROOT)).increment();
    }

    private static Duration min(Duration left, Duration right) { return left.compareTo(right) <= 0 ? left : right; }

    private static Duration max(Duration left, Duration right) { return left.compareTo(right) >= 0 ? left : right; }
}
