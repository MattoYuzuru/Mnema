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
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The complaint kill switch (Share/15): {@code learning.community.complaints.enabled=false} with the public routes on. Both filing routes answer 404 as if
 * they were absent, whatever is sent, and nothing is written; the reads of the deck are unaffected.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ComplaintDisabledHttpIntegrationTest extends PostgresIntegrationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

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
        registry.add("learning.community.complaints.enabled", () -> false);
    }

    @Test
    void switchedOffTheRoutesAreAbsentAndNothingIsWritten() throws Exception {
        LibraryFixtures fixtures = new LibraryFixtures(decks, items, exercises, sessions, media, jdbc, publications);
        UUID owner = UUID.randomUUID();
        UUID reporter = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        Deck deck = fixtures.deck(owner, "Жалобы выключены");
        String code = fixtures.publishAt(deck, DeckVisibility.PUBLIC);
        HttpIdentityFixture.publicLogin("switched-off-login", target);
        String bearer = HttpIdentityFixture.reader(reporter);
        int lookups = HttpIdentityFixture.profileLookups();
        for (String body : new String[] {"{\"reason\":\"SPAM\"}", "not json", "{}"}) {
            for (String path : new String[] {"/public/decks/" + code + "/complaints", "/public/decks/AAAAAAAAAA/complaints", "/public/profiles/switched-off-login/complaints"}) {
                HttpResponse<String> response = HttpIdentityFixture.send(port, "POST", path, bearer, body, Map.of());
                assertThat(response.statusCode()).as(path + " " + body).isEqualTo(404);
                assertThat(JSON.readTree(response.body()).path("code").stringValue(null)).isEqualTo("RESOURCE_NOT_FOUND");
            }
        }
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.content_complaint WHERE reporter_id = :r").param("r", reporter).query(Long.class).single()).isZero();
        assertThat(HttpIdentityFixture.profileLookups()).as("an absent route never asks Identity").isEqualTo(lookups);
        assertThat(HttpIdentityFixture.send(port, "GET", "/public/decks/" + code, bearer, null, Map.of()).statusCode()).isEqualTo(200);
    }
}
