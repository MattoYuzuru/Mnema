package app.mnema.learning.library;

import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.library.SharedMediaWorlds.World;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.storage.ImmutableStorage;
import app.mnema.learning.study.session.StudySessionService;
import app.mnema.learning.support.HttpIdentityFixture;
import app.mnema.learning.support.PostgresIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The abuse limits of the public media route on the real stack (Share/9): a guest per client network, all guests together per deck (a signed-in viewer is never
 * charged to the deck, so guests cannot starve accounts), 429 in the house schema with the real {@code Retry-After}, and every request counts, found or not.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PublicMediaRateLimitHttpIntegrationTest extends PostgresIntegrationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort private int port;
    @Autowired private PublicationService publication;
    @Autowired private DeckPublications deckPublications;
    @Autowired private DeckService decks;
    @Autowired private ItemService items;
    @Autowired private ExerciseService exercises;
    @Autowired private StudySessionService sessions;
    @Autowired private MediaCatalog media;
    @Autowired private ImmutableStorage storage;
    @Autowired private JdbcClient jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private MeterRegistry meters;
    private SharedMediaWorlds worlds;
    private World world;
    private World other;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        HttpIdentityFixture.register(registry);
        registry.add("learning.community.public-routes.enabled", () -> true);
        registry.add("learning.community.public-routes.guest-per-minute", () -> 100_000);
        registry.add("learning.community.public-routes.coarse-per-minute", () -> 100_000);
        registry.add("learning.community.public-routes.account-per-minute", () -> 100_000);
        registry.add("learning.community.public-media.enabled", () -> true);
        registry.add("learning.community.public-media.guest-per-minute", () -> 3);
        registry.add("learning.community.public-media.coarse-per-minute", () -> 5);
        registry.add("learning.community.public-media.deck-per-minute", () -> 8);
        registry.add("learning.media.upload.endpoint", () -> "http://127.0.0.1:9");
        registry.add("learning.media.upload.allow-loopback-http", () -> true);
        registry.add("learning.media.upload.bucket", () -> "shared-media-test");
        registry.add("learning.media.upload.access-key", () -> "AKIATESTONLY");
        registry.add("learning.media.upload.secret-key", () -> "test-only-signing-material");
    }

    @BeforeEach
    void worlds() {
        worlds = new SharedMediaWorlds(UUID.randomUUID(), publication, deckPublications, decks, items, exercises, sessions, media, storage, jdbc,
                transactions);
        world = worlds.world("PUBLIC", null);
        other = worlds.world("PUBLIC", null);
    }

    private HttpResponse<String> guest(String code, UUID asset, String network) {
        return HttpIdentityFixture.send(port, "GET", "/public/decks/" + code + "/media/" + asset, null, null, Map.of("X-Forwarded-For", network));
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
        assertThat(response.body()).doesNotContain("X-Amz").doesNotContain("variant/");
    }

    @Test
    void aGuestIsLimitedPerClientNetworkAndUnknownCodesAndAssetsCountToo() throws Exception {
        for (int index = 0; index < 3; index++) assertThat(guest(world.code(), world.image(), "203.0.113.50").statusCode()).isEqualTo(200);
        limited(guest(world.code(), world.image(), "203.0.113.50"));
        limited(guest(PublicCodes.next(new java.security.SecureRandom()), UUID.randomUUID(), "203.0.113.50"));
        // another network has its own window
        assertThat(guest(world.code(), world.image(), "203.0.113.51").statusCode()).isEqualTo(200);
        // misses cost as much as hits
        for (int index = 0; index < 3; index++) assertThat(guest(world.code(), UUID.randomUUID(), "203.0.113.52").statusCode()).isEqualTo(404);
        limited(guest(world.code(), world.image(), "203.0.113.52"));
        // the IPv6 /64 is one network
        World v6 = worlds.world("PUBLIC", null);
        assertThat(guest(v6.code(), v6.image(), "2001:db8:7:1::1").statusCode()).isEqualTo(200);
        assertThat(guest(v6.code(), v6.image(), "2001:db8:7:1:ffff::2").statusCode()).isEqualTo(200);
        assertThat(guest(v6.code(), v6.image(), "2001:db8:7:1::3").statusCode()).isEqualTo(200);
        limited(guest(v6.code(), v6.image(), "2001:db8:7:1::4"));
    }

    @Test
    void rotatingSlash64sInsideOneSlash48IsRefusedByTheCoarseBudgetOfTheMediaRoute() throws Exception {
        World coarse = worlds.world("PUBLIC", null);
        for (int network = 1; network <= 5; network++) assertThat(guest(coarse.code(), coarse.image(), "2001:db8:cc:" + network + "::1").statusCode()).isEqualTo(200);
        limited(guest(coarse.code(), coarse.image(), "2001:db8:cc:6::1"));
        limited(guest(coarse.code(), coarse.image(), "2001:db8:cc:ffff::1"));
        assertThat(guest(coarse.code(), coarse.image(), "2001:db8:dd:1::1").statusCode()).isEqualTo(200);
        assertThat(guest(coarse.code(), coarse.image(), "198.51.100.77").statusCode()).isEqualTo(200);
    }

    @Test
    void allGuestsOfOneDeckShareADeckBudgetThatNeverChargesASignedInViewer() throws Exception {
        // eight guests of eight networks use the deck's budget of eight; the ninth, from a fresh network, is refused
        for (int network = 1; network <= 8; network++) assertThat(guest(other.code(), other.image(), "198.51.100." + network).statusCode()).isEqualTo(200);
        limited(guest(other.code(), other.image(), "198.51.100.99"));
        // the other deck is untouched
        assertThat(guest(world.code(), world.image(), "198.51.100.99").statusCode()).isEqualTo(200);
        // a signed-in viewer is limited per account by the public chain, not by the guests' deck budget
        UUID account = UUID.randomUUID();
        for (int index = 0; index < 7; index++) {
            HttpResponse<String> response = HttpIdentityFixture.send(port, "GET", "/public/decks/" + other.code() + "/media/" + other.image(),
                    HttpIdentityFixture.reader(account), null, Map.of("X-Forwarded-For", "198.51.100.200"));
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        }
        var counter = meters.find("mnema_public_media_requests_total").tag("outcome", "rate_limited").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isPositive();
    }
}
