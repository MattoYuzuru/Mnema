package app.mnema.learning.library;

import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.library.LibraryFixtures.Deck;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.study.session.StudySessionService;
import app.mnema.learning.support.HttpIdentityFixture;
import app.mnema.learning.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * When Identity itself answers 429 to the by-username lookup (its answer has no {@code Retry-After} of use: the fixture says 7), the whole in-process budget is
 * treated as spent until the end of the current 15-minute window, and the caller is told THAT wait, not Identity's guess and not a constant. A class of its own
 * (own property set, so own context and own budget): the budget stays spent for the window, which would break any other test sharing it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProfileLookupIdentityRefusalHttpIntegrationTest extends PostgresIntegrationTest {
    @LocalServerPort private int port;
    @Autowired private DeckPublications publications;
    @Autowired private DeckService decks;
    @Autowired private ItemService items;
    @Autowired private ExerciseService exercises;
    @Autowired private StudySessionService sessions;
    @Autowired private MediaCatalog media;
    @Autowired private JdbcClient jdbc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        HttpIdentityFixture.register(registry);
        registry.add("learning.community.public-routes.enabled", () -> true);
        registry.add("learning.community.public-routes.guest-per-minute", () -> 100_000);
        registry.add("learning.community.public-routes.account-per-minute", () -> 100_000);
        registry.add("learning.community.profile-lookups.instance-per-window", () -> 7);
        registry.add("learning.community.profile-lookups.complaints-per-window", () -> 3);
        registry.add("learning.community.profile-lookups.owner-per-window", () -> 7);
    }

    private HttpResponse<String> post(String path, UUID account, String body) {
        return HttpIdentityFixture.send(port, "POST", path, HttpIdentityFixture.reader(account), body, Map.of());
    }

    private static long retryAfter(HttpResponse<String> response) {
        return Long.parseLong(response.headers().firstValue("retry-after").orElseThrow());
    }

    @Test
    void identityAnswering429SpendsTheBudgetUntilTheEndOfTheWindowForGrantsAndComplaintsAlike() {
        LibraryFixtures fixtures = new LibraryFixtures(decks, items, exercises, sessions, media, jdbc, publications);
        UUID owner = UUID.randomUUID();
        Deck deck = fixtures.deck(owner, "Лимит");
        fixtures.publishAt(deck, DeckVisibility.INVITE);
        String known = "known-" + UUID.randomUUID().toString().substring(0, 8);
        HttpIdentityFixture.publicLogin(known, UUID.randomUUID());
        assertThat(post("/decks/" + deck.id() + "/grants", owner, "{\"username\":\"" + known + "\"}").statusCode()).as("the budget works before").isEqualTo(200);

        int before = HttpIdentityFixture.profileLookups();
        HttpResponse<String> refused = post("/decks/" + deck.id() + "/grants", owner, "{\"username\":\"" + HttpIdentityFixture.LIMITED_LOGIN + "\"}");
        assertThat(HttpIdentityFixture.profileLookups() - before).as("Identity was asked once").isEqualTo(1);
        assertThat(refused.statusCode()).isEqualTo(429);
        assertThat(refused.body()).contains("\"code\":\"RATE_LIMITED\"");
        long wait = retryAfter(refused);
        assertThat(wait).as("the rest of Identity's 15-minute window, not Identity's 7 and not a made-up 30").isBetween(800L, 900L);
        assertThat(refused.body()).contains("\"retryAfter\":" + wait);

        // from now on nobody asks Identity until the window ends: another owner, a known login, a complaint
        UUID other = UUID.randomUUID();
        Deck otherDeck = fixtures.deck(other, "Другая");
        fixtures.publishAt(otherDeck, DeckVisibility.INVITE);
        HttpResponse<String> grant = post("/decks/" + otherDeck.id() + "/grants", other, "{\"username\":\"" + known + "\"}");
        assertThat(grant.statusCode()).isEqualTo(429);
        assertThat(retryAfter(grant)).isBetween(800L, wait);
        HttpResponse<String> complaint = post("/public/profiles/" + known + "/complaints", UUID.randomUUID(), "{\"reason\":\"SPAM\"}");
        assertThat(complaint.statusCode()).isEqualTo(429);
        assertThat(retryAfter(complaint)).isBetween(800L, wait);
        assertThat(HttpIdentityFixture.profileLookups() - before).as("no further call reached Identity").isEqualTo(1);
    }
}
