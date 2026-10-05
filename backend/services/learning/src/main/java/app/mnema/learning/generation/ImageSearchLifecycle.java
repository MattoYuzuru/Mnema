package app.mnema.learning.generation;

import app.mnema.learning.generation.Rows.Artifact;
import app.mnema.learning.generation.Rows.Candidate;
import app.mnema.learning.generation.Rows.EventDraft;
import app.mnema.learning.generation.Rows.Revision;
import app.mnema.learning.generation.Rows.Slot;
import app.mnema.learning.generation.Rows.Step;
import app.mnema.learning.generation.Rows.Turn;
import app.mnema.learning.generation.SessionLifecycle.Tx;
import app.mnema.learning.usage.AdmissionPricing;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The state changes of licensed image search (#296), and the slot-level transitions every media step of a slot shares (the speech steps of #297 use
 * {@link #beginSlot}, {@link #slotFailed}, {@link #recover} and {@link #expire} as they are), each in one short transaction under the session lock like {@link SessionLifecycle} and
 * {@link EditLifecycle}; no method is entered with a search, a download or a wait in flight.
 *
 * <p><b>The initial step of a slot</b> (a {@code TEXT_DRAFT} child step, no turn): the slot goes PENDING, GENERATING, VERIFYING, then READY on
 * its pre-allocated asset with that one candidate chosen (the IMAGE_SEARCH credits are debited from the session's batch hold, idempotently per
 * step and attempt), or FAILED with a code and nothing debited. The batch hold outlives the session's move to REVIEW while such a step is
 * open ({@link SessionLifecycle#releaseHolds}) and ends with the last of them ({@link #releaseBatchWhenIdle}).
 *
 * <p><b>The step of an IMAGE_SEARCH turn</b> (an edit): up to four new candidates are stored, then one transaction either applies the first
 * READY one in a new revision (cause {@code MEDIA}, the turn's own hold debited) or fails the turn with the hold released and the revision and slot
 * unchanged.
 */
@Service
class ImageSearchLifecycle {
    private static final Logger LOG = LoggerFactory.getLogger(ImageSearchLifecycle.class);
    /** The rate-card operation of one search, debited under it. */
    static final String OPERATION = "IMAGE_SEARCH";

    private final GenerationRepository repository;
    private final StepRepository steps;
    private final CandidateRepository candidates;
    private final SessionLifecycle lifecycle;
    private final EditLifecycle edits;
    private final UsageLedger ledger;
    private final AdmissionPricing pricing;
    private final GenerationSettings settings;

    ImageSearchLifecycle(GenerationRepository repository, StepRepository steps, CandidateRepository candidates, SessionLifecycle lifecycle,
                         EditLifecycle edits, UsageLedger ledger, AdmissionPricing pricing, GenerationSettings settings) {
        this.repository = repository;
        this.steps = steps;
        this.candidates = candidates;
        this.lifecycle = lifecycle;
        this.edits = edits;
        this.ledger = ledger;
        this.pricing = pricing;
        this.settings = settings;
    }

    // ------------------------------------------------------------ initial step

    /**
     * The worker starts its claimed slot step: the slot becomes GENERATING (once).
     *
     * @return the slot, or empty when the claim is void (lease lost, step cancelled, session over, the slot is gone or no longer open)
     */
    @Transactional
    Optional<Slot> beginSlot(StepClaim claim) {
        Tx tx = lifecycle.lock(claim.sessionId());
        if (tx == null) return Optional.empty();
        Optional<Step> held = steps.lockHeld(claim.stepId(), claim.token());
        if (held.isEmpty()) return Optional.empty();
        Slot slot = slot(claim);
        if (held.get().cancelRequested() || slot == null || !SessionLifecycle.runnable(tx.session) || !openSlot(slot)) {
            cancelStep(tx, claim);
            return Optional.empty();
        }
        if (slot.state().equals("PENDING")) {
            candidates.slotState(slot.artifactId(), slot.slotKey(), "GENERATING", null);
            tx.events.add(slotEvent(slot, "GENERATING", null));
        } else {
            tx.touch = false;
        }
        lifecycle.flush(tx);
        return Optional.of(slot);
    }

    /**
     * The worker chose the image to stage and has reserved its asset: the candidate row (its source key and attribution) is stored <em>before</em> the
     * transfer, so a retry after a lost lease resumes this very candidate. The slot stays GENERATING. False when the claim is void.
     */
    @Transactional
    boolean slotChosen(StepClaim claim, Candidate row) {
        Tx tx = lifecycle.lock(claim.sessionId());
        if (tx == null) return false;
        Optional<Step> held = steps.lockHeld(claim.stepId(), claim.token());
        if (held.isEmpty()) return false;
        Slot slot = slot(claim);
        if (held.get().cancelRequested() || slot == null || !openSlot(slot)) {
            cancelStep(tx, claim);
            return false;
        }
        if (candidates.ofSlot(slot.artifactId(), slot.slotKey()).stream().noneMatch(each -> each.assetId().equals(row.assetId()))) {
            candidates.insert(row, tx.session.sessionId(), tx.session.ownerId());
        }
        tx.touch = false;
        lifecycle.flush(tx);
        return true;
    }

    /** The one candidate of the slot is stored and handed to the media pipeline: the slot is VERIFYING. False when the claim is void. */
    @Transactional
    boolean slotStaged(StepClaim claim, Candidate row) {
        Tx tx = lifecycle.lock(claim.sessionId());
        if (tx == null) return false;
        Optional<Step> held = steps.lockHeld(claim.stepId(), claim.token());
        if (held.isEmpty()) return false;
        Slot slot = slot(claim);
        if (held.get().cancelRequested() || slot == null || !openSlot(slot)) {
            cancelStep(tx, claim);
            return false;
        }
        if (candidates.ofSlot(slot.artifactId(), slot.slotKey()).stream().noneMatch(each -> each.assetId().equals(row.assetId()))) {
            candidates.insert(row, tx.session.sessionId(), tx.session.ownerId());
        }
        candidates.slotState(slot.artifactId(), slot.slotKey(), "VERIFYING", null);
        tx.events.add(slotEvent(slot, "VERIFYING", null));
        lifecycle.flush(tx);
        return true;
    }

    /**
     * The candidate is READY: the debit, the slot READY on its asset, the node hold, the step SUCCEEDED. If the hold cannot pay, the slot is
     * FAILED ({@code ESTIMATE_EXCEEDED}) and nothing is debited. False, writing nothing, when the lease token no longer holds.
     */
    @Transactional
    boolean slotReady(StepClaim claim, UUID candidateId) {
        Tx tx = lifecycle.lock(claim.sessionId());
        if (tx == null) return false;
        Optional<Step> held = steps.lockHeld(claim.stepId(), claim.token());
        if (held.isEmpty()) return false;
        Slot slot = slot(claim);
        Candidate candidate = slot == null ? null : candidates.find(slot.artifactId(), candidateId).orElse(null);
        if (held.get().cancelRequested() || slot == null || !openSlot(slot) || candidate == null) {
            cancelStep(tx, claim);
            return false;
        }
        try {
            ledger.settle(tx.session.ownerId(), new UsageLedger.Debit(SessionReservations.forStep(tx.session, held.get().input()),
                    "debit:" + claim.stepId() + ":" + claim.attempt(), OPERATION, pricing.credits(OPERATION), 0L, claim.stepId().toString()));
        } catch (EstimateExceededException | ReservationNotActiveException exceeded) {
            failSlot(tx, held.get(), slot, "ESTIMATE_EXCEEDED");
            return true;
        }
        candidates.setState(candidate.candidateId(), "READY");
        candidates.slotReady(slot.artifactId(), slot.slotKey(), candidate.assetId());
        candidates.holdNode(slot.artifactId(), slot.nodeId(), tx.session.sessionId(), tx.session.ownerId(), candidate.assetId());
        steps.finish(claim.stepId(), "SUCCEEDED", null, candidate.candidateId().toString());
        tx.events.add(slotEvent(slot, "READY", null, candidate.assetId()));
        tx.events.add(lifecycle.usageEvent(tx.session, null));
        lifecycle.releaseIdleBatch(tx);
        lifecycle.flush(tx);
        return true;
    }

    /**
     * The step ends without an image: the slot is FAILED with {@code errorCode} (the candidate, if one was staged, as rejected when the
     * verification said so), nothing is debited. A cancelled step only ends. False when the lease token no longer holds.
     */
    @Transactional
    boolean slotFailed(StepClaim claim, String errorCode, boolean cancelled) {
        Tx tx = lifecycle.lock(claim.sessionId());
        if (tx == null) return false;
        Optional<Step> held = steps.lockHeld(claim.stepId(), claim.token());
        if (held.isEmpty()) return false;
        Slot slot = slot(claim);
        if (cancelled || held.get().cancelRequested() || slot == null || !openSlot(slot)) {
            cancelStep(tx, claim);
            return true;
        }
        failSlot(tx, held.get(), slot, errorCode);
        return true;
    }

    /**
     * The step ends cancelled (the session, the slot or the claim is gone), and the batch hold ends with the last step. The slot is left alone
     * while another step of it (a turn that replaced this search) will still decide its fate; when none will, a slot that is still open ends
     * FAILED/CANCELLED here, so that it is never stuck GENERATING or VERIFYING.
     */
    void cancelStep(Tx tx, StepClaim claim) {
        steps.finish(claim.stepId(), "CANCELLED", null, null);
        Slot slot = slot(claim);
        if (slot != null && slot.assetId().toString().equals(claim.input().path("assetId").stringValue(""))) closeOrphanedSlot(tx, slot, "CANCELLED");
        lifecycle.releaseIdleBatch(tx);
        tx.touch = false;
        lifecycle.flush(tx);
    }

    /** The slot is still open and no step of it will decide its fate any more: it is FAILED with {@code errorCode}. */
    private void closeOrphanedSlot(Tx tx, Slot slot, String errorCode) {
        if (!openSlot(slot) || steps.hasLiveSlotStep(slot.artifactId(), slot.slotKey())) return;
        closeSlot(tx, slot, errorCode);
    }

    private void closeSlot(Tx tx, Slot slot, String errorCode) {
        candidates.ofSlot(slot.artifactId(), slot.slotKey()).stream().filter(each -> each.assetId().equals(slot.assetId()) && !each.state().equals("READY"))
                .forEach(each -> candidates.setState(each.candidateId(), "FAILED"));
        candidates.slotState(slot.artifactId(), slot.slotKey(), "FAILED", errorCode);
        tx.events.add(slotEvent(slot, "FAILED", errorCode));
    }

    void failSlot(Tx tx, Step step, Slot slot, String errorCode) {
        closeSlot(tx, slot, errorCode);
        steps.finish(step.stepId(), "FAILED", errorCode, null);
        lifecycle.releaseIdleBatch(tx);
        lifecycle.flush(tx);
        LOG.info("generation_media_failed step_id={} session_id={} slot_key={} error_code={}", step.stepId(), step.sessionId(), slot.slotKey(), errorCode);
    }

    /** A claim whose lease ran out: the step is claimed again after a backoff, or the slot fails after the last attempt. */
    @Transactional
    void recover(UUID stepId) {
        Step probe = steps.step(stepId).orElse(null);
        if (probe == null) return;
        Tx tx = lifecycle.lock(probe.sessionId());
        if (tx == null) return;
        Step step = steps.lockExpired(stepId).orElse(null);
        if (step == null) return;
        Slot slot = slot(step);
        if (step.cancelRequested() || slot == null || !openSlot(slot)) {
            steps.finish(stepId, "CANCELLED", null, null);
            if (slot != null && slot.assetId().toString().equals(step.input().path("assetId").stringValue(""))) closeOrphanedSlot(tx, slot, "CANCELLED");
            lifecycle.releaseIdleBatch(tx);
            tx.touch = false;
            lifecycle.flush(tx);
            return;
        }
        if (step.attempts() >= settings.step().maxAttempts() || lifecycle.expired(step)) {
            boolean late = lifecycle.expired(step) || step.deadlineAt() != null && step.deadlineAt().isBefore(Instant.now());
            failSlot(tx, step, slot, late ? "DEADLINE_EXCEEDED" : "PROVIDER_UNAVAILABLE");
            return;
        }
        steps.requeue(stepId, lifecycle.backoff(step.attempts()).toSeconds(), "LEASE_EXPIRED");
        LOG.warn("generation_lease_recovered step_id={} session_id={} attempt={}", stepId, step.sessionId(), step.attempts());
    }

    /** A READY slot step whose lifetime ran out while it waited: the slot fails with the deadline code and the step is never claimed again. */
    @Transactional
    void expire(UUID stepId) {
        Step probe = steps.step(stepId).orElse(null);
        if (probe == null) return;
        Tx tx = lifecycle.lock(probe.sessionId());
        if (tx == null) return;
        Step step = steps.lockReady(stepId).orElse(null);
        if (step == null) return;
        Slot slot = slot(step);
        if (slot == null || !openSlot(slot)) {
            steps.finish(stepId, "CANCELLED", null, null);
            lifecycle.releaseIdleBatch(tx);
            tx.touch = false;
            lifecycle.flush(tx);
            return;
        }
        failSlot(tx, step, slot, "DEADLINE_EXCEEDED");
    }

    Slot slot(StepClaim claim) {
        return slot(claim.artifactId(), claim.input().path("slotKey").stringValue(""));
    }

    private Slot slot(Step step) {
        return slot(step.artifactId(), step.input().path("slotKey").stringValue(""));
    }

    private Slot slot(UUID artifactId, String slotKey) {
        if (artifactId == null) return null;
        return repository.slotsOf(artifactId).stream().filter(each -> each.slotKey().equals(slotKey)).findFirst().orElse(null);
    }

    static boolean openSlot(Slot slot) {
        return slot.state().equals("PENDING") || slot.state().equals("GENERATING") || slot.state().equals("VERIFYING");
    }

    private static EventDraft slotEvent(Slot slot, String state, String errorCode) {
        return slotEvent(slot, state, errorCode, slot.assetId());
    }

    static EventDraft slotEvent(Slot slot, String state, String errorCode, UUID assetId) {
        EventDraft event = SessionLifecycle.slotEvent(slot.artifactId(), slot.slotKey(), slot.kind(), state, assetId);
        if (errorCode != null) ((ObjectNode) event.payload()).put("errorCode", errorCode);
        return event;
    }

    // --------------------------------------------------------------- the turn

    /**
     * The new candidates of a turn are stored (VERIFYING) before the wait for their verification, so that a crash leaves them known: the next
     * attempt does not bring them again. False when the claim is void (the turn ended meanwhile).
     */
    @Transactional
    boolean addTurnCandidates(StepClaim claim, List<Candidate> rows) {
        Tx tx = lifecycle.lock(claim.sessionId());
        if (tx == null) return false;
        Optional<Step> held = steps.lockHeld(claim.stepId(), claim.token());
        if (held.isEmpty()) return false;
        Turn turn = edits.turn(held.get());
        if (held.get().cancelRequested() || turn == null || !turn.open() || !SessionLifecycle.runnable(tx.session)) return false;
        for (Candidate row : rows) candidates.insert(row, tx.session.sessionId(), tx.session.ownerId());
        tx.touch = false;
        lifecycle.flush(tx);
        return true;
    }

    /**
     * The one result transaction of an IMAGE_SEARCH turn that has a usable image: {@code chosen} (the first READY new candidate) becomes the asset
     * of the slot's image node in a new revision (cause MEDIA), the slot is READY on it, the turn's hold is debited and released, the turn APPLIED,
     * the artifact PROPOSED on the revision and the step SUCCEEDED. If the hold cannot pay, the turn fails with {@code ESTIMATE_EXCEEDED} and nothing
     * is debited. Returns false, writing nothing, when the lease token no longer holds.
     */
    @Transactional
    boolean succeedImages(StepClaim claim, Candidate chosen, Map<UUID, String> states) {
        Tx tx = lifecycle.lock(claim.sessionId());
        if (tx == null) return false;
        Optional<Step> held = steps.lockHeld(claim.stepId(), claim.token());
        if (held.isEmpty()) return false;
        Turn turn = edits.turn(held.get());
        Artifact artifact = repository.artifact(claim.sessionId(), claim.artifactId()).orElse(null);
        if (held.get().cancelRequested() || turn == null || !turn.open() || artifact == null || !artifact.state().equals("REVISING")
                || !SessionLifecycle.runnable(tx.session)) {
            steps.finish(claim.stepId(), "CANCELLED", null, null);
            if (turn != null && turn.open() && artifact != null) edits.cancelTurn(tx, held.get(), turn, artifact);
            Slot gone = slot(claim);
            if (gone != null) closeOrphanedSlot(tx, gone, "CANCELLED");
            return false;
        }
        states.forEach(candidates::setState);
        Slot slot = slot(claim);
        Revision current = repository.revision(artifact.artifactId(), artifact.currentRevisionId()).orElseThrow();
        if (slot == null || !held.get().input().path("revisionId").stringValue("").equals(String.valueOf(artifact.currentRevisionId()))
                || !MediaNodes.has(current.payload().path("document"), slot.nodeId())) {
            edits.finishFailed(tx, held.get(), turn, artifact, "NO_RESULT");
            return true;
        }
        UUID reservation = EditLifecycle.reservation(held.get());
        try {
            ledger.settle(tx.session.ownerId(), new UsageLedger.Debit(reservation, "debit:" + claim.stepId() + ":" + claim.attempt(), OPERATION,
                    claim.input().path("credits").asInt(0), 0L, claim.stepId().toString()));
        } catch (EstimateExceededException | ReservationNotActiveException exceeded) {
            edits.finishFailed(tx, held.get(), turn, artifact, "ESTIMATE_EXCEEDED");
            return true;
        }
        edits.release(tx, reservation);

        UUID revisionId = UUID.randomUUID();
        int revisionNo = artifact.revisionCount() + 1;
        ObjectNode payload = Json.object().put("kind", "NATIVE_DOCUMENT");
        payload.set("document", MediaNodes.withAsset(current.payload().path("document"), slot.nodeId(), chosen.assetId()));
        repository.insertRevision(new Revision(revisionId, artifact.artifactId(), revisionNo, "MEDIA", payload, current.handles(),
                current.promptVersion(), "image-search:" + chosen.source().toLowerCase(java.util.Locale.ROOT), current.validation(), Instant.now()),
                tx.session.sessionId(), tx.session.ownerId());
        repository.attachAllSlots(artifact.artifactId(), revisionId);
        candidates.slotReady(slot.artifactId(), slot.slotKey(), chosen.assetId());
        candidates.holdNode(slot.artifactId(), slot.nodeId(), tx.session.sessionId(), tx.session.ownerId(), chosen.assetId());
        repository.updateTurn(turn.turnId(), "APPLIED", revisionId, null);
        steps.finish(claim.stepId(), "SUCCEEDED", null, revisionId.toString());
        Artifact proposed = repository.transition(artifact, "PROPOSED", null, revisionId, null, revisionNo);
        tx.events.add(SessionLifecycle.artifactEvent(proposed));
        tx.events.add(slotEvent(slot, "READY", null, chosen.assetId()));
        tx.events.add(lifecycle.usageEvent(tx.session, null));
        lifecycle.settleRevision(tx);
        lifecycle.flush(tx);
        return true;
    }

    /** The turn found nothing usable: its candidates (if any were stored) are as the verification left them, then the turn fails with {@code errorCode}. */
    @Transactional
    boolean failTurn(StepClaim claim, Map<UUID, String> states, String errorCode) {
        states.forEach(candidates::setState);
        boolean stored = edits.fail(claim, SessionLifecycle.Failure.fail(errorCode));
        if (!stored) return false;
        endOpenSlot(claim, errorCode);
        return true;
    }

    /** The turn was cancelled: as it fails, a slot of a first search that it replaced is not left open. False when the claim is void. */
    @Transactional
    boolean cancelTurn(StepClaim claim) {
        boolean stored = edits.fail(claim, SessionLifecycle.Failure.cancelled());
        if (stored) endOpenSlot(claim, "CANCELLED");
        return stored;
    }

    /**
     * A turn that replaced a first search leaves that slot without an image when it ends without one: the slot fails like the first search would,
     * unless that search (not asked to stop) is still running and will decide it. A step that was asked to stop does not count: it ends without
     * touching the slot (see {@link #cancelStep}), so waiting for it here would leave the slot GENERATING or VERIFYING for good.
     */
    private void endOpenSlot(StepClaim claim, String errorCode) {
        Slot slot = slot(claim);
        if (slot == null || !openSlot(slot) || steps.hasLiveSlotStep(slot.artifactId(), slot.slotKey())) return;
        Tx tx = lifecycle.lock(claim.sessionId());
        if (tx == null) return;
        closeSlot(tx, slot, errorCode);
        tx.touch = false;
        lifecycle.releaseIdleBatch(tx);
        lifecycle.flush(tx);
    }
}
