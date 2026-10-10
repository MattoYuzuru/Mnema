package app.mnema.learning.study.session;

import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseCommand;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.item.ItemPublicationCommand;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.storage.ImmutableStorage;
import app.mnema.learning.study.attempt.AttemptService;
import app.mnema.learning.study.restart.StudyRestartCommand;
import app.mnema.learning.study.restart.StudyRestartService;
import app.mnema.learning.support.PostgresIntegrationTest;
import app.mnema.learning.support.PinnedRevisionScenario;
import app.mnema.learning.support.SharedScopeFixture;
import app.mnema.learning.support.SharedScopeFixture.Copy;
import app.mnema.learning.support.StudyFixtures;
import app.mnema.learning.support.StudyFixtures.Issued;
import app.mnema.learning.support.StudyFixtures.Material;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static app.mnema.learning.support.ContractFixtures.bytes;
import static app.mnema.learning.support.StudyFixtures.JSON;
import static app.mnema.learning.support.StudyFixtures.attempt;
import static app.mnema.learning.support.StudyFixtures.blocks;
import static app.mnema.learning.support.StudyFixtures.option;
import static app.mnema.learning.support.StudyFixtures.quote;
import static app.mnema.learning.support.StudyFixtures.text;
import static app.mnema.learning.support.StudyFixtures.textResponse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Share/5 (#427): exercises, objectives, bindings and Study sit on the storage lineage. Two decks share ONE scope, built
 * without the future copy job ({@link SharedScopeFixture}); everything else goes through the real services. The copy studies the
 * exercises it inherited with progress of its own, never sees what the source added privately after the fork, reads its
 * candidates from the pinned manifest and keeps the source's progress untouched.
 */
@SpringBootTest
class LineageStudyIntegrationTest extends PostgresIntegrationTest {
    @Autowired private StudySessionService study;
    @Autowired private StudySessionRepository repository;
    @Autowired private AttemptService attempts;
    @Autowired private StudyRestartService restarts;
    @Autowired private DeckService decks;
    @Autowired private ItemService items;
    @Autowired private ExerciseService exercises;
    @Autowired private MediaCatalog media;
    @Autowired private ImmutableStorage storage;
    @Autowired private JdbcClient jdbc;
    @Autowired private PlatformTransactionManager transactions;
    private StudyFixtures fixtures;
    private SharedScopeFixture shared;

    @BeforeEach
    void fixtures() {
        fixtures = new StudyFixtures(decks, items, exercises, study, media, jdbc);
        shared = new SharedScopeFixture(decks, items, storage, jdbc, transactions);
    }

    /** What the author published before the fork, and the fork. */
    private record Fork(Material a, Material b, JsonNode exerciseA, JsonNode exerciseB, JsonNode exerciseAB, Copy copy,
                        Material copyA, Material copyB) { }

    private Fork fork() {
        Material a = fixtures.material();
        JsonNode exerciseA = fixtures.publish(a, fixtures.freeResponse(a, blocks(text("A question")), blocks(), "memory"), "About A");
        Material b = fixtures.addMaterial(a.actor(), a.deck(), "beta", "other");
        JsonNode exerciseB = fixtures.publish(b, fixtures.freeResponse(b, blocks(text("B question")), blocks(), "beta"), "About B");
        // an exercise assessed on A that quotes B as context
        UUID correct = UUID.randomUUID();
        JsonNode exerciseAB = fixtures.publish(a, fixtures.choice(a, false, blocks(text("Pick")), blocks()
                .add(option(correct, text("Right"))).add(option(UUID.randomUUID(), quote(b, b.node()))), correct), "A with B");
        Copy copy = shared.copy(a.deck(), UUID.randomUUID());
        return new Fork(a, b, exerciseA, exerciseB, exerciseAB, copy, inCopy(a, copy), inCopy(b, copy));
    }

    private static Material inCopy(Material material, Copy copy) {
        return new Material(copy.owner(), copy.deckId(), material.member(), material.itemRevision(), material.node(),
                material.distractor(), material.divider(), material.root());
    }

    private static String revision(JsonNode acknowledgement) { return acknowledgement.path("exerciseRevisionId").stringValue(null); }

    private static UUID id(JsonNode acknowledgement, String field) { return UUID.fromString(acknowledgement.path(field).stringValue(null)); }

    private void deleteMaterial(Material material, Copy copy) {
        JsonNode head = decks.read(copy.owner(), copy.deckId());
        int ordinal = items.read(copy.owner(), copy.deckId(), material.member(), null).path("ordinal").intValue();
        ObjectNode delete = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString())
                .put("expectedDeckRevisionId", head.path("revisionId").stringValue(null));
        delete.putArray("changes").addObject().put("operation", "delete").put("memberKey", material.member().toString())
                .put("expectedItemRevisionId", material.itemRevision().toString()).put("expectedOrdinal", ordinal);
        items.publish(copy.owner(), copy.deckId(), Long.parseLong(head.path("rowVersion").stringValue(null)),
                ItemPublicationCommand.readBulk(bytes(delete)));
    }

    private Set<String> issuedRevisions(List<Issued> issued) {
        Set<String> result = new HashSet<>();
        issued.forEach(presentation -> result.add(presentation.json().path("exerciseRevisionId").stringValue(null)));
        return result;
    }

    private String progress(UUID account, UUID deck) {
        return jdbc.sql("""
                SELECT jsonb_build_object(
                    'state',(SELECT jsonb_agg(to_jsonb(s) ORDER BY objective_id) FROM app_learning.study_state s
                              WHERE account_id=:account AND deck_id=:deck),
                    'evidence',(SELECT jsonb_agg(to_jsonb(e) ORDER BY attempt_id) FROM app_learning.study_evidence e
                                 WHERE account_id=:account AND deck_id=:deck),
                    'transition',(SELECT jsonb_agg(to_jsonb(t) ORDER BY objective_id,transition_sequence) FROM app_learning.study_transition t
                                   WHERE account_id=:account AND deck_id=:deck),
                    'assignment',(SELECT jsonb_agg(to_jsonb(a) ORDER BY objective_id) FROM app_learning.study_policy_assignment a
                                   WHERE account_id=:account AND deck_id=:deck))::text
                """).param("account", account).param("deck", deck).query(String.class).single();
    }

    @Test
    void aCopyStudiesOnlyItsOwnExercisesFromThePinnedManifestWithProgressOfItsOwn() {
        Fork f = fork();
        // the author studies first, in the source
        List<Issued> authorIssued = fixtures.issue(f.a(), "SCHEDULED", null);
        assertThat(issuedRevisions(authorIssued)).containsExactlyInAnyOrder(revision(f.exerciseA()), revision(f.exerciseB()),
                revision(f.exerciseAB()));
        Issued authorAnswer = authorIssued.stream().filter(p -> p.json().path("exerciseRevisionId").stringValue(null)
                .equals(revision(f.exerciseA()))).findFirst().orElseThrow();
        attempts.submit(f.a().actor(), f.a().deck(), authorAnswer.session(), attempt(authorAnswer, textResponse("memory")));
        String authorProgress = progress(f.a().actor(), f.a().deck());
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.study_state WHERE account_id=:a AND deck_id=:d AND transition_sequence=1")
                .param("a", f.a().actor()).param("d", f.a().deck()).query(Long.class).single()).isOne();

        // after the fork the source adds a private exercise and objective; the copy deletes material B
        JsonNode secret = fixtures.publish(f.a(), fixtures.freeResponse(f.a(), blocks(text("Source secret")), blocks(), "secret"),
                "Source-only objective");
        deleteMaterial(f.copyB(), f.copy());

        List<Issued> issued = fixtures.issue(f.copyA(), "SCHEDULED", null);
        // only the copy's exercises, and never those of the material it deleted (nor the one that quotes it)
        assertThat(issuedRevisions(issued)).containsExactly(revision(f.exerciseA()));
        UUID session = issued.getFirst().session();
        UUID generation = repository.generation(f.copy().deckId(), exercisesRoot(f.copy().deckId())).orElseThrow().generationId();
        // the candidates were read from the copy's manifest (3 entries at the fork): the secret exercise is not among them
        var generationRow = repository.generation(f.copy().deckId(), exercisesRoot(f.copy().deckId())).orElseThrow();
        assertThat(generationRow.scopeId()).isEqualTo(f.copy().scope());
        assertThat(generationRow.status()).isEqualTo("READY");
        assertThat(generationRow.scannedCount()).isEqualTo(3);
        assertThat(generationRow.candidateCount()).isEqualTo(3);
        assertThat(jdbc.sql("SELECT exercise_id FROM app_learning.study_candidate WHERE generation_id=:g ORDER BY candidate_ordinal")
                .param("g", generation).query(UUID.class).list())
                .containsExactly(id(f.exerciseA(), "exerciseId"), id(f.exerciseB(), "exerciseId"), id(f.exerciseAB(), "exerciseId"));
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.study_candidate WHERE generation_id=:g AND reuse_scope_id=:scope AND deck_id=:deck")
                .param("g", generation).param("scope", f.copy().scope()).param("deck", f.copy().deckId()).query(Long.class).single())
                .isEqualTo(3L);
        // presentations carry the copy's deck, the inherited exercise and objective
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.study_presentation WHERE session_id=:s AND deck_id=:deck AND exercise_id=:e")
                .param("s", session).param("deck", f.copy().deckId()).param("e", id(f.exerciseA(), "exerciseId"))
                .query(Long.class).single()).isOne();

        // the copy answers: state, transition, evidence and the policy assignment are keyed by the COPY deck
        Issued answer = issued.getFirst();
        assertThat(attempts.submit(f.copyA().actor(), f.copyA().deck(), session, attempt(answer, textResponse("memory")))
                .outcome().path("feedback").path("result").stringValue(null)).isEqualTo("CORRECT");
        UUID objective = id(f.exerciseA(), "objectiveId");
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.study_state WHERE account_id=:o AND deck_id=:deck AND objective_id=:objective AND transition_sequence=1")
                .param("o", f.copy().owner()).param("deck", f.copy().deckId()).param("objective", objective).query(Long.class).single()).isOne();
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.study_state WHERE account_id=:o AND deck_id<>:deck")
                .param("o", f.copy().owner()).param("deck", f.copy().deckId()).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.study_evidence WHERE account_id=:o AND deck_id=:deck AND objective_id=:objective")
                .param("o", f.copy().owner()).param("deck", f.copy().deckId()).param("objective", objective).query(Long.class).single()).isOne();
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.study_policy_assignment WHERE account_id=:o AND deck_id=:deck AND reuse_scope_id=:scope AND objective_id=:objective")
                .param("o", f.copy().owner()).param("deck", f.copy().deckId()).param("scope", f.copy().scope()).param("objective", objective)
                .query(Long.class).single()).isOne();
        // the source's progress is untouched, and the author's own session still works with the secret exercise included
        assertThat(progress(f.a().actor(), f.a().deck())).isEqualTo(authorProgress);
        assertThat(issuedRevisions(fixtures.issue(f.a(), "PRACTICE", null))).contains(revision(secret));
        // ... while the copy's practice does not
        jdbc.sql("UPDATE app_learning.study_session SET status='COMPLETE',completed_at=statement_timestamp() WHERE session_id=:s")
                .param("s", session).update();
        assertThat(issuedRevisions(fixtures.issue(f.copyA(), "PRACTICE", null))).containsExactly(revision(f.exerciseA()));
        // replay copies the completed scheduled session's presentations of the copy
        List<Issued> replayed = fixtures.issue(f.copyA(), "REPLAY", session);
        assertThat(issuedRevisions(replayed)).containsExactly(revision(f.exerciseA()));
        assertThat(replayed.getFirst().json().path("content").toString()).contains("A question");
    }

    @Test
    void restartTouchesOnlyTheObjectivesOfTheReadingDeck() {
        Fork f = fork();
        // the source gains a private objective on the same material after the fork
        JsonNode secret = fixtures.publish(f.a(), fixtures.freeResponse(f.a(), blocks(text("Source secret")), blocks(), "secret"),
                "Source-only objective");
        ObjectNode body = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString());
        body.putArray("memberKeys").add(f.a().member().toString());
        var copyResult = restarts.restart(f.copy().owner(), f.copy().deckId(), StudyRestartCommand.read(bytes(body)));
        // A has two objectives in the copy (exercise A and the one that quotes B); the source's third is not the copy's
        assertThat(copyResult.acknowledgement().path("objectiveCount").intValue()).isEqualTo(2);
        UUID secretObjective = id(secret, "objectiveId");
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.study_state WHERE objective_id=:o AND deck_id=:copy")
                .param("o", secretObjective).param("copy", f.copy().deckId()).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.study_policy_assignment WHERE objective_id=:o AND deck_id=:copy")
                .param("o", secretObjective).param("copy", f.copy().deckId()).query(Long.class).single()).isZero();
        // the same restart in the source covers its three objectives
        ObjectNode sourceBody = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString());
        sourceBody.putArray("memberKeys").add(f.a().member().toString());
        assertThat(restarts.restart(f.a().actor(), f.a().deck(), StudyRestartCommand.read(bytes(sourceBody)))
                .acknowledgement().path("objectiveCount").intValue()).isEqualTo(3);
        // and the copy's rows were written with the copy's scope
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.study_policy_assignment WHERE deck_id=:copy AND reuse_scope_id<>:scope")
                .param("copy", f.copy().deckId()).param("scope", f.copy().scope()).query(Long.class).single()).isZero();
    }

    @Test
    void theCopyAuthorsNewExercisesAndObjectivesOnInheritedMaterialsAndStudiesThem() {
        Fork f = fork();
        // a new exercise with a new objective on the inherited material A, in the copy
        JsonNode fresh = fixtures.publish(f.copyA(), fixtures.freeResponse(f.copyA(), blocks(text("Copy question")), blocks(), "mine"),
                "Copy objective");
        // and the copy revises the inherited objective of exercise A through an update of the inherited exercise
        UUID inheritedExercise = id(f.exerciseA(), "exerciseId");
        ObjectNode revise = fixtures.createBody(f.copyA(), fixtures.freeResponse(f.copyA(), blocks(text("A question, reworded")),
                blocks(), "memory"), "ignored");
        revise.set("objective", JSON.createObjectNode().put("operation", "revise")
                .put("objectiveId", id(f.exerciseA(), "objectiveId").toString())
                .put("expectedObjectiveRevisionId", id(f.exerciseA(), "objectiveRevisionId").toString())
                .put("title", "Reworded by the copy"));
        revise.put("expectedExerciseRevisionId", revision(f.exerciseA()));
        JsonNode revised = exercises.publish(f.copy().owner(), f.copy().deckId(), inheritedExercise,
                fixtures.deckVersion(f.copyA()), ExerciseCommand.readUpdate(bytes(revise))).acknowledgement();

        // the rows are lineage rows of the copy's scope, with the copy as origin
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.memory_objective WHERE objective_id=:o AND reuse_scope_id=:scope AND deck_id=:copy")
                .param("o", id(fresh, "objectiveId")).param("scope", f.copy().scope()).param("copy", f.copy().deckId())
                .query(Long.class).single()).isOne();
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.exercise_revision WHERE reuse_scope_id=:scope AND deck_id=:copy")
                .param("scope", f.copy().scope()).param("copy", f.copy().deckId()).query(Long.class).single()).isEqualTo(2L);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.objective_revision WHERE reuse_scope_id=:scope AND deck_id=:copy")
                .param("scope", f.copy().scope()).param("copy", f.copy().deckId()).query(Long.class).single()).isEqualTo(2L);
        // the revision chain continues the author's: parent is the inherited revision, in the same lineage
        assertThat(jdbc.sql("SELECT parent_revision_id FROM app_learning.exercise_revision WHERE revision_id=:r AND reuse_scope_id=:scope")
                .param("r", id(revised, "exerciseRevisionId")).param("scope", f.copy().scope()).query(UUID.class).single())
                .isEqualTo(UUID.fromString(revision(f.exerciseA())));
        // reads: the copy's roster has its own exercises and the inherited ones; the source's roster has neither of the copy's
        JsonNode copyList = exercises.list(f.copy().owner(), f.copy().deckId(), null, "100", null);
        Set<String> copyIds = new HashSet<>();
        copyList.path("exercises").forEach(value -> copyIds.add(value.path("exerciseId").stringValue(null)));
        assertThat(copyIds).contains(fresh.path("exerciseId").stringValue(null), inheritedExercise.toString(),
                id(f.exerciseB(), "exerciseId").toString());
        JsonNode sourceList = exercises.list(f.a().actor(), f.a().deck(), null, "100", null);
        Set<String> sourceIds = new HashSet<>();
        sourceList.path("exercises").forEach(value -> sourceIds.add(value.path("exerciseId").stringValue(null)));
        assertThat(sourceIds).doesNotContain(fresh.path("exerciseId").stringValue(null)).contains(inheritedExercise.toString());
        // the source still sees its original revision and objective title of exercise A; the copy sees its reworded one
        assertThat(exercises.read(f.a().actor(), f.a().deck(), inheritedExercise, null).path("objective").path("title").stringValue(null))
                .isEqualTo("About A");
        assertThat(exercises.read(f.copy().owner(), f.copy().deckId(), inheritedExercise, null).path("objective").path("title").stringValue(null))
                .isEqualTo("Reworded by the copy");

        // the copy studies them: a new manifest root is a new generation, built from the copy's own manifest
        List<Issued> issued = fixtures.issue(f.copyA(), "SCHEDULED", null);
        assertThat(issuedRevisions(issued)).containsExactlyInAnyOrder(fresh.path("exerciseRevisionId").stringValue(null),
                revision(revised), revision(f.exerciseB()), revision(f.exerciseAB()));
        // ... and the source's session is unchanged by any of it
        assertThat(issuedRevisions(fixtures.issue(f.a(), "SCHEDULED", null))).containsExactlyInAnyOrder(revision(f.exerciseA()),
                revision(f.exerciseB()), revision(f.exerciseAB()));
        Issued answer = issued.stream().filter(p -> p.json().path("exerciseRevisionId").stringValue(null).equals(
                fresh.path("exerciseRevisionId").stringValue(null))).findFirst().orElseThrow();
        assertThat(attempts.submit(f.copy().owner(), f.copy().deckId(), answer.session(), attempt(answer, textResponse("mine")))
                .outcome().path("feedback").path("result").stringValue(null)).isEqualTo("CORRECT");
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.study_state WHERE deck_id=:copy AND objective_id=:o")
                .param("copy", f.copy().deckId()).param("o", id(fresh, "objectiveId")).query(Long.class).single()).isOne();
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.study_state WHERE deck_id=:source AND objective_id=:o")
                .param("source", f.a().deck()).param("o", id(fresh, "objectiveId")).query(Long.class).single()).isZero();
    }

    @Test
    void aMaterialTheCopyDeletedIsNotIssuedAndASourceDeletionDoesNotReachTheCopy() {
        Fork f = fork();
        // the SOURCE deletes B after the fork: the copy still studies B and its quoting exercise
        deleteSource(f.b(), f.a());
        assertThat(issuedRevisions(fixtures.issue(f.a(), "SCHEDULED", null))).containsExactly(revision(f.exerciseA()));
        assertThat(issuedRevisions(fixtures.issue(f.copyA(), "SCHEDULED", null)))
                .containsExactlyInAnyOrder(revision(f.exerciseA()), revision(f.exerciseB()), revision(f.exerciseAB()));
    }

    private void deleteSource(Material material, Material source) {
        JsonNode head = decks.read(source.actor(), source.deck());
        int ordinal = items.read(source.actor(), source.deck(), material.member(), null).path("ordinal").intValue();
        ObjectNode delete = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString())
                .put("expectedDeckRevisionId", head.path("revisionId").stringValue(null));
        delete.putArray("changes").addObject().put("operation", "delete").put("memberKey", material.member().toString())
                .put("expectedItemRevisionId", material.itemRevision().toString()).put("expectedOrdinal", ordinal);
        items.publish(source.actor(), source.deck(), Long.parseLong(head.path("rowVersion").stringValue(null)),
                ItemPublicationCommand.readBulk(bytes(delete)));
    }

    @Test
    void anInheritedExerciseThatQuotesAMaterialRevisionOlderThanTheCopyStillIssuesItsTextButTheRevisionIsNotReadable() {
        // the exercise pins a revision that is neither the copy's head nor in its (empty) journal: Study alone reaches it
        // through the copy's own head exercise binding
        PinnedRevisionScenario s = PinnedRevisionScenario.build(shared, fixtures);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.deck_item_change WHERE deck_id=:d").param("d", s.copy().deckId())
                .query(Long.class).single()).isZero();
        assertThat(repository.material(s.copy().deckId(), s.member(), s.pinned())).isPresent();
        assertThat(repository.material(s.copy().deckId(), s.member(), s.head())).isPresent();
        // an exercise the source authored AFTER the fork is not in the copy's heads: its pinned revision stays invisible to the copy
        assertThat(repository.material(s.copy().deckId(), s.member(), s.later())).isEmpty();
        assertThat(repository.material(s.source(), s.member(), s.later())).isPresent();
        // nothing but Study widens: a by-id read of the pinned-only revision is an opaque 404 (not a 409 for a missing position)
        assertThatThrownBy(() -> items.read(s.copy().owner(), s.copy().deckId(), s.member(), s.pinned()))
                .isInstanceOf(app.mnema.learning.platform.api.ResourceNotFoundException.class);
        assertThatThrownBy(() -> items.read(s.copy().owner(), s.copy().deckId(), s.member(), s.later()))
                .isInstanceOf(app.mnema.learning.platform.api.ResourceNotFoundException.class);
        assertThat(items.read(s.copy().owner(), s.copy().deckId(), s.member(), s.head()).path("itemRevisionId").stringValue(null))
                .isEqualTo(s.head().toString());

        List<Issued> issued = fixtures.issue(s.copyMaterial(), "SCHEDULED", null);
        assertThat(issuedRevisions(issued)).containsExactly(revision(s.inheritedExercise()));
        // the text comes from the pinned (older) revision, not from the head
        assertThat(issued.getFirst().json().path("content").toString()).contains("memory").doesNotContain("rewritten");
    }

    @Test
    void anInFlightPreparationResumesFromTheOrdinalOfThePinnedManifest() {
        // a deck whose manifest spans several pages: the cursor is the ordinal in the pinned root, scanned_count
        Material m = fixtures.material();
        int total = 70;
        for (int index = 0; index < total; index++) {
            fixtures.publish(m, fixtures.freeResponse(m, blocks(text("Q" + index)), blocks(), "memory"), "Objective " + index);
        }
        var started = study.start(m.actor(), m.deck(), "UTC", fixtures.scheduled());
        UUID session = UUID.fromString(started.body().path("sessionId").stringValue(null));
        var row = repository.generation(m.deck(), exercisesRoot(m.deck())).orElseThrow();
        assertThat(row.status()).isEqualTo("PREPARING");
        assertThat(row.scannedCount()).isZero();
        assertThat(row.scopeId()).isNotNull();
        ExerciseManifestReader reader = new ExerciseManifestReader(storage);
        UUID scope = row.scopeId();
        List<StudySessionRepository.ManifestEntry> whole = reader.entries(scope, row.exercisesRootId(), total, 0, total);
        assertThat(whole).hasSize(total);

        // a previous step stopped after 30 entries (the state V52 leaves a generation in is "0 scanned"; this is a restart in between)
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status -> {
            var generation = repository.generationForUpdate(m.deck(), row.generationId()).orElseThrow();
            var sources = repository.sourceBatch(generation, whole.subList(0, 30));
            int candidate = 0;
            for (var source : sources) repository.insertCandidate(generation.generationId(), candidate++, generation, source);
            repository.advanceGeneration(generation, 30, 30, false, repository.now());
        });
        assertThat(repository.generation(m.deck(), row.exercisesRootId()).orElseThrow().scannedCount()).isEqualTo(30);

        JsonNode ready = study.read(m.actor(), m.deck(), session);
        assertThat(ready.path("status").stringValue(null)).isEqualTo("ACTIVE");
        var done = repository.generation(m.deck(), row.exercisesRootId()).orElseThrow();
        assertThat(done.status()).isEqualTo("READY");
        assertThat(done.scannedCount()).isEqualTo(total);
        assertThat(done.candidateCount()).isEqualTo(total);
        // candidates follow the manifest order = the roster order, ordinal by ordinal, with no gap and no duplicate at the seam
        assertThat(jdbc.sql("""
                SELECT count(*) FROM app_learning.study_candidate c JOIN app_learning.deck_head_exercise h
                    ON h.deck_id=c.deck_id AND h.exercise_id=c.exercise_id AND h.revision_id=c.exercise_revision_id
                 WHERE c.generation_id=:g AND h.ordinal=c.candidate_ordinal
                """).param("g", row.generationId()).query(Long.class).single()).isEqualTo((long) total);

        // the manifest reader answers any range of the same pinned root, in order and without gaps
        List<StudySessionRepository.ManifestEntry> stitched = new java.util.ArrayList<>();
        for (int at = 0; at < total; at += 25) stitched.addAll(reader.entries(scope, row.exercisesRootId(), total, at, 25));
        assertThat(stitched).isEqualTo(whole);
        assertThat(reader.entries(scope, row.exercisesRootId(), total, total, 5)).isEmpty();
        assertThat(repository.sourceBatch(done, whole.subList(10, 20))).hasSize(10);
        // an entry the lineage cannot resolve is a short answer (the service treats it as an inconsistent projection)
        assertThat(repository.sourceBatch(done, List.of(new StudySessionRepository.ManifestEntry(UUID.randomUUID(), UUID.randomUUID()))))
                .isEmpty();
        assertThatThrownBy(() -> reader.entries(scope, UUID.randomUUID(), total, 0, 5)).isInstanceOf(RuntimeException.class);
    }

    private UUID exercisesRoot(UUID deck) {
        return jdbc.sql("SELECT r.exercises_root_id FROM app_learning.deck d JOIN app_learning.deck_revision r "
                + "ON r.deck_id=d.deck_id AND r.revision_id=d.head_revision_id WHERE d.deck_id=:deck")
                .param("deck", deck).query(UUID.class).single();
    }
}
