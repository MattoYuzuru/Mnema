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
 * Share/15 (#437): profile complaints have an Identity lookup budget of their own. Identity limits that public lookup per network, so complaint traffic
 * must never starve an owner granting access by login (Share/13), and a repeat of an open complaint is answered before any lookup.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ComplaintLookupBudgetHttpIntegrationTest extends PostgresIntegrationTest {
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
        // the two budgets are small and separate: three grant lookups, two complaint lookups an instance a minute
        registry.add("learning.community.grants.instance-lookups-per-minute", () -> 3);
        registry.add("learning.community.grants.owner-lookups-per-minute", () -> 1_000);
        registry.add("learning.community.complaints.profile-lookups-per-instance-per-minute", () -> 2);
        registry.add("learning.community.complaints.profile-lookups-per-reporter-per-minute", () -> 1_000);
    }

    private HttpResponse<String> post(String path, UUID account, String body) {
        return HttpIdentityFixture.send(port, "POST", path, HttpIdentityFixture.reader(account), body, Map.of());
    }

    private String login(String prefix) {
        String login = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        HttpIdentityFixture.publicLogin(login, UUID.randomUUID());
        return login;
    }

    @Test
    void complaintLookupsNeverSpendTheGrantBudgetAndARepeatCostsNoLookup() {
        UUID reporter = UUID.randomUUID();
        String first = login("c1");
        String second = login("c2");
        String third = login("c3");
        String body = "{\"reason\":\"SPAM\"}";
        int before = HttpIdentityFixture.profileLookups();
        assertThat(post("/public/profiles/" + first + "/complaints", reporter, body).statusCode()).isEqualTo(202);
        assertThat(post("/public/profiles/" + second + "/complaints", reporter, body).statusCode()).isEqualTo(202);
        assertThat(HttpIdentityFixture.profileLookups() - before).isEqualTo(2);
        // the complaints' own instance cap is spent: a new login is 429 before Identity is asked
        HttpResponse<String> limited = post("/public/profiles/" + third + "/complaints", reporter, body);
        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(limited.headers().firstValue("retry-after")).isPresent();
        assertThat(HttpIdentityFixture.profileLookups() - before).isEqualTo(2);
        // a repeat of an open complaint is answered before the budget and before Identity: it is the same 202 even with the cap spent, and costs no lookup
        assertThat(post("/public/profiles/" + first + "/complaints", reporter, body).statusCode()).isEqualTo(202);
        assertThat(post("/public/profiles/" + second.toUpperCase(java.util.Locale.ROOT) + "/complaints", reporter, body).statusCode()).isEqualTo(202);
        assertThat(HttpIdentityFixture.profileLookups() - before).isEqualTo(2);

        // the owner's grants by login still have their whole budget of three
        LibraryFixtures fixtures = new LibraryFixtures(decks, items, exercises, sessions, media, jdbc, publications);
        UUID owner = UUID.randomUUID();
        Deck deck = fixtures.deck(owner, "Бюджеты");
        fixtures.publishAt(deck, DeckVisibility.INVITE);
        for (int index = 0; index < 3; index++) {
            HttpResponse<String> granted = post("/decks/" + deck.id() + "/grants", owner, "{\"username\":\"" + login("g" + index) + "\"}");
            assertThat(granted.statusCode()).as(granted.body()).isEqualTo(200);
        }
        assertThat(post("/decks/" + deck.id() + "/grants", owner, "{\"username\":\"" + login("g3") + "\"}").statusCode()).as("the fourth is the grants' own cap").isEqualTo(429);
        // and the grants did not spend the complaints' budget either (it was already spent by the complaints, and refills with its own window)
        assertThat(post("/public/profiles/" + third + "/complaints", reporter, body).statusCode()).isEqualTo(429);
    }
}
