package app.mnema.learning.moderation;

import app.mnema.learning.catalog.content.ItemPreviews;
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
import app.mnema.learning.support.SharedScopeFixture.Scenario;
import app.mnema.learning.support.StudyFixtures;
import app.mnema.learning.support.StudyFixtures.Issued;
import app.mnema.learning.support.StudyFixtures.Material;
import app.mnema.learning.support.TakedownFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static app.mnema.learning.support.StudyFixtures.blocks;
import static app.mnema.learning.support.StudyFixtures.option;
import static app.mnema.learning.support.StudyFixtures.quote;
import static app.mnema.learning.support.StudyFixtures.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Share/15 (#437): the readers of content ask ONE predicate, "is this revision of this unit of this lineage taken down", about the revision they are about to
 * serve. These tests drive the real services over a lineage shared by a source, an unchanged copy and a copy whose learner edited the unit
 * ({@link SharedScopeFixture}), without the console: the takedown row is written by {@link TakedownFixture}.
 */
@SpringBootTest
class ContentTakedownReadersIntegrationTest extends PostgresIntegrationTest {
    @Autowired private StudySessionService study;
    @Autowired private app.mnema.learning.study.attempt.AttemptService attempts;
    @Autowired private DeckService decks;
    @Autowired private ItemService items;
    @Autowired private ExerciseService exercises;
    @Autowired private ItemPreviews previews;
    @Autowired private ContentTakedowns takedowns;
    @Autowired private MediaCatalog media;
    @Autowired private ImmutableStorage storage;
    @Autowired private JdbcClient jdbc;
    @Autowired private PlatformTransactionManager transactions;
    private StudyFixtures fixtures;
    private SharedScopeFixture shared;
    private TakedownFixture takedown;

    @BeforeEach
    void fixtures() {
        fixtures = new StudyFixtures(decks, items, exercises, study, media, jdbc);
        shared = new SharedScopeFixture(decks, items, storage, jdbc, transactions);
        takedown = new TakedownFixture(jdbc);
    }

    private static UUID id(JsonNode node, String field) { return UUID.fromString(node.path(field).stringValue(null)); }

    private Set<String> issuedRevisions(List<Issued> issued) {
        Set<String> result = new HashSet<>();
        issued.forEach(presentation -> result.add(presentation.json().path("exerciseRevisionId").stringValue(null)));
        return result;
    }

    private JsonNode row(JsonNode list, String field, UUID key) {
        for (JsonNode row : list.path(field)) if (key.toString().equals(row.path(field.equals("items") ? "memberKey" : "exerciseId").stringValue(null))) return row;
        throw new AssertionError("no row " + key);
    }

    // ---- materials: the owner's reads ----

    @Test
    void aTakenDownMaterialIsUnreadableInTheSourceAndInACopyThatStillHoldsItButNotInACopyThatEditedIt() {
        Scenario scenario = shared.fork();
        UUID member = scenario.material().member();
        UUID original = scenario.material().revision();
        Copy edited = shared.copy(scenario.source(), UUID.randomUUID());
        UUID replacement = shared.save(edited.owner(), edited.deckId(), member, original, shared.document("ученик переписал сам"));
        UUID other = shared.create(scenario.author(), scenario.source(), shared.document("another")).member();
        assertThat(items.read(scenario.author(), scenario.source(), member, null).path("itemRevisionId").stringValue(null)).isEqualTo(original.toString());

        takedown.item(scenario.source(), member, original);

        // the source, and the copy that still holds the revision
        for (var holder : List.of(java.util.Map.entry(scenario.author(), scenario.source()), java.util.Map.entry(scenario.copyOwner(), scenario.copyDeck()))) {
            assertThatThrownBy(() -> items.read(holder.getKey(), holder.getValue(), member, null)).isInstanceOf(ContentUnavailableException.class)
                    .isInstanceOf(ResourceNotFoundException.class);
            JsonNode list = items.list(holder.getKey(), holder.getValue(), "100", null);
            JsonNode listed = row(list, "items", member);
            assertThat(listed.path("unavailable").booleanValue(false)).isTrue();
            assertThat(listed.path("title").stringValue(null)).isEmpty();
            assertThat(listed.path("itemRevisionId").stringValue(null)).isEqualTo(original.toString());
        }
        // the by-revision read of the taken-down revision is blocked too, also for the copy that has since replaced it
        assertThatThrownBy(() -> items.read(edited.owner(), edited.deckId(), member, original)).isInstanceOf(ContentUnavailableException.class);
        // the edited copy holds another revision: nothing changes for it
        JsonNode read = items.read(edited.owner(), edited.deckId(), member, null);
        assertThat(read.path("itemRevisionId").stringValue(null)).isEqualTo(replacement.toString());
        JsonNode editedRow = row(items.list(edited.owner(), edited.deckId(), "100", null), "items", member);
        assertThat(editedRow.has("unavailable")).isFalse();
        assertThat(editedRow.path("title").stringValue(null)).isNotEmpty();
        // a material no decision named is untouched everywhere
        assertThat(items.read(scenario.author(), scenario.source(), other, null).path("memberKey").stringValue(null)).isEqualTo(other.toString());
        assertThat(row(items.list(scenario.author(), scenario.source(), "100", null), "items", other).has("unavailable")).isFalse();
        // the title cache is behind the same predicate (progress pages, AI outlines and speech hints read titles here)
        UUID scope = takedown.scope(scenario.source());
        assertThat(previews.title(scope, member, original)).isEmpty();
        assertThat(previews.title(scope, member, replacement)).isNotEmpty();
        assertThat(takedowns.item(scope, member, original)).isTrue();
        assertThat(takedowns.item(scope, member, replacement)).isFalse();
        assertThat(takedowns.items(scope, List.of(original, replacement))).containsExactly(original);
    }

    @Test
    void aTakenDownExerciseIsUnreadableWhereverItsRevisionIsTheHead() {
        Material a = fixtures.material();
        JsonNode exercise = fixtures.publish(a, fixtures.freeResponse(a, blocks(text("A question")), blocks(text("Reference")), "memory"), "About A");
        Copy copy = shared.copy(a.deck(), UUID.randomUUID());
        UUID exerciseId = id(exercise, "exerciseId");
        UUID revision = id(exercise, "exerciseRevisionId");
        assertThat(exercises.read(a.actor(), a.deck(), exerciseId, null).path("content").toString()).contains("A question");

        takedown.exercise(a.deck(), exerciseId, revision);

        for (var holder : List.of(java.util.Map.entry(a.actor(), a.deck()), java.util.Map.entry(copy.owner(), copy.deckId()))) {
            assertThatThrownBy(() -> exercises.read(holder.getKey(), holder.getValue(), exerciseId, null)).isInstanceOf(ContentUnavailableException.class);
            assertThatThrownBy(() -> exercises.read(holder.getKey(), holder.getValue(), exerciseId, revision)).isInstanceOf(ContentUnavailableException.class);
            JsonNode listed = row(exercises.list(holder.getKey(), holder.getValue(), "100", null), "exercises", exerciseId);
            assertThat(listed.path("unavailable").booleanValue(false)).isTrue();
            assertThat(listed.path("exerciseRevisionId").stringValue(null)).isEqualTo(revision.toString());
            assertThat(listed.path("objective").path("title").stringValue(null)).isEmpty();
            assertThat(listed.toString()).doesNotContain("About A");
        }
        assertThat(takedowns.exercise(takedown.scope(a.deck()), exerciseId, revision)).isTrue();
        assertThat(takedowns.exercise(takedown.scope(a.deck()), exerciseId, UUID.randomUUID())).isFalse();
        // the unit key is part of the predicate: the same revision id under another exercise is not taken down
        assertThat(takedowns.exercise(takedown.scope(a.deck()), UUID.randomUUID(), revision)).isFalse();
    }

    // ---- Study ----

    private record Fork(Material a, Material b, JsonNode exerciseA, JsonNode exerciseB, JsonNode exerciseAB, Copy copy, Material copyA) { }

    /** Two materials and three exercises, one of which assesses A and quotes B (as in the lineage Study tests), and a copy of the deck. */
    private Fork fork() {
        Material a = fixtures.material();
        JsonNode exerciseA = fixtures.publish(a, fixtures.freeResponse(a, blocks(text("A question")), blocks(), "memory"), "About A");
        Material b = fixtures.addMaterial(a.actor(), a.deck(), "beta", "other");
        JsonNode exerciseB = fixtures.publish(b, fixtures.freeResponse(b, blocks(text("B question")), blocks(), "beta"), "About B");
        UUID correct = UUID.randomUUID();
        JsonNode exerciseAB = fixtures.publish(a, fixtures.choice(a, false, blocks(text("Pick")), blocks()
                .add(option(correct, text("Right"))).add(option(UUID.randomUUID(), quote(b, b.node()))), correct), "A with B");
        Copy copy = shared.copy(a.deck(), UUID.randomUUID());
        return new Fork(a, b, exerciseA, exerciseB, exerciseAB, copy,
                new Material(copy.owner(), copy.deckId(), a.member(), a.itemRevision(), a.node(), a.distractor(), a.divider(), a.root()));
    }

    private static String revision(JsonNode acknowledgement) { return acknowledgement.path("exerciseRevisionId").stringValue(null); }

    @Test
    void studySkipsTakenDownExercisesAndExercisesThatQuoteTakenDownMaterialsInTheSourceAndTheCopy() {
        Fork f = fork();
        assertThat(issuedRevisions(fixtures.issue(f.a(), "SCHEDULED", null))).containsExactlyInAnyOrder(revision(f.exerciseA()), revision(f.exerciseB()),
                revision(f.exerciseAB()));
        // the generation of candidates is built and cached by now: the predicate is applied at issuing, not when candidates are built
        takedown.exercise(f.a().deck(), id(f.exerciseA(), "exerciseId"), id(f.exerciseA(), "exerciseRevisionId"));
        assertThat(issuedRevisions(fixtures.issue(f.a(), "SCHEDULED", null))).containsExactlyInAnyOrder(revision(f.exerciseB()), revision(f.exerciseAB()));
        assertThat(issuedRevisions(fixtures.issue(f.copyA(), "SCHEDULED", null))).containsExactlyInAnyOrder(revision(f.exerciseB()), revision(f.exerciseAB()));
        assertThat(issuedRevisions(fixtures.issue(f.a(), "PRACTICE", null))).doesNotContain(revision(f.exerciseA()));

        // B's material is taken down: the exercise that quotes it is skipped, the one assessed on it (it shows none of its text) is not
        takedown.item(f.a().deck(), f.b().member(), f.b().itemRevision());
        assertThat(issuedRevisions(fixtures.issue(f.a(), "SCHEDULED", null))).containsExactly(revision(f.exerciseB()));
        assertThat(issuedRevisions(fixtures.issue(f.copyA(), "SCHEDULED", null))).containsExactly(revision(f.exerciseB()));

        // and the exercise itself
        takedown.exercise(f.b().deck(), id(f.exerciseB(), "exerciseId"), id(f.exerciseB(), "exerciseRevisionId"));
        assertThat(fixtures.issue(f.a(), "SCHEDULED", null)).isEmpty();
        assertThat(fixtures.issue(f.copyA(), "PRACTICE", null)).isEmpty();
    }

    @Test
    void anIssuedPresentationIsNotShownResumedOrReplayedOnceItsExerciseIsTakenDown() {
        Fork f = fork();
        List<Issued> issued = fixtures.issue(f.a(), "SCHEDULED", null);
        UUID session = issued.getFirst().session();
        assertThat(study.read(f.a().actor(), f.a().deck(), session).path("presentations")).isNotEmpty();
        // every exercise of the session is taken down after it was issued
        takedown.exercise(f.a().deck(), id(f.exerciseA(), "exerciseId"), id(f.exerciseA(), "exerciseRevisionId"));
        takedown.exercise(f.a().deck(), id(f.exerciseB(), "exerciseId"), id(f.exerciseB(), "exerciseRevisionId"));
        takedown.exercise(f.a().deck(), id(f.exerciseAB(), "exerciseId"), id(f.exerciseAB(), "exerciseRevisionId"));
        JsonNode reloaded = study.read(f.a().actor(), f.a().deck(), session);
        assertThat(reloaded.path("presentations")).as("the reload shows no taken-down presentation").isEmpty();
        assertThat(reloaded.toString()).doesNotContain("A question").doesNotContain("B question");
        // an answer to a presentation of a taken-down exercise is not graded and returns no reference: the presentation is gone
        Issued answered = issued.getFirst();
        assertThatThrownBy(() -> attempts.submit(f.a().actor(), f.a().deck(), session,
                StudyFixtures.attempt(answered, StudyFixtures.textResponse("memory")))).isInstanceOf(ResourceNotFoundException.class);
    }
}
