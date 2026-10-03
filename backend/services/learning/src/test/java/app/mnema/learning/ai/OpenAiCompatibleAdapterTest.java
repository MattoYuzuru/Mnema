package app.mnema.learning.ai;

import app.mnema.learning.ai.FakeProvider.Reply;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Recorded provider replies served by a loopback {@code HttpServer}: no network, no key. */
class OpenAiCompatibleAdapterTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration BUDGET = Duration.ofSeconds(5);
    private static final Map<String, AiProperties.Model> PRICES = Map.of("deepseek-flash", AiTestSupport.FLASH,
            "GigaChat-2", AiTestSupport.GIGA);

    private FakeProvider provider;
    private ChatHttp http;

    @BeforeEach
    void start() {
        provider = FakeProvider.start();
        http = new ChatHttp(AiTestSupport.properties("", AiTestSupport.routes(List.of(), List.of(), List.of()), Map.of()).transport());
    }

    @AfterEach
    void stop() {
        provider.close();
        http.close();
    }

    private OpenAiCompatibleAdapter deepseek() { return adapter(OpenAiCompatibleAdapter.Dialect.DEEPSEEK, "deepseek", "sk-test-SECRET"); }

    private OpenAiCompatibleAdapter adapter(OpenAiCompatibleAdapter.Dialect dialect, String name, String key) {
        return new OpenAiCompatibleAdapter(name, dialect, URI.create(provider.baseUrl()), BearerSource.staticKey(key), http,
                PRICES, Clock.systemUTC());
    }

    private static AiFailure failure(AiResult<TextResponse> result) {
        assertThat(result).isInstanceOf(AiResult.Failed.class);
        return ((AiResult.Failed<TextResponse>) result).failure();
    }

    private static TextResponse ok(AiResult<TextResponse> result) {
        assertThat(result).isInstanceOf(AiResult.Ok.class);
        return ((AiResult.Ok<TextResponse>) result).value();
    }

    @Test
    void aNonStreamedReplyIsParsedWithCacheUsageAndCost() {
        provider.enqueue(Reply.fixtureJson("chat-ok.json"));
        TextResponse response = ok(deepseek().attempt("deepseek-flash", AiTestSupport.request(), BUDGET));

        assertThat(response.text()).isEqualTo("# Заголовок\n\nТекст материала.");
        assertThat(response.finishReason()).isEqualTo(TextResponse.FinishReason.STOP);
        assertThat(response.usage()).isEqualTo(new Usage(2500, 2000, 500, 300));
        // 2000 * 6000 + 500 * 300000 + 300 * 1200000 micro-dollars per million tokens, rounded up
        assertThat(response.costMicros()).isEqualTo(522);
        assertThat(response.providerRequestId()).isEqualTo("chatcmpl-5f1c0e2b");
        assertThat(response.route()).isEqualTo(new TextResponse.RouteUsed("deepseek", "deepseek-flash"));
    }

    @Test
    void theRequestDisablesThinkingMergesSegmentsAndSendsOnlyTheOpaqueUser() throws Exception {
        provider.enqueue(Reply.fixtureJson("chat-ok.json"));
        deepseek().attempt("deepseek-flash", AiTestSupport.request(), BUDGET);

        FakeProvider.Recorded recorded = provider.requests().get(0);
        assertThat(recorded.path()).isEqualTo("/chat/completions");
        assertThat(recorded.headers()).containsEntry("authorization", "Bearer sk-test-SECRET");
        JsonNode body = JSON.readTree(recorded.body());
        assertThat(body.path("model").stringValue()).isEqualTo("deepseek-flash");
        assertThat(body.path("thinking").path("type").stringValue()).isEqualTo("disabled");
        assertThat(body.path("user_id").stringValue()).isEqualTo(AiTestSupport.USER_KEY);
        assertThat(body.path("max_tokens").intValue()).isEqualTo(1_000);
        assertThat(body.path("temperature").doubleValue()).isEqualTo(0.7);
        assertThat(body.has("stream")).isFalse();
        assertThat(body.has("response_format")).isFalse();
        assertThat(body.path("messages")).hasSize(2);
        assertThat(body.path("messages").path(0).path("role").stringValue()).isEqualTo("system");
        assertThat(body.path("messages").path(0).path("content").stringValue()).isEqualTo("СИСТЕМА");
        // consecutive same-role segments are one message, joined deterministically, so the cacheable prefix is byte-stable
        assertThat(body.path("messages").path(1).path("content").stringValue()).isEqualTo("БРИФ\n\nЗАДАЧА");
    }

    @Test
    void thePlannerRoutesSwitchThinkingOnAndEveryOtherRouteKeepsItOff() throws Exception {
        provider.enqueue(Reply.fixtureJson("chat-ok.json"), Reply.fixtureJson("chat-ok.json"), Reply.fixtureJson("chat-ok.json"));
        deepseek().attempt("deepseek-flash", AiTestSupport.request().withRoute(AiRoute.PLAN), BUDGET);
        deepseek().attempt("deepseek-v4-pro", AiTestSupport.request().withRoute(AiRoute.PLAN_STRONG), BUDGET);
        deepseek().attempt("deepseek-flash", AiTestSupport.request().withRoute(AiRoute.TEXT_STRONG), BUDGET);

        assertThat(JSON.readTree(provider.requests().get(0).body()).path("thinking").path("type").stringValue()).isEqualTo("enabled");
        assertThat(JSON.readTree(provider.requests().get(1).body()).path("thinking").path("type").stringValue()).isEqualTo("enabled");
        assertThat(JSON.readTree(provider.requests().get(2).body()).path("thinking").path("type").stringValue()).isEqualTo("disabled");
    }

    @Test
    void aJsonContractAsksForAJsonObjectAndRejectsAnythingElse() throws Exception {
        provider.enqueue(Reply.fixtureJson("chat-json-ok.json"), Reply.fixtureJson("chat-json-array.json"),
                Reply.fixtureJson("chat-json-truncated.json"));
        OpenAiCompatibleAdapter adapter = deepseek();

        assertThat(ok(adapter.attempt("deepseek-flash", AiTestSupport.jsonRequest(), BUDGET)).text()).isEqualTo("{\"exercises\":[]}");
        assertThat(failure(adapter.attempt("deepseek-flash", AiTestSupport.jsonRequest(), BUDGET)))
                .isEqualTo(new AiFailure.InvalidOutput("json_not_object"));
        assertThat(failure(adapter.attempt("deepseek-flash", AiTestSupport.jsonRequest(), BUDGET)))
                .isEqualTo(new AiFailure.InvalidOutput("malformed_json_output"));
        assertThat(JSON.readTree(provider.requests().get(0).body()).path("response_format").path("type").stringValue())
                .isEqualTo("json_object");
    }

    @Test
    void unusableRepliesAreInvalidOutputRefusalOrTransientNeverSuccess() {
        provider.enqueue(Reply.fixtureJson("chat-empty-content.json"), Reply.fixtureJson("chat-no-choices.json"),
                Reply.json(200, FakeProvider.fixture("chat-malformed.txt")), Reply.fixtureJson("chat-content-filter.json"),
                Reply.fixtureJson("chat-refusal-field.json"), Reply.fixtureJson("chat-resource.json"));
        OpenAiCompatibleAdapter adapter = deepseek();
        List<AiFailure> failures = new ArrayList<>();
        for (int index = 0; index < 6; index++) {
            failures.add(failure(adapter.attempt("deepseek-flash", AiTestSupport.request(), BUDGET)));
        }
        assertThat(failures).containsExactly(new AiFailure.InvalidOutput("empty_content"),
                new AiFailure.InvalidOutput("no_choices"), new AiFailure.InvalidOutput("malformed_json"),
                new AiFailure.Refusal("content_filter"), new AiFailure.Refusal("refusal"),
                new AiFailure.Transient("finish_insufficient_system_resource"));
    }

    @Test
    void aTruncatedAnswerIsReportedAsLengthAndAMissingUsageIsEstimated() {
        provider.enqueue(Reply.fixtureJson("chat-length.json"), Reply.fixtureJson("chat-no-usage.json"));
        OpenAiCompatibleAdapter adapter = deepseek();

        assertThat(ok(adapter.attempt("deepseek-flash", AiTestSupport.request(), BUDGET)).finishReason())
                .isEqualTo(TextResponse.FinishReason.LENGTH);
        TextResponse estimated = ok(adapter.attempt("deepseek-flash", AiTestSupport.request(), BUDGET));
        assertThat(estimated.usage().promptTokens()).isGreaterThan(0);
        assertThat(estimated.usage().cacheHitTokens()).isZero();
        assertThat(estimated.usage().completionTokens()).isEqualTo(TokenCounter.estimate("Короткий ответ без usage."));
    }

    @Test
    void httpStatusesMapToFailureKinds() {
        String date = DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.now().plusSeconds(120).atOffset(ZoneOffset.UTC));
        provider.enqueue(Reply.status(429, "Retry-After", "7"), Reply.status(429, "Retry-After", date),
                Reply.status(429, "Retry-After", "soon"), Reply.status(429), Reply.status(429, "Retry-After", "99999"),
                Reply.status(503), Reply.status(408), Reply.status(401), Reply.status(402), Reply.status(404),
                Reply.status(400), Reply.status(422), Reply.status(302, "Location", provider.baseUrl() + "/elsewhere"));
        OpenAiCompatibleAdapter adapter = deepseek();
        List<AiFailure> failures = new ArrayList<>();
        for (int index = 0; index < 13; index++) {
            failures.add(failure(adapter.attempt("deepseek-flash", AiTestSupport.request(), BUDGET)));
        }

        assertThat(failures.get(0)).isEqualTo(new AiFailure.RateLimited(Duration.ofSeconds(7)));
        AiFailure.RateLimited dated = (AiFailure.RateLimited) failures.get(1);
        assertThat(dated.retryAfter()).isBetween(Duration.ofSeconds(100), Duration.ofSeconds(121));
        assertThat(failures.get(2)).isEqualTo(new AiFailure.RateLimited(Duration.ZERO));
        assertThat(failures.get(3)).isEqualTo(new AiFailure.RateLimited(Duration.ZERO));
        assertThat(failures.get(4)).isEqualTo(new AiFailure.RateLimited(Duration.ofHours(1)));
        assertThat(failures.subList(5, 7)).containsExactly(new AiFailure.Transient("http_503"), new AiFailure.Transient("http_408"));
        assertThat(failures.subList(7, 10)).containsExactly(new AiFailure.NotConfigured("http_401"),
                new AiFailure.NotConfigured("http_402"), new AiFailure.NotConfigured("http_404"));
        assertThat(failures.subList(10, 12)).containsExactly(new AiFailure.Refusal("http_400"), new AiFailure.Refusal("http_422"));
        // a redirect is never followed: it is a configuration error and exactly one request was made
        assertThat(failures.get(12)).isEqualTo(new AiFailure.NotConfigured("http_302"));
        assertThat(provider.requests()).hasSize(13);
    }

    @Test
    void anOldHttpDateRetryAfterIsZero() {
        provider.enqueue(Reply.status(429, "Retry-After", "Wed, 21 Oct 2015 07:28:00 GMT"));
        assertThat(failure(deepseek().attempt("deepseek-flash", AiTestSupport.request(), BUDGET)))
                .isEqualTo(new AiFailure.RateLimited(Duration.ZERO));
    }

    @Test
    void aStreamIsReadLineByLineIgnoringKeepAliveCommentsAndEndsAtDone() throws Exception {
        provider.enqueue(Reply.sse("stream-ok.sse"));
        List<String> deltas = new ArrayList<>();
        TextResponse response = ok(deepseek().attempt("deepseek-flash", AiTestSupport.request(deltas::add), BUDGET));

        assertThat(deltas).containsExactly("# Заго", "ловок\n\nТекст.");
        assertThat(response.text()).isEqualTo("# Заголовок\n\nТекст.");
        assertThat(response.finishReason()).isEqualTo(TextResponse.FinishReason.STOP);
        assertThat(response.usage()).isEqualTo(new Usage(2500, 2000, 500, 300));
        assertThat(response.costMicros()).isEqualTo(522);
        assertThat(response.providerRequestId()).isEqualTo("chatcmpl-s1");
        JsonNode body = JSON.readTree(provider.requests().get(0).body());
        assertThat(body.path("stream").booleanValue()).isTrue();
        assertThat(body.path("stream_options").path("include_usage").booleanValue()).isTrue();
        assertThat(provider.requests().get(0).headers()).containsEntry("accept", "text/event-stream");
    }

    @Test
    void brokenStreamsAreClassified() {
        provider.enqueue(Reply.sse("stream-truncated.sse"), Reply.sse("stream-error-chunk.sse"),
                Reply.sse("stream-malformed.sse"), Reply.sse("stream-content-filter.sse"), Reply.sse("stream-empty.sse"));
        OpenAiCompatibleAdapter adapter = deepseek();
        List<AiFailure> failures = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            failures.add(failure(adapter.attempt("deepseek-flash", AiTestSupport.request(text -> { }), BUDGET)));
        }
        assertThat(failures).containsExactly(new AiFailure.Transient("stream_truncated"),
                new AiFailure.Transient("provider_error"), new AiFailure.InvalidOutput("malformed_chunk"),
                new AiFailure.Refusal("content_filter"), new AiFailure.InvalidOutput("empty_content"));
    }

    @Test
    void aStreamWithoutUsageIsEstimated() {
        provider.enqueue(Reply.sse("stream-no-usage.sse"));
        TextResponse response = ok(deepseek().attempt("deepseek-flash", AiTestSupport.request(text -> { }), BUDGET));
        assertThat(response.usage().completionTokens()).isEqualTo(TokenCounter.estimate("Ответ без usage."));
        assertThat(response.providerRequestId()).isEqualTo("chatcmpl-s7");
    }

    @Test
    void theOverallDeadlineTheIdleLimitAndAZeroBudgetAreTimeouts() {
        provider.enqueue(Reply.fixtureJson("chat-ok.json").delayed(1_500),
                Reply.sse("stream-ok.sse").gap(900),
                Reply.sse("stream-ok.sse").gap(300));
        OpenAiCompatibleAdapter adapter = deepseek();

        // headers never arrive within the budget
        assertThat(failure(adapter.attempt("deepseek-flash", AiTestSupport.request(), Duration.ofMillis(300))))
                .isEqualTo(new AiFailure.Timeout());
        // the stream goes silent for longer than the idle limit (400 ms in tests)
        assertThat(failure(adapter.attempt("deepseek-flash", AiTestSupport.request(text -> { }), BUDGET)))
                .isEqualTo(new AiFailure.Timeout());
        // the stream keeps talking, but the overall deadline passes
        assertThat(failure(adapter.attempt("deepseek-flash", AiTestSupport.request(text -> { }), Duration.ofMillis(700))))
                .isEqualTo(new AiFailure.Timeout());
        assertThat(failure(adapter.attempt("deepseek-flash", AiTestSupport.request(), Duration.ZERO)))
                .isEqualTo(new AiFailure.Timeout());
    }

    @Test
    void anUnreachableProviderIsTransientAndAnOversizedBodyIsInvalid() {
        FakeProvider closed = FakeProvider.start();
        String dead = closed.baseUrl();
        closed.close();
        var unreachable = new OpenAiCompatibleAdapter("deepseek", OpenAiCompatibleAdapter.Dialect.DEEPSEEK, URI.create(dead),
                BearerSource.staticKey("k"), http, PRICES, Clock.systemUTC());
        assertThat(failure(unreachable.attempt("deepseek-flash", AiTestSupport.request(), BUDGET)))
                .isEqualTo(new AiFailure.Transient("io_error"));

        try (var small = new ChatHttp(new AiProperties.Transport(Duration.ofSeconds(2), Duration.ofSeconds(1), 1_024, Duration.ofSeconds(1)))) {
            provider.enqueue(Reply.json(200, "x".repeat(4_000)));
            var adapter = new OpenAiCompatibleAdapter("deepseek", OpenAiCompatibleAdapter.Dialect.DEEPSEEK,
                    URI.create(provider.baseUrl()), BearerSource.staticKey("k"), small, PRICES, Clock.systemUTC());
            assertThat(failure(adapter.attempt("deepseek-flash", AiTestSupport.request(), BUDGET)))
                    .isEqualTo(new AiFailure.InvalidOutput("body_too_large"));
        }
    }

    @Test
    void withoutACredentialTheAdapterIsNotConfiguredAndNeverCallsOut() {
        OpenAiCompatibleAdapter adapter = adapter(OpenAiCompatibleAdapter.Dialect.DEEPSEEK, "deepseek", "");
        assertThat(adapter.configured()).isFalse();
        assertThat(failure(adapter.attempt("deepseek-flash", AiTestSupport.request(), BUDGET)))
                .isEqualTo(new AiFailure.NotConfigured("no_key"));
        assertThat(provider.requests()).isEmpty();
    }

    @Test
    void gigaChatAndOpenRouterUseTheirOwnFieldsAndUsageShapes() throws Exception {
        provider.enqueue(Reply.fixtureJson("gigachat-chat-ok.json"), Reply.fixtureJson("openrouter-chat-ok.json"));

        TextResponse giga = ok(adapter(OpenAiCompatibleAdapter.Dialect.GIGACHAT, "gigachat", "giga").attempt("GigaChat-2",
                AiTestSupport.request(), BUDGET));
        assertThat(giga.usage()).isEqualTo(new Usage(1000, 600, 400, 50));
        assertThat(giga.costMicros()).isEqualTo(Math.ceilDiv(1050L * 765_000, 1_000_000));
        JsonNode gigaBody = JSON.readTree(provider.requests().get(0).body());
        assertThat(gigaBody.has("thinking")).isFalse();
        assertThat(gigaBody.has("user_id")).isFalse();

        TextResponse router = ok(adapter(OpenAiCompatibleAdapter.Dialect.OPENROUTER, "openrouter", "or").attempt(
                "deepseek/deepseek-flash", AiTestSupport.request(), BUDGET));
        assertThat(router.usage()).isEqualTo(new Usage(800, 300, 500, 40));
        assertThat(router.costMicros()).isEqualTo(123);
        JsonNode routerBody = JSON.readTree(provider.requests().get(1).body());
        assertThat(routerBody.path("user").stringValue()).isEqualTo(AiTestSupport.USER_KEY);
        assertThat(routerBody.path("provider").path("data_collection").stringValue()).isEqualTo("deny");
        assertThat(routerBody.path("usage").path("include").booleanValue()).isTrue();
    }

    @Test
    void aSilentProviderTimesOutAtFirstByteWithDeadlineLeft() {
        provider.enqueue(Reply.fixtureJson("chat-ok.json").delayed(1_200));
        long started = System.nanoTime();
        // 5 s of budget, 300 ms first-byte limit (tests): the attempt ends long before the budget does
        assertThat(failure(deepseek().attempt("deepseek-flash", AiTestSupport.request(), BUDGET))).isEqualTo(new AiFailure.Timeout());
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
    }

    @Test
    void gigaChatFinishReasonsAreMappedAndAStreamedRefusalIsARefusal() {
        provider.enqueue(Reply.fixtureJson("chat-blacklist.json"), Reply.fixtureJson("chat-finish-error.json"), Reply.sse("stream-refusal.sse"));
        OpenAiCompatibleAdapter adapter = adapter(OpenAiCompatibleAdapter.Dialect.GIGACHAT, "gigachat", "giga");
        assertThat(failure(adapter.attempt("GigaChat-2", AiTestSupport.request(), BUDGET))).isEqualTo(new AiFailure.Refusal("blacklist"));
        assertThat(failure(adapter.attempt("GigaChat-2", AiTestSupport.request(), BUDGET))).isEqualTo(new AiFailure.Transient("finish_error"));
        assertThat(failure(adapter.attempt("GigaChat-2", AiTestSupport.request(text -> { }), BUDGET))).isEqualTo(new AiFailure.Refusal("refusal"));
    }

    @Test
    void onlyPlainProviderRequestIdsAreKept() {
        provider.enqueue(Reply.fixtureJson("chat-unsafe-id.json"), Reply.json(200, FakeProvider.fixture("chat-ok.json").replace(
                "chatcmpl-5f1c0e2b", "x".repeat(201))), Reply.json(200, FakeProvider.fixture("chat-unsafe-id.json"))
                .withHeader("x-request-id", "hdr-123.ABC:4"));
        OpenAiCompatibleAdapter adapter = deepseek();
        assertThat(ok(adapter.attempt("deepseek-flash", AiTestSupport.request(), BUDGET)).providerRequestId()).isNull();
        assertThat(ok(adapter.attempt("deepseek-flash", AiTestSupport.request(), BUDGET)).providerRequestId()).as("over 200 characters").isNull();
        assertThat(ok(adapter.attempt("deepseek-flash", AiTestSupport.request(), BUDGET)).providerRequestId())
                .as("an unsafe body id falls back to a safe header id").isEqualTo("hdr-123.ABC:4");
    }

    @Test
    void aRejectedCredentialInvalidatesTheCachedToken() {
        provider.enqueue(Reply.status(401));
        var invalidated = new java.util.concurrent.atomic.AtomicInteger();
        BearerSource source = new BearerSource() {
            @Override public boolean configured() { return true; }

            @Override public AiResult<String> bearer(Duration budget) { return AiResult.ok("t"); }

            @Override public void invalidate() { invalidated.incrementAndGet(); }
        };
        var adapter = new OpenAiCompatibleAdapter("gigachat", OpenAiCompatibleAdapter.Dialect.GIGACHAT, URI.create(provider.baseUrl()),
                source, http, PRICES, Clock.systemUTC());
        assertThat(failure(adapter.attempt("GigaChat-2", AiTestSupport.request(), BUDGET))).isEqualTo(new AiFailure.NotConfigured("http_401"));
        assertThat(invalidated).hasValue(1);
    }
}
