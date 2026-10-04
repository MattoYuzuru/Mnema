package app.mnema.learning.generation;

import app.mnema.learning.ai.TextGeneration;
import app.mnema.learning.generation.Rows.Session;
import app.mnema.learning.generation.SessionLifecycle.Failure;
import app.mnema.learning.usage.AdmissionPricing;
import app.mnema.learning.usage.Bucket;
import app.mnema.learning.usage.Reservation;
import app.mnema.learning.usage.ReservationScope;
import app.mnema.learning.usage.ReservationState;
import app.mnema.learning.usage.UsageLedger;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The early exits of the PLAN step, before any provider call (#295): a step whose claim is void does nothing, a smart-plan cap that is full and a plan
 * hold that cannot pay end the plan without a call. The races after the call (the cap filling, the hold ending) are in {@code GenerationPlanIntegrationTest}.
 */
class PlanExecutorTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final TextGeneration text = mock(TextGeneration.class);
    private final GenerationRepository repository = mock(GenerationRepository.class);
    private final SessionLifecycle lifecycle = mock(SessionLifecycle.class);
    private final PlanContexts contexts = mock(PlanContexts.class);
    private final UsageLedger ledger = mock(UsageLedger.class);
    private final StepExecutor.StepControl control = mock(StepExecutor.StepControl.class);
    private final PlanExecutor executor = new PlanExecutor(text, repository, lifecycle, contexts, mock(Plans.class), ledger, mock(AdmissionPricing.class),
            mock(ProviderKeys.class), settings(), new SimpleMeterRegistry());

    private static GenerationSettings settings() {
        return new GenerationSettings(Duration.ofDays(30), 3, BigDecimal.valueOf(85), 0.55,
                new GenerationSettings.Worker(Duration.ofSeconds(30), Duration.ofSeconds(3), Duration.ofSeconds(2), 4, Duration.ofMinutes(10)),
                new GenerationSettings.Step(3, Duration.ofSeconds(5), Duration.ofMinutes(2), Duration.ofMinutes(6), Duration.ofHours(1)),
                new GenerationSettings.Stream(Duration.ofMillis(750), 24_576), new GenerationSettings.Context(200, 40, 40, 2_500, 6_000, 12_000, 5_000),
                new GenerationSettings.Retention(Duration.ofMinutes(10), Duration.ofDays(1), Duration.ofDays(3), Duration.ofDays(1), 50),
                new GenerationSettings.Edit(Duration.ofMinutes(2)), new GenerationSettings.Intent(30, Duration.ofSeconds(20)),
                new GenerationSettings.Planner(true, Duration.ofMinutes(4), 16_000));
    }

    private final UUID owner = UUID.randomUUID();
    private final UUID planHold = UUID.randomUUID();
    private final StepClaim claim = new StepClaim(UUID.randomUUID(), UUID.randomUUID(), null, owner, "PLAN", "TEXT", UUID.randomUUID(), 1,
            Instant.now().plusSeconds(60), JSON.createObjectNode().put("credits", 20).put("budgetCredits", 10).put("reservationId", planHold.toString()));
    private final Session session = new Session(claim.sessionId(), owner, UUID.randomUUID(), "MATERIALS", "PLANNING", null, JSON.createObjectNode(),
            UUID.randomUUID(), 1, 0, null, null, null);

    private Reservation hold(int held) {
        return new Reservation(planHold, owner, ReservationScope.STEP, claim.sessionId(), null, "2026-10", ReservationState.ACTIVE, held, 0, "rc-v1",
                Instant.now(), Instant.now().plusSeconds(60));
    }

    @Test
    void aVoidClaimDoesNothing() {
        when(lifecycle.beginPlan(claim)).thenReturn(false);
        executor.execute(claim, control);
        verifyNoInteractions(text, ledger, contexts);
    }

    @Test
    void aFullCapEndsThePlanWithoutACall() {
        when(lifecycle.beginPlan(claim)).thenReturn(true);
        when(repository.session(claim.sessionId())).thenReturn(Optional.of(session));
        when(ledger.fairUseFits(owner, Bucket.SMART_PLAN, 1)).thenReturn(false);
        executor.execute(claim, control);
        verify(lifecycle).fail(eq(claim), argThat((Failure failure) -> "USAGE_LIMIT".equals(failure.errorCode()) && failure.kind() == Failure.Kind.FAIL));
        verify(text, never()).generate(any());
        verifyNoInteractions(contexts);
    }

    @Test
    void aHoldThatCannotPayEndsThePlanWithoutACall() {
        when(lifecycle.beginPlan(claim)).thenReturn(true);
        when(repository.session(claim.sessionId())).thenReturn(Optional.of(session));
        when(ledger.fairUseFits(owner, Bucket.SMART_PLAN, 1)).thenReturn(true);
        when(ledger.reservation(owner, planHold)).thenReturn(Optional.of(hold(19)));
        executor.execute(claim, control);
        verify(lifecycle).fail(eq(claim), argThat((Failure failure) -> "ESTIMATE_EXCEEDED".equals(failure.errorCode())));
        // an ended hold is the same
        when(ledger.reservation(owner, planHold)).thenReturn(Optional.empty());
        executor.execute(claim, control);
        verify(text, never()).generate(any());
        verifyNoInteractions(contexts);
    }
}
