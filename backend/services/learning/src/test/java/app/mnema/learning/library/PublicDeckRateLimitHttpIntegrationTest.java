package app.mnema.learning.library;

import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.library.LibraryFixtures.Deck;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.study.session.StudySessionService;
import app.mnema.learning.support.HttpIdentityFixture;
import app.mnema.learning.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The abuse limit of the public routes on the real stack: a guest per client network (IPv6 as its /64), an account per account, 429 in the house schema. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PublicDeckRateLimitHttpIntegrationTest extends PostgresIntegrationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort private int port;
    @Autowired private DeckPublications publications;
    @Autowired private DeckService decks;
    @Autowired private ItemService items;
    @Autowired private ExerciseService exercises;
    @Autowired private StudySessionService sessions;
    @Autowired private MediaCatalog media;
    @Autowired private JdbcClient jdbc;
    private String code;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        HttpIdentityFixture.register(registry);
        registry.add("learning.community.public-routes.enabled", () -> true);
        registry.add("learning.community.public-routes.guest-per-minute", () -> 3);
        registry.add("learning.community.public-routes.account-per-minute", () -> 4);
        registry.add("learning.community.public-routes.coarse-per-minute", () -> 5);
    }

    @BeforeEach
    void deck() {
        LibraryFixtures fixtures = new LibraryFixtures(decks, items, exercises, sessions, media, jdbc, publications);
        Deck deck = fixtures.deck(UUID.randomUUID(), "Лимиты");
        code = fixtures.publishAt(deck, DeckVisibility.PUBLIC);
    }

    private HttpResponse<String> guest(String path, String forwardedFor) {
        return HttpIdentityFixture.send(port, "GET", path, null, null, Map.of("X-Forwarded-For", forwardedFor));
    }

    private static void limited(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(429);
        assertThat(response.headers().firstValue("content-type")).hasValueSatisfying(value -> assertThat(value).startsWith("application/problem+json"));
        assertThat(response.headers().firstValue("cache-control")).hasValueSatisfying(value -> assertThat(value).contains("no-store"));
        long retry = Long.parseLong(response.headers().firstValue("retry-after").orElseThrow());
        assertThat(retry).isBetween(1L, 60L);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.path("code").stringValue(null)).isEqualTo("RATE_LIMITED");
        assertThat(body.path("retryAfter").longValue()).isEqualTo(retry);
        assertThat(response.body()).doesNotContain("Лимиты");
    }

    @Test
    void aGuestIsLimitedPerClientNetworkAndUnknownCodesCountToo() throws Exception {
        String path = "/public/decks/" + code;
        for (int index = 0; index < 3; index++) assertThat(guest(path, "203.0.113.10").statusCode()).isEqualTo(200);
        limited(guest(path, "203.0.113.10"));
        limited(guest("/public/decks/" + PublicCodes.next(new java.security.SecureRandom()), "203.0.113.10"));
        assertThat(guest(path, "203.0.113.11").statusCode()).isEqualTo(200);
        // misses cost as much as hits
        for (int index = 0; index < 3; index++) {
            assertThat(guest("/public/decks/" + PublicCodes.next(new java.security.SecureRandom()), "203.0.113.12").statusCode()).isEqualTo(404);
        }
        limited(guest(path, "203.0.113.12"));
    }

    @Test
    void anIpv6SubscriberSharesOneBudgetAcrossItsWholeSlash64() throws Exception {
        String path = "/public/decks/" + code;
        assertThat(guest(path, "2001:db8:1:2::1").statusCode()).isEqualTo(200);
        assertThat(guest(path, "2001:db8:1:2:aaaa:bbbb:cccc:dddd").statusCode()).isEqualTo(200);
        assertThat(guest(path, "2001:db8:1:2::ffff").statusCode()).isEqualTo(200);
        limited(guest(path, "2001:db8:1:2:1:2:3:4"));
        assertThat(guest(path, "2001:db8:1:3::1").statusCode()).isEqualTo(200);
    }

    @Test
    void rotatingSlash64sInsideOneSlash48IsRefusedByTheCoarseBudget() throws Exception {
        String path = "/public/decks/" + code;
        for (int network = 1; network <= 5; network++) assertThat(guest(path, "2001:db8:bb:" + network + "::1").statusCode()).isEqualTo(200);
        limited(guest(path, "2001:db8:bb:6::1"));
        limited(guest(path, "2001:db8:bb:ffff::1"));
        assertThat(guest(path, "2001:db8:cc:1::1").statusCode()).isEqualTo(200);
        assertThat(guest(path, "198.51.100.99").statusCode()).isEqualTo(200);
    }

    @Test
    void aSignedInViewerIsLimitedPerAccountWhateverTheAddress() throws Exception {
        String path = "/public/decks/" + code;
        UUID account = UUID.randomUUID();
        String token = HttpIdentityFixture.reader(account);
        for (int index = 0; index < 4; index++) {
            assertThat(HttpIdentityFixture.send(port, "GET", path, token, null, Map.of("X-Forwarded-For", "198.51.100." + index)).statusCode()).isEqualTo(200);
        }
        limited(HttpIdentityFixture.send(port, "GET", path, token, null, Map.of("X-Forwarded-For", "198.51.100.200")));
        // other accounts and guests have their own budgets
        assertThat(HttpIdentityFixture.send(port, "GET", path, HttpIdentityFixture.reader(UUID.randomUUID()), null, Map.of("X-Forwarded-For", "198.51.100.0")).statusCode())
                .isEqualTo(200);
        assertThat(guest(path, "198.51.100.0").statusCode()).isEqualTo(200);
    }

    @Test
    void oneTokenCannotDriveUnlimitedIdentityRoundTrips() throws Exception {
        String path = "/public/decks/" + code;
        String token = HttpIdentityFixture.reader(UUID.randomUUID());
        int before = HttpIdentityFixture.userInfoCalls();
        int refused = 0;
        for (int index = 0; index < 12; index++) {
            HttpResponse<String> response = HttpIdentityFixture.send(port, "GET", path, token, null, Map.of());
            if (response.statusCode() == 429) {
                limited(response);
                refused++;
            } else {
                assertThat(response.statusCode()).isEqualTo(200);
            }
        }
        // the account limit (4) is applied before /userinfo is asked: the refused requests cost Identity nothing
        assertThat(refused).isEqualTo(8);
        assertThat(HttpIdentityFixture.userInfoCalls() - before).isEqualTo(4);
        // a token that is not valid is refused by the resource server before it reaches the limiter or Identity
        int calls = HttpIdentityFixture.userInfoCalls();
        for (int index = 0; index < 10; index++) {
            assertThat(HttpIdentityFixture.send(port, "GET", path, "garbage", null, Map.of()).statusCode()).isEqualTo(401);
        }
        assertThat(HttpIdentityFixture.userInfoCalls()).isEqualTo(calls);
    }

    @Test
    void anUntrustedForwardedHeaderCannotMintFreshBudgets() throws Exception {
        // the test peer is a trusted proxy, so the header counts here; a client address that is not a proxy gets its own budget either way.
        String path = "/public/decks/" + code;
        for (int index = 0; index < 3; index++) assertThat(guest(path, "192.0.2.77").statusCode()).isEqualTo(200);
        limited(guest(path, "192.0.2.77"));
        limited(guest(path, "10.1.1.1, 192.0.2.77"));
    }
}
