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
import app.mnema.learning.usage.AdmissionPricing;
import app.mnema.learning.usage.Bucket;
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
    /** The rate-card operation an {@code EXERCISES} step is debited under (five exercises per unit). */
    static final String EXERCISES_OPERATION = "EXERCISES_PER_MATERIAL";

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
            fail(tx, claim, List.of(artifact), Failure.fail("ESTIMATE_EXCEEDED"));
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
        // Only registered media kinds are claimed; unsupported kinds remain READY.
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
        if (held.get().kind().equals(PlanExecutor.KIND)) {
            failPlan(tx, held.get(), failure);
            return true;
        }
        List<Artifact> open = openArtifacts(held.get());
        if (open.isEmpty() || failure.kind() == Failure.Kind.CANCELLED || held.get().cancelRequested()) {
            steps.finish(claim.stepId(), "CANCELLED", null, null);
            return true;
        }
        fail(tx, claim, open, failure);
        return true;
    }

    /** One run that did not produce a result: a retry later (nothing changes) or the end of every artifact it still owed. */
    private void fail(Tx tx, StepClaim claim, List<Artifact> artifacts, Failure failure) {
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
        for (Artifact artifact : artifacts) failArtifact(tx, artifact, code);
        settle(tx);
        flush(tx);
    }

    /**
     * The artifacts a step works on that are still being written: the {@code artifactIds} of its input (an {@code EXERCISES} step
     * fills several), else its own artifact. Artifacts that left QUEUED and GENERATING (cancelled, failed, stale) are not listed.
     */
    private List<Artifact> openArtifacts(Step step) {
        List<UUID> ids = new ArrayList<>();
        if (step.input().path("artifactIds").isArray()) {
            step.input().path("artifactIds").forEach(id -> ids.add(UUID.fromString(id.stringValue(""))));
        } else if (step.artifactId() != null) {
            ids.add(step.artifactId());
        }
        List<Artifact> open = new ArrayList<>();
        for (UUID id : ids) repository.artifact(step.sessionId(), id).filter(SessionLifecycle::open).ifPresent(open::add);
        return open;
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
        if (step.kind().equals(PlanExecutor.KIND)) {
            // a planner whose worker crashed or stalled: another run, or the plan fails after its last attempt (the session is cancelled)
            boolean late = expired(step) || step.deadlineAt() != null && step.deadlineAt().isBefore(Instant.now());
            failPlan(tx, step, step.attempts() >= settings.step().maxAttempts() || expired(step)
                    ? Failure.fail(late ? "DEADLINE_EXCEEDED" : "PROVIDER_UNAVAILABLE") : Failure.retry("LEASE_EXPIRED", backoff(step.attempts())));
            return;
        }
        List<Artifact> open = openArtifacts(step);
        if (step.cancelRequested() || open.isEmpty()) {
            steps.finish(stepId, "CANCELLED", null, null);
            return;
        }
        if (step.attempts() >= settings.step().maxAttempts() || expired(step)) {
            boolean late = expired(step) || step.deadlineAt() != null && step.deadlineAt().isBefore(Instant.now());
            steps.finish(stepId, "FAILED", late ? "DEADLINE_EXCEEDED" : "PROVIDER_UNAVAILABLE", null);
            for (Artifact artifact : open) failArtifact(tx, artifact, late ? "DEADLINE_EXCEEDED" : "PROVIDER_UNAVAILABLE");
            settle(tx);
            flush(tx);
            return;
        }
        steps.requeue(stepId, backoff(step.attempts()).toSeconds(), "LEASE_EXPIRED");
        LOG.warn("generation_lease_recovered step_id={} session_id={} attempt={}", stepId, step.sessionId(), step.attempts());
    }

    /** The step has outlived {@code learning.generation.step.max-lifetime} counted from its first claim. */
    boolean expired(Step step) {
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
        if (step.kind().equals(PlanExecutor.KIND)) {
            failPlan(tx, step, Failure.fail("DEADLINE_EXCEEDED"));
            return;
        }
        List<Artifact> open = openArtifacts(step);
        steps.finish(stepId, "FAILED", "DEADLINE_EXCEEDED", null);
        if (!open.isEmpty()) {
            for (Artifact artifact : open) failArtifact(tx, artifact, "DEADLINE_EXCEEDED");
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
            } catch (ReservationNotActiveException settled) {
                // a hold that ended (the plan's, spent; a lapsed one) is normal and needs no renewal
                LOG.debug("generation_reservation_ended session_id={}", session.sessionId());
            } catch (IllegalArgumentException unknown) {
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
        cancel(tx, "USER_CANCELLED");
    }

    /** As {@link #cancel(Tx)} with the reason the session ends for: {@code USER_CANCELLED}, or {@code PLAN_FAILED} when the planner gave up. */
    void cancel(Tx tx, String endReason) {
        stopWork(tx);
        tx.state = "CANCELLED";
        tx.endReason = endReason;
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
            } else if (artifact.state().equals("REVISING")) {
                // an edit in flight is cancelled with its step: the proposal is what it was before the edit (contract states.json turn)
                tx.events.add(artifactEvent(repository.transition(artifact, "PROPOSED", null, null, null, artifact.revisionCount())));
            }
        }
        repository.cancelSessionTurns(tx.session.sessionId());
        releaseReservation(tx, false);
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
        releaseReservation(tx, true);
        tx.state = open ? "REVIEW" : "CLOSED";
        // a revision is made while the person waits in the Workshop: nothing to announce
        if (!isRevision(tx.session)) notifyOutcome(tx, counts);
    }

    /** A REVISE_ITEM or REVISE_EXERCISE session (#294): one artifact, revised turn by turn. */
    static boolean isRevision(Session session) {
        return session.kind().startsWith("REVISE_");
    }

    /**
     * After a turn of a revision ended with its artifact PROPOSED again: the session leaves RUNNING for REVIEW (a revision has no initial
     * batch to wait for, only turns). Nothing for any other kind of session, whose artifacts settle through their own steps. The caller flushes.
     */
    void settleRevision(Tx tx) {
        if (isRevision(tx.session)) settle(tx);
    }

    /**
     * Ends the holds of the session (initial batch, retries and edit turns); what was consumed stays consumed. A session that
     * merely leaves RUNNING ({@code keepOpenEdits}) keeps the holds of the edits still working: they end with their turn.
     */
    void releaseReservation(Tx tx, boolean keepOpenEdits) {
        if (releaseHolds(tx.session, keepOpenEdits)) tx.events.add(usageEvent(tx.session, null));
    }

    /**
     * The session left RUNNING while image searches of slots were open and its batch hold was kept for them: when no such step is open any more
     * (the last one ended, or was cancelled by a removal, a replacement or a hand-off) the hold ends and what was debited stays debited. A RUNNING
     * session releases it with its own settling, a PLANNING one has no use for this. The caller flushes.
     */
    void releaseIdleBatch(Tx tx) {
        if (!(tx.state.equals("REVIEW") || tx.state.equals("CLOSED")) || tx.session.reservationId() == null
                || steps.hasOpenSlotSteps(tx.session.sessionId())) {
            return;
        }
        boolean active = ledger.reservation(tx.session.ownerId(), tx.session.reservationId())
                .filter(found -> found.state() == app.mnema.learning.usage.ReservationState.ACTIVE).isPresent();
        if (!active) return;
        try {
            ledger.release(tx.session.ownerId(), tx.session.reservationId());
        } catch (IllegalArgumentException unknown) {
            LOG.warn("generation_reservation_missing session_id={}", tx.session.sessionId());
        }
        tx.events.add(usageEvent(tx.session, null));
    }

    /** Ends every hold of the session in the caller's transaction, announcing nothing; false when it had none. */
    boolean releaseHolds(Session session) {
        return releaseHolds(session, false);
    }

    private boolean releaseHolds(Session session, boolean keepOpenEdits) {
        List<UUID> held = new ArrayList<>(reservations.ids(session));
        if (keepOpenEdits) {
            held.removeAll(steps.openEditReservations(session.sessionId()));
            // an image search of a slot still running pays from the batch hold: it ends with the last of them (ImageSearchLifecycle)
            if (session.reservationId() != null && steps.hasOpenSlotSteps(session.sessionId())) held.remove(session.reservationId());
        }
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
     * An initial media step of a slot (image search or speech) ended, in the caller's transaction and after its usage calls: the batch hold is released
     * once no such step is open, and a session that had already left RUNNING with its media still being made now announces its outcome
     * ({@link #notifyOutcome}), exactly once whichever slot finishes last. A RUNNING session settles later through {@link #settle}, which sees the same
     * state; a revision announces nothing. The session lock serialises the slots of a session, and the notification's deduplication key
     * ({@code generation:{sessionId}:ready}) makes a repeated evaluation harmless.
     */
    void slotStepEnded(Tx tx) {
        releaseIdleBatch(tx);
        if (isRevision(tx.session) || !(tx.state.equals("REVIEW") || tx.state.equals("CLOSED"))
                || steps.hasOpenSlotSteps(tx.session.sessionId())) {
            return;
        }
        notifyOutcome(tx, repository.artifactCounts(tx.session.sessionId()));
    }

    /**
     * {@code GENERATION_READY} when every artifact is proposed, none failed and no media step is still working (a slot that failed counts as finished:
     * the owner has something to review, and {@code approvableCount} says how much of it can be approved now), {@code GENERATION_PARTIAL} when some
     * are approvable and some failed, {@code GENERATION_FAILED} when none is approvable and something failed ({@code contracts/notifications}).
     * Nothing is announced while a media step of a slot is still working; {@link #slotStepEnded} evaluates the same outcome when the last one ends. Each kind is
     * published at most once per session (deduplication key), so a retry in the Workshop, of a text or of a media redo, never announces a kind again; a text retry
     * that reopens the session settles it once more and may publish the kind not yet published (for example READY after PARTIAL), a media redo (a turn) announces nothing.
     */
    private void notifyOutcome(Tx tx, Map<String, Integer> counts) {
        UUID sessionId = tx.session.sessionId();
        // an outcome is announced once nothing of the session is still being made: media that is still working can turn "nothing approvable" into "ready"
        if (steps.hasOpenSlotSteps(sessionId)) return;
        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        int failed = counts.get("FAILED");
        int approvable = repository.approvableCount(sessionId);
        Map<String, Object> params = new HashMap<>();
        params.put("deckId", tx.session.deckId());
        params.put("sessionId", sessionId);
        params.put("sessionKind", tx.session.kind());
        if (failed == 0 && total > 0 && counts.get("PROPOSED") == total) {
            params.put("artifactCount", total);
            params.put("approvableCount", approvable);
            notifications.publish(tx.session.ownerId(), NotificationKind.GENERATION_READY, "generation:" + sessionId + ":ready",
                    params, NotificationRoute.WORKSHOP);
        } else if (failed > 0 && approvable > 0) {
            params.put("approvableCount", approvable);
            params.put("failedCount", failed);
            notifications.publish(tx.session.ownerId(), NotificationKind.GENERATION_PARTIAL, "generation:" + sessionId + ":partial",
                    params, NotificationRoute.WORKSHOP);
        } else if (failed > 0 && counts.get("REVISING") + counts.get("STALE") == 0) {
            // nothing is approvable (a proposal whose media failed counts as not approvable) and something failed: the contract's GENERATION_FAILED
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
    static boolean runnable(Session session) {
        return session.state().equals("RUNNING") || session.state().equals("REVIEW");
    }

    static boolean open(Artifact artifact) {
        return artifact.state().equals("QUEUED") || artifact.state().equals("GENERATING");
    }

    // ------------------------------------------------------------------------ planner

    /**
     * The worker starts its claimed {@code PLAN} step: the session must still be PLANNING and the step not cancelled. Nothing is written (the
     * plan has no state of its own until it is ready), so the session version stays.
     *
     * @return false when the claim is void (lease lost, session cancelled or deleted)
     */
    @Transactional
    boolean beginPlan(StepClaim claim) {
        Tx tx = lock(claim.sessionId());
        if (tx == null) return false;
        Optional<Step> held = steps.lockHeld(claim.stepId(), claim.token());
        if (held.isEmpty()) return false;
        if (held.get().cancelRequested() || !tx.state.equals("PLANNING")) {
            steps.finish(claim.stepId(), "CANCELLED", null, null);
            return false;
        }
        return true;
    }

    /**
     * The one result transaction of a successful plan: the plan's debit ({@code SMART_PLAN_FLASH}, from the plan's own hold, apart from the batch
     * hold that stays reserved), the smart-plan count of the owner's cap, the plan stored, the step SUCCEEDED, the session PLAN_READY and the owner told.
     * Returns false, writing nothing, when the lease no longer holds or the session left PLANNING (a cancellation: the plan was not delivered, so it
     * is not paid).
     *
     * @throws app.mnema.learning.usage.UsageLimitReachedException the cap filled meanwhile: the transaction rolled back, nothing was written
     * @throws PlanUnpayableException the plan's hold ended or no longer covers its price: the transaction rolled back, nothing was written; the
     *                                caller fails the plan with {@code ESTIMATE_EXCEEDED}
     */
    @Transactional
    boolean succeedPlan(StepClaim claim, JsonNode plan, long costMicros, int plannedCount) {
        Tx tx = lock(claim.sessionId());
        if (tx == null) return false;
        Optional<Step> held = steps.lockHeld(claim.stepId(), claim.token());
        if (held.isEmpty()) return false;
        if (held.get().cancelRequested() || !tx.state.equals("PLANNING")) {
            steps.finish(claim.stepId(), "CANCELLED", null, null);
            return false;
        }
        UUID reservation = SessionReservations.forStep(tx.session, held.get().input());
        // The cap first, then the debit (counters before the reservation and the balance, so a count that fits is never left behind by a debit that does
        // not): a refusal of either rolls this transaction back, nothing of the plan is paid.
        ledger.consume(tx.session.ownerId(), Bucket.SMART_PLAN, 1, "plan:" + tx.session.ownerId() + ":" + tx.session.sessionId(),
                claim.stepId().toString());
        try {
            ledger.settle(tx.session.ownerId(), new UsageLedger.Debit(reservation, "debit:" + claim.stepId() + ":" + claim.attempt(),
                    AdmissionPricing.PLAN_OPERATION, held.get().input().path("credits").asInt(0), costMicros, claim.stepId().toString()));
        } catch (EstimateExceededException | ReservationNotActiveException exceeded) {
            // thrown, not handled here: the count above must not stay consumed for a plan that is not delivered
            throw new PlanUnpayableException();
        }
        // the plan's hold is spent: it ends settled; the batch hold of the session stays until the plan is launched or cancelled
        ledger.release(tx.session.ownerId(), reservation);
        repository.setPlan(tx.session.sessionId(), plan);
        steps.finish(claim.stepId(), "SUCCEEDED", null, null);
        tx.state = "PLAN_READY";
        tx.events.add(usageEvent(tx.session, null));
        notifications.publish(tx.session.ownerId(), NotificationKind.GENERATION_PLAN_READY, "generation:" + tx.session.sessionId() + ":plan-ready",
                Map.of("deckId", tx.session.deckId(), "sessionId", tx.session.sessionId(), "sessionKind", tx.session.kind(),
                        "plannedCount", Math.max(1, plannedCount)), NotificationRoute.WORKSHOP);
        flush(tx);
        return true;
    }

    /** The plan was made but its own hold cannot pay for it any more (ended, or less than its price): thrown out of the result transaction so nothing is kept. */
    static final class PlanUnpayableException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        PlanUnpayableException() {
            super("The plan's hold cannot pay for the plan", null, false, false);
        }
    }

    /**
     * A PLAN run that did not produce a plan: retried later, or failed for good. A failed plan cancels the session ({@code endReason PLAN_FAILED}, contract
     * decision 3): the holds are released, nothing was debited, the owner is told ({@code GENERATION_FAILED}). A cancellation, or a session that already
     * left PLANNING, only ends the step. The caller holds the session and step locks.
     */
    private void failPlan(Tx tx, Step step, Failure failure) {
        if (failure.kind() == Failure.Kind.CANCELLED || step.cancelRequested() || !tx.state.equals("PLANNING")) {
            steps.finish(step.stepId(), "CANCELLED", null, null);
            return;
        }
        if (failure.kind() == Failure.Kind.RETRY && step.attempts() < settings.step().maxAttempts() && !expired(step)) {
            long delay = Math.min(Math.max(0, failure.delay().toSeconds()), settings.step().backoffCap().toSeconds());
            steps.requeue(step.stepId(), delay, failure.errorCode());
            LOG.info("generation_step_retry step_id={} session_id={} attempt={} error_code={}", step.stepId(), step.sessionId(), step.attempts(),
                    failure.errorCode());
            return;
        }
        String code = failure.kind() == Failure.Kind.RETRY && expired(step) ? "DEADLINE_EXCEEDED" : failure.errorCode();
        steps.finish(step.stepId(), "FAILED", code, null);
        cancel(tx, "PLAN_FAILED");
        notifications.publish(tx.session.ownerId(), NotificationKind.GENERATION_FAILED, "generation:" + tx.session.sessionId() + ":failed",
                Map.of("deckId", tx.session.deckId(), "sessionId", tx.session.sessionId(), "sessionKind", tx.session.kind(),
                        "errorCode", "PLAN_FAILED"), NotificationRoute.WORKSHOP);
        flush(tx);
    }

    // ------------------------------------------------------------------ exercises

    /** One validated exercise of an {@code EXERCISES} step: the artifact it fills and the command it proposes. */
    record Proposal(UUID artifactId, ObjectNode command, String title, List<String> warnings) { }

    /**
     * What an {@code EXERCISES} run produced: the exercises that passed validation (at most one per open artifact of the step, in the
     * order of the artifacts; the artifacts without one fail with {@code INVALID_OUTPUT}). {@code credits} is the step's share of the
     * session hold and {@code planned} the exercises it was asked for: the debit is that share for the exercises that were produced.
     * {@code handles} are the blocks the model was shown ({@code m1:b3 -> node id}), kept on every revision: a re-pin may follow the
     * material only if all of them are unchanged. {@code failureCode} is the error of the artifacts that got no exercise.
     */
    record ExerciseDraft(String promptVersion, String modelRoute, List<Proposal> proposals, long costMicros, int credits, int planned,
                         Map<String, String> handles, String failureCode) { }

    /**
     * The worker starts an {@code EXERCISES} step: its QUEUED artifacts become GENERATING. There is no draft stream: a proposal
     * appears whole, when the step has validated it.
     *
     * @return false when the claim is void (lease lost, session cancelled, nothing left to write)
     */
    @Transactional
    boolean beginExercises(StepClaim claim) {
        Tx tx = lock(claim.sessionId());
        if (tx == null) return false;
        Optional<Step> held = steps.lockHeld(claim.stepId(), claim.token());
        if (held.isEmpty()) return false;
        List<Artifact> open = openArtifacts(held.get());
        if (held.get().cancelRequested() || !runnable(tx.session) || open.isEmpty()) {
            steps.finish(claim.stepId(), "CANCELLED", null, null);
            return false;
        }
        boolean changed = false;
        for (Artifact artifact : open) {
            if (!artifact.state().equals("QUEUED")) continue;
            tx.events.add(artifactEvent(repository.transition(artifact, "GENERATING", null, null, null, artifact.revisionCount())));
            changed = true;
        }
        if (!changed) tx.touch = false;
        flush(tx);
        return true;
    }

    /**
     * The one result transaction of an {@code EXERCISES} step: the debit for the exercises produced, one INITIAL revision and the
     * move to PROPOSED for each, FAILED({@code INVALID_OUTPUT}) for the artifacts that got none, the step's end and the events. If the
     * reservation cannot pay, every artifact of the step fails with {@code ESTIMATE_EXCEEDED} and nothing is debited. Returns false,
     * writing nothing, when the lease token no longer holds.
     */
    @Transactional
    boolean succeedExercises(StepClaim claim, ExerciseDraft draft) {
        Tx tx = lock(claim.sessionId());
        if (tx == null) return false;
        Optional<Step> held = steps.lockHeld(claim.stepId(), claim.token());
        if (held.isEmpty()) return false;
        List<Artifact> open = openArtifacts(held.get());
        if (held.get().cancelRequested() || !runnable(tx.session) || open.isEmpty()) {
            steps.finish(claim.stepId(), "CANCELLED", null, null);
            return false;
        }
        Map<UUID, Proposal> proposals = new HashMap<>();
        draft.proposals().forEach(proposal -> proposals.put(proposal.artifactId(), proposal));
        if (!proposals.isEmpty()) {
            // the step's share of the hold, in proportion to what was produced: the shares of all steps add up to the hold
            int credits = (int) (((long) draft.credits() * proposals.size() + draft.planned() - 1) / draft.planned());
            try {
                ledger.settle(tx.session.ownerId(), new UsageLedger.Debit(SessionReservations.forStep(tx.session, held.get().input()),
                        "debit:" + claim.stepId() + ":" + claim.attempt(), EXERCISES_OPERATION, credits, draft.costMicros(),
                        claim.stepId().toString()));
            } catch (EstimateExceededException | ReservationNotActiveException exceeded) {
                fail(tx, claim, open, Failure.fail("ESTIMATE_EXCEEDED"));
                return true;
            }
        }
        String firstRevision = null;
        for (Artifact artifact : open) {
            Proposal proposal = proposals.get(artifact.artifactId());
            if (proposal == null) {
                failArtifact(tx, artifact, draft.failureCode());
                continue;
            }
            UUID revisionId = UUID.randomUUID();
            int revisionNo = artifact.revisionCount() + 1;
            ObjectNode payload = Json.object().put("kind", "EXERCISE_COMMAND");
            payload.set("command", proposal.command());
            ObjectNode validation = Json.object();
            var warnings = validation.putArray("warnings");
            proposal.warnings().forEach(code -> warnings.addObject().put("code", code));
            ObjectNode handles = Json.object();
            draft.handles().forEach(handles::put);
            repository.insertRevision(new Revision(revisionId, artifact.artifactId(), revisionNo, "INITIAL", payload, handles,
                    draft.promptVersion(), draft.modelRoute(), validation, Instant.now()), tx.session.sessionId(), tx.session.ownerId());
            tx.events.add(artifactEvent(repository.transition(artifact, "PROPOSED", null, revisionId, proposal.title(), revisionNo)));
            if (firstRevision == null) firstRevision = revisionId.toString();
        }
        if (proposals.isEmpty()) steps.finish(claim.stepId(), "FAILED", draft.failureCode(), null);
        else steps.finish(claim.stepId(), "SUCCEEDED", null, firstRevision);
        if (!proposals.isEmpty()) tx.events.add(usageEvent(tx.session, null));
        settle(tx);
        flush(tx);
        return true;
    }
}
