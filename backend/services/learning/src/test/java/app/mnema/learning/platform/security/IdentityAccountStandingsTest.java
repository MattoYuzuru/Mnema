package app.mnema.learning.platform.security;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** The caller's own standing from Identity: forwarded with the caller's bearer, cached for a minute when verified, and never assumed on any failure. */
class IdentityAccountStandingsTest {
    private final UUID account = UUID.randomUUID();
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String body;
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-02T09:00:00Z"));
    private HttpServer server;
    private IdentityHttp http;
    private IdentityAccountStandings standings;

    @BeforeEach
    void start() throws Exception {
        body = profile(account.toString(), "true", "false");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/accounts/me", exchange -> {
            calls.incrementAndGet();
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        http = new IdentityHttp(Duration.ofSeconds(2), 4);
        var endpoints = IdentityEndpoints.configured("https://identity.example", "http://127.0.0.1:" + server.getAddress().getPort(), true);
        Clock clock = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        standings = new IdentityAccountStandings(http, endpoints, clock);
    }

    @AfterEach
    void stop() {
        server.stop(0);
        http.close();
    }

    private static String profile(String accountId, String verified, String admin) {
        return "{\"accountId\":\"" + accountId + "\",\"email\":\"a@example.test\",\"emailVerified\":" + verified + ",\"admin\":" + admin + "}";
    }

    private Jwt token() {
        return Jwt.withTokenValue("bearer-token").header("alg", "RS256").subject(account.toString()).build();
    }

    @Test
    void readsTheStandingWithTheCallersOwnBearer() {
        body = profile(account.toString(), "true", "true");

        var standing = standings.of(token());

        assertThat(standing).contains(new AccountStandings.Standing(true, true));
        assertThat(authorization.get()).isEqualTo("Bearer bearer-token");
    }

    @Test
    void aVerifiedStandingIsCachedForAMinuteAndThenReadAgain() {
        standings.of(token());
        standings.of(token());
        assertThat(calls.get()).isEqualTo(1);

        now.set(Instant.parse("2026-10-02T09:00:59Z"));
        standings.of(token());
        assertThat(calls.get()).isEqualTo(1);

        now.set(Instant.parse("2026-10-02T09:01:01Z"));
        body = profile(account.toString(), "true", "false");
        standings.of(token());
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void anUnverifiedStandingIsNeverCachedSoAConfirmedEmailCountsAtOnce() {
        body = profile(account.toString(), "false", "false");
        assertThat(standings.of(token())).contains(new AccountStandings.Standing(false, false));

        body = profile(account.toString(), "true", "false");
        assertThat(standings.of(token())).contains(new AccountStandings.Standing(true, false));
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void anAdministratorLosesTheCachedGrantExactlyAtTheSixtySecondBoundary() {
        body = profile(account.toString(), "true", "true");
        assertThat(standings.of(token())).contains(new AccountStandings.Standing(true, true));
        body = profile(account.toString(), "true", "false");

        now.set(Instant.parse("2026-10-02T09:00:59Z"));
        assertThat(standings.of(token())).contains(new AccountStandings.Standing(true, true));
        assertThat(calls.get()).isOne();
        now.set(Instant.parse("2026-10-02T09:01:00Z"));
        assertThat(standings.of(token())).contains(new AccountStandings.Standing(true, false));
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void everyFailureIsEmptyNeverAssumed() {
        status = 401;
        assertThat(standings.of(token())).isEmpty();
        status = 500;
        assertThat(standings.of(token())).isEmpty();
        status = 200;
        for (String broken : new String[] {profile(UUID.randomUUID().toString(), "true", "true"), profile(account.toString(), "\"yes\"", "false"),
                profile(account.toString(), "true", "null"), "{\"emailVerified\":true,\"admin\":true}", "not json", "[]", ""}) {
            body = broken;
            assertThat(standings.of(token())).as(broken).isEmpty();
        }
        assertThat(standings.of(null)).isEmpty();
        assertThat(standings.of(Jwt.withTokenValue("t").header("alg", "RS256").claim("x", "y").build())).isEmpty();
    }

    @Test
    void aTransportFailureAndAMissingIdentityAreEmpty() {
        server.stop(0);
        assertThat(standings.of(token())).isEmpty();

        assertThat(new IdentityAccountStandings(http, new IdentityEndpoints("", null)).of(token())).isEmpty();
    }
}
