package app.mnema.learning.fork;

import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.fork.ForkWorlds.Source;
import app.mnema.learning.library.ManifestPages;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.storage.ImmutableStorage;
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
import org.springframework.transaction.PlatformTransactionManager;

import java.net.http.HttpResponse;
import java.util.UUID;

import static app.mnema.learning.fork.ForkWorlds.json;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The kill switch «создание копий» ({@code learning.community.forks.enabled}, off by default): the creation route answers 404 as if it did not exist, for any body and
 * any source, before anything is read or written. The other routes of an existing copy are not affected by it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ForkDisabledHttpIntegrationTest extends PostgresIntegrationTest {
    @LocalServerPort private int port;
    @Autowired private DeckService decks;
    @Autowired private ItemService items;
    @Autowired private ExerciseService exercises;
    @Autowired private StudySessionService sessions;
    @Autowired private MediaCatalog media;
    @Autowired private ImmutableStorage storage;
    @Autowired private JdbcClient jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private ManifestPages manifests;
    @Autowired private ForkService forks;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        HttpIdentityFixture.register(registry);
        registry.add("learning.community.public-routes.enabled", () -> true);
    }

    @Test
    void creationAnswersNotFoundAsIfTheRouteDidNotExistAndWritesNothing() {
        ForkWorlds worlds = new ForkWorlds(port, decks, items, exercises, sessions, media, storage, jdbc, transactions, manifests);
        Source source = worlds.source("LINK", 2, 1);
        UUID reader = UUID.randomUUID();
        long decksBefore = jdbc.sql("SELECT count(*) FROM app_learning.deck").query(Long.class).single();
        long jobsBefore = jdbc.sql("SELECT count(*) FROM app_learning.learning_job WHERE queue = 'deck.copy.materialize'").query(Long.class).single();

        HttpResponse<String> response = worlds.fork(reader, source.code());
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(json(response).path("code").stringValue(null)).isEqualTo("RESOURCE_NOT_FOUND");
        // any body: the switch is checked before the body is read, so a malformed one is the same 404 (a route that does not exist does not parse)
        assertThat(worlds.send("POST", "/decks/forks", reader, "{not json", null).statusCode()).isEqualTo(404);
        assertThat(worlds.send("POST", "/decks/forks", reader, "{}", null).statusCode()).isEqualTo(404);
        // the owner of the source gets the same answer: nothing about the deck is decided
        assertThat(worlds.fork(source.author(), source.code()).statusCode()).isEqualTo(404);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.deck").query(Long.class).single()).isEqualTo(decksBefore);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.learning_job WHERE queue = 'deck.copy.materialize'").query(Long.class).single()).isEqualTo(jobsBefore);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.deck_fork").query(Long.class).single()).isZero();
        // authentication still comes first
        assertThat(HttpIdentityFixture.send(port, "POST", "/decks/forks", null, "{}", java.util.Map.of()).statusCode()).isEqualTo(401);
        // the service itself refuses too (no caller can bypass the switch)
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> forks.create(reader, new ForkCommand(UUID.randomUUID(), source.code())))
                .isInstanceOf(app.mnema.learning.platform.api.ResourceNotFoundException.class);
        // the summary of the public deck says nothing of copies for a viewer who has none
        assertThat(json(worlds.get("/public/decks/" + source.code(), reader)).path("myCopy").isNull()).isTrue();
    }
}
