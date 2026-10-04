package app.mnema.learning.ai;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Several sources asked together: interleaving, de-duplication, a failing source, the cache, the breaker, the journal and the download. */
class RoutedImageSearchTest {
    private static final AiProperties PROPERTIES = AiTestSupport.properties("", AiTestSupport.routes(List.of(), List.of(), List.of()), Map.of());
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final Journal journal = new Journal();
    private final MapCache cache = new MapCache();
    private long spent;

    /** A source answering from a script; counts its calls. */
    static final class Fake implements ImageSource {
        private final ImageSearch.Source source;
        private final String provider;
        final AtomicInteger calls = new AtomicInteger();
        volatile AiResult<List<ImageSearch.Candidate>> answer = AiResult.ok(List.of());
        volatile boolean configured = true;
        volatile Set<String> hosts = Set.of("localhost");

        Fake(ImageSearch.Source source) {
            this.source = source;
            this.provider = source.name().toLowerCase();
        }

        @Override public ImageSearch.Source source() { return source; }

        @Override public String provider() { return provider; }

        @Override public boolean configured() { return configured; }

        @Override public AiProperties.EgressMode egress() { return AiProperties.EgressMode.DIRECT; }

        @Override public Set<String> imageHosts() { return hosts; }

        @Override
        public AiResult<List<ImageSearch.Candidate>> search(String query, String lang, int maxResults, Duration budget) {
            calls.incrementAndGet();
            return answer;
        }
    }

    private static ImageSearch.Candidate candidate(ImageSearch.Source source, String id, String url) {
        return new ImageSearch.Candidate(source, id, "t" + id, "a", "CC0 1.0", null, "https://example.org/" + id, false, 640, 480, url);
    }

    private static AiResult<List<ImageSearch.Candidate>> ok(ImageSearch.Candidate... candidates) { return AiResult.ok(List.of(candidates)); }

    private static ImageSearch.Request request(String query, int max, Set<String> exclude) {
        return new ImageSearch.Request(query, "en", max, exclude, UUID.randomUUID(), 1);
    }

    private static List<ImageSearch.Candidate> found(AiResult<List<ImageSearch.Candidate>> result) {
        assertThat(result).isInstanceOf(AiResult.Ok.class);
        return ((AiResult.Ok<List<ImageSearch.Candidate>>) result).value();
    }

    private RoutedImageSearch router(Fake... sources) { return router(Clock.systemUTC(), sources); }

    private RoutedImageSearch router(Clock clock, Fake... sources) {
        var breakers = new BreakerRegistry(clock, PROPERTIES.breaker());
        var budget = new AiBudget(PROPERTIES.budget(), (capability, since) -> spent, Clock.systemUTC());
        return new RoutedImageSearch(List.<ImageSource>of(sources), cache, new SafeImageFetcher(true, ImageAddressPolicy.Resolver.SYSTEM, "t"),
                EgressClients.direct(new ChatHttp(PROPERTIES.transport())), breakers, budget, journal, new AiTelemetry(meters), PROPERTIES,
                ImageSearchSettings.defaults());
    }

    @AfterEach
    void clearTransactionState() { TransactionSynchronizationManager.setActualTransactionActive(false); }

    @Test
    void resultsOfAllSourcesAreInterleavedInSourceOrderDeduplicatedAndMinusTheKnownOnes() {
        Fake pixabay = new Fake(ImageSearch.Source.PIXABAY);
        Fake wikimedia = new Fake(ImageSearch.Source.WIKIMEDIA);
        Fake openverse = new Fake(ImageSearch.Source.OPENVERSE);
        pixabay.answer = ok(candidate(ImageSearch.Source.PIXABAY, "p1", "https://pixabay.com/1.jpg"), candidate(ImageSearch.Source.PIXABAY, "p2", "https://pixabay.com/2.jpg"),
                candidate(ImageSearch.Source.PIXABAY, "p3", "https://pixabay.com/3.jpg"));
        wikimedia.answer = ok(candidate(ImageSearch.Source.WIKIMEDIA, "w1", "https://upload.wikimedia.org/1.jpg"),
                candidate(ImageSearch.Source.WIKIMEDIA, "w2", "https://pixabay.com/2.jpg"));
        openverse.answer = ok(candidate(ImageSearch.Source.OPENVERSE, "o1", "https://api.openverse.org/1/"),
                candidate(ImageSearch.Source.PIXABAY, "p1", "https://elsewhere/p1.jpg"));
        RoutedImageSearch router = router(pixabay, openverse, wikimedia);

        // the order of the sources is the order of the interleaving; p1 appears twice (same key) and w2 repeats p2's download
        List<ImageSearch.Candidate> all = found(router.search(request("fox", 10, Set.of())));
        assertThat(all).extracting(ImageSearch.Candidate::key).containsExactly("PIXABAY:p1", "OPENVERSE:o1", "WIKIMEDIA:w1", "PIXABAY:p2", "PIXABAY:p3");

        List<ImageSearch.Candidate> fewer = found(router.search(request("fox other", 2, Set.of("OPENVERSE:o1", "PIXABAY:p1"))));
        assertThat(fewer).extracting(ImageSearch.Candidate::key).containsExactly("WIKIMEDIA:w1", "PIXABAY:p2");
    }

    @Test
    void aFailingSourceIsNotAFailureOfTheSearchWhileAnotherAnswers() {
        Fake pixabay = new Fake(ImageSearch.Source.PIXABAY);
        Fake wikimedia = new Fake(ImageSearch.Source.WIKIMEDIA);
        pixabay.answer = AiResult.failed(new AiFailure.Transient("http_503"));
        wikimedia.answer = ok(candidate(ImageSearch.Source.WIKIMEDIA, "w1", "https://upload.wikimedia.org/1.jpg"));

        List<ImageSearch.Candidate> list = found(router(pixabay, wikimedia).search(request("fox", 5, Set.of())));

        assertThat(list).extracting(ImageSearch.Candidate::key).containsExactly("WIKIMEDIA:w1");
        assertThat(journal.outcomes.stream().map(CallJournal.Outcome::outcome)).containsExactlyInAnyOrder("TRANSIENT", "OK");
    }

    @Test
    void everySourceFailingIsAFailureAndNoLicensedResultIsAnEmptyList() {
        Fake pixabay = new Fake(ImageSearch.Source.PIXABAY);
        Fake wikimedia = new Fake(ImageSearch.Source.WIKIMEDIA);
        pixabay.answer = AiResult.failed(new AiFailure.Timeout());
        wikimedia.answer = AiResult.failed(new AiFailure.RateLimited(Duration.ofSeconds(1)));
        RoutedImageSearch router = router(pixabay, wikimedia);
        var failed = router.search(request("fox", 5, Set.of()));
        assertThat(failed).isInstanceOf(AiResult.Failed.class);

        pixabay.answer = AiResult.ok(List.of());
        wikimedia.answer = AiResult.ok(List.of());
        assertThat(found(router.search(request("nothing licensed", 5, Set.of())))).isEmpty();
    }

    @Test
    void anAnswerInTheCacheCostsNoCallAndIsKeyedByQueryAndLanguage() {
        Fake pixabay = new Fake(ImageSearch.Source.PIXABAY);
        pixabay.answer = ok(candidate(ImageSearch.Source.PIXABAY, "p1", "https://pixabay.com/1.jpg"));
        RoutedImageSearch router = router(pixabay);

        found(router.search(request("Red  Fox", 5, Set.of())));
        found(router.search(request("red fox", 5, Set.of())));
        assertThat(pixabay.calls.get()).isEqualTo(1);
        assertThat(journal.intents).hasSize(1);

        found(router.search(request("red fox", 5, Set.of("PIXABAY:p1"))));
        assertThat(pixabay.calls.get()).isEqualTo(1);
        found(router.search(new ImageSearch.Request("red fox", "ru", 5, Set.of(), null, 1)));
        assertThat(pixabay.calls.get()).isEqualTo(2);
        // a failure is not cached
        pixabay.answer = AiResult.failed(new AiFailure.Transient("x"));
        router.search(request("other query", 5, Set.of()));
        router.search(request("other query", 5, Set.of()));
        assertThat(pixabay.calls.get()).isEqualTo(4);
    }

    @Test
    void aLaterTurnForTheSameQueryGetsFreshCandidatesFromTheCachedPage() {
        Fake pixabay = new Fake(ImageSearch.Source.PIXABAY);
        List<ImageSearch.Candidate> page = new java.util.ArrayList<>();
        for (int index = 1; index <= 6; index++) page.add(candidate(ImageSearch.Source.PIXABAY, "p" + index, "https://pixabay.com/" + index + ".jpg"));
        pixabay.answer = AiResult.ok(page);
        RoutedImageSearch router = router(pixabay);

        List<ImageSearch.Candidate> first = found(router.search(request("fox", 2, Set.of())));
        Set<String> known = new java.util.HashSet<>(first.stream().map(ImageSearch.Candidate::key).toList());
        List<ImageSearch.Candidate> second = found(router.search(request("fox", 2, known)));
        known.addAll(second.stream().map(ImageSearch.Candidate::key).toList());
        List<ImageSearch.Candidate> third = found(router.search(request("fox", 2, known)));

        assertThat(first).extracting(ImageSearch.Candidate::key).containsExactly("PIXABAY:p1", "PIXABAY:p2");
        assertThat(second).extracting(ImageSearch.Candidate::key).containsExactly("PIXABAY:p3", "PIXABAY:p4");
        assertThat(third).extracting(ImageSearch.Candidate::key).containsExactly("PIXABAY:p5", "PIXABAY:p6");
        assertThat(pixabay.calls.get()).isEqualTo(1);
    }

    @Test
    void everyRealCallIsJournaledMeteredAndGuardedByABreakerPerSource() {
        Fake pixabay = new Fake(ImageSearch.Source.PIXABAY);
        Fake wikimedia = new Fake(ImageSearch.Source.WIKIMEDIA);
        wikimedia.answer = ok(candidate(ImageSearch.Source.WIKIMEDIA, "w1", "https://upload.wikimedia.org/1.jpg"));
        pixabay.answer = AiResult.failed(new AiFailure.Transient("http_503"));
        RoutedImageSearch router = router(pixabay, wikimedia);
        UUID step = UUID.randomUUID();

        for (int index = 0; index < 5; index++) router.search(new ImageSearch.Request("fox " + index, "en", 5, Set.of(), step, 2));
        assertThat(pixabay.calls.get()).isEqualTo(5);
        // the breaker of pixabay is open now, the one of wikimedia is not: pixabay is not called any more
        router.search(request("fox sixth", 5, Set.of()));
        assertThat(pixabay.calls.get()).isEqualTo(5);
        assertThat(wikimedia.calls.get()).isEqualTo(6);

        assertThat(journal.intents).allSatisfy(intent -> {
            assertThat(intent.capability()).isEqualTo(AiCapability.IMAGE_SEARCH);
            assertThat(intent.model()).isEqualTo("search");
            assertThat(intent.requestHash()).matches("[0-9a-f]{64}");
        });
        assertThat(journal.intents.stream().filter(intent -> intent.stepId() != null && intent.attempt() == 2)).hasSize(10);
        assertThat(journal.intents.stream().map(CallJournal.Intent::provider).distinct()).containsExactlyInAnyOrder("pixabay", "wikimedia");
        assertThat(meters.get("mnema_ai_calls_total").tag("provider", "wikimedia").tag("outcome", "OK").counter().count()).isEqualTo(6);
        assertThat(meters.get("mnema_ai_calls_total").tag("provider", "pixabay").tag("outcome", "TRANSIENT").counter().count()).isEqualTo(5);
    }

    @Test
    void anUnconfiguredSourceIsNotAskedNoSourceAtAllIsNotConfiguredAndAnEmptyBudgetStops() {
        Fake pixabay = new Fake(ImageSearch.Source.PIXABAY);
        pixabay.configured = false;
        RoutedImageSearch none = router(pixabay);
        assertThat(none.configured()).isFalse();
        var unconfigured = none.search(request("fox", 5, Set.of()));
        assertThat(((AiResult.Failed<List<ImageSearch.Candidate>>) unconfigured).failure()).isInstanceOf(AiFailure.NotConfigured.class);
        assertThat(pixabay.calls.get()).isZero();

        pixabay.configured = true;
        pixabay.answer = ok(candidate(ImageSearch.Source.PIXABAY, "p1", "https://pixabay.com/1.jpg"));
        RoutedImageSearch exhausted = router(pixabay);
        spent = PROPERTIES.budget().imageSearchMicros();
        var stopped = exhausted.search(request("fox", 5, Set.of()));
        assertThat(((AiResult.Failed<List<ImageSearch.Candidate>>) stopped).failure()).isInstanceOf(AiFailure.BudgetExhausted.class);
        assertThat(pixabay.calls.get()).isZero();
    }

    @Test
    void aBlankQueryFindsNothingWithoutACallAndASearchRefusesToRunInsideATransaction() {
        Fake pixabay = new Fake(ImageSearch.Source.PIXABAY);
        RoutedImageSearch router = router(pixabay);
        assertThat(found(router.search(request("   ", 5, Set.of())))).isEmpty();
        assertThat(pixabay.calls.get()).isZero();

        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThatThrownBy(() -> router.search(request("fox", 5, Set.of()))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> router.fetch(candidate(ImageSearch.Source.PIXABAY, "p1", "https://pixabay.com/1.jpg"))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aJournalThatCannotRecordTheIntentMeansNoCall() {
        Fake pixabay = new Fake(ImageSearch.Source.PIXABAY);
        pixabay.answer = ok(candidate(ImageSearch.Source.PIXABAY, "p1", "https://pixabay.com/1.jpg"));
        journal.failBegin = true;
        var result = router(pixabay).search(request("fox", 5, Set.of()));
        assertThat(result).isInstanceOf(AiResult.Failed.class);
        assertThat(pixabay.calls.get()).isZero();
    }

    @Test
    void aCandidateIsDownloadedOnlyFromTheHostsOfItsOwnSourceAndOnlyOverHttps() {
        Fake pixabay = new Fake(ImageSearch.Source.PIXABAY);
        pixabay.hosts = Set.of("pixabay.com", "cdn.pixabay.com");
        RoutedImageSearch router = router(pixabay);

        // a result of Pixabay that names another host, an internal address or a plain http URL is refused before any request
        for (String url : new String[] {"https://upload.wikimedia.org/1.jpg", "https://127.0.0.1/1.jpg", "http://pixabay.com/1.jpg", "https://[::ffff:127.0.0.1]/1.jpg",
                "https://169.254.169.254/latest/meta-data/"}) {
            var result = router.fetch(candidate(ImageSearch.Source.PIXABAY, "p1", url));
            assertThat(((AiResult.Failed<ImageSearch.Image>) result).failure()).as(url).isInstanceOf(AiFailure.Refusal.class);
        }
        // a candidate of a source that is not configured has no transport
        var elsewhere = router.fetch(candidate(ImageSearch.Source.OPENVERSE, "o1", "https://api.openverse.org/1/"));
        assertThat(((AiResult.Failed<ImageSearch.Image>) elsewhere).failure()).isInstanceOf(AiFailure.NotConfigured.class);
    }

    // ------------------------------------------------------------------ fakes

    static final class MapCache implements ImageSearchCache {
        final Map<String, List<ImageSearch.Candidate>> entries = new HashMap<>();

        private static String key(String source, String query, String lang, int page) {
            return source + "|" + query.strip().toLowerCase().replaceAll("\\s+", " ") + "|" + lang + "|" + page;
        }

        @Override
        public synchronized Optional<List<ImageSearch.Candidate>> get(String source, String query, String lang, int page) {
            return Optional.ofNullable(entries.get(key(source, query, lang, page)));
        }

        @Override
        public synchronized void put(String source, String query, String lang, int page, List<ImageSearch.Candidate> candidates) {
            entries.put(key(source, query, lang, page), List.copyOf(candidates));
        }
    }

    static final class Journal implements CallJournal {
        final List<Intent> intents = new CopyOnWriteArrayList<>();
        final List<Outcome> outcomes = new CopyOnWriteArrayList<>();
        volatile boolean failBegin;

        @Override
        public UUID begin(Intent intent) {
            if (failBegin) throw new IllegalStateException("database down");
            intents.add(intent);
            return UUID.randomUUID();
        }

        @Override
        public void finish(UUID callId, Outcome outcome) { outcomes.add(outcome); }
    }
}
