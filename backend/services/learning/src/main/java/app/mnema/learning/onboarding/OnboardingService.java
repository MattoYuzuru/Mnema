package app.mnema.learning.onboarding;

import app.mnema.learning.experiment.ExperimentAssignments;
import app.mnema.learning.experiment.ExperimentEvents;
import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.api.RateLimitedException;
import app.mnema.learning.platform.concurrency.VersionConflictException;
import app.mnema.learning.platform.id.UuidPolicy;
import app.mnema.learning.platform.idempotency.CommandIdentity;
import app.mnema.learning.platform.idempotency.CommandReceiptService;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The first-entry journey of an account ({@code contracts/onboarding}): the first read of an eligible account creates its {@code OFFERED} row exactly once and
 * freezes its holdout cohort; the two commands move the row by compare-and-set on its version, each idempotent by command id through the global receipts
 * (the receipt and the update are one transaction, so a repeat returns the stored answer and a failure leaves nothing behind). The service holds no state.
 */
@Service
public class OnboardingService {
    /** The experiment whose {@code control} variant is the holdout (OB-105). */
    static final String EXPERIMENT = "onboarding_v1";
    private static final String SCOPE = "onboarding";
    private static final int MAX_REPLAYS = Short.MAX_VALUE;

    /** @param replayed the answer was stored by an earlier execution of the same command */
    record Result(ObjectNode view, boolean replayed) { }

    private final OnboardingRepository repository;
    private final OnboardingSettings settings;
    private final ExperimentAssignments assignments;
    private final ExperimentEvents experimentEvents;
    private final CommandReceiptService receipts;
    private final MeterRegistry meters;

    @Autowired
    OnboardingService(OnboardingRepository repository, OnboardingSettings settings, ExperimentAssignments assignments, ExperimentEvents experimentEvents,
                      CommandReceiptService receipts, MeterRegistry meters) {
        this.repository = repository;
        this.settings = settings;
        this.assignments = assignments;
        this.experimentEvents = experimentEvents;
        this.receipts = receipts;
        this.meters = meters;
    }

    /**
     * The view of the account; an eligible account without a row gets its {@code OFFERED} row first.
     *
     * @param newAccount Identity marks the account new ({@code mnema_new_account}); an absent or malformed claim is false, so the tour is never offered on a guess
     */
    @Transactional(timeout = 10)
    public ObjectNode read(UUID account, boolean newAccount) {
        UuidPolicy.requireEntityId(account, "account");
        if (!settings.enabled()) return OnboardingView.disabled();
        Optional<OnboardingRepository.Row> row = repository.find(account);
        if (row.isEmpty() && newAccount) {
            Instant now = repository.now();
            if (settings.opens(now) && !repository.hasLiveDeck(account)) row = offer(account, now);
        }
        return row.map(OnboardingView::of).orElseGet(OnboardingView::notOffered);
    }

    /** Applies a tour command. @throws OnboardingUnavailableException the tour is turned off */
    Result journey(UUID account, OnboardingCommands.Journey command) {
        requireEnabled();
        boolean[] ran = new boolean[1];
        JsonNode stored = receipts.execute(new CommandIdentity(command.commandId(), account, SCOPE, "journey.command"), command.envelope(), () -> {
            ran[0] = true;
            return applyJourney(account, command);
        });
        if (ran[0]) {
            // After the commit; the step is a registry key or "none", never client text.
            meters.counter("mnema_onboarding_journey_total", "action", command.action().name(), "step", command.stepKey() == null ? "none" : command.stepKey())
                    .increment();
        }
        return new Result((ObjectNode) stored, !ran[0]);
    }

    /** Applies the survey answers that belong to the tour. @throws OnboardingUnavailableException the tour is turned off */
    Result preferences(UUID account, OnboardingCommands.Preferences command) {
        requireEnabled();
        boolean[] ran = new boolean[1];
        JsonNode stored = receipts.execute(new CommandIdentity(command.commandId(), account, SCOPE, "preferences.command"), command.envelope(), () -> {
            ran[0] = true;
            return applyPreferences(account, command);
        });
        return new Result((ObjectNode) stored, !ran[0]);
    }

    boolean enabled() {
        return settings.enabled();
    }

    void requireEnabled() {
        if (!settings.enabled()) throw new OnboardingUnavailableException();
    }

    /** Inserts the {@code OFFERED} row of an eligible account; of concurrent first reads exactly one inserts and counts the exposure. */
    private Optional<OnboardingRepository.Row> offer(UUID account, Instant now) {
        OnboardingRepository.Cohort cohort = cohort(account);
        OnboardingRepository.Row fresh = new OnboardingRepository.Row(JourneySteps.TOUR_VERSION, JourneyState.OFFERED, cohort, null, null, null, null, now,
                null, 0, 0);
        if (repository.insert(account, fresh, now)) exposure(account);
        return repository.find(account);
    }

    /**
     * The holdout: the account the experiment puts in {@code control} is held out. A disabled experiment or a missing secret buckets nobody
     * ({@link ExperimentAssignments#assigned}), and then everybody gets the tour: nobody is held out by accident.
     */
    private OnboardingRepository.Cohort cohort(UUID account) {
        return assignments.assigned(account, EXPERIMENT).filter("control"::equals).isPresent()
                ? OnboardingRepository.Cohort.CONTROL : OnboardingRepository.Cohort.TOUR;
    }

    /** EXPOSURE is the decision to offer the tour; counted only for an experiment that buckets, and a metric never fails the read. */
    private void exposure(UUID account) {
        if (assignments.assigned(account, EXPERIMENT).isEmpty()) return;
        try {
            experimentEvents.record(account, EXPERIMENT, ExperimentEvents.Event.EXPOSURE);
        } catch (RateLimitedException limited) {
            // Best effort: the counter is analytics, not state.
        }
    }

    private ObjectNode applyJourney(UUID account, OnboardingCommands.Journey command) {
        Optional<OnboardingRepository.Row> found = repository.find(account);
        long current = found.map(OnboardingRepository.Row::rowVersion).orElse(0L);
        if (current != command.rowVersion()) throw new VersionConflictException();
        JourneyAction action = command.action();
        JourneyState from = found.map(OnboardingRepository.Row::state).orElse(JourneyState.NOT_OFFERED);
        JourneyState to = action.target(from);
        if (to == null) throw new OnboardingTransitionException();
        Instant now = repository.now();
        if (found.isEmpty()) {
            // REPLAY from NOT_OFFERED: the row exists outside the cohort (no holdout, no exposure, never auto-offered).
            OnboardingRepository.Row created = new OnboardingRepository.Row(JourneySteps.TOUR_VERSION, to, null, null, command.stepKey(), null, null, now, null,
                    1, 0);
            if (!repository.insert(account, created, now)) throw new VersionConflictException();
        } else {
            OnboardingRepository.Row row = found.get();
            JourneyBranch branch = action == JourneyAction.REPLAY ? null : command.branch() != null ? command.branch() : row.branch();
            if (command.stepKey() != null) {
                JourneyBranch stepBranch = JourneySteps.branchOf(command.stepKey());
                if (stepBranch != null && stepBranch != branch) throw new InvalidRequestException();
            }
            Instant finished = switch (action.finish()) {
                case KEEP -> row.finishedAt();
                case SET -> now;
                case CLEAR -> null;
            };
            boolean replay = action == JourneyAction.REPLAY;
            OnboardingRepository.Row next = new OnboardingRepository.Row(replay ? JourneySteps.TOUR_VERSION : row.tourVersion(), to, row.cohort(), branch,
                    command.stepKey() != null ? command.stepKey() : row.stepKey(), row.experience(), row.weeklyTarget(), row.offeredAt(), finished,
                    replay ? Math.min(row.replayCount() + 1, MAX_REPLAYS) : row.replayCount(), row.rowVersion());
            if (!repository.update(account, next, command.rowVersion(), now)) throw new VersionConflictException();
        }
        return OnboardingView.of(repository.find(account).orElseThrow());
    }

    private ObjectNode applyPreferences(UUID account, OnboardingCommands.Preferences command) {
        Optional<OnboardingRepository.Row> found = repository.find(account);
        long current = found.map(OnboardingRepository.Row::rowVersion).orElse(0L);
        if (current != command.rowVersion()) throw new VersionConflictException();
        // The answers belong to a journey: without a row there is nothing to attach them to.
        OnboardingRepository.Row row = found.orElseThrow(OnboardingTransitionException::new);
        OnboardingRepository.Row next = new OnboardingRepository.Row(row.tourVersion(), row.state(), row.cohort(), row.branch(), row.stepKey(),
                command.setExperience() ? command.experience() : row.experience(),
                command.setWeeklyTarget() ? command.weeklyTarget() : row.weeklyTarget(), row.offeredAt(), row.finishedAt(), row.replayCount(),
                row.rowVersion());
        if (!repository.update(account, next, command.rowVersion(), repository.now())) throw new VersionConflictException();
        return OnboardingView.of(repository.find(account).orElseThrow());
    }
}
