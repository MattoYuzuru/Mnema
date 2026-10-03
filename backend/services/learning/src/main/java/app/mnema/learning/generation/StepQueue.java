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

        record Expired(UUID stepId, UUID sessionId) implements Look { }
    }

    private static final int MAX_LOOKS = 25;

    private final StepRepository steps;
    private final SessionLifecycle lifecycle;
    private final UsageLedger ledger;
    private final GenerationSettings settings;
    private final TransactionTemplate transaction;

    StepQueue(StepRepository steps, SessionLifecycle lifecycle, UsageLedger ledger, GenerationSettings settings,
              PlatformTransactionManager transactions) {
        this.steps = steps;
        this.lifecycle = lifecycle;
        this.ledger = ledger;
        this.settings = settings;
        this.transaction = new TransactionTemplate(transactions);
        this.transaction.setTimeout(10);
    }

    /** The next due step of one of {@code kinds}, claimed with a new lease token; empty when nothing is due. */
    Optional<StepClaim> claim(Collection<String> kinds) {
        for (int look = 0; look < MAX_LOOKS; look++) {
            Look result = transaction.execute(status -> lookOnce(kinds));
            switch (result) {
                case Look.Empty ignored -> {
                    return Optional.empty();
                }
                case Look.Claimed claimed -> {
                    return Optional.of(claimed.claim());
                }
                case Look.Parked parked -> lifecycle.parked(parked.sessionId(), parked.until());
                case Look.Expired expired -> lifecycle.expire(expired.stepId());
            }
        }
        return Optional.empty();
    }

    private Look lookOnce(Collection<String> kinds) {
        Optional<Step> due = steps.pickDue(kinds, settings.worker().accountCap());
        if (due.isEmpty()) return new Look.Empty();
        Step step = due.get();
        // The whole step has a lifetime from its first claim: past it, no further run is started.
        if (step.firstClaimedAt() != null
                && step.firstClaimedAt().plus(settings.step().maxLifetime()).isBefore(java.time.Instant.now())) {
            return new Look.Expired(step.stepId(), step.sessionId());
        }
        int credits = step.input().path("credits").asInt(0);
        if (credits > 0) {
            var room = ledger.dailyDebitRoom(step.ownerId());
            if (room.isPresent() && room.get().remainingTodayCredits() < credits) {
                steps.defer(step.stepId(), room.get().resetsAt());
                return new Look.Parked(step.sessionId(), room.get().resetsAt());
            }
        }
        Duration deadline = step.kind().equals(TextDraftExecutor.KIND) ? settings.step().textDraftDeadline() : Duration.ofMinutes(2);
        Step running = steps.claim(step.stepId(), UUID.randomUUID(), Math.max(1, settings.worker().lease().toSeconds()),
                Math.max(1, deadline.toSeconds()), Math.max(1, settings.step().maxLifetime().toSeconds()));
        return new Look.Claimed(new StepClaim(running.stepId(), running.sessionId(), running.artifactId(), running.ownerId(),
                running.kind(), running.capability(), running.leaseToken(), running.attempts(), running.deadlineAt(),
                running.input()));
    }
}
