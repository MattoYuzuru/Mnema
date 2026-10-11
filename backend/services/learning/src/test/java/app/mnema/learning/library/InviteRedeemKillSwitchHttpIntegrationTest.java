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
 * {@code learning.community.public-routes.enabled} off (its default) also switches off {@code POST /api/invites:redeem}: the same 404 as a route that does not
 * exist, decided before the body is read (so a malformed body is the same 404), with the invitation untouched. The tests of the redemption itself run with the switch on
 * ({@code DeckAccessHttpIntegrationTest}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InviteRedeemKillSwitchHttpIntegrationTest extends PostgresIntegrationTest {
    @LocalServerPort private int port;
    @Autowired private DeckPublications publications;
    @Autowired private DeckService decks;
    @Autowired private ItemService items;
    @Autowired private ExerciseService exercises;
    @Autowired private StudySessionService sessions;
    @Autowired private MediaCatalog media;
    @Autowired private JdbcClient jdbc;
    @Autowired private InviteService invites;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        HttpIdentityFixture.register(registry);
        // learning.community.public-routes.enabled is deliberately left at its default (false)
    }

    private HttpResponse<String> redeem(UUID account, String body) {
        return HttpIdentityFixture.send(port, "POST", "/invites:redeem", account == null ? null : HttpIdentityFixture.reader(account), body, Map.of());
    }

    @Test
    void redeemAnswersNotFoundBeforeTheBodyIsReadAndTheInvitationIsUntouched() {
        LibraryFixtures fixtures = new LibraryFixtures(decks, items, exercises, sessions, media, jdbc, publications);
        UUID owner = UUID.randomUUID();
        Deck deck = fixtures.deck(owner, "Выключатель");
        String code = fixtures.publishAt(deck, DeckVisibility.INVITE);
        String token = invites.create(owner, deck.id(), null, null).token();
        UUID stranger = UUID.randomUUID();

        for (String body : new String[] {"{\"token\":\"" + token + "\"}", "{not json", "{}", "", "{\"token\":1,\"token\":2}"}) {
            HttpResponse<String> response = redeem(stranger, body);
            assertThat(response.statusCode()).as(body).isEqualTo(404);
            assertThat(response.body()).as(body).contains("RESOURCE_NOT_FOUND").doesNotContain(token);
        }
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.deck_access_grant WHERE deck_id = :deck").param("deck", deck.id()).query(Long.class).single()).isZero();
        // authentication still comes first
        assertThat(redeem(null, "{}").statusCode()).isEqualTo(401);
        // the other two routes of the controller are off with the same switch (their service checks it)
        assertThat(HttpIdentityFixture.send(port, "POST", "/public/decks/" + code + "/access-requests", HttpIdentityFixture.reader(stranger), null, Map.of()).statusCode()).isEqualTo(404);
    }
}
