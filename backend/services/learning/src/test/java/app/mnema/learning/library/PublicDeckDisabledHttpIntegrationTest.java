package app.mnema.learning.library;

import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.library.LibraryFixtures.Deck;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.study.session.StudySessionService;
import app.mnema.learning.support.HttpIdentityFixture;
import app.mnema.learning.support.PostgresIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The kill switch: the shipped default is off, and off means 404 as if the routes were absent, with a real published deck behind the code. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PublicDeckDisabledHttpIntegrationTest extends PostgresIntegrationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort private int port;
    @Autowired private Environment environment;
    @Autowired private DeckPublications publications;
    @Autowired private DeckService decks;
    @Autowired private ItemService items;
    @Autowired private ExerciseService exercises;
    @Autowired private StudySessionService sessions;
    @Autowired private MediaCatalog media;
    @Autowired private JdbcClient jdbc;
    @Autowired private MeterRegistry meters;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) { HttpIdentityFixture.register(registry); }

    @Test
    void theRoutesShipDisabledAndAnswerLikeAnUnknownCode() throws Exception {
        assertThat(environment.getProperty("learning.community.public-routes.enabled")).isEqualTo("false");
        LibraryFixtures fixtures = new LibraryFixtures(decks, items, exercises, sessions, media, jdbc, publications);
        UUID owner = UUID.randomUUID();
        Deck deck = fixtures.deck(owner, "Выключено");
        String code = fixtures.publishAt(deck, DeckVisibility.PUBLIC);
        for (String path : new String[] {"/public/decks/" + code, "/public/decks/" + code + "/items", "/public/decks/" + code + "/items/" + deck.material().member(),
                "/public/decks/" + code + "/exercises", "/public/decks/AAAAAAAAAA"}) {
            for (String bearer : new String[] {null, HttpIdentityFixture.reader(owner), HttpIdentityFixture.reader(UUID.randomUUID())}) {
                HttpResponse<String> response = HttpIdentityFixture.send(port, "GET", path, bearer, null, Map.of());
                assertThat(response.statusCode()).as(path).isEqualTo(404);
                assertThat(JSON.readTree(response.body()).path("code").stringValue(null)).isEqualTo("RESOURCE_NOT_FOUND");
                assertThat(response.body()).doesNotContain("Выключено");
            }
        }
        // a bad token is still refused before anything is looked at: the security chain does not know the switch
        assertThat(HttpIdentityFixture.send(port, "GET", "/public/decks/" + code, "garbage", null, Map.of()).statusCode()).isEqualTo(401);
        assertThat(meters.find("mnema_public_deck_requests_total").tags("route", "summary", "outcome", "disabled").counter().count()).isGreaterThanOrEqualTo(3);
        assertThat(meters.find("mnema_public_deck_requests_total").tags("route", "summary", "outcome", "ok").counter()).isNull();
    }
}
