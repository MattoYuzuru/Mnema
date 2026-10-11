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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The kill switch of the public media route (Share/9): {@code learning.community.public-media.enabled} is off by default, so the route answers 404 as if it did not
 * exist while the other public routes of the same deck keep working; nothing is read or counted against a limit. The media route also needs the public routes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PublicMediaDisabledHttpIntegrationTest extends PostgresIntegrationTest {
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

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        HttpIdentityFixture.register(registry);
        // the public routes are on, the media switch is left at its default (off)
        registry.add("learning.community.public-routes.enabled", () -> true);
        registry.add("learning.media.upload.endpoint", () -> "http://127.0.0.1:9");
        registry.add("learning.media.upload.allow-loopback-http", () -> true);
        registry.add("learning.media.upload.bucket", () -> "shared-media-test");
        registry.add("learning.media.upload.access-key", () -> "AKIATESTONLY");
        registry.add("learning.media.upload.secret-key", () -> "test-only-signing-material");
    }

    @Test
    void theMediaRouteIsDisabledByDefaultWhileTheReadsOfTheSameDeckWork() {
        SharedMediaWorlds worlds = new SharedMediaWorlds(UUID.randomUUID(), publication, deckPublications, decks, items, exercises, sessions, media, storage, jdbc,
                transactions);
        World world = worlds.world("PUBLIC", null);
        double before = disabled();
        HttpResponse<String> response = HttpIdentityFixture.send(port, "GET", "/public/decks/" + world.code() + "/media/" + world.image(), null, null, Map.of());
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).doesNotContain("X-Amz").doesNotContain("variant/");
        // the same 404 for a real and an unknown asset, and for any viewer
        HttpResponse<String> unknown = HttpIdentityFixture.send(port, "GET", "/public/decks/" + world.code() + "/media/" + UUID.randomUUID(),
                HttpIdentityFixture.reader(UUID.randomUUID()), null, Map.of());
        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(disabled()).isEqualTo(before + 2);
        assertThat(HttpIdentityFixture.send(port, "GET", "/public/decks/" + world.code(), null, null, Map.of()).statusCode()).isEqualTo(200);
    }

    private double disabled() {
        var counter = meters.find("mnema_public_media_requests_total").tag("outcome", "disabled").counter();
        return counter == null ? 0 : counter.count();
    }
}
