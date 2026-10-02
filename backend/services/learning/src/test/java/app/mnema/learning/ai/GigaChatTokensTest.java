package app.mnema.learning.ai;

import app.mnema.learning.ai.FakeProvider.Reply;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The GigaChat OAuth exchange against a recorded fixture; it has not been run against the live service. */
class GigaChatTokensTest {
    private FakeProvider server;
    private ChatHttp http;
    private final AiTestSupport.MutableClock clock = new AiTestSupport.MutableClock(Instant.parse("2026-10-02T10:00:00Z"));

    @BeforeEach
    void start() {
        server = FakeProvider.start();
        http = new ChatHttp(AiTestSupport.properties("", AiTestSupport.routes(List.of(), List.of(), List.of()), Map.of()).transport());
    }

    @AfterEach
    void stop() {
        server.close();
        http.close();
    }

    private GigaChatTokens tokens(String authKey) {
        return new GigaChatTokens(URI.create(server.baseUrl() + "/api/v2/oauth"), authKey, "GIGACHAT_API_PERS", http, clock);
    }

    private static AiFailure failure(AiResult<String> result) { return ((AiResult.Failed<String>) result).failure(); }

    @Test
    void theAuthorizationKeyIsExchangedOnceAndTheTokenIsCachedUntilJustBeforeExpiry() {
        server.enqueue(Reply.fixtureJson("gigachat-token.json"));
        GigaChatTokens tokens = tokens("YXV0aC1rZXk=");

        assertThat(tokens.configured()).isTrue();
        assertThat(tokens.bearer(Duration.ofSeconds(5))).isEqualTo(AiResult.ok("giga-token-test-value"));
        assertThat(tokens.bearer(Duration.ofSeconds(5))).isEqualTo(AiResult.ok("giga-token-test-value"));
        assertThat(server.requests()).hasSize(1);
        FakeProvider.Recorded recorded = server.requests().get(0);
        assertThat(recorded.path()).isEqualTo("/api/v2/oauth");
        assertThat(recorded.headers()).containsEntry("authorization", "Basic YXV0aC1rZXk=")
                .containsEntry("content-type", "application/x-www-form-urlencoded");
        assertThat(recorded.headers().get("rquid")).matches("[0-9a-f-]{36}");
        assertThat(recorded.body()).isEqualTo("scope=GIGACHAT_API_PERS");

        // the fixture token expires far in the future; once it is within a minute of expiry a new one is fetched
        clock.advance(Duration.between(clock.instant(), Instant.ofEpochMilli(4102444800000L).minusSeconds(30)));
        server.enqueue(Reply.fixtureJson("gigachat-token.json"));
        assertThat(tokens.bearer(Duration.ofSeconds(5))).isEqualTo(AiResult.ok("giga-token-test-value"));
        assertThat(server.requests()).hasSize(2);
    }

    @Test
    void failuresAreClassifiedWithoutLeakingTheKey() {
        GigaChatTokens tokens = tokens("secret-auth-key");
        server.enqueue(Reply.status(401), Reply.status(403), Reply.status(500), Reply.json(200, "{\"access_token\":\"\"}"),
                Reply.json(200, "not json"), Reply.fixtureJson("gigachat-token.json").delayed(1_500));

        assertThat(failure(tokens.bearer(Duration.ofSeconds(5)))).isEqualTo(new AiFailure.NotConfigured("token_rejected"));
        assertThat(failure(tokens.bearer(Duration.ofSeconds(5)))).isEqualTo(new AiFailure.NotConfigured("token_rejected"));
        assertThat(failure(tokens.bearer(Duration.ofSeconds(5)))).isEqualTo(new AiFailure.Transient("token_http_500"));
        assertThat(failure(tokens.bearer(Duration.ofSeconds(5)))).isEqualTo(new AiFailure.Transient("token_shape"));
        assertThat(failure(tokens.bearer(Duration.ofSeconds(5)))).isEqualTo(new AiFailure.Transient("token_shape"));
        assertThat(failure(tokens.bearer(Duration.ofMillis(300)))).isEqualTo(new AiFailure.Timeout());
    }

    @Test
    void anUnreachableAuthServerIsTransientAndMissingConfigurationIsNotConfigured() {
        String dead = server.baseUrl();
        server.close();
        var unreachable = new GigaChatTokens(URI.create(dead + "/oauth"), "k", "GIGACHAT_API_PERS", http, clock);
        assertThat(failure(unreachable.bearer(Duration.ofSeconds(2)))).isEqualTo(new AiFailure.Transient("token_io"));

        assertThat(tokens("").configured()).isFalse();
        assertThat(failure(tokens("").bearer(Duration.ofSeconds(1)))).isEqualTo(new AiFailure.NotConfigured("no_key"));
        var noUri = new GigaChatTokens(null, "key", "GIGACHAT_API_PERS", http, Clock.systemUTC());
        assertThat(noUri.configured()).isFalse();
    }
}
