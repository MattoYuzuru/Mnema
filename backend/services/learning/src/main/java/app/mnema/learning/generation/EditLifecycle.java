package app.mnema.learning.generation;

import app.mnema.learning.generation.Rows.Artifact;
import app.mnema.learning.generation.Rows.Revision;
import app.mnema.learning.generation.Rows.Slot;
import app.mnema.learning.generation.Rows.Step;
import app.mnema.learning.generation.Rows.Turn;
import app.mnema.learning.generation.SessionLifecycle.Failure;
import app.mnema.learning.generation.SessionLifecycle.Tx;
import app.mnema.learning.usage.EstimateExceededException;
import app.mnema.learning.usage.ReservationNotActiveException;
import app.mnema.learning.usage.UsageLedger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The state changes of an EDIT step, each in one short transaction under the session lock like {@link SessionLifecycle}: the turn
 * and the artifact move together (QUEUED/RUNNING turn with REVISING artifact; APPLIED/FAILED/CANCELLED turn with a PROPOSED one), the
 * turn's own reservation is debited and released with the result and released without a debit on every other end, and a step that
 * lost its lease or its lifetime fails the turn instead of leaving the artifact REVISING. No method is entered with a provider call in
 * flight.
 */
@Service
class EditLifecycle {
    private static final Logger LOG = LoggerFactory.getLogger(EditLifecycle.class);
    /** The rate-card operation an edit is debited under. */
    static final String OPERATION = "EDIT_SELECTION";
    /** The rate-card operation the redo of the audio of an exercise is reserved under. */
    static final String MEDIA_OPERATION = "TTS_CLIP_30S";

    private final GenerationRepository repository;
    private final StepRepository steps;
    private final SessionLifecycle lifecycle;
    private final UsageLedger ledger;
    private final GenerationSettings settings;
    private final ObjectProvider<StepDispatcher> dispatcher;

    EditLifecycle(GenerationRepository repository, StepRepository steps, SessionLifecycle lifecycle, UsageLedger ledger,
                  GenerationSettings settings, ObjectProvider<StepDispatcher> dispatcher) {
        this.repository = repository;
        this.steps = steps;
        this.lifecycle = lifecycle;
        this.ledger = ledger;
        this.settings = settings;
        this.dispatcher = dispatcher;
    }

    /**
     * What a valid rewrite produced: the payload of the new revision, its title, validation and handles, the route and prompt version
     * that wrote it and what the provider calls cost. For a material {@code document} is the new document (the old one with the target
     * range replaced) and tells which media slots survive; for an exercise it is null (the payload is {@code EXERCISE_COMMAND}).
     */
    record Result(JsonNode payload, JsonNode document, String title, JsonNode validation, String promptVersion, String modelRoute,
                  long costMicros, Map<String, UUID> handles) {
        static Result ofDocument(JsonNode document, String title, JsonNode validation, String promptVersion, String modelRoute,
                                 long costMicros, Map<String, UUID> handles) {
            ObjectNode payload = Json.object().put("kind", "NATIVE_DOCUMENT");
            payload.set("document", document);
            return new Result(payload, document, title, validation, promptVersion, modelRoute, costMicros, handles);
        }

        /** @param command {@code {objective, exercise}} */
        static Result ofExercise(JsonNode command, String title, JsonNode validation, String promptVersion, String modelRoute,
                                 long costMicros) {
            ObjectNode payload = Json.object().put("kind", "EXERCISE_COMMAND");
            payload.set("command", command);
            return new Result(payload, null, title, validation, promptVersion, modelRoute, costMicros, Map.of());
        }
    }

    // ---------------------------------------------------------------------- begin

    /**
     * The worker starts its claimed step: the turn becomes RUNNING (once).
     *
     * @return the turn, or empty when the claim is void (lease lost, turn or session cancelled)
     */
    @Transactional
    Optional<Turn> begin(StepClaim claim) {
        Tx tx = lifecycle.lock(claim.sessionId());
        if (tx == null) return Optional.empty();
        Optional<Step> held = steps.lockHeld(claim.stepId(), claim.token());
        if (held.isEmpty()) return Optional.empty();
        Turn turn = turn(held.get());
        Artifact artifact = repository.artifact(claim.sessionId(), claim.artifactId()).orElse(null);
        if (held.get().cancelRequested() || turn == null || !turn.open() || artifact == null || !artifact.state().equals("REVISING")
                || !SessionLifecycle.runnable(tx.session)) {
            steps.finish(claim.stepId(), "CANCELLED", null, null);
            // a turn still open here is over with its step: it ends cancelled and releases its hold, as in fail()
            if (turn != null && turn.open() && artifact != null) cancelTurn(tx, held.get(), turn, artifact);
            return Optional.empty();
        }
        if (turn.status().equals("QUEUED")) repository.updateTurn(turn.turnId(), "RUNNING", null, null);
        // a turn that starts running is not visible in the session: the artifact is REVISING already
        tx.touch = false;
        lifecycle.flush(tx);
        return Optional.of(turn);
    }

    // -------------------------------------------------------------------- success

    /**
     * The one result transaction of a successful rewrite: the debit and the release of the turn's hold, the new revision (cause
     * EDIT), the slots of media that is gone (REMOVED) or still there (attached to the new revision), the artifact back to PROPOSED
     * on it, the turn APPLIED, the step SUCCEEDED and the events. If the hold cannot pay, the turn fails with
     * {@code ESTIMATE_EXCEEDED} and nothing is debited. Returns false, writing nothing, when the lease token no longer holds.
     */
    @Transactional
    boolean succeed(StepClaim claim, Result result) {
        Tx tx = lifecycle.lock(claim.sessionId());
        if (tx == null) return false;
        Optional<Step> held = steps.lockHeld(claim.stepId(), claim.token());
        if (held.isEmpty()) return false;
        Turn turn = turn(held.get());
        Artifact artifact = repository.artifact(claim.sessionId(), claim.artifactId()).orElse(null);
        if (held.get().cancelRequested() || turn == null || !turn.open() || artifact == null || !artifact.state().equals("REVISING")
                || !SessionLifecycle.runnable(tx.session)) {
            steps.finish(claim.stepId(), "CANCELLED", null, null);
            if (turn != null && turn.open() && artifact != null) cancelTurn(tx, held.get(), turn, artifact);
            return false;
        }
        // the rewrite was made of the revision the turn was admitted on; if the pointer moved meanwhile it is stale and is not stored
        if (!held.get().input().path("revisionId").stringValue("").equals(String.valueOf(artifact.currentRevisionId()))) {
            finishFailed(tx, held.get(), turn, artifact, "INVALID_OUTPUT");
            return true;
        }
        UUID reservation = reservation(held.get());
        try {
            ledger.settle(tx.session.ownerId(), new UsageLedger.Debit(reservation, "debit:" + claim.stepId() + ":" + claim.attempt(),
                    OPERATION, claim.input().path("credits").asInt(0), result.costMicros(), claim.stepId().toString()));
        } catch (EstimateExceededException | ReservationNotActiveException exceeded) {
            finishFailed(tx, held.get(), turn, artifact, "ESTIMATE_EXCEEDED");
            return true;
        }
        release(tx, reservation);

        UUID revisionId = UUID.randomUUID();
        int revisionNo = artifact.revisionCount() + 1;
        ObjectNode handles = Json.object();
        result.handles().forEach((handle, nodeId) -> handles.put(handle, nodeId.toString()));
        repository.insertRevision(new Revision(revisionId, artifact.artifactId(), revisionNo, "EDIT", result.payload(), handles,
                result.promptVersion(), result.modelRoute(), result.validation(), Instant.now()), tx.session.sessionId(),
                tx.session.ownerId());
        List<Rows.EventDraft> slotEvents = List.of();
        if (result.document() != null) {
            slotEvents = followSlots(artifact, revisionId, EditDocument.idSet(result.document()), false);
        } else {
            // an exercise has no nodes to tell its slots apart by: every slot (its audio) follows the new revision
            repository.attachAllSlots(artifact.artifactId(), revisionId);
        }
        repository.updateTurn(turn.turnId(), "APPLIED", revisionId, null);
        steps.finish(claim.stepId(), "SUCCEEDED", null, revisionId.toString());
        // a media turn that was waiting for this rewrite starts now (the artifact stays REVISING, on the new revision)
        List<Step> promoted = steps.promoteDependents(claim.stepId());
        for (Step next : promoted) {
            repository.insertTurn(new Turn(UUID.fromString(next.input().path("turnId").stringValue("")), artifact.artifactId(),
                    tx.session.sessionId(), tx.session.ownerId(), "QUEUED", next.input().path("action").stringValue("AUDIO_REGENERATE"),
                    null, null, List.of(), next.stepId(), null, null, true, null, next.input().path("voice").stringValue(null)));
        }
        Artifact moved = repository.transition(artifact, promoted.isEmpty() ? "PROPOSED" : "REVISING", null, revisionId, result.title(),
                revisionNo);
        tx.events.add(SessionLifecycle.artifactEvent(moved));
        tx.events.addAll(slotEvents);
        tx.events.add(lifecycle.usageEvent(tx.session, null));
        if (promoted.isEmpty()) lifecycle.settleRevision(tx);
        lifecycle.flush(tx);
        if (!promoted.isEmpty()) wakeAfterCommit();
        return true;
    }

    /**
     * The one result transaction of a media turn of an exercise (the Stub speech executor, #294): the turn's hold is released unspent
     * (nothing was synthesized, so nothing is debited), a new revision (cause MEDIA) carries the same exercise, every audio slot is READY
     * on the asset it already had with {@code voice} recorded in its spec, the turn is APPLIED and the artifact PROPOSED. Returns false,
     * writing nothing, when the lease token no longer holds.
     */
    @Transactional
    boolean succeedMedia(StepClaim claim, String voice, String modelRoute) {
        Tx tx = lifecycle.lock(claim.sessionId());
        if (tx == null) return false;
        Optional<Step> held = steps.lockHeld(claim.stepId(), claim.token());
        if (held.isEmpty()) return false;
        Turn turn = turn(held.get());
        Artifact artifact = repository.artifact(claim.sessionId(), claim.artifactId()).orElse(null);
        if (held.get().cancelRequested() || turn == null || !turn.open() || artifact == null || !artifact.state().equals("REVISING")
                || !SessionLifecycle.runnable(tx.session)) {
            steps.finish(claim.stepId(), "CANCELLED", null, null);
            if (turn != null && turn.open() && artifact != null) cancelTurn(tx, held.get(), turn, artifact);
            return false;
        }
        Revision current = repository.revision(artifact.artifactId(), artifact.currentRevisionId()).orElseThrow();
        release(tx, reservation(held.get()));
        UUID revisionId = UUID.randomUUID();
        int revisionNo = artifact.revisionCount() + 1;
        repository.insertRevision(new Revision(revisionId, artifact.artifactId(), revisionNo, "MEDIA", current.payload(), current.handles(),
                current.promptVersion(), modelRoute, current.validation(), Instant.now()), tx.session.sessionId(), tx.session.ownerId());
        repository.attachAllSlots(artifact.artifactId(), revisionId);
        List<Rows.EventDraft> slotEvents = new ArrayList<>();
        for (Slot slot : repository.slotsOf(artifact.artifactId())) {
            if (!slot.kind().equals("AUDIO") || slot.state().equals("REMOVED")) continue;
            ObjectNode spec = Json.object().put("mode", "existing").put("voice", voice);
            repository.readySlot(artifact.artifactId(), slot.slotKey(), spec, slot.assetId());
            slotEvents.add(SessionLifecycle.slotEvent(artifact.artifactId(), slot.slotKey(), slot.kind(), "READY", slot.assetId()));
        }
        repository.updateTurn(turn.turnId(), "APPLIED", revisionId, null);
        steps.finish(claim.stepId(), "SUCCEEDED", null, revisionId.toString());
        Artifact proposed = repository.transition(artifact, "PROPOSED", null, revisionId, null, revisionNo);
        tx.events.add(SessionLifecycle.artifactEvent(proposed));
        tx.events.addAll(slotEvents);
        tx.events.add(lifecycle.usageEvent(tx.session, null));
        lifecycle.settleRevision(tx);
        lifecycle.flush(tx);
        return true;
    }

    /**
     * After the current revision of {@code artifact} changed to {@code newRevision}, whose top-level blocks are {@code present}: the
     * slots whose node is gone become REMOVED (the hold on their assets ends and their media steps stop) and the ones whose node is
     * there follow the new revision. With {@code restore} (a revert) a slot that was REMOVED but whose node is back is FAILED
     * ({@code NO_RESULT}) instead: the asset is no longer held, so the node shows without a ready asset and approval needs the media
     * removed again. Returns the {@code MEDIA_SLOT_STATE} events of the slots that changed state.
     */
    List<Rows.EventDraft> followSlots(Artifact artifact, UUID newRevision, Set<UUID> present, boolean restore) {
        List<Slot> all = repository.slotsOf(artifact.artifactId());
        Set<UUID> gone = all.stream().map(Slot::nodeId).filter(node -> !present.contains(node)).collect(Collectors.toSet());
        Set<UUID> kept = all.stream().map(Slot::nodeId).filter(present::contains).collect(Collectors.toSet());
        List<Slot> removed = repository.removeSlots(artifact.artifactId(), gone);
        List<Slot> reopened = restore ? repository.reopenRemovedSlots(artifact.artifactId(), kept) : List.of();
        steps.cancelMediaOfSlots(artifact.artifactId(), removed.stream().map(Slot::slotKey).toList());
        repository.attachSlots(artifact.artifactId(), kept, newRevision);
        List<Rows.EventDraft> events = new ArrayList<>();
        for (Slot slot : removed) {
            events.add(SessionLifecycle.slotEvent(artifact.artifactId(), slot.slotKey(), slot.kind(), "REMOVED", slot.assetId()));
        }
        for (Slot slot : reopened) {
            Rows.EventDraft event = SessionLifecycle.slotEvent(artifact.artifactId(), slot.slotKey(), slot.kind(), "FAILED", slot.assetId());
            ((ObjectNode) event.payload()).put("errorCode", slot.errorCode());
            events.add(event);
        }
        return events;
    }

    // -------------------------------------------------------------------- failure

    /**
     * A run that did not produce a rewrite: retried later, failed for good, or cancelled. The turn fails (or is cancelled) and the
     * artifact is PROPOSED again on the revision it had. Writes nothing when the lease token no longer holds.
     */
    @Transactional
    boolean fail(StepClaim claim, Failure failure) {
        Tx tx = lifecycle.lock(claim.sessionId());
        if (tx == null) return false;
        Optional<Step> held = steps.lockHeld(claim.stepId(), claim.token());
        if (held.isEmpty()) return false;
        Turn turn = turn(held.get());
        Artifact artifact = repository.artifact(claim.sessionId(), claim.artifactId()).orElse(null);
        if (turn == null || !turn.open() || artifact == null || held.get().cancelRequested() || failure.kind() == Failure.Kind.CANCELLED) {
            steps.finish(claim.stepId(), "CANCELLED", null, null);
            if (turn != null && turn.open() && artifact != null) cancelTurn(tx, held.get(), turn, artifact);
            return true;
        }
        Step step = steps.step(claim.stepId()).orElseThrow();
        if (failure.kind() == Failure.Kind.RETRY && step.attempts() < settings.step().maxAttempts() && !lifecycle.expired(step)) {
            long delay = Math.min(Math.max(0, failure.delay().toSeconds()), settings.step().backoffCap().toSeconds());
            steps.requeue(step.stepId(), delay, failure.errorCode());
            LOG.info("generation_step_retry step_id={} session_id={} attempt={} error_code={}", step.stepId(), step.sessionId(),
                    step.attempts(), failure.errorCode());
            return true;
        }
        String code = failure.kind() == Failure.Kind.RETRY && lifecycle.expired(step) ? "DEADLINE_EXCEEDED" : failure.errorCode();
        finishFailed(tx, step, turn, artifact, code);
        return true;
    }

    /** A claim whose lease ran out: the step goes back to the queue after a backoff, or the turn fails when it has used all its attempts. */
    @Transactional
    void recover(UUID stepId) {
        Step probe = steps.step(stepId).orElse(null);
        if (probe == null) return;
        Tx tx = lifecycle.lock(probe.sessionId());
        if (tx == null) return;
        Step step = steps.lockExpired(stepId).orElse(null);
        if (step == null) return;
        Turn turn = turn(step);
        Artifact artifact = step.artifactId() == null ? null : repository.artifact(step.sessionId(), step.artifactId()).orElse(null);
        if (step.cancelRequested() || turn == null || !turn.open() || artifact == null) {
            steps.finish(stepId, "CANCELLED", null, null);
            return;
        }
        if (step.attempts() >= settings.step().maxAttempts() || lifecycle.expired(step)) {
            boolean late = lifecycle.expired(step) || step.deadlineAt() != null && step.deadlineAt().isBefore(Instant.now());
            finishFailed(tx, step, turn, artifact, late ? "DEADLINE_EXCEEDED" : "PROVIDER_UNAVAILABLE");
            return;
        }
        steps.requeue(stepId, lifecycle.backoff(step.attempts()).toSeconds(), "LEASE_EXPIRED");
        LOG.warn("generation_lease_recovered step_id={} session_id={} attempt={}", stepId, step.sessionId(), step.attempts());
    }

    /** A READY step whose lifetime ran out while it waited: the turn fails with the deadline code and the step is never claimed again. */
    @Transactional
    void expire(UUID stepId) {
        Step probe = steps.step(stepId).orElse(null);
        if (probe == null) return;
        Tx tx = lifecycle.lock(probe.sessionId());
        if (tx == null) return;
        Step step = steps.lockReady(stepId).orElse(null);
        if (step == null) return;
        Turn turn = turn(step);
        Artifact artifact = step.artifactId() == null ? null : repository.artifact(step.sessionId(), step.artifactId()).orElse(null);
        if (turn == null || !turn.open() || artifact == null) {
            steps.finish(stepId, "CANCELLED", null, null);
            return;
        }
        finishFailed(tx, step, turn, artifact, "DEADLINE_EXCEEDED");
    }

    // -------------------------------------------------------------------- helpers

    /** The end of a turn without a result: its step FAILED, its hold released unspent, the artifact PROPOSED on its old revision. */
    void finishFailed(Tx tx, Step step, Turn turn, Artifact artifact, String errorCode) {
        steps.finish(step.stepId(), "FAILED", errorCode, null);
        repository.updateTurn(turn.turnId(), "FAILED", null, errorCode);
        release(tx, reservation(step));
        releaseDependents(tx, step);
        backToProposed(tx, artifact);
        tx.events.add(lifecycle.usageEvent(tx.session, null));
        lifecycle.settleRevision(tx);
        lifecycle.flush(tx);
        LOG.info("generation_edit_failed step_id={} session_id={} turn_id={} error_code={}", step.stepId(), step.sessionId(),
                turn.turnId(), errorCode);
    }

    /** A turn that was still open when its step stopped (not by a session cancellation, which ends it itself). */
    void cancelTurn(Tx tx, Step step, Turn turn, Artifact artifact) {
        repository.updateTurn(turn.turnId(), "CANCELLED", null, null);
        release(tx, reservation(step));
        releaseDependents(tx, step);
        backToProposed(tx, artifact);
        tx.events.add(lifecycle.usageEvent(tx.session, null));
        lifecycle.settleRevision(tx);
        lifecycle.flush(tx);
    }

    /** A rewrite that did not produce a revision: the media turn that waited for it is not started and its hold is released. */
    private void releaseDependents(Tx tx, Step step) {
        for (Step dependent : steps.cancelDependents(step.stepId())) release(tx, reservation(dependent));
    }

    private void wakeAfterCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                dispatcher.ifAvailable(StepDispatcher::wake);
            }
        });
    }

    private void backToProposed(Tx tx, Artifact artifact) {
        if (!artifact.state().equals("REVISING")) return;
        tx.events.add(SessionLifecycle.artifactEvent(repository.transition(artifact, "PROPOSED", null, null, null, artifact.revisionCount())));
    }

    void release(Tx tx, UUID reservation) {
        if (reservation == null) return;
        try {
            ledger.release(tx.session.ownerId(), reservation);
        } catch (IllegalArgumentException unknown) {
            LOG.warn("generation_reservation_missing session_id={}", tx.session.sessionId());
        }
    }

    Turn turn(Step step) {
        String id = step.input().path("turnId").stringValue(null);
        return id == null ? null : repository.turn(UUID.fromString(id)).orElse(null);
    }

    static UUID reservation(Step step) {
        String id = step.input().path("reservationId").stringValue(null);
        return id == null ? null : UUID.fromString(id);
    }
}
