package app.mnema.learning.fork;

import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.library.ManifestPages;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.media.MediaManifestCatalog;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.storage.ImmutableStorage;
import app.mnema.learning.study.session.StudySessionService;
import app.mnema.learning.support.HttpIdentityFixture;
import app.mnema.learning.support.MediaFixtures;
import app.mnema.learning.support.MediaFixtures.Variants;
import app.mnema.learning.support.PostgresIntegrationTest;
import app.mnema.learning.support.SharedScopeFixture.Material;
import app.mnema.learning.support.StudyFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static app.mnema.learning.fork.ForkWorlds.json;
import static app.mnema.learning.support.StudyFixtures.audio;
import static app.mnema.learning.support.StudyFixtures.blocks;
import static app.mnema.learning.support.StudyFixtures.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Share/10 security acceptance (from the Share/9 review): the heads of a copy come ONLY from the published revision's manifest, never from the source's current
 * heads. The author publishes, then keeps working: a new material with a new image, a new revision of a published material with another image, a new exercise with a new
 * sound. A copy made after that must answer 404 for every one of those assets on playback, variant download and the offline manifest (the COPY grant is decided by the
 * heads of the copy), and must neither list nor read those revisions. The assets of the published revision stay playable, as a control.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"learning.runtime.roles=worker", "learning.jobs.sweep-interval=PT0.5S", "learning.ai.provider=stub",
                "learning.features.speech-to-text.enabled=true", "learning.speech.sweep-interval=PT5M",
                "learning.generation.worker.sweep-interval=PT5M", "learning.ai.assess.sweep-interval=PT15S"})
class ForkUnpublishedContentIntegrationTest extends PostgresIntegrationTest {
    @LocalServerPort private int port;
    @Autowired private DeckService decks;
    @Autowired private ItemService items;
    @Autowired private ExerciseService exercises;
    @Autowired private StudySessionService sessions;
    @Autowired private MediaCatalog media;
    @Autowired private MediaManifestCatalog manifestCatalog;
    @Autowired private ImmutableStorage storage;
    @Autowired private JdbcClient jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private ManifestPages manifests;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        HttpIdentityFixture.register(registry);
        registry.add("learning.community.forks.enabled", () -> true);
        registry.add("learning.community.public-routes.enabled", () -> true);
    }

    @Test
    void aCopyMadeWhileTheAuthorHoldsUnpublishedAssetsNeverReachesThem() {
        ForkWorlds worlds = new ForkWorlds(port, decks, items, exercises, sessions, media, storage, jdbc, transactions, manifests);
        StudyFixtures study = worlds.study;
        UUID author = UUID.randomUUID();
        HttpIdentityFixture.publicProfile(author, true);
        UUID reader = UUID.randomUUID();

        // the published revision: a material with an image, an exercise with a sound
        UUID published = study.readyAsset(author, "image/png");
        Variants publishedVariants = MediaFixtures.variants(jdbc, published, "image/webp");
        UUID publishedSound = study.readyAsset(author, "audio/mpeg");
        Variants publishedSoundVariants = MediaFixtures.variants(jdbc, publishedSound, "audio/mp4", false);
        UUID deck = worlds.shared.deck(author, "Источник с медиа");
        Material picture = worlds.shared.create(author, deck, MediaFixtures.imageDocument(published));
        StudyFixtures.Material host = study.addMaterial(author, deck, "memory", "forgetting");
        JsonNode firstExercise = study.publish(host, study.freeResponse(host, blocks(audio(publishedSound, "Звук", null), text("Что слышно?")), blocks(), "memory"), "Слух");
        String code = worlds.publish(author, deck, "LINK");

        // what the author does afterwards, unpublished
        UUID later = study.readyAsset(author, "image/png");
        Variants laterVariants = MediaFixtures.variants(jdbc, later, "image/webp");
        UUID replacement = study.readyAsset(author, "image/png");
        Variants replacementVariants = MediaFixtures.variants(jdbc, replacement, "image/webp");
        UUID laterSound = study.readyAsset(author, "audio/mpeg");
        Variants laterSoundVariants = MediaFixtures.variants(jdbc, laterSound, "audio/mp4", false);
        Material newPicture = worlds.shared.create(author, deck, MediaFixtures.imageDocument(later));
        UUID newerRevision = MediaFixtures.saveImages(decks, items, author, deck, picture.member(), picture.revision(), published, replacement);
        StudyFixtures.Material host2 = study.addMaterial(author, deck, "extra", "extra");
        JsonNode secondExercise = study.publish(host2, study.freeResponse(host2, blocks(audio(laterSound, "Новый звук", null), text("Что теперь?")), blocks(), "extra"), "Новый слух");
        UUID secondExerciseId = UUID.fromString(secondExercise.path("exerciseId").stringValue(null));

        // the copy
        UUID copy = worlds.forked(reader, code);
        worlds.awaitReady(reader, copy);
        worlds.assertHeadsEqualPublishedManifest(deck, copy);

        // 1. control: the published assets are the copier's through the COPY grant, as derived variants
        assertThat(media.playback(reader, published).playable().objectKey()).isEqualTo(publishedVariants.playbackKey());
        assertThat(media.playback(reader, publishedSound).playable().objectKey()).isEqualTo(publishedSoundVariants.playbackKey());
        assertThat(media.playback(reader, published).original()).isNull();

        // 2. playback, variant download and the offline manifest refuse every asset that only an unpublished revision uses
        record Unpublished(String name, UUID asset, Variants variants) { }
        List<Unpublished> hidden = List.of(new Unpublished("new material's image", later, laterVariants),
                new Unpublished("replacement image of a published material", replacement, replacementVariants),
                new Unpublished("new exercise's sound", laterSound, laterSoundVariants));
        for (Unpublished asset : hidden) {
            assertThatThrownBy(() -> media.playback(reader, asset.asset())).as(asset.name()).isInstanceOf(ResourceNotFoundException.class);
            assertThatThrownBy(() -> media.resolve(reader, asset.asset(), asset.variants().playback())).as(asset.name()).isInstanceOf(ResourceNotFoundException.class);
            HttpResponse<String> playback = worlds.get("/media-assets/" + asset.asset() + "/playback", reader);
            assertThat(playback.statusCode()).as(asset.name() + ": " + playback.body()).isEqualTo(404);
            HttpResponse<String> download = worlds.get("/media-assets/" + asset.asset() + "/variants/" + asset.variants().playback() + "/download", reader);
            assertThat(download.statusCode()).as(asset.name() + ": " + download.body()).isEqualTo(404);
            assertThat(playback.body() + download.body()).doesNotContain(asset.variants().playbackKey());
        }
        String manifest = manifestCatalog.current(reader, copy).body();
        assertThat(manifest).contains(published.toString()).contains(publishedSound.toString());
        for (Unpublished asset : hidden) assertThat(manifest).as(asset.name()).doesNotContain(asset.asset().toString()).doesNotContain(asset.variants().playbackKey());
        HttpResponse<String> http = worlds.get("/decks/" + copy + "/media-manifests/current", reader);
        assertThat(http.statusCode()).isEqualTo(200);
        for (Unpublished asset : hidden) assertThat(http.body()).as(asset.name()).doesNotContain(asset.asset().toString());
        // the public route answers the same for the copier as for everybody: only the published materials' assets exist there
        for (Unpublished asset : hidden) {
            assertThat(worlds.get("/public/decks/" + code + "/media/" + asset.asset(), reader).statusCode()).as(asset.name()).isEqualTo(404);
        }

        // 3. the copy does not list or read the unpublished revisions
        JsonNode list = json(worlds.get("/decks/" + copy + "/items?limit=100", reader));
        List<String> listed = new ArrayList<>();
        list.path("items").forEach(item -> listed.add(item.path("memberKey").stringValue(null) + "/" + item.path("itemRevisionId").stringValue(null)));
        assertThat(listed).hasSize(2).contains(picture.member() + "/" + picture.revision()).doesNotContain(picture.member() + "/" + newerRevision);
        assertThat(list.toString()).doesNotContain(newPicture.member().toString());
        assertThat(worlds.get("/decks/" + copy + "/items/" + newPicture.member(), reader).statusCode()).isEqualTo(404);
        HttpResponse<String> newer = worlds.get("/decks/" + copy + "/items/" + picture.member() + "?revisionId=" + newerRevision, reader);
        assertThat(newer.statusCode()).isIn(404, 412);
        assertThat(newer.body()).doesNotContain(replacement.toString());
        JsonNode head = json(worlds.get("/decks/" + copy + "/items/" + picture.member(), reader));
        assertThat(head.path("itemRevisionId").stringValue(null)).isEqualTo(picture.revision().toString());
        assertThat(head.toString()).contains(published.toString()).doesNotContain(replacement.toString());
        JsonNode exerciseList = json(worlds.get("/decks/" + copy + "/exercises?limit=100", reader));
        assertThat(exerciseList.path("exercises")).hasSize(1);
        assertThat(exerciseList.toString()).contains(firstExercise.path("exerciseId").stringValue(null)).doesNotContain(secondExerciseId.toString());
        assertThat(worlds.get("/decks/" + copy + "/exercises/" + secondExerciseId, reader).statusCode()).isEqualTo(404);
        // the draft path cannot attach one of them either
        assertThatThrownBy(() -> MediaFixtures.saveImages(decks, items, reader, copy, picture.member(), picture.revision(), published, later))
                .isInstanceOf(ResourceNotFoundException.class);

        // 4. the author is unaffected, and so is a stranger (no deck of the lineage, nothing)
        assertThat(media.playback(author, later).original()).isNotNull();
        assertThatThrownBy(() -> media.playback(UUID.randomUUID(), later)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void aCopyThatRepublishesAnInheritedNonCommercialImageIsHeldByTheSameChecklistAsItsAuthor() {
        ForkWorlds worlds = new ForkWorlds(port, decks, items, exercises, sessions, media, storage, jdbc, transactions, manifests);
        UUID author = UUID.randomUUID();
        UUID reader = UUID.randomUUID();
        HttpIdentityFixture.publicProfile(author, true);
        HttpIdentityFixture.publicProfile(reader, true);
        UUID image = worlds.study.readyAsset(author, "image/png");
        MediaFixtures.variants(jdbc, image, "image/webp");
        UUID deck = worlds.shared.deck(author, "С чужой картинкой");
        worlds.shared.create(author, deck, MediaFixtures.imageDocument(image));
        String code = worlds.publish(author, deck, "LINK");
        // the stock provenance of the author's image says non-commercial; it never mattered at the LINK level
        jdbc.sql("""
                INSERT INTO app_learning.generation_provenance(provenance_id, owner_id, session_id, artifact_id, revision_id, published_ref, created_at, media)
                VALUES (:id, :owner, :session, :artifact, :revision, '{}'::jsonb, now(), CAST(:media AS jsonb))
                """).param("id", UUID.randomUUID()).param("owner", author).param("session", UUID.randomUUID()).param("artifact", UUID.randomUUID())
                .param("revision", UUID.randomUUID()).param("media", "[{\"assetId\":\"" + image + "\",\"source\":\"PIXABAY\",\"sourceId\":\"1\","
                        + "\"license\":\"cc-by-nc-4.0\",\"sourcePageUrl\":\"https://example.org/1\"}]").update();

        UUID copy = worlds.forked(reader, code);
        worlds.awaitReady(reader, copy);
        // the copier did not make the image, but it is in his heads: becoming public is refused for the same reason it would be for the author
        HttpResponse<String> refused = worlds.publishRaw(reader, copy, "PUBLIC");
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(409);
        JsonNode problem = json(refused);
        assertThat(problem.path("code").stringValue(null)).isEqualTo("PUBLICATION_REQUIREMENTS");
        assertThat(problem.path("failed").toString()).contains("blockedMedia");
        assertThat(json(worlds.get("/decks/" + copy + "/publication", reader)).path("checklist").path("blockedMedia")).hasSize(1);
        // by link it is fine
        assertThat(worlds.publishRaw(reader, copy, "LINK").statusCode()).isEqualTo(200);
    }
}
