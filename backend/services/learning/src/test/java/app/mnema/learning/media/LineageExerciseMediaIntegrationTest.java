package app.mnema.learning.media;

import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.storage.ImmutableStorage;
import app.mnema.learning.study.session.StudySessionService;
import app.mnema.learning.support.PostgresIntegrationTest;
import app.mnema.learning.support.SharedScopeFixture;
import app.mnema.learning.support.SharedScopeFixture.Copy;
import app.mnema.learning.support.StudyFixtures;
import app.mnema.learning.support.StudyFixtures.Material;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.function.BiFunction;

import java.util.UUID;

import static app.mnema.learning.support.StudyFixtures.audio;
import static app.mnema.learning.support.StudyFixtures.blocks;
import static app.mnema.learning.support.StudyFixtures.category;
import static app.mnema.learning.support.StudyFixtures.item;
import static app.mnema.learning.support.StudyFixtures.option;
import static app.mnema.learning.support.StudyFixtures.video;
import static app.mnema.learning.support.StudyFixtures.image;
import static app.mnema.learning.support.StudyFixtures.text;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Share/5 (#427): exercise media references are lineage rows that keep the owner of the ASSET ({@code asset_owner_id}).
 * Readiness is a property of the lineage revision (no actor, no deck filter) and fails closed: a revision whose content names an
 * asset that has no reference row, and a revision that does not exist, are not ready.
 */
@SpringBootTest
class LineageExerciseMediaIntegrationTest extends PostgresIntegrationTest {
    @Autowired private MediaCatalog catalog;
    @Autowired private MediaManifestCatalog manifests;
    @Autowired private DeckService decks;
    @Autowired private ItemService items;
    @Autowired private ExerciseService exercises;
    @Autowired private StudySessionService studies;
    @Autowired private ImmutableStorage storage;
    @Autowired private JdbcClient jdbc;
    @Autowired private PlatformTransactionManager transactions;
    private StudyFixtures fixtures;
    private SharedScopeFixture shared;

    @BeforeEach
    void fixtures() {
        fixtures = new StudyFixtures(decks, items, exercises, studies, catalog, jdbc);
        shared = new SharedScopeFixture(decks, items, storage, jdbc, transactions);
    }

    private boolean ready(UUID scope, UUID exercise, UUID revision) {
        return jdbc.sql("SELECT app_learning.exercise_media_ready(:scope,:exercise,:revision)").param("scope", scope)
                .param("exercise", exercise).param("revision", revision).query(Boolean.class).single();
    }

    @Test
    void readinessIsAPropertyOfTheLineageRevisionAndFailsClosed() {
        Material a = fixtures.material();
        UUID asset = fixtures.pendingAsset(a.actor());
        JsonNode withImage = fixtures.publish(a, fixtures.freeResponse(a, blocks(text("What is it?"), image(asset, "A picture")),
                blocks(), "memory"));
        JsonNode textOnly = fixtures.publish(a, fixtures.freeResponse(a, blocks(text("No media")), blocks(), "memory"));
        UUID exercise = UUID.fromString(withImage.path("exerciseId").stringValue(null));
        UUID revision = UUID.fromString(withImage.path("exerciseRevisionId").stringValue(null));
        Copy copy = shared.copy(a.deck(), UUID.randomUUID());
        UUID scope = copy.scope();

        // the reference carries the asset owner and the lineage; the asset is still uploading
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.exercise_media_ref WHERE reuse_scope_id=:scope AND exercise_id=:e "
                + "AND exercise_revision_id=:r AND asset_owner_id=:owner AND asset_id=:asset").param("scope", scope).param("e", exercise)
                .param("r", revision).param("owner", a.actor()).param("asset", asset).query(Long.class).single()).isOne();
        assertThat(ready(scope, exercise, revision)).isFalse();
        assertThat(catalog.exerciseMediaReady(a.actor(), a.deck(), exercise, revision)).isFalse();
        fixtures.ready(asset, "image/png");
        assertThat(ready(scope, exercise, revision)).isTrue();
        assertThat(catalog.exerciseMediaReady(a.actor(), a.deck(), exercise, revision)).isTrue();
        // the copy's owner does not own the asset and is not the writer: readiness does not depend on who asks
        assertThat(catalog.exerciseMediaReady(copy.owner(), copy.deckId(), exercise, revision)).isTrue();
        // ... but a stranger's deck (or a deck that is not the caller's) is never ready
        assertThat(catalog.exerciseMediaReady(UUID.randomUUID(), copy.deckId(), exercise, revision)).isFalse();
        assertThat(catalog.exerciseMediaReady(copy.owner(), UUID.randomUUID(), exercise, revision)).isFalse();
        // a text-only revision is trivially ready; an unknown revision is not
        assertThat(ready(scope, UUID.fromString(textOnly.path("exerciseId").stringValue(null)),
                UUID.fromString(textOnly.path("exerciseRevisionId").stringValue(null)))).isTrue();
        assertThat(ready(scope, exercise, UUID.randomUUID())).isFalse();
        assertThat(ready(UUID.randomUUID(), exercise, revision)).isFalse();

        // a declared asset whose reference row is missing is NOT ready (the old COALESCE(..., TRUE) said yes)
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.sql("SET LOCAL session_replication_role = replica").update();   // the immutability guard is not what this tests
            assertThat(jdbc.sql("DELETE FROM app_learning.exercise_media_ref WHERE reuse_scope_id=:scope AND exercise_revision_id=:r")
                    .param("scope", scope).param("r", revision).update()).isOne();
        });
        assertThat(ready(scope, exercise, revision)).isFalse();
        assertThat(catalog.exerciseMediaReady(copy.owner(), copy.deckId(), exercise, revision)).isFalse();
        assertThat(catalog.exerciseMediaReady(a.actor(), a.deck(), exercise, revision)).isFalse();
    }

    @Test
    void theOfflineManifestListsTheHeadExercisesOfTheDeckWithTheAssetsItsOwnerOwns() {
        Material a = fixtures.material();
        UUID asset = fixtures.readyAsset(a.actor(), "image/png");
        JsonNode published = fixtures.publish(a, fixtures.freeResponse(a, blocks(text("Look"), image(asset, "A picture")), blocks(), "memory"));
        Copy copy = shared.copy(a.deck(), UUID.randomUUID());
        // the author's manifest pins the exercise reference; the copy's owner owns no asset, so the owner-only rule leaves it empty
        String authorManifest = manifests.current(a.actor(), a.deck()).body();
        assertThat(authorManifest).contains(asset.toString()).contains(published.path("exerciseId").stringValue(null));
        assertThat(manifests.current(copy.owner(), copy.deckId()).body()).doesNotContain(asset.toString());
    }

    private record Case(String name, String mime, BiFunction<Material, UUID, ObjectNode> exercise) { }

    @Test
    void aDeclaredAssetWithoutItsReferenceRowIsNotReadyInEveryPlaceAMechanicCanHoldMedia() {
        UUID correct = UUID.randomUUID(), other = UUID.randomUUID(), left = UUID.randomUUID(), right = UUID.randomUUID();
        UUID leftTwo = UUID.randomUUID(), rightTwo = UUID.randomUUID(), one = UUID.randomUUID(), two = UUID.randomUUID();
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        List<Case> cases = List.of(
                new Case("choice option image", "image/png", (m, asset) -> fixtures.choice(m, false, blocks(text("Pick")),
                        StudyFixtures.blocks().add(option(correct, image(asset, "Picture"))).add(option(other, text("b"))), correct)),
                new Case("choice option audio", "audio/mpeg", (m, asset) -> fixtures.choice(m, true, blocks(text("Pick")),
                        StudyFixtures.blocks().add(option(correct, audio(asset, "Sound", null))).add(option(other, text("b"))), correct)),
                new Case("match left audio", "audio/mpeg", (m, asset) -> fixtures.match(m, blocks(text("Match")),
                        StudyFixtures.blocks().add(item(left, audio(asset, "Sound", null))).add(item(leftTwo, text("l2"))),
                        StudyFixtures.blocks().add(item(right, text("r1"))).add(item(rightTwo, text("r2"))),
                        new UUID[][] {{left, right}, {leftTwo, rightTwo}})),
                new Case("match right image", "image/png", (m, asset) -> fixtures.match(m, blocks(text("Match")),
                        StudyFixtures.blocks().add(item(left, text("l1"))).add(item(leftTwo, text("l2"))),
                        StudyFixtures.blocks().add(item(right, image(asset, "Picture"))).add(item(rightTwo, text("r2"))),
                        new UUID[][] {{left, right}, {leftTwo, rightTwo}})),
                new Case("order item image", "image/png", (m, asset) -> fixtures.order(m, blocks(text("Order")),
                        StudyFixtures.blocks().add(item(one, image(asset, "Picture"))).add(item(two, text("second"))), one, two)),
                new Case("categorize item audio", "audio/mpeg", (m, asset) -> fixtures.categorize(m, blocks(text("Sort")),
                        StudyFixtures.blocks().add(category(first, "A")).add(category(second, "B")),
                        StudyFixtures.blocks().add(item(one, audio(asset, "Sound", null))).add(item(two, text("t"))),
                        new UUID[][] {{one, first}, {two, second}})),
                new Case("prompt audio", "audio/mpeg", (m, asset) -> fixtures.freeResponse(m, blocks(audio(asset, "Voice", null),
                        text("Type")), blocks(), "memory")),
                new Case("prompt video", "video/mp4", (m, asset) -> fixtures.freeResponse(m, blocks(video(asset, "Clip", null),
                        text("Type")), blocks(), "memory")));
        for (Case c : cases) {
            Material m = fixtures.material();
            UUID asset = fixtures.readyAsset(m.actor(), c.mime());
            JsonNode published = fixtures.publish(m, c.exercise().apply(m, asset), c.name());
            Copy copy = shared.copy(m.deck(), UUID.randomUUID());
            UUID exercise = UUID.fromString(published.path("exerciseId").stringValue(null));
            UUID revision = UUID.fromString(published.path("exerciseRevisionId").stringValue(null));
            assertThat(ready(copy.scope(), exercise, revision)).as(c.name() + " ready").isTrue();
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                jdbc.sql("SET LOCAL session_replication_role = replica").update();   // the immutability guard is not what this tests
                assertThat(jdbc.sql("DELETE FROM app_learning.exercise_media_ref WHERE reuse_scope_id=:scope AND exercise_revision_id=:r")
                        .param("scope", copy.scope()).param("r", revision).update()).as(c.name() + " pinned").isOne();
            });
            assertThat(ready(copy.scope(), exercise, revision)).as(c.name() + " without its reference").isFalse();
        }
    }
}
