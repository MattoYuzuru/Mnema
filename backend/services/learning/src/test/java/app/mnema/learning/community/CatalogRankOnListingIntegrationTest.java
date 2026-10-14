package app.mnema.learning.community;

import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.exercise.ExerciseType;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.community.CatalogWorlds.World;
import app.mnema.learning.library.DeckModeration;
import app.mnema.learning.library.ManifestPages;
import app.mnema.learning.library.PublicationService;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.platform.jobs.LearningJobs;
import app.mnema.learning.storage.ImmutableStorage;
import app.mnema.learning.study.session.StudySessionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A deck that has just become listed must reach «Популярные» and «Учат сейчас» within about a minute, not up to an hour: the projector, finding a deck newly listed (its first listing or a
 * re-listing after a hide), enqueues the system rank job with the dedupe key of the current UTC minute ({@code rank:2026-10-12T10:42}), so there is at most one extra generation a minute.
 * A deck that is still listed, or not listed at all, enqueues nothing; the hourly job stays.
 */
@SpringBootTest(properties = "learning.community.rank.enabled=true")
class CatalogRankOnListingIntegrationTest extends IsolatedCatalogDatabase {
    private static final String MINUTE_KEY = "SELECT dedupe_key FROM app_learning.learning_job WHERE queue = 'catalog.rank' AND dedupe_key ~ '^rank:[0-9T:-]+:[0-9]{2}$' ORDER BY dedupe_key";

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
    @Autowired private ProjectionRepository projection;
    @Autowired private ManifestPages manifests;
    @Autowired private CatalogSettings settings;
    @Autowired private RankSettings rank;
    @Autowired private LearningJobs jobs;
    private CatalogWorlds worlds;
    private TestClock clock;
    private CatalogProjector projector;

    @BeforeEach
    void setUp() {
        worlds = new CatalogWorlds(decks, items, exercises, sessions, media, storage, jdbc, transactions, publication, moderation);
        reset(jdbc);
        jdbc.sql("DELETE FROM app_learning.learning_job WHERE queue = 'catalog.rank'").update();
        clock = new TestClock(Instant.parse("2026-10-12T10:42:10Z"));
        projector = new CatalogProjector(projection, manifests, settings, new SimpleMeterRegistry(), jobs, rank, clock);
    }

    private World deck(int materials) {
        World world = worlds.deck("Колода " + java.util.UUID.randomUUID().toString().substring(0, 6), "Описание", materials);
        if (materials >= 10) worlds.exercise(world.materials().getFirst(), ExerciseType.CHOICE);
        worlds.publish(world);
        return world;
    }

    private List<String> keys() {
        return jdbc.sql(MINUTE_KEY).query(String.class).list();
    }

    @Test
    void aNewlyListedDeckAsksForARankWithinTheMinuteAndAtMostOnePerMinute() {
        World below = deck(3);
        worlds.project(projector, below.deck());
        assertThat(keys()).as("a deck below the threshold is not listed").isEmpty();

        World first = deck(10);
        worlds.project(projector, first.deck());
        assertThat(keys()).containsExactly("rank:2026-10-12T10:42");
        // another deck in the same minute: the same job, no second generation
        World second = deck(10);
        worlds.project(projector, second.deck());
        assertThat(keys()).containsExactly("rank:2026-10-12T10:42");
        // projecting a deck that is already listed again (a republication, a replay) asks for nothing, even in a new minute
        clock.advance(Duration.ofMinutes(1));
        worlds.project(projector, first.deck());
        assertThat(keys()).containsExactly("rank:2026-10-12T10:42");
        // the next minute and a new listing: a new job
        World third = deck(10);
        worlds.project(projector, third.deck());
        assertThat(keys()).containsExactly("rank:2026-10-12T10:42", "rank:2026-10-12T10:43");
        // a deck hidden and shown again is a re-listing: it asks again, in its minute
        worlds.hide(first);
        worlds.project(projector, first.deck());
        clock.advance(Duration.ofMinutes(2));
        worlds.unhide(first);
        worlds.project(projector, first.deck());
        assertThat(keys()).containsExactly("rank:2026-10-12T10:42", "rank:2026-10-12T10:43", "rank:2026-10-12T10:45");
        // the job is a system job of the rank queue with a payload that names the minute, due at once
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.learning_job WHERE queue = 'catalog.rank' AND account_id IS NULL AND state = 'QUEUED'").query(Long.class).single()).isEqualTo(3);
    }

    @Test
    void theRankJobItselfPutsTheNewDeckIntoThePopularListOfTheNextGeneration() {
        World world = deck(10);
        worlds.project(projector, world.deck());
        assertThat(keys()).hasSize(1);
        CatalogRank job = new CatalogRank(new RankRepository(jdbc), RankSettings.defaults(), new SimpleMeterRegistry(), clock);
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status ->
                job.run(new app.mnema.learning.platform.jobs.Job(java.util.UUID.randomUUID(), CatalogRank.QUEUE, null, tools.jackson.databind.node.JsonNodeFactory.instance.objectNode(), null, 0, 1)));
        assertThat(jdbc.sql("SELECT rank FROM app_learning.deck_rank r JOIN app_learning.rank_generation g USING (generation) WHERE r.deck_id = :d AND g.completed_at IS NOT NULL").param("d", world.deck())
                .query(Integer.class).single()).isEqualTo(1);
    }
}
