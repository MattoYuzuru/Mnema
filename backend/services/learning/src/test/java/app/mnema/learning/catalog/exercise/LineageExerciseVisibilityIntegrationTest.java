package app.mnema.learning.catalog.exercise;

import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.platform.api.ResourceNotFoundException;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.UUID;

import static app.mnema.learning.support.ContractFixtures.bytes;
import static app.mnema.learning.support.StudyFixtures.JSON;
import static app.mnema.learning.support.StudyFixtures.blocks;
import static app.mnema.learning.support.StudyFixtures.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Share/5 (#427): an exercise or objective revision read by id is visible to a deck only when it is the deck's head or the deck's
 * own change journal published or replaced it. Two decks share one lineage; neither can read the other's later revisions, the
 * source's history before the copy stays the source's, and an objective revision is never reachable by its own id.
 */
@SpringBootTest
class LineageExerciseVisibilityIntegrationTest extends PostgresIntegrationTest {
    @Autowired private ExerciseRepository repository;
    @Autowired private ExerciseService service;
    @Autowired private DeckService decks;
    @Autowired private ItemService items;
    @Autowired private MediaCatalog media;
    @Autowired private ImmutableStorage storage;
    @Autowired private StudySessionService studies;
    @Autowired private JdbcClient jdbc;
    @Autowired private PlatformTransactionManager transactions;
    private StudyFixtures fixtures;
    private SharedScopeFixture shared;

    @BeforeEach
    void fixtures() {
        fixtures = new StudyFixtures(decks, items, service, studies, media, jdbc);
        shared = new SharedScopeFixture(decks, items, storage, jdbc, transactions);
    }

    private JsonNode update(Material material, UUID exercise, UUID expectedRevision, String question) {
        ObjectNode body = fixtures.createBody(material, fixtures.freeResponse(material, blocks(text(question)), blocks(), "memory"), "x");
        body.set("objective", JSON.createObjectNode().put("operation", "revise")
                .put("objectiveId", objectiveOf(material, exercise).toString())
                .put("expectedObjectiveRevisionId", currentObjectiveRevision(material, exercise).toString())
                .put("title", "Objective after " + question));
        body.put("expectedExerciseRevisionId", expectedRevision.toString());
        return service.publish(material.actor(), material.deck(), exercise, fixtures.deckVersion(material),
                ExerciseCommand.readUpdate(bytes(body))).acknowledgement();
    }

    private UUID objectiveOf(Material material, UUID exercise) {
        return UUID.fromString(service.read(material.actor(), material.deck(), exercise, null).path("objective").path("objectiveId")
                .stringValue(null));
    }

    private UUID currentObjectiveRevision(Material material, UUID exercise) {
        return UUID.fromString(service.read(material.actor(), material.deck(), exercise, null).path("objective")
                .path("objectiveRevisionId").stringValue(null));
    }

    private static UUID id(JsonNode node, String field) { return UUID.fromString(node.path(field).stringValue(null)); }

    @Test
    void anExerciseRevisionIsVisibleOnlyToTheDeckWhoseHeadOrJournalHasIt() {
        Material a = fixtures.material();
        JsonNode first = fixtures.publish(a, fixtures.freeResponse(a, blocks(text("v0")), blocks(), "memory"), "v0");
        UUID exercise = id(first, "exerciseId");
        UUID v0 = id(first, "exerciseRevisionId");
        // the author's history BEFORE the copy: v0 -> v1
        UUID v1 = id(update(a, exercise, v0, "v1"), "exerciseRevisionId");
        Copy copy = shared.copy(a.deck(), UUID.randomUUID());
        Material ca = new Material(copy.owner(), copy.deckId(), a.member(), a.itemRevision(), a.node(), a.distractor(), a.divider(), a.root());

        // after the fork the source goes on (v2) and the copy edits on its own (v3, a sibling of v2)
        UUID v2 = id(update(a, exercise, v1, "v2"), "exerciseRevisionId");
        UUID v3 = id(update(ca, exercise, v1, "v3"), "exerciseRevisionId");

        // the copy: its head, the revision its change replaced (the inherited one) and what it wrote itself; never the source's later or earlier
        assertThat(repository.exerciseRevision(copy.owner(), copy.deckId(), exercise, v3)).isPresent();
        assertThat(repository.exerciseRevision(copy.owner(), copy.deckId(), exercise, v1)).isPresent();
        assertThat(repository.exerciseRevision(copy.owner(), copy.deckId(), exercise, v2)).isEmpty();
        assertThat(repository.exerciseRevision(copy.owner(), copy.deckId(), exercise, v0)).isEmpty();
        // the source: its own history, never the copy's revision
        assertThat(repository.exerciseRevision(a.actor(), a.deck(), exercise, v2)).isPresent();
        assertThat(repository.exerciseRevision(a.actor(), a.deck(), exercise, v1)).isPresent();
        assertThat(repository.exerciseRevision(a.actor(), a.deck(), exercise, v0)).isPresent();
        assertThat(repository.exerciseRevision(a.actor(), a.deck(), exercise, v3)).isEmpty();
        // ownership of the deck is part of the answer
        assertThat(repository.exerciseRevision(a.actor(), copy.deckId(), exercise, v3)).isEmpty();
        assertThat(repository.exerciseRevision(copy.owner(), a.deck(), exercise, v2)).isEmpty();
        // the same through the service, which answers the opaque 404
        assertThat(service.read(copy.owner(), copy.deckId(), exercise, v1).path("exerciseRevisionId").stringValue(null)).isEqualTo(v1.toString());
        assertThatThrownBy(() -> service.read(copy.owner(), copy.deckId(), exercise, v2)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.read(a.actor(), a.deck(), exercise, v3)).isInstanceOf(ResourceNotFoundException.class);
        // the ASSESSED binding of a revision a deck cannot see is not readable either
        assertThat(repository.subject(copy.deckId(), exercise, v3)).isPresent();
        assertThat(repository.subject(copy.deckId(), exercise, v2)).isEmpty();
        assertThat(repository.subject(a.deck(), exercise, v3)).isEmpty();
        // each deck reads its OWN head
        assertThat(repository.exerciseHead(copy.owner(), copy.deckId(), exercise).orElseThrow().revisionId()).isEqualTo(v3);
        assertThat(repository.exerciseHead(a.actor(), a.deck(), exercise).orElseThrow().revisionId()).isEqualTo(v2);
    }

    @Test
    void anObjectiveIsReadOnlyThroughTheRevisionsAndHeadsOfTheReadingDeck() {
        Material a = fixtures.material();
        JsonNode first = fixtures.publish(a, fixtures.freeResponse(a, blocks(text("v0")), blocks(), "memory"), "Shared objective");
        UUID exercise = id(first, "exerciseId");
        UUID objective = id(first, "objectiveId");
        UUID objectiveV0 = id(first, "objectiveRevisionId");
        Copy copy = shared.copy(a.deck(), UUID.randomUUID());
        Material ca = new Material(copy.owner(), copy.deckId(), a.member(), a.itemRevision(), a.node(), a.distractor(), a.divider(), a.root());

        // each deck revises the SAME objective on its own: two siblings in one lineage
        JsonNode sourceEdit = update(a, exercise, id(first, "exerciseRevisionId"), "source");
        JsonNode copyEdit = update(ca, exercise, id(first, "exerciseRevisionId"), "copy");
        UUID sourceObjective = id(sourceEdit, "objectiveRevisionId");
        UUID copyObjective = id(copyEdit, "objectiveRevisionId");
        assertThat(sourceObjective).isNotEqualTo(copyObjective);

        // the head of each deck is its own
        assertThat(repository.objectiveHead(a.actor(), a.deck(), objective).orElseThrow().revisionId()).isEqualTo(sourceObjective);
        assertThat(repository.objectiveHead(copy.owner(), copy.deckId(), objective).orElseThrow().revisionId()).isEqualTo(copyObjective);
        assertThat(repository.objectivesOf(copy.owner(), copy.deckId(), a.member())).extracting(ExerciseRepository.ObjectiveRow::revisionId)
                .containsExactly(copyObjective);
        // the objective of a revision is derived from the exercise revision the deck can see
        assertThat(repository.objectiveOf(copy.owner(), copy.deckId(), exercise, id(copyEdit, "exerciseRevisionId")).orElseThrow()
                .revisionId()).isEqualTo(copyObjective);
        assertThat(repository.objectiveOf(copy.owner(), copy.deckId(), exercise, id(sourceEdit, "exerciseRevisionId"))).isEmpty();
        assertThat(repository.objectiveOf(a.actor(), a.deck(), exercise, id(copyEdit, "exerciseRevisionId"))).isEmpty();
        // the inherited revision the copy replaced still reads with the objective revision it was written against
        assertThat(service.read(copy.owner(), copy.deckId(), exercise, id(first, "exerciseRevisionId")).path("objective")
                .path("objectiveRevisionId").stringValue(null)).isEqualTo(objectiveV0.toString());
        assertThat(service.read(copy.owner(), copy.deckId(), exercise, id(copyEdit, "exerciseRevisionId")).path("objective")
                .path("title").stringValue(null)).isEqualTo("Objective after copy");
        assertThat(service.read(a.actor(), a.deck(), exercise, null).path("objective").path("title").stringValue(null))
                .isEqualTo("Objective after source");
        // a copy can neither reuse nor revise the source's objective revision (it is not the copy's head)
        ObjectNode stale = fixtures.createBody(ca, fixtures.freeResponse(ca, blocks(text("stale")), blocks(), "memory"), "x");
        stale.set("objective", JSON.createObjectNode().put("operation", "reuse").put("objectiveId", objective.toString())
                .put("objectiveRevisionId", sourceObjective.toString()));
        assertThatThrownBy(() -> service.publish(copy.owner(), copy.deckId(), null, fixtures.deckVersion(ca),
                ExerciseCommand.readCreate(bytes(stale)))).isInstanceOf(app.mnema.learning.platform.concurrency.VersionConflictException.class);
    }

    @Test
    void theRosterAndTheCountsOfADeckComeFromItsOwnHeads() {
        Material a = fixtures.material();
        fixtures.publish(a, fixtures.freeResponse(a, blocks(text("padding")), blocks(), "memory"), "padding");
        JsonNode one = fixtures.publish(a, fixtures.freeResponse(a, blocks(text("one")), blocks(), "memory"), "one");
        Copy copy = shared.copy(a.deck(), UUID.randomUUID());
        Material ca = new Material(copy.owner(), copy.deckId(), a.member(), a.itemRevision(), a.node(), a.distractor(), a.divider(), a.root());
        fixtures.publish(a, fixtures.freeResponse(a, blocks(text("source only")), blocks(), "memory"), "two");
        fixtures.publish(ca, fixtures.freeResponse(ca, blocks(text("copy only 1")), blocks(), "memory"), "three");
        fixtures.publish(ca, fixtures.freeResponse(ca, blocks(text("copy only 2")), blocks(), "memory"), "four");

        assertThat(service.list(a.actor(), a.deck(), a.member(), "100", null).path("total").intValue()).isEqualTo(3);
        assertThat(service.list(copy.owner(), copy.deckId(), ca.member(), "100", null).path("total").intValue()).isEqualTo(4);
        assertThat(service.list(copy.owner(), copy.deckId(), null, "100", null).path("exercises")).hasSize(4);
        assertThat(service.list(a.actor(), a.deck(), null, "100", null).path("exercises")).hasSize(3);
        // the roster ordinals are the deck's own: the copy appended after the one it inherited
        assertThat(service.list(copy.owner(), copy.deckId(), null, "100", null).path("exercises").get(3).path("ordinal").intValue()).isEqualTo(3);

        // the copy removes the exercise it inherited: only its own roster shrinks, the removal is journaled with the copy's scope,
        // and the revision it removed stays readable by the copy (its change replaced it) and by the source (still its head)
        UUID inherited = id(one, "exerciseId");
        UUID inheritedRevision = id(one, "exerciseRevisionId");
        service.delete(copy.owner(), copy.deckId(), inherited, fixtures.deckVersion(ca));
        assertThat(service.list(copy.owner(), copy.deckId(), null, "100", null).path("exercises")).hasSize(3);
        assertThat(service.list(a.actor(), a.deck(), null, "100", null).path("exercises")).hasSize(3);
        assertThat(service.read(a.actor(), a.deck(), inherited, null).path("exerciseRevisionId").stringValue(null))
                .isEqualTo(inheritedRevision.toString());
        assertThat(repository.exerciseHead(copy.owner(), copy.deckId(), inherited)).isEmpty();
        assertThat(repository.exerciseRevision(copy.owner(), copy.deckId(), inherited, inheritedRevision)).isPresent();
        // ... with the position its removal recorded (the second of the roster), not 0 by accident of a missing head
        assertThat(repository.exerciseRevision(copy.owner(), copy.deckId(), inherited, inheritedRevision).orElseThrow().ordinal()).isOne();
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.deck_exercise_change WHERE deck_id=:copy AND reuse_scope_id=:scope "
                + "AND exercise_id=:e AND revision_id IS NULL AND previous_revision_id=:r").param("copy", copy.deckId())
                .param("scope", copy.scope()).param("e", inherited).param("r", inheritedRevision).query(Long.class).single()).isOne();
    }
}
