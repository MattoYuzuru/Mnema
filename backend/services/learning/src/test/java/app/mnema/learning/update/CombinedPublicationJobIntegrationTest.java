package app.mnema.learning.update;

import app.mnema.learning.catalog.deck.DeckCommand;
import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.library.ManifestPages;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.concurrency.VersionConflictException;
import app.mnema.learning.platform.idempotency.IdempotencyConflictException;
import app.mnema.learning.platform.jobs.Job;
import app.mnema.learning.platform.jobs.JobHandler;
import app.mnema.learning.platform.jobs.JobState;
import app.mnema.learning.platform.jobs.JobStatus;
import app.mnema.learning.platform.jobs.JobTestAccess;
import app.mnema.learning.platform.jobs.LearningJobs;
import app.mnema.learning.platform.jobs.Slice;
import app.mnema.learning.storage.ImmutableStorage;
import app.mnema.learning.study.session.StudySessionService;
import app.mnema.learning.support.PostgresIntegrationTest;
import app.mnema.learning.support.SharedScopeFixture.Copy;
import app.mnema.learning.support.StudyFixtures.Material;
import app.mnema.learning.update.CombinedPublication.Applied;
import app.mnema.learning.update.CombinedPublication.Queued;
import app.mnema.learning.update.CombinedUnits.ItemOp;
import app.mnema.learning.update.CombinedUnits.Metadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Updates/2 (#441), the job path under the executor's hands: no process executor runs here (the default test role is {@code api}); a worker is built by hand around the real
 * {@link CombinedPublicationJob}, so every claim, kill and lease expiry happens in a known order. A copy that is 34 revisions behind its source adopts all of them in slices of 10 page
 * edits: a worker is killed in the middle of the third slice and another resumes from the last committed cursor; heads, journal, revision and receipt exist only after the last slice,
 * once; a deck that moved meanwhile ends the job {@code stale_head} with nothing written; a refusing {@link FinalWriter} rolls the last slice back and the retry applies it once.
 */
@SpringBootTest(properties = {"learning.updates.slice-size=10", "learning.updates.sync-max-units=20", "learning.jobs.sweep-interval=PT5M", "spring.datasource.hikari.maximum-pool-size=20"})
class CombinedPublicationJobIntegrationTest extends PostgresIntegrationTest {
    private static final String QUEUE = CombinedPublication.QUEUE;
    private static final int EDITED = 34;

    /** A worker killed in the middle of a slice: not an exception any handler could catch. */
    private static final class SimulatedKill extends Error {
        private static final long serialVersionUID = 1L;
    }

    static final class RecordingWriter implements FinalWriter {
        final List<Context> calls = new CopyOnWriteArrayList<>();
        final List<FinalWrites> writes = new CopyOnWriteArrayList<>();
        volatile RuntimeException failure;

        @Override
        public void write(FinalWrites given, Context context) {
            calls.add(context);
            writes.add(given);
            if (failure != null) throw failure;
        }
    }

    @TestConfiguration
    static class Hooks {
        @Bean
        RecordingWriter recordingWriter() { return new RecordingWriter(); }
    }

    @Autowired private ApplicationContext context;
    @Autowired private CombinedPublication publication;
    @Autowired private CombinedPublicationJob handler;
    @Autowired private RecordingWriter writer;
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
    private CombinedWorlds worlds;

    @BeforeEach
    void worlds() {
        worlds = new CombinedWorlds(decks, items, exercises, sessions, media, storage, jdbc, transactions, manifests);
        worlds.cancelPendingJobs();
        writer.calls.clear();
        writer.writes.clear();
        writer.failure = null;
    }

    /** The author's deck with {@code 1 + EDITED} materials, a copy of it made BEFORE the author edited the last {@code EDITED}, and what the copy must adopt. */
    private record Behind(UUID author, UUID source, Copy copy, Material first, List<UUID[]> edited, List<UUID> revisions) {
        UUID owner() { return copy.owner(); }

        UUID deck() { return copy.deckId(); }
    }

    private Behind behind() {
        Material first = worlds.study.material();
        List<UUID[]> created = worlds.createMany(first.actor(), first.deck(), EDITED, "bulk");
        Copy copy = worlds.copyOf(first.deck(), UUID.randomUUID());
        List<UUID> revisions = worlds.saveMany(first.actor(), first.deck(), created, 1, "edited");
        return new Behind(first.actor(), first.deck(), copy, first, created, revisions);
    }

    private CombinedUnits adoptAll(Behind b, Metadata metadata, FinalWrites finalWrites) {
        List<ItemOp> ops = new ArrayList<>();
        for (int index = 0; index < EDITED; index++) ops.add(new ItemOp.AdoptReplace(b.edited().get(index)[0], 1 + index, b.revisions().get(index)));
        return new CombinedUnits(UUID.randomUUID(), b.deck(), b.owner(), worlds.head(b.deck()).revisionId(), metadata, ops, List.of(), List.of(), finalWrites);
    }

    private long stored(String table, UUID command) {
        return jdbc.sql("SELECT count(*) FROM app_learning." + table + " WHERE command_id=:c").param("c", command).query(Long.class).single();
    }

    private JobStatus status(UUID job) { return jobs.status(job).orElseThrow(); }

    @Test
    void aKilledWorkerResumesFromItsLastCursorAndTheCommandIsAppliedExactlyOnceByTheLastSlice() {
        Behind b = behind();
        CombinedUnits units = adoptAll(b, new Metadata("Deck", "Новое описание"), new FinalWrites("fork.apply", CombinedWorlds.JSON.createObjectNode().put("base", "x")));
        String before = worlds.snapshot(b.deck());
        assertThat(units.unitCount()).isEqualTo(EDITED + 1);

        long enqueued = System.nanoTime();
        Queued queued = (Queued) publication.enqueue(units);
        long enqueueMs = (System.nanoTime() - enqueued) / 1_000_000;
        UUID job = queued.jobId();
        assertThat(status(job).state()).isEqualTo(JobState.QUEUED);
        assertThat(status(job).accountId()).isEqualTo(b.owner());
        assertThat(stored("combined_publication", units.commandId())).isOne();
        assertThat(stored("combined_publication_unit", units.commandId())).isEqualTo(EDITED);
        // the same command again is the same job; a different body under the same id is a conflict; a replay needs no second job
        assertThat(((Queued) publication.enqueue(units)).jobId()).isEqualTo(job);
        assertThat(stored("combined_publication_unit", units.commandId())).isEqualTo(EDITED);
        assertThatThrownBy(() -> publication.enqueue(new CombinedUnits(units.commandId(), b.deck(), b.owner(), units.expectedHeadRevisionId(), null,
                units.items().subList(0, 11), List.of(), List.of(), units.finalWrites()))).isInstanceOf(IdempotencyConflictException.class);
        // nothing is written for the deck at enqueue time
        assertThat(worlds.snapshot(b.deck())).isEqualTo(before);

        AtomicInteger slices = new AtomicInteger();
        JobHandler dying = new JobHandler() {
            @Override public String queue() { return QUEUE; }
            @Override public Slice run(Job claimed) {
                Slice result = handler.run(claimed);
                // the effects of the third slice are written, then the process dies before the transaction commits
                if (slices.incrementAndGet() == 3) throw new SimulatedKill();
                return result;
            }
        };
        try (JobTestAccess first = JobTestAccess.around(context, dying); JobTestAccess second = JobTestAccess.around(context, handler)) {
            assertThat(first.claim(QUEUE)).contains(job);
            assertThatThrownBy(() -> first.run(job)).isInstanceOf(SimulatedKill.class);

            // slices 1 and 2 committed with their cursor; the third rolled back with it; no head, journal row, revision or receipt exists
            assertThat(status(job).state()).isEqualTo(JobState.RUNNING);
            assertThat(status(job).progress().path("next").intValue()).isEqualTo(20);
            assertThat(status(job).progressCount()).isEqualTo(20);
            assertThat(worlds.snapshot(b.deck())).isEqualTo(before);
            assertThat(worlds.receipts(units.commandId())).isZero();
            assertThat(writer.calls).isEmpty();
            assertThat(stagingPins(b)).as("the staged roots of the last committed slice are pinned: one pin per manifest root").isEqualTo(2L);
            assertThat(second.recoverExpired()).as("the dead worker still holds a live lease").isZero();

            second.expireLease(job);
            assertThat(second.recoverExpired()).isEqualTo(1);
            second.makeDue(job);
            long resumed = System.nanoTime();
            assertThat(second.runOne(QUEUE)).isTrue();
            System.out.printf("combined-publication-scale: %d units as a job in slices of 10: enqueue %d ms; the resumed run (2 slices + final) %d ms%n", units.unitCount(),
                    enqueueMs, (System.nanoTime() - resumed) / 1_000_000);
        }

        JobStatus done = status(job);
        assertThat(done.state()).isEqualTo(JobState.SUCCEEDED);
        assertThat(done.progressCount()).isEqualTo(units.unitCount());
        // exactly one new revision, the source's manifest, the heads at the adopted revisions, a journal row per member, the receipt, the hook once
        assertThat(worlds.revisions(b.deck())).isEqualTo(2);
        assertThat(worlds.members(b.deck())).isEqualTo(worlds.members(b.source()));
        for (int index = 0; index < EDITED; index++) {
            assertThat(worlds.itemHeads(b.deck())).containsEntry(b.edited().get(index)[0], b.revisions().get(index));
        }
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.deck_item_change WHERE deck_id=:d AND change_kind='save'").param("d", b.deck()).query(Long.class).single())
                .isEqualTo((long) EDITED);
        assertThat(worlds.head(b.deck()).description()).isEqualTo("Новое описание");
        assertThat(worlds.receipts(units.commandId())).isOne();
        assertThat(writer.calls).hasSize(1);
        assertThat(writer.calls.getFirst().changed()).isTrue();
        assertThat(stored("combined_publication", units.commandId())).as("the stored command goes with the job").isZero();
        assertThat(stored("combined_publication_unit", units.commandId())).isZero();
        // no staging pin of the job outlives it (the durable pins of the revision stay)
        assertThat(stagingPins(b)).isZero();

        // sending the command again answers the stored result, with no second job and nothing written
        Applied replay = (Applied) publication.enqueue(units);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.result().changed()).isTrue();
        assertThat(replay.result().itemsChanged()).isEqualTo(EDITED);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.learning_job WHERE queue=:q AND dedupe_key=:k").param("q", QUEUE).param("k", units.commandId().toString())
                .query(Long.class).single()).isOne();
        assertThat(worlds.revisions(b.deck())).isEqualTo(2);
        // a command of this size is not run in the request
        assertThatThrownBy(() -> publication.publish(adoptAll(b, null, null))).isInstanceOf(InvalidRequestException.class);

        // the race with the end of an identical command (its receipt appeared after the replay was looked up): the copy just stored is removed and the result answered
        byte[] digest = new app.mnema.learning.platform.json.CanonicalJsonHasher().hash(units.envelope()).sha256();
        CombinedPublication.Outcome raced = new TransactionTemplate(transactions).execute(status -> publication.store(units, digest));
        assertThat(raced).isInstanceOf(Applied.class);
        assertThat(((Applied) raced).replayed()).isTrue();
        assertThat(stored("combined_publication", units.commandId())).isZero();
        assertThat(stored("combined_publication_unit", units.commandId())).isZero();
    }

    @Test
    void aDeckThatMovedOnMeanwhileEndsTheJobStaleAndWritesNothing() {
        Behind late = behind();
        CombinedUnits stale = adoptAll(late, null, null);
        UUID lateJob = ((Queued) publication.enqueue(stale)).jobId();
        // the owner edits the deck's metadata before the worker comes: the plan was built against a head that is gone
        decks.save(late.owner(), late.deck(), worlds.head(late.deck()).version(), new DeckCommand(UUID.randomUUID(), "Переименована", "Другое"));
        String snapshot = worlds.snapshot(late.deck());
        try (JobTestAccess worker = JobTestAccess.around(context, handler)) {
            assertThat(worker.runOne(QUEUE)).isTrue();
        }
        JobStatus ended = status(lateJob);
        assertThat(ended.state()).isEqualTo(JobState.FAILED);
        assertThat(ended.lastError()).isEqualTo("stale_head");
        assertThat(worlds.snapshot(late.deck())).isEqualTo(snapshot);
        assertThat(worlds.receipts(stale.commandId())).isZero();
        assertThat(stored("combined_publication", stale.commandId())).isZero();
        assertThat(stored("combined_publication_unit", stale.commandId())).isZero();
        // a failed job is not restarted by sending the command again: the plan has to be built again
        assertThatThrownBy(() -> publication.enqueue(stale)).isInstanceOf(VersionConflictException.class);
        assertThat(stored("combined_publication", stale.commandId())).isZero();
    }

    @Test
    void aDeckThatMovesBetweenSlicesEndsTheJobStaleAndReleasesWhatItStaged() {
        Behind b = behind();
        CombinedUnits units = adoptAll(b, null, null);
        UUID job = ((Queued) publication.enqueue(units)).jobId();
        AtomicInteger slices = new AtomicInteger();
        JobHandler moving = new JobHandler() {
            @Override public String queue() { return QUEUE; }
            @Override public Slice run(Job claimed) {
                if (slices.incrementAndGet() == 2) {
                    // between the first and the second slice the owner changes the deck (a separate transaction, committed before this slice reads the deck)
                    new TransactionTemplate(transactions, requiresNew()).executeWithoutResult(ignored ->
                            decks.save(b.owner(), b.deck(), worlds.head(b.deck()).version(), new DeckCommand(UUID.randomUUID(), "Переименована", "Другое")));
                }
                return handler.run(claimed);
            }
            @Override public void onEnded(Job ended, JobState state) { handler.onEnded(ended, state); }
        };
        try (JobTestAccess worker = JobTestAccess.around(context, moving)) {
            assertThat(worker.runOne(QUEUE)).isTrue();
        }
        JobStatus ended = status(job);
        assertThat(ended.state()).isEqualTo(JobState.FAILED);
        assertThat(ended.lastError()).isEqualTo("stale_head");
        assertThat(worlds.revisions(b.deck())).as("the owner's own edit is the only new revision").isEqualTo(2);
        assertThat(worlds.head(b.deck()).title()).isEqualTo("Переименована");
        assertThat(worlds.itemHeads(b.deck())).doesNotContainValue(b.revisions().getFirst());
        assertThat(worlds.receipts(units.commandId())).isZero();
        assertThat(stored("combined_publication", units.commandId())).isZero();
        assertThat(stagingPins(b)).as("the first slice's staging pins were released by the hook of the job's end").isZero();
    }

    @Test
    void aStaleHeadIsRefusedAtEnqueueToo() {
        Behind b = behind();
        CombinedUnits units = adoptAll(b, null, null);
        decks.save(b.owner(), b.deck(), worlds.head(b.deck()).version(), new DeckCommand(UUID.randomUUID(), "Переименована", "Другое"));
        assertThatThrownBy(() -> publication.enqueue(units)).isInstanceOf(VersionConflictException.class);
        assertThat(stored("combined_publication", units.commandId())).isZero();
    }

    @Test
    void aRefusingFinalWriterRollsTheLastSliceBackAndTheRetryAppliesTheCommandOnce() {
        Behind b = behind();
        CombinedUnits units = adoptAll(b, null, new FinalWrites("fork.apply", CombinedWorlds.JSON.createObjectNode()));
        UUID job = ((Queued) publication.enqueue(units)).jobId();
        String before = worlds.snapshot(b.deck());
        writer.failure = new IllegalStateException("refused");
        try (JobTestAccess worker = JobTestAccess.around(context, handler)) {
            // three slices commit (10, 10, 10), the fourth stages the last 4 edits and runs the final transaction, which the hook refuses
            assertThat(worker.runOne(QUEUE)).isTrue();
            JobStatus failing = status(job);
            assertThat(failing.state()).isEqualTo(JobState.QUEUED);
            assertThat(failing.lastError()).isEqualTo("handler_error");
            assertThat(failing.progress().path("next").intValue()).isEqualTo(30);
            assertThat(worlds.snapshot(b.deck())).as("the refused final transaction left nothing").isEqualTo(before);
            assertThat(worlds.receipts(units.commandId())).isZero();
            assertThat(writer.calls).hasSize(1);

            writer.failure = null;
            worker.makeDue(job);
            assertThat(worker.runOne(QUEUE)).isTrue();
        }
        assertThat(status(job).state()).isEqualTo(JobState.SUCCEEDED);
        assertThat(worlds.revisions(b.deck())).isEqualTo(2);
        assertThat(worlds.members(b.deck())).isEqualTo(worlds.members(b.source()));
        assertThat(worlds.receipts(units.commandId())).isOne();
        assertThat(writer.calls).hasSize(2);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.deck_item_change WHERE deck_id=:d").param("d", b.deck()).query(Long.class).single()).isEqualTo((long) EDITED);
    }

    @Test
    void stagedPagesThatAreGoneMakeTheStagingStartAgainInsteadOfFailingTheJob() {
        Behind b = behind();
        CombinedUnits units = adoptAll(b, null, null);
        UUID job = ((Queued) publication.enqueue(units)).jobId();
        AtomicInteger slices = new AtomicInteger();
        JobHandler stopping = new JobHandler() {
            @Override public String queue() { return QUEUE; }
            @Override public Slice run(Job claimed) {
                Slice result = handler.run(claimed);
                if (slices.incrementAndGet() == 2) throw new SimulatedKill();
                return result;
            }
        };
        try (JobTestAccess first = JobTestAccess.around(context, stopping); JobTestAccess second = JobTestAccess.around(context, handler)) {
            assertThat(first.claim(QUEUE)).contains(job);
            assertThatThrownBy(() -> first.run(job)).isInstanceOf(SimulatedKill.class);
            assertThat(status(job).progress().path("next").intValue()).isEqualTo(10);
            // the staged roots of the cursor vanish (the staging lease ran out and the pages were collected)
            jdbc.sql("UPDATE app_learning.learning_job SET progress = jsonb_set(jsonb_set(progress,'{members,root}',to_jsonb(:gone::text)),'{exercises,root}',to_jsonb(:gone::text)) WHERE job_id=:j")
                    .param("gone", UUID.randomUUID().toString()).param("j", job).update();
            first.expireLease(job);
            assertThat(second.recoverExpired()).isEqualTo(1);
            second.makeDue(job);
            assertThat(second.runOne(QUEUE)).isTrue();
        }
        assertThat(status(job).state()).isEqualTo(JobState.SUCCEEDED);
        assertThat(worlds.revisions(b.deck())).isEqualTo(2);
        assertThat(worlds.members(b.deck())).isEqualTo(worlds.members(b.source()));
    }

    @Test
    void aJobThatEndsWithoutApplyingTakesItsStoredCommandAndStagingPinsWithIt() {
        Behind b = behind();
        CombinedUnits units = adoptAll(b, null, null);
        UUID job = ((Queued) publication.enqueue(units)).jobId();
        AtomicInteger slices = new AtomicInteger();
        JobHandler failing = new JobHandler() {
            @Override public String queue() { return QUEUE; }
            @Override public Slice run(Job claimed) {
                if (slices.incrementAndGet() == 3) throw new app.mnema.learning.platform.jobs.PermanentJobException("injected");
                return handler.run(claimed);
            }
            @Override public void onEnded(Job ended, JobState state) { handler.onEnded(ended, state); }
        };
        try (JobTestAccess worker = JobTestAccess.around(context, failing)) {
            assertThat(worker.runOne(QUEUE)).isTrue();
        }
        assertThat(status(job).state()).isEqualTo(JobState.FAILED);
        assertThat(status(job).lastError()).isEqualTo("injected");
        assertThat(status(job).progress().path("next").intValue()).isEqualTo(20);
        assertThat(stored("combined_publication", units.commandId())).isZero();
        assertThat(stored("combined_publication_unit", units.commandId())).isZero();
        assertThat(worlds.receipts(units.commandId())).isZero();
        assertThat(worlds.revisions(b.deck())).isOne();
        assertThat(stagingPins(b)).isZero();

        // cancelled before it ever ran: the command goes too
        CombinedUnits other = adoptAll(b, null, null);
        UUID cancelled = ((Queued) publication.enqueue(other)).jobId();
        assertThat(jobs.cancel(cancelled)).contains(JobState.CANCELLED);
        assertThat(status(cancelled).state()).isEqualTo(JobState.CANCELLED);
        // the cancel of a QUEUED job runs no hook, so its command is still stored: the orphan purge removes it, and only it
        CombinedUnits live = adoptAll(b, null, null);
        UUID liveJob = ((Queued) publication.enqueue(live)).jobId();
        assertThat(stored("combined_publication", other.commandId())).isOne();
        assertThat(stored("combined_publication_unit", other.commandId())).isEqualTo((long) EDITED);
        assertThat(publication.purgeOrphans(100)).as("a command younger than a minute is not an orphan yet").isZero();
        jdbc.sql("UPDATE app_learning.combined_publication SET created_at = now() - interval '2 minutes'").update();
        assertThat(publication.purgeOrphans(100)).isOne();
        assertThat(stored("combined_publication", other.commandId())).isZero();
        assertThat(stored("combined_publication_unit", other.commandId())).isZero();
        assertThat(stored("combined_publication", live.commandId())).as("a queued job still needs its command").isOne();
        assertThat(status(liveJob).state()).isEqualTo(JobState.QUEUED);
        // an enqueue removes a few orphans on the way: a job that retention purged leaves its command behind in the same way
        jdbc.sql("DELETE FROM app_learning.learning_job WHERE job_id = :j").param("j", liveJob).update();
        jdbc.sql("UPDATE app_learning.combined_publication SET created_at = now() - interval '2 minutes'").update();
        publication.enqueue(adoptAll(b, null, null));
        assertThat(stored("combined_publication", live.commandId())).isZero();
        worlds.cancelPendingJobs();
    }

    @Test
    void aPlanThatDoesNotDescribeTheDeckEndsTheJobAtOnceWithACodeAndNotAfterFiveRetries() {
        Behind b = behind();
        List<ItemOp> ops = new ArrayList<>();
        for (int index = 0; index < EDITED; index++) ops.add(new ItemOp.AdoptReplace(b.edited().get(index)[0], index == 15 ? 31 : 1 + index, b.revisions().get(index)));
        CombinedUnits units = new CombinedUnits(UUID.randomUUID(), b.deck(), b.owner(), worlds.head(b.deck()).revisionId(), null, ops, List.of(), List.of());
        UUID job = ((Queued) publication.enqueue(units)).jobId();
        try (JobTestAccess worker = JobTestAccess.around(context, handler)) {
            assertThat(worker.runOne(QUEUE)).isTrue();
        }
        JobStatus ended = status(job);
        assertThat(ended.state()).isEqualTo(JobState.FAILED);
        assertThat(ended.lastError()).isEqualTo("plan_invalid");
        assertThat(ended.attempts()).isOne();
        assertThat(ended.progress().path("next").intValue()).as("the first slice committed, the second found the plan stale").isEqualTo(10);
        assertThat(worlds.revisions(b.deck())).isOne();
        assertThat(stored("combined_publication", units.commandId())).isZero();
        assertThat(stagingPins(b)).isZero();
    }

    @Test
    void aTakenDownRevisionEndsTheJobWithItsOwnCode() {
        Behind b = behind();
        CombinedUnits units = adoptAll(b, null, null);
        new app.mnema.learning.support.TakedownFixture(jdbc).item(b.source(), b.edited().get(3)[0], b.revisions().get(3));
        UUID job = ((Queued) publication.enqueue(units)).jobId();
        try (JobTestAccess worker = JobTestAccess.around(context, handler)) {
            assertThat(worker.runOne(QUEUE)).isTrue();
        }
        assertThat(status(job).state()).isEqualTo(JobState.FAILED);
        assertThat(status(job).lastError()).isEqualTo("taken_down");
        assertThat(status(job).attempts()).isOne();
        assertThat(worlds.revisions(b.deck())).isOne();
    }

    @Test
    void theFinalWritesAreStoredByteForByteAndReachTheHookAsTheCallerSentThem() throws Exception {
        Behind b = behind();
        tools.jackson.databind.node.ObjectNode body = (tools.jackson.databind.node.ObjectNode) CombinedWorlds.JSON.readTree(
                "{\"z\":1e2,\"a\":[1.50,\"x\"],\"big\":12345678901234567890,\"text\":\"Привет\"}");
        FinalWrites writes = new FinalWrites("fork.apply", body);
        CombinedUnits units = adoptAll(b, null, writes);
        UUID job = ((Queued) publication.enqueue(units)).jobId();
        String text = jdbc.sql("SELECT final_writes FROM app_learning.combined_publication WHERE command_id=:c").param("c", units.commandId()).query(String.class).single();
        // the order of the keys and the form of the numbers are the caller's (jsonb would have sorted the keys and printed 100 for 1e2)
        assertThat(text).startsWith("{\"type\":\"fork.apply\",\"body\":{\"z\":100.0,\"a\":[1.5,\"x\"],\"big\":12345678901234567890,\"text\":\"Привет\"}");
        assertThat(jdbc.sql("SELECT data_type FROM information_schema.columns WHERE table_name='combined_publication' AND column_name='final_writes'")
                .query(String.class).single()).isEqualTo("text");
        try (JobTestAccess worker = JobTestAccess.around(context, handler)) {
            assertThat(worker.runOne(QUEUE)).isTrue();
        }
        assertThat(status(job).state()).isEqualTo(JobState.SUCCEEDED);
        assertThat(writer.writes).hasSize(1);
        assertThat(writer.writes.getFirst()).isEqualTo(writes);
        assertThat(writer.writes.getFirst().body()).isEqualTo(body);
        // the replay answers the stored result and writes nothing again
        assertThat(((Applied) publication.enqueue(units)).replayed()).isTrue();
        assertThat(writer.writes).hasSize(1);
    }

    private static org.springframework.transaction.support.DefaultTransactionDefinition requiresNew() {
        return new org.springframework.transaction.support.DefaultTransactionDefinition(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    private long stagingPins(Behind b) {
        return jdbc.sql("SELECT count(*) FROM app_learning.storage_pin WHERE reuse_scope_id=:s AND pin_kind='staging' AND actor_id=:a")
                .param("s", b.copy().scope()).param("a", b.owner()).query(Long.class).single();
    }
}
