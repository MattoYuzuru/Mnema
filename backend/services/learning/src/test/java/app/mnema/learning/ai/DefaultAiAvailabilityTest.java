package app.mnema.learning.ai;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** The availability matrix behind {@code aiGeneration}: not configured, available, temporarily unavailable. */
class DefaultAiAvailabilityTest {
    private final AiTestSupport.MutableClock clock = new AiTestSupport.MutableClock(Instant.parse("2026-10-02T10:00:00Z"));
    private final AtomicLong spent = new AtomicLong();
    private final ScriptedAdapter deepseek = new ScriptedAdapter("deepseek");
    private final ScriptedAdapter gigachat = new ScriptedAdapter("gigachat");

    private record Rig(DefaultAiAvailability availability, BreakerRegistry breakers) { }

    private Rig rig(String provider, String secret) {
        AiProperties base = AiTestSupport.properties(provider, AiTestSupport.routes(List.of("deepseek:deepseek-flash", "gigachat:GigaChat-2"),
                List.of(), List.of()), Map.of());
        AiProperties properties = new AiProperties(base.provider(), base.routes(), base.providers(), base.models(), base.transport(),
                base.retry(), base.breaker(), base.permits(), base.budget(), new AiProperties.UserKey(secret, "k1"), base.prompt());
        var breakers = new BreakerRegistry(clock, properties.breaker());
        var routing = new AiRouting(properties, Map.of("deepseek", deepseek, "gigachat", gigachat, "stub", new StubTextAdapter()));
        var budget = new AiBudget(properties.budget(), (capability, since) -> spent.get(), clock);
        return new Rig(new DefaultAiAvailability(routing, breakers, budget, new UserKeys(properties.userKey()), "stub".equals(provider)), breakers);
    }

    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    @Test
    void withoutAUsableAdapterOrTheUserKeySecretTextIsNotConfigured() {
        deepseek.unconfigured();
        gigachat.unconfigured();
        assertThat(rig("", SECRET).availability().text()).isEqualTo(AiAvailability.State.NOT_CONFIGURED);
        var keyed = new ScriptedAdapter("deepseek");
        AiProperties base = AiTestSupport.properties("", AiTestSupport.routes(List.of("deepseek:deepseek-flash"), List.of(), List.of()), Map.of());
        var noSecret = new DefaultAiAvailability(new AiRouting(base, Map.of("deepseek", keyed)),
                new BreakerRegistry(clock, base.breaker()), new AiBudget(base.budget(), (c, s) -> 0, clock),
                new UserKeys(new AiProperties.UserKey("", "k1")), false);
        assertThat(noSecret.text()).as("a key without the user-key secret cannot generate").isEqualTo(AiAvailability.State.NOT_CONFIGURED);
    }

    @Test
    void aKeyAndTheSecretMakeItAvailableAndTheStubNeedsNeither() {
        assertThat(rig("", SECRET).availability().text()).isEqualTo(AiAvailability.State.AVAILABLE);
        deepseek.unconfigured();
        gigachat.unconfigured();
        assertThat(rig("stub", "").availability().text()).isEqualTo(AiAvailability.State.AVAILABLE);
    }

    @Test
    void anOpenBreakerOnEveryCandidateIsTemporaryButAHealthyFallbackKeepsItAvailable() {
        Rig rig = rig("", SECRET);
        for (int index = 0; index < 5; index++) {
            var breaker = rig.breakers().of("deepseek", AiCapability.TEXT);
            breaker.onFailure(breaker.tryAcquire());
        }
        assertThat(rig.availability().text()).as("gigachat is healthy").isEqualTo(AiAvailability.State.AVAILABLE);
        for (int index = 0; index < 5; index++) {
            var breaker = rig.breakers().of("gigachat", AiCapability.TEXT);
            breaker.onFailure(breaker.tryAcquire());
        }
        assertThat(rig.availability().text()).isEqualTo(AiAvailability.State.TEMPORARILY_UNAVAILABLE);
        clock.advance(Duration.ofSeconds(31));
        assertThat(rig.availability().text()).as("a probe may go again").isEqualTo(AiAvailability.State.AVAILABLE);
    }

    @Test
    void aSpentDailyBudgetIsTemporaryAndPortsFollowTheirOwnBudget() {
        Rig rig = rig("", SECRET);
        spent.set(10_000_000);
        assertThat(rig.availability().text()).isEqualTo(AiAvailability.State.TEMPORARILY_UNAVAILABLE);
        assertThat(rig.availability().port(AiCapability.SEARCH, true)).isEqualTo(AiAvailability.State.TEMPORARILY_UNAVAILABLE);
        spent.set(0);
        clock.advance(Duration.ofMinutes(1));
        assertThat(rig.availability().port(AiCapability.SEARCH, true)).isEqualTo(AiAvailability.State.AVAILABLE);
        assertThat(rig.availability().port(AiCapability.TTS, false)).isEqualTo(AiAvailability.State.NOT_CONFIGURED);
    }
}
