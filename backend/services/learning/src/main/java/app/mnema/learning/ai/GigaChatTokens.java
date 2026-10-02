package app.mnema.learning.ai;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * GigaChat OAuth: the authorization key is exchanged for an access token valid about 30 minutes; the token is cached
 * and renewed a minute before it expires. Request shape: {@code POST authUrl}, {@code Authorization: Basic <key>},
 * {@code RqUID: <uuid>}, form body {@code scope=...}; reply {@code {"access_token", "expires_at"}} with the expiry in
 * epoch milliseconds (Sber GigaChat REST reference). The exchange is covered by a recorded fixture only; it has not been
 * run against the live service.
 */
final class GigaChatTokens implements BearerSource {
    private static final Duration RENEW_BEFORE = Duration.ofSeconds(60);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final URI authUri;
    private final String authKey;
    private final String scope;
    private final ChatHttp http;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private String token;
    private Instant expiresAt = Instant.EPOCH;

    GigaChatTokens(URI authUri, String authKey, String scope, ChatHttp http, Clock clock) {
        this.authUri = authUri;
        this.authKey = authKey;
        this.scope = scope;
        this.http = http;
        this.clock = clock;
    }

    @Override
    public boolean configured() { return authUri != null && !authKey.isEmpty(); }

    @Override
    public void invalidate() {
        lock.lock();
        try {
            token = null;
            expiresAt = Instant.EPOCH;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public AiResult<String> bearer(Duration budget) {
        if (!configured()) return AiResult.failed(new AiFailure.NotConfigured("no_key"));
        lock.lock();
        try {
            return exchange(budget);
        } finally {
            lock.unlock();
        }
    }

    /** Runs under {@link #lock}: concurrent callers share one exchange instead of stampeding the auth server. */
    private AiResult<String> exchange(Duration budget) {
        if (token != null && clock.instant().isBefore(expiresAt.minus(RENEW_BEFORE))) return AiResult.ok(token);
        var request = HttpRequest.newBuilder(authUri)
                .header("Authorization", "Basic " + authKey)
                .header("RqUID", UUID.randomUUID().toString())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("scope=" + URLEncoder.encode(scope, StandardCharsets.UTF_8)));
        ChatHttp.Reply reply;
        try {
            reply = http.send(request, budget, null);
        } catch (ChatHttp.TransportException exception) {
            return AiResult.failed(exception.kind() == ChatHttp.TransportException.Kind.TIMEOUT
                    ? new AiFailure.Timeout() : new AiFailure.Transient("token_io"));
        }
        if (reply.status() == 401 || reply.status() == 403) return AiResult.failed(new AiFailure.NotConfigured("token_rejected"));
        if (reply.status() / 100 != 2) return AiResult.failed(new AiFailure.Transient("token_http_" + reply.status()));
        try {
            JsonNode body = JSON.readTree(reply.body());
            String accessToken = body.path("access_token").stringValue("");
            long expiresMillis = body.path("expires_at").longValue(0);
            if (accessToken.isEmpty() || expiresMillis <= 0) return AiResult.failed(new AiFailure.Transient("token_shape"));
            token = accessToken;
            expiresAt = Instant.ofEpochMilli(expiresMillis);
            return AiResult.ok(token);
        } catch (JacksonException exception) {
            return AiResult.failed(new AiFailure.Transient("token_shape"));
        }
    }
}
