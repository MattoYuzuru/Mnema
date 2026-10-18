package app.mnema.learning.update;

import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.fork.DeckPreparingException;
import app.mnema.learning.library.ManifestPages;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.platform.api.ContentUnavailableException;
import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.api.ResourceLimitExceededException;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.platform.concurrency.VersionConflictException;
import app.mnema.learning.platform.idempotency.IdempotencyConflictException;
import app.mnema.learning.storage.ImmutableStorage;
import app.mnema.learning.study.session.StudySessionService;
import app.mnema.learning.support.MediaFixtures;
import app.mnema.learning.support.PostgresIntegrationTest;
import app.mnema.learning.support.SharedScopeFixture;
import app.mnema.learning.support.StudyFixtures.Issued;
import app.mnema.learning.support.StudyFixtures.Material;
import app.mnema.learning.support.TakedownFixture;
import app.mnema.learning.update.CombinedPublication.Applied;
import app.mnema.learning.update.CombinedUnits.ExerciseOp;
import app.mnema.learning.update.CombinedUnits.ItemOp;
import app.mnema.learning.update.CombinedUnits.Metadata;
import app.mnema.learning.update.CombinedUnits.ObjectiveOp;
import app.mnema.learning.update.CombinedWorlds.Authored;
import app.mnema.learning.update.CombinedWorlds.Fork;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static app.mnema.learning.support.StudyFixtures.blocks;
import static app.mnema.learning.support.StudyFixtures.quote;
import static app.mnema.learning.support.StudyFixtures.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Updates/2 (#441), the combined publication on a real PostgreSQL: a source deck and a copy in ONE lineage ({@link SharedScopeFixture}); the author edits materials, exercises and
 * objectives, adds, removes and reorders; the copy adopts a mix of all of it in ONE command and ends with exactly one new revision whose manifests are the author's. Idempotency, the
 * compare-and-set, the shapes the engine refuses, the all-or-nothing of the transaction, re-adoption and a copy's own edits after an adoption (the reason for V60).
 */
@SpringBootTest
class CombinedPublicationIntegrationTest extends PostgresIntegrationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The test double of the Updates/4 hook: records what it was called with and can be told to refuse. */
    static final class RecordingWriter implements FinalWriter {
        record Call(FinalWrites writes, Context context) { }

        final List<Call> calls = new CopyOnWriteArrayList<>();
        volatile RuntimeException failure;

        @Override
        public void write(FinalWrites writes, Context context) {
            calls.add(new Call(writes, context));
            if (failure != null) throw failure;
        }
    }

    @TestConfiguration
    static class Hooks {
        @Bean
        RecordingWriter recordingWriter() { return new RecordingWriter(); }
    }

    @Autowired private CombinedPublication publication;
    @Autowired private RecordingWriter writer;
    @Autowired private DeckService decks;
    @Autowired private ItemService items;
    @Autowired private ExerciseService exercises;
    @Autowired private StudySessionService sessions;
    @Autowired private MediaCatalog media;
    @Autowired private ImmutableStorage storage;
    @Autowired private JdbcClient jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private ManifestPages manifests;
    private CombinedWorlds worlds;

    @BeforeEach
    void worlds() {
        worlds = new CombinedWorlds(decks, items, exercises, sessions, media, storage, jdbc, transactions, manifests);
        writer.calls.clear();
        writer.failure = null;
    }

    private static Material material(UUID actor, UUID deck, UUID member, UUID revision) {
        return new Material(actor, deck, member, revision, null, null, null, null);
    }

    /** Everything the author does to the source after the copy was made: the copy starts one revision behind all of it. */
    private record Authoring(UUID a2, SharedScopeFixture.Material d, UUID asset, MediaFixtures.Variants variants, Authored exerciseA2, Authored exerciseD) { }

    private Authoring authorChanges(Fork f) {
        UUID a2 = worlds.save(f.author(), f.source(), f.a().member(), f.a().itemRevision(), "alpha two");
        UUID asset = worlds.study.readyAsset(f.author(), "image/png");
        MediaFixtures.Variants variants = MediaFixtures.variants(jdbc, asset, "image/webp");
        SharedScopeFixture.Material d = worlds.shared.create(f.author(), f.source(), MediaFixtures.imageDocument(asset));
        Material dm = material(f.author(), f.source(), d.member(), d.revision());
        Authored exerciseD = Authored.of(worlds.study.publish(dm, worlds.study.freeResponse(dm, blocks(text("D question")), blocks(), "delta"), "About D"));
        Authored exerciseA2 = worlds.revise(material(f.author(), f.source(), f.a().member(), a2), f.exerciseA(), "Renamed A");
        MediaFixtures.deleteMaterial(decks, items, f.author(), f.source(), f.b().member(), f.b().itemRevision());
        worlds.deleteExercise(f.author(), f.source(), f.exerciseB().exerciseId());
        worlds.reorder(f.author(), f.source(), f.c().member(), 0);
        return new Authoring(a2, d, asset, variants, exerciseA2, exerciseD);
    }

    private CombinedUnits theWholeUpdate(Fork f, Authoring author, FinalWrites finalWrites) {
        UUID head = worlds.head(f.copyDeck()).revisionId();
        return new CombinedUnits(UUID.randomUUID(), f.copyDeck(), f.copyOwner(), head, new Metadata("Источник, второе издание", "Новое описание"),
                List.of(new ItemOp.AdoptReplace(f.a().member(), 0, author.a2()), new ItemOp.Remove(f.b().member(), 1), new ItemOp.Move(f.c().member(), 1, 0),
                        new ItemOp.AdoptInsert(author.d().member(), 2, author.d().revision())),
                List.of(new ExerciseOp.AdoptReplace(f.exerciseA().exerciseId(), 0, author.exerciseA2().revisionId()),
                        new ExerciseOp.Remove(f.exerciseB().exerciseId(), 1),
                        new ExerciseOp.AdoptInsert(author.exerciseD().exerciseId(), 1, author.exerciseD().revisionId())),
                List.of(new ObjectiveOp.Adopt(author.exerciseA2().objectiveId(), author.exerciseA2().objectiveRevisionId()),
                        new ObjectiveOp.Adopt(author.exerciseD().objectiveId(), author.exerciseD().objectiveRevisionId())), finalWrites);
    }

    @Test
    void acceptingAnUpdateWritesNoMilestoneOfTheCopysOwner() {
        Fork f = worlds.fork();
        Authoring author = authorChanges(f);
        long authored = jdbc.sql("SELECT count(*) FROM app_learning.account_milestone WHERE account_id=:a AND milestone_key IN ('OWN_MATERIAL','OWN_EXERCISES')").param("a", f.author())
                .query(Long.class).single();
        assertThat(authored).as("the author's own publications are his milestones").isEqualTo(2);
        long copyOwnerBefore = jdbc.sql("SELECT count(*) FROM app_learning.account_milestone WHERE account_id=:a").param("a", f.copyOwner()).query(Long.class).single();

        // the update adopts a new material, a new exercise and a new objective into the copy: they are the author's, not the copy owner's
        Applied applied = publication.publish(theWholeUpdate(f, author, null));

        assertThat(applied.result().changed()).isTrue();
        assertThat(applied.result().itemsChanged()).isEqualTo(4);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.account_milestone WHERE account_id=:a").param("a", f.copyOwner()).query(Long.class).single())
                .isEqualTo(copyOwnerBefore).isZero();
    }

    @Test
    void aMixOfAdoptionsRemovalsMovesObjectivesAndMetadataIsOneRevisionWhoseManifestsAreTheAuthors() {
        Fork f = worlds.fork();
        Authoring author = authorChanges(f);
        CombinedWorlds.DeckHead before = worlds.head(f.copyDeck());
        assertThat(worlds.revisions(f.copyDeck())).isOne();
        // the copy is still where it was forked: the author's later revisions are not its own
        assertThat(worlds.members(f.copyDeck())).hasSize(3).isNotEqualTo(worlds.members(f.source()));
        assertThatThrownBy(() -> items.read(f.copyOwner(), f.copyDeck(), f.a().member(), author.a2())).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> media.playback(f.copyOwner(), author.asset())).isInstanceOf(ResourceNotFoundException.class);

        CombinedUnits units = theWholeUpdate(f, author, null);
        Applied applied = publication.publish(units);

        // exactly one new revision, a child of the old head, with the new title and the author's manifests
        assertThat(applied.replayed()).isFalse();
        CombinedResult result = applied.result();
        assertThat(result.changed()).isTrue();
        assertThat(result.deckVersion()).isEqualTo(before.version() + 1);
        assertThat(result.itemsChanged()).isEqualTo(4);
        assertThat(result.exercisesChanged()).isEqualTo(3);
        assertThat(result.objectivesChanged()).isEqualTo(2);
        assertThat(result.metadataChanged()).isTrue();
        assertThat(worlds.revisions(f.copyDeck())).isEqualTo(2);
        CombinedWorlds.DeckHead after = worlds.head(f.copyDeck());
        assertThat(after.revisionId()).isEqualTo(result.deckRevisionId());
        assertThat(after.version()).isEqualTo(before.version() + 1);
        assertThat(after.title()).isEqualTo("Источник, второе издание");
        assertThat(after.description()).isEqualTo("Новое описание");
        assertThat(jdbc.sql("SELECT parent_revision_id FROM app_learning.deck_revision WHERE revision_id=:r").param("r", after.revisionId()).query(UUID.class).single())
                .isEqualTo(before.revisionId());
        assertThat(after.memberCount()).isEqualTo(3);
        assertThat(after.exerciseCount()).isEqualTo(2);
        assertThat(worlds.members(f.copyDeck())).isEqualTo(worlds.members(f.source()));
        assertThat(worlds.exercises(f.copyDeck())).isEqualTo(worlds.exercises(f.source()));

        // the heads are the expected set, in the author's order
        assertThat(worlds.itemHeads(f.copyDeck())).isEqualTo(Map.of(f.a().member(), author.a2(), f.c().member(), f.c().itemRevision(),
                author.d().member(), author.d().revision()));
        assertThat(worlds.exerciseHeads(f.copyDeck())).isEqualTo(Map.of(f.exerciseA().exerciseId(), author.exerciseA2().revisionId(),
                author.exerciseD().exerciseId(), author.exerciseD().revisionId()));
        assertThat(jdbc.sql("SELECT exercise_id FROM app_learning.deck_head_exercise WHERE deck_id=:d ORDER BY ordinal").param("d", f.copyDeck()).query(UUID.class).list())
                .containsExactly(f.exerciseA().exerciseId(), author.exerciseD().exerciseId());
        Map<UUID, UUID> objectives = worlds.objectiveHeads(f.copyDeck());
        assertThat(objectives).containsEntry(f.exerciseA().objectiveId(), author.exerciseA2().objectiveRevisionId())
                .containsEntry(author.exerciseD().objectiveId(), author.exerciseD().objectiveRevisionId())
                .containsEntry(f.exerciseB().objectiveId(), f.exerciseB().objectiveRevisionId());
        // the lineage got nothing new: no revision of any kind was written for the copy
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.item_revision WHERE deck_id=:d").param("d", f.copyDeck()).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.exercise_revision WHERE deck_id=:d").param("d", f.copyDeck()).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.objective_revision WHERE deck_id=:d").param("d", f.copyDeck()).query(Long.class).single()).isZero();

        // the journal: one row per member and revision; the move of C is its own reorder, the final ordinals are the result's
        List<String> itemJournal = jdbc.sql("""
                SELECT change_ordinal||' '||change_kind||' '||member_key||' '||COALESCE(previous_revision_id::text,'-')||' '||COALESCE(revision_id::text,'-')
                       ||' '||COALESCE(from_ordinal::text,'-')||' '||COALESCE(to_ordinal::text,'-')
                  FROM app_learning.deck_item_change WHERE deck_id=:d AND deck_revision_id=:r ORDER BY change_ordinal
                """).param("d", f.copyDeck()).param("r", after.revisionId()).query(String.class).list();
        assertThat(itemJournal).containsExactly(
                "0 save " + f.a().member() + " " + f.a().itemRevision() + " " + author.a2() + " 0 1",
                "1 delete " + f.b().member() + " " + f.b().itemRevision() + " - 1 -",
                "2 reorder " + f.c().member() + " " + f.c().itemRevision() + " - 1 0",
                "3 create " + author.d().member() + " - " + author.d().revision() + " - 2");
        List<String> exerciseJournal = jdbc.sql("""
                SELECT exercise_id||' '||COALESCE(previous_revision_id::text,'-')||' '||COALESCE(revision_id::text,'-')||' '||ordinal
                  FROM app_learning.deck_exercise_change WHERE deck_id=:d AND deck_revision_id=:r ORDER BY ordinal,exercise_id
                """).param("d", f.copyDeck()).param("r", after.revisionId()).query(String.class).list();
        assertThat(exerciseJournal).containsExactlyInAnyOrder(
                f.exerciseA().exerciseId() + " " + f.exerciseA().revisionId() + " " + author.exerciseA2().revisionId() + " 0",
                f.exerciseB().exerciseId() + " " + f.exerciseB().revisionId() + " - 1",
                author.exerciseD().exerciseId() + " - " + author.exerciseD().revisionId() + " 1");

        // the copy reads what it adopted by id (journaled), and still its own history; the author's later revisions it did not adopt stay invisible
        assertThat(items.read(f.copyOwner(), f.copyDeck(), f.a().member(), author.a2()).path("itemRevisionId").stringValue(null)).isEqualTo(author.a2().toString());
        assertThat(items.read(f.copyOwner(), f.copyDeck(), f.a().member(), author.a2()).path("ordinal").intValue()).isEqualTo(1);
        assertThat(items.read(f.copyOwner(), f.copyDeck(), f.a().member(), f.a().itemRevision()).path("itemRevisionId").stringValue(null))
                .isEqualTo(f.a().itemRevision().toString());
        assertThat(items.read(f.copyOwner(), f.copyDeck(), f.b().member(), f.b().itemRevision()).path("itemRevisionId").stringValue(null))
                .isEqualTo(f.b().itemRevision().toString());
        assertThat(items.read(f.copyOwner(), f.copyDeck(), author.d().member(), null).path("ordinal").intValue()).isEqualTo(2);
        assertThat(exercises.read(f.copyOwner(), f.copyDeck(), f.exerciseA().exerciseId(), author.exerciseA2().revisionId()).path("exerciseRevisionId").stringValue(null))
                .isEqualTo(author.exerciseA2().revisionId().toString());
        assertThat(exercises.read(f.copyOwner(), f.copyDeck(), f.exerciseA().exerciseId(), null).path("objective").path("title").stringValue(null)).isEqualTo("Renamed A");
        assertThat(exercises.read(f.copyOwner(), f.copyDeck(), author.exerciseD().exerciseId(), null).path("objective").path("title").stringValue(null)).isEqualTo("About D");

        // the media of the adopted revision is the copier's through the COPY grant, derived variants only
        assertThat(media.playback(f.copyOwner(), author.asset()).playable().objectKey()).isEqualTo(author.variants().playbackKey());
        assertThat(media.playback(f.copyOwner(), author.asset()).original()).isNull();

        // Study issues the adopted exercises (and not the removed one), from the new exercises manifest
        List<Issued> issued = worlds.study.issue(CombinedWorlds.inCopy(f.a(), f.copy()), "SCHEDULED", null);
        Set<String> issuedRevisions = new HashSet<>();
        issued.forEach(presentation -> issuedRevisions.add(presentation.json().path("exerciseRevisionId").stringValue(null)));
        assertThat(issuedRevisions).containsExactlyInAnyOrder(author.exerciseA2().revisionId().toString(), author.exerciseD().revisionId().toString());

        // the source is untouched by the copy's command
        assertThat(worlds.revisions(f.source())).isGreaterThan(1);
        assertThat(writer.calls).isEmpty();
    }

    @Test
    void theFinalWriterRunsInsideTheTransactionWithTheResultAndAlsoWhenNothingChangesAndRollsEverythingBackWhenItRefuses() {
        Fork f = worlds.fork();
        Authoring author = authorChanges(f);
        ObjectNode body = JSON.createObjectNode().put("baseRevisionId", worlds.head(f.copyDeck()).revisionId().toString());
        body.putArray("decisions").addObject().put("unit", "A").put("decision", "TAKE");
        FinalWrites writes = new FinalWrites("fork.apply", body);

        // a refusal: nothing of the command survives (no revision, no head, no journal row, no receipt)
        CombinedUnits refused = theWholeUpdate(f, author, writes);
        String snapshot = worlds.snapshot(f.copyDeck());
        writer.failure = new IllegalStateException("decisions refused");
        assertThatThrownBy(() -> publication.publish(refused)).isInstanceOf(IllegalStateException.class).hasMessage("decisions refused");
        assertThat(worlds.snapshot(f.copyDeck())).isEqualTo(snapshot);
        assertThat(worlds.receipts(refused.commandId())).isZero();
        assertThat(writer.calls).hasSize(1);
        writer.failure = null;
        writer.calls.clear();

        // accepted: called once, in the final transaction, with the new revision
        Applied applied = publication.publish(refused);
        assertThat(writer.calls).hasSize(1);
        RecordingWriter.Call call = writer.calls.getFirst();
        assertThat(call.writes()).isEqualTo(writes);
        assertThat(call.context().changed()).isTrue();
        assertThat(call.context().commandId()).isEqualTo(refused.commandId());
        assertThat(call.context().deckId()).isEqualTo(f.copyDeck());
        assertThat(call.context().resultRevisionId()).isEqualTo(applied.result().deckRevisionId());
        assertThat(call.context().resultVersion()).isEqualTo(applied.result().deckVersion());
        assertThat(call.context().baseRevisionId()).isEqualTo(refused.expectedHeadRevisionId());
        // a replay does not write again
        assertThat(publication.publish(refused).replayed()).isTrue();
        assertThat(writer.calls).hasSize(1);

        // nothing changes: no revision, and the hook still runs (the decisions of a plan with nothing to take are recorded all the same)
        UUID head = worlds.head(f.copyDeck()).revisionId();
        CombinedUnits nothing = new CombinedUnits(UUID.randomUUID(), f.copyDeck(), f.copyOwner(), head, null, List.of(), List.of(), List.of(),
                new FinalWrites("fork.apply", JSON.createObjectNode().put("note", "nothing to take")));
        Applied unchanged = publication.publish(nothing);
        assertThat(unchanged.result().changed()).isFalse();
        assertThat(unchanged.result().deckRevisionId()).isEqualTo(head);
        assertThat(worlds.head(f.copyDeck()).revisionId()).isEqualTo(head);
        assertThat(writer.calls).hasSize(2);
        assertThat(writer.calls.getLast().context().changed()).isFalse();
        assertThat(writer.calls.getLast().context().resultRevisionId()).isEqualTo(head);
        assertThat(worlds.receipts(nothing.commandId())).isOne();
    }

    @Test
    void operationsThatChangeNothingAreDroppedAndACommandWithNothingLeftCreatesNoRevision() {
        Fork f = worlds.fork();
        UUID head = worlds.head(f.copyDeck()).revisionId();
        String snapshot = worlds.snapshot(f.copyDeck());
        CombinedUnits noop = new CombinedUnits(UUID.randomUUID(), f.copyDeck(), f.copyOwner(), head, new Metadata("Deck", "Description"),
                List.of(new ItemOp.AdoptReplace(f.a().member(), 0, f.a().itemRevision()), new ItemOp.Move(f.c().member(), 2, 2)),
                List.of(new ExerciseOp.AdoptReplace(f.exerciseA().exerciseId(), 0, f.exerciseA().revisionId())),
                List.of(new ObjectiveOp.Adopt(f.exerciseA().objectiveId(), f.exerciseA().objectiveRevisionId())));
        Applied applied = publication.publish(noop);
        assertThat(applied.result().changed()).isFalse();
        assertThat(applied.result().deckRevisionId()).isEqualTo(head);
        assertThat(worlds.snapshot(f.copyDeck())).isEqualTo(snapshot);
        // the receipt records it; the same command answers the same
        assertThat(worlds.receipts(noop.commandId())).isOne();
        assertThat(publication.publish(noop).replayed()).isTrue();
        // an empty command is the same
        assertThat(publication.publish(new CombinedUnits(UUID.randomUUID(), f.copyDeck(), f.copyOwner(), head, null, List.of(), List.of(), List.of()))
                .result().changed()).isFalse();
        assertThat(worlds.snapshot(f.copyDeck())).isEqualTo(snapshot);
    }

    @Test
    void theSameCommandIdIsAReplayAnotherBodyIsAConflictAndAStaleHeadIsRefused() {
        Fork f = worlds.fork();
        Authoring author = authorChanges(f);
        CombinedUnits units = theWholeUpdate(f, author, null);
        Applied first = publication.publish(units);
        Applied second = publication.publish(units);
        assertThat(second.replayed()).isTrue();
        assertThat(second.result()).isEqualTo(first.result());
        assertThat(worlds.revisions(f.copyDeck())).isEqualTo(2);

        // the same id with another body is a conflict, whatever the difference
        CombinedUnits other = new CombinedUnits(units.commandId(), f.copyDeck(), f.copyOwner(), units.expectedHeadRevisionId(), new Metadata("Другое", "название"),
                units.items(), units.exercises(), units.objectives());
        assertThatThrownBy(() -> publication.publish(other)).isInstanceOf(IdempotencyConflictException.class);
        CombinedUnits fewer = new CombinedUnits(units.commandId(), f.copyDeck(), f.copyOwner(), units.expectedHeadRevisionId(), units.metadata(), List.of(),
                units.exercises(), units.objectives());
        assertThatThrownBy(() -> publication.publish(fewer)).isInstanceOf(IdempotencyConflictException.class);

        // a fresh command built against the OLD head is stale now: the head moved
        CombinedUnits stale = new CombinedUnits(UUID.randomUUID(), f.copyDeck(), f.copyOwner(), units.expectedHeadRevisionId(), null,
                List.of(new ItemOp.Move(f.c().member(), 0, 1)), List.of(), List.of());
        assertThatThrownBy(() -> publication.publish(stale)).isInstanceOf(VersionConflictException.class);
        assertThat(worlds.revisions(f.copyDeck())).isEqualTo(2);
    }

    @Test
    void aPlanThatDoesNotDescribeTheDeckIsRefusedAndAStrangerSeesTheOpaqueNotFound() {
        Fork f = worlds.fork();
        Authoring author = authorChanges(f);
        UUID head = worlds.head(f.copyDeck()).revisionId();
        String snapshot = worlds.snapshot(f.copyDeck());

        // an ordinal that holds another key; an insertion of a member the deck has; a replacement of one it does not; a removal of one it does not
        assertConflict(f, head, List.of(new ItemOp.AdoptReplace(f.a().member(), 1, author.a2())));
        assertConflict(f, head, List.of(new ItemOp.AdoptReplace(f.a().member(), 99, author.a2())));
        assertConflict(f, head, List.of(new ItemOp.AdoptInsert(f.a().member(), 3, author.a2())));
        assertConflict(f, head, List.of(new ItemOp.AdoptReplace(author.d().member(), 0, author.d().revision())));
        assertConflict(f, head, List.of(new ItemOp.Remove(author.d().member(), 0)));
        assertConflict(f, head, List.of(new ItemOp.Remove(f.b().member(), 0)));
        assertConflict(f, head, List.of(new ItemOp.Move(f.c().member(), 0, 1)));
        assertConflict(f, head, List.of(new ItemOp.Move(author.d().member(), 0, 1)));
        // a revision that does not exist for that member (another member's, a made-up one) is a defect of the command
        assertNotFound(f, head, List.of(new ItemOp.AdoptReplace(f.a().member(), 0, f.b().itemRevision())));
        assertNotFound(f, head, List.of(new ItemOp.AdoptReplace(f.a().member(), 0, UUID.randomUUID())));
        assertNotFound(f, head, List.of(new ItemOp.AdoptInsert(author.d().member(), 3, f.a().itemRevision())));
        // an insertion beyond the manifest
        assertConflict(f, head, List.of(new ItemOp.AdoptInsert(author.d().member(), 4, author.d().revision())));
        // exercises: a key at the wrong place, a revision of another exercise, an unknown one
        assertThatThrownBy(() -> publication.publish(new CombinedUnits(UUID.randomUUID(), f.copyDeck(), f.copyOwner(), head, null, List.of(),
                List.of(new ExerciseOp.AdoptReplace(f.exerciseA().exerciseId(), 1, author.exerciseA2().revisionId())), List.of()))).isInstanceOf(VersionConflictException.class);
        assertThatThrownBy(() -> publication.publish(new CombinedUnits(UUID.randomUUID(), f.copyDeck(), f.copyOwner(), head, null, List.of(),
                List.of(new ExerciseOp.AdoptReplace(f.exerciseA().exerciseId(), 0, f.exerciseB().revisionId())), List.of()))).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> publication.publish(new CombinedUnits(UUID.randomUUID(), f.copyDeck(), f.copyOwner(), head, null, List.of(), List.of(),
                List.of(new ObjectiveOp.Adopt(f.exerciseA().objectiveId(), UUID.randomUUID()))))).isInstanceOf(ResourceNotFoundException.class);
        assertThat(worlds.snapshot(f.copyDeck())).isEqualTo(snapshot);

        // another account sees the opaque 404, and so does an unknown deck, and the source's owner cannot write the copy
        assertThatThrownBy(() -> publication.publish(new CombinedUnits(UUID.randomUUID(), f.copyDeck(), UUID.randomUUID(), head, null, List.of(), List.of(), List.of())))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> publication.publish(new CombinedUnits(UUID.randomUUID(), f.copyDeck(), f.author(), head, null, List.of(), List.of(), List.of())))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> publication.publish(new CombinedUnits(UUID.randomUUID(), UUID.randomUUID(), f.copyOwner(), head, null, List.of(), List.of(), List.of())))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(worlds.snapshot(f.copyDeck())).isEqualTo(snapshot);
    }

    private void assertConflict(Fork f, UUID head, List<ItemOp> ops) {
        assertThatThrownBy(() -> publication.publish(new CombinedUnits(UUID.randomUUID(), f.copyDeck(), f.copyOwner(), head, null, ops, List.of(), List.of())))
                .as(ops.toString()).isInstanceOf(VersionConflictException.class);
    }

    private void assertNotFound(Fork f, UUID head, List<ItemOp> ops) {
        assertThatThrownBy(() -> publication.publish(new CombinedUnits(UUID.randomUUID(), f.copyDeck(), f.copyOwner(), head, null, ops, List.of(), List.of())))
                .as(ops.toString()).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void anAdoptedExerciseWhoseMaterialTheCopyDoesNotHaveRollsTheWholeCommandBackAfterTheHeadsWereWritten() {
        Fork f = worlds.fork();
        Authoring author = authorChanges(f);
        UUID head = worlds.head(f.copyDeck()).revisionId();
        String snapshot = worlds.snapshot(f.copyDeck());
        // the exercise on D is adopted, D is not: a presentation would quote a material the deck does not have; the item heads and the exercise heads are already written
        CombinedUnits units = new CombinedUnits(UUID.randomUUID(), f.copyDeck(), f.copyOwner(), head, null,
                List.of(new ItemOp.AdoptReplace(f.a().member(), 0, author.a2())),
                List.of(new ExerciseOp.AdoptInsert(author.exerciseD().exerciseId(), 2, author.exerciseD().revisionId())),
                List.of(new ObjectiveOp.Adopt(author.exerciseD().objectiveId(), author.exerciseD().objectiveRevisionId())));
        assertThatThrownBy(() -> publication.publish(units)).isInstanceOf(InvalidRequestException.class);
        assertThat(worlds.snapshot(f.copyDeck())).isEqualTo(snapshot);
        assertThat(worlds.receipts(units.commandId())).isZero();

        // an adopted exercise whose assessed objective has no head in the copy is refused the same way
        CombinedUnits noObjective = new CombinedUnits(UUID.randomUUID(), f.copyDeck(), f.copyOwner(), head, null,
                List.of(new ItemOp.AdoptInsert(author.d().member(), 3, author.d().revision())),
                List.of(new ExerciseOp.AdoptInsert(author.exerciseD().exerciseId(), 2, author.exerciseD().revisionId())), List.of());
        assertThatThrownBy(() -> publication.publish(noObjective)).isInstanceOf(InvalidRequestException.class);
        assertThat(worlds.snapshot(f.copyDeck())).isEqualTo(snapshot);

        // both together are fine
        CombinedUnits complete = new CombinedUnits(UUID.randomUUID(), f.copyDeck(), f.copyOwner(), head, null,
                List.of(new ItemOp.AdoptInsert(author.d().member(), 3, author.d().revision())),
                List.of(new ExerciseOp.AdoptInsert(author.exerciseD().exerciseId(), 2, author.exerciseD().revisionId())),
                List.of(new ObjectiveOp.Adopt(author.exerciseD().objectiveId(), author.exerciseD().objectiveRevisionId())));
        assertThat(publication.publish(complete).result().changed()).isTrue();
    }

    @Test
    void anOlderRevisionCanBeAdoptedAgainAndAgainWithTheVisibilityStillRight() {
        Fork f = worlds.fork();
        UUID a1 = f.a().itemRevision();
        UUID a2 = worlds.save(f.author(), f.source(), f.a().member(), a1, "second");
        UUID a3 = worlds.save(f.author(), f.source(), f.a().member(), a2, "third");

        publication.publish(adopt(f, a2));
        assertThat(worlds.itemHeads(f.copyDeck())).containsEntry(f.a().member(), a2);
        publication.publish(adopt(f, a1));          // a revert
        assertThat(worlds.itemHeads(f.copyDeck())).containsEntry(f.a().member(), a1);
        publication.publish(adopt(f, a2));          // the same revision again
        assertThat(worlds.itemHeads(f.copyDeck())).containsEntry(f.a().member(), a2);

        // a2 was published twice by this deck's journal: one more row, no violation, and the reads answer from the newest
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.deck_item_change WHERE deck_id=:d AND member_key=:m AND revision_id=:r")
                .param("d", f.copyDeck()).param("m", f.a().member()).param("r", a2).query(Long.class).single()).isEqualTo(2L);
        assertThat(jdbc.sql("SELECT indexdef FROM pg_indexes WHERE indexname='deck_item_change_revision'").query(String.class).single())
                .doesNotContain("UNIQUE").contains("(deck_id, member_key, revision_id)");
        for (UUID revision : List.of(a1, a2)) {
            assertThat(items.read(f.copyOwner(), f.copyDeck(), f.a().member(), revision).path("itemRevisionId").stringValue(null)).isEqualTo(revision.toString());
        }
        assertThat(items.read(f.copyOwner(), f.copyDeck(), f.a().member(), a2).path("deckVersion").stringValue(null)).isEqualTo("3");
        // the author's third revision was never adopted: invisible to the copy
        assertThatThrownBy(() -> items.read(f.copyOwner(), f.copyDeck(), f.a().member(), a3)).isInstanceOf(ResourceNotFoundException.class);
        assertThat(worlds.revisions(f.copyDeck())).isEqualTo(4);

        // the same for an exercise
        Authored second = worlds.revise(material(f.author(), f.source(), f.a().member(), a3), f.exerciseA(), "Second title");
        publication.publish(adoptExercise(f, second));
        publication.publish(adoptExercise(f, new Authored(f.exerciseA().exerciseId(), f.exerciseA().revisionId(), f.exerciseA().objectiveId(), f.exerciseA().objectiveRevisionId())));
        publication.publish(adoptExercise(f, second));
        assertThat(worlds.exerciseHeads(f.copyDeck())).containsEntry(f.exerciseA().exerciseId(), second.revisionId());
        for (UUID revision : List.of(f.exerciseA().revisionId(), second.revisionId())) {
            assertThat(exercises.read(f.copyOwner(), f.copyDeck(), f.exerciseA().exerciseId(), revision).path("exerciseRevisionId").stringValue(null)).isEqualTo(revision.toString());
        }
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.deck_exercise_change WHERE deck_id=:d AND exercise_id=:e AND revision_id=:r")
                .param("d", f.copyDeck()).param("e", f.exerciseA().exerciseId()).param("r", second.revisionId()).query(Long.class).single()).isEqualTo(2L);
    }

    private CombinedUnits adopt(Fork f, UUID revision) {
        return new CombinedUnits(UUID.randomUUID(), f.copyDeck(), f.copyOwner(), worlds.head(f.copyDeck()).revisionId(), null,
                List.of(new ItemOp.AdoptReplace(f.a().member(), 0, revision)), List.of(), List.of());
    }

    private CombinedUnits adoptExercise(Fork f, Authored exercise) {
        return new CombinedUnits(UUID.randomUUID(), f.copyDeck(), f.copyOwner(), worlds.head(f.copyDeck()).revisionId(), null, List.of(),
                List.of(new ExerciseOp.AdoptReplace(exercise.exerciseId(), 0, exercise.revisionId())),
                List.of(new ObjectiveOp.Adopt(exercise.objectiveId(), exercise.objectiveRevisionId())));
    }

    @Test
    void aCopyEditingAnAdoptedMaterialOrExerciseTwiceNoLongerCollidesWithItsOwnOlderNumbers() {
        Fork f = worlds.fork();
        UUID member = f.a().member();
        UUID a1 = f.a().itemRevision();
        UUID a2 = worlds.save(f.author(), f.source(), member, a1, "author second");
        // the copy edits its inherited material twice: its own revisions of sequence 1 and 2
        UUID c1 = worlds.save(f.copyOwner(), f.copyDeck(), member, a1, "copy first");
        UUID c2 = worlds.save(f.copyOwner(), f.copyDeck(), member, c1, "copy second");
        // ... adopts the author's sequence 1 (origin: the author's deck) ...
        publication.publish(adopt(f, a2));
        // ... and edits again: sequence 2 of the COPY's origin, which used to collide with c2 (deck_id, member_key, item_sequence)
        UUID c3 = worlds.save(f.copyOwner(), f.copyDeck(), member, a2, "copy third");
        assertThat(worlds.itemHeads(f.copyDeck())).containsEntry(member, c3);
        record Row(long sequence, UUID origin, UUID parent, Long parentSequence) { }
        Map<UUID, Row> rows = new java.util.HashMap<>();
        jdbc.sql("SELECT revision_id,item_sequence,deck_id,parent_revision_id,parent_item_sequence FROM app_learning.item_revision WHERE member_key=:m").param("m", member)
                .query((row, ignored) -> rows.put(row.getObject(1, UUID.class), new Row(row.getLong(2), row.getObject(3, UUID.class), row.getObject(4, UUID.class),
                        row.getObject(5, Long.class)))).list();
        assertThat(rows.get(c2)).isEqualTo(new Row(2, f.copyDeck(), c1, 1L));
        assertThat(rows.get(c3)).isEqualTo(new Row(2, f.copyDeck(), a2, 1L));
        assertThat(rows.get(a2)).isEqualTo(new Row(1, f.source(), a1, 0L));
        assertThat(items.read(f.copyOwner(), f.copyDeck(), member, c3).path("itemRevisionId").stringValue(null)).isEqualTo(c3.toString());
        assertThat(items.read(f.copyOwner(), f.copyDeck(), member, a2).path("itemRevisionId").stringValue(null)).isEqualTo(a2.toString());

        // an exercise: the copy revises twice, adopts the author's, revises again
        Material subject = CombinedWorlds.inCopy(f.a(), f.copy());
        Authored authorSecond = worlds.revise(material(f.author(), f.source(), member, a2), f.exerciseA(), "Author second");
        Authored copyFirst = worlds.revise(material(f.copyOwner(), f.copyDeck(), member, c3), f.exerciseA(), "Copy first");
        Authored copySecond = worlds.revise(material(f.copyOwner(), f.copyDeck(), member, c3), copyFirst, "Copy second");
        publication.publish(adoptExercise(f, authorSecond));
        Authored copyThird = worlds.revise(material(f.copyOwner(), f.copyDeck(), member, c3), authorSecond, "Copy third");
        assertThat(worlds.exerciseHeads(f.copyDeck())).containsEntry(f.exerciseA().exerciseId(), copyThird.revisionId());
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.exercise_revision WHERE exercise_id=:e AND exercise_sequence=2 AND deck_id=:d")
                .param("e", f.exerciseA().exerciseId()).param("d", f.copyDeck()).query(Long.class).single()).isEqualTo(2L);
        assertThat(jdbc.sql("SELECT parent_revision_id FROM app_learning.exercise_revision WHERE revision_id=:r").param("r", copyThird.revisionId()).query(UUID.class).single())
                .isEqualTo(authorSecond.revisionId());
        assertThat(copySecond.revisionId()).isNotEqualTo(copyThird.revisionId());
        assertThat(subject.member()).isEqualTo(member);
    }

    @Test
    void aCopyThatIsStillBeingPreparedRefusesTheCommandAndTheDatabaseRefusesItsHeads() {
        Fork f = worlds.fork();
        Authoring author = authorChanges(f);
        UUID head = worlds.head(f.copyDeck()).revisionId();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            // the guards of deck_fork are not what is tested here: a preparing copy is a row with no materialized_at
            jdbc.sql("SET LOCAL session_replication_role = replica").update();
            jdbc.sql("UPDATE app_learning.deck_fork SET materialized_at = NULL WHERE deck_id = :deck").param("deck", f.copyDeck()).update();
        });
        String snapshot = worlds.snapshot(f.copyDeck());
        assertThatThrownBy(() -> publication.publish(theWholeUpdate(f, author, null))).isInstanceOf(DeckPreparingException.class);
        assertThatThrownBy(() -> publication.enqueue(theWholeUpdate(f, author, null))).isInstanceOf(DeckPreparingException.class);
        // the database is the net under the check: a head write of a preparing copy from any other transaction is refused
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM app_learning.deck_head_item WHERE deck_id=:d AND member_key=:m").param("d", f.copyDeck()).param("m", f.a().member()).update())
                .hasStackTraceContaining("still being prepared");
        assertThat(worlds.snapshot(f.copyDeck())).isEqualTo(snapshot);
        assertThat(worlds.head(f.copyDeck()).revisionId()).isEqualTo(head);
    }

    @Test
    void sizeLimitsSendABigCommandToTheJobAndRefuseAnAbsurdOne() {
        Fork f = worlds.fork();
        UUID head = worlds.head(f.copyDeck()).revisionId();
        List<ItemOp> many = new ArrayList<>();
        for (int index = 0; index < 101; index++) many.add(new ItemOp.AdoptInsert(UUID.randomUUID(), 3 + index, UUID.randomUUID()));
        CombinedUnits big = new CombinedUnits(UUID.randomUUID(), f.copyDeck(), f.copyOwner(), head, null, many, List.of(), List.of());
        assertThat(big.unitCount()).isEqualTo(101);
        assertThatThrownBy(() -> publication.publish(big)).isInstanceOf(InvalidRequestException.class);
        List<ItemOp> absurd = new ArrayList<>();
        for (int index = 0; index < 10_001; index++) absurd.add(new ItemOp.AdoptInsert(UUID.randomUUID(), 0, UUID.randomUUID()));
        CombinedUnits tooBig = new CombinedUnits(UUID.randomUUID(), f.copyDeck(), f.copyOwner(), head, null, absurd, List.of(), List.of());
        // above the cap the existing limit problem (422 RESOURCE_LIMIT_EXCEEDED with the limit), nothing stored
        assertThatThrownBy(() -> publication.enqueue(tooBig)).isInstanceOf(ResourceLimitExceededException.class);
        assertThatThrownBy(() -> publication.submit(tooBig)).isInstanceOf(ResourceLimitExceededException.class);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.combined_publication WHERE command_id=:c").param("c", tooBig.commandId()).query(Long.class).single()).isZero();
    }

    @Test
    void onlyAnAttachedCopyAdoptsAndOnlyWhatItsUpstreamOrItselfCanSee() {
        Fork f = worlds.fork();
        // a sibling copy in the same lineage, made and edited BEFORE the author changes anything
        Fork sibling = new Fork(f.author(), f.source(), f.a(), f.b(), f.c(), f.exerciseA(), f.exerciseB(), worlds.copyOf(f.source(), UUID.randomUUID()));
        UUID siblingEdit = worlds.save(sibling.copyOwner(), sibling.copyDeck(), f.a().member(), f.a().itemRevision(), "sibling edit");
        Authored siblingExercise = worlds.revise(material(sibling.copyOwner(), sibling.copyDeck(), f.a().member(), siblingEdit), f.exerciseA(), "Sibling title");
        Authoring author = authorChanges(f);
        UUID head = worlds.head(f.copyDeck()).revisionId();
        String snapshot = worlds.snapshot(f.copyDeck());

        // a deck that is not a copy has nothing to adopt from (the author's own deck)
        assertThatThrownBy(() -> publication.publish(new CombinedUnits(UUID.randomUUID(), f.source(), f.author(), worlds.head(f.source()).revisionId(), null,
                List.of(new ItemOp.Move(f.c().member(), 0, 1)), List.of(), List.of()))).isInstanceOf(InvalidRequestException.class);

        // the sibling's own revisions are not this copy's to take, however well the ids are known
        assertNotFound(f, head, List.of(new ItemOp.AdoptReplace(f.a().member(), 0, siblingEdit)));
        assertThatThrownBy(() -> publication.publish(new CombinedUnits(UUID.randomUUID(), f.copyDeck(), f.copyOwner(), head, null, List.of(),
                List.of(new ExerciseOp.AdoptReplace(f.exerciseA().exerciseId(), 0, siblingExercise.revisionId())), List.of()))).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> publication.publish(new CombinedUnits(UUID.randomUUID(), f.copyDeck(), f.copyOwner(), head, null, List.of(), List.of(),
                List.of(new ObjectiveOp.Adopt(f.exerciseA().objectiveId(), siblingExercise.objectiveRevisionId()))))).isInstanceOf(ResourceNotFoundException.class);
        assertThat(worlds.snapshot(f.copyDeck())).isEqualTo(snapshot);
        // ... while what the source journaled is accepted (its objective revision too: the source wrote it)
        assertThat(publication.publish(new CombinedUnits(UUID.randomUUID(), f.copyDeck(), f.copyOwner(), head, null, List.of(new ItemOp.AdoptReplace(f.a().member(), 0, author.a2())),
                List.of(new ExerciseOp.AdoptReplace(f.exerciseA().exerciseId(), 0, author.exerciseA2().revisionId())),
                List.of(new ObjectiveOp.Adopt(f.exerciseA().objectiveId(), author.exerciseA2().objectiveRevisionId())))).result().changed()).isTrue();

        // a copy of a copy takes what its upstream (the first copy) holds, which it inherited through its head, and nothing of the source behind it
        Fork second = new Fork(f.author(), f.source(), f.a(), f.b(), f.c(), f.exerciseA(), f.exerciseB(), worlds.copyOf(f.copyDeck(), UUID.randomUUID()));
        UUID firstCopyEdit = worlds.save(f.copyOwner(), f.copyDeck(), f.a().member(), author.a2(), "edit of the first copy");
        UUID thirdHead = worlds.head(second.copyDeck()).revisionId();
        assertThat(publication.publish(new CombinedUnits(UUID.randomUUID(), second.copyDeck(), second.copyOwner(), thirdHead, null,
                List.of(new ItemOp.AdoptReplace(f.a().member(), 0, firstCopyEdit)), List.of(), List.of())).result().changed()).isTrue();
        UUID later = worlds.save(f.author(), f.source(), f.a().member(), author.a2(), "later author edit");
        assertThatThrownBy(() -> publication.publish(new CombinedUnits(UUID.randomUUID(), second.copyDeck(), second.copyOwner(), worlds.head(second.copyDeck()).revisionId(), null,
                List.of(new ItemOp.AdoptReplace(f.a().member(), 0, later)), List.of(), List.of()))).isInstanceOf(ResourceNotFoundException.class);

        // a revision of another lineage is the same 404, whatever kind it is
        Fork foreign = worlds.fork();
        assertThatThrownBy(() -> publication.publish(new CombinedUnits(UUID.randomUUID(), f.copyDeck(), f.copyOwner(), worlds.head(f.copyDeck()).revisionId(), null,
                List.of(new ItemOp.AdoptInsert(foreign.a().member(), 3, foreign.a().itemRevision())), List.of(), List.of()))).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> publication.publish(new CombinedUnits(UUID.randomUUID(), f.copyDeck(), f.copyOwner(), worlds.head(f.copyDeck()).revisionId(), null, List.of(),
                List.of(new ExerciseOp.AdoptInsert(foreign.exerciseA().exerciseId(), 2, foreign.exerciseA().revisionId())), List.of()))).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> publication.publish(new CombinedUnits(UUID.randomUUID(), f.copyDeck(), f.copyOwner(), worlds.head(f.copyDeck()).revisionId(), null, List.of(), List.of(),
                List.of(new ObjectiveOp.Adopt(foreign.exerciseA().objectiveId(), foreign.exerciseA().objectiveRevisionId()))))).isInstanceOf(ResourceNotFoundException.class);

        // a detached copy takes nothing more, but its earlier answers still replay
        UUID detachedHead = worlds.head(f.copyDeck()).revisionId();
        CombinedUnits done = new CombinedUnits(UUID.randomUUID(), f.copyDeck(), f.copyOwner(), detachedHead, new Metadata("Новое имя", "Описание"), List.of(), List.of(), List.of());
        publication.publish(done);
        jdbc.sql("UPDATE app_learning.deck_fork SET detached_at = now() WHERE deck_id = :d").param("d", f.copyDeck()).update();
        assertThat(publication.publish(done).replayed()).isTrue();
        assertThatThrownBy(() -> publication.publish(new CombinedUnits(UUID.randomUUID(), f.copyDeck(), f.copyOwner(), worlds.head(f.copyDeck()).revisionId(), null,
                List.of(new ItemOp.Move(f.c().member(), 2, 0)), List.of(), List.of()))).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void aTakenDownRevisionIsNeverAdoptedWhetherItIsTheItemTheExerciseOrAMaterialTheExerciseQuotes() {
        Fork f = worlds.fork();
        Authoring author = authorChanges(f);
        TakedownFixture takedowns = new TakedownFixture(jdbc);
        UUID head = worlds.head(f.copyDeck()).revisionId();
        String snapshot = worlds.snapshot(f.copyDeck());
        // an exercise of the author that QUOTES material C (a CONTEXT binding) and assesses A at its current revision
        Material subject = material(f.author(), f.source(), f.a().member(), author.a2());
        Authored quoting = Authored.of(worlds.study.publish(subject, worlds.study.freeResponse(subject, blocks(text("Quote"), quote(f.c(), f.c().node())), blocks(), "memory"), "Quoting"));

        takedowns.item(f.source(), f.a().member(), author.a2());
        assertThatThrownBy(() -> publication.publish(new CombinedUnits(UUID.randomUUID(), f.copyDeck(), f.copyOwner(), head, null,
                List.of(new ItemOp.AdoptReplace(f.a().member(), 0, author.a2())), List.of(), List.of()))).isInstanceOf(ContentUnavailableException.class);

        takedowns.exercise(f.source(), author.exerciseD().exerciseId(), author.exerciseD().revisionId());
        assertThatThrownBy(() -> publication.publish(new CombinedUnits(UUID.randomUUID(), f.copyDeck(), f.copyOwner(), head, null, List.of(),
                List.of(new ExerciseOp.AdoptInsert(author.exerciseD().exerciseId(), 2, author.exerciseD().revisionId())), List.of()))).isInstanceOf(ContentUnavailableException.class);

        // the quoting exercise is blocked once the revision of C it quotes goes down (Study would not serve it either)
        takedowns.item(f.source(), f.c().member(), f.c().itemRevision());
        assertThatThrownBy(() -> publication.publish(new CombinedUnits(UUID.randomUUID(), f.copyDeck(), f.copyOwner(), head, null, List.of(),
                List.of(new ExerciseOp.AdoptInsert(quoting.exerciseId(), 2, quoting.revisionId())), List.of()))).isInstanceOf(ContentUnavailableException.class);
        assertThat(worlds.snapshot(f.copyDeck())).isEqualTo(snapshot);
    }

    @Test
    void aRemovedMemberTakesItsExemplarMarkWithItAndAReplacedOneKeepsItsMark() {
        Fork f = worlds.fork();
        UUID a2 = worlds.save(f.author(), f.source(), f.a().member(), f.a().itemRevision(), "alpha two");
        for (UUID member : List.of(f.a().member(), f.c().member())) {
            jdbc.sql("INSERT INTO app_learning.deck_item_exemplar(deck_id,member_key,reuse_scope_id,marked_at) VALUES (:d,:m,:s,now())")
                    .param("d", f.copyDeck()).param("m", member).param("s", f.copy().scope()).update();
        }
        publication.publish(new CombinedUnits(UUID.randomUUID(), f.copyDeck(), f.copyOwner(), worlds.head(f.copyDeck()).revisionId(), null,
                List.of(new ItemOp.AdoptReplace(f.a().member(), 0, a2), new ItemOp.Remove(f.c().member(), 2)), List.of(), List.of()));
        List<UUID> marked = jdbc.sql("SELECT member_key FROM app_learning.deck_item_exemplar WHERE deck_id=:d").param("d", f.copyDeck()).query(UUID.class).list();
        assertThat(marked).containsExactly(f.a().member());
    }

    @Test
    void aMaterialIsNotRemovedWhileAnUntouchedHeadExerciseStillBindsIt() {
        Fork f = worlds.fork();
        UUID head = worlds.head(f.copyDeck()).revisionId();
        String snapshot = worlds.snapshot(f.copyDeck());
        // exercise B assesses material B: removing B alone would leave a presentation quoting a non-member
        assertThatThrownBy(() -> publication.publish(new CombinedUnits(UUID.randomUUID(), f.copyDeck(), f.copyOwner(), head, null,
                List.of(new ItemOp.Remove(f.b().member(), 1)), List.of(), List.of()))).isInstanceOf(InvalidRequestException.class);
        assertThat(worlds.snapshot(f.copyDeck())).isEqualTo(snapshot);
        // with the exercise removed in the same command it is fine
        assertThat(publication.publish(new CombinedUnits(UUID.randomUUID(), f.copyDeck(), f.copyOwner(), head, null, List.of(new ItemOp.Remove(f.b().member(), 1)),
                List.of(new ExerciseOp.Remove(f.exerciseB().exerciseId(), 1)), List.of())).result().changed()).isTrue();
    }

    @Test
    void anObjectiveHeadHasToBeWhatTheMaterializerWouldWriteForTheResultingExercises() {
        Fork f = worlds.fork();
        Authoring author = authorChanges(f);
        UUID head = worlds.head(f.copyDeck()).revisionId();
        String snapshot = worlds.snapshot(f.copyDeck());
        // the exercise moves to the revision that pins the NEW objective revision, but the objective head is not moved with it
        assertThatThrownBy(() -> publication.publish(new CombinedUnits(UUID.randomUUID(), f.copyDeck(), f.copyOwner(), head, null, List.of(),
                List.of(new ExerciseOp.AdoptReplace(f.exerciseA().exerciseId(), 0, author.exerciseA2().revisionId())), List.of()))).isInstanceOf(InvalidRequestException.class);
        // the objective head moves to the new revision while the head exercise still pins the old one: the newest PINNED revision is the old one
        assertThatThrownBy(() -> publication.publish(new CombinedUnits(UUID.randomUUID(), f.copyDeck(), f.copyOwner(), head, null, List.of(), List.of(),
                List.of(new ObjectiveOp.Adopt(f.exerciseA().objectiveId(), author.exerciseA2().objectiveRevisionId()))))).isInstanceOf(InvalidRequestException.class);
        assertThat(worlds.snapshot(f.copyDeck())).isEqualTo(snapshot);
        // both together are the rule
        assertThat(publication.publish(new CombinedUnits(UUID.randomUUID(), f.copyDeck(), f.copyOwner(), head, null, List.of(),
                List.of(new ExerciseOp.AdoptReplace(f.exerciseA().exerciseId(), 0, author.exerciseA2().revisionId())),
                List.of(new ObjectiveOp.Adopt(f.exerciseA().objectiveId(), author.exerciseA2().objectiveRevisionId())))).result().changed()).isTrue();
    }
}
