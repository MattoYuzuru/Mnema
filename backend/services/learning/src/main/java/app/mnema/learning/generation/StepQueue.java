package app.mnema.learning.generation;

import app.mnema.learning.generation.Rows.Step;
import app.mnema.learning.usage.UsageLedger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * Claims the next due step: one short transaction of {@code SELECT ... FOR UPDATE SKIP LOCKED}, the daily-burst check and
 * the lease. A step the burst parks stays READY with its {@code next_attempt_at} at the next day start and the owner's
 * session learns when work resumes; the claim then looks at the next candidate. Nothing here takes the session lock (see
 * {@link StepRepository}).
 */
@Service
class StepQueue {
    /** The outcome of one look at the queue. */
    private sealed interface Look {
        record Empty() implements Look { }

        record Parked(UUID sessionId, Instant until) implements Look { }

        record Claimed(StepClaim claim) implements Look { }

        /** @param turn the step belongs to a turn of an artifact (an edit or a media turn): it fails the turn, not an artifact */
        record Expired(UUID stepId, UUID sessionId, String kind, boolean turn, boolean slot) implements Look { }
    }

    private static final int MAX_LOOKS = 25;

    private final StepRepository steps;
    private final SessionLifecycle lifecycle;
    private final EditLifecycle edits;
    private final ImageSearchLifecycle imageSlots;
    private final UsageLedger ledger;
    private final GenerationSettings settings;
    private final TransactionTemplate transaction;

    StepQueue(StepRepository steps, SessionLifecycle lifecycle, EditLifecycle edits, ImageSearchLifecycle imageSlots, UsageLedger ledger,
              GenerationSettings settings, PlatformTransactionManager transactions) {
        this.steps = steps;
        this.lifecycle = lifecycle;
        this.edits = edits;
        this.imageSlots = imageSlots;
        this.ledger = ledger;
        this.settings = settings;
        this.transaction = new TransactionTemplate(transactions);
        this.transaction.setTimeout(10);
    }

    /** The next due step of one of {@code kinds}, claimed with a new lease token; empty when nothing is due. */
    Optional<StepClaim> claim(Collection<String> kinds) {
        return claim(kinds, null);
    }

    /** As {@link #claim(Collection)}, and only a step whose input has the member {@code requiredInput} when one is given. */
    Optional<StepClaim> claim(Collection<String> kinds, String requiredInput) {
        for (int look = 0; look < MAX_LOOKS; look++) {
            Look result = transaction.execute(status -> lookOnce(kinds, requiredInput));
            switch (result) {
                case Look.Empty ignored -> {
                    return Optional.empty();
                }
                case Look.Claimed claimed -> {
                    return Optional.of(claimed.claim());
                }
                case Look.Parked parked -> lifecycle.parked(parked.sessionId(), parked.until());
                case Look.Expired expired -> {
                    if (expired.turn()) edits.expire(expired.stepId());
                    else if (expired.slot()) imageSlots.expire(expired.stepId());
                    else lifecycle.expire(expired.stepId());
                }
            }
        }
        return Optional.empty();
    }

    private Look lookOnce(Collection<String> kinds, String requiredInput) {
        Optional<Step> due = steps.pickDue(kinds, settings.worker().accountCap(), requiredInput);
        if (due.isEmpty()) return new Look.Empty();
        Step step = due.get();
        // an edit and the media turn of an exercise are interactive: a person waits for them
        boolean turn = step.kind().equals(EditExecutor.KIND) || step.input().has("turnId");
        // the initial image search of a slot is neither: it fails its slot, not an artifact or a turn
        boolean slot = ImageSearchExecutor.isSlotStep(step.kind(), step.input());
        // The whole step has a lifetime from its first claim: past it, no further run is started.
        if (step.firstClaimedAt() != null
                && step.firstClaimedAt().plus(settings.step().maxLifetime()).isBefore(java.time.Instant.now())) {
            return new Look.Expired(step.stepId(), step.sessionId(), step.kind(), turn, slot);
        }
        // A person waits for an edit: one that no worker claimed in time is given up, its turn fails and its hold is released
        if (turn && step.firstClaimedAt() == null && step.createdAt() != null
                && step.createdAt().plus(settings.edit().queueTimeout()).isBefore(java.time.Instant.now())) {
            return new Look.Expired(step.stepId(), step.sessionId(), step.kind(), turn, slot);
        }
        int credits = step.input().path("credits").asInt(0);
        // An edit is interactive and costs a few credits, and so is a plan (the owner waits for it in the Workshop): the daily burst never
        // parks them for a day (a turn that waits is a turn that hangs); their debits are still recorded
        boolean plan = step.kind().equals(PlanExecutor.KIND);
        if (credits > 0 && !turn && !plan) {
            var room = ledger.dailyDebitRoom(step.ownerId());
            if (room.isPresent() && room.get().remainingTodayCredits() < credits) {
                steps.defer(step.stepId(), room.get().resetsAt());
                return new Look.Parked(step.sessionId(), room.get().resetsAt());
            }
        }
        Duration deadline = step.kind().equals(TextDraftExecutor.KIND) ? settings.step().textDraftDeadline()
                : plan ? settings.planner().deadline() : step.kind().equals(ImageSearchExecutor.KIND) ? ImageSearchExecutor.DEADLINE
                : Duration.ofMinutes(2);
        Step running = steps.claim(step.stepId(), UUID.randomUUID(), Math.max(1, settings.worker().lease().toSeconds()),
                Math.max(1, deadline.toSeconds()), Math.max(1, settings.step().maxLifetime().toSeconds()));
        return new Look.Claimed(new StepClaim(running.stepId(), running.sessionId(), running.artifactId(), running.ownerId(),
                running.kind(), running.capability(), running.leaseToken(), running.attempts(), running.deadlineAt(),
                running.input()));
    }
}
