package app.mnema.learning.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Licensed image search over the configured {@link ImageSource}s. The sources are asked <em>concurrently</em> on virtual threads; a source
 * that fails (down, throttled, credentials rejected, breaker open) is not a failure of the search while another one answers. The answers are
 * interleaved in the configured source order (the first result of each source, then the second ...), de-duplicated by
 * {@code (source, sourceId)} and by download URL, minus the keys the caller already has.
 *
 * <p>Every real source call is guarded by a breaker per {@code (source, IMAGE_SEARCH)}, bounded by the {@code imageSearch} permits and the daily
 * budget of the capability, journaled in {@code ai_provider_call} (provider = the source id, model {@code search}, cost 0: the sources are free) and
 * metered; an answer that is in the 24-hour cache costs no call at all. Nothing here logs a query, a URL or a key. The method refuses to run inside a
 * database transaction.
 *
 * <p>All sources failing is {@code Failed} (the caller says {@code PROVIDER_UNAVAILABLE}); no source finding a licensed image is an empty list
 * ({@code NO_RESULT}).
 */
final class RoutedImageSearch implements ImageSearch, AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(RoutedImageSearch.class);
    private static final AiCapability CAPABILITY = AiCapability.IMAGE_SEARCH;
    private static final String MODEL = "search";

    private final List<ImageSource> sources;
    private final ImageSearchCache cache;
    private final SafeImageFetcher fetcher;
    private final EgressClients clients;
    private final BreakerRegistry breakers;
    private final AiBudget budget;
    private final CallJournal journal;
    private final AiTelemetry telemetry;
    private final AiProperties properties;
    private final ImageSearchSettings settings;
    private final Semaphore permits;
    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();

    RoutedImageSearch(List<ImageSource> sources, ImageSearchCache cache, SafeImageFetcher fetcher, EgressClients clients, BreakerRegistry breakers,
                      AiBudget budget, CallJournal journal, AiTelemetry telemetry, AiProperties properties, ImageSearchSettings settings) {
        this.sources = List.copyOf(sources);
        this.cache = cache;
        this.fetcher = fetcher;
        this.clients = clients;
        this.breakers = breakers;
        this.budget = budget;
        this.journal = journal;
        this.telemetry = telemetry;
        this.properties = properties;
        this.settings = settings;
        // the dispatcher already admits imageSearch-many steps at once and one search asks every source: the permits cover all of those calls
        this.permits = new Semaphore(properties.permits().imageSearch() * Math.max(1, this.sources.size()));
    }

    @Override
    public boolean configured() { return sources.stream().anyMatch(ImageSource::configured); }

    @Override
    public AiResult<List<Candidate>> search(Request request) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("A provider call must not run inside a database transaction");
        }
        List<ImageSource> active = sources.stream().filter(ImageSource::configured).toList();
        if (active.isEmpty()) return AiResult.failed(new AiFailure.NotConfigured("no_source"));
        if (budget.exhausted(CAPABILITY)) return AiResult.failed(new AiFailure.BudgetExhausted());
        if (request.query().isEmpty()) return AiResult.ok(List.of());

        List<Future<AiResult<List<Candidate>>>> pending = new ArrayList<>();
        for (ImageSource source : active) pending.add(threads.submit(() -> ask(source, request)));
        List<List<Candidate>> answers = new ArrayList<>();
        AiFailure failure = null;
        long deadline = System.nanoTime() + settings.searchTimeout().plusSeconds(2).toNanos();
        for (Future<AiResult<List<Candidate>>> future : pending) {
            try {
                AiResult<List<Candidate>> result = future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                if (result instanceof AiResult.Ok<List<Candidate>> ok) answers.add(ok.value());
                else if (failure == null) failure = ((AiResult.Failed<List<Candidate>>) result).failure();
            } catch (TimeoutException late) {
                future.cancel(true);
                if (failure == null) failure = new AiFailure.Timeout();
            } catch (ExecutionException broken) {
                if (failure == null) failure = new AiFailure.Transient("source_error");
            } catch (InterruptedException interrupted) {
                pending.forEach(each -> each.cancel(true));
                Thread.currentThread().interrupt();
                return AiResult.failed(new AiFailure.Transient("interrupted"));
            }
        }
        // a source that answered keeps the search alive; the failures only count when nothing answered
        if (answers.isEmpty()) return AiResult.failed(failure == null ? new AiFailure.Transient("no_answer") : failure);
        return AiResult.ok(interleave(answers, request));
    }

    private static List<Candidate> interleave(List<List<Candidate>> answers, Request request) {
        List<Candidate> out = new ArrayList<>();
        Set<String> keys = new HashSet<>(request.excludeKeys());
        Set<String> urls = new HashSet<>();
        for (int round = 0; out.size() < request.maxResults(); round++) {
            boolean any = false;
            for (List<Candidate> answer : answers) {
                if (round >= answer.size()) continue;
                any = true;
                Candidate candidate = answer.get(round);
                if (out.size() < request.maxResults() && !keys.contains(candidate.key()) && !urls.contains(candidate.downloadUrl())) {
                    keys.add(candidate.key());
                    urls.add(candidate.downloadUrl());
                    out.add(candidate);
                }
            }
            if (!any) break;
        }
        return out;
    }

    /** One source: the cache, else one journaled, breaker-guarded call. */
    private AiResult<List<Candidate>> ask(ImageSource source, Request request) {
        String name = source.source().name();
        var cached = cache.get(name, request.query(), request.lang(), 1);
        if (cached.isPresent()) return AiResult.ok(cached.get());
        CircuitBreaker breaker = breakers.of(source.provider(), CAPABILITY);
        long ticket = breaker.tryAcquire();
        if (ticket == CircuitBreaker.REFUSED) return AiResult.failed(new AiFailure.CircuitOpen());
        boolean settled = false;
        boolean permit = false;
        try {
            permit = permits.tryAcquire(properties.permits().queueWait().toMillis(), TimeUnit.MILLISECONDS);
            if (!permit) return AiResult.failed(new AiFailure.RateLimited(Duration.ofSeconds(1)));
            UUID callId;
            try {
                callId = journal.begin(new CallJournal.Intent(request.stepId(), request.attempt(), CAPABILITY, source.provider(), MODEL, hash(source, request)));
            } catch (RuntimeException exception) {
                LOG.warn("image_search_internal_failure stage=journal_begin error_type={} source={}", exception.getClass().getSimpleName(), source.provider());
                return AiResult.failed(new AiFailure.Transient("journal_unavailable"));
            }
            long started = System.nanoTime();
            AiResult<List<Candidate>> result;
            try {
                result = source.search(request.query(), request.lang(), request.maxResults(), settings.searchTimeout());
            } catch (RuntimeException exception) {
                LOG.warn("image_search_internal_failure stage=adapter error_type={} source={}", exception.getClass().getSimpleName(), source.provider());
                result = AiResult.failed(new AiFailure.Transient("adapter_error"));
            }
            Duration latency = Duration.ofNanos(System.nanoTime() - started);
            // as for text: the breaker opens on transport and credential failures, not on an answer that is merely unusable
            if (result instanceof AiResult.Failed<List<Candidate>> failed && (failed.failure() instanceof AiFailure.Transient
                    || failed.failure() instanceof AiFailure.Timeout || failed.failure() instanceof AiFailure.NotConfigured)) {
                breaker.onFailure(ticket);
            } else {
                breaker.onSuccess(ticket);
            }
            settled = true;
            String outcome = result instanceof AiResult.Failed<List<Candidate>> failed ? failed.failure().outcome() : "OK";
            journal.finish(callId, new CallJournal.Outcome(outcome, Usage.ZERO, 0, null, latency.toMillis()));
            try {
                telemetry.record(CAPABILITY, source.provider(), MODEL, request.stepId(), outcome, latency, Usage.ZERO, 0, source.egress());
            } catch (RuntimeException exception) {
                LOG.warn("image_search_internal_failure stage=telemetry error_type={} source={}", exception.getClass().getSimpleName(), source.provider());
            }
            if (result instanceof AiResult.Ok<List<Candidate>> ok) cache.put(name, request.query(), request.lang(), 1, ok.value());
            return result;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return AiResult.failed(new AiFailure.Transient("interrupted"));
        } finally {
            if (permit) permits.release();
            if (!settled) breaker.release(ticket);
        }
    }

    @Override
    public AiResult<Image> fetch(Candidate candidate) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("A provider call must not run inside a database transaction");
        }
        ImageSource source = sources.stream().filter(each -> each.source() == candidate.source()).findFirst().orElse(null);
        if (source == null) return AiResult.failed(new AiFailure.NotConfigured("no_source"));
        ChatHttp http = clients.http(source.egress());
        if (http == null) return AiResult.failed(new AiFailure.NotConfigured("no_transport"));
        long started = System.nanoTime();
        AiResult<Image> result = fetcher.fetch(http.client(), source.egress() == AiProperties.EgressMode.PROXY, source.imageHosts(), candidate.downloadUrl(),
                settings.fetchTimeout());
        try {
            telemetry.record(CAPABILITY, source.provider(), "fetch", null, result instanceof AiResult.Failed<Image> failed ? failed.failure().outcome() : "OK",
                    Duration.ofNanos(System.nanoTime() - started), Usage.ZERO, 0, source.egress());
        } catch (RuntimeException exception) {
            LOG.warn("image_search_internal_failure stage=telemetry error_type={}", exception.getClass().getSimpleName());
        }
        return result;
    }

    /** SHA-256 of the request shape: correlates repeats without keeping the query. */
    private static String hash(ImageSource source, Request request) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((source.provider() + "|" + request.lang() + "|" + request.query()).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @Override
    public void close() { threads.shutdownNow(); }
}
