package app.mnema.learning.update.plan;

import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.library.ManifestPages;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.platform.jobs.Job;
import app.mnema.learning.platform.jobs.JobHandler;
import app.mnema.learning.platform.jobs.JobState;
import app.mnema.learning.platform.jobs.JobStatus;
import app.mnema.learning.platform.jobs.JobTestAccess;
import app.mnema.learning.platform.jobs.LearningJobs;
import app.mnema.learning.platform.jobs.Slice;
import app.mnema.learning.storage.ImmutableStorage;
import app.mnema.learning.study.session.StudySessionService;
import app.mnema.learning.support.HttpIdentityFixture;
import app.mnema.learning.support.PostgresIntegrationTest;
import app.mnema.learning.platform.idempotency.IdempotencyConflictException;
import app.mnema.learning.update.CombinedPublication;
import app.mnema.learning.update.CombinedUnits;
import app.mnema.learning.update.plan.PlanWorlds.Big;
import app.mnema.learning.update.plan.PlanWorlds.Copy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;

import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static app.mnema.learning.update.plan.PlanWorlds.json;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Updates/4 (#443), the apply command above the synchronous limit, under the executor's hands: no process executor runs here (the default test role is {@code api}); a worker is built
 * by hand around the real handlers, so every claim and kill happens in a known order. The command answers {@code 202 APPLYING}, the plan route says {@code APPLYING} while the job
 * waits or runs, a worker killed in the middle of the third slice is replaced by another and the command is applied exactly once (the base, the decisions and the answer in the
 * same transaction as the revision); a job that ends {@code FAILED} shows as {@code lastApplyFailed} on the plan until the copy moves on.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"learning.updates.slice-size=10", "learning.updates.sync-max-units=20", "learning.community.forks.slice-size=10", "learning.jobs.sweep-interval=PT5M",
                "spring.datasource.hikari.maximum-pool-size=20"})
class ApplyJobIntegrationTest extends PostgresIntegrationTest {
    private static final JsonNode CONTRACT = PlanWorlds.updatesContract();
    private static final String QUEUE = CombinedPublication.QUEUE;

    /** A worker killed in the middle of a slice: not an exception any handler could catch. */
    private static final class SimulatedKill extends Error {
        private static final long serialVersionUID = 1L;
    }

    @LocalServerPort private int port;
    @Autowired private ApplicationContext context;
    @Autowired private LearningJobs jobs;
    @Autowired private CombinedPublication publication;
    @Autowired private DeckService decks;
    @Autowired private ItemService items;
    @Autowired private ExerciseService exercises;
    @Autowired private StudySessionService sessions;
    @Autowired private MediaCatalog media;
    @Autowired private ImmutableStorage storage;
    @Autowired private JdbcClient jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private ManifestPages manifests;
    @Autowired private UpdatePlanService service;
    private PlanWorlds worlds;
    private ApplyWorlds apply;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        HttpIdentityFixture.register(registry);
        registry.add("learning.community.forks.enabled", () -> true);
        registry.add("learning.community.public-routes.enabled", () -> true);
        registry.add("learning.community.public-routes.guest-per-minute", () -> 100_000);
        registry.add("learning.community.public-routes.account-per-minute", () -> 100_000);
    }

    @BeforeEach
    void worlds() {
        worlds = new PlanWorlds(port, decks, items, exercises, sessions, media, storage, jdbc, transactions, manifests);
        apply = new ApplyWorlds(worlds, manifests, service);
        // the database is shared by the test classes and a worker claims the oldest job of a queue
        jdbc.sql("UPDATE app_learning.learning_job SET state='CANCELLED', finished_at=clock_timestamp(), lease_token=NULL, lease_until=NULL "
                + "WHERE state IN ('QUEUED','RUNNING') AND queue IN ('deck.combined.publish','deck.copy.materialize')").update();
    }

    private static java.util.Set<String> names(JsonNode node) {
        java.util.Set<String> result = new java.util.TreeSet<>();
        node.propertyNames().forEach(result::add);
        return result;
    }

    private JobHandler[] handlers() {
        return context.getBeansOfType(JobHandler.class).values().toArray(new JobHandler[0]);
    }

    private JobHandler handlerOf(String queue) {
        return context.getBeansOfType(JobHandler.class).values().stream().filter(handler -> queue.equals(handler.queue())).findFirst().orElseThrow();
    }

    private JobStatus job(UUID command) {
        UUID id = jdbc.sql("SELECT job_id FROM app_learning.learning_job WHERE queue=:q AND dedupe_key=:k").param("q", QUEUE).param("k", command.toString()).query(UUID.class).single();
        return jobs.status(id).orElseThrow();
    }

    private long stored(UUID command) {
        return jdbc.sql("SELECT count(*) FROM app_learning.combined_publication WHERE command_id=:c").param("c", command).query(Long.class).single();
    }

    @Test
    void aCommandAboveTheSynchronousLimitIsAJobThePlanSaysApplyingAKilledWorkerResumesAndItIsAppliedExactlyOnce() {
        try (JobTestAccess hand = JobTestAccess.around(context, handlers())) {
            Big big = worlds.source("LINK", 2, 0, 80);
            Copy copy = apply.copyByHand(big.source(), hand);
            UUID mine = copy.deck();
            UUID author = big.source().author();
            List<UUID> bulk = big.bulk();
            List<UUID> revisions = apply.saveAll(author, big.source().deck(), bulk, 2, "bulk edit");
            worlds.publish(author, big.source().deck(), "LINK");
            UUID published = worlds.published(big.source().deck());
            UpdatePlan plan = apply.plan(copy);
            assertThat(plan.units()).hasSize(80).allSatisfy(unit -> assertThat(unit.unitClass()).isEqualTo(UnitClass.SAFE));
            long revisionsBefore = apply.revisions(mine);
            UUID baseBefore = worlds.base(mine);

            // the command is a job: 202, nothing written yet
            UUID command = UUID.randomUUID();
            HttpResponse<String> accepted = apply.apply(copy, command, plan.planVersion(), true);
            assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(202);
            assertThat(accepted.headers().firstValue("cache-control")).contains("private, no-store");
            assertThat(json(accepted).path("commandId").stringValue(null)).isEqualTo(command.toString());
            assertThat(json(accepted).path("state").stringValue(null)).isEqualTo("APPLYING");
            assertThat(names(json(accepted))).isEqualTo(names(CONTRACT.path("apply").path("queued").path("body")));
            assertThat(job(command).state()).isEqualTo(JobState.QUEUED);
            assertThat(job(command).accountId()).isEqualTo(copy.owner());
            assertThat(stored(command)).isOne();
            assertThat(apply.revisions(mine)).isEqualTo(revisionsBefore);
            assertThat(worlds.base(mine)).isEqualTo(baseBefore);
            assertThat(apply.decisions(mine)).isEmpty();

            // the plan route: no plan while it runs, with the command; the same command again is the same job; another command is refused
            JsonNode applying = apply.planRoute(copy);
            assertThat(applying.path("state").stringValue(null)).isEqualTo("APPLYING");
            assertThat(applying.path("commandId").stringValue(null)).isEqualTo(command.toString());
            assertThat(applying.path("forkDeckId").stringValue(null)).isEqualTo(mine.toString());
            assertThat(applying.path("units")).isEmpty();
            assertThat(applying.path("nextCursor").isNull()).isTrue();
            assertThat(names(applying)).isEqualTo(names(CONTRACT.path("applying").path("body")));
            assertThat(worlds.get("/decks/" + mine + "/update-plan?limit=0", copy.owner()).statusCode()).as("a malformed query is a 400 also while applying").isEqualTo(400);
            assertThat(worlds.get("/decks/" + mine + "/update-plan", UUID.randomUUID()).statusCode()).as("not the owner's").isEqualTo(404);
            HttpResponse<String> again = apply.apply(copy, command, plan.planVersion(), true);
            assertThat(again.statusCode()).isEqualTo(202);
            assertThat(stored(command)).isOne();
            assertThat(apply.apply(copy, UUID.randomUUID(), plan.planVersion(), true).statusCode()).isEqualTo(409);
            assertThat(ApplyWorlds.code(apply.apply(copy, UUID.randomUUID(), plan.planVersion(), true))).isEqualTo("UPDATE_PLAN_STALE");
            assertThat(apply.apply(copy, command, plan.planVersion(), false).statusCode()).as("the same id with another body").isEqualTo(409);
            // ... also straight at the engine: a body small enough to run in the request, under the id of a stored command, is another command
            CombinedUnits small = new CombinedUnits(command, mine, copy.owner(), worlds.headRevisionOf(mine), null, List.of(), List.of(), List.of());
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> publication.publish(small)).isInstanceOf(IdempotencyConflictException.class);
            assertThat(job(command).state()).isEqualTo(JobState.QUEUED);
            assertThat(stored(command)).isOne();
            assertThat(ApplyWorlds.code(apply.apply(copy, command, plan.planVersion(), false))).isEqualTo("IDEMPOTENCY_CONFLICT");

            // a worker dies in the middle of the third slice; another resumes from the last committed cursor
            AtomicInteger slices = new AtomicInteger();
            JobHandler real = handlerOf(QUEUE);
            JobHandler dying = new JobHandler() {
                @Override public String queue() { return QUEUE; }
                @Override public Slice run(Job claimed) {
                    Slice result = real.run(claimed);
                    if (slices.incrementAndGet() == 3) throw new SimulatedKill();
                    return result;
                }
            };
            try (JobTestAccess first = JobTestAccess.around(context, dying)) {
                UUID id = first.claim(QUEUE).orElseThrow();
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> first.run(id)).isInstanceOf(SimulatedKill.class);
                assertThat(job(command).state()).isEqualTo(JobState.RUNNING);
                assertThat(job(command).progressCount()).isEqualTo(20);
                assertThat(apply.revisions(mine)).isEqualTo(revisionsBefore);
                assertThat(worlds.base(mine)).isEqualTo(baseBefore);
                assertThat(apply.decisions(mine)).isEmpty();
                assertThat(apply.planRoute(copy).path("state").stringValue(null)).as("a running job is still APPLYING").isEqualTo("APPLYING");
                hand.expireLease(id);
                assertThat(hand.recoverExpired()).isEqualTo(1);
                hand.makeDue(id);
            }
            assertThat(hand.runOne(QUEUE)).isTrue();

            // applied once: one revision, every head the author's, the base moved, the answer stored
            assertThat(job(command).state()).isEqualTo(JobState.SUCCEEDED);
            assertThat(apply.revisions(mine)).isEqualTo(revisionsBefore + 1);
            Map<UUID, UUID> heads = new HashMap<>();
            jdbc.sql("SELECT member_key,revision_id FROM app_learning.deck_head_item WHERE deck_id=:d").param("d", mine)
                    .query((row, ignored) -> heads.put(row.getObject(1, UUID.class), row.getObject(2, UUID.class))).list();
            for (int index = 0; index < bulk.size(); index++) assertThat(heads).containsEntry(bulk.get(index), revisions.get(index));
            assertThat(jdbc.sql("SELECT count(*) FROM app_learning.deck_item_change WHERE deck_id=:d AND change_kind='save'").param("d", mine).query(Long.class).single()).isEqualTo(80L);
            assertThat(worlds.base(mine)).isEqualTo(published);
            assertThat(apply.decisions(mine)).isEmpty();
            assertThat(stored(command)).as("the stored command goes with the job").isZero();
            assertThat(apply.plan(copy).state()).isEqualTo(UpdatePlan.State.UP_TO_DATE);
            JsonNode after = apply.planRoute(copy);
            assertThat(after.path("state").stringValue(null)).isEqualTo("UP_TO_DATE");
            assertThat(after.has("lastApplyFailed")).isFalse();

            // the same command again: the stored answer, flagged, no second job and nothing more written
            HttpResponse<String> replay = apply.apply(copy, command, plan.planVersion(), true);
            assertThat(replay.statusCode()).as(replay.body()).isEqualTo(200);
            assertThat(replay.headers().firstValue("idempotency-replayed")).contains("true");
            JsonNode answer = json(replay);
            assertThat(names(answer)).isEqualTo(names(CONTRACT.path("apply").path("applied").path("body")));
            assertThat(answer.path("state").stringValue(null)).isEqualTo("APPLIED");
            assertThat(answer.path("applied").intValue()).isEqualTo(80);
            assertThat(answer.path("declined").intValue()).isZero();
            assertThat(answer.path("pending").intValue()).isZero();
            assertThat(answer.path("baseRevisionId").stringValue(null)).isEqualTo(published.toString());
            assertThat(answer.path("deckRevisionId").stringValue(null)).isEqualTo(worlds.headRevisionOf(mine).toString());
            assertThat(jdbc.sql("SELECT count(*) FROM app_learning.learning_job WHERE queue=:q AND dedupe_key=:k").param("q", QUEUE).param("k", command.toString())
                    .query(Long.class).single()).isOne();
            assertThat(apply.revisions(mine)).isEqualTo(revisionsBefore + 1);
        }
    }

    @Test
    void aJobThatFailsShowsAsLastApplyFailedOnThePlanUntilTheCopyMovesOnAndIsNeverRestarted() {
        try (JobTestAccess hand = JobTestAccess.around(context, handlers())) {
            Big big = worlds.source("LINK", 2, 0, 30);
            Copy copy = apply.copyByHand(big.source(), hand);
            UUID mine = copy.deck();
            apply.saveAll(big.source().author(), big.source().deck(), big.bulk(), 2, "bulk edit");
            worlds.publish(big.source().author(), big.source().deck(), "LINK");
            String version = apply.version(copy);

            UUID command = UUID.randomUUID();
            assertThat(apply.apply(copy, command, version, true).statusCode()).isEqualTo(202);
            // the learner edits his copy while the job waits: its head moves, the job cannot apply the plan it was made from
            worlds.editText(copy.owner(), mine, big.source().materials().get(0).member(), "learner zero");
            String snapshot = apply.snapshot(mine);
            assertThat(hand.runOne(QUEUE)).isTrue();
            assertThat(job(command).state()).isEqualTo(JobState.FAILED);
            assertThat(job(command).lastError()).isEqualTo("stale_head");
            assertThat(apply.snapshot(mine).replaceAll("\"jobs\": \\d+", "").replaceAll("\"commands\": \\d+", ""))
                    .isEqualTo(snapshot.replaceAll("\"jobs\": \\d+", "").replaceAll("\"commands\": \\d+", ""));
            assertThat(stored(command)).isZero();

            // the plan is back, with the code
            JsonNode plan = apply.planRoute(copy);
            assertThat(plan.path("state").stringValue(null)).isEqualTo("READY");
            assertThat(plan.path("lastApplyFailed").path("code").stringValue(null)).isEqualTo("stale_head");
            assertThat(names(plan.path("lastApplyFailed"))).isEqualTo(names(CONTRACT.path("lastApplyFailed").path("member").path("lastApplyFailed")));
            assertThat(plan.path("units")).hasSize(30);
            // the same command is not restarted (it was made from a plan that is gone)
            HttpResponse<String> retry = apply.apply(copy, command, version, true);
            assertThat(retry.statusCode()).isEqualTo(409);
            assertThat(ApplyWorlds.code(retry)).isEqualTo("UPDATE_PLAN_STALE");

            // an apply that succeeds clears the code even when it creates no revision (here it takes nothing and leaves everything pending)
            long revisions = apply.revisions(mine);
            HttpResponse<String> nothing = apply.apply(copy, UUID.randomUUID(), plan.path("planVersion").stringValue(null), false);
            assertThat(nothing.statusCode()).as(nothing.body()).isEqualTo(200);
            assertThat(json(nothing).path("deckRevisionId").isNull()).isTrue();
            assertThat(apply.revisions(mine)).isEqualTo(revisions);
            JsonNode cleared = apply.planRoute(copy);
            assertThat(cleared.path("state").stringValue(null)).isEqualTo("READY");
            assertThat(cleared.has("lastApplyFailed")).isFalse();
            assertThat(cleared.path("units")).hasSize(30);

            // a new command from the new plan is a new job
            UUID second = UUID.randomUUID();
            assertThat(apply.apply(copy, second, cleared.path("planVersion").stringValue(null), true).statusCode()).isEqualTo(202);
            assertThat(hand.runOne(QUEUE)).isTrue();
            assertThat(job(second).state()).isEqualTo(JobState.SUCCEEDED);
            JsonNode done = apply.planRoute(copy);
            assertThat(done.path("state").stringValue(null)).isEqualTo("UP_TO_DATE");
            assertThat(done.has("lastApplyFailed")).isFalse();
        }
    }
}
