package app.mnema.learning.community;

import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.exercise.ExerciseType;
import app.mnema.learning.fork.ForkCommand;
import app.mnema.learning.fork.ForkService;
import app.mnema.learning.fork.ForkMaterializer;
import app.mnema.learning.library.DeckModeration;
import app.mnema.learning.library.PublicationService;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.platform.jobs.Job;
import app.mnema.learning.platform.jobs.Slice;
import app.mnema.learning.platform.security.IdentityClaims.AccountSignals;
import app.mnema.learning.profile.LearningGoal;
import app.mnema.learning.profile.LearningProfileService;
import app.mnema.learning.storage.ImmutableStorage;
import app.mnema.learning.study.session.StudySessionService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
    "learning.community.public-routes.enabled=true", "learning.community.forks.enabled=true", "learning.community.events.enabled=true", "learning.community.for-you.enabled=true"
})
class RecommendationSignalsIntegrationTest extends IsolatedCatalogDatabase {

    @Autowired private DeckService decks;
    @Autowired private ItemService items;
    @Autowired private ExerciseService exercises;
    @Autowired private StudySessionService sessions;
    @Autowired private MediaCatalog media;
    @Autowired private ImmutableStorage storage;
    @Autowired private PublicationService publication;
    @Autowired private DeckModeration moderation;
    @Autowired private CatalogProjector projector;
    @Autowired private ForkService forks;
    @Autowired private ForkMaterializer materializer;
    @Autowired private CatalogEvents events;
    @Autowired private InterestService interests;
    @Autowired private InterestRepository repository;
    @Autowired private InterestRebuild rebuild;
    @Autowired private LearningProfileService goals;
    @Autowired private JdbcClient jdbc;
    @Autowired private PlatformTransactionManager transactions;
    private CatalogWorlds worlds;

    @BeforeEach void fixture() {
        reset(jdbc);
        worlds = new CatalogWorlds(decks, items, exercises, sessions, media, storage, jdbc, transactions, publication, moderation);
    }

    private CatalogWorlds.World source() {
        var world = worlds.deck("Испанский источник", "Публичное описание", 10);
        worlds.exercise(world.materials().getFirst(), ExerciseType.FREE_RESPONSE);
        worlds.publish(world);
        worlds.project(projector, world.deck());
        return world;
    }

    private String code(UUID deck) {
        return jdbc.sql("SELECT public_code FROM app_learning.public_deck WHERE deck_id=:d").param("d", deck).query(String.class).single();
    }

    private UUID copy(UUID account, CatalogWorlds.World source) {
        var command = new ForkCommand(UUID.randomUUID(), code(source.deck()));
        var created = forks.create(account, command, new AccountSignals(true, false));
        assertThat(forks.create(account, command, new AccountSignals(true, false)).replayed()).isTrue();
        assertThat(forks.create(account, new ForkCommand(UUID.randomUUID(), code(source.deck())), new AccountSignals(true, false)).existing()).isTrue();
        return UUID.fromString(created.acknowledgement().path("deckId").stringValue());
    }

    private void ready(UUID copy) {
        ObjectNode progress = null;
        long count = 0;
        for (int i = 0; i < 100; i++) {
            Job job = new Job(UUID.randomUUID(), ForkService.QUEUE, null, JsonNodeFactory.instance.objectNode().put("deckId", copy.toString()), progress, count, 1);
            Slice slice = worlds.run(materializer, job);
            if (slice instanceof Slice.Done) return;
            Slice.Continue more = (Slice.Continue)slice;
            progress = more.progress();
            count = more.progressCount();
        }

        throw new AssertionError("copy did not finish");
    }

    @Test void successfulLifecycleSignalsOnlyOnceWithInheritedVersionAndDetachedCopiesCount() {
        var source = source();
        UUID account = UUID.randomUUID();
        ObjectNode envelope = JsonNodeFactory.instance.objectNode().put("commandId", UUID.randomUUID().toString()).put("code", code(source.deck())).put("kind", "DETAIL_VIEW")
                .put("surface", "for-you").put("position", 1).put("rankVersion", "recs-v1");
        events.event(account, new RecommendationCommands.Event(UUID.fromString(envelope.path("commandId").stringValue()), "DETAIL_VIEW", code(source.deck()), "for-you", 1, "recs-v1", envelope));
        UUID copy = copy(account, source);
        ready(copy);
        forks.detach(account, copy);
        assertThat(interests.profile(account).path("inferredTopics").get(0).path("id").stringValue()).isEqualTo("spanish");
        var command = worlds.study.start("SCHEDULED", null);
        var started = sessions.start(account, copy, "UTC", command);
        assertThat(sessions.start(account, copy, "UTC", command).replayed()).isTrue();
        assertThat(started.body().path("status").stringValue()).isIn("ACTIVE", "PREPARING");
        var practice = worlds.study.start("PRACTICE", null);
        sessions.start(account, copy, "UTC", practice);
        UUID session = UUID.fromString(started.body().path("sessionId").stringValue());
        while (sessions.read(account, copy, session).path("status").stringValue().equals("PREPARING")) { }
        // Completion is a fixture here; attempts and scheduler credit have their own DB tests.
        jdbc.sql("UPDATE app_learning.study_session SET status='COMPLETE',completed_at=statement_timestamp(),batch_size=0,row_version=row_version+1 WHERE session_id=:s")
                .param("s", session).update();
        sessions.start(account, copy, "UTC", worlds.study.start("REPLAY", session));
        decks.delete(account, copy, Long.parseLong(decks.read(account, copy).path("rowVersion").stringValue()));
        assertThat(jdbc.sql("SELECT kind FROM app_learning.catalog_event WHERE account_id=:a ORDER BY occurred_at,event_id").param("a", account).query(String.class).list())
                .containsExactly("DETAIL_VIEW", "FORK", "STUDY_START", "STUDY_START", "DROP");
        assertThat(jdbc.sql("SELECT rank_version FROM app_learning.catalog_event WHERE account_id=:a").param("a", account).query(String.class).list()).containsOnly("recs-v1");
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.catalog_event WHERE account_id=:a AND kind IN ('FORK','STUDY_START','DROP') AND position IS NULL").param("a", account).query(Long.class).single()).isEqualTo(4);
    }

    @Test void resetExcludesExistingCopiesGoalAndQueuedOldRebuildButNewGoalContributes() {
        UUID account = UUID.randomUUID();
        var source = source();
        copy(account, source);
        goals.answer(account, LearningGoal.LANGUAGE);
        JsonNode profile = interests.profile(account);
        assertThat(profile.path("inferredTopics")).isNotEmpty();
        UUID command = UUID.randomUUID();
        ObjectNode body = JsonNodeFactory.instance.objectNode().put("commandId", command.toString());
        interests.reset(account, Long.parseLong(profile.path("rowVersion").stringValue()), command, body);
        worlds.run(rebuild, new Job(UUID.randomUUID(), InterestRebuild.QUEUE, null, JsonNodeFactory.instance.objectNode().put("day", "yesterday"), null, 0, 1));
        assertThat(interests.profile(account).path("inferredTopics")).isEmpty();
        goals.answer(account, LearningGoal.WORK);
        assertThat(interests.profile(account).path("inferredTopics").get(0).path("id").stringValue()).isEqualTo("professional");
    }

    @Test void rolledBackEffectCannotLeaveAnEventOrProfile() {
        UUID account = UUID.randomUUID();
        var source = source();
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            events.forked(account, source.deck());  throw new IllegalStateException("synthetic rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.catalog_event WHERE account_id=:a").param("a", account).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.community_interest_profile WHERE account_id=:a").param("a", account).query(Long.class).single()).isZero();
    }

    @Test void profileLockSerializesResetAndDelayedRebuild() throws Exception {
        UUID account = UUID.randomUUID();
        goals.answer(account, LearningGoal.LANGUAGE);
        long version = Long.parseLong(interests.profile(account).path("rowVersion").stringValue());
        var locked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        try(var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var reset = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                repository.lock(account);  locked.countDown();
                try {
                    if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("fixture timed out");
                }
                catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();  throw new IllegalStateException(failure);
                }

                repository.reset(account);
            }));
            assertThat(locked.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var delayed = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> interests.rebuild(account)));
            release.countDown();
            reset.get(10, java.util.concurrent.TimeUnit.SECONDS);
            delayed.get(10, java.util.concurrent.TimeUnit.SECONDS);
        }

        JsonNode after = interests.profile(account);
        assertThat(after.path("inferredTopics")).isEmpty();
        assertThat(Long.parseLong(after.path("rowVersion").stringValue())).isEqualTo(version + 1);
    }
    @Test void effectiveGoalAnswersBumpOnceEvenWhenNormalizedGoalInterestIsUnchanged() {
        UUID account = UUID.randomUUID();
        goals.answer(account, LearningGoal.LANGUAGE);
        long before = Long.parseLong(interests.profile(account).path("rowVersion").stringValue());
        var normalized = repository.interests(account);
        assertThat(normalized).containsExactlyEntriesOf(java.util.Map.of("languages", 1.0));
        goals.answer(account, LearningGoal.LANGUAGE);
        assertThat(repository.interests(account)).isEqualTo(normalized);
        assertThat(Long.parseLong(interests.profile(account).path("rowVersion").stringValue())).isEqualTo(before + 1);
        new TransactionTemplate(transactions).executeWithoutResult(status -> interests.rebuild(account));
        assertThat(Long.parseLong(interests.profile(account).path("rowVersion").stringValue())).isEqualTo(before + 1);
    }

}
