package app.mnema.learning.platform.security;

import app.mnema.learning.support.PostgresIntegrationTest;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real Learning HTTP/filter chain and PostgreSQL; Identity HTTP is a controlled protocol fixture. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.max-http-request-header-size=32KB")
@Import(LearningSecurityHttpIntegrationTest.Probe.class)
class LearningSecurityHttpIntegrationTest extends PostgresIntegrationTest {
    private static final String ISSUER = "https://identity.example.test";
    private static final String ACTOR = UUID.randomUUID().toString();
    private static final RSAKey KEY;
    private static final HttpServer IDENTITY;
    private static final HttpClient CLIENT = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private static final AtomicInteger CALLS = new AtomicInteger();
    private static final AtomicInteger OPERATIONS = new AtomicInteger();
    private static final AtomicReference<String> RELAYED = new AtomicReference<>();
    private static volatile int userInfoStatus;
    private static volatile int keyStatus = 200;
    private static volatile String userInfoBody;
    private static volatile CountDownLatch slowResponse;
    private static volatile CountDownLatch requestStarted;

    static {
        try {
            KEY = new RSAKeyGenerator(2048).keyID("learning-test").generate();
            IDENTITY = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            IDENTITY.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            IDENTITY.createContext("/oauth2/jwks", exchange -> {
                byte[] bytes = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(keyStatus, bytes.length);
                try (var body = exchange.getResponseBody()) { body.write(bytes); }
            });
            IDENTITY.createContext("/userinfo", exchange -> {
                CALLS.incrementAndGet();
                RELAYED.set(exchange.getRequestHeaders().getFirst("Authorization"));
                CountDownLatch release = slowResponse;
                CountDownLatch started = requestStarted;
                int status = userInfoStatus;
                byte[] bytes = userInfoBody.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.getResponseHeaders().set("Location", "/must-not-follow");
                exchange.sendResponseHeaders(status, bytes.length);
                if (started != null) started.countDown();
                try (var body = exchange.getResponseBody()) {
                    if (release != null) {
                        try { release.await(5, TimeUnit.SECONDS); }
                        catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
                    }
                    body.write(bytes);
                }
            });
            IDENTITY.start();
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @LocalServerPort
    int port;

    @DynamicPropertySource
    static void identityProperties(DynamicPropertyRegistry registry) {
        registry.add("learning.identity.issuer", () -> ISSUER);
        registry.add("learning.identity.transport-base", () -> "http://127.0.0.1:" + IDENTITY.getAddress().getPort());
        registry.add("learning.identity.allow-loopback-http", () -> true);
        registry.add("learning.identity.timeout", () -> "500ms");
        registry.add("learning.identity.max-concurrency", () -> 1);
    }

    @BeforeEach
    void activeIdentity() {
        keyStatus = 200;
        userInfoStatus = 200;
        userInfoBody = "{\"sub\":\"" + ACTOR + "\"}";
        slowResponse = null;
        requestStarted = null;
        CALLS.set(0);
        OPERATIONS.set(0);
        RELAYED.set(null);
    }

    @AfterAll
    static void stopFixture() {
        IDENTITY.stop(0);
        CLIENT.shutdownNow();
    }

    @Test
    void oversizedBearerReturnsTheStableAuthenticationProblem() throws Exception {
        // The larger test connector limit lets this reach the application's independent token limit.
        assertProblem(request("GET", "/_security", "a".repeat(16_385)), 401, "AUTHENTICATION_REQUIRED");
        assertThat(CALLS).hasValue(0);
        assertThat(OPERATIONS).hasValue(0);
    }

    @Test
    void coldKeyFetchFailureIsAnAuthenticationFailureWithoutPrivateWork() throws Exception {
        keyStatus = 503;
        var endpoints = IdentityEndpoints.configured(ISSUER,
                "http://127.0.0.1:" + IDENTITY.getAddress().getPort(), true);
        try (var http = new IdentityHttp(Duration.ofMillis(500), 1)) {
            var decoder = new LearningSecurityConfiguration().learningJwtDecoder(endpoints, http);
            String access = token("learning.read", claims -> { });
            assertThatThrownBy(() -> decoder.decode(access)).isInstanceOf(BadJwtException.class);
        } finally {
            keyStatus = 200;
        }
        assertThat(CALLS).hasValue(0);
        assertThat(OPERATIONS).hasValue(0);
    }

    @Test
    void acceptsHeaderTokenAndChecksCurrentIdentityOnEveryRequest() throws Exception {
        String token = token("learning.read learning.write", c -> { });
        assertThat(request("GET", "/_security", token).statusCode()).isEqualTo(200);
        assertThat(request("POST", "/_security", token).statusCode()).isEqualTo(200);
        assertThat(CALLS).hasValue(2);
        assertThat(RELAYED).hasValue("Bearer " + token);
        assertThat(OPERATIONS).hasValue(2);
        userInfoStatus = 401;
        assertProblem(request("POST", "/_security", token), 401, "AUTHENTICATION_REQUIRED");
        assertThat(CALLS).hasValue(3);
        assertThat(OPERATIONS).hasValue(2);
    }

    @Test
    void separatesLearningScopesAndDoesNotCallIdentityForMissingScope() throws Exception {
        assertProblem(request("GET", "/_security", token("account.read account.write", c -> { })), 403, "ACCESS_DENIED");
        assertProblem(request("POST", "/_security", token("learning.read", c -> { })), 403, "ACCESS_DENIED");
        assertProblem(request("GET", "/_security", token("learning.write", c -> { })), 403, "ACCESS_DENIED");
        assertThat(CALLS).hasValue(0);
        assertThat(OPERATIONS).hasValue(0);
    }

    @Test
    void rejectsMissingCookieAndQueryCredentialsWithoutCreatingSession() throws Exception {
        String token = token("learning.read", c -> { });
        var response = CLIENT.send(HttpRequest.newBuilder(uri("/_security?access_token=" + token))
                .header("Cookie", "access_token=" + token + "; JSESSIONID=not-a-session").build(),
                HttpResponse.BodyHandlers.ofString());
        assertProblem(response, 401, "AUTHENTICATION_REQUIRED");
        assertThat(response.headers().firstValue("set-cookie")).isEmpty();
        assertThat(response.headers().firstValue("www-authenticate")).contains("Bearer");
        assertThat(CALLS).hasValue(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"issuer", "audience", "expired", "future", "subject", "generation", "no-exp", "no-iat"})
    void rejectsInvalidClaimsBeforePrivateWork(String invalid) throws Exception {
        String token = token("learning.read", claims -> {
            switch (invalid) {
                case "issuer" -> claims.issuer("https://attacker.example");
                case "audience" -> claims.audience("other-api");
                case "expired" -> claims.expirationTime(Date.from(Instant.now().minusSeconds(90)));
                case "future" -> claims.notBeforeTime(Date.from(Instant.now().plusSeconds(120)));
                case "subject" -> claims.subject(ACTOR.toUpperCase());
                case "generation" -> claims.claim("generation", 0);
                case "no-exp" -> claims.expirationTime(null);
                case "no-iat" -> claims.issueTime(null);
                default -> throw new IllegalArgumentException(invalid);
            }
        });
        assertProblem(request("GET", "/_security", token), 401, "AUTHENTICATION_REQUIRED");
        assertThat(CALLS).hasValue(0);
        assertThat(OPERATIONS).hasValue(0);
    }

    @Test
    void rejectsIdTokensBadSignaturesAndMalformedBearer() throws Exception {
        var claims = claims("learning.read").build();
        var idToken = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID())
                .type(JOSEObjectType.JWT).build(), claims);
        idToken.sign(new RSASSASigner(KEY));
        var forged = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID())
                .type(new JOSEObjectType("at+jwt")).build(), claims);
        forged.sign(new RSASSASigner(new RSAKeyGenerator(2048).generate()));
        for (String value : new String[]{idToken.serialize(), forged.serialize(), "not.a.token"}) {
            assertProblem(request("GET", "/_security", value), 401, "AUTHENTICATION_REQUIRED");
        }
        assertThat(CALLS).hasValue(0);
        assertThat(OPERATIONS).hasValue(0);
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 302, 429, 500})
    void rejectsUnsuccessfulIdentityWithoutFollowingRedirects(int status) throws Exception {
        userInfoStatus = status;
        int expected = status == 401 || status == 403 ? 401 : 503;
        assertProblem(request("POST", "/_security", token("learning.write", c -> { })), expected,
                expected == 401 ? "AUTHENTICATION_REQUIRED" : "IDENTITY_UNAVAILABLE");
        assertThat(CALLS).hasValue(1);
        assertThat(OPERATIONS).hasValue(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "[]", "not json", "{\"sub\":\"other\"}", "{\"sub\":123}", "duplicate", "oversized"})
    void rejectsMalformedMismatchedOrOversizedIdentity(String body) throws Exception {
        userInfoBody = switch (body) {
            case "duplicate" -> "{\"sub\":\"" + ACTOR + "\",\"sub\":\"" + ACTOR + "\"}";
            case "oversized" -> "{\"sub\":\"" + ACTOR + "\",\"padding\":\"" + "x".repeat(17_000) + "\"}";
            default -> body;
        };
        assertProblem(request("POST", "/_security", token("learning.write", c -> { })), 503, "IDENTITY_UNAVAILABLE");
        assertThat(OPERATIONS).hasValue(0);
    }

    @Test
    void boundsStalledBodiesAndConcurrencyThenRecovers() throws Exception {
        String token = token("learning.write", c -> { });
        // Warm only the public JWKS cache; current-account results are never cached.
        assertThat(request("POST", "/_security", token).statusCode()).isEqualTo(200);
        OPERATIONS.set(0);
        slowResponse = new CountDownLatch(1);
        requestStarted = new CountDownLatch(1);
        var first = CLIENT.sendAsync(HttpRequest.newBuilder(uri("/_security")).header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(requestStarted.await(3, TimeUnit.SECONDS)).isTrue();
        try {
            assertProblem(request("POST", "/_security", token), 503, "IDENTITY_UNAVAILABLE");
            assertProblem(first.get(3, TimeUnit.SECONDS), 503, "IDENTITY_UNAVAILABLE");
            assertThat(OPERATIONS).hasValue(0);
        } finally {
            slowResponse.countDown();
            slowResponse = null;
            requestStarted = null;
        }
        assertThat(request("POST", "/_security", token).statusCode()).isEqualTo(200);
    }

    @Test
    void healthRemainsPublicAndAuthenticatedUnknownRoutesRemainMissing() throws Exception {
        assertThat(request("GET", "/actuator/health/liveness", "invalid-token").statusCode()).isEqualTo(200);
        assertThat(request("GET", "/actuator/health/readiness", null).statusCode()).isEqualTo(200);
        assertThat(request("GET", "/actuator/info", null).statusCode()).isEqualTo(200);
        assertThat(CALLS).hasValue(0);
        assertThat(request("GET", "/v2", token("learning.read", c -> { })).statusCode()).isEqualTo(404);
    }

    private HttpResponse<String> request(String method, String path, String bearer) throws Exception {
        var builder = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(5))
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (bearer != null) builder.header("Authorization", "Bearer " + bearer);
        return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + "/api" + path); }

    private static JWTClaimsSet.Builder claims(String scopes) {
        return new JWTClaimsSet.Builder().issuer(ISSUER).subject(ACTOR).audience("mnema-api")
                .issueTime(new Date()).expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .claim("generation", "0").claim("scope", scopes);
    }

    private static String token(String scopes, Consumer<JWTClaimsSet.Builder> change) throws Exception {
        var builder = claims(scopes);
        change.accept(builder);
        var token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID())
                .type(new JOSEObjectType("at+jwt")).build(), builder.build());
        token.sign(new RSASSASigner(KEY));
        return token.serialize();
    }

    private static void assertProblem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("content-type")).hasValueSatisfying(value ->
                assertThat(value).startsWith("application/problem+json"));
        assertThat(response.headers().firstValue("cache-control")).contains("no-store");
        assertThat(response.body()).contains("\"code\":\"" + code + "\"")
                .doesNotContain("Bearer ", "Exception", "java.", "access_token");
    }

    @RestController
    static class Probe {
        @GetMapping("/_security")
        Map<String, String> read(Authentication actor) { return execute(actor); }

        @PostMapping("/_security")
        Map<String, String> write(Authentication actor) { return execute(actor); }

        private Map<String, String> execute(Authentication actor) {
            OPERATIONS.incrementAndGet();
            return Map.of("actor", actor.getName());
        }
    }
}
