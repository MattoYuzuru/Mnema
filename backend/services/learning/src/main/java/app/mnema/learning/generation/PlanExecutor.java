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
import app.mnema.learning.generation.ContextBuilder.SourceGoneException;
import app.mnema.learning.generation.Rows.Session;
import app.mnema.learning.generation.SessionLifecycle.Failure;
import app.mnema.learning.usage.AdmissionPricing;
import app.mnema.learning.usage.Bucket;
import app.mnema.learning.usage.Reservation;
import app.mnema.learning.usage.UsageLedger;
import app.mnema.learning.usage.UsageLimitReachedException;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * The {@code PLAN} step of a plan-first session ({@code contracts/generation/README.md}, decision 17): one provider call, with thinking, that turns the
 * budget and what the deck already holds into a plan of exercises or materials; nothing is generated and no artifact exists yet. A run is
 * {@link SessionLifecycle#beginPlan} (the session must still be PLANNING), the smart-plan cap and the plan's hold checked (no provider call when either
 * cannot pay), the context from the database, then the provider called <em>without a database transaction</em>.
 *
 * <p>The answer is validated by {@link Plans#fromModel}: a shape violation sends it back ONCE ({@code {findings}} as the repair), then one call on the
 * strong plan route, then the step fails and the session is {@code CANCELLED} ({@code endReason PLAN_FAILED}, the holds released, nothing debited). A
 * plan that merely costs more than the batch hold is trimmed from the end and says so. A valid plan is stored with the plan's debit
 * ({@code SMART_PLAN_FLASH}, apart from the batch), the count of the smart-plan cap and the move to {@code PLAN_READY} in one transaction
 * ({@link SessionLifecycle#succeedPlan}). Provider failures are the router's business first; what it hands back is mapped by {@link ProviderFailures}.
 */
@Component
class PlanExecutor implements StepExecutor {
    static final String KIND = "PLAN";
    private static final Logger LOG = LoggerFactory.getLogger(PlanExecutor.class);
    private static final int ROUNDS = 3;

    private final TextGeneration text;
    private final GenerationRepository repository;
    private final SessionLifecycle lifecycle;
    private final PlanContexts contexts;
    private final Plans plans;
    private final UsageLedger ledger;
    private final AdmissionPricing pricing;
    private final ProviderKeys keys;
    private final GenerationSettings settings;
    private final MeterRegistry meters;

    PlanExecutor(TextGeneration text, GenerationRepository repository, SessionLifecycle lifecycle, PlanContexts contexts, Plans plans,
                 UsageLedger ledger, AdmissionPricing pricing, ProviderKeys keys, GenerationSettings settings,
                 MeterRegistry meters) {
        this.text = text;
        this.repository = repository;
        this.lifecycle = lifecycle;
        this.contexts = contexts;
        this.plans = plans;
        this.ledger = ledger;
        this.pricing = pricing;
        this.keys = keys;
        this.settings = settings;
        this.meters = meters;
    }

    @Override public String kind() { return KIND; }

    @Override public AiCapability capability() { return AiCapability.TEXT; }

    @Override
    public void execute(StepClaim claim, StepControl control) {
        if (!lifecycle.beginPlan(claim)) return;
        Session session = repository.session(claim.sessionId()).orElse(null);
        if (session == null) return;

        // the smart-plan cap and the plan's own hold are checked before the call: a provider call that could not be paid is never made
        if (!ledger.fairUseFits(claim.ownerId(), Bucket.SMART_PLAN, 1)) {
            finish(claim, Failure.fail("USAGE_LIMIT"));
            return;
        }
        int credits = claim.input().path("credits").asInt(0);
        UUID reservationId = SessionReservations.forStep(session, claim.input());
        Optional<Reservation> reservation = reservationId == null ? Optional.empty() : ledger.reservation(claim.ownerId(), reservationId);
        if (reservation.isEmpty() || reservation.get().heldRemaining() < credits) {
            finish(claim, Failure.fail("ESTIMATE_EXCEEDED"));
            return;
        }
        int hold = claim.input().path("budgetCredits").asInt(0);
        PlanContexts.Request request;
        try {
            request = contexts.build(session, hold);
        } catch (SourceGoneException gone) {
            finish(claim, Failure.fail("SOURCE_UNAVAILABLE"));
            return;
        } catch (PromptException tooBig) {
            LOG.warn("generation_prompt_rejected step_id={} session_id={}", claim.stepId(), claim.sessionId());
            finish(claim, Failure.fail("INVALID_OUTPUT"));
            return;
        }
        OpaqueUserKey key;
        try {
            key = keys.opaque(claim.ownerId());
        } catch (IllegalStateException notConfigured) {
            finish(claim, Failure.fail("PROVIDER_UNAVAILABLE"));
            return;
        }
        run(claim, control, session, request, key, hold, credits);
    }

    private void run(StepClaim claim, StepControl control, Session session, PlanContexts.Request request, OpaqueUserKey key, int hold,
                     int credits) {
        AiRoute route = AiRoute.PLAN;
        String violations = null;
        long costMicros = 0;
        for (int round = 0; round < ROUNDS; round++) {
            Duration remaining = Duration.between(Instant.now(), claim.deadlineAt());
            if (remaining.compareTo(Duration.ofMillis(250)) < 0) {
                finish(claim, Failure.fail("DEADLINE_EXCEEDED"));
                return;
            }
            if (round > 0) meters.counter("mnema_generation_repairs_total", "route", route.name().toLowerCase(Locale.ROOT)).increment();
            TextRequest call = new TextRequest(route, request.prompt().segments(), OutputContract.JSON, settings.planner().maxOutputTokens(),
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
            if (response.finishReason() == TextResponse.FinishReason.LENGTH) {
                violations = "items: ANSWER_CUT_OFF, ответ оборван по лимиту длины: сократи рассуждение и закончи json";
            } else {
                Plans.Parsed parsed = plans.fromModel(ExerciseDraftExecutor.parse(response.text()), request.basis(), hold);
                if (parsed instanceof Plans.Parsed.Ok ok) {
                    commit(claim, control, session, request, ok.draft(), hold, credits, costMicros);
                    return;
                }
                violations = ((Plans.Parsed.Invalid) parsed).findings();
            }
            // the fast route repairs once; the next rejection goes to the strong route
            route = round == 0 ? route : AiRoute.PLAN_STRONG;
        }
        meters.counter("mnema_generation_steps_total", "kind", KIND, "outcome", "invalid_output").increment();
        finish(claim, Failure.fail("INVALID_OUTPUT"));
    }

    private void commit(StepClaim claim, StepControl control, Session session, PlanContexts.Request request, Plans.Draft draft, int hold,
                        int credits, long providerCostMicros) {
        BigDecimal rubMicros = BigDecimal.valueOf(providerCostMicros).multiply(settings.usdRubRate()).setScale(0, RoundingMode.CEILING);
        ObjectNode plan = plans.wire(request.basis(), draft, request.targets(), request.sources(), credits, hold,
                pricing.barCredits(session.ownerId()), false);
        if (control.lost()) return;
        boolean stored;
        try {
            stored = lifecycle.succeedPlan(claim, plan, rubMicros.longValueExact(), draft.size());
        } catch (UsageLimitReachedException capReached) {
            // the cap filled between the early check and the debit (a second plan finished first): nothing was written, the plan is not paid
            finish(claim, Failure.fail("USAGE_LIMIT"));
            return;
        }
        meters.counter("mnema_generation_steps_total", "kind", KIND, "outcome", stored ? "succeeded" : "void").increment();
        LOG.info("generation_step_done step_id={} session_id={} kind={} attempt={} outcome={} cost_micros={} items={}", claim.stepId(),
                claim.sessionId(), KIND, claim.attempt(), stored ? "succeeded" : "void", providerCostMicros, draft.size());
    }

    private void finish(StepClaim claim, Failure failure) {
        boolean stored = lifecycle.fail(claim, failure);
        LOG.info("generation_step_done step_id={} session_id={} kind={} attempt={} outcome={} error_code={} stored={}", claim.stepId(),
                claim.sessionId(), KIND, claim.attempt(), failure.kind().name().toLowerCase(Locale.ROOT),
                failure.errorCode() == null ? "-" : failure.errorCode(), stored);
        meters.counter("mnema_generation_steps_total", "kind", KIND, "outcome", failure.kind().name().toLowerCase(Locale.ROOT)).increment();
    }

    private static Duration min(Duration left, Duration right) { return left.compareTo(right) <= 0 ? left : right; }
}
