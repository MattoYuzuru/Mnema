package app.mnema.learning.platform.security;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

/** Author cards for link previews: a card or its absence is cached for a minute, anything else is neither, and no failure is ever thrown. */
class IdentityAuthorCardsTest {
    private final UUID account = UUID.randomUUID();
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>("none");
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-11T09:00:00Z"));
    private volatile int status = 200;
    private volatile long delayMillis;
    private volatile String body;
    private HttpServer server;
    private IdentityHttp http;
    private IdentityAuthorCards cards;

    @BeforeEach
    void start() throws Exception {
        body = card(account.toString(), "anna.k", "\"Анна\"", "\"Люблю языки\"");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/accounts/profiles/", exchange -> {
            calls.incrementAndGet();
            path.set(exchange.getRequestURI().getPath());
            authorization.set(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            if (delayMillis > 0) {
                try { Thread.sleep(delayMillis); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        http = new IdentityHttp(Duration.ofMillis(300), 4);
        var endpoints = IdentityEndpoints.configured("https://identity.example", "http://127.0.0.1:" + server.getAddress().getPort(), true);
        Clock clock = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        cards = new IdentityAuthorCards(http, endpoints, clock);
    }

    @AfterEach
    void stop() {
        server.stop(0);
        cards.close();
    }

    private static String card(String id, String username, String displayName, String bio) {
        return "{\"accountId\":\"" + id + "\",\"profileUsername\":\"" + username + "\",\"displayName\":" + displayName + ",\"bio\":" + bio + ",\"avatarPresent\":false}";
    }

    @Test
    void readsTheCardByAccountWithoutAnyCredential() {
        assertThat(cards.byAccount(account)).hasValueSatisfying(card -> {
            assertThat(card.username()).isEqualTo("anna.k");
            assertThat(card.displayName()).isEqualTo("Анна");
            assertThat(card.bio()).isEqualTo("Люблю языки");
        });
        assertThat(path.get()).isEqualTo("/api/accounts/profiles/" + account);
        assertThat(authorization.get()).isEqualTo("null");
    }

    @Test
    void readsTheCardByLoginCaseInsensitively() {
        assertThat(cards.byUsername("Anna.K")).hasValueSatisfying(card -> assertThat(card.username()).isEqualTo("anna.k"));
        assertThat(path.get()).isEqualTo("/api/accounts/profiles/by-username/Anna.K");
    }

    @Test
    void fieldsTheOwnerDidNotShowAreAbsent() {
        body = card(account.toString(), "anna.k", "null", "null");
        assertThat(cards.byAccount(account)).hasValueSatisfying(card -> {
            assertThat(card.displayName()).isNull();
            assertThat(card.bio()).isNull();
        });
    }

    @Test
    void aCardAndItsAbsenceAreCachedForAMinuteAndFailuresForFiveSeconds() {
        assertThat(cards.byAccount(account)).isPresent();
        assertThat(cards.byAccount(account)).isPresent();
        assertThat(calls.get()).isEqualTo(1);
        now.set(now.get().plusSeconds(IdentityAuthorCards.TTL_SECONDS + 1));
        assertThat(cards.byAccount(account)).isPresent();
        assertThat(calls.get()).isEqualTo(2);

        UUID unknown = UUID.randomUUID();
        status = 404;
        body = "{\"code\":\"profile_not_found\"}";
        assertThat(cards.byAccount(unknown)).isEmpty();
        assertThat(cards.byAccount(unknown)).isEmpty();
        assertThat(calls.get()).isEqualTo(3);

        UUID failing = UUID.randomUUID();
        status = 503;
        assertThat(cards.byAccount(failing)).isEmpty();
        assertThat(cards.byAccount(failing)).isEmpty();
        // a failure is remembered only for a few seconds: a struggling Identity is not asked again by every preview, and recovers quickly
        assertThat(calls.get()).isEqualTo(4);
        now.set(now.get().plusSeconds(IdentityAuthorCards.FAILURE_TTL_SECONDS + 1));
        status = 429;
        assertThat(cards.byAccount(failing)).isEmpty();
        assertThat(calls.get()).isEqualTo(5);
        now.set(now.get().plusSeconds(IdentityAuthorCards.FAILURE_TTL_SECONDS + 1));
        status = 200;
        body = card(failing.toString(), "bob_b", "null", "null");
        assertThat(cards.byAccount(failing)).isPresent();
    }

    @Test
    void aSlowIdentityIsLeftOutInsteadOfWaitedFor() {
        delayMillis = 900;
        long started = System.nanoTime();
        assertThat(cards.byAccount(account)).isEmpty();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(800));
        // asked again at once it is the remembered failure, not a second wait
        started = System.nanoTime();
        assertThat(cards.byAccount(account)).isEmpty();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(100));
        delayMillis = 0;
        now.set(now.get().plusSeconds(IdentityAuthorCards.FAILURE_TTL_SECONDS + 1));
        assertThat(cards.byAccount(account)).isPresent();
    }

    @Test
    void aCardForAnotherAccountOrWithAMalformedLoginIsRejected() {
        // every answer is a failure that is remembered for a few seconds, so each case starts after the previous one was forgotten
        for (String rejected : new String[] {card(UUID.randomUUID().toString(), "anna.k", "null", "null"), card(account.toString(), "a b", "null", "null"),
                card(account.toString(), "x<y>z", "null", "null"), "not json", "[]"}) {
            body = rejected;
            now.set(now.get().plusSeconds(IdentityAuthorCards.FAILURE_TTL_SECONDS + 1));
            assertThat(cards.byAccount(account)).as(rejected).isEmpty();
        }
        body = card(account.toString(), "other.login", "null", "null");
        assertThat(cards.byUsername("anna.k")).isEmpty();
    }

    @Test
    void aMalformedLoginNeverReachesTheNetwork() {
        for (String login : new String[] {null, "", "ab", "a/b/c", "../x", "a b c", "x".repeat(51), "имя-логин"}) assertThat(cards.byUsername(login)).isEmpty();
        assertThat(cards.byAccount(null)).isEmpty();
        assertThat(calls.get()).isZero();
    }

    @Test
    void textsAreMadeOneBoundedLine() {
        body = card(account.toString(), "anna.k", "\"  Анна\\n\\u0007  К.\\u202E\\u2066  \"", "\"" + "б".repeat(900) + "\"");
        assertThat(cards.byAccount(account)).hasValueSatisfying(card -> {
            assertThat(card.displayName()).isEqualTo("Анна К.");
            assertThat(card.bio()).hasSize(500);
        });
        body = card(account.toString(), "anna.k", "\"   \"", "null");
        now.set(now.get().plusSeconds(120));
        assertThat(cards.byAccount(account)).hasValueSatisfying(card -> assertThat(card.displayName()).isNull());
    }

    @Test
    void withoutAConfiguredIdentityThereIsNoCard() {
        var unconfigured = new IdentityAuthorCards(http, new IdentityEndpoints("", null), Clock.systemUTC());
        assertThat(unconfigured.byAccount(account)).isEmpty();
        assertThat(calls.get()).isZero();
    }
}
