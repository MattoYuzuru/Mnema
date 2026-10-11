package app.mnema.learning.generation;

import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.platform.api.ContentUnavailableException;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.storage.ImmutableStorage;
import app.mnema.learning.study.session.StudySessionService;
import app.mnema.learning.support.PostgresIntegrationTest;
import app.mnema.learning.support.SharedScopeFixture;
import app.mnema.learning.support.SharedScopeFixture.Copy;
import app.mnema.learning.support.StudyFixtures;
import app.mnema.learning.support.StudyFixtures.Material;
import app.mnema.learning.support.TakedownFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.UUID;

import static app.mnema.learning.support.StudyFixtures.blocks;
import static app.mnema.learning.support.StudyFixtures.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Share/15 (#437): a taken-down unit is not part of the AI context of the source or of a copy that still holds it (outline, starred, closest titles, exercises the
 * model must not repeat); a material read for a prompt is {@link ContentUnavailableException}, which every caller that reads an unreadable material already
 * treats as absent because it is a {@link ResourceNotFoundException}.
 */
@SpringBootTest
class ContentTakedownContextIntegrationTest extends PostgresIntegrationTest {
    @Autowired private ContextRepository context;
    @Autowired private PinnedMaterials pinned;
    @Autowired private DeckService decks;
    @Autowired private ItemService items;
    @Autowired private ExerciseService exercises;
    @Autowired private MediaCatalog media;
    @Autowired private ImmutableStorage storage;
    @Autowired private StudySessionService studies;
    @Autowired private JdbcClient jdbc;
    @Autowired private PlatformTransactionManager transactions;
    private StudyFixtures fixtures;
    private SharedScopeFixture shared;
    private TakedownFixture takedown;

    @BeforeEach
    void fixtures() {
        fixtures = new StudyFixtures(decks, items, exercises, studies, media, jdbc);
        shared = new SharedScopeFixture(decks, items, storage, jdbc, transactions);
        takedown = new TakedownFixture(jdbc);
    }

    @Test
    void aTakenDownMaterialAndItsExercisesLeaveTheContextOfTheSourceAndOfTheCopyThatHoldsThem() {
        Material a = fixtures.material();
        Material b = fixtures.addMaterial(a.actor(), a.deck(), "beta", "other");
        JsonNode exerciseA = fixtures.publish(a, fixtures.freeResponse(a, blocks(text("A question")), blocks(), "memory"), "About A");
        JsonNode exerciseA2 = fixtures.publish(a, fixtures.freeResponse(a, blocks(text("Second A question")), blocks(), "memory"), "About A again");
        fixtures.publish(b, fixtures.freeResponse(b, blocks(text("B question")), blocks(), "beta"), "About B");
        Copy copy = shared.copy(a.deck(), UUID.randomUUID());
        for (UUID deck : List.of(a.deck(), copy.deckId())) {
            assertThat(context.latest(deck, 10)).extracting(ContextRepository.Head::memberKey).containsExactlyInAnyOrder(a.member(), b.member());
            assertThat(context.exercises(deck, a.member(), 10)).hasSize(2);
        }

        takedown.item(a.deck(), a.member(), a.itemRevision());
        takedown.exercise(a.deck(), UUID.fromString(exerciseA2.path("exerciseId").stringValue(null)), UUID.fromString(exerciseA2.path("exerciseRevisionId").stringValue(null)));

        for (UUID deck : List.of(a.deck(), copy.deckId())) {
            assertThat(context.latest(deck, 10)).as("outline").extracting(ContextRepository.Head::memberKey).containsExactly(b.member());
            assertThat(context.heads(deck, List.of(a.member(), b.member()))).extracting(ContextRepository.Head::memberKey).containsExactly(b.member());
            assertThat(context.exercises(deck, a.member(), 10)).as("exercises the model must not repeat").hasSize(1);
            assertThat(String.join(" ", context.exercises(deck, a.member(), 10).stream().map(ContextRepository.ExerciseLine::content).toList()))
                    .contains("A question").doesNotContain("Second A question");
        }
        assertThat(exerciseA.path("exerciseId").stringValue(null)).isNotNull();
        // a pinned or revised material is read through the owner's item read: gone for the prompt, and absent for every caller that tolerates an absent material
        assertThatThrownBy(() -> items.read(a.actor(), a.deck(), a.member(), null)).isInstanceOf(ContentUnavailableException.class).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> items.read(copy.owner(), copy.deckId(), a.member(), null)).isInstanceOf(ContentUnavailableException.class);
    }

    @Test
    void aCachedPinnedMaterialIsDroppedTheMomentATakedownNamesItsRevision() {
        Material a = fixtures.material();
        assertThat(pinned.read(a.actor(), a.deck(), a.member(), a.itemRevision())).isPresent();
        // read again: served from the cache (a revision never changes)
        assertThat(pinned.read(a.actor(), a.deck(), a.member(), a.itemRevision())).isPresent();
        takedown.item(a.deck(), a.member(), a.itemRevision());
        assertThat(pinned.read(a.actor(), a.deck(), a.member(), a.itemRevision())).as("the cache hit re-checks the takedown").isEmpty();
        assertThat(pinned.read(a.actor(), a.deck(), a.member(), a.itemRevision())).as("and the entry is gone").isEmpty();
    }
}
