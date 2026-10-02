package app.mnema.learning.usage;

import app.mnema.learning.platform.api.CapabilityUnavailableException;
import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.api.ProblemExtension;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.platform.id.UuidPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The preflight cost of a spec or of one edit: a pure function of the spec, the rate card and the current balance. It
 * reserves and persists nothing; pressing "Create" later re-checks everything inside the admission transaction.
 */
@Service
class EstimateService {
    private static final Set<String> EDIT_ACTIONS = Set.of("REWRITE", "IMAGE_SEARCH", "IMAGE_GENERATE", "AUDIO_REGENERATE",
            "FREE", "REMOVE_MEDIA");
    private static final int MAX_TARGET_NODES = 50;

    private final UsageRepository repository;
    private final UsageState state;
    private final RateCard rateCard;
    private final GenerationSpecInterpreter interpreter;
    private final UsageClock clock;

    EstimateService(UsageRepository repository, UsageState state, RateCard rateCard,
                    GenerationSpecInterpreter interpreter, UsageClock clock) {
        this.repository = repository;
        this.state = state;
        this.rateCard = rateCard;
        this.interpreter = interpreter;
        this.clock = clock;
    }

    EstimateView estimate(UUID owner, UUID deckId, JsonNode body) {
        return estimate(owner, deckId, () -> body);
    }

    /**
     * @param request supplies the parsed body, exactly one of {@code spec} or {@code edit}; it is called only after
     *                the deck is known to be the owner's, so a stranger learns nothing from how a body fails
     * @throws ResourceNotFoundException a foreign or absent deck, indistinguishable
     * @throws InvalidRequestException   a malformed body or spec
     */
    @Transactional(readOnly = true)
    EstimateView estimate(UUID owner, UUID deckId, Supplier<JsonNode> request) {
        if (!repository.deckOwned(owner, deckId)) throw new ResourceNotFoundException();
        JsonNode body = request.get();
        if (body.size() != 1 || body.has("spec") == body.has("edit")) throw new InvalidRequestException();

        Instant now = clock.now();
        UsageState.Resolved resolved = state.resolve(owner, now);
        UsageState.Credits credits = state.credits(resolved);
        GenerationSpecInterpreter.Interpretation interpretation = body.has("spec")
                ? interpreter.interpret(owner, deckId, body.get("spec"), credits.remaining())
                : new GenerationSpecInterpreter.Interpretation(editLines(body.get("edit")), null, List.of());
        return price(resolved, credits, interpretation, now);
    }

    EstimateView price(UsageState.Resolved resolved, UsageState.Credits credits,
                       GenerationSpecInterpreter.Interpretation interpretation, Instant now) {
        List<EstimateView.LineView> breakdown = new ArrayList<>();
        BigDecimal exact = BigDecimal.ZERO;
        int p95 = 0;
        List<UsageLimitReachedException.Block> blocks = new ArrayList<>();
        for (GenerationSpecInterpreter.Line line : interpretation.lines()) {
            if (line.count() < 1) continue;
            RateCard.Operation operation = rateCard.operation(line.operation());
            if (operation.availability() != RateCard.Availability.AVAILABLE) throw unavailable(operation);
            int lineCredits = rateCard.credits(line.operation(), line.count());
            breakdown.add(new EstimateView.LineView(line.operation(), line.count(), lineCredits));
            p95 += lineCredits;
            exact = exact.add(rateCard.exactCredits(line.operation(), line.count()));
            if (operation.cap() != null && operation.cap().isCap()) {
                capBlock(resolved, operation.cap(), line.count(), now).ifPresent(blocks::add);
            }
        }
        // p50 = typical weights (x 0.6) rounded up once on the sum; the spec's exercises stay fractional until here.
        int p50 = exact.multiply(RateCard.TYPICAL_FACTOR).setScale(0, RoundingMode.CEILING).intValueExact();
        int remaining = credits.remaining();

        EstimateView.BudgetView budget = null;
        int held = p95;
        if (interpretation.budgetPercent() != null) {
            int cap = (int) ((long) interpretation.budgetPercent() * remaining / 100);
            budget = new EstimateView.BudgetView(interpretation.budgetPercent(), cap);
            held = Math.min(p95, cap);
        }
        Integer shortfall = null;
        if (held > remaining) {
            blocks.addFirst(state.creditsBlock(credits, held));
            shortfall = held - remaining;
        }
        return new EstimateView(rateCard.version(), new EstimateView.CreditsView(p50, p95), breakdown,
                new EstimateView.BalanceView(remaining, Wire.time(credits.renewsAt())),
                new EstimateView.PercentView(Wire.percent(p50, credits.total()), Wire.percent(p95, credits.total())),
                budget, blocks.isEmpty(), blocks.stream().map(EstimateView.BlockView::of).toList(), shortfall,
                interpretation.warnings().stream().map(EstimateView::warning).toList());
    }

    /** A count cap the operation consumes: blocks when the plan has none or the window has no room left. */
    private java.util.Optional<UsageLimitReachedException.Block> capBlock(UsageState.Resolved resolved, Bucket bucket,
                                                                          int count, Instant now) {
        Allowance.WindowLimit limit = resolved.allowance().limits(bucket).getFirst();
        long used = state.counter(resolved.owner(), bucket, limit.window(), now);
        if (limit.limit() != null && used + count > limit.limit()) {
            return java.util.Optional.of(state.windowBlock(resolved.allowance().plan(), bucket, limit, used, count, now));
        }
        return java.util.Optional.empty();
    }

    /** An operation whose provider is not in this release: the capability is off, not a limit of the account. */
    private static CapabilityUnavailableException unavailable(RateCard.Operation operation) {
        String capability = operation.id().startsWith("VIDEO") ? "videoGeneration" : "imageGeneration";
        return new CapabilityUnavailableException(ProblemExtension.builder().put("capability", capability)
                .put("reason", "PROVIDER_NOT_CONFIGURED").build());
    }

    /** One edit turn: {@code {sessionId, artifactId, action, targetNodeCount?}}; a free action prices nothing. */
    private List<GenerationSpecInterpreter.Line> editLines(JsonNode edit) {
        if (!edit.isObject()) throw new InvalidRequestException();
        for (String name : edit.propertyNames()) {
            if (!Set.of("sessionId", "artifactId", "action", "targetNodeCount").contains(name)) {
                throw new InvalidRequestException();
            }
        }
        // TODO(AI-04, #287): when generation sessions exist, answer an opaque 404 for a session or artifact that does
        // not belong to this deck and owner; until then the ids are validated and priced by action only.
        entityId(edit, "sessionId");
        entityId(edit, "artifactId");
        if (edit.has("targetNodeCount")) {
            JsonNode count = edit.get("targetNodeCount");
            if (!count.isIntegralNumber() || !count.canConvertToInt() || count.intValue() < 1
                    || count.intValue() > MAX_TARGET_NODES) {
                throw new InvalidRequestException();
            }
        }
        if (!edit.has("action") || !edit.get("action").isString() || !EDIT_ACTIONS.contains(edit.get("action").stringValue())) {
            throw new InvalidRequestException();
        }
        return rateCard.forAction(edit.get("action").stringValue())
                .map(operation -> List.of(new GenerationSpecInterpreter.Line(operation.id(), 1))).orElse(List.of());
    }

    private static void entityId(JsonNode node, String name) {
        try {
            if (!node.has(name) || !node.get(name).isString()) throw new InvalidRequestException();
            UUID id = UuidPolicy.requireEntityId(UUID.fromString(node.get(name).stringValue()), name);
            if (!id.toString().equals(node.get(name).stringValue())) throw new InvalidRequestException();
        } catch (IllegalArgumentException failure) {
            throw new InvalidRequestException();
        }
    }
}
