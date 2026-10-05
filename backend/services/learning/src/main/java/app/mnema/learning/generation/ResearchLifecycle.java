package app.mnema.learning.generation;

import app.mnema.learning.generation.Rows.Artifact;
import app.mnema.learning.generation.Rows.Step;
import app.mnema.learning.generation.SessionLifecycle.Tx;
import app.mnema.learning.usage.AdmissionPricing;
import app.mnema.learning.usage.EstimateExceededException;
import app.mnema.learning.usage.ReservationNotActiveException;
import app.mnema.learning.usage.UsageLedger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The state changes of the {@code RESEARCH} step (#299), each in one short transaction under the session lock like {@link SessionLifecycle}; nothing here is
 * entered with a model call or a search in flight. A research never fails its material: it ends with what it found (possibly nothing) and the
 * {@code TEXT_DRAFT} that waited for it becomes READY; the only step that does not succeed is one whose session was cancelled.
 *
 * <p>The artifact does not change state while it researches (it stays QUEUED): the step itself is what the Workshop reads in the active steps.
 */
@Service
class ResearchLifecycle {
    private static final Logger LOG = LoggerFactory.getLogger(ResearchLifecycle.class);

    /**
     * What a research found.
     *
     * @param requests the paid search requests answered; the debit is {@code WEB_SEARCH_QUERY} x this
     * @param results the numbered results in {@code [n]} order, possibly none
     * @param costMicros the provider cost of the whole run (the query planner and the searches) in millionths of a rouble, for the economics of the ledger
     */
    record Outcome(int requests, List<ResearchRepository.Source> results, long costMicros) { }

    private final GenerationRepository repository;
    private final StepRepository steps;
    private final ResearchRepository research;
    private final SessionLifecycle lifecycle;
    private final UsageLedger ledger;
    private final AdmissionPricing pricing;
    private final GenerationSettings settings;

    ResearchLifecycle(GenerationRepository repository, StepRepository steps, ResearchRepository research, SessionLifecycle lifecycle, UsageLedger ledger,
                      AdmissionPricing pricing, GenerationSettings settings) {
        this.repository = repository;
        this.steps = steps;
        this.research = research;
        this.lifecycle = lifecycle;
        this.ledger = ledger;
        this.pricing = pricing;
        this.settings = settings;
    }

    /**
     * The worker starts its claimed step. Nothing is written when it may run.
     *
     * @return false when the claim is void (lease lost, session cancelled or over, the artifact left QUEUED); a step that must not run ends CANCELLED
     */
    @Transactional
    boolean begin(StepClaim claim) {
        Tx tx = lifecycle.lock(claim.sessionId());
        if (tx == null) return false;
        Optional<Step> held = steps.lockHeld(claim.stepId(), claim.token());
        if (held.isEmpty()) return false;
        Artifact artifact = repository.artifact(claim.sessionId(), claim.artifactId()).orElse(null);
        if (held.get().cancelRequested() || artifact == null || !SessionLifecycle.runnable(tx.session) || !SessionLifecycle.open(artifact)) {
            steps.finish(claim.stepId(), "CANCELLED", null, null);
            return false;
        }
        return true;
    }

    /**
     * The one result transaction: the debit of the requests made ({@code WEB_SEARCH_QUERY} each, idempotent per step and attempt; a hold that cannot pay
     * is logged and the results are kept), the results stored for the artifact, the step SUCCEEDED and the draft that waited for it READY. Returns false,
     * writing nothing, when the lease token no longer holds; a cancelled session ends the step CANCELLED and stores nothing.
     */
    @Transactional
    boolean succeed(StepClaim claim, Outcome outcome) {
        Tx tx = lifecycle.lock(claim.sessionId());
        if (tx == null) return false;
        Optional<Step> held = steps.lockHeld(claim.stepId(), claim.token());
        if (held.isEmpty()) return false;
        Artifact artifact = repository.artifact(claim.sessionId(), claim.artifactId()).orElse(null);
        if (held.get().cancelRequested() || artifact == null || !SessionLifecycle.runnable(tx.session) || !SessionLifecycle.open(artifact)) {
            steps.finish(claim.stepId(), "CANCELLED", null, null);
            return false;
        }
        boolean debited = false;
        if (outcome.requests() > 0) {
            try {
                ledger.settle(tx.session.ownerId(), new UsageLedger.Debit(SessionReservations.forStep(tx.session, held.get().input()),
                        "debit:" + claim.stepId() + ":" + claim.attempt(), ResearchSteps.OPERATION,
                        outcome.requests() * pricing.credits(ResearchSteps.OPERATION), outcome.costMicros(), claim.stepId().toString()));
                debited = true;
            } catch (EstimateExceededException | ReservationNotActiveException unpayable) {
                // the hold cannot pay for what was already bought: the sources are real and kept, the owner is not charged beyond the hold
                LOG.warn("generation_research_unpaid step_id={} session_id={} requests={}", claim.stepId(), claim.sessionId(), outcome.requests());
            }
        }
        research.upsert(artifact.artifactId(), tx.session.sessionId(), tx.session.ownerId(), outcome.requests(), outcome.results());
        steps.finish(claim.stepId(), "SUCCEEDED", null, null);
        steps.promoteDependents(claim.stepId());
        if (debited) tx.events.add(lifecycle.usageEvent(tx.session, null));
        else tx.touch = false;
        lifecycle.flush(tx);
        return true;
    }

    /**
     * A claim whose lease ran out (the worker crashed or stalled): the step is claimed again after a backoff, or, after its last attempt or its lifetime, it
     * ends as a research that found nothing, so the draft is not left waiting for a step that cannot run.
     */
    @Transactional
    void recover(UUID stepId) {
        Step probe = steps.step(stepId).orElse(null);
        if (probe == null) return;
        Tx tx = lifecycle.lock(probe.sessionId());
        if (tx == null) return;
        Step step = steps.lockExpired(stepId).orElse(null);
        if (step == null) return;
        if (!step.cancelRequested() && step.attempts() < settings.step().maxAttempts() && !lifecycle.expired(step)) {
            steps.requeue(stepId, lifecycle.backoff(step.attempts()).toSeconds(), "LEASE_EXPIRED");
            LOG.warn("generation_lease_recovered step_id={} session_id={} attempt={}", stepId, step.sessionId(), step.attempts());
            return;
        }
        giveUp(tx, step);
    }

    /** A READY step whose lifetime ran out while it waited: never claimed again, it ends as a research that found nothing. */
    @Transactional
    void expire(UUID stepId) {
        Step probe = steps.step(stepId).orElse(null);
        if (probe == null) return;
        Tx tx = lifecycle.lock(probe.sessionId());
        if (tx == null) return;
        Step step = steps.lockReady(stepId).orElse(null);
        if (step == null) return;
        giveUp(tx, step);
    }

    private void giveUp(Tx tx, Step step) {
        Artifact artifact = step.artifactId() == null ? null : repository.artifact(step.sessionId(), step.artifactId()).orElse(null);
        if (step.cancelRequested() || artifact == null || !SessionLifecycle.runnable(tx.session) || !SessionLifecycle.open(artifact)) {
            steps.finish(step.stepId(), "CANCELLED", null, null);
            return;
        }
        LOG.warn("generation_research_gave_up step_id={} session_id={} attempts={}", step.stepId(), step.sessionId(), step.attempts());
        research.upsert(artifact.artifactId(), tx.session.sessionId(), tx.session.ownerId(), 0, List.of());
        steps.finish(step.stepId(), "SUCCEEDED", null, null);
        steps.promoteDependents(step.stepId());
        tx.touch = false;
        lifecycle.flush(tx);
    }
}
