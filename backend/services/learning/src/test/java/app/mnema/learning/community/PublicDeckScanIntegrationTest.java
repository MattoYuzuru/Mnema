package app.mnema.learning.community;

import app.mnema.learning.catalog.content.pages.CountedPageTypes.Entry;
import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.exercise.ExerciseType;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.community.CatalogWorlds.World;
import app.mnema.learning.library.DeckModeration;
import app.mnema.learning.library.ManifestPages;
import app.mnema.learning.library.PublicationService;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.platform.jobs.Job;
import app.mnema.learning.platform.jobs.JobState;
import app.mnema.learning.platform.jobs.PermanentJobException;
import app.mnema.learning.platform.jobs.Slice;
import app.mnema.learning.storage.ImmutableStorage;
import app.mnema.learning.storage.StorageTypes.ObjectRef;
import app.mnema.learning.study.session.StudySessionService;
import app.mnema.learning.support.PostgresIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Community/1 (#448): the scan of the PUBLISHED exercises manifest for the mechanics, in slices (here of 3 entries, the production value is 500). The mask is written only by the slice
 * that reads the last entry, together with {@code projected_revision_id}; a cursor never outlives the revision it names (a publication under the scan restarts it from the beginning
 * of the new revision, whatever bits the old cursor carried); a row removed or a deck hidden under the scan ends cleanly; a malformed cursor or an unresolvable manifest is permanent.
 */
@SpringBootTest(properties = "learning.community.catalog.slice-size=3")
class PublicDeckScanIntegrationTest extends PostgresIntegrationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired private DeckService decks;
    @Autowired private ItemService items;
    @Autowired private ExerciseService exercises;
    @Autowired private StudySessionService sessions;
    @Autowired private MediaCatalog media;
    @Autowired private ImmutableStorage storage;
    @Autowired private JdbcClient jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private PublicationService publication;
    @Autowired private DeckModeration moderation;
    @Autowired private CatalogProjector projector;
    @Autowired private CatalogRepository catalog;
    @Autowired private ProjectionRepository projection;
    @Autowired private ManifestPages manifests;
    @Autowired private CatalogSettings settings;
    private CatalogWorlds worlds;

    @BeforeEach
    void worlds() {
        worlds = new CatalogWorlds(decks, items, exercises, sessions, media, storage, jdbc, transactions, publication, moderation);
        jdbc.sql("UPDATE app_learning.learning_job SET state = 'CANCELLED', finished_at = clock_timestamp() WHERE queue LIKE 'catalog.%' AND state = 'QUEUED'").update();
    }

    /** A public deck with one exercise per mechanic plus a listening exercise: eight exercises, so three slices of three. */
    private World eightExercises() {
        World world = worlds.deck("Восемь упражнений", "Описание", 10);
        int index = 0;
        for (ExerciseType type : ExerciseType.values()) worlds.exercise(world.materials().get(index++), type);
        worlds.listening(world.materials().get(index), worlds.study.readyAsset(world.owner(), "audio/mpeg"));
        worlds.publish(world);
        return world;
    }

    private Slice run(UUID deck, ObjectNode progress) {
        return worlds.run(projector, CatalogWorlds.job(deck, progress, 0));
    }

    private static ObjectNode cursorOf(Slice slice) {
        return ((Slice.Continue) slice).progress();
    }

    @Test
    void theMaskIsWrittenOnlyByTheSliceThatReadsTheLastEntryAndEverySliceIsBounded() {
        World world = eightExercises();
        assertThat(settings.sliceSize).isEqualTo(3);

        Slice first = run(world.deck(), null);
        assertThat(first).isInstanceOf(Slice.Continue.class);
        assertThat(((Slice.Continue) first).progressCount()).isEqualTo(3);
        assertThat(cursorOf(first).path("next").intValue()).isEqualTo(3);
        // the row exists after the first slice (the metadata), the scan is not finished
        PublicDeckRow partial = catalog.find(world.deck()).orElseThrow();
        assertThat(partial.projectedRevisionId()).isNull();
        assertThat(partial.mechanicsMask()).isZero();
        assertThat(partial.itemCount()).isEqualTo(10);

        Slice second = run(world.deck(), cursorOf(first));
        assertThat(cursorOf(second).path("next").intValue()).isEqualTo(6);
        assertThat(catalog.find(world.deck()).orElseThrow().projectedRevisionId()).isNull();

        Slice third = run(world.deck(), cursorOf(second));
        assertThat(third).isEqualTo(Slice.done(8));
        PublicDeckRow done = catalog.find(world.deck()).orElseThrow();
        assertThat(done.mechanicsMask()).isEqualTo(CatalogMechanic.ALL);
        assertThat(done.projectedRevisionId()).isEqualTo(done.publishedRevisionId());
    }

    @Test
    void theMechanicsOfTheOldRevisionStayInTheRowWhileTheNewOneIsScanned() {
        World world = worlds.deck("Старые механики", "Описание", 10);
        for (int index = 0; index < 4; index++) worlds.exercise(world.materials().get(index), ExerciseType.CHOICE);
        worlds.publish(world);
        worlds.project(projector, world.deck());
        assertThat(catalog.find(world.deck()).orElseThrow().mechanics()).containsExactly(CatalogMechanic.CHOICE);

        worlds.exercise(world.materials().get(5), ExerciseType.ORDER);
        worlds.publish(world);
        Slice first = run(world.deck(), null);
        assertThat(first).isInstanceOf(Slice.Continue.class);
        PublicDeckRow during = catalog.find(world.deck()).orElseThrow();
        assertThat(during.exerciseCount()).as("the new metadata is in").isEqualTo(5);
        assertThat(during.mechanics()).as("the previous mechanics stay until the scan of the new revision ends").containsExactly(CatalogMechanic.CHOICE);
        assertThat(during.projectedRevisionId()).isNotEqualTo(during.publishedRevisionId());
        run(world.deck(), cursorOf(first));
        assertThat(catalog.find(world.deck()).orElseThrow().mechanics()).containsExactlyInAnyOrder(CatalogMechanic.CHOICE, CatalogMechanic.ORDER);
    }

    @Test
    void aPublicationUnderTheScanRestartsItFromTheBeginningOfTheNewRevision() {
        World world = eightExercises();
        UUID firstRevision = worlds.publishedRevision(world);
        Slice first = run(world.deck(), null);
        assertThat(cursorOf(first).path("revisionId").stringValue(null)).isEqualTo(firstRevision.toString());

        // the author publishes again while the scan is in flight, and the old cursor even carries bits the new revision does not have
        worlds.exercise(worlds.addMaterial(world), ExerciseType.FREE_RESPONSE);
        worlds.publish(world);
        UUID secondRevision = worlds.publishedRevision(world);
        assertThat(secondRevision).isNotEqualTo(firstRevision);
        ObjectNode stale = cursorOf(first).deepCopy();
        stale.put("mask", CatalogMechanic.ALL);

        Slice restarted = run(world.deck(), stale);
        assertThat(restarted).isInstanceOf(Slice.Continue.class);
        assertThat(cursorOf(restarted).path("revisionId").stringValue(null)).isEqualTo(secondRevision.toString());
        assertThat(cursorOf(restarted).path("next").intValue()).as("from the beginning, not from the old position").isEqualTo(3);
        assertThat(catalog.find(world.deck()).orElseThrow().publishedRevisionId()).isEqualTo(secondRevision);

        Slice cursor = restarted;
        while (cursor instanceof Slice.Continue next) cursor = run(world.deck(), next.progress());
        PublicDeckRow row = catalog.find(world.deck()).orElseThrow();
        assertThat(row.projectedRevisionId()).isEqualTo(secondRevision);
        assertThat(row.exerciseCount()).isEqualTo(9);
        assertThat(row.mechanicsMask()).as("the true bits of the new revision, not the bits of the stale cursor").isEqualTo(CatalogMechanic.ALL);

        // and a deck whose new revision has FEWER kinds does not inherit the stale bits
        World plain = worlds.deck("Простая", "Описание", 10);
        for (int index = 0; index < 4; index++) worlds.exercise(plain.materials().get(index), ExerciseType.FREE_RESPONSE);
        worlds.publish(plain);
        Slice plainFirst = run(plain.deck(), null);
        ObjectNode forged = cursorOf(plainFirst).deepCopy();
        forged.put("mask", CatalogMechanic.ALL);
        worlds.addMaterial(plain);
        worlds.publish(plain);
        Slice again = run(plain.deck(), forged);
        while (again instanceof Slice.Continue next) again = run(plain.deck(), next.progress());
        assertThat(catalog.find(plain.deck()).orElseThrow().mechanicsMask()).isEqualTo(CatalogMechanic.WRITTEN.bit());
    }

    @Test
    void aRowRemovedUnderTheScanIsWrittenAgainAndScannedFromTheStart() {
        World world = eightExercises();
        Slice first = run(world.deck(), null);
        jdbc.sql("DELETE FROM app_learning.public_deck WHERE deck_id = :d").param("d", world.deck()).update();

        Slice second = run(world.deck(), cursorOf(first));
        assertThat(second).isInstanceOf(Slice.Continue.class);
        assertThat(catalog.find(world.deck())).as("a slice in the middle of the scan does not create the row").isEmpty();
        // the last slice finds no row to write the result to: it starts over with a cursor that names no revision
        Slice third = run(world.deck(), cursorOf(second));
        assertThat(third).isInstanceOf(Slice.Continue.class);
        assertThat(cursorOf(third).has("revisionId")).isFalse();
        // the next slice writes the row again and scans from the first entry
        Slice fourth = run(world.deck(), cursorOf(third));
        assertThat(cursorOf(fourth).path("next").intValue()).isEqualTo(3);
        assertThat(catalog.find(world.deck())).isPresent();
        Slice last = fourth;
        while (last instanceof Slice.Continue next) last = run(world.deck(), next.progress());
        assertThat(catalog.find(world.deck()).orElseThrow().mechanicsMask()).isEqualTo(CatalogMechanic.ALL);
    }

    @Test
    void aDeckHiddenUnderTheScanEndsWithoutARow() {
        World world = eightExercises();
        Slice first = run(world.deck(), null);
        worlds.hide(world);
        assertThat(run(world.deck(), cursorOf(first))).isInstanceOf(Slice.Done.class);
        assertThat(catalog.find(world.deck())).isEmpty();
    }

    @Test
    void aDeckWithoutExercisesIsScannedInOneSlice() {
        World world = worlds.deck("Без упражнений", "Описание", 10);
        worlds.publish(world);
        assertThat(run(world.deck(), null)).isInstanceOf(Slice.Done.class);
        PublicDeckRow row = catalog.find(world.deck()).orElseThrow();
        assertThat(row.mechanicsMask()).isZero();
        assertThat(row.projectedRevisionId()).isEqualTo(row.publishedRevisionId());
        assertThat(row.listed()).isFalse();
    }

    @Test
    void aMalformedPayloadOrCursorIsPermanent() {
        World world = eightExercises();
        UUID revision = worlds.publishedRevision(world);
        ObjectNode badPayload = JSON.createObjectNode().put("deckId", "not-a-uuid");
        assertThatThrownBy(() -> worlds.run(projector, new Job(UUID.randomUUID(), "catalog.project", null, badPayload, null, 0, 1)))
                .isInstanceOf(PermanentJobException.class).hasMessageContaining("payload_invalid");
        assertThatThrownBy(() -> worlds.run(projector, new Job(UUID.randomUUID(), "catalog.project", null, JSON.createObjectNode(), null, 0, 1)))
                .isInstanceOf(PermanentJobException.class);
        for (ObjectNode cursor : List.of(
                JSON.createObjectNode().put("revisionId", "x"),
                JSON.createObjectNode().put("revisionId", revision.toString()).put("next", 99).put("mask", 0),
                JSON.createObjectNode().put("revisionId", revision.toString()).put("next", -1).put("mask", 0),
                JSON.createObjectNode().put("revisionId", revision.toString()).put("next", 1).put("mask", 99),
                JSON.createObjectNode().put("revisionId", revision.toString()).put("next", "1").put("mask", 0),
                JSON.createObjectNode().put("revisionId", revision.toString()))) {
            assertThatThrownBy(() -> run(world.deck(), cursor)).as(cursor.toString()).isInstanceOf(PermanentJobException.class);
        }
        // a cursor of ANOTHER revision is not an error: it is ignored
        assertThat(run(world.deck(), JSON.createObjectNode().put("revisionId", UUID.randomUUID().toString()).put("next", 99).put("mask", 99))).isInstanceOf(Slice.Continue.class);
    }

    @Test
    void anUnresolvableManifestEndsTheJobPermanentlyAndAnUnknownTypeIsAnInvariantBreak() {
        World world = eightExercises();
        // a manifest that returns fewer entries than asked, then entries that no exercise revision of the lineage holds
        ManifestPages short_ = Mockito.mock(ManifestPages.class);
        Mockito.when(short_.exercises(Mockito.any(), Mockito.any(), Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt())).thenReturn(List.of());
        CatalogProjector missing = new CatalogProjector(projection, short_, settings, new SimpleMeterRegistry());
        assertThatThrownBy(() -> worlds.run(missing, CatalogWorlds.job(world.deck(), null, 0))).isInstanceOf(PermanentJobException.class).hasMessageContaining("manifest_unresolved");

        ManifestPages invented = Mockito.mock(ManifestPages.class);
        Mockito.when(invented.exercises(Mockito.any(), Mockito.any(), Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt())).thenAnswer(call -> {
            UUID scope = call.getArgument(0);
            return List.of(new Entry(UUID.randomUUID(), new ObjectRef(scope, UUID.randomUUID())), new Entry(UUID.randomUUID(), new ObjectRef(scope, UUID.randomUUID())),
                    new Entry(UUID.randomUUID(), new ObjectRef(scope, UUID.randomUUID())));
        });
        CatalogProjector phantom = new CatalogProjector(projection, invented, settings, new SimpleMeterRegistry());
        assertThatThrownBy(() -> worlds.run(phantom, CatalogWorlds.job(world.deck(), null, 0))).isInstanceOf(PermanentJobException.class).hasMessageContaining("manifest_unresolved");
    }

    @Test
    void aPublicationThatMovesBetweenTheReadAndTheWriteLeavesTheWorkToTheJobItEnqueued() {
        World world = eightExercises();
        // the row is not the one of the revision this job read (the publication moved after the state read): the job does nothing more
        java.util.concurrent.atomic.AtomicReference<java.util.Optional<ProjectionRepository.Projected>> answer = new java.util.concurrent.atomic.AtomicReference<>(java.util.Optional.empty());
        ProjectionRepository moved = new ProjectionRepository(jdbc) {
            @Override
            java.util.Optional<Projected> projected(UUID deck) {
                return answer.get();
            }
        };
        CatalogProjector late = new CatalogProjector(moved, manifests, settings, new SimpleMeterRegistry());
        assertThat(worlds.run(late, CatalogWorlds.job(world.deck(), null, 0))).isInstanceOf(Slice.Done.class);
        answer.set(java.util.Optional.of(new ProjectionRepository.Projected(UUID.randomUUID(), null)));
        assertThat(worlds.run(late, CatalogWorlds.job(world.deck(), null, 0))).isInstanceOf(Slice.Done.class);
    }

    @Test
    void anExerciseTypeTheCodeDoesNotKnowIsABrokenInvariantNotASilentSkip() {
        World world = eightExercises();
        ProjectionRepository odd = new ProjectionRepository(jdbc) {
            @Override
            List<ExerciseKind> exerciseKinds(UUID scope, List<Entry> entries) {
                return List.of(new ExerciseKind("HOLOGRAM", false, true, entries.size()));
            }
        };
        CatalogProjector strange = new CatalogProjector(odd, manifests, settings, new SimpleMeterRegistry());
        assertThatThrownBy(() -> worlds.run(strange, CatalogWorlds.job(world.deck(), null, 0))).isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(PermanentJobException.class);
    }

    @Test
    void anEndedJobIsCountedAndLoggedWithoutTouchingTheRow() {
        MeterRegistry meters = new SimpleMeterRegistry();
        CatalogProjector counting = new CatalogProjector(projection, Mockito.mock(ManifestPages.class), settings, meters);
        counting.onEnded(CatalogWorlds.job(UUID.randomUUID(), null, 0), JobState.FAILED);
        counting.onEnded(CatalogWorlds.job(UUID.randomUUID(), null, 0), JobState.CANCELLED);
        assertThat(meters.get("mnema_catalog_projection_ended_total").tag("outcome", "failed").counter().count()).isEqualTo(1.0);
        assertThat(meters.get("mnema_catalog_projection_ended_total").tag("outcome", "cancelled").counter().count()).isEqualTo(1.0);
        assertThat(counting.queue()).isEqualTo("catalog.project");
    }
}
