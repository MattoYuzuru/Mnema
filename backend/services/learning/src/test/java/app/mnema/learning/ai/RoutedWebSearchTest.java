package app.mnema.learning.ai;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The web search over its route: chunks per provider, fallback, the paid unit, partial answers, the breaker, the budget and the journal. */
class RoutedWebSearchTest {
    private static final AiProperties.Routes ROUTE = new AiProperties.Routes(List.of(), List.of(), List.of(), Duration.ofSeconds(8), List.of(), List.of(),
            List.of(), List.of(), List.of("yandex", "perplexity"));
    private final AiProperties properties = withRoute(ROUTE);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final SpeechTestSupport.RecordingJournal journal = new SpeechTestSupport.RecordingJournal();
    private long spent;

    private static AiProperties withRoute(AiProperties.Routes routes) {
        AiProperties base = AiTestSupport.properties("", AiTestSupport.routes(List.of(), List.of(), List.of()), Map.of());
        return new AiProperties(base.provider(), routes, base.providers(), base.models(), base.transport(), base.retry(), base.breaker(), base.permits(),
                base.budget(), base.userKey(), base.prompt());
    }

    /** An adapter that answers from a script and counts what it was asked. */
    static final class Fake implements WebSearchAdapter {
        private final String provider;
        private final int max;
        final List<List<String>> asked = new CopyOnWriteArrayList<>();
        final AtomicInteger calls = new AtomicInteger();
        volatile boolean configured = true;
        /** The answer of the n-th call (1-based); a missing entry answers one result per query. */
        volatile java.util.function.IntFunction<AiResult<List<WebSearch.Result>>> script;

        Fake(String provider, int max) {
            this.provider = provider;
            this.max = max;
        }

        @Override public String provider() { return provider; }

        @Override public boolean configured() { return configured; }

        @Override public AiProperties.EgressMode egress() { return AiProperties.EgressMode.DIRECT; }

        @Override public int maxQueries() { return max; }

        @Override public long requestCostMicros() { return provider.equals("yandex") ? 5_742 : 5_000; }

        @Override
        public AiResult<List<WebSearch.Result>> search(WebSearch.Request request, Duration budget) {
            int call = calls.incrementAndGet();
            asked.add(request.queries());
            if (script != null) return script.apply(call);
            List<WebSearch.Result> results = new ArrayList<>();
            for (int query = 0; query < request.queries().size(); query++) {
                for (int rank = 1; rank <= 2; rank++) {
                    results.add(new WebSearch.Result("https://example.org/" + provider + "/" + call + "/" + query + "/" + rank, "t", "s", null,
                            provider.equals("yandex") ? WebSearch.Provider.YANDEX : WebSearch.Provider.PERPLEXITY, query, rank));
                }
            }
            return AiResult.ok(results);
        }
    }

    private RoutedWebSearch router(Fake... adapters) { return router(properties, adapters); }

    private RoutedWebSearch router(AiProperties configured, Fake... adapters) {
        Map<String, WebSearchAdapter> byId = new java.util.LinkedHashMap<>();
        for (Fake adapter : adapters) byId.put(adapter.provider(), adapter);
        return new RoutedWebSearch(configured, ResearchSettings.defaults(), byId, new BreakerRegistry(Clock.systemUTC(), configured.breaker()),
                new AiBudget(configured.budget(), (capability, since) -> spent, Clock.systemUTC()), journal, new AiTelemetry(meters));
    }

    private static WebSearch.Request request(String... queries) {
        return new WebSearch.Request(List.of(queries), "ru", 5, null, java.util.UUID.randomUUID(), 1);
    }

    private static WebSearch.Answer answer(AiResult<WebSearch.Answer> result) {
        assertThat(result).isInstanceOf(AiResult.Ok.class);
        return ((AiResult.Ok<WebSearch.Answer>) result).value();
    }

    private static AiFailure failure(AiResult<WebSearch.Answer> result) {
        assertThat(result).isInstanceOf(AiResult.Failed.class);
        return ((AiResult.Failed<WebSearch.Answer>) result).failure();
    }

    @AfterEach
    void clearTransactionState() { TransactionSynchronizationManager.setActualTransactionActive(false); }

    @Test
    void yandexAnswersOneRequestPerQueryAndTheResultsComeBackInQueryOrderWithTheirQueryIndex() {
        Fake yandex = new Fake("yandex", 1);
        Fake perplexity = new Fake("perplexity", 5);

        WebSearch.Answer answer = answer(router(yandex, perplexity).search(request("a", "b", "c")));

        assertThat(yandex.asked).containsExactly(List.of("a"), List.of("b"), List.of("c"));
        assertThat(perplexity.calls.get()).isZero();
        assertThat(answer.requests()).isEqualTo(3);
        assertThat(answer.costMicros()).isEqualTo(3 * 5_742);
        assertThat(answer.results()).extracting(WebSearch.Result::queryIndex).containsExactly(0, 0, 1, 1, 2, 2);
        assertThat(answer.results()).extracting(WebSearch.Result::rank).containsExactly(1, 2, 1, 2, 1, 2);
        assertThat(journal.intents).hasSize(3).allSatisfy(intent -> {
            assertThat(intent.capability()).isEqualTo(AiCapability.SEARCH);
            assertThat(intent.provider()).isEqualTo("yandex");
            assertThat(intent.model()).isEqualTo("search");
            // the hash covers the queries, never their text
            assertThat(intent.requestHash()).matches("[0-9a-f]{64}");
        });
        assertThat(journal.outcomes).extracting(CallJournal.Outcome::outcome).containsOnly("OK");
        assertThat(journal.outcomes).extracting(CallJournal.Outcome::costMicros).containsOnly(5_742L);
    }

    @Test
    void perplexityBatchesUpToFiveQueriesInOneRequestAndTheQueryIndexIsShiftedToTheRequest() {
        Fake perplexity = new Fake("perplexity", 5);
        AiProperties onlyPerplexity = withRoute(new AiProperties.Routes(List.of(), List.of(), List.of(), Duration.ofSeconds(8), List.of(), List.of(),
                List.of(), List.of(), List.of("perplexity")));

        WebSearch.Answer answer = answer(router(onlyPerplexity, perplexity).search(request("1", "2", "3", "4", "5", "6", "7")));

        assertThat(perplexity.asked).containsExactly(List.of("1", "2", "3", "4", "5"), List.of("6", "7"));
        assertThat(answer.requests()).isEqualTo(2);
        assertThat(answer.costMicros()).isEqualTo(10_000);
        // the second request's queries 0 and 1 are the request's queries 5 and 6
        assertThat(answer.results()).extracting(WebSearch.Result::queryIndex).containsExactly(0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6);
    }

    @Test
    void aRequestYandexCannotAnswerFallsToPerplexityAndOnlyAnsweredRequestsArePaid() {
        Fake yandex = new Fake("yandex", 1);
        Fake perplexity = new Fake("perplexity", 5);
        yandex.script = call -> call == 2 ? AiResult.failed(new AiFailure.Transient("http_503")) : AiResult.ok(List.of());

        WebSearch.Answer answer = answer(router(yandex, perplexity).search(request("a", "b", "c")));

        // query a: yandex; query b: yandex fails, then perplexity answers b and c together; the failed request is not counted
        assertThat(yandex.asked).containsExactly(List.of("a"), List.of("b"));
        assertThat(perplexity.asked).containsExactly(List.of("b", "c"));
        assertThat(answer.requests()).isEqualTo(2);
        assertThat(answer.costMicros()).isEqualTo(5_742 + 5_000);
        assertThat(answer.results()).extracting(WebSearch.Result::queryIndex).containsExactly(1, 1, 2, 2);
        assertThat(journal.outcomes).extracting(CallJournal.Outcome::outcome).containsExactly("OK", "TRANSIENT", "OK");
    }

    @Test
    void whenNoProviderCanAnswerARequestTheSearchStopsAndKeepsWhatWasAnsweredBefore() {
        Fake yandex = new Fake("yandex", 1);
        yandex.script = call -> call == 1 ? AiResult.ok(List.of(new WebSearch.Result("https://example.org/1", "t", "s", null, WebSearch.Provider.YANDEX, 0, 1)))
                : AiResult.failed(new AiFailure.Timeout());
        AiProperties onlyYandex = withRoute(new AiProperties.Routes(List.of(), List.of(), List.of(), Duration.ofSeconds(8), List.of(), List.of(),
                List.of(), List.of(), List.of("yandex")));

        WebSearch.Answer answer = answer(router(onlyYandex, yandex).search(request("a", "b", "c")));

        // the third query is not asked of a provider that just timed out
        assertThat(yandex.calls.get()).isEqualTo(2);
        assertThat(answer.requests()).isEqualTo(1);
        assertThat(answer.results()).hasSize(1);
    }

    @Test
    void nothingAnsweredIsAFailureWithTheLastReasonAndAnEmptyAnswerIsASuccess() {
        Fake yandex = new Fake("yandex", 1);
        Fake perplexity = new Fake("perplexity", 5);
        yandex.script = call -> AiResult.failed(new AiFailure.RateLimited(Duration.ofSeconds(1)));
        perplexity.script = call -> AiResult.failed(new AiFailure.Transient("http_502"));
        RoutedWebSearch router = router(yandex, perplexity);
        assertThat(failure(router.search(request("a")))).isEqualTo(new AiFailure.Transient("http_502"));

        yandex.script = call -> AiResult.ok(List.of());
        WebSearch.Answer empty = answer(router.search(request("a", "b")));
        assertThat(empty.results()).isEmpty();
        // an empty answer is still a paid request
        assertThat(empty.requests()).isEqualTo(2);
    }

    @Test
    void aRefusalEndsTheSearchWithoutAskingTheNextProvider() {
        Fake yandex = new Fake("yandex", 1);
        Fake perplexity = new Fake("perplexity", 5);
        yandex.script = call -> AiResult.failed(new AiFailure.Refusal("http_404"));

        assertThat(failure(router(yandex, perplexity).search(request("a")))).isEqualTo(new AiFailure.Refusal("http_404"));
        assertThat(perplexity.calls.get()).isZero();
    }

    @Test
    void anAdapterThatThrowsIsAFailureOfThatCallNotOfTheCaller() {
        Fake yandex = new Fake("yandex", 1);
        yandex.script = call -> {
            throw new IllegalStateException("bug");
        };
        assertThat(failure(router(yandex).search(request("a")))).isEqualTo(new AiFailure.Transient("adapter_error"));
        assertThat(journal.outcomes).extracting(CallJournal.Outcome::outcome).containsExactly("TRANSIENT");
    }

    @Test
    void anOpenBreakerIsSkippedAndAProviderWithoutAKeyIsNotAsked() {
        Fake yandex = new Fake("yandex", 1);
        Fake perplexity = new Fake("perplexity", 5);
        yandex.script = call -> AiResult.failed(new AiFailure.NotConfigured("http_401"));
        RoutedWebSearch router = router(yandex, perplexity);
        // five credential failures open the breaker of yandex; the sixth search does not even try it
        for (int i = 0; i < 5; i++) assertThat(router.search(request("a"))).isInstanceOf(AiResult.Ok.class);
        int before = yandex.calls.get();
        assertThat(answer(router.search(request("a"))).requests()).isEqualTo(1);
        assertThat(yandex.calls.get()).isEqualTo(before);

        yandex.configured = false;
        perplexity.configured = false;
        assertThat(router.configured()).isFalse();
        assertThat(failure(router.search(request("a")))).isEqualTo(new AiFailure.NotConfigured("no_route"));
    }

    @Test
    void aSpentDailyBudgetIsRefusedBeforeAnyCall() {
        Fake yandex = new Fake("yandex", 1);
        spent = 6_000_000;
        assertThat(failure(router(yandex).search(request("a")))).isInstanceOf(AiFailure.BudgetExhausted.class);
        assertThat(yandex.calls.get()).isZero();
    }

    @Test
    void aJournalThatCannotRecordTheIntentMeansNoCall() {
        Fake yandex = new Fake("yandex", 1);
        CallJournal broken = new CallJournal() {
            @Override public java.util.UUID begin(Intent intent) { throw new IllegalStateException("database down"); }

            @Override public void finish(java.util.UUID callId, Outcome outcome) { }
        };
        var router = new RoutedWebSearch(properties, ResearchSettings.defaults(), Map.of("yandex", yandex), new BreakerRegistry(Clock.systemUTC(), properties.breaker()),
                new AiBudget(properties.budget(), (capability, since) -> 0, Clock.systemUTC()), broken, new AiTelemetry(meters));
        assertThat(failure(router.search(request("a")))).isEqualTo(new AiFailure.Transient("journal_unavailable"));
        assertThat(yandex.calls.get()).isZero();
    }

    @Test
    void itRefusesToRunInsideATransactionAndAnUnknownRouteEntryFailsAtStartup() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThatThrownBy(() -> router(new Fake("yandex", 1)).search(request("a"))).isInstanceOf(IllegalStateException.class);
        TransactionSynchronizationManager.setActualTransactionActive(false);
        AiProperties unknown = withRoute(new AiProperties.Routes(List.of(), List.of(), List.of(), Duration.ofSeconds(8), List.of(), List.of(), List.of(),
                List.of(), List.of("brave")));
        assertThatThrownBy(() -> router(unknown)).isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------- wiring

    private static AiProperties.Provider provider(String key, AiProperties.EgressMode egress) {
        return new AiProperties.Provider(true, "https://example.org", key, "", "", "", egress);
    }

    private boolean configured(AiProperties configured, ResearchSettings settings) {
        try (EgressClients clients = EgressClients.create(configured)) {
            Map<String, WebSearchAdapter> adapters = AiConfiguration.webSearchAdapters(configured, settings, clients, BigDecimal.valueOf(85), Clock.systemUTC());
            return new RoutedWebSearch(configured, settings, adapters, new BreakerRegistry(Clock.systemUTC(), configured.breaker()),
                    new AiBudget(configured.budget(), (capability, since) -> 0, Clock.systemUTC()), journal, new AiTelemetry(meters)).configured();
        }
    }

    private AiProperties wired(List<String> route, Map<String, AiProperties.Provider> providers, AiProperties.Egress egress) {
        AiProperties base = AiTestSupport.properties("", AiTestSupport.routes(List.of(), List.of(), List.of()), providers);
        return new AiProperties(base.provider(), new AiProperties.Routes(List.of(), List.of(), List.of(), Duration.ofSeconds(8), List.of(), List.of(), List.of(),
                List.of(), route), providers, base.models(), base.transport(), base.retry(), base.breaker(), base.permits(), base.budget(), base.userKey(),
                base.prompt(), egress);
    }

    @Test
    void yandexNeedsAKeyAndAFolderButNoProxyAndPerplexityNeedsAKeyAndAProxy() {
        ResearchSettings withFolder = new ResearchSettings(15, 30, 5, Duration.ofSeconds(10), Duration.ofSeconds(90), "b1gFOLDER", "225", new BigDecimal("0.488"),
                new BigDecimal("0.005"));
        Map<String, AiProperties.Provider> yandex = Map.of("yandex-search", provider("k", AiProperties.EgressMode.DIRECT));
        assertThat(configured(wired(List.of("yandex"), yandex, null), ResearchSettings.defaults())).isFalse();
        assertThat(configured(wired(List.of("yandex"), yandex, null), withFolder)).isTrue();
        // a provider that exists but is not listed in the route is never called
        assertThat(configured(wired(List.of(), yandex, null), withFolder)).isFalse();
        assertThat(configured(wired(List.of("yandex"), Map.of("yandex-search", provider("", AiProperties.EgressMode.DIRECT)), null), withFolder)).isFalse();

        Map<String, AiProperties.Provider> perplexity = Map.of("perplexity", provider("k", AiProperties.EgressMode.PROXY));
        assertThat(configured(wired(List.of("perplexity"), perplexity, null), withFolder)).isFalse();
        assertThat(configured(wired(List.of("perplexity"), perplexity, new AiProperties.Egress("http://proxy.invalid:3128", "", "", true)), withFolder)).isTrue();
        assertThat(configured(wired(List.of("perplexity"), perplexity, new AiProperties.Egress("http://proxy.invalid:3128", "", "", false)), withFolder)).isFalse();
        // yandex listed first and unconfigured: perplexity still serves
        Map<String, AiProperties.Provider> both = Map.of("yandex-search", provider("", AiProperties.EgressMode.DIRECT), "perplexity",
                provider("k", AiProperties.EgressMode.PROXY));
        assertThat(configured(wired(List.of("yandex", "perplexity"), both, new AiProperties.Egress("http://proxy.invalid:3128", "", "", true)), withFolder)).isTrue();
    }

    @Test
    void aProviderWithoutAnEntryHasNoAdapter() {
        AiProperties none = wired(List.of("yandex"), Map.of(), null);
        try (EgressClients clients = EgressClients.create(none)) {
            assertThat(AiConfiguration.webSearchAdapters(none, ResearchSettings.defaults(), clients, BigDecimal.valueOf(85), Clock.systemUTC())).isEmpty();
        }
    }
}
