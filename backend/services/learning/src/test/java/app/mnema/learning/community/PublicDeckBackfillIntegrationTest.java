package app.mnema.learning.community;

import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.exercise.ExerciseType;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.community.CatalogWorlds.World;
import app.mnema.learning.library.DeckModeration;
import app.mnema.learning.library.DeckVisibility;
import app.mnema.learning.library.PublicationService;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.platform.jobs.Job;
import app.mnema.learning.platform.jobs.JobState;
import app.mnema.learning.platform.jobs.JobTestAccess;
import app.mnema.learning.platform.jobs.LearningJobs;
import app.mnema.learning.platform.jobs.PermanentJobException;
import app.mnema.learning.platform.jobs.Slice;
import app.mnema.learning.storage.ImmutableStorage;
import app.mnema.learning.study.session.StudySessionService;
import app.mnema.learning.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Community/1 (#448): decks that were public before the projector existed have no row; migration {@code V67} enqueues ONE system job {@code catalog.rebuild} and the rebuild hands
 * every PUBLIC deck, and every deck that still has a row, to the projector. The rebuild is also the documented repair tool for any divergence of {@code public_deck} from the
 * publications. Here the jobs run through the real executor code ({@link JobTestAccess}: claim, slices, cursor, lease), in slices of 2 decks.
 */
@SpringBootTest(properties = "learning.community.catalog.rebuild-size=2")
class PublicDeckBackfillIntegrationTest extends PostgresIntegrationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired private ApplicationContext context;
    @Autowired private LearningJobs jobs;
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
    @Autowired private CatalogRebuild rebuild;
    @Autowired private CatalogRepository catalog;
    private CatalogWorlds worlds;

    @BeforeEach
    void worlds() {
        worlds = new CatalogWorlds(decks, items, exercises, sessions, media, storage, jdbc, transactions, publication, moderation);
        jdbc.sql("UPDATE app_learning.learning_job SET state = 'CANCELLED', finished_at = clock_timestamp() WHERE queue LIKE 'catalog.%' AND state = 'QUEUED'").update();
    }

    private World publicDeck(String title, int exercisesCount) {
        World world = worlds.deck(title, "Описание", 10);
        for (int index = 0; index < exercisesCount; index++) worlds.exercise(world.materials().get(index), ExerciseType.CLOZE);
        worlds.publish(world);
        return world;
    }

    @Test
    void theMigrationEnqueuedOneBackfillJobAndEnqueueingItAgainAddsNothing() {
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.learning_job WHERE queue = 'catalog.rebuild' AND dedupe_key = 'v67-backfill'").query(Long.class).single()).isOne();
        assertThat(jdbc.sql("SELECT account_id IS NULL AND payload ->> 'reason' = 'v67-backfill' AND max_attempts = 5 FROM app_learning.learning_job WHERE dedupe_key = 'v67-backfill'")
                .query(Boolean.class).single()).as("a system job with the default attempts").isTrue();
        // the very statement of the migration, run again, is a no-op
        String migration = migration();
        String insert = migration.substring(migration.indexOf("INSERT INTO app_learning.learning_job"));
        assertThat(insert).contains("ON CONFLICT (queue, dedupe_key) DO NOTHING");
        jdbc.sql(insert.replace(";", "").trim()).update();
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.learning_job WHERE queue = 'catalog.rebuild' AND dedupe_key = 'v67-backfill'").query(Long.class).single()).isOne();
    }

    private static String migration() {
        try (var stream = PublicDeckBackfillIntegrationTest.class.getResourceAsStream("/db/learning/migration/V67__public_deck.sql")) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    @Test
    void theRebuildBringsBackEveryPublicDeckAndRemovesWhatNoLongerBelongs() {
        // five public decks projected the normal way, then the table loses their rows: the state of a database that had public decks before V67
        List<World> publics = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            World world = publicDeck("Публичная " + index, 1);
            worlds.project(projector, world.deck());
            publics.add(world);
        }
        World link = publicDeck("По ссылке", 1);
        worlds.setLevel(link, DeckVisibility.LINK);
        World stale = publicDeck("Устаревшая", 1);
        worlds.project(projector, stale.deck());
        // its level is lowered by a write that enqueued nothing (a lost job): the row is stale
        jdbc.sql("UPDATE app_learning.deck_publication SET visibility = 'LINK', row_version = row_version + 1 WHERE deck_id = :d").param("d", stale.deck()).update();
        for (World world : publics) jdbc.sql("DELETE FROM app_learning.public_deck WHERE deck_id = :d").param("d", world.deck()).update();
        assertThat(catalog.find(stale.deck())).isPresent();
        assertThat(publics).allSatisfy(world -> assertThat(catalog.find(world.deck())).isEmpty());

        UUID rebuildJob = jobs.enqueue(CatalogOutbox.REBUILD_QUEUE, "test-rebuild-" + UUID.randomUUID(), null, JSON.createObjectNode().put("reason", "test"));
        try (JobTestAccess access = JobTestAccess.around(context, projector, rebuild)) {
            assertThat(access.runOne(CatalogOutbox.REBUILD_QUEUE)).isTrue();
            assertThat(jobs.status(rebuildJob).orElseThrow().state()).isEqualTo(JobState.SUCCEEDED);
            // the hand-over is in the pages of two decks and ends with the figure of the decks it handed over
            assertThat(jobs.status(rebuildJob).orElseThrow().progressCount()).isGreaterThanOrEqualTo(6);
            assertThat(jobs.status(rebuildJob).orElseThrow().attempts()).isZero();
            for (World world : publics) {
                assertThat(jdbc.sql("SELECT count(*) FROM app_learning.learning_job WHERE queue = 'catalog.project' AND dedupe_key = :k")
                        .param("k", world.deck() + ":rebuild:" + rebuildJob).query(Long.class).single()).as("one projection job per deck").isOne();
            }
            assertThat(jdbc.sql("SELECT count(*) FROM app_learning.learning_job WHERE queue = 'catalog.project' AND dedupe_key = :k")
                    .param("k", link.deck() + ":rebuild:" + rebuildJob).query(Long.class).single()).as("a by-link deck without a row is not handed over").isZero();
            // the projector runs the jobs it was handed
            int ran = 0;
            while (access.runOne(CatalogOutbox.PROJECT_QUEUE)) ran++;
            assertThat(ran).isGreaterThanOrEqualTo(6);
        }
        for (World world : publics) {
            PublicDeckRow row = catalog.find(world.deck()).orElseThrow();
            assertThat(row.projectedRevisionId()).isEqualTo(row.publishedRevisionId());
            assertThat(row.mechanics()).containsExactly(CatalogMechanic.WRITTEN);
        }
        assertThat(catalog.find(stale.deck())).as("a stale row is removed by the rebuild").isEmpty();
        assertThat(catalog.find(link.deck())).isEmpty();
    }

    @Test
    void aHealthyCatalogueIsLookedAtAgainWithoutReadingAManifest() {
        World world = publicDeck("Здоровая", 1);
        worlds.project(projector, world.deck());
        PublicDeckRow before = catalog.find(world.deck()).orElseThrow();
        UUID rebuildJob = jobs.enqueue(CatalogOutbox.REBUILD_QUEUE, "test-healthy-" + UUID.randomUUID(), null, JSON.createObjectNode());
        try (JobTestAccess access = JobTestAccess.around(context, projector, rebuild)) {
            assertThat(access.runOne(CatalogOutbox.REBUILD_QUEUE)).isTrue();
            while (access.runOne(CatalogOutbox.PROJECT_QUEUE)) {
                // drain
            }
        }
        assertThat(jobs.status(rebuildJob).orElseThrow().state()).isEqualTo(JobState.SUCCEEDED);
        assertThat(catalog.find(world.deck())).as("nothing the row is built from changed, so nothing is written").contains(before);
    }

    @Test
    void theHandOverIsIdempotentWhenTheRebuildRunsAgainFromItsCursor() {
        World world = publicDeck("Повторная", 1);
        UUID rebuildId = UUID.randomUUID();
        // the slices of a killed worker are repeated from its last committed cursor: the same keys, the same payloads, nothing new
        for (int round = 0; round < 2; round++) {
            ObjectNode cursor = null;
            long count = 0;
            while (true) {
                Slice slice = worlds.run(rebuild, new Job(rebuildId, CatalogOutbox.REBUILD_QUEUE, null, JSON.createObjectNode(), cursor, count, 1));
                if (slice instanceof Slice.Done) break;
                cursor = ((Slice.Continue) slice).progress();
                count = ((Slice.Continue) slice).progressCount();
            }
        }
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.learning_job WHERE queue = 'catalog.project' AND dedupe_key = :k")
                .param("k", world.deck() + ":rebuild:" + rebuildId).query(Long.class).single()).isOne();
        assertThat(rebuild.queue()).isEqualTo("catalog.rebuild");
    }

    @Test
    void aCorruptCursorOfTheRebuildIsPermanent() {
        for (ObjectNode cursor : List.of(JSON.createObjectNode(), JSON.createObjectNode().put("after", "x"))) {
            assertThatThrownBy(() -> worlds.run(rebuild, new Job(UUID.randomUUID(), CatalogOutbox.REBUILD_QUEUE, null, JSON.createObjectNode(), cursor, 0, 1)))
                    .as(cursor.toString()).isInstanceOf(PermanentJobException.class).hasMessageContaining("cursor_invalid");
        }
    }

    @Test
    void aPublicationThatIsRunByTheRealExecutorCodeProjectsTheDeck() {
        World world = publicDeck("Через исполнитель", 2);
        try (JobTestAccess access = JobTestAccess.around(context, projector, rebuild)) {
            while (access.runOne(CatalogOutbox.PROJECT_QUEUE)) {
                // drain: the job the publication enqueued is the only one due
            }
        }
        PublicDeckRow row = catalog.find(world.deck()).orElseThrow();
        assertThat(row.projectedRevisionId()).isEqualTo(row.publishedRevisionId());
        assertThat(row.exerciseCount()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT state FROM app_learning.learning_job WHERE queue = 'catalog.project' AND dedupe_key LIKE :k")
                .param("k", world.deck() + ":%").query(String.class).list()).containsOnly("SUCCEEDED");
    }
}
