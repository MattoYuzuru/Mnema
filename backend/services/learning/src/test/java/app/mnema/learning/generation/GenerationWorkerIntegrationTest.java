package app.mnema.learning.generation;

import app.mnema.learning.usage.ReservationScope;
import app.mnema.learning.usage.UsageLedger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/** The durable queue: lease and fencing, event numbering under the session lock, and the daily-burst deferral. */
class GenerationWorkerIntegrationTest extends GenerationIntegrationTest {
    @Autowired private SessionLifecycle lifecycle;
    @Autowired private GenerationRepository repository;
    @Autowired private GenerationSettings settings;

    private StepClaim claimOf(UUID step) {
        Rows.Step row = steps.step(step).orElseThrow();
        return new StepClaim(row.stepId(), row.sessionId(), row.artifactId(), row.ownerId(), row.kind(), row.capability(),
                row.leaseToken(), row.attempts(), row.deadlineAt(), row.input());
    }

    private SessionLifecycle.Draft draft() {
        ObjectNode document = JSON.createObjectNode().put("formatVersion", 1);
        document.putObject("root").put("type", "doc");
        return new SessionLifecycle.Draft("v1", "stub:stub", document, List.of(), JSON.createObjectNode(), "Заголовок", 0,
                "MATERIAL_MEDIUM", 10, Map.of());
    }

    @Test
    void aWorkerThatLostItsLeaseWritesNothingAndTheTakeoverCompletesTheStepOnce() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        UUID session = start(owner, deck, spec("[[fake:block]] долго"));
        assertThat(provider.blockedEntered.await(10, TimeUnit.SECONDS)).isTrue();
        UUID step = stepOf(session);
        StepClaim stale = claimOf(step);
        assertThat(stale.token()).isNotNull();
        assertThat(stale.attempt()).isEqualTo(1);

        // the lease is taken from the worker (as after an expiry and a recovery); its next heartbeat finds the token gone
        jdbc.sql("UPDATE app_learning.generation_step SET state='READY',lease_token=NULL,lease_until=NULL,"
                + "next_attempt_at=CURRENT_TIMESTAMP + interval '1 hour' WHERE step_id=:id").param("id", step).update();
        await("the stale worker to give up", Duration.ofSeconds(10), () -> steps.heartbeat(step, stale.token(), 30).isEmpty());
        Thread.sleep(500);
        // it wrote nothing: the artifact is as the claim left it, no revision, no debit
        assertThat(artifactStates(session)).containsExactly("GENERATING");
        assertThat(debits(owner)).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.generation_artifact_revision WHERE session_id=:id").param("id", session)
                .query(Integer.class).single()).isZero();

        // and a late write with the stale token is refused on every path
        assertThat(lifecycle.succeed(stale, draft())).isFalse();
        assertThat(lifecycle.fail(stale, SessionLifecycle.Failure.fail("INVALID_OUTPUT"))).isFalse();
        assertThat(lifecycle.fail(stale, SessionLifecycle.Failure.retry("PROVIDER_UNAVAILABLE", Duration.ZERO))).isFalse();
        assertThat(lifecycle.checkpoint(stale, 1, 0, Json.array())).isFalse();
        assertThat(lifecycle.begin(stale)).isEmpty();
        assertThat(steps.heartbeat(step, stale.token(), 30)).isEmpty();
        assertThat(artifactStates(session)).containsExactly("GENERATING");
        assertThat(debits(owner)).isZero();

        // the takeover: the step is due again and the second claim runs it to the end
        provider.release.countDown();
        jdbc.sql("UPDATE app_learning.generation_step SET next_attempt_at=CURRENT_TIMESTAMP WHERE step_id=:id").param("id", step).update();
        awaitState(session, "REVIEW");
        assertThat(jdbc.sql("SELECT attempts FROM app_learning.generation_step WHERE step_id=:id").param("id", step)
                .query(Integer.class).single()).isEqualTo(2);
        assertThat(artifactStates(session)).containsExactly("PROPOSED");
        assertThat(debits(owner)).isEqualTo(10);

        // a worker that wakes up after the result still cannot overwrite it
        assertThat(lifecycle.succeed(stale, draft())).isFalse();
        assertThat(lifecycle.fail(stale, SessionLifecycle.Failure.fail("INVALID_OUTPUT"))).isFalse();
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.generation_artifact_revision WHERE session_id=:id").param("id", session)
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(artifactStates(session)).containsExactly("PROPOSED");
        assertThat(debits(owner)).isEqualTo(10);
        assertThat(jdbc.sql("SELECT state FROM app_learning.generation_step WHERE step_id=:id").param("id", step).query(String.class)
                .single()).isEqualTo("SUCCEEDED");
    }

    @Test
    void anExpiredLeaseIsRecoveredWithABackoffAndAfterTheLastAttemptTheStepFailsInsteadOfLoopingForever() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        UUID session = parkedSession(owner, deck, spec("20 глаголов движения"));
        UUID step = stepOf(session);

        // a claim that crashed with attempts left: back to READY after a backoff, nothing else changes
        jdbc.sql("UPDATE app_learning.generation_step SET state='RUNNING',lease_token=gen_random_uuid(),"
                + "lease_until=CURRENT_TIMESTAMP - interval '1 second',attempts=1 WHERE step_id=:id").param("id", step).update();
        assertThat(steps.expiredRunning(10)).contains(step);
        lifecycle.recover(step);
        Rows.Step requeued = steps.step(step).orElseThrow();
        assertThat(requeued.state()).isEqualTo("READY");
        assertThat(requeued.leaseToken()).isNull();
        assertThat(requeued.errorCode()).isEqualTo("LEASE_EXPIRED");
        assertThat(sessionState(session)).isEqualTo("RUNNING");

        // a lease that is still valid is left alone
        jdbc.sql("UPDATE app_learning.generation_step SET state='RUNNING',lease_token=gen_random_uuid(),"
                + "lease_until=CURRENT_TIMESTAMP + interval '1 minute',attempts=2 WHERE step_id=:id").param("id", step).update();
        lifecycle.recover(step);
        assertThat(steps.step(step).orElseThrow().state()).isEqualTo("RUNNING");

        // the last attempt crashed too: the step and its artifact fail, nothing is debited, the hold is released
        jdbc.sql("UPDATE app_learning.generation_step SET lease_until=CURRENT_TIMESTAMP - interval '1 second',attempts=:max "
                + "WHERE step_id=:id").param("id", step).param("max", settings.step().maxAttempts()).update();
        lifecycle.recover(step);
        assertThat(steps.step(step).orElseThrow().state()).isEqualTo("FAILED");
        assertThat(artifactErrors(session)).containsExactly("PROVIDER_UNAVAILABLE");
        assertThat(sessionState(session)).isEqualTo("REVIEW");
        assertThat(reservationState(session)).isEqualTo("RELEASED");
        assertThat(notificationKinds(owner)).containsExactly("GENERATION_FAILED");
    }

    @Test
    void aStepThatOutlivedItsDeadlineFailsWithTheDeadlineCode() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        UUID session = parkedSession(owner, deck, spec("20 глаголов движения"));
        UUID step = stepOf(session);
        jdbc.sql("UPDATE app_learning.generation_step SET state='RUNNING',lease_token=gen_random_uuid(),"
                + "lease_until=CURRENT_TIMESTAMP - interval '1 second',deadline_at=CURRENT_TIMESTAMP - interval '1 minute',attempts=:max "
                + "WHERE step_id=:id").param("id", step).param("max", settings.step().maxAttempts()).update();
        lifecycle.recover(step);
        assertThat(artifactErrors(session)).containsExactly("DEADLINE_EXCEEDED");
        assertThat(debits(owner)).isEqualTo(125);
    }

    @Test
    void aCheckpointOfTheConfiguredMaximumFitsTheEventRowAndAStaleOrCancelledClaimAppendsNothing() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        UUID session = parkedSession(owner, deck, spec("20 глаголов движения"));
        UUID step = stepOf(session);
        jdbc.sql("UPDATE app_learning.generation_step SET state='RUNNING',lease_token=gen_random_uuid(),"
                + "lease_until=CURRENT_TIMESTAMP + interval '1 minute',attempts=1 WHERE step_id=:id").param("id", step).update();
        StepClaim claim = claimOf(step);
        // many small blocks inflate most when stored as jsonb text: fill the event up to the configured bound
        var blocks = Json.array();
        while (blocks.toString().length() < settings.stream().maxEventBytes() - 300) {
            var paragraph = blocks.addObject().put("id", UUID.randomUUID().toString()).put("type", "paragraph").put("version", 1);
            paragraph.putObject("attrs");
            var text = paragraph.putArray("content").addObject().put("id", UUID.randomUUID().toString()).put("type", "text").put("version", 1);
            text.putObject("attrs").put("text", "ab").putArray("marks");
            text.putArray("content");
        }
        int before = jdbc.sql("SELECT count(*) FROM app_learning.generation_event WHERE session_id=:id").param("id", session)
                .query(Integer.class).single();
        assertThat(lifecycle.checkpoint(claim, 1, 0, blocks)).isTrue();
        assertThat(jdbc.sql("SELECT max(octet_length(payload::text)) FROM app_learning.generation_event WHERE session_id=:id AND type='BLOCKS_APPENDED'")
                .param("id", session).query(Integer.class).single()).isLessThanOrEqualTo(32 * 1024);
        assertThat(jdbc.sql("SELECT draft_generation FROM app_learning.generation_artifact WHERE session_id=:id").param("id", session)
                .query(Integer.class).single()).isEqualTo(1);

        // the owner cancels: the next checkpoint of the running claim is refused and writes nothing
        jdbc.sql("UPDATE app_learning.generation_step SET cancel_requested=TRUE WHERE step_id=:id").param("id", step).update();
        assertThat(lifecycle.checkpoint(claim, 2, 0, blocks)).isFalse();
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.generation_event WHERE session_id=:id").param("id", session)
                .query(Integer.class).single()).isEqualTo(before + 1);
    }

    @Autowired private GenerationTestConfiguration.VideoExecutor videoExecutor;

    @Test
    void aReadyStepOfASessionInReviewIsClaimedWhenAnExecutorExistsForItsKind() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        UUID session = start(owner, deck, spec("20 глаголов движения"));
        awaitState(session, "REVIEW");
        UUID artifact = repository.artifacts(session).getFirst().artifactId();
        UUID media = UUID.randomUUID();
        steps.insert(media, session, artifact, owner, "VIDEO_GENERATE", "VIDEO", JSON.createObjectNode(), "test:media:" + media);
        await("the media step to be claimed", Duration.ofSeconds(10), () -> steps.step(media).orElseThrow().state().equals("SUCCEEDED"));
        assertThat(videoExecutor.claimed).contains(media);
        assertThat(sessionState(session)).isEqualTo("REVIEW");
    }

    @Test
    void aStepLifetimeCountsFromItsFirstClaimAndRequeueDelaysAreCapped() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        UUID session = parkedSession(owner, deck, spec("20 глаголов движения"));
        UUID step = stepOf(session);

        // a claim near the end of the lifetime gets what is left, not a fresh run
        jdbc.sql("UPDATE app_learning.generation_step SET first_claimed_at=CURRENT_TIMESTAMP - interval '3590 seconds' WHERE step_id=:id")
                .param("id", step).update();
        Rows.Step claimed = steps.claim(step, UUID.randomUUID(), 30, 360, settings.step().maxLifetime().toSeconds());
        assertThat(claimed.deadlineAt()).isBefore(Instant.now().plusSeconds(30));
        assertThat(claimed.firstClaimedAt()).isBefore(Instant.now().minusSeconds(3500));

        // a retry delay asked for by a provider (an hour) is capped at the backoff cap
        StepClaim claim = claimOf(step);
        assertThat(lifecycle.fail(claim, SessionLifecycle.Failure.retry("PROVIDER_UNAVAILABLE", Duration.ofHours(1)))).isTrue();
        Rows.Step requeued = steps.step(step).orElseThrow();
        assertThat(requeued.state()).isEqualTo("READY");
        assertThat(requeued.nextAttemptAt()).isBefore(Instant.now().plus(settings.step().backoffCap()).plusSeconds(2));

        // and a step whose lifetime is over fails with the deadline code instead of being retried or claimed
        jdbc.sql("UPDATE app_learning.generation_step SET first_claimed_at=CURRENT_TIMESTAMP - interval '2 hours',"
                + "next_attempt_at=CURRENT_TIMESTAMP WHERE step_id=:id").param("id", step).update();
        awaitState(session, "REVIEW");
        assertThat(artifactErrors(session)).containsExactly("DEADLINE_EXCEEDED");
        assertThat(steps.step(step).orElseThrow().state()).isEqualTo("FAILED");
        assertThat(provider.calls).isEmpty();
    }

    @Test
    void aLeaseExpiredPastTheLifetimeFailsTheStepEvenWithAttemptsLeft() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        UUID session = parkedSession(owner, deck, spec("20 глаголов движения"));
        UUID step = stepOf(session);
        jdbc.sql("UPDATE app_learning.generation_step SET state='RUNNING',lease_token=gen_random_uuid(),"
                + "lease_until=CURRENT_TIMESTAMP - interval '1 second',attempts=1,first_claimed_at=CURRENT_TIMESTAMP - interval '2 hours' "
                + "WHERE step_id=:id").param("id", step).update();
        lifecycle.recover(step);
        assertThat(artifactErrors(session)).containsExactly("DEADLINE_EXCEEDED");
    }

    @Test
    void aShutdownHandsARunningStepBackWithoutBurningAnAttempt() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        UUID session = parkedSession(owner, deck, spec("20 глаголов движения"));
        UUID step = stepOf(session);
        UUID token = UUID.randomUUID();
        jdbc.sql("UPDATE app_learning.generation_step SET state='RUNNING',lease_token=:token,"
                + "lease_until=CURRENT_TIMESTAMP + interval '1 minute',attempts=2 WHERE step_id=:id").param("id", step).param("token", token).update();
        assertThat(steps.release(step, UUID.randomUUID())).isZero();
        assertThat(steps.release(step, token)).isEqualTo(1);
        Rows.Step back = steps.step(step).orElseThrow();
        assertThat(back.state()).isEqualTo("READY");
        assertThat(back.attempts()).isEqualTo(1);
        assertThat(back.leaseToken()).isNull();
        assertThat(back.nextAttemptAt()).isBeforeOrEqualTo(Instant.now().plusSeconds(1));
    }

    @Test
    void aClaimOfACancelledSessionIsVoidAndItsStepEndsCancelled() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        UUID session = parkedSession(owner, deck, spec("20 глаголов движения"));
        UUID step = stepOf(session);
        jdbc.sql("UPDATE app_learning.generation_step SET state='RUNNING',lease_token=gen_random_uuid(),"
                + "lease_until=CURRENT_TIMESTAMP + interval '1 minute',attempts=1,cancel_requested=TRUE WHERE step_id=:id")
                .param("id", step).update();
        StepClaim claim = claimOf(step);
        assertThat(lifecycle.begin(claim)).isEmpty();
        assertThat(steps.step(step).orElseThrow().state()).isEqualTo("CANCELLED");
        assertThat(artifactStates(session)).containsExactly("QUEUED");
    }

    @Test
    void eventNumbersFromConcurrentWritersAreGapFreeAndAPollerNeverMissesOne() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        UUID session = start(owner, deck, spec("20 глаголов движения"));
        awaitState(session, "REVIEW");
        long before = repository.session(session).orElseThrow().lastEventSeq();
        int writers = 8;
        int perWriter = 3;
        TransactionTemplate transaction = new TransactionTemplate(transactions);
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean done = new AtomicBoolean();
        Set<Long> seen = new TreeSet<>();
        long[] cursor = {before};
        Thread poller = Thread.ofVirtual().start(() -> {
            // the contract's client: ask for events after the cursor and advance the cursor to the last seq returned
            boolean last = false;
            while (!last) {
                last = done.get();
                for (Rows.Event event : repository.events(session, cursor[0], 100)) {
                    seen.add(event.seq());
                    cursor[0] = event.seq();
                }
            }
        });
        List<Thread> threads = new ArrayList<>();
        for (int writer = 0; writer < writers; writer++) {
            int id = writer;
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    transaction.executeWithoutResult(status -> {
                        Rows.Session locked = repository.lockSession(session).orElseThrow();
                        List<Rows.EventDraft> drafts = new ArrayList<>();
                        for (int n = 0; n < perWriter; n++) {
                            drafts.add(new Rows.EventDraft("USAGE_UPDATED", null, JSON.createObjectNode().put("writer", id).put("n", n)));
                        }
                        long[] allocated = repository.update(session, locked.state(), locked.endReason(), false, perWriter, settings.sessionRetention());
                        // hold the lock a moment so writers really queue behind each other
                        try {
                            Thread.sleep(5);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                        repository.insertEvents(session, allocated[0], drafts);
                    });
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        start.countDown();
        for (Thread thread : threads) thread.join();
        done.set(true);
        poller.join();

        List<Rows.Event> all = repository.events(session, before, 1_000);
        assertThat(all).hasSize(writers * perWriter);
        for (int index = 0; index < all.size(); index++) assertThat(all.get(index).seq()).isEqualTo(before + index + 1);
        // the three events of one transaction are consecutive
        for (int writer = 0; writer < writers; writer++) {
            int id = writer;
            List<Long> numbers = all.stream().filter(event -> event.payload().path("writer").intValue() == id).map(Rows.Event::seq).toList();
            assertThat(numbers).hasSize(perWriter);
            assertThat(numbers.get(2) - numbers.get(0)).isEqualTo(2);
        }
        // the poller saw every number once, in order: nothing was skipped
        assertThat(seen).containsExactlyElementsOf(all.stream().map(Rows.Event::seq).toList());
    }

    @Test
    void aStepThatTheDailyBurstWouldExceedIsParkedUntilTomorrowAndTheClientLearnsWhen() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        // PLUS may debit 35% of 360 = 126 credits a day: spend 125 today, so the ten of a medium material do not fit
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            var reservation = ledger.reserve(owner, ReservationScope.SESSION, UUID.randomUUID(), null, 125);
            ledger.settle(owner, new UsageLedger.Debit(reservation.reservationId(), "debit:burst:" + owner, "MATERIAL_DETAILED", 125,
                    null, null));
        });
        UUID session = start(owner, deck, spec("20 глаголов движения"));
        await("the step to be parked", Duration.ofSeconds(10), () -> jdbc.sql("SELECT count(*) FROM app_learning.generation_event WHERE session_id=:id AND type='USAGE_UPDATED' "
                        + "AND payload->>'deferredUntil' IS NOT NULL").param("id", session).query(Integer.class).single() > 0);

        UUID step = stepOf(session);
        Rows.Step parked = steps.step(step).orElseThrow();
        assertThat(parked.state()).isEqualTo("READY");
        assertThat(parked.attempts()).isZero();
        assertThat(parked.nextAttemptAt()).isAfter(Instant.now().plus(Duration.ofMinutes(10)));
        assertThat(provider.calls).isEmpty();
        assertThat(sessionState(session)).isEqualTo("RUNNING");
        assertThat(artifactStates(session)).containsExactly("QUEUED");
        JsonNode usage = null;
        for (JsonNode event : json(events(owner, deck, session, "")).path("events")) {
            if (event.path("type").stringValue("").equals("USAGE_UPDATED")) usage = event.path("payload");
        }
        assertThat(usage).isNotNull();
        assertThat(usage.path("deferredUntil").stringValue(null)).isEqualTo(parked.nextAttemptAt().toString());
        assertThat(usage.path("reservedCredits").intValue()).isEqualTo(10);
        // the hold of the waiting session is kept alive, not left to expire as an orphan
        assertThat(reservationState(session)).isEqualTo("ACTIVE");
        assertThat(cancel(owner, deck, session, UUID.randomUUID()).getStatus()).isEqualTo(200);
        assertThat(reservationState(session)).isEqualTo("RELEASED");
    }

    @Test
    void theNotificationCentersActiveWorkCountsRunningSessionsOnly() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        UUID running = start(owner, deck, spec("[[fake:block]] долго"));
        assertThat(provider.blockedEntered.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(repository.activeWork(owner)).isEqualTo(1);
        provider.release.countDown();
        awaitState(running, "REVIEW");
        assertThat(repository.activeWork(owner)).isZero();
        assertThat(new ActiveGenerationWork(repository).count(owner)).isZero();
    }
}
