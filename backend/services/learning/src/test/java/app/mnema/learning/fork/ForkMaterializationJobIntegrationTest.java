package app.mnema.learning.fork;

import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.fork.ForkWorlds.Source;
import app.mnema.learning.library.ManifestPages;
import app.mnema.learning.media.MediaCatalog;
import tools.jackson.databind.JsonNode;
import app.mnema.learning.platform.jobs.Job;
import app.mnema.learning.platform.jobs.JobHandler;
import app.mnema.learning.platform.jobs.JobState;
import app.mnema.learning.platform.jobs.JobTestAccess;
import app.mnema.learning.platform.jobs.LearningJobs;
import app.mnema.learning.platform.jobs.PermanentJobException;
import app.mnema.learning.platform.jobs.Slice;
import app.mnema.learning.storage.ImmutableStorage;
import app.mnema.learning.study.session.StudySessionService;
import app.mnema.learning.support.HttpIdentityFixture;
import app.mnema.learning.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Share/10 (#432), the materialization job under the executor's hands: no process executor runs here (the default test role is {@code api}); a worker is built by hand
 * around the real {@link ForkMaterializer}, so every claim, kill and lease expiry happens in a known order. A deck of a thousand materials is copied in slices of 150: the
 * worker is killed in the middle of the third slice (after the handler wrote its rows, before the transaction committed), the lease expires, another worker resumes from
 * the last committed cursor, and the copy ends with exactly the published manifest, completed once.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"learning.community.forks.slice-size=150", "learning.jobs.sweep-interval=PT5M", "spring.datasource.hikari.maximum-pool-size=20"})
class ForkMaterializationJobIntegrationTest extends PostgresIntegrationTest {
    private static final String QUEUE = "deck.copy.materialize";
    private static final int MATERIALS = 1_000;
    private static final int EXERCISES = 60;

    /** A worker killed in the middle of a slice: not an exception any handler could catch. */
    private static final class SimulatedKill extends Error {
        private static final long serialVersionUID = 1L;
    }

    @LocalServerPort private int port;
    @Autowired private ApplicationContext context;
    @Autowired private ForkMaterializer materializer;
    @Autowired private ForkService forks;
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

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        HttpIdentityFixture.register(registry);
        registry.add("learning.community.forks.enabled", () -> true);
        registry.add("learning.community.public-routes.enabled", () -> true);
    }

    /** The thousand-material deck, built once for the class (it takes a minute): the tests only copy it. */
    private static Source big;

    private synchronized Source big(ForkWorlds worlds) {
        if (big == null) {
            long built = System.nanoTime();
            big = worlds.bigSource("LINK", MATERIALS, EXERCISES);
            System.out.printf("fork-scale: built a deck of %d materials and %d exercises in %d ms%n", MATERIALS, EXERCISES, (System.nanoTime() - built) / 1_000_000);
        }
        return big;
    }

    private ForkWorlds worlds() {
        return new ForkWorlds(port, decks, items, exercises, sessions, media, storage, jdbc, transactions, manifests);
    }

    private UUID jobOf(UUID copy) {
        return jdbc.sql("SELECT job_id FROM app_learning.deck_fork WHERE deck_id = :d").param("d", copy).query(UUID.class).single();
    }

    private long count(String table, UUID copy) {
        return jdbc.sql("SELECT count(*) FROM app_learning." + table + " WHERE deck_id = :d").param("d", copy).query(Long.class).single();
    }

    private boolean materialized(UUID copy) {
        return jdbc.sql("SELECT materialized_at IS NOT NULL FROM app_learning.deck_fork WHERE deck_id = :d").param("d", copy).query(Boolean.class).single();
    }

    @Test
    void aKilledWorkerResumesFromItsLastCursorAndACopyOfAThousandMaterialsIsCompleteExactlyOnce() {
        ForkWorlds worlds = worlds();
        Source source = big(worlds);
        UUID reader = UUID.randomUUID();
        // the author keeps editing after publishing: a head the copy must not see
        worlds.edit(source.author(), source.deck(), source.materials().getFirst().member(), source.materials().getFirst().itemRevision(), "после публикации");

        long started = System.nanoTime();
        UUID copy = worlds.forked(reader, source.code());
        long requestMs = (System.nanoTime() - started) / 1_000_000;
        UUID job = jobOf(copy);
        assertThat(count("deck_head_item", copy)).as("the request writes no head").isZero();
        assertThat(materialized(copy)).isFalse();

        AtomicInteger slices = new AtomicInteger();
        JobHandler dying = new JobHandler() {
            @Override public String queue() { return QUEUE; }
            @Override public Slice run(Job claimed) {
                Slice result = materializer.run(claimed);
                // the effects of the third slice are written, then the process dies before the transaction commits
                if (slices.incrementAndGet() == 3) throw new SimulatedKill();
                return result;
            }
        };
        try (JobTestAccess first = JobTestAccess.around(context, dying); JobTestAccess second = JobTestAccess.around(context, materializer)) {
            assertThat(first.claim(QUEUE)).contains(job);
            assertThatThrownBy(() -> first.run(job)).isInstanceOf(SimulatedKill.class);

            // slices 1 and 2 committed with their cursor, the third rolled back with it
            assertThat(count("deck_head_item", copy)).isEqualTo(300);
            assertThat(count("deck_head_exercise", copy)).isZero();
            assertThat(jobs.status(job).orElseThrow().state()).isEqualTo(JobState.RUNNING);
            assertThat(jobs.status(job).orElseThrow().progress().path("phase").stringValue(null)).isEqualTo("members");
            assertThat(jobs.status(job).orElseThrow().progress().path("next").intValue()).isEqualTo(300);
            assertThat(jobs.status(job).orElseThrow().progressCount()).isEqualTo(300);
            assertThat(materialized(copy)).isFalse();
            assertThat(second.recoverExpired()).as("the dead worker still holds a live lease").isZero();

            second.expireLease(job);
            assertThat(second.recoverExpired()).isEqualTo(1);
            second.makeDue(job);
            long resumed = System.nanoTime();
            assertThat(second.runOne(QUEUE)).isTrue();
            System.out.printf("fork-scale: request %d ms; the resumed run wrote the remaining %d rows in %d ms%n", requestMs, MATERIALS + EXERCISES - 300,
                    (System.nanoTime() - resumed) / 1_000_000);
        }

        var status = jobs.status(job).orElseThrow();
        assertThat(status.state()).isEqualTo(JobState.SUCCEEDED);
        assertThat(status.progressCount()).isEqualTo(MATERIALS + EXERCISES);
        assertThat(materialized(copy)).isTrue();
        assertThat(count("deck_head_item", copy)).isEqualTo(MATERIALS);
        assertThat(count("deck_head_exercise", copy)).isEqualTo(EXERCISES);
        assertThat(count("objective_head", copy)).isEqualTo(EXERCISES);
        worlds.assertHeadsEqualPublishedManifest(source.deck(), copy);
        // the author's later edit is not in the copy
        assertThat(jdbc.sql("SELECT revision_id FROM app_learning.deck_head_item WHERE deck_id = :d AND member_key = :m").param("d", copy)
                .param("m", source.materials().getFirst().member()).query(UUID.class).single()).isEqualTo(source.materials().getFirst().itemRevision());
        // completed once: a second run of the (finished) job writes nothing and the single job row stays
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.learning_job WHERE queue = :q AND dedupe_key = :k").param("q", QUEUE).param("k", copy.toString())
                .query(Long.class).single()).isEqualTo(1);
        assertThat(materializer.run(new Job(job, QUEUE, reader, ForkWorlds.JSON.createObjectNode().put("deckId", copy.toString()), null, 0, 1)).toString())
                .contains("Done");
        assertThat(count("deck_head_item", copy)).isEqualTo(MATERIALS);
    }

    @Test
    void aFailedJobWhoseRecordWasPurgedIsRestartedFromNothingAndTheCopyIsExact() {
        ForkWorlds worlds = worlds();
        Source source = big(worlds);
        UUID reader = UUID.randomUUID();
        UUID copy = worlds.forked(reader, source.code());
        UUID job = jobOf(copy);

        AtomicInteger slices = new AtomicInteger();
        JobHandler failing = new JobHandler() {
            @Override public String queue() { return QUEUE; }
            @Override public Slice run(Job claimed) {
                Slice result = materializer.run(claimed);
                // the effects of the third slice are written, then the handler gives up for good: that slice rolls back, the first two stay
                if (slices.incrementAndGet() == 3) throw new PermanentJobException("injected");
                return result;
            }
        };
        try (JobTestAccess worker = JobTestAccess.around(context, failing)) {
            assertThat(worker.runOne(QUEUE)).isTrue();
        }
        var failed = jobs.status(job).orElseThrow();
        assertThat(failed.state()).isEqualTo(JobState.FAILED);
        assertThat(failed.progress().path("next").intValue()).isEqualTo(300);
        assertThat(count("deck_head_item", copy)).as("two slices committed, the third rolled back").isEqualTo(300);
        assertThat(materialized(copy)).isFalse();
        assertThat(ForkWorlds.json(worlds.get("/decks/" + copy, reader)).path("fork").path("failed").booleanValue(false)).isTrue();

        // retention deletes the finished job: its cursor goes with it, and the retry enqueues the same key afresh
        assertThat(jdbc.sql("DELETE FROM app_learning.learning_job WHERE job_id = :j").param("j", job).update()).isEqualTo(1);
        assertThat(worlds.send("POST", "/decks/" + copy + "/fork:retry", reader, null, null).statusCode()).isEqualTo(200);
        UUID fresh = jobOf(copy);
        assertThat(fresh).isNotEqualTo(job);
        assertThat(jobs.status(fresh).orElseThrow().progress()).as("no cursor").isNull();
        assertThat(count("deck_head_item", copy)).as("the heads of the dead job are still there").isEqualTo(300);

        try (JobTestAccess worker = JobTestAccess.around(context, materializer)) {
            worker.makeDue(fresh);
            assertThat(worker.runOne(QUEUE)).isTrue();
        }
        assertThat(jobs.status(fresh).orElseThrow().state()).isEqualTo(JobState.SUCCEEDED);
        assertThat(jobs.status(fresh).orElseThrow().progressCount()).isEqualTo(MATERIALS + EXERCISES);
        assertThat(materialized(copy)).isTrue();
        assertThat(count("deck_head_item", copy)).isEqualTo(MATERIALS);
        assertThat(count("deck_head_exercise", copy)).isEqualTo(EXERCISES);
        assertThat(count("objective_head", copy)).isEqualTo(EXERCISES);
        worlds.assertHeadsEqualPublishedManifest(source.deck(), copy);
    }

    @Test
    void oneCopyOfAnAccountRunsAtATimeAndAnotherAccountIsNotBlocked() {
        ForkWorlds worlds = worlds();
        Source one = worlds.source("LINK", 3, 1);
        Source two = worlds.source("LINK", 3, 1);
        UUID account = UUID.randomUUID();
        UUID first = worlds.forked(account, one.code());
        UUID second = worlds.forked(account, two.code());
        UUID other = worlds.forked(UUID.randomUUID(), one.code());
        try (JobTestAccess worker = JobTestAccess.around(context, materializer)) {
            Optional<UUID> a = worker.claim(QUEUE);
            assertThat(a).isPresent();
            // the second copy of the same account waits behind the first: the database holds one RUNNING job per (queue, account)
            Optional<UUID> b = worker.claim(QUEUE);
            assertThat(b).as("the only other due job belongs to another account or none").isNotEqualTo(a);
            UUID runningFor = jdbc.sql("SELECT account_id FROM app_learning.learning_job WHERE job_id = :j").param("j", a.orElseThrow()).query(UUID.class).single();
            long running = jdbc.sql("SELECT count(*) FROM app_learning.learning_job WHERE queue = :q AND state = 'RUNNING' AND account_id = :a")
                    .param("q", QUEUE).param("a", runningFor).query(Long.class).single();
            assertThat(running).isEqualTo(1);
            // finish everything that was claimed, then the rest one by one
            worker.run(a.orElseThrow());
            b.ifPresent(worker::run);
            while (worker.runOne(QUEUE)) {
                // drains the queue of this test database
            }
        }
        for (UUID copy : new UUID[] {first, second, other}) {
            assertThat(materialized(copy)).isTrue();
            assertThat(jobs.status(jobOf(copy)).orElseThrow().state()).isEqualTo(JobState.SUCCEEDED);
        }
        assertThat(count("deck_head_item", first)).isEqualTo(3);
    }

    @Test
    void aCopyDeletedWhileItsJobIsQueuedEndsTheJobWithoutWritingAnything() {
        ForkWorlds worlds = worlds();
        Source source = worlds.source("LINK", 3, 1);
        UUID reader = UUID.randomUUID();
        UUID copy = worlds.forked(reader, source.code());
        assertThat(worlds.send("DELETE", "/decks/" + copy, reader, null, "\"0\"").statusCode()).isEqualTo(204);
        try (JobTestAccess worker = JobTestAccess.around(context, materializer)) {
            while (worker.runOne(QUEUE)) {
                // drains
            }
        }
        assertThat(jobs.status(jobOf(copy)).orElseThrow().state()).isEqualTo(JobState.SUCCEEDED);
        assertThat(count("deck_head_item", copy)).isZero();
        assertThat(count("deck_head_exercise", copy)).isZero();
        assertThat(materialized(copy)).isFalse();
    }

    @Test
    void aBrokenManifestFailsTheJobPermanentlyAndTheCopyStaysPreparingAndFailed() {
        ForkWorlds worlds = worlds();
        Source source = worlds.source("LINK", 12, 2);
        UUID reader = UUID.randomUUID();
        UUID copy = worlds.forked(reader, source.code());
        // corrupt the lineage the manifest points at: a published exercise revision whose descriptor no manifest entry matches (the immutability guards are
        // switched off for this one transaction only)
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.sql("SET LOCAL session_replication_role = replica").update();
            assertThat(jdbc.sql("UPDATE app_learning.exercise_revision SET descriptor_root_id = gen_random_uuid() WHERE exercise_id = :e")
                    .param("e", source.exercises().getFirst()).update()).isEqualTo(1);
        });
        try (JobTestAccess worker = JobTestAccess.around(context, materializer)) {
            // the members are fine, the exercise cannot be resolved: the slice fails permanently on its own
            worker.runOne(QUEUE);
        }
        var status = jobs.status(jobOf(copy)).orElseThrow();
        assertThat(status.state()).isEqualTo(JobState.FAILED);
        assertThat(status.lastError()).isEqualTo("manifest_unresolved");
        // the failed slice rolled back as a whole: no half-written head, the copy is not ready and says so
        assertThat(materialized(copy)).isFalse();
        assertThat(count("deck_head_item", copy)).isZero();
        assertThat(count("deck_head_exercise", copy)).isZero();
        JsonNode fork = ForkWorlds.json(worlds.get("/decks/" + copy, reader)).path("fork");
        assertThat(fork.path("preparing").booleanValue(false)).isTrue();
        assertThat(fork.path("failed").booleanValue(false)).isTrue();
        // retrying cannot cure a corrupt manifest: the job fails again, permanently, and the copy can be deleted
        assertThat(worlds.send("POST", "/decks/" + copy + "/fork:retry", reader, null, null).statusCode()).isEqualTo(200);
        try (JobTestAccess worker = JobTestAccess.around(context, materializer)) {
            assertThat(worker.runOne(QUEUE)).isTrue();
        }
        assertThat(jobs.status(jobOf(copy)).orElseThrow().state()).isEqualTo(JobState.FAILED);
        assertThat(worlds.send("DELETE", "/decks/" + copy, reader, null, "\"0\"").statusCode()).isEqualTo(204);
    }
}
