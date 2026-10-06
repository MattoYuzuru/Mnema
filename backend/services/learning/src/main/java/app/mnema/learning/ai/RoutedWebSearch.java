package app.mnema.learning.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Web search over the {@code learning.ai.routes.search} list ({@code yandex}, then {@code perplexity} when the owner listed it). The queries of a
 * request are cut into provider requests (one query for Yandex, up to five for Perplexity) and answered in order, one provider request after another;
 * for each the first usable entry is asked, and a transport, rate-limit, credential or unusable-answer failure falls through to the next one. A route
 * entry is skipped, never an error, when its provider is switched off, has no key or transport (a proxied provider without an active proxy), or has an
 * open breaker. The paid unit is an answered request: the search reports how many it made and what they cost.
 *
 * <p>A request that no provider could answer ends the search, and so does the deadline of the request (a provider that is down is not asked once per remaining query); what was answered before
 * is still returned. Only when nothing was answered is the search {@code Failed}. Every real request is guarded by a breaker per
 * {@code (provider, SEARCH)}, bounded by the {@code search} permits and the daily budget of the capability, journaled in {@code ai_provider_call}
 * (the request hash covers the query, never the text) and metered. The method refuses to run inside a database transaction.
 */
final class RoutedWebSearch implements WebSearch {
    private static final Logger LOG = LoggerFactory.getLogger(RoutedWebSearch.class);
    private static final AiCapability CAPABILITY = AiCapability.SEARCH;
    private static final String MODEL = "search";
    private static final Set<String> KNOWN = Set.of(YandexWebSearch.PROVIDER, PerplexityWebSearch.PROVIDER);

    private final List<String> route;
    private final Map<String, WebSearchAdapter> adapters;
    private final BreakerRegistry breakers;
    private final AiBudget budget;
    private final CallJournal journal;
    private final AiTelemetry telemetry;
    private final AiProperties properties;
    private final ResearchSettings settings;
    private final Semaphore permits;

    RoutedWebSearch(AiProperties properties, ResearchSettings settings, Map<String, WebSearchAdapter> adapters, BreakerRegistry breakers,
                    AiBudget budget, CallJournal journal, AiTelemetry telemetry) {
        this.properties = properties;
        this.settings = settings;
        this.adapters = Map.copyOf(adapters);
        this.breakers = breakers;
        this.budget = budget;
        this.journal = journal;
        this.telemetry = telemetry;
        for (String entry : properties.routes().search()) {
            if (!KNOWN.contains(entry)) throw new IllegalArgumentException("Unknown web search provider in learning.ai.routes.search");
        }
        this.route = List.copyOf(properties.routes().search());
        this.permits = new Semaphore(properties.permits().search());
    }

    private List<WebSearchAdapter> usable() {
        List<WebSearchAdapter> out = new ArrayList<>();
        for (String entry : route) {
            WebSearchAdapter adapter = adapters.get(entry);
            if (adapter != null && adapter.configured() && !out.contains(adapter)) out.add(adapter);
        }
        return out;
    }

    @Override
    public boolean configured() { return !usable().isEmpty(); }

    @Override
    public AiResult<Answer> search(Request request) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("A provider call must not run inside a database transaction");
        }
        List<WebSearchAdapter> usable = usable();
        if (usable.isEmpty()) return AiResult.failed(new AiFailure.NotConfigured("no_route"));
        if (budget.exhausted(CAPABILITY)) return AiResult.failed(new AiFailure.BudgetExhausted());
        List<Result> results = new ArrayList<>();
        int requests = 0;
        long cost = 0;
        AiFailure last = null;
        int next = 0;
        while (next < request.queries().size()) {
            if (Thread.currentThread().isInterrupted()) {
                last = new AiFailure.Transient("interrupted");
                break;
            }
            // the deadline of the step is held between requests: what was answered is returned, nothing new is bought
            if (request.deadline() != null && !Instant.now().isBefore(request.deadline())) {
                last = new AiFailure.Timeout();
                break;
            }
            Chunk chunk = null;
            for (WebSearchAdapter adapter : usable) {
                // The confirmed query count is also the maximum number of billed requests. A paid but rejected body consumes that room,
                // so a fallback must not buy another request after it is gone; the draft keeps its separately reserved credits.
                if (requests >= request.queries().size()) break;
                if (budget.exhausted(CAPABILITY)) {
                    last = new AiFailure.BudgetExhausted();
                    break;
                }
                if (request.deadline() != null && !Instant.now().isBefore(request.deadline())) {
                    last = new AiFailure.Timeout();
                    break;
                }
                int end = Math.min(request.queries().size(), next + adapter.maxQueries());
                Request part = new Request(request.queries().subList(next, end), request.lang(), request.maxResults(), request.region(),
                        request.stepId(), request.attempt(), request.deadline());
                AiResult<List<Result>> answer = call(adapter, part);
                if (answer instanceof AiResult.Ok<List<Result>> ok) {
                    chunk = new Chunk(end, ok.value(), adapter.requestCostMicros());
                    break;
                }
                last = ((AiResult.Failed<List<Result>>) answer).failure();
                if (adapter.paid(last)) {
                    // a 200 whose body was rejected was still a billed request: it is counted and debited, and the next provider is tried
                    requests++;
                    cost += adapter.requestCostMicros();
                }
                if (last instanceof AiFailure.Refusal || last instanceof AiFailure.BudgetExhausted) break;
            }
            if (chunk == null) break;
            final int offset = next;
            chunk.results().forEach(result -> results.add(result.withQueryIndex(offset + result.queryIndex())));
            requests++;
            cost += chunk.cost();
            next = chunk.end();
        }
        if (requests == 0) return AiResult.failed(last == null ? new AiFailure.Transient("no_answer") : last);
        results.sort(Comparator.comparingInt(Result::queryIndex).thenComparingInt(Result::rank));
        return AiResult.ok(new Answer(results, requests, cost));
    }

    private record Chunk(int end, List<Result> results, long cost) { }

    private AiResult<List<Result>> call(WebSearchAdapter adapter, Request request) {
        CircuitBreaker breaker = breakers.of(adapter.provider(), CAPABILITY);
        long ticket = breaker.tryAcquire();
        if (ticket == CircuitBreaker.REFUSED) return AiResult.failed(new AiFailure.CircuitOpen());
        boolean settled = false;
        boolean permit = false;
        try {
            permit = permits.tryAcquire(properties.permits().queueWait().toMillis(), TimeUnit.MILLISECONDS);
            if (!permit) return AiResult.failed(new AiFailure.RateLimited(Duration.ofSeconds(1)));
            if (budget.exhausted(CAPABILITY)) return AiResult.failed(new AiFailure.BudgetExhausted());
            if (request.deadline() != null && !Instant.now().isBefore(request.deadline())) return AiResult.failed(new AiFailure.Timeout());
            UUID callId;
            try {
                callId = journal.begin(new CallJournal.Intent(request.stepId(), request.attempt(), CAPABILITY, adapter.provider(), MODEL, hash(adapter, request)));
            } catch (RuntimeException exception) {
                LOG.warn("web_search_internal_failure stage=journal_begin error_type={} provider={}", exception.getClass().getSimpleName(), adapter.provider());
                return AiResult.failed(new AiFailure.Transient("journal_unavailable"));
            }
            long started = System.nanoTime();
            AiResult<List<Result>> result;
            try {
                Duration callBudget = request.deadline() == null ? settings.callTimeout()
                        : settings.callTimeout().compareTo(Duration.between(Instant.now(), request.deadline())) <= 0 ? settings.callTimeout()
                        : Duration.between(Instant.now(), request.deadline());
                result = adapter.search(request, callBudget.isNegative() || callBudget.isZero() ? Duration.ofMillis(1) : callBudget);
            } catch (RuntimeException exception) {
                LOG.warn("web_search_internal_failure stage=adapter error_type={} provider={}", exception.getClass().getSimpleName(), adapter.provider());
                result = AiResult.failed(new AiFailure.Transient("adapter_error"));
            }
            Duration latency = Duration.ofNanos(System.nanoTime() - started);
            // as for text: the breaker opens on transport and credential failures, not on an answer that is merely unusable
            if (result instanceof AiResult.Failed<List<Result>> failed && (failed.failure() instanceof AiFailure.Transient
                    || failed.failure() instanceof AiFailure.Timeout || failed.failure() instanceof AiFailure.NotConfigured)) {
                breaker.onFailure(ticket);
            } else {
                breaker.onSuccess(ticket);
            }
            settled = true;
            long cost = result instanceof AiResult.Ok<List<Result>> || (result instanceof AiResult.Failed<List<Result>> paidFailure && adapter.paid(paidFailure.failure()))
                    ? adapter.requestCostMicros() : 0;
            String outcome = result instanceof AiResult.Failed<List<Result>> failed ? failed.failure().outcome() : "OK";
            try {
                journal.finish(callId, new CallJournal.Outcome(outcome, Usage.ZERO, cost, null, latency.toMillis()));
            } catch (RuntimeException exception) {
                // The provider already answered: retain its paid result for the ledger even when the journal outcome write is unavailable.
                LOG.warn("web_search_internal_failure stage=journal_finish error_type={} provider={}", exception.getClass().getSimpleName(), adapter.provider());
            }
            budget.record(CAPABILITY, cost);
            try {
                telemetry.record(CAPABILITY, adapter.provider(), MODEL, request.stepId(), outcome, latency, Usage.ZERO, cost, adapter.egress());
            } catch (RuntimeException exception) {
                LOG.warn("web_search_internal_failure stage=telemetry error_type={}", exception.getClass().getSimpleName());
            }
            return result;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return AiResult.failed(new AiFailure.Transient("interrupted"));
        } finally {
            if (permit) permits.release();
            if (!settled) breaker.release(ticket);
        }
    }

    /** SHA-256 of the request shape: correlates repeats without keeping the queries. */
    private static String hash(WebSearchAdapter adapter, Request request) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((adapter.provider() + "|" + request.lang() + "|" + String.join("\n", request.queries())).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
