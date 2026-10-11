package app.mnema.learning.update;

import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.library.ManifestPages;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.platform.jobs.JobState;
import app.mnema.learning.platform.jobs.JobTestAccess;
import app.mnema.learning.platform.jobs.LearningJobs;
import app.mnema.learning.storage.ImmutableStorage;
import app.mnema.learning.study.session.StudySessionService;
import app.mnema.learning.support.PostgresIntegrationTest;
import app.mnema.learning.support.SharedScopeFixture.Copy;
import app.mnema.learning.support.StudyFixtures.Material;
import app.mnema.learning.update.CombinedPublication.Queued;
import app.mnema.learning.update.CombinedUnits.ExerciseOp;
import app.mnema.learning.update.CombinedUnits.ItemOp;
import app.mnema.learning.update.CombinedUnits.ObjectiveOp;
import app.mnema.learning.update.CombinedWorlds.Authored;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static app.mnema.learning.support.StudyFixtures.blocks;
import static app.mnema.learning.support.StudyFixtures.text;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Zero and H of the combined publication (community decks architecture §12), measured on a real PostgreSQL with the defaults: a copy of a 300-material deck with 100 exercises adopts
 * 100 changed materials and 20 exercises in one request; a copy adopts 1 000 new materials as a job in slices of 200. The numbers are printed
 * ({@code combined-publication-scale:}) and quoted in the guide. The asserts check the RESULT (the copy's manifests are the source's), not the time.
 */
@SpringBootTest(properties = {"learning.jobs.sweep-interval=PT5M", "spring.datasource.hikari.maximum-pool-size=20"})
class CombinedPublicationScaleIntegrationTest extends PostgresIntegrationTest {
    @Autowired private ApplicationContext context;
    @Autowired private CombinedPublication publication;
    @Autowired private CombinedPublicationJob handler;
    @Autowired private LearningJobs jobs;
    @Autowired private DeckService decks;
    @Autowired private ItemService items;
    @Autowired private ExerciseService exercises;
    @Autowired private StudySessionService sessions;
    @Autowired private MediaCatalog media;
    @Autowired private ImmutableStorage storage;
    @Autowired private JdbcClient jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private ManifestPages manifests;

    private CombinedWorlds worlds() {
        CombinedWorlds worlds = new CombinedWorlds(decks, items, exercises, sessions, media, storage, jdbc, transactions, manifests);
        worlds.cancelPendingJobs();
        return worlds;
    }

    private static long ms(long started) { return (System.nanoTime() - started) / 1_000_000; }

    @Test
    void aHundredUnitsAreAppliedInOneRequestToACopyOfATripleHundredMaterialDeck() {
        CombinedWorlds worlds = worlds();
        Material first = worlds.study.material();
        UUID author = first.actor();
        UUID source = first.deck();
        long built = System.nanoTime();
        List<UUID[]> created = worlds.createMany(author, source, 299, "scale");
        List<Authored> authored = new ArrayList<>();
        Material target = null;
        for (int index = 0; index < 100; index++) {
            // an exercise on each of the first 100 materials (the first one of the deck and the first 99 bulk ones)
            Material material = index == 0 ? first : new Material(author, source, created.get(index - 1)[0], created.get(index - 1)[1], null, null, null, null);
            authored.add(Authored.of(worlds.study.publish(material, worlds.study.freeResponse(material, blocks(text("Q " + index)), blocks(), "a" + index), "Goal " + index)));
            if (index == 0) target = material;
        }
        assertThat(target).isNotNull();
        Copy copy = worlds.copyOf(source, UUID.randomUUID());
        System.out.printf("combined-publication-scale: built a 300-material deck with 100 exercises and its copy in %d ms%n", ms(built));

        // zero: nothing to apply
        UUID head = worlds.head(copy.deckId()).revisionId();
        long empty = System.nanoTime();
        CombinedResult nothing = publication.publish(new CombinedUnits(UUID.randomUUID(), copy.deckId(), copy.owner(), head, null, List.of(), List.of(), List.of())).result();
        System.out.printf("combined-publication-scale: zero units: %d ms (changed=%s)%n", ms(empty), nothing.changed());
        assertThat(nothing.changed()).isFalse();

        // the author changes 60 materials (ordinals 1..60), and revises 20 exercises (on the first 20 bulk materials: they are the ones the author edited)
        long edited = System.nanoTime();
        List<UUID> revisions = worlds.saveMany(author, source, created.subList(0, 60), 1, "changed");
        List<Authored> revised = new ArrayList<>();
        for (int index = 1; index <= 20; index++) {
            Material material = new Material(author, source, created.get(index - 1)[0], revisions.get(index - 1), null, null, null, null);
            revised.add(worlds.revise(material, authored.get(index), "Revised " + index));
        }
        System.out.printf("combined-publication-scale: the author's 60 saves and 20 exercise revisions took %d ms%n", ms(edited));

        List<ItemOp> itemOps = new ArrayList<>();
        for (int index = 0; index < 60; index++) itemOps.add(new ItemOp.AdoptReplace(created.get(index)[0], 1 + index, revisions.get(index)));
        List<ExerciseOp> exerciseOps = new ArrayList<>();
        List<ObjectiveOp> objectiveOps = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            exerciseOps.add(new ExerciseOp.AdoptReplace(revised.get(index).exerciseId(), 1 + index, revised.get(index).revisionId()));
        }
        // an exercise and the objective head it pins move together (the objective head is the newest revision a head exercise pins): 60 + 20 + 20 = 100 units
        for (int index = 0; index < 20; index++) objectiveOps.add(new ObjectiveOp.Adopt(revised.get(index).objectiveId(), revised.get(index).objectiveRevisionId()));
        CombinedUnits units = new CombinedUnits(UUID.randomUUID(), copy.deckId(), copy.owner(), head, null, itemOps, exerciseOps, objectiveOps);
        assertThat(units.unitCount()).isEqualTo(100);
        long started = System.nanoTime();
        CombinedResult result = publication.publish(units).result();
        long applied = ms(started);
        System.out.printf("combined-publication-scale: H 100 units (60 materials, 20 exercises, 20 objectives) on a 300-material deck with 100 exercises: %d ms in one request%n", applied);
        assertThat(result.changed()).isTrue();
        assertThat(result.itemsChanged()).isEqualTo(60);
        assertThat(result.exercisesChanged()).isEqualTo(20);
        assertThat(result.objectivesChanged()).isEqualTo(20);
        assertThat(worlds.revisions(copy.deckId())).isEqualTo(2);
        assertThat(worlds.members(copy.deckId())).isEqualTo(worlds.members(source));
        assertThat(worlds.exercises(copy.deckId())).isEqualTo(worlds.exercises(source));
    }

    @Test
    void aThousandUnitsAreAppliedAsAJobInSlicesOfTwoHundredPageEdits() {
        CombinedWorlds worlds = worlds();
        Material first = worlds.study.material();
        UUID author = first.actor();
        UUID source = first.deck();
        // the copy is made while the source has one material; the author then adds a thousand, and the copy adopts all of them in one command (1 000 insertions)
        Copy copy = worlds.copyOf(source, UUID.randomUUID());
        long built = System.nanoTime();
        List<UUID[]> created = worlds.createMany(author, source, 1_000, "big");
        System.out.printf("combined-publication-scale: the author's 1 000 new materials took %d ms%n", ms(built));

        List<ItemOp> itemOps = new ArrayList<>();
        for (int index = 0; index < 1_000; index++) itemOps.add(new ItemOp.AdoptInsert(created.get(index)[0], 1 + index, created.get(index)[1]));
        CombinedUnits units = new CombinedUnits(UUID.randomUUID(), copy.deckId(), copy.owner(), worlds.head(copy.deckId()).revisionId(), null, itemOps, List.of(), List.of());
        long enqueue = System.nanoTime();
        UUID job = ((Queued) publication.enqueue(units)).jobId();
        long enqueueMs = ms(enqueue);
        long ran = System.nanoTime();
        try (JobTestAccess worker = JobTestAccess.around(context, handler)) {
            assertThat(worker.runOne(CombinedPublication.QUEUE)).isTrue();
        }
        long jobMs = ms(ran);
        System.out.printf("combined-publication-scale: H 1 000 units (insertions) as a job: enqueue %d ms; 5 slices of 200 edits and the final transaction %d ms in all%n", enqueueMs, jobMs);
        assertThat(jobs.status(job).orElseThrow().state()).isEqualTo(JobState.SUCCEEDED);
        assertThat(worlds.revisions(copy.deckId())).isEqualTo(2);
        assertThat(worlds.members(copy.deckId())).isEqualTo(worlds.members(source));
        assertThat(worlds.head(copy.deckId()).memberCount()).isEqualTo(1_001);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.deck_item_change WHERE deck_id=:d AND change_kind='create'").param("d", copy.deckId()).query(Long.class).single())
                .isEqualTo(1_000L);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.deck_head_item WHERE deck_id=:d").param("d", copy.deckId()).query(Long.class).single()).isEqualTo(1_001L);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.storage_pin WHERE reuse_scope_id=:s AND pin_kind='staging' AND actor_id=:a").param("s", copy.scope())
                .param("a", copy.owner()).query(Long.class).single()).isZero();
    }
}
