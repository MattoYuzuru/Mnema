package app.mnema.learning.ai;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in smoke test of the real search providers; skipped unless {@code MNEMA_AI_LIVE=true}, and each provider is skipped without its credentials:
 * <pre>
 * MNEMA_AI_LIVE=true ./gradlew :services:learning:cleanTest :services:learning:test --tests '*WebSearchLive*'
 * </pre>
 * Yandex needs {@code MNEMA_AI_YANDEX_SEARCH_API_KEY} and {@code MNEMA_AI_YANDEX_FOLDER_ID} (and a Russian host: the API does not answer from outside); Perplexity needs
 * {@code MNEMA_AI_PERPLEXITY_API_KEY} and the egress proxy ({@code MNEMA_AI_EGRESS_PROXY_URL}, optionally {@code ..._USER} / {@code ..._PASSWORD}). Each asks one Russian and
 * one English question through the router, asserts the shape (https results with a title, at most 300 characters of snippet) and prints the latency and the
 * number of results, for the numbers of the spike (section 6: latency p50/p95, whether the answer carries a date); it never prints a key, a query or a URL. The rest
 * of the live checklist (the {@code l10n} spelling, billing of an empty answer, saved fixtures, the quality of the COM index) is done by hand. Every call is a paid request.
 */
@EnabledIfEnvironmentVariable(named = "MNEMA_AI_LIVE", matches = "true")
class WebSearchLiveTest {
    private static String env(String name) {
        String value = System.getenv(name);
        return value == null ? "" : value;
    }

    private static AiProperties properties(List<String> route, Map<String, AiProperties.Provider> providers, AiProperties.Egress egress) {
        AiProperties base = AiTestSupport.properties("", AiTestSupport.routes(List.of(), List.of(), List.of()), providers);
        return new AiProperties(base.provider(), new AiProperties.Routes(List.of(), List.of(), List.of(), Duration.ofSeconds(8), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), route), providers, base.models(), new AiProperties.Transport(Duration.ofSeconds(5), Duration.ofSeconds(30), 8 << 20, Duration.ofSeconds(30)),
                base.retry(), base.breaker(), base.permits(), base.budget(), base.userKey(), base.prompt(), egress);
    }

    private static void roundTrip(AiProperties properties, ResearchSettings settings, String route, String lang, String query) {
        try (EgressClients clients = EgressClients.create(properties)) {
            var adapters = AiConfiguration.webSearchAdapters(properties, settings, clients, BigDecimal.valueOf(85), Clock.systemUTC());
            var router = new RoutedWebSearch(properties, settings, adapters, new BreakerRegistry(Clock.systemUTC(), properties.breaker()),
                    new AiBudget(properties.budget(), (capability, since) -> 0, Clock.systemUTC()), new SpeechTestSupport.RecordingJournal(),
                    new AiTelemetry(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
            Assumptions.assumeTrue(router.configured(), route + " is not configured here");
            long started = System.nanoTime();
            AiResult<WebSearch.Answer> result = router.search(new WebSearch.Request(List.of(query), lang, 5, null, UUID.randomUUID(), 1));
            long millis = Duration.ofNanos(System.nanoTime() - started).toMillis();
            assertThat(result).as(route + " " + lang).isInstanceOf(AiResult.Ok.class);
            WebSearch.Answer answer = ((AiResult.Ok<WebSearch.Answer>) result).value();
            long dated = answer.results().stream().filter(each -> each.date() != null).count();
            System.out.println("web_search_live route=" + route + " lang=" + lang + " latency_ms=" + millis + " results=" + answer.results().size()
                    + " dated=" + dated + " requests=" + answer.requests() + " cost_micros=" + answer.costMicros());
            assertThat(answer.requests()).isEqualTo(1);
            assertThat(answer.results()).isNotEmpty().allSatisfy(each -> {
                assertThat(each.url()).startsWith("https://");
                assertThat(each.title()).isNotBlank();
                assertThat(each.snippet().length()).isLessThanOrEqualTo(WebSearch.MAX_SNIPPET);
            });
        }
    }

    @Test
    void yandexAnswersARussianAndAnEnglishQuestion() {
        String key = env("MNEMA_AI_YANDEX_SEARCH_API_KEY");
        String folder = env("MNEMA_AI_YANDEX_FOLDER_ID");
        Assumptions.assumeTrue(!key.isBlank() && !folder.isBlank(), "MNEMA_AI_YANDEX_SEARCH_API_KEY and MNEMA_AI_YANDEX_FOLDER_ID are not set");
        var yandex = new AiProperties.Provider(true, "https://searchapi.api.cloud.yandex.net", key, "", "", "", AiProperties.EgressMode.DIRECT);
        AiProperties properties = properties(List.of("yandex"), Map.of("yandex-search", yandex), null);
        ResearchSettings defaults = ResearchSettings.defaults();
        ResearchSettings settings = new ResearchSettings(defaults.maxRequests(), defaults.maxResults(), defaults.resultsPerQuery(), defaults.callTimeout(),
                defaults.deadline(), folder, defaults.yandexRegion(), defaults.yandexRubPerRequest(), defaults.perplexityUsdPerRequest());
        roundTrip(properties, settings, "yandex", "ru", "как работает планировщик запросов в PostgreSQL");
        roundTrip(properties, settings, "yandex", "en", "how does the PostgreSQL query planner choose a plan");
    }

    @Test
    void perplexityAnswersARussianAndAnEnglishQuestionThroughTheProxy() {
        String key = env("MNEMA_AI_PERPLEXITY_API_KEY");
        Assumptions.assumeTrue(!key.isBlank() && !env("MNEMA_AI_EGRESS_PROXY_URL").isBlank(), "MNEMA_AI_PERPLEXITY_API_KEY and the egress proxy are not set");
        var perplexity = new AiProperties.Provider(true, "https://api.perplexity.ai", key, "", "", "", AiProperties.EgressMode.PROXY);
        AiProperties properties = properties(List.of("perplexity"), Map.of("perplexity", perplexity),
                new AiProperties.Egress(env("MNEMA_AI_EGRESS_PROXY_URL"), env("MNEMA_AI_EGRESS_PROXY_USER"), env("MNEMA_AI_EGRESS_PROXY_PASSWORD"), true));
        roundTrip(properties, ResearchSettings.defaults(), "perplexity", "ru", "как работает планировщик запросов в PostgreSQL");
        roundTrip(properties, ResearchSettings.defaults(), "perplexity", "en", "how does the PostgreSQL query planner choose a plan");
    }
}
