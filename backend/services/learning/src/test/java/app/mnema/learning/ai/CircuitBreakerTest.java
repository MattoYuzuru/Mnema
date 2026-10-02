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
            assertThat(breaker.tryAcquire()).isTrue();
            breaker.onFailure();
        }
    }

    @Test
    void staysClosedBelowTheThresholdAndASuccessResetsTheStreak() {
        fail(4);
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breaker.tryAcquire()).isTrue();
        breaker.onSuccess();
        fail(4);
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breaker.isOpen()).isFalse();
    }

    @Test
    void fiveFailuresInsideTheWindowOpenItAndCallsAreRefusedUntilItElapses() {
        fail(5);
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(breaker.isOpen()).isTrue();
        assertThat(breaker.tryAcquire()).isFalse();
        clock.advance(Duration.ofSeconds(29));
        assertThat(breaker.tryAcquire()).isFalse();
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
        assertThat(breaker.tryAcquire()).isTrue();
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        assertThat(breaker.tryAcquire()).as("only one probe at a time").isFalse();
        assertThat(breaker.isOpen()).isTrue();
        breaker.onSuccess();
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breaker.tryAcquire()).isTrue();
    }

    @Test
    void aFailedProbeReopensForAnotherFullPeriod() {
        fail(5);
        clock.advance(Duration.ofSeconds(31));
        assertThat(breaker.tryAcquire()).isTrue();
        breaker.onFailure();
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
        clock.advance(Duration.ofSeconds(29));
        assertThat(breaker.tryAcquire()).isFalse();
        clock.advance(Duration.ofSeconds(2));
        assertThat(breaker.tryAcquire()).isTrue();
    }

    @Test
    void anUnusedProbePermissionCanBeGivenBack() {
        fail(5);
        clock.advance(Duration.ofSeconds(31));
        assertThat(breaker.tryAcquire()).isTrue();
        breaker.release();
        assertThat(breaker.tryAcquire()).isTrue();
    }

    @Test
    void theRegistryKeepsOneBreakerPerProviderAndCapability() {
        var registry = new BreakerRegistry(clock, new AiProperties.Breaker(5, Duration.ofSeconds(60), Duration.ofSeconds(30)));
        assertThat(registry.of("deepseek", AiCapability.TEXT)).isSameAs(registry.of("deepseek", AiCapability.TEXT));
        assertThat(registry.of("deepseek", AiCapability.TEXT)).isNotSameAs(registry.of("deepseek", AiCapability.ASSESS));
        assertThat(registry.of("deepseek", AiCapability.TEXT)).isNotSameAs(registry.of("gigachat", AiCapability.TEXT));
    }
}
