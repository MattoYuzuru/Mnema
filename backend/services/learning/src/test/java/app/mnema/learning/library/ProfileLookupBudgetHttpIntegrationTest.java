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
 * Identity limits the by-username lookup to 60 per 15 minutes per caller address, so Learning has ONE in-process budget for the grants by login and the profile
 * complaints ({@code ProfileLookupBudget}). Here, on the real stack with small caps (instance 5 in a window; complaints at most 2 of them, one per reporter; an owner
 * at most 4): complaint traffic cannot starve the grants, a refusal is 429 with the real rest of the 15-minute window before Identity is asked, and a repeat of an
 * open complaint costs no lookup. Identity answering 429 itself is {@link ProfileLookupIdentityRefusalHttpIntegrationTest} (it needs a budget of its own).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProfileLookupBudgetHttpIntegrationTest extends PostgresIntegrationTest {
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
        registry.add("learning.community.profile-lookups.instance-per-window", () -> 5);
        registry.add("learning.community.profile-lookups.complaints-per-window", () -> 2);
        registry.add("learning.community.profile-lookups.owner-per-window", () -> 4);
        registry.add("learning.community.profile-lookups.reporter-per-window", () -> 1);
    }

    private HttpResponse<String> post(String path, UUID account, String body) {
        return HttpIdentityFixture.send(port, "POST", path, HttpIdentityFixture.reader(account), body, Map.of());
    }

    private String login(String prefix) {
        String login = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        HttpIdentityFixture.publicLogin(login, UUID.randomUUID());
        return login;
    }

    private static long retryAfter(HttpResponse<String> response) {
        return Long.parseLong(response.headers().firstValue("retry-after").orElseThrow());
    }

    @Test
    void complaintTrafficCannotStarveGrantsAndEveryRefusalIsTheRestOfTheWindow() {
        String body = "{\"reason\":\"SPAM\"}";
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        String firstLogin = login("c1");
        String secondLogin = login("c2");
        int before = HttpIdentityFixture.profileLookups();

        // two complaints of two reporters take the complaints' pool (2 of the 5 places)
        assertThat(post("/public/profiles/" + firstLogin + "/complaints", first, body).statusCode()).isEqualTo(202);
        assertThat(post("/public/profiles/" + secondLogin + "/complaints", second, body).statusCode()).isEqualTo(202);
        assertThat(HttpIdentityFixture.profileLookups() - before).isEqualTo(2);

        // a reporter has one lookup a window: his second login is 429 with the rest of the 15-minute window, and Identity is not asked
        HttpResponse<String> perReporter = post("/public/profiles/" + login("c3") + "/complaints", first, body);
        assertThat(perReporter.statusCode()).isEqualTo(429);
        assertThat(retryAfter(perReporter)).as("the rest of a 15-minute window, not a minute and not a guess").isBetween(800L, 900L);
        // a third reporter meets the complaints' pool (2), not the instance's total (5)
        HttpResponse<String> pool = post("/public/profiles/" + login("c4") + "/complaints", UUID.randomUUID(), body);
        assertThat(pool.statusCode()).isEqualTo(429);
        assertThat(retryAfter(pool)).isBetween(800L, 900L);
        assertThat(HttpIdentityFixture.profileLookups() - before).as("refused before Identity is asked").isEqualTo(2);

        // a repeat of an open complaint is answered before the budget and before Identity, even with the pool spent
        assertThat(post("/public/profiles/" + firstLogin + "/complaints", first, body).statusCode()).isEqualTo(202);
        assertThat(post("/public/profiles/" + secondLogin.toUpperCase(java.util.Locale.ROOT) + "/complaints", second, body).statusCode()).isEqualTo(202);
        assertThat(HttpIdentityFixture.profileLookups() - before).isEqualTo(2);

        // the grants keep the rest of the instance total: three lookups of an owner who may have four
        LibraryFixtures fixtures = new LibraryFixtures(decks, items, exercises, sessions, media, jdbc, publications);
        UUID owner = UUID.randomUUID();
        Deck deck = fixtures.deck(owner, "Бюджеты");
        fixtures.publishAt(deck, DeckVisibility.INVITE);
        for (int index = 0; index < 3; index++) {
            HttpResponse<String> granted = post("/decks/" + deck.id() + "/grants", owner, "{\"username\":\"" + login("g" + index) + "\"}");
            assertThat(granted.statusCode()).as(granted.body()).isEqualTo(200);
        }
        assertThat(HttpIdentityFixture.profileLookups() - before).isEqualTo(5);
        // the instance total of 5 is spent although the owner has one of his four left: 429 with the rest of the window, no Identity call
        HttpResponse<String> total = post("/decks/" + deck.id() + "/grants", owner, "{\"username\":\"" + login("g3") + "\"}");
        assertThat(total.statusCode()).isEqualTo(429);
        assertThat(retryAfter(total)).isBetween(800L, 900L);
        assertThat(HttpIdentityFixture.profileLookups() - before).isEqualTo(5);
        // an unrelated lookup-free call is not affected: reading the grants needs no budget
        assertThat(HttpIdentityFixture.send(port, "GET", "/decks/" + deck.id() + "/grants", HttpIdentityFixture.reader(owner), null, Map.of()).statusCode()).isEqualTo(200);
    }
}
