package app.mnema.learning.ai;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The two search adapters on recorded answers served by a local server (no network, no key): the request each one sends, the mapping of the answer
 * (hit-word markup, snippets, dates, https only), the hardening of the Yandex XML (a DOCTYPE or an entity is refused), both failure layers of
 * Yandex, the statuses of both, and the prices.
 */
class WebSearchAdaptersTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String KEY = "search-SECRET-KEY-1234";
    private static final String FOLDER = "b1gfolder0123";
    private static final Duration BUDGET = Duration.ofSeconds(5);

    private final ChatHttp http = new ChatHttp(new AiProperties.Transport(Duration.ofSeconds(2), Duration.ofSeconds(2), 8 << 20, Duration.ofSeconds(2)));
    private ImageServer server;

    @BeforeEach
    void start() { server = new ImageServer(); }

    @AfterEach
    void stop() {
        server.close();
        http.close();
    }

    private static String fixture(String name) {
        try (InputStream in = WebSearchAdaptersTest.class.getResourceAsStream("/ai/search/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException | NullPointerException failure) {
            throw new IllegalStateException("Missing fixture " + name);
        }
    }

    private static String raw(String xml) {
        return "{\"rawData\":\"" + Base64.getEncoder().encodeToString(xml.getBytes(StandardCharsets.UTF_8)) + "\"}";
    }

    private static String errorXml(int code) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?><yandexsearch version=\"1.0\"><response date=\"20261005T120000\"><error code=\"" + code
                + "\">message</error></response></yandexsearch>";
    }

    private static ResearchSettings settings() {
        return new ResearchSettings(15, 30, 5, Duration.ofSeconds(10), Duration.ofSeconds(90), FOLDER, "225", new BigDecimal("0.488"), new BigDecimal("0.005"));
    }

    private YandexWebSearch yandex(String key) {
        var provider = new AiProperties.Provider(true, server.origin(), key, "", "", "");
        return new YandexWebSearch(provider, http, settings(), BigDecimal.valueOf(85), Clock.systemUTC());
    }

    private PerplexityWebSearch perplexity(String key) {
        var provider = new AiProperties.Provider(true, server.origin(), key, "", "", "");
        return new PerplexityWebSearch(provider, http, settings(), Clock.systemUTC());
    }

    private static WebSearch.Request request(String lang, String... queries) {
        return new WebSearch.Request(List.of(queries), lang, 5, null, null, 1);
    }

    private static List<WebSearch.Result> ok(AiResult<List<WebSearch.Result>> result) {
        assertThat(result).isInstanceOf(AiResult.Ok.class);
        return ((AiResult.Ok<List<WebSearch.Result>>) result).value();
    }

    private static AiFailure failure(AiResult<List<WebSearch.Result>> result) {
        assertThat(result).isInstanceOf(AiResult.Failed.class);
        return ((AiResult.Failed<List<WebSearch.Result>>) result).failure();
    }

    // ------------------------------------------------------------------ Yandex

    @Test
    void yandexAsksTheRussianIndexOfTheFolderOnceAndKeepsOnlyHttpsDocumentsWithoutMarkup() throws Exception {
        server.json("/v2/web/search", raw(fixture("yandex-ok.xml")));

        List<WebSearch.Result> results = ok(yandex(KEY).search(request("ru", "планировщик postgresql"), BUDGET));

        assertThat(server.requests).hasSize(1);
        ImageServer.Recorded sent = server.requests.getFirst();
        assertThat(sent.method()).isEqualTo("POST");
        assertThat(sent.headers().get("Authorization")).containsExactly("Api-Key " + KEY);
        JsonNode body = JSON.readTree(sent.body());
        assertThat(body.path("query").path("searchType").stringValue(null)).isEqualTo("SEARCH_TYPE_RU");
        assertThat(body.path("query").path("queryText").stringValue(null)).isEqualTo("планировщик postgresql");
        assertThat(body.path("query").path("page").stringValue(null)).isEqualTo("0");
        assertThat(body.path("groupSpec").path("groupMode").stringValue(null)).isEqualTo("GROUP_MODE_DEEP");
        assertThat(body.path("groupSpec").path("docsInGroup").stringValue(null)).isEqualTo("1");
        assertThat(body.path("region").stringValue(null)).isEqualTo("225");
        assertThat(body.path("l10n").stringValue(null)).isEqualTo("LOCALIZATION_RU");
        assertThat(body.path("folderId").stringValue(null)).isEqualTo(FOLDER);
        assertThat(body.path("responseFormat").stringValue(null)).isEqualTo("FORMAT_XML");

        // the http document and the one with user info are dropped; the fragment goes; the hit-word markup is text
        assertThat(results).extracting(WebSearch.Result::url).containsExactly("https://postgrespro.ru/docs/postgresql/current/planner-optimizer",
                "https://ru.wikipedia.org/wiki/PostgreSQL");
        WebSearch.Result first = results.getFirst();
        assertThat(first.title()).isEqualTo("Как работает планировщик запросов в PostgreSQL");
        assertThat(first.snippet()).isEqualTo("Планировщик выбирает самый дешёвый план выполнения. … Стоимость плана складывается из стоимостей узлов & их числа.");
        assertThat(first.date()).isEqualTo("2025-08-14");
        assertThat(first.provider()).isEqualTo(WebSearch.Provider.YANDEX);
        assertThat(first.rank()).isEqualTo(1);
        // no passages: the headline is the snippet; a date that is not one is none
        assertThat(results.get(1).snippet()).startsWith("PostgreSQL — свободная объектно-реляционная");
        assertThat(results.get(1).date()).isNull();
        assertThat(results.get(1).rank()).isEqualTo(2);
        // nothing of the saved copy is kept
        assertThat(results.toString()).doesNotContain("hghltd", "secret-copy");
    }

    @Test
    void aQueryInAnotherLanguageUsesTheInternationalIndexWithoutARegion() throws Exception {
        server.json("/v2/web/search", raw(fixture("yandex-ok.xml")));

        yandex(KEY).search(request("en", "query planner"), BUDGET);

        JsonNode body = JSON.readTree(server.requests.getFirst().body());
        assertThat(body.path("query").path("searchType").stringValue(null)).isEqualTo("SEARCH_TYPE_COM");
        assertThat(body.path("l10n").stringValue(null)).isEqualTo("LOCALIZATION_EN");
        assertThat(body.has("region")).isFalse();
    }

    @Test
    void aDoctypeOrAnEntityInTheAnswerIsRefusedBeforeAnythingIsResolved() {
        for (String name : List.of("yandex-xxe.xml", "yandex-billion-laughs.xml")) {
            server.json("/v2/web/search", raw(fixture(name)));
            assertThat(failure(yandex(KEY).search(request("ru", "запрос"), BUDGET))).isEqualTo(new AiFailure.InvalidOutput("xml"));
        }
        // a payload in another encoding cannot hide a DOCTYPE from the scan
        String utf16 = Base64.getEncoder().encodeToString(fixture("yandex-xxe.xml").getBytes(StandardCharsets.UTF_16LE));
        server.json("/v2/web/search", "{\"rawData\":\"" + utf16 + "\"}");
        assertThat(failure(yandex(KEY).search(request("ru", "запрос"), BUDGET))).isEqualTo(new AiFailure.InvalidOutput("xml"));
    }

    @Test
    void anAnswerThatIsNotTheFormatIsInvalidOutputNeverAnException() {
        String huge = "<yandexsearch><response>" + "x".repeat(YandexWebSearch.MAX_XML_BYTES) + "</response></yandexsearch>";
        for (String body : List.of("not json", "{}", "{\"rawData\":\"***not base64***\"}", raw("<other/>"), raw("<yandexsearch><response>"),
                raw(huge), "{\"rawData\":" + "\"" + "A".repeat(YandexWebSearch.MAX_XML_BYTES * 2) + "\"}")) {
            server.json("/v2/web/search", body);
            assertThat(failure(yandex(KEY).search(request("ru", "запрос"), BUDGET))).isInstanceOf(AiFailure.InvalidOutput.class);
        }
    }

    @Test
    void theErrorCodeInsideAnHttp200AnswerMapsToTheSameFailuresAsTheStatusDoes() {
        record Case(int code, Class<?> expected) { }
        for (Case each : List.of(new Case(55, AiFailure.RateLimited.class), new Case(32, AiFailure.RateLimited.class),
                new Case(31, AiFailure.NotConfigured.class), new Case(42, AiFailure.NotConfigured.class), new Case(48, AiFailure.NotConfigured.class),
                new Case(18, AiFailure.InvalidOutput.class), new Case(37, AiFailure.InvalidOutput.class), new Case(100, AiFailure.Transient.class),
                new Case(20, AiFailure.Transient.class), new Case(9999, AiFailure.Transient.class))) {
            server.json("/v2/web/search", raw(errorXml(each.code())));
            assertThat(failure(yandex(KEY).search(request("ru", "запрос"), BUDGET))).as("code " + each.code()).isInstanceOf(each.expected());
        }
        // no results is a successful, paid, empty answer
        server.json("/v2/web/search", raw(errorXml(15)));
        assertThat(ok(yandex(KEY).search(request("ru", "запрос"), BUDGET))).isEmpty();
    }

    @Test
    void theHttpStatusMapsToAFailureAndTheBodyOfAnErrorIsNeverRead() {
        record Case(int status, Class<?> expected) { }
        for (Case each : List.of(new Case(400, AiFailure.InvalidOutput.class), new Case(401, AiFailure.NotConfigured.class),
                new Case(403, AiFailure.NotConfigured.class), new Case(429, AiFailure.RateLimited.class), new Case(500, AiFailure.Transient.class),
                new Case(503, AiFailure.Transient.class), new Case(504, AiFailure.Timeout.class), new Case(404, AiFailure.Refusal.class))) {
            server.on("/v2/web/search", exchange -> ImageServer.reply(exchange, each.status(), "application/json",
                    "{\"code\":16,\"message\":\"echoes the query планировщик\"}".getBytes(StandardCharsets.UTF_8)));
            AiFailure failure = failure(yandex(KEY).search(request("ru", "запрос"), BUDGET));
            assertThat(failure).as("status " + each.status()).isInstanceOf(each.expected());
            assertThat(failure.toString()).doesNotContain("планировщик");
        }
        server.on("/v2/web/search", exchange -> {
            exchange.getResponseHeaders().add("Retry-After", "7");
            ImageServer.reply(exchange, 429, "application/json", new byte[0]);
        });
        assertThat(failure(yandex(KEY).search(request("ru", "запрос"), BUDGET))).isEqualTo(new AiFailure.RateLimited(Duration.ofSeconds(7)));
    }

    @Test
    void aServerThatIsGoneIsATransientFailure() {
        server.close();
        assertThat(failure(yandex(KEY).search(request("ru", "запрос"), BUDGET))).isInstanceOf(AiFailure.Transient.class);
        server = new ImageServer();
    }

    @Test
    void yandexIsConfiguredOnlyWithAKeyAndAFolderIsPricedPerRequestAndNeverGoesThroughTheProxy() {
        assertThat(yandex(KEY).configured()).isTrue();
        assertThat(yandex("").configured()).isFalse();
        var noFolder = new YandexWebSearch(new AiProperties.Provider(true, server.origin(), KEY, "", "", ""), http,
                new ResearchSettings(15, 30, 5, Duration.ofSeconds(10), Duration.ofSeconds(90), "", "225", new BigDecimal("0.488"), new BigDecimal("0.005")),
                BigDecimal.valueOf(85), Clock.systemUTC());
        assertThat(noFolder.configured()).isFalse();
        var disabled = new YandexWebSearch(new AiProperties.Provider(false, server.origin(), KEY, "", "", ""), http, settings(), BigDecimal.valueOf(85),
                Clock.systemUTC());
        assertThat(disabled.configured()).isFalse();
        var noTransport = new YandexWebSearch(new AiProperties.Provider(true, "", KEY, "", "", ""), null, settings(), BigDecimal.valueOf(85), Clock.systemUTC());
        assertThat(noTransport.configured()).isFalse();
        // 0.488 roubles at 85 roubles to the dollar, rounded up to the micro-dollar
        assertThat(yandex(KEY).requestCostMicros()).isEqualTo(5_742);
        assertThat(yandex(KEY).maxQueries()).isEqualTo(1);
        assertThat(yandex(KEY).egress()).isEqualTo(AiProperties.EgressMode.DIRECT);
        assertThat(yandex(KEY).provider()).isEqualTo("yandex");
    }

    @Test
    void theDefaultEndpointIsTheYandexCloudSearchApi() {
        var adapter = new YandexWebSearch(new AiProperties.Provider(true, "", KEY, "", "", ""), http, settings(), BigDecimal.valueOf(85), Clock.systemUTC());
        assertThat(adapter.configured()).isTrue();
    }

    // -------------------------------------------------------------- Perplexity

    @Test
    void perplexitySendsASingleQueryAsAStringAndReadsAFlatAnswer() throws Exception {
        server.json("/search", fixture("perplexity-flat.json"));

        List<WebSearch.Result> results = ok(perplexity(KEY).search(new WebSearch.Request(List.of("query planner"), "en", 5, "us", null, 1), BUDGET));

        ImageServer.Recorded sent = server.requests.getFirst();
        assertThat(sent.headers().get("Authorization")).containsExactly("Bearer " + KEY);
        JsonNode body = JSON.readTree(sent.body());
        assertThat(body.path("query").stringValue(null)).isEqualTo("query planner");
        assertThat(body.path("max_results").intValue()).isEqualTo(5);
        assertThat(body.path("max_tokens_per_page").intValue()).isEqualTo(400);
        assertThat(body.path("search_language_filter").get(0).stringValue(null)).isEqualTo("en");
        assertThat(body.path("country").stringValue(null)).isEqualTo("US");

        // the http result is dropped; markup in a title or a snippet is stripped; a date that is not a date is none; ranks run over the list
        assertThat(results).extracting(WebSearch.Result::url).containsExactly("https://www.postgresql.org/docs/current/planner-optimizer.html",
                "https://example.org/planning?utm_source=feed", "https://example.org/odd");
        assertThat(results.get(0).date()).isEqualTo("2025-01-23");
        assertThat(results.get(1).title()).isEqualTo("Query planning basics");
        assertThat(results.get(1).snippet()).isEqualTo("A short explanation of plans.");
        assertThat(results.get(1).date()).isNull();
        assertThat(results.get(2).date()).isNull();
        assertThat(results).extracting(WebSearch.Result::rank).containsExactly(1, 2, 3);
        assertThat(results).extracting(WebSearch.Result::provider).containsOnly(WebSearch.Provider.PERPLEXITY);
        assertThat(results).extracting(WebSearch.Result::queryIndex).containsOnly(0);
    }

    @Test
    void severalQueriesGoInOneRequestAndANestedAnswerTellsWhichQueryFoundWhat() throws Exception {
        server.json("/search", fixture("perplexity-nested.json"));

        List<WebSearch.Result> results = ok(perplexity(KEY).search(request("en", "first", "second"), BUDGET));

        JsonNode body = JSON.readTree(server.requests.getFirst().body());
        assertThat(body.path("query").isArray()).isTrue();
        assertThat(body.path("query")).hasSize(2);
        assertThat(body.has("country")).isFalse();
        assertThat(results).extracting(WebSearch.Result::queryIndex).containsExactly(0, 0, 1);
        assertThat(results).extracting(WebSearch.Result::rank).containsExactly(1, 2, 1);
        assertThat(results).extracting(WebSearch.Result::url).containsExactly("https://example.org/q1/a", "https://example.org/q1/b", "https://example.org/q2/a");
    }

    @Test
    void perplexityMapsStatusesAndMalformedAnswers() {
        record Case(int status, Class<?> expected) { }
        for (Case each : List.of(new Case(401, AiFailure.NotConfigured.class), new Case(422, AiFailure.InvalidOutput.class),
                new Case(429, AiFailure.RateLimited.class), new Case(500, AiFailure.Transient.class))) {
            server.on("/search", exchange -> ImageServer.reply(exchange, each.status(), "application/json", "{\"error\":{}}".getBytes(StandardCharsets.UTF_8)));
            assertThat(failure(perplexity(KEY).search(request("en", "q"), BUDGET))).as("status " + each.status()).isInstanceOf(each.expected());
        }
        server.json("/search", "not json");
        assertThat(failure(perplexity(KEY).search(request("en", "q"), BUDGET))).isEqualTo(new AiFailure.InvalidOutput("malformed"));
        server.json("/search", "{\"results\":{}}");
        assertThat(failure(perplexity(KEY).search(request("en", "q"), BUDGET))).isEqualTo(new AiFailure.InvalidOutput("shape"));
        server.json("/search", "{\"results\":[]}");
        assertThat(ok(perplexity(KEY).search(request("en", "q"), BUDGET))).isEmpty();
        server.json("/search", "{\"results\":[1,\"x\",{\"url\":null}]}");
        assertThat(ok(perplexity(KEY).search(request("en", "q"), BUDGET))).isEmpty();
    }

    @Test
    void perplexityIsConfiguredOnlyWithAKeyAndATransportAndCostsAFixedPricePerRequestOfUpToFiveQueries() {
        assertThat(perplexity(KEY).configured()).isTrue();
        assertThat(perplexity("").configured()).isFalse();
        // a proxied provider without an active proxy has no transport
        var noTransport = new PerplexityWebSearch(new AiProperties.Provider(true, "", KEY, "", "", "", AiProperties.EgressMode.PROXY), null, settings(),
                Clock.systemUTC());
        assertThat(noTransport.configured()).isFalse();
        assertThat(noTransport.egress()).isEqualTo(AiProperties.EgressMode.DIRECT);
        assertThat(perplexity(KEY).requestCostMicros()).isEqualTo(5_000);
        assertThat(perplexity(KEY).maxQueries()).isEqualTo(5);
        assertThat(perplexity(KEY).provider()).isEqualTo("perplexity");
        var defaultEndpoint = new PerplexityWebSearch(new AiProperties.Provider(true, "", KEY, "", "", ""), http, settings(), Clock.systemUTC());
        assertThat(defaultEndpoint.configured()).isTrue();
    }

    @Test
    void yandexNormalizesAnInternationalizedHostAndANonAsciiPathOfAResult() {
        String xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?><yandexsearch version=\"1.0\"><response><results><grouping><group>"
                + "<doc><url>https://ru.wikipedia.org/wiki/Москва</url><title>Москва</title></doc></group><group>"
                + "<doc><url>  https://пример.рф/страница  </url><title>Пример</title></doc></group></grouping></results></response></yandexsearch>";
        server.json("/v2/web/search", raw(xml));

        List<WebSearch.Result> results = ok(yandex(KEY).search(request("ru", "москва"), BUDGET));

        assertThat(results).extracting(WebSearch.Result::url).containsExactly("https://ru.wikipedia.org/wiki/%D0%9C%D0%BE%D1%81%D0%BA%D0%B2%D0%B0",
                "https://xn--e1afmkfd.xn--p1ai/%D1%81%D1%82%D1%80%D0%B0%D0%BD%D0%B8%D1%86%D0%B0");
    }

    @Test
    void aBodyOfAnAnsweredRequestThatIsRejectedIsPaidButAnErrorStatusIsNot() {
        YandexWebSearch adapter = yandex(KEY);
        assertThat(adapter.paid(new AiFailure.InvalidOutput("xml"))).isTrue();
        assertThat(adapter.paid(new AiFailure.InvalidOutput("malformed"))).isTrue();
        assertThat(adapter.paid(new AiFailure.InvalidOutput("shape"))).isTrue();
        assertThat(adapter.paid(new AiFailure.InvalidOutput("body_too_large"))).isTrue();
        assertThat(adapter.paid(new AiFailure.InvalidOutput("request"))).isFalse();
        assertThat(adapter.paid(new AiFailure.Transient("http_503"))).isFalse();
        assertThat(perplexity(KEY).paid(new AiFailure.InvalidOutput("xml"))).isFalse();
        assertThat(perplexity(KEY).paid(new AiFailure.InvalidOutput("malformed"))).isTrue();
        assertThat(perplexity(KEY).paid(new AiFailure.InvalidOutput("shape"))).isTrue();
        assertThat(perplexity(KEY).paid(new AiFailure.InvalidOutput("request"))).isFalse();
        assertThat(perplexity(KEY).paid(new AiFailure.Transient("http_503"))).isFalse();
    }

    @Test
    void onlyAnAsciiCompatibleXmlDocumentIsRead() {
        for (String xml : List.of("\uFEFF" + fixture("yandex-ok.xml"), " " + fixture("yandex-ok.xml"),
                fixture("yandex-ok.xml").replaceFirst("(?i)utf-8", "windows-1251"))) {
            server.json("/v2/web/search", raw(xml));
            assertThat(failure(yandex(KEY).search(request("ru", "запрос"), BUDGET))).isEqualTo(new AiFailure.InvalidOutput("xml"));
        }
        server.json("/v2/web/search", raw(fixture("yandex-ok.xml")));
        assertThat(ok(yandex(KEY).search(request("ru", "запрос"), BUDGET))).isNotEmpty();
    }

    @Test
    void perplexityStripsWhitespaceAroundAUrl() {
        server.json("/search", "{\"results\":[{\"url\":\"  https://example.org/a \\n\",\"title\":\"t\",\"snippet\":\"s\"}]}");
        assertThat(ok(perplexity(KEY).search(request("en", "q"), BUDGET))).extracting(WebSearch.Result::url).containsExactly("https://example.org/a");
    }
}
