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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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

    private final GenerationRepository repository;
    private final StepRepository steps;
    private final SessionLifecycle lifecycle;
    private final UsageLedger ledger;
    private final GenerationSettings settings;

    EditLifecycle(GenerationRepository repository, StepRepository steps, SessionLifecycle lifecycle, UsageLedger ledger,
                  GenerationSettings settings) {
        this.repository = repository;
        this.steps = steps;
        this.lifecycle = lifecycle;
        this.ledger = ledger;
        this.settings = settings;
    }

    /**
     * What a valid rewrite produced: the new document (the old one with the target range replaced), its title, validation and
     * handles, the route and prompt version that wrote it and what the provider calls cost.
     */
    record Result(JsonNode document, String title, JsonNode validation, String promptVersion, String modelRoute, long costMicros,
                  Map<String, UUID> handles) { }

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
            return false;
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
        ObjectNode payload = Json.object().put("kind", "NATIVE_DOCUMENT");
        payload.set("document", result.document());
        ObjectNode handles = Json.object();
        result.handles().forEach((handle, nodeId) -> handles.put(handle, nodeId.toString()));
        repository.insertRevision(new Revision(revisionId, artifact.artifactId(), revisionNo, "EDIT", payload, handles,
                result.promptVersion(), result.modelRoute(), result.validation(), Instant.now()), tx.session.sessionId(),
                tx.session.ownerId());
        List<Rows.EventDraft> slotEvents = followSlots(artifact, revisionId, EditDocument.idSet(result.document()), false);
        Artifact proposed = repository.transition(artifact, "PROPOSED", null, revisionId, result.title(), revisionNo);
        repository.updateTurn(turn.turnId(), "APPLIED", revisionId, null);
        steps.finish(claim.stepId(), "SUCCEEDED", null, revisionId.toString());
        tx.events.add(SessionLifecycle.artifactEvent(proposed));
        tx.events.addAll(slotEvents);
        tx.events.add(lifecycle.usageEvent(tx.session, null));
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
    private void finishFailed(Tx tx, Step step, Turn turn, Artifact artifact, String errorCode) {
        steps.finish(step.stepId(), "FAILED", errorCode, null);
        repository.updateTurn(turn.turnId(), "FAILED", null, errorCode);
        release(tx, reservation(step));
        backToProposed(tx, artifact);
        tx.events.add(lifecycle.usageEvent(tx.session, null));
        lifecycle.flush(tx);
        LOG.info("generation_edit_failed step_id={} session_id={} turn_id={} error_code={}", step.stepId(), step.sessionId(),
                turn.turnId(), errorCode);
    }

    /** A turn that was still open when its step stopped (not by a session cancellation, which ends it itself). */
    private void cancelTurn(Tx tx, Step step, Turn turn, Artifact artifact) {
        repository.updateTurn(turn.turnId(), "CANCELLED", null, null);
        release(tx, reservation(step));
        backToProposed(tx, artifact);
        tx.events.add(lifecycle.usageEvent(tx.session, null));
        lifecycle.flush(tx);
    }

    private void backToProposed(Tx tx, Artifact artifact) {
        if (!artifact.state().equals("REVISING")) return;
        tx.events.add(SessionLifecycle.artifactEvent(repository.transition(artifact, "PROPOSED", null, null, null, artifact.revisionCount())));
    }

    private void release(Tx tx, UUID reservation) {
        if (reservation == null) return;
        try {
            ledger.release(tx.session.ownerId(), reservation);
        } catch (IllegalArgumentException unknown) {
            LOG.warn("generation_reservation_missing session_id={}", tx.session.sessionId());
        }
    }

    private Turn turn(Step step) {
        String id = step.input().path("turnId").stringValue(null);
        return id == null ? null : repository.turn(UUID.fromString(id)).orElse(null);
    }

    private static UUID reservation(Step step) {
        String id = step.input().path("reservationId").stringValue(null);
        return id == null ? null : UUID.fromString(id);
    }
}
