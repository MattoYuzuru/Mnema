package app.mnema.learning.generation;

import app.mnema.learning.generation.Rows.Artifact;
import app.mnema.learning.generation.Rows.EventDraft;
import app.mnema.learning.generation.Rows.Revision;
import app.mnema.learning.generation.Rows.Session;
import app.mnema.learning.generation.Rows.Slot;
import app.mnema.learning.generation.Rows.Step;
import app.mnema.learning.generation.mbm.MbmSlot;
import app.mnema.learning.notification.NotificationKind;
import app.mnema.learning.notification.NotificationPublisher;
import app.mnema.learning.notification.NotificationRoute;
import app.mnema.learning.usage.EstimateExceededException;
import app.mnema.learning.usage.ReservationNotActiveException;
import app.mnema.learning.usage.UsageLedger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Every state change of a session, its artifacts and its steps, each in one short transaction. The rule of all of them:
 * <ol>
 *   <li>the session row is locked first ({@link #lock}); it is the one lock of the aggregate and allocates the event
 *       numbers, so events of two transactions are numbered in commit order;</li>
 *   <li>then the artifact and the step are read and written; a step is written only if its lease token still holds
 *       ({@link StepRepository#lockHeld}), so a worker that lost its lease changes nothing;</li>
 *   <li>the usage ledger is called after the domain writes (reservation, balance, then the owner's notification cursor)
 *       and the session's own notifications last, as the usage guide requires;</li>
 *   <li>{@link #flush} writes the session row and its events once.</li>
 * </ol>
 * No provider is ever called from here, and no method is entered with a provider call in flight.
 */
@Service
class SessionLifecycle {
    private static final Logger LOG = LoggerFactory.getLogger(SessionLifecycle.class);
    private static final String MEDIA_CAPABILITY_TTS = "TTS";

    private final GenerationRepository repository;
    private final StepRepository steps;
    private final UsageLedger ledger;
    private final NotificationPublisher notifications;
    private final GenerationSettings settings;
    private final SessionReservations reservations;

    SessionLifecycle(GenerationRepository repository, StepRepository steps, UsageLedger ledger,
                     NotificationPublisher notifications, GenerationSettings settings, SessionReservations reservations) {
        this.repository = repository;
        this.steps = steps;
        this.ledger = ledger;
        this.notifications = notifications;
        this.settings = settings;
        this.reservations = reservations;
    }

    /** The mutable state of one transaction on one locked session. Not thread-safe; never leaves the transaction. */
    static final class Tx {
        final Session session;
        String state;
        String endReason;
        boolean touch = true;
        final List<EventDraft> events = new ArrayList<>();

        Tx(Session session) {
            this.session = session;
            this.state = session.state();
            this.endReason = session.endReason();
        }
    }

    /** What a successful draft produced; written with the debit, the state change and the events in one transaction. */
    record Draft(String promptVersion, String modelRoute, JsonNode document, List<MbmSlot> slots, JsonNode validation,
                 String title, long costMicros, String operation, int credits, Map<String, UUID> handles) { }

    /** How a failed or interrupted run ends. */
    record Failure(Kind kind, String errorCode, Duration delay) {
        enum Kind { RETRY, FAIL, CANCELLED }

        static Failure retry(String errorCode, Duration delay) { return new Failure(Kind.RETRY, errorCode, delay); }

        static Failure fail(String errorCode) { return new Failure(Kind.FAIL, errorCode, Duration.ZERO); }

        static Failure cancelled() { return new Failure(Kind.CANCELLED, null, Duration.ZERO); }
    }

    // ------------------------------------------------------------------- locking

    Tx lock(UUID sessionId) {
        return repository.lockSession(sessionId).map(Tx::new).orElse(null);
    }

    /** Writes the session row and the transaction's events; adds {@code SESSION_STATE} when the state changed. */
    void flush(Tx tx) {
        boolean changed = !tx.state.equals(tx.session.state()) || !Objects.equals(tx.endReason, tx.session.endReason());
        boolean touch = tx.touch || changed;
        List<EventDraft> events = new ArrayList<>(tx.events);
        if (changed) {
            ObjectNode payload = Json.object().put("state", tx.state).put("rowVersion", Long.toString(tx.session.rowVersion() + 1));
            payload.set("artifactCounts", SessionViews.counts(repository.artifactCounts(tx.session.sessionId())));
            events.add(new EventDraft("SESSION_STATE", null, payload));
        }
        if (events.isEmpty() && !touch) return;
        long[] allocated = repository.update(tx.session.sessionId(), tx.state, tx.endReason, touch, events.size(),
                settings.sessionRetention());
        repository.insertEvents(tx.session.sessionId(), allocated[0], events);
    }

    // --------------------------------------------------------------------- begin

    /**
     * The worker starts its claimed step: the artifact becomes GENERATING (once) and the draft counter advances.
     *
     * @return the draft generation of this run, or empty when the claim is void (lease lost, session cancelled)
     */
    @Transactional
    Optional<Integer> begin(StepClaim claim) {
        Tx tx = lock(claim.sessionId());
        if (tx == null) return Optional.empty();
        Optional<Step> held = steps.lockHeld(claim.stepId(), claim.token());
        if (held.isEmpty()) return Optional.empty();
        Artifact artifact = repository.artifact(claim.sessionId(), claim.artifactId()).orElse(null);
        if (held.get().cancelRequested() || artifact == null || !runnable(tx.session) || !open(artifact)) {
            steps.finish(claim.stepId(), "CANCELLED", null, null);
            return Optional.empty();
        }
        int generation = artifact.draftGeneration() + 1;
        repository.raiseDraftGeneration(artifact.artifactId(), generation);
        if (artifact.state().equals("QUEUED")) {
            Artifact moved = repository.transition(artifact, "GENERATING", null, null, null, artifact.revisionCount());
            tx.events.add(artifactEvent(moved));
        } else {
            tx.touch = false;
        }
        flush(tx);
        return Optional.of(generation);
    }

    // ---------------------------------------------------------------- checkpoints

    /** Appends one {@code BLOCKS_APPENDED} event of the running draft; false when the claim is void. */
    @Transactional
    boolean checkpoint(StepClaim claim, int generation, int startIndex, ArrayNode blocks) {
        Tx tx = lock(claim.sessionId());
        if (tx == null) return false;
        Optional<Step> held = steps.lockHeld(claim.stepId(), claim.token());
        if (held.isEmpty() || held.get().cancelRequested()) return false;
        repository.raiseDraftGeneration(claim.artifactId(), generation);
        ObjectNode payload = Json.object().put("generation", generation).put("startIndex", startIndex);
        payload.set("blocks", blocks);
        tx.events.add(new EventDraft("BLOCKS_APPENDED", claim.artifactId(), payload));
        tx.touch = false;
        flush(tx);
        return true;
    }

    // -------------------------------------------------------------------- success

    /**
     * The one result transaction of a successful draft: the debit, the revision, the media slots and their steps, the
     * artifact's move to PROPOSED, the step's SUCCEEDED, the events and the session's move to REVIEW when this was the last
     * open artifact. If the reservation cannot pay, the artifact fails with {@code ESTIMATE_EXCEEDED} and nothing is
     * debited. Returns false, writing nothing, when the lease token no longer holds.
     */
    @Transactional
    boolean succeed(StepClaim claim, Draft draft) {
        Tx tx = lock(claim.sessionId());
        if (tx == null) return false;
        Optional<Step> held = steps.lockHeld(claim.stepId(), claim.token());
        if (held.isEmpty()) return false;
        Artifact artifact = repository.artifact(claim.sessionId(), claim.artifactId()).orElse(null);
        if (held.get().cancelRequested() || artifact == null || !runnable(tx.session) || !open(artifact)) {
            steps.finish(claim.stepId(), "CANCELLED", null, null);
            return false;
        }
        try {
            ledger.settle(tx.session.ownerId(), new UsageLedger.Debit(SessionReservations.forStep(tx.session, held.get().input()),
                    "debit:" + claim.stepId() + ":" + claim.attempt(), draft.operation(), draft.credits(),
                    draft.costMicros(), claim.stepId().toString()));
        } catch (EstimateExceededException | ReservationNotActiveException exceeded) {
            fail(tx, claim, artifact, Failure.fail("ESTIMATE_EXCEEDED"));
            return true;
        }
        UUID revisionId = UUID.randomUUID();
        int revisionNo = artifact.revisionCount() + 1;
        ObjectNode payload = Json.object().put("kind", "NATIVE_DOCUMENT");
        payload.set("document", draft.document());
        ObjectNode handles = Json.object();
        draft.handles().forEach((handle, nodeId) -> handles.put(handle, nodeId.toString()));
        repository.insertRevision(new Revision(revisionId, artifact.artifactId(), revisionNo, "INITIAL", payload, handles,
                draft.promptVersion(), draft.modelRoute(), draft.validation(), Instant.now()), tx.session.sessionId(),
                tx.session.ownerId());
        Artifact proposed = repository.transition(artifact, "PROPOSED", null, revisionId, draft.title(), revisionNo);
        tx.events.add(artifactEvent(proposed));
        for (MbmSlot slot : draft.slots()) {
            ObjectNode spec = Json.object();
            slot.spec().forEach(spec::put);
            repository.insertSlot(new Slot(artifact.artifactId(), slot.slotKey(), revisionId, slot.nodeId(), slot.kind().name(),
                    spec, slot.assetId(), "PENDING", null), tx.session.sessionId(), tx.session.ownerId());
            insertMediaStep(tx.session, artifact, revisionId, slot, spec);
            tx.events.add(slotEvent(artifact.artifactId(), slot.slotKey(), slot.kind().name(), "PENDING", slot.assetId()));
        }
        steps.finish(claim.stepId(), "SUCCEEDED", null, revisionId.toString());
        tx.events.add(usageEvent(tx.session, null));
        settle(tx);
        flush(tx);
        return true;
    }

    private void insertMediaStep(Session session, Artifact artifact, UUID revisionId, MbmSlot slot, ObjectNode spec) {
        String kind;
        String capability;
        switch (slot.kind()) {
            case AUDIO -> {
                kind = "TTS";
                capability = MEDIA_CAPABILITY_TTS;
            }
            case VIDEO -> {
                kind = "VIDEO_GENERATE";
                capability = "VIDEO";
            }
            default -> {
                boolean generate = "generate".equals(slot.spec().get("mode"));
                kind = generate ? "IMAGE_GENERATE" : "IMAGE_SEARCH";
                capability = generate ? "IMAGE" : "IMAGE_SEARCH";
            }
        }
        ObjectNode input = Json.object().put("slotKey", slot.slotKey()).put("assetId", slot.assetId().toString())
                .put("revisionId", revisionId.toString());
        input.set("spec", spec);
        // Child steps are created READY and stay unclaimed until a media executor registers (AI-09, AI-10).
        steps.insert(UUID.randomUUID(), session.sessionId(), artifact.artifactId(), session.ownerId(), kind, capability, input,
                "media:" + artifact.artifactId() + ":" + slot.slotKey() + ":" + revisionId);
    }

    // -------------------------------------------------------------------- failure

    /**
     * A run that did not produce a draft: retried later, failed for good, or cancelled. Writes nothing when the lease
     * token no longer holds.
     */
    @Transactional
    boolean fail(StepClaim claim, Failure failure) {
        Tx tx = lock(claim.sessionId());
        if (tx == null) return false;
        Optional<Step> held = steps.lockHeld(claim.stepId(), claim.token());
        if (held.isEmpty()) return false;
        Artifact artifact = repository.artifact(claim.sessionId(), claim.artifactId()).orElse(null);
        if (artifact == null || failure.kind() == Failure.Kind.CANCELLED || held.get().cancelRequested() || !open(artifact)) {
            steps.finish(claim.stepId(), "CANCELLED", null, null);
            return true;
        }
        fail(tx, claim, artifact, failure);
        return true;
    }

    private void fail(Tx tx, StepClaim claim, Artifact artifact, Failure failure) {
        Step step = steps.step(claim.stepId()).orElseThrow();
        if (failure.kind() == Failure.Kind.RETRY && step.attempts() < settings.step().maxAttempts() && !expired(step)) {
            long delay = Math.min(Math.max(0, failure.delay().toSeconds()), settings.step().backoffCap().toSeconds());
            steps.requeue(step.stepId(), delay, failure.errorCode());
            LOG.info("generation_step_retry step_id={} session_id={} attempt={} error_code={}", step.stepId(), step.sessionId(),
                    step.attempts(), failure.errorCode());
            return;
        }
        String code = failure.kind() == Failure.Kind.RETRY && expired(step) ? "DEADLINE_EXCEEDED" : failure.errorCode();
        steps.finish(step.stepId(), "FAILED", code, null);
        failArtifact(tx, artifact, code);
        settle(tx);
        flush(tx);
    }

    private void failArtifact(Tx tx, Artifact artifact, String errorCode) {
        Artifact failed = repository.transition(artifact, "FAILED", errorCode, null, null, artifact.revisionCount());
        tx.events.add(artifactEvent(failed));
    }

    // ------------------------------------------------------------------- recovery

    /**
     * A claim whose lease ran out (the worker crashed or stalled): the step returns to READY after a backoff, or fails when
     * it has used all its attempts. A stale worker that wakes up later finds its token gone and writes nothing.
     */
    @Transactional
    void recover(UUID stepId) {
        Step probe = steps.step(stepId).orElse(null);
        if (probe == null) return;
        Tx tx = lock(probe.sessionId());
        if (tx == null) return;
        Step step = steps.lockExpired(stepId).orElse(null);
        if (step == null) return;
        Artifact artifact = step.artifactId() == null ? null : repository.artifact(step.sessionId(), step.artifactId()).orElse(null);
        if (step.cancelRequested() || artifact == null || !open(artifact)) {
            steps.finish(stepId, "CANCELLED", null, null);
            return;
        }
        if (step.attempts() >= settings.step().maxAttempts() || expired(step)) {
            boolean late = expired(step) || step.deadlineAt() != null && step.deadlineAt().isBefore(Instant.now());
            steps.finish(stepId, "FAILED", late ? "DEADLINE_EXCEEDED" : "PROVIDER_UNAVAILABLE", null);
            failArtifact(tx, artifact, late ? "DEADLINE_EXCEEDED" : "PROVIDER_UNAVAILABLE");
            settle(tx);
            flush(tx);
            return;
        }
        steps.requeue(stepId, backoff(step.attempts()).toSeconds(), "LEASE_EXPIRED");
        LOG.warn("generation_lease_recovered step_id={} session_id={} attempt={}", stepId, step.sessionId(), step.attempts());
    }

    /** The step has outlived {@code learning.generation.step.max-lifetime} counted from its first claim. */
    private boolean expired(Step step) {
        return step.firstClaimedAt() != null && step.firstClaimedAt().plus(settings.step().maxLifetime()).isBefore(Instant.now());
    }

    /** A READY step whose lifetime ran out while it waited: failed with the deadline code, never claimed again. */
    @Transactional
    void expire(UUID stepId) {
        Step probe = steps.step(stepId).orElse(null);
        if (probe == null) return;
        Tx tx = lock(probe.sessionId());
        if (tx == null) return;
        Step step = steps.lockReady(stepId).orElse(null);
        if (step == null) return;
        Artifact artifact = step.artifactId() == null ? null : repository.artifact(step.sessionId(), step.artifactId()).orElse(null);
        steps.finish(stepId, "FAILED", "DEADLINE_EXCEEDED", null);
        if (artifact != null && open(artifact)) {
            failArtifact(tx, artifact, "DEADLINE_EXCEEDED");
            settle(tx);
            flush(tx);
        }
    }

    Duration backoff(int attempts) {
        Duration cap = settings.step().backoffCap();
        long exponential = settings.step().backoffBase().toMillis() << Math.min(Math.max(attempts - 1, 0), 20);
        long millis = Math.min(cap.toMillis(), exponential < 0 ? Long.MAX_VALUE : exponential);
        // Equal jitter: at least half the exponential wait, so a crash loop cannot spin.
        return Duration.ofMillis(millis / 2 + java.util.concurrent.ThreadLocalRandom.current().nextLong(millis / 2 + 1));
    }

    // ---------------------------------------------------------------- reservations

    /**
     * A step was parked by the daily burst: the session's hold is kept alive and the client learns when work resumes
     * ({@code USAGE_UPDATED.deferredUntil}). Visible state does not change, so the session version stays.
     */
    @Transactional
    void parked(UUID sessionId, Instant until) {
        Tx tx = lock(sessionId);
        if (tx == null || !tx.state.equals("RUNNING")) return;
        renew(tx.session);
        tx.events.add(usageEvent(tx.session, until));
        tx.touch = false;
        flush(tx);
    }

    /** Keeps the holds of a running session (its initial batch and its retried artifacts) from expiring as orphans. */
    @Transactional
    void renew(Session session) {
        for (UUID reservation : reservations.ids(session)) {
            try {
                ledger.renew(session.ownerId(), reservation);
            } catch (ReservationNotActiveException | IllegalArgumentException ended) {
                LOG.info("generation_reservation_not_renewed session_id={}", session.sessionId());
            }
        }
    }

    // -------------------------------------------------------------------- cancel

    /**
     * Cancels the session in this transaction (the caller holds the lock): waiting steps CANCELLED, running steps get
     * {@code cancel_requested}, unfinished artifacts FAILED(CANCELLED), the reservation released except what was consumed.
     */
    void cancel(Tx tx) {
        stopWork(tx);
        tx.state = "CANCELLED";
        tx.endReason = "USER_CANCELLED";
    }

    /** Waiting steps stop, running ones are told to, unfinished artifacts fail as cancelled and the holds are released. */
    private void stopWork(Tx tx) {
        steps.cancelWaiting(tx.session.sessionId());
        steps.requestCancel(tx.session.sessionId());
        // media slots whose steps were just cancelled end FAILED(CANCELLED) (states.json mediaSlot) and announce it
        for (Slot slot : repository.failOpenSlots(tx.session.sessionId(), "CANCELLED")) {
            ObjectNode payload = Json.object().put("slotKey", slot.slotKey()).put("kind", slot.kind()).put("state", "FAILED")
                    .put("assetId", slot.assetId().toString()).put("errorCode", "CANCELLED");
            tx.events.add(new EventDraft("MEDIA_SLOT_STATE", slot.artifactId(), payload));
        }
        for (Artifact artifact : repository.artifacts(tx.session.sessionId())) {
            if (artifact.state().equals("QUEUED") || artifact.state().equals("GENERATING")) {
                failArtifact(tx, artifact, "CANCELLED");
            }
        }
        releaseReservation(tx);
    }

    /**
     * The session reached {@code expires_at} without being closed: it stops as {@link #cancel} does and ends EXPIRED, which
     * stays readable until the retention worker purges it. Expiry and activity do not move (the purge is timed from them),
     * so this writes the row itself instead of {@link #flush}.
     */
    void expire(Tx tx) {
        stopWork(tx);
        tx.state = "EXPIRED";
        tx.endReason = "EXPIRED";
        ObjectNode payload = Json.object().put("state", "EXPIRED").put("rowVersion", Long.toString(tx.session.rowVersion() + 1));
        payload.set("artifactCounts", SessionViews.counts(repository.artifactCounts(tx.session.sessionId())));
        tx.events.add(new EventDraft("SESSION_STATE", null, payload));
        long first = repository.expire(tx.session.sessionId(), tx.events.size());
        repository.insertEvents(tx.session.sessionId(), first, tx.events);
    }

    /**
     * After an artifact left review (approved, handed off, rejected): a REVIEW session with nothing to review, retry or wait
     * for, or a CANCELLED one with no proposal left, is CLOSED (contract decision 3). The caller flushes.
     */
    void closeIfDone(Tx tx) {
        if (!tx.state.equals("REVIEW") && !tx.state.equals("CANCELLED")) return;
        Map<String, Integer> counts = repository.artifactCounts(tx.session.sessionId());
        boolean open = counts.get("PROPOSED") + counts.get("REVISING") + counts.get("STALE") > 0;
        if (tx.state.equals("REVIEW")) {
            open = open || counts.get("QUEUED") + counts.get("GENERATING") > 0
                    || repository.retryableFailures(tx.session.sessionId()) > 0;
        }
        if (!open) tx.state = "CLOSED";
    }

    // ------------------------------------------------------------ session settling

    /**
     * Evaluates the session after an artifact transition: a RUNNING session with no QUEUED or GENERATING artifact moves to
     * REVIEW (something to review) or CLOSED (nothing left), its initial-batch reservation is released and the owner is
     * notified. Notifications come last, after the usage calls.
     */
    void settle(Tx tx) {
        if (!tx.state.equals("RUNNING")) return;
        Map<String, Integer> counts = repository.artifactCounts(tx.session.sessionId());
        if (counts.get("QUEUED") + counts.get("GENERATING") > 0) return;
        boolean open = counts.get("PROPOSED") + counts.get("REVISING") + counts.get("STALE")
                + repository.retryableFailures(tx.session.sessionId()) > 0;
        releaseReservation(tx);
        tx.state = open ? "REVIEW" : "CLOSED";
        notifyOutcome(tx, counts);
    }

    /** Ends every hold of the session (initial batch and retries); what was consumed stays consumed. */
    void releaseReservation(Tx tx) {
        if (releaseHolds(tx.session)) tx.events.add(usageEvent(tx.session, null));
    }

    /** Ends every hold of the session in the caller's transaction, announcing nothing; false when it had none. */
    boolean releaseHolds(Session session) {
        List<UUID> held = reservations.ids(session);
        for (UUID reservation : held) {
            try {
                ledger.release(session.ownerId(), reservation);
            } catch (IllegalArgumentException unknown) {
                LOG.warn("generation_reservation_missing session_id={}", session.sessionId());
            }
        }
        return !held.isEmpty();
    }

    /**
     * {@code GENERATION_READY} when every artifact is approvable now, {@code GENERATION_PARTIAL} when some are and some
     * failed, {@code GENERATION_FAILED} when none is approvable and something failed ({@code contracts/notifications}).
     * A proposal whose media is still being made is not approvable yet: with no failure nothing is published here.
     * TODO(AI-09, AI-10: media steps): publish {@code GENERATION_READY} when the last slot of a REVIEW session resolves;
     * until media executors exist the slots stay PENDING and a session with media never announces itself as ready.
     */
    private void notifyOutcome(Tx tx, Map<String, Integer> counts) {
        UUID sessionId = tx.session.sessionId();
        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        int failed = counts.get("FAILED");
        int approvable = repository.approvableCount(sessionId);
        Map<String, Object> params = new HashMap<>();
        params.put("deckId", tx.session.deckId());
        params.put("sessionId", sessionId);
        params.put("sessionKind", tx.session.kind());
        if (failed == 0 && approvable == total && total > 0) {
            params.put("artifactCount", total);
            params.put("approvableCount", approvable);
            notifications.publish(tx.session.ownerId(), NotificationKind.GENERATION_READY, "generation:" + sessionId + ":ready",
                    params, NotificationRoute.WORKSHOP);
        } else if (failed > 0 && approvable > 0) {
            params.put("approvableCount", approvable);
            params.put("failedCount", failed);
            notifications.publish(tx.session.ownerId(), NotificationKind.GENERATION_PARTIAL, "generation:" + sessionId + ":partial",
                    params, NotificationRoute.WORKSHOP);
        } else if (failed > 0 && counts.get("PROPOSED") + counts.get("REVISING") + counts.get("STALE") == 0) {
            List<String> codes = repository.failureCodes(sessionId);
            params.put("errorCode", codes.isEmpty() ? "UNKNOWN" : codes.getFirst());
            notifications.publish(tx.session.ownerId(), NotificationKind.GENERATION_FAILED, "generation:" + sessionId + ":failed",
                    params, NotificationRoute.WORKSHOP);
        }
    }

    // --------------------------------------------------------------------- events

    static EventDraft artifactEvent(Artifact artifact) {
        ObjectNode payload = Json.object().put("state", artifact.state()).put("artifactVersion", Long.toString(artifact.rowVersion()));
        payload.put("currentRevisionId", artifact.currentRevisionId() == null ? null : artifact.currentRevisionId().toString());
        payload.put("errorCode", artifact.errorCode());
        payload.put("repinStatus", artifact.repinStatus());
        return new EventDraft("ARTIFACT_STATE", artifact.artifactId(), payload);
    }

    static EventDraft slotEvent(UUID artifactId, String slotKey, String kind, String state, UUID assetId) {
        ObjectNode payload = Json.object().put("slotKey", slotKey).put("kind", kind).put("state", state)
                .put("assetId", assetId.toString());
        payload.putNull("errorCode");
        return new EventDraft("MEDIA_SLOT_STATE", artifactId, payload);
    }

    /** {@code USAGE_UPDATED}: the session's holds and spending now, and the account's remaining credits. */
    EventDraft usageEvent(Session session, Instant deferredUntil) {
        SessionReservations.Totals totals = reservations.totals(session);
        ObjectNode payload = Json.object().put("reservedCredits", totals.reserved()).put("spentCredits", totals.spent())
                .put("balanceRemainingCredits", ledger.remainingCredits(session.ownerId()));
        payload.put("deferredUntil", deferredUntil == null ? null : Json.time(deferredUntil));
        return new EventDraft("USAGE_UPDATED", null, payload);
    }

    // ------------------------------------------------------------------- helpers

    /** Steps of these sessions may run: a RUNNING session, or a REVIEW one whose media steps still work. */
    private static boolean runnable(Session session) {
        return session.state().equals("RUNNING") || session.state().equals("REVIEW");
    }

    private static boolean open(Artifact artifact) {
        return artifact.state().equals("QUEUED") || artifact.state().equals("GENERATING");
    }
}
