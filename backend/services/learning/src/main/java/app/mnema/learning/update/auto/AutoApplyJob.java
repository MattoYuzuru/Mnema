package app.mnema.learning.update.auto;

import app.mnema.learning.fork.DeckPreparingException;
import app.mnema.learning.platform.api.ContentUnavailableException;
import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.api.ResourceLimitExceededException;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.platform.concurrency.VersionConflictException;
import app.mnema.learning.platform.idempotency.IdempotencyConflictException;
import app.mnema.learning.platform.jobs.Job;
import app.mnema.learning.platform.jobs.JobHandler;
import app.mnema.learning.platform.jobs.PermanentJobException;
import app.mnema.learning.platform.jobs.RetryableJobException;
import app.mnema.learning.platform.jobs.Slice;
import app.mnema.learning.update.apply.ApplyService;
import app.mnema.learning.update.apply.ApplyService.AutoOutcome;
import app.mnema.learning.update.apply.ApplyService.AutoResult;
import app.mnema.learning.update.plan.UpdatePlanStaleException;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The handler of the queue {@value AutoUpdateFanout#AUTO_QUEUE} (Updates/6 part B, #445): the automatic apply of ONE copy for ONE published revision (payload
 * {@code {deckId, revisionId}}, account = the copy's owner, so one account's copies run one after another). One slice is the whole job: {@link ApplyService#applyAuto} computes the
 * plan, takes the SAFE units, moves the base, records the rest as pending and (when the apply made a revision) the «Что нового» entry, all inside the slice's transaction, so the
 * job's end and its effects commit together and a crash repeats the slice from nothing.
 *
 * <p>Nothing here runs on a read path. With the kill switch off the job ends without writing. A copy that is up to date, closed, detached, busy with the owner's own apply or has
 * nothing safe to take is left exactly as it is. A plan that went stale while the slice ran (the owner edited the copy) is retried by the executor with a fresh plan; a refusal no
 * retry will cure ends the job with a short code.
 */
@Component
class AutoApplyJob implements JobHandler {
    private final ApplyService applies;
    private final AutoUpdateSettings settings;
    private final MeterRegistry meters;

    AutoApplyJob(ApplyService applies, AutoUpdateSettings settings, MeterRegistry meters) {
        this.applies = applies;
        this.settings = settings;
        this.meters = meters;
    }

    @Override
    public String queue() { return AutoUpdateFanout.AUTO_QUEUE; }

    @Override
    public Slice run(Job job) {
        if (!settings.enabled()) {
            outcome("disabled");
            return Slice.done(0);
        }
        if (job.accountId() == null) throw new PermanentJobException("payload_invalid");
        UUID deck = deck(job);
        AutoResult result;
        try {
            result = applies.applyAuto(job.accountId(), deck);
        } catch (VersionConflictException | UpdatePlanStaleException stale) {
            outcome("stale");
            throw new RetryableJobException("stale_plan");
        } catch (ContentUnavailableException taken) {
            outcome("failed");
            throw new PermanentJobException("taken_down");
        } catch (ResourceNotFoundException missing) {
            outcome("failed");
            throw new PermanentJobException("not_found");
        } catch (InvalidRequestException | ResourceLimitExceededException | IdempotencyConflictException refused) {
            outcome("failed");
            throw new PermanentJobException("plan_invalid");
        } catch (DeckPreparingException preparing) {
            outcome("failed");
            throw new PermanentJobException("deck_preparing");
        }
        outcome(result.outcome().name().toLowerCase(java.util.Locale.ROOT));
        if (result.planUnits() > 0) meters.summary("mnema_deck_update_auto_plan_units").record(result.planUnits());
        // units of an apply that was queued as the combined job are only SUBMITTED: that job may still fail (stale head, switch off), so they are not counted as applied
        if (result.accepted() > 0) {
            meters.counter("mnema_deck_update_auto_units_total", "result", result.outcome() == AutoOutcome.QUEUED ? "submitted" : "applied").increment(result.accepted());
        }
        if (result.unresolved() > 0) meters.counter("mnema_deck_update_auto_units_total", "result", "left_to_owner").increment(result.unresolved());
        return Slice.done(result.accepted());
    }

    private void outcome(String name) {
        meters.counter("mnema_deck_update_auto_total", "outcome", name).increment();
    }

    private static UUID deck(Job job) {
        try {
            return UUID.fromString(job.payload().path("deckId").stringValue(""));
        } catch (IllegalArgumentException invalid) {
            throw new PermanentJobException("payload_invalid");
        }
    }
}
