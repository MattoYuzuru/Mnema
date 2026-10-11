package app.mnema.learning.library;

import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.media.MediaManifestCatalog;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.storage.ImmutableStorage;
import app.mnema.learning.study.session.StudySessionService;
import app.mnema.learning.support.HttpIdentityFixture;
import app.mnema.learning.support.MediaFixtures;
import app.mnema.learning.support.PostgresIntegrationTest;
import app.mnema.learning.support.SharedScopeFixture;
import app.mnema.learning.support.SharedScopeFixture.Copy;
import app.mnema.learning.support.StudyFixtures;
import app.mnema.learning.support.TakedownFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Share/15 (#437): the media of a taken-down material. The public media route, the copy owner's private routes and the offline manifest all stop serving an
 * asset that a taken-down revision references, a copy whose learner edited the material (a new revision that keeps the inherited image) included.
 * The block is per asset and lineage, so a trivial edit that keeps the disputed image does not bring it back; the owner of the upload does not play it either.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ContentTakedownMediaIntegrationTest extends PostgresIntegrationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort private int port;
    @Autowired private PublicationService publication;
    @Autowired private DeckPublications deckPublications;
    @Autowired private DeckService decks;
    @Autowired private ItemService items;
    @Autowired private ExerciseService exercises;
    @Autowired private StudySessionService sessions;
    @Autowired private MediaCatalog media;
    @Autowired private MediaManifestCatalog manifests;
    @Autowired private ImmutableStorage storage;
    @Autowired private JdbcClient jdbc;
    @Autowired private PlatformTransactionManager transactions;
    private SharedMediaWorlds worlds;
    private StudyFixtures study;
    private SharedScopeFixture shared;
    private TakedownFixture takedown;

    private final UUID author = UUID.randomUUID();
    private final UUID viewer = UUID.randomUUID();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        HttpIdentityFixture.register(registry);
        registry.add("learning.community.public-routes.enabled", () -> true);
        registry.add("learning.community.public-routes.guest-per-minute", () -> 100_000);
        registry.add("learning.community.public-routes.coarse-per-minute", () -> 100_000);
        registry.add("learning.community.public-routes.account-per-minute", () -> 100_000);
        registry.add("learning.community.public-media.enabled", () -> true);
        registry.add("learning.community.public-media.guest-per-minute", () -> 100_000);
        registry.add("learning.community.public-media.deck-per-minute", () -> 1_000_000);
        registry.add("learning.media.upload.endpoint", () -> "http://127.0.0.1:9");
        registry.add("learning.media.upload.allow-loopback-http", () -> true);
        registry.add("learning.media.upload.bucket", () -> "shared-media-test");
        registry.add("learning.media.upload.access-key", () -> "AKIATESTONLY");
        registry.add("learning.media.upload.secret-key", () -> "test-only-signing-material");
    }

    @BeforeEach
    void fixtures() {
        worlds = new SharedMediaWorlds(author, publication, deckPublications, decks, items, exercises, sessions, media, storage, jdbc, transactions);
        shared = worlds.shared();
        study = worlds.study();
        takedown = new TakedownFixture(jdbc);
    }

    private HttpResponse<String> publicMedia(String code, UUID asset, UUID account) {
        return HttpIdentityFixture.send(port, "GET", "/public/decks/" + code + "/media/" + asset, account == null ? null : HttpIdentityFixture.reader(account), null, Map.of());
    }

    private boolean manifestMentions(UUID owner, UUID deck, UUID asset) {
        return manifests.current(owner, deck).body().contains(asset.toString());
    }

    @Test
    void aTakenDownMaterialsImageIsNotServedPubliclyNorToTheAuthorNorToAnyCopyOfTheLineageEvenOneThatKeptItInANewRevision() {
        SharedMediaWorlds.World world = worlds.world("PUBLIC", null);
        UUID member = world.material().member();
        UUID original = world.material().revision();
        Copy unchanged = shared.copy(world.deck(), UUID.randomUUID());
        Copy edited = shared.copy(world.deck(), UUID.randomUUID());
        UUID own = study.readyAsset(edited.owner(), "image/png");
        MediaFixtures.variants(jdbc, own, "image/webp");
        UUID second = MediaFixtures.saveImages(decks, items, edited.owner(), edited.deckId(), member, original, world.image(), own);
        assertThat(second).isNotEqualTo(original);
        Copy dropped = shared.copy(world.deck(), UUID.randomUUID());
        UUID withoutImage = MediaFixtures.saveImages(decks, items, dropped.owner(), dropped.deckId(), member, original);
        assertThat(withoutImage).isNotEqualTo(original);

        // before: everybody who may read plays it
        assertThat(publicMedia(world.code(), world.image(), viewer).statusCode()).isEqualTo(200);
        assertThat(publicMedia(world.code(), world.image(), null).statusCode()).isEqualTo(200);
        assertThat(media.playback(unchanged.owner(), world.image()).state()).isEqualTo("READY");
        assertThat(media.playback(edited.owner(), world.image()).state()).isEqualTo("READY");
        assertThat(manifestMentions(unchanged.owner(), unchanged.deckId(), world.image())).isTrue();

        takedown.item(world.deck(), member, original);

        // public: the same opaque 404 as an asset that is not in the deck, for an account and a guest
        for (UUID who : new UUID[] {viewer, null}) {
            HttpResponse<String> response = publicMedia(world.code(), world.image(), who);
            assertThat(response.statusCode()).isEqualTo(404);
            assertThat(JSON.readTree(response.body()).path("code").stringValue(null)).isEqualTo("RESOURCE_NOT_FOUND");
        }
        assertThat(publicMedia(world.code(), UUID.randomUUID(), viewer).statusCode()).isEqualTo(404);
        // the copy that still holds the revision: no playback, no variant, no place in the offline manifest
        assertThatThrownBy(() -> media.playback(unchanged.owner(), world.image())).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> media.resolve(unchanged.owner(), world.image(), world.imageVariants().playback())).isInstanceOf(ResourceNotFoundException.class);
        assertThat(manifestMentions(unchanged.owner(), unchanged.deckId(), world.image())).isFalse();
        // the copy whose learner saved a NEW revision that keeps the disputed image does not serve it either: the dispute is about the asset, in the whole lineage
        assertThat(takedown.scope(edited.deckId())).isEqualTo(takedown.scope(world.deck()));
        assertThatThrownBy(() -> media.playback(edited.owner(), world.image())).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> media.resolve(edited.owner(), world.image(), world.imageVariants().playback())).isInstanceOf(ResourceNotFoundException.class);
        assertThat(manifestMentions(edited.owner(), edited.deckId(), world.image())).isFalse();
        // ... while what the learner added himself is his and plays
        assertThat(media.playback(edited.owner(), own).state()).isEqualTo("READY");
        assertThat(manifestMentions(edited.owner(), edited.deckId(), own)).isTrue();
        // the author's own upload is blocked for him too: the dispute is about that content (the whole asset, in whichever deck it appears)
        assertThatThrownBy(() -> media.playback(author, world.image())).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> media.resolve(author, world.image(), null)).isInstanceOf(ResourceNotFoundException.class);
        // a copy whose learner dropped the image in his edit has nothing of it to serve
        assertThatThrownBy(() -> media.playback(dropped.owner(), world.image())).isInstanceOf(ResourceNotFoundException.class);
        assertThat(manifestMentions(dropped.owner(), dropped.deckId(), world.image())).isFalse();
        // an exercise sound of the same deck is not taken down
        assertThat(media.playback(unchanged.owner(), world.sound()).state()).isEqualTo("READY");
        assertThat(media.playback(author, world.sound()).state()).isEqualTo("READY");
    }

    @Test
    void aTakenDownExerciseHidesItsSoundFromTheCopyThatHoldsItAndNothingElse() {
        SharedMediaWorlds.World world = worlds.world("PUBLIC", null);
        Copy copy = shared.copy(world.deck(), UUID.randomUUID());
        assertThat(media.playback(copy.owner(), world.sound()).state()).isEqualTo("READY");
        assertThat(manifestMentions(copy.owner(), copy.deckId(), world.sound())).isTrue();
        UUID revision = jdbc.sql("SELECT revision_id FROM app_learning.deck_head_exercise WHERE deck_id = :deck AND exercise_id = :exercise")
                .param("deck", world.deck()).param("exercise", world.exercise()).query(UUID.class).single();
        takedown.exercise(world.deck(), world.exercise(), revision);
        assertThatThrownBy(() -> media.playback(copy.owner(), world.sound())).isInstanceOf(ResourceNotFoundException.class);
        assertThat(manifestMentions(copy.owner(), copy.deckId(), world.sound())).isFalse();
        assertThatThrownBy(() -> media.playback(author, world.sound())).isInstanceOf(ResourceNotFoundException.class);
        // the material's image is another unit and stays
        assertThat(media.playback(copy.owner(), world.image()).state()).isEqualTo("READY");
        assertThat(publicMedia(world.code(), world.image(), viewer).statusCode()).isEqualTo(200);
    }
}
