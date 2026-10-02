package app.mnema.learning.ai;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** The state machine with a fake clock: 5 consecutive failures inside 60 s open it for 30 s, then one probe. */
class CircuitBreakerTest {
    private final AiTestSupport.MutableClock clock = new AiTestSupport.MutableClock(Instant.parse("2026-10-02T10:00:00Z"));
    private final CircuitBreaker breaker = new CircuitBreaker(clock, new AiProperties.Breaker(5, Duration.ofSeconds(60), Duration.ofSeconds(30)));

    private void fail(int times) {
        for (int index = 0; index < times; index++) {
            long ticket = breaker.tryAcquire();
            assertThat(ticket).isNotEqualTo(CircuitBreaker.REFUSED);
            breaker.onFailure(ticket);
        }
    }

    private boolean granted() { return breaker.tryAcquire() != CircuitBreaker.REFUSED; }

    @Test
    void staysClosedBelowTheThresholdAndASuccessResetsTheStreak() {
        fail(4);
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        breaker.onSuccess(breaker.tryAcquire());
        fail(4);
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breaker.isOpen()).isFalse();
    }

    @Test
    void fiveFailuresInsideTheWindowOpenItAndCallsAreRefusedUntilItElapses() {
        fail(5);
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(breaker.isOpen()).isTrue();
        assertThat(granted()).isFalse();
        clock.advance(Duration.ofSeconds(29));
        assertThat(granted()).isFalse();
        assertThat(breaker.isOpen()).isTrue();
    }

    @Test
    void failuresSpreadOverMoreThanTheWindowDoNotAccumulate() {
        fail(4);
        clock.advance(Duration.ofSeconds(61));
        fail(4);
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        fail(1);
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void afterTheOpenPeriodExactlyOneProbeIsAdmittedAndItsOutcomeDecides() {
        fail(5);
        clock.advance(Duration.ofSeconds(30));
        assertThat(breaker.isOpen()).as("a probe may go now").isFalse();
        long probe = breaker.tryAcquire();
        assertThat(probe).isNotEqualTo(CircuitBreaker.REFUSED);
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        assertThat(granted()).as("only one probe at a time").isFalse();
        assertThat(breaker.isOpen()).isTrue();
        breaker.onSuccess(probe);
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(granted()).isTrue();
    }

    @Test
    void aFailedProbeReopensForAnotherFullPeriod() {
        fail(5);
        clock.advance(Duration.ofSeconds(31));
        long probe = breaker.tryAcquire();
        assertThat(probe).isNotEqualTo(CircuitBreaker.REFUSED);
        breaker.onFailure(probe);
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
        clock.advance(Duration.ofSeconds(29));
        assertThat(granted()).isFalse();
        clock.advance(Duration.ofSeconds(2));
        assertThat(granted()).isTrue();
    }

    @Test
    void anUnusedProbePermissionCanBeGivenBack() {
        fail(5);
        clock.advance(Duration.ofSeconds(31));
        long probe = breaker.tryAcquire();
        assertThat(probe).isNotEqualTo(CircuitBreaker.REFUSED);
        breaker.release(probe);
        assertThat(granted()).isTrue();
    }

    @Test
    void aSlowCallAdmittedBeforeTheBreakerOpenedCannotCloseItOrCountAgainstTheNextStreak() {
        long slow = breaker.tryAcquire();
        fail(5);
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
        breaker.onSuccess(slow);
        assertThat(breaker.state()).as("a stale success is ignored").isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(granted()).isFalse();

        clock.advance(Duration.ofSeconds(31));
        long probe = breaker.tryAcquire();
        breaker.release(slow);
        assertThat(granted()).as("a stale release does not free the probe slot").isFalse();
        breaker.onFailure(slow);
        assertThat(breaker.state()).as("a stale failure does not reopen it").isEqualTo(CircuitBreaker.State.HALF_OPEN);
        breaker.onSuccess(probe);
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breaker.currentTicket()).isEqualTo(1);
    }

    @Test
    void theRegistryKeepsOneBreakerPerProviderAndCapability() {
        var registry = new BreakerRegistry(clock, new AiProperties.Breaker(5, Duration.ofSeconds(60), Duration.ofSeconds(30)));
        assertThat(registry.of("deepseek", AiCapability.TEXT)).isSameAs(registry.of("deepseek", AiCapability.TEXT));
        assertThat(registry.of("deepseek", AiCapability.TEXT)).isNotSameAs(registry.of("deepseek", AiCapability.ASSESS));
        assertThat(registry.of("deepseek", AiCapability.TEXT)).isNotSameAs(registry.of("gigachat", AiCapability.TEXT));
    }
}
