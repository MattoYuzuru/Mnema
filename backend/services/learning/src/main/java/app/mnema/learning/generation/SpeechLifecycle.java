package app.mnema.learning.generation;

import app.mnema.learning.generation.ClipRepository.Clip;
import app.mnema.learning.generation.Rows.Artifact;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The state changes of speech synthesis (#297), each in one short transaction under the session lock like {@link ImageSearchLifecycle} and
 * {@link EditLifecycle}; no method is entered with a provider call, a staging or a wait in flight. A clip is debited ({@code TTS_CLIP_30S}, idempotently per
 * step and attempt) only when this run synthesised it: a cache hit costs nothing.
 *
 * <p><b>The initial step of a slot</b> ({@code TEXT_DRAFT} child step): PENDING, GENERATING (by {@link ImageSearchLifecycle#beginSlot}), VERIFYING
 * ({@link #slotStaged}), READY on the slot's pre-allocated asset ({@link #slotReady}) or FAILED; no new revision.
 *
 * <p><b>The turn of a material</b> ({@link #succeedClip}): a new asset in a new revision (cause {@code MEDIA}) whose audio node uses it. <b>The turn of an
 * exercise</b> ({@link #succeedExercise}): every audio block of the exercise on a new asset in a new revision. Every clip a slot has had stays held
 * ({@code generation_media_clip}) so that a revert can restore it.
 */
@Service
class SpeechLifecycle {
    /** The rate-card operation of one clip. */
    static final String OPERATION = "TTS_CLIP_30S";

    /** One audio block of an exercise made anew: the slot, the new asset and what it was made with. */
    record ExerciseClip(String slotKey, UUID assetId, String voice, String lang, int take, boolean synthesized) { }

    private final GenerationRepository repository;
    private final StepRepository steps;
    private final CandidateRepository candidates;
    private final ClipRepository clips;
    private final SessionLifecycle lifecycle;
    private final ImageSearchLifecycle slots;
    private final EditLifecycle edits;
    private final UsageLedger ledger;
    private final AdmissionPricing pricing;

    SpeechLifecycle(GenerationRepository repository, StepRepository steps, CandidateRepository candidates, ClipRepository clips, SessionLifecycle lifecycle,
                    ImageSearchLifecycle slots, EditLifecycle edits, UsageLedger ledger, AdmissionPricing pricing) {
        this.repository = repository;
        this.steps = steps;
        this.candidates = candidates;
        this.clips = clips;
        this.lifecycle = lifecycle;
        this.slots = slots;
        this.edits = edits;
        this.ledger = ledger;
        this.pricing = pricing;
    }

    // ------------------------------------------------------------ initial step

    /** The clip is staged and handed to the media pipeline (or adopted from the cache): the slot is VERIFYING. False when the claim is void. */
    @Transactional
    boolean slotStaged(StepClaim claim) {
        Tx tx = lifecycle.lock(claim.sessionId());
        if (tx == null) return false;
        Optional<Step> held = steps.lockHeld(claim.stepId(), claim.token());
        if (held.isEmpty()) return false;
        Slot slot = slots.slot(claim);
        if (held.get().cancelRequested() || slot == null || !ImageSearchLifecycle.openSlot(slot)) {
            slots.cancelStep(tx, claim);
            return false;
        }
        if (slot.state().equals("VERIFYING")) {
            tx.touch = false;
        } else {
            candidates.slotState(slot.artifactId(), slot.slotKey(), "VERIFYING", null);
            tx.events.add(ImageSearchLifecycle.slotEvent(slot, "VERIFYING", null, slot.assetId()));
        }
        lifecycle.flush(tx);
        return true;
    }

    /**
     * The clip is READY: the debit when {@code synthesized}, the slot READY on its asset with the voice and take, the node hold, the clip row, the step
     * SUCCEEDED. If the hold cannot pay, the slot is FAILED ({@code ESTIMATE_EXCEEDED}) and nothing is debited. False when the lease token no longer holds.
     */
    @Transactional
    boolean slotReady(StepClaim claim, String voice, String lang, int take, boolean synthesized, long costRubMicros) {
        Tx tx = lifecycle.lock(claim.sessionId());
        if (tx == null) return false;
        Optional<Step> held = steps.lockHeld(claim.stepId(), claim.token());
        if (held.isEmpty()) return false;
        Slot slot = slots.slot(claim);
        if (held.get().cancelRequested() || slot == null || !ImageSearchLifecycle.openSlot(slot)) {
            slots.cancelStep(tx, claim);
            return false;
        }
        if (synthesized && !debit(tx, held.get(), claim, costRubMicros)) {
            slots.failSlot(tx, held.get(), slot, "ESTIMATE_EXCEEDED");
            return true;
        }
        repository.readySlot(slot.artifactId(), slot.slotKey(), specOf(slot.spec(), voice, lang, take), slot.assetId());
        candidates.holdNode(slot.artifactId(), slot.nodeId(), tx.session.sessionId(), tx.session.ownerId(), slot.assetId());
        clips.insert(new Clip(slot.assetId(), slot.artifactId(), slot.slotKey(), voice, take), tx.session.sessionId(), tx.session.ownerId());
        steps.finish(claim.stepId(), "SUCCEEDED", null, slot.assetId().toString());
        tx.events.add(ImageSearchLifecycle.slotEvent(slot, "READY", null, slot.assetId()));
        tx.events.add(lifecycle.usageEvent(tx.session, null));
        lifecycle.releaseIdleBatch(tx);
        lifecycle.flush(tx);
        return true;
    }

    private boolean debit(Tx tx, Step step, StepClaim claim, long costRubMicros) {
        try {
            ledger.settle(tx.session.ownerId(), new UsageLedger.Debit(SessionReservations.forStep(tx.session, step.input()),
                    "debit:" + claim.stepId() + ":" + claim.attempt(), OPERATION, pricing.credits(OPERATION), costRubMicros, claim.stepId().toString()));
            return true;
        } catch (EstimateExceededException | ReservationNotActiveException exceeded) {
            return false;
        }
    }

    /** A copy of a slot's spec that records the voice, language and take of its current clip. */
    static ObjectNode specOf(JsonNode spec, String voice, String lang, int take) {
        ObjectNode copy = spec.isObject() ? (ObjectNode) spec.deepCopy() : Json.object();
        copy.remove("mode");
        copy.put("voice", voice);
        if (lang != null) copy.put("lang", lang);
        copy.put("take", take);
        return copy;
    }

    // --------------------------------------------------------------- the turn

    /**
     * The one result transaction of an AUDIO_REGENERATE turn of a material: the debit when {@code synthesized} and the release of the turn's hold, the
     * new revision (cause MEDIA) whose audio node uses {@code assetId}, the slot READY on it with the new voice and take, the turn APPLIED, the artifact
     * PROPOSED and the step SUCCEEDED. If the hold cannot pay, the turn fails with {@code ESTIMATE_EXCEEDED}. False, writing nothing, when the lease token
     * no longer holds.
     */
    @Transactional
    boolean succeedClip(StepClaim claim, UUID assetId, String voice, int take, boolean synthesized, long costRubMicros) {
        Tx tx = lifecycle.lock(claim.sessionId());
        if (tx == null) return false;
        Optional<Step> held = steps.lockHeld(claim.stepId(), claim.token());
        if (held.isEmpty()) return false;
        Turn turn = edits.turn(held.get());
        Artifact artifact = repository.artifact(claim.sessionId(), claim.artifactId()).orElse(null);
        if (!runnable(held.get(), turn, artifact, tx)) {
            steps.finish(claim.stepId(), "CANCELLED", null, null);
            if (turn != null && turn.open() && artifact != null) edits.cancelTurn(tx, held.get(), turn, artifact);
            return false;
        }
        Slot slot = slots.slot(claim);
        Revision current = repository.revision(artifact.artifactId(), artifact.currentRevisionId()).orElseThrow();
        if (slot == null || !held.get().input().path("revisionId").stringValue("").equals(String.valueOf(artifact.currentRevisionId()))
                || !MediaNodes.has(current.payload().path("document"), slot.nodeId())) {
            edits.finishFailed(tx, held.get(), turn, artifact, "VERIFICATION_REJECTED");
            return true;
        }
        UUID reservation = EditLifecycle.reservation(held.get());
        if (synthesized) {
            try {
                ledger.settle(tx.session.ownerId(), new UsageLedger.Debit(reservation, "debit:" + claim.stepId() + ":" + claim.attempt(), OPERATION,
                        claim.input().path("credits").asInt(0), costRubMicros, claim.stepId().toString()));
            } catch (EstimateExceededException | ReservationNotActiveException exceeded) {
                edits.finishFailed(tx, held.get(), turn, artifact, "ESTIMATE_EXCEEDED");
                return true;
            }
        }
        edits.release(tx, reservation);

        UUID revisionId = UUID.randomUUID();
        int revisionNo = artifact.revisionCount() + 1;
        ObjectNode payload = Json.object().put("kind", "NATIVE_DOCUMENT");
        payload.set("document", MediaNodes.withAsset(current.payload().path("document"), slot.nodeId(), assetId));
        repository.insertRevision(new Revision(revisionId, artifact.artifactId(), revisionNo, "MEDIA", payload, current.handles(), current.promptVersion(),
                "tts:" + claim.input().path("route").stringValue("speech"), current.validation(), Instant.now()), tx.session.sessionId(), tx.session.ownerId());
        repository.attachAllSlots(artifact.artifactId(), revisionId);
        String lang = slot.spec().path("lang").stringValue(null);
        repository.readySlot(slot.artifactId(), slot.slotKey(), specOf(slot.spec(), voice, lang, take), assetId);
        candidates.holdNode(slot.artifactId(), slot.nodeId(), tx.session.sessionId(), tx.session.ownerId(), assetId);
        clips.insert(new Clip(assetId, slot.artifactId(), slot.slotKey(), voice, take), tx.session.sessionId(), tx.session.ownerId());
        repository.updateTurn(turn.turnId(), "APPLIED", revisionId, null);
        steps.finish(claim.stepId(), "SUCCEEDED", null, revisionId.toString());
        Artifact proposed = repository.transition(artifact, "PROPOSED", null, revisionId, null, revisionNo);
        tx.events.add(SessionLifecycle.artifactEvent(proposed));
        tx.events.add(ImageSearchLifecycle.slotEvent(slot, "READY", null, assetId));
        tx.events.add(lifecycle.usageEvent(tx.session, null));
        lifecycle.settleRevision(tx);
        lifecycle.flush(tx);
        return true;
    }

    /**
     * The one result transaction of the voice redo of an exercise: the debit of {@code synthesizedCount} clips and the release of the turn's hold, a new
     * exercise revision (cause MEDIA) whose audio blocks name the new assets, every slot READY on its new asset with the voice and take, the turn APPLIED,
     * the artifact PROPOSED. If the hold cannot pay for the clips, the turn fails with {@code ESTIMATE_EXCEEDED} and nothing is debited. False, writing
     * nothing, when the lease token no longer holds.
     */
    @Transactional
    boolean succeedExercise(StepClaim claim, JsonNode command, List<ExerciseClip> made, long costRubMicros) {
        Tx tx = lifecycle.lock(claim.sessionId());
        if (tx == null) return false;
        Optional<Step> held = steps.lockHeld(claim.stepId(), claim.token());
        if (held.isEmpty()) return false;
        Turn turn = edits.turn(held.get());
        Artifact artifact = repository.artifact(claim.sessionId(), claim.artifactId()).orElse(null);
        if (!runnable(held.get(), turn, artifact, tx)) {
            steps.finish(claim.stepId(), "CANCELLED", null, null);
            if (turn != null && turn.open() && artifact != null) edits.cancelTurn(tx, held.get(), turn, artifact);
            return false;
        }
        Revision current = repository.revision(artifact.artifactId(), artifact.currentRevisionId()).orElseThrow();
        UUID reservation = EditLifecycle.reservation(held.get());
        int synthesized = (int) made.stream().filter(ExerciseClip::synthesized).count();
        if (synthesized > 0) {
            try {
                ledger.settle(tx.session.ownerId(), new UsageLedger.Debit(reservation, "debit:" + claim.stepId() + ":" + claim.attempt(), OPERATION,
                        synthesized * pricing.credits(OPERATION), costRubMicros, claim.stepId().toString()));
            } catch (EstimateExceededException | ReservationNotActiveException exceeded) {
                edits.finishFailed(tx, held.get(), turn, artifact, "ESTIMATE_EXCEEDED");
                return true;
            }
        }
        edits.release(tx, reservation);

        UUID revisionId = UUID.randomUUID();
        int revisionNo = artifact.revisionCount() + 1;
        ObjectNode payload = Json.object().put("kind", "EXERCISE_COMMAND");
        payload.set("command", command);
        repository.insertRevision(new Revision(revisionId, artifact.artifactId(), revisionNo, "MEDIA", payload, current.handles(), current.promptVersion(),
                "tts:speech", current.validation(), Instant.now()), tx.session.sessionId(), tx.session.ownerId());
        repository.attachAllSlots(artifact.artifactId(), revisionId);
        List<EventDraft> slotEvents = new ArrayList<>();
        for (Slot slot : repository.slotsOf(artifact.artifactId())) {
            ExerciseClip clip = made.stream().filter(each -> each.slotKey().equals(slot.slotKey())).findFirst().orElse(null);
            if (clip == null) continue;
            repository.readySlot(slot.artifactId(), slot.slotKey(), specOf(slot.spec(), clip.voice(), clip.lang(), clip.take()), clip.assetId());
            candidates.holdNode(slot.artifactId(), slot.nodeId(), tx.session.sessionId(), tx.session.ownerId(), clip.assetId());
            clips.insert(new Clip(clip.assetId(), slot.artifactId(), slot.slotKey(), clip.voice(), clip.take()), tx.session.sessionId(), tx.session.ownerId());
            slotEvents.add(SessionLifecycle.slotEvent(slot.artifactId(), slot.slotKey(), slot.kind(), "READY", clip.assetId()));
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
     * The turn made no clip: it fails with {@code errorCode} and its hold is released unspent. A turn that replaced a first clip still waiting for its
     * result leaves that slot without audio: it fails like the first step would.
     */
    @Transactional
    boolean failTurn(StepClaim claim, String errorCode) {
        boolean stored = edits.fail(claim, SessionLifecycle.Failure.fail(errorCode));
        if (!stored) return false;
        Slot slot = slots.slot(claim);
        if (slot != null && ImageSearchLifecycle.openSlot(slot) && !steps.hasOpenSlotSteps(claim.sessionId())) {
            Tx tx = lifecycle.lock(claim.sessionId());
            if (tx != null) {
                candidates.slotState(slot.artifactId(), slot.slotKey(), "FAILED", errorCode);
                tx.events.add(ImageSearchLifecycle.slotEvent(slot, "FAILED", errorCode, slot.assetId()));
                tx.touch = false;
                lifecycle.releaseIdleBatch(tx);
                lifecycle.flush(tx);
            }
        }
        return true;
    }

    private static boolean runnable(Step step, Turn turn, Artifact artifact, Tx tx) {
        return !(step.cancelRequested() || turn == null || !turn.open() || artifact == null || !artifact.state().equals("REVISING")
                || !SessionLifecycle.runnable(tx.session));
    }
}
