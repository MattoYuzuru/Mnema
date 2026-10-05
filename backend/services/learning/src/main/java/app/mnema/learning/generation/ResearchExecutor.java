package app.mnema.learning.generation;

import app.mnema.learning.ai.AiCapability;
import app.mnema.learning.ai.AiResult;
import app.mnema.learning.ai.AiRoute;
import app.mnema.learning.ai.OpaqueUserKey;
import app.mnema.learning.ai.OutputContract;
import app.mnema.learning.ai.ResearchSettings;
import app.mnema.learning.ai.TextGeneration;
import app.mnema.learning.ai.TextRequest;
import app.mnema.learning.ai.TextResponse;
import app.mnema.learning.ai.WebSearch;
import app.mnema.learning.ai.prompt.PromptException;
import app.mnema.learning.generation.ContextBuilder.SourceGoneException;
import app.mnema.learning.generation.Rows.Artifact;
import app.mnema.learning.generation.mbm.NativeProfile;
import app.mnema.learning.generation.Rows.Session;
import app.mnema.learning.generation.SessionLifecycle.Failure;
import app.mnema.learning.usage.AdmissionPricing;
import app.mnema.learning.usage.Reservation;
import app.mnema.learning.usage.UsageLedger;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The {@code RESEARCH} step (AI-18, #299): the web research of a fact-checked material, before its {@code TEXT_DRAFT}.
 *
 * <p>A run is: the step begins ({@link ResearchLifecycle#begin}), the prompt of the query planner is built from the database, <em>one cheap call on
 * the fast text route</em> (strict JSON {@code {"queries": [...]}}, low temperature, one repair) proposes at most {@code cap} queries, the server clamps
 * and de-duplicates them and runs them through the {@link WebSearch} port, the results are de-duplicated by normalized URL and numbered
 * {@code [n]} in query order, then rank (the order the provider gave them), at most {@code max-results} are kept, and one transaction debits the requests
 * actually made, stores the results and starts the draft ({@link ResearchLifecycle#succeed}). No database transaction is open during a model call
 * or a search, and no page is ever fetched.
 *
 * <p><b>A fact check never blocks the material.</b> Every failure on the way (the planner is down or answers garbage, no query, no provider, the
 * budget, the hold that cannot pay for a request, a source that is gone) ends the step as SUCCEEDED with no results and a logged reason; the draft then
 * runs without sources. Requests already answered are debited even when the run ends early. The only runs that do not succeed are cancelled ones.
 */
@Component
class ResearchExecutor implements StepExecutor {
    static final String KIND = "RESEARCH";
    private static final Logger LOG = LoggerFactory.getLogger(ResearchExecutor.class);
    private static final int PLANNER_ROUNDS = 2;
    private static final int PLANNER_MAX_TOKENS = 400;
    private static final Duration PLANNER_CALL_CAP = Duration.ofSeconds(30);
    private static final Pattern CYRILLIC = Pattern.compile("\\p{IsCyrillic}");

    private final TextGeneration text;
    private final WebSearch search;
    private final GenerationRepository repository;
    private final ResearchLifecycle lifecycle;
    private final SessionLifecycle sessions;
    private final ContextBuilder contexts;
    private final UsageLedger ledger;
    private final AdmissionPricing pricing;
    private final ProviderKeys keys;
    private final ResearchSettings settings;
    private final GenerationSettings generation;
    private final MeterRegistry meters;

    ResearchExecutor(TextGeneration text, WebSearch search, GenerationRepository repository, ResearchLifecycle lifecycle, SessionLifecycle sessions,
                     ContextBuilder contexts, UsageLedger ledger, AdmissionPricing pricing, ProviderKeys keys, ResearchSettings settings,
                     GenerationSettings generation, MeterRegistry meters) {
        this.text = text;
        this.search = search;
        this.repository = repository;
        this.lifecycle = lifecycle;
        this.sessions = sessions;
        this.contexts = contexts;
        this.ledger = ledger;
        this.pricing = pricing;
        this.keys = keys;
        this.settings = settings;
        this.generation = generation;
        this.meters = meters;
    }

    @Override public String kind() { return KIND; }

    @Override public AiCapability capability() { return AiCapability.SEARCH; }

    @Override
    public void execute(StepClaim claim, StepControl control) {
        if (!lifecycle.begin(claim)) return;
        Session session = repository.session(claim.sessionId()).orElse(null);
        Artifact artifact = repository.artifact(claim.sessionId(), claim.artifactId()).orElse(null);
        if (session == null || artifact == null) return;
        MaterialsSpec spec = MaterialsSpec.read(session.spec());

        int cap = Math.min(claim.input().path("cap").asInt(0), settings.maxRequests());
        int perRequest = Math.max(1, pricing.credits(ResearchSteps.OPERATION));
        UUID reservationId = SessionReservations.forStep(session, claim.input());
        Optional<Reservation> reservation = reservationId == null ? Optional.empty() : ledger.reservation(claim.ownerId(), reservationId);
        // the research never takes what its own draft needs: only the credits held beyond the draft's can be spent on requests
        int spare = reservation.map(held -> held.heldRemaining() - claim.input().path("draftCredits").asInt(0)).orElse(0);
        cap = Math.min(cap, Math.max(0, spare) / perRequest);
        if (cap < 1) {
            done(claim, control, new Run("budget"), 0);
            return;
        }

        ContextBuilder.ResearchPrompt prompt;
        OpaqueUserKey key;
        try {
            prompt = contexts.buildResearch(session, artifact, spec, cap);
            key = keys.opaque(claim.ownerId());
        } catch (SourceGoneException gone) {
            done(claim, control, new Run("source_unavailable"), 0);
            return;
        } catch (PromptException tooBig) {
            done(claim, control, new Run("prompt_rejected"), 0);
            return;
        } catch (IllegalStateException notConfigured) {
            done(claim, control, new Run("provider_not_configured"), 0);
            return;
        }

        Planned planned = plan(claim, control, prompt, key, cap);
        if (planned == null) return;
        if (planned.queries().isEmpty()) {
            done(claim, control, new Run(planned.reason()), planned.costMicros());
            return;
        }

        WebSearch.Request request = new WebSearch.Request(planned.queries(), language(spec, planned.queries()), settings.resultsPerQuery(), null,
                claim.stepId(), claim.attempt(), claim.deadlineAt());
        AiResult<WebSearch.Answer> result;
        control.callStarted();
        try {
            result = search.search(request);
        } finally {
            control.callEnded();
        }
        if (control.lost() || control.cancelled()) {
            // what was already bought is paid for even though nothing is stored (idempotent per step and attempt)
            if (result instanceof AiResult.Ok<WebSearch.Answer> paid && paid.value().requests() > 0) {
                lifecycle.debitPaid(claim, paid.value().requests(), costRub(planned.costMicros() + paid.value().costMicros()));
            }
            if (!control.lost()) cancelled(claim);
            return;
        }
        if (result instanceof AiResult.Failed<WebSearch.Answer> failed) {
            done(claim, control, new Run("search_" + failed.failure().outcome().toLowerCase(Locale.ROOT)), planned.costMicros());
            return;
        }
        WebSearch.Answer answer = ((AiResult.Ok<WebSearch.Answer>) result).value();
        List<ResearchRepository.Source> numbered = number(answer.results());
        done(claim, control, new Run(null, answer.requests(), numbered, answer.costMicros()), planned.costMicros());
    }

    // ----------------------------------------------------------------- the planner

    /** The queries the planner proposed, clamped and de-duplicated, or why there are none; null when the run ended (cancelled, lease lost). */
    private record Planned(List<String> queries, String reason, long costMicros) { }

    private Planned plan(StepClaim claim, StepControl control, ContextBuilder.ResearchPrompt prompt, OpaqueUserKey key, int cap) {
        String violations = null;
        long cost = 0;
        for (int round = 0; round < PLANNER_ROUNDS; round++) {
            Duration remaining = Duration.between(Instant.now(), claim.deadlineAt());
            if (remaining.compareTo(Duration.ofMillis(500)) < 0) return new Planned(List.of(), "deadline", cost);
            TextRequest call = new TextRequest(AiRoute.TEXT_FAST, prompt.prompt().segments(), OutputContract.JSON, PLANNER_MAX_TOKENS, prompt.temperature(),
                    min(remaining, PLANNER_CALL_CAP), key, null, claim.stepId(), claim.attempt());
            if (violations != null) call = call.withRepair(violations);
            AiResult<TextResponse> result;
            control.callStarted();
            try {
                result = text.generate(call);
            } finally {
                control.callEnded();
            }
            if (control.lost()) return null;
            if (control.cancelled()) {
                cancelled(claim);
                return null;
            }
            if (result instanceof AiResult.Failed<TextResponse> failed) {
                return new Planned(List.of(), "planner_" + failed.failure().outcome().toLowerCase(Locale.ROOT), cost);
            }
            TextResponse response = ((AiResult.Ok<TextResponse>) result).value();
            cost += response.costMicros();
            Optional<List<String>> queries = queries(ExerciseDraftExecutor.parse(response.text()), cap);
            if (queries.isPresent() && !queries.get().isEmpty()) return new Planned(queries.get(), null, cost);
            violations = "ответ вне формата: нужен json {\"queries\": [...]} с непустым списком строк, не больше " + cap;
        }
        return new Planned(List.of(), "planner_invalid_output", cost);
    }

    /**
     * The queries of an answer: strings only, blanks and repeats dropped, clamped to {@code cap} (the model is told the cap and the server holds it);
     * empty when the answer has none, absent when it is not the format.
     */
    static Optional<List<String>> queries(JsonNode answer, int cap) {
        if (answer == null || !answer.path("queries").isArray()) return Optional.empty();
        List<String> queries = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode node : answer.path("queries")) {
            if (!node.isString()) return Optional.empty();
            // redacted and bounded exactly as the search port sends it; one that is empty then (a first word longer than the bound, only an address) is dropped
            String query = WebSearch.Request.clean(node.stringValue());
            if (query.isEmpty() || !seen.add(query.toLowerCase(Locale.ROOT))) continue;
            if (queries.size() < cap) queries.add(query);
        }
        return Optional.of(queries);
    }

    /**
     * The language of the queries: the language of the material, except that a Russian material whose planner wrote its queries in English (it was
     * told to for international topics) is searched in English, so the provider uses the right index.
     */
    static String language(MaterialsSpec spec, List<String> queries) {
        String language = spec.outputLanguage() == null ? "" : spec.outputLanguage().toLowerCase(Locale.ROOT);
        String primary = language.length() >= 2 && language.substring(0, 2).matches("[a-z]{2}") ? language.substring(0, 2) : "en";
        if (primary.equals("ru") && queries.stream().noneMatch(query -> CYRILLIC.matcher(query).find())) return "en";
        return primary;
    }

    // --------------------------------------------------------------- the results

    /**
     * The results de-duplicated by normalized URL (the first occurrence stays) and numbered in query order, then rank: the order of the answer, which the
     * provider ordered by relevance. At most {@code max-results} are kept.
     */
    List<ResearchRepository.Source> number(List<WebSearch.Result> results) {
        List<WebSearch.Result> ordered = new ArrayList<>(results);
        ordered.sort(Comparator.comparingInt(WebSearch.Result::queryIndex).thenComparingInt(WebSearch.Result::rank));
        Set<String> keys = new HashSet<>();
        List<ResearchRepository.Source> numbered = new ArrayList<>();
        for (WebSearch.Result result : ordered) {
            if (numbered.size() >= settings.maxResults()) break;
            // the link must also be one the compiler's native-v1 href profile accepts, or the material could not cite it
            String url = WebSearch.acceptable(result.url());
            if (url == null || !NativeProfile.acceptsHref(url) || !keys.add(WebSearch.key(url))) continue;
            numbered.add(new ResearchRepository.Source(numbered.size() + 1, url, result.title(), result.snippet(), result.date(), result.provider().name(),
                    result.queryIndex()));
        }
        return numbered;
    }

    // -------------------------------------------------------------------- the end

    /** How a run ended: {@code reason} is why it found nothing (null when it searched). */
    private record Run(String reason, int requests, List<ResearchRepository.Source> results, long searchCostMicros) {
        Run(String reason) {
            this(reason, 0, List.of(), 0);
        }
    }

    /** Ends the step with what the run found: the debit, the rows and the draft that waits for it are one transaction. */
    private void done(StepClaim claim, StepControl control, Run run, long plannerCostMicros) {
        if (control.lost()) return;
        boolean stored = lifecycle.succeed(claim, new ResearchLifecycle.Outcome(run.requests(), run.results(), costRub(plannerCostMicros + run.searchCostMicros())));
        String outcome = !stored ? "void" : run.reason() == null ? (run.results().isEmpty() ? "empty" : "succeeded") : "skipped";
        meters.counter("mnema_generation_steps_total", "kind", KIND, "outcome", outcome).increment();
        LOG.info("generation_step_done step_id={} session_id={} kind={} attempt={} outcome={} reason={} requests={} results={} stored={}", claim.stepId(),
                claim.sessionId(), KIND, claim.attempt(), outcome, run.reason() == null ? "-" : run.reason(), run.requests(), run.results().size(), stored);
    }

    /** Micro-US-dollars to millionths of a rouble, rounded up. */
    private long costRub(long usdMicros) {
        return BigDecimal.valueOf(usdMicros).multiply(generation.usdRubRate()).setScale(0, RoundingMode.CEILING).longValueExact();
    }

    private void cancelled(StepClaim claim) {
        boolean stored = sessions.fail(claim, Failure.cancelled());
        meters.counter("mnema_generation_steps_total", "kind", KIND, "outcome", "cancelled").increment();
        LOG.info("generation_step_done step_id={} session_id={} kind={} attempt={} outcome=cancelled stored={}", claim.stepId(), claim.sessionId(), KIND,
                claim.attempt(), stored);
    }

    private static Duration min(Duration left, Duration right) { return left.compareTo(right) <= 0 ? left : right; }
}
