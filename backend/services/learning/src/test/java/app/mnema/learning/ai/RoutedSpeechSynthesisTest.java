package app.mnema.learning.ai;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Routing, fallback, breaker, budget, permits, journal and guards of the speech router on scripted adapters (no network, no database). */
class RoutedSpeechSynthesisTest {
    /** An adapter whose answers a test scripts. */
    private static class Scripted implements SpeechAdapter {
        private final String provider;
        private final boolean russianOnly;
        final List<SpeechSynthesis.Request> calls = new ArrayList<>();
        AiResult<SpeechSynthesis.Audio> answer;
        boolean configured = true;
        long cost = 1_234;

        Scripted(String provider, boolean russianOnly) {
            this.provider = provider;
            this.russianOnly = russianOnly;
            this.answer = null;
        }

        @Override public String provider() { return provider; }

        @Override public boolean configured() { return configured; }

        @Override public AiProperties.EgressMode egress() { return AiProperties.EgressMode.DIRECT; }

        @Override public boolean supports(String lang) { return !russianOnly || lang.startsWith("ru"); }

        @Override public String voiceName(String voice) { return provider + "-" + voice; }

        @Override public String format() { return provider.equals("yandex") ? "mp3" : "wav"; }

        @Override
        public AiResult<SpeechSynthesis.Audio> synthesize(String model, String modelVersion, SpeechSynthesis.Request request, Duration budget) {
            calls.add(request);
            if (answer != null) return answer;
            return AiResult.ok(new SpeechSynthesis.Audio(new byte[] {1}, "audio/wav", request.text().length(), 1_000,
                    new SpeechSynthesis.Identity(provider, model, modelVersion, format(), voiceName(request.voice())), 0L));
        }

        @Override public long costMicros(SpeechSynthesis.Audio audio) { return cost; }
    }

    private final Scripted google = new Scripted("google", false);
    private final Scripted yandex = new Scripted("yandex", true);
    private final SpeechTestSupport.RecordingJournal journal = new SpeechTestSupport.RecordingJournal();
    private final AtomicLong spent = new AtomicLong();
    private final AiTestSupport.MutableClock clock = new AiTestSupport.MutableClock(java.time.Instant.parse("2026-10-04T10:00:00Z"));

    private RoutedSpeechSynthesis router(List<String> tts, List<String> ttsRu) {
        return router(tts, ttsRu, SpeechSettings.defaults());
    }

    private RoutedSpeechSynthesis router(List<String> tts, List<String> ttsRu, SpeechSettings settings) {
        AiProperties properties = SpeechTestSupport.properties(tts, ttsRu, Map.of());
        Map<String, SpeechAdapter> adapters = new LinkedHashMap<>();
        adapters.put("google", google);
        adapters.put("yandex", yandex);
        return new RoutedSpeechSynthesis(properties, settings, adapters, new BreakerRegistry(clock, properties.breaker()),
                new AiBudget(properties.budget(), (capability, since) -> spent.get(), Clock.systemUTC()), journal, new AiTelemetry(new SimpleMeterRegistry()));
    }

    private static final String GEMINI = "google:gemini-3.8-flash-tts";
    private static final String KIT = "yandex:speechkit-v1";

    private static AiFailure failure(AiResult<SpeechSynthesis.Audio> result) {
        assertThat(result).isInstanceOf(AiResult.Failed.class);
        return ((AiResult.Failed<SpeechSynthesis.Audio>) result).failure();
    }

    @Test
    void theFirstUsableEntryAnswersAndTheCallIsJournaledWithItsCostAndWithoutTheText() {
        RoutedSpeechSynthesis router = router(List.of(GEMINI, KIT), List.of());

        AiResult<SpeechSynthesis.Audio> result = router.synthesize(SpeechTestSupport.request("секретный текст", "ja", "female"));

        assertThat(result).isInstanceOf(AiResult.Ok.class);
        SpeechSynthesis.Audio audio = ((AiResult.Ok<SpeechSynthesis.Audio>) result).value();
        assertThat(audio.costMicros()).isEqualTo(1_234);
        assertThat(audio.identity().provider()).isEqualTo("google");
        assertThat(yandex.calls).isEmpty();
        assertThat(journal.intents).hasSize(1);
        assertThat(journal.intents.getFirst().capability()).isEqualTo(AiCapability.TTS);
        assertThat(journal.intents.getFirst().provider()).isEqualTo("google");
        assertThat(journal.intents.getFirst().requestHash()).matches("[0-9a-f]{64}").doesNotContain("секретный");
        assertThat(journal.outcomes.getFirst().outcome()).isEqualTo("OK");
        assertThat(journal.outcomes.getFirst().costMicros()).isEqualTo(1_234);
    }

    @Test
    void aFailingEntryFallsThroughToTheNextOneAndARefusalEndsTheCall() {
        RoutedSpeechSynthesis router = router(List.of(GEMINI, KIT), List.of());
        google.answer = AiResult.failed(new AiFailure.Transient("http_503"));

        // google is down: Yandex serves Russian, but cannot serve Japanese, so that call fails with google's failure
        assertThat(router.synthesize(SpeechTestSupport.request("привет", "ru", "male"))).isInstanceOf(AiResult.Ok.class);
        assertThat(yandex.calls).hasSize(1);
        assertThat(failure(router.synthesize(SpeechTestSupport.request("行く", "ja", "male")))).isInstanceOf(AiFailure.Transient.class);
        assertThat(yandex.calls).hasSize(1);

        google.answer = AiResult.failed(new AiFailure.Refusal("http_400"));
        assertThat(failure(router.synthesize(SpeechTestSupport.request("привет", "ru", "male")))).isInstanceOf(AiFailure.Refusal.class);
        assertThat(yandex.calls).hasSize(1);
    }

    @Test
    void theRussianRouteReplacesTheGeneralOneForRussianOnlyWhenItIsNotEmpty() {
        RoutedSpeechSynthesis plain = router(List.of(GEMINI), List.of());
        assertThat(plain.identity("ru", "female")).hasValueSatisfying(identity -> assertThat(identity.provider()).isEqualTo("google"));

        RoutedSpeechSynthesis split = router(List.of(GEMINI), List.of(KIT, GEMINI));
        assertThat(split.identity("ru", "female")).hasValueSatisfying(identity -> {
            assertThat(identity.provider()).isEqualTo("yandex");
            assertThat(identity.format()).isEqualTo("mp3");
            assertThat(identity.voice()).isEqualTo("yandex-female");
            assertThat(identity.modelVersion()).isEqualTo("v1");
        });
        assertThat(split.identity("ru-RU", "male")).hasValueSatisfying(identity -> assertThat(identity.provider()).isEqualTo("yandex"));
        assertThat(split.identity("ja", "male")).hasValueSatisfying(identity -> assertThat(identity.provider()).isEqualTo("google"));
        assertThat(split.synthesize(SpeechTestSupport.request("привет", "ru", "female"))).isInstanceOf(AiResult.Ok.class);
        assertThat(yandex.calls).hasSize(1);
        assertThat(google.calls).isEmpty();
    }

    @Test
    void anEntryWithoutAKeyIsSkippedAndNothingConfiguredIsNotConfigured() {
        RoutedSpeechSynthesis router = router(List.of(GEMINI, KIT), List.of());
        assertThat(router.configured()).isTrue();
        google.configured = false;
        assertThat(router.identity("ru", "female")).hasValueSatisfying(identity -> assertThat(identity.provider()).isEqualTo("yandex"));
        assertThat(router.identity("ja", "female")).isEmpty();
        assertThat(failure(router.synthesize(SpeechTestSupport.request("行く", "ja", "female")))).isInstanceOf(AiFailure.NotConfigured.class);
        yandex.configured = false;
        assertThat(router.configured()).isFalse();
        assertThat(router.identity("ru", "female")).isEmpty();
        assertThat(router(List.of(), List.of()).configured()).isFalse();
    }

    @Test
    void anOpenBreakerMovesTheIdentityToTheNextEntryAndFailsFastWhenEveryOneIsOpen() {
        RoutedSpeechSynthesis router = router(List.of(GEMINI, KIT), List.of());
        google.answer = AiResult.failed(new AiFailure.Transient("http_503"));
        for (int index = 0; index < 5; index++) router.synthesize(SpeechTestSupport.request("привет", "ru", "female"));
        assertThat(google.calls).hasSize(5);

        // five transport failures opened google's breaker: no further call, and the cache key moves with it
        assertThat(router.identity("ru", "female")).hasValueSatisfying(identity -> assertThat(identity.provider()).isEqualTo("yandex"));
        router.synthesize(SpeechTestSupport.request("привет", "ru", "female"));
        assertThat(google.calls).hasSize(5);
        assertThat(yandex.calls.size()).isGreaterThanOrEqualTo(6);
        // for Japanese only google is a candidate and it is open: the call fails fast, no provider is called
        assertThat(router.identity("ja", "female")).hasValueSatisfying(identity -> assertThat(identity.provider()).isEqualTo("google"));
        assertThat(failure(router.synthesize(SpeechTestSupport.request("行く", "ja", "female")))).isInstanceOf(AiFailure.CircuitOpen.class);
        assertThat(google.calls).hasSize(5);
    }

    @Test
    void anUnusableAnswerFallsBackButDoesNotOpenTheBreaker() {
        RoutedSpeechSynthesis router = router(List.of(GEMINI), List.of());
        google.answer = AiResult.failed(new AiFailure.InvalidOutput("not_wav"));
        for (int index = 0; index < 8; index++) assertThat(failure(router.synthesize(SpeechTestSupport.request("x", "en", "male")))).isInstanceOf(AiFailure.InvalidOutput.class);
        assertThat(google.calls).hasSize(8);
        assertThat(journal.outcomes).allSatisfy(outcome -> assertThat(outcome.outcome()).isEqualTo("INVALID_OUTPUT"));
    }

    @Test
    void aTextOverTheBoundIsRefusedBeforeAnyCallAndASpentBudgetStopsTheCall() {
        RoutedSpeechSynthesis router = router(List.of(GEMINI), List.of());
        assertThat(failure(router.synthesize(SpeechTestSupport.request("я".repeat(601), "ru", "female")))).isEqualTo(new AiFailure.Refusal("text_too_long"));
        assertThat(google.calls).isEmpty();
        assertThat(journal.intents).isEmpty();

        spent.set(5_000_000);
        assertThat(failure(router.synthesize(SpeechTestSupport.request("привет", "ru", "female")))).isInstanceOf(AiFailure.BudgetExhausted.class);
        assertThat(google.calls).isEmpty();
    }

    @Test
    void aCallInsideATransactionIsAProgrammingError() {
        RoutedSpeechSynthesis router = router(List.of(GEMINI), List.of());
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> router.synthesize(SpeechTestSupport.request("привет", "ru", "female"))).isInstanceOf(IllegalStateException.class);
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void anAdapterThatThrowsIsATransientFailureAndAJournalThatCannotBeWrittenStopsTheCall() {
        RoutedSpeechSynthesis router = router(List.of(GEMINI), List.of());
        google.answer = null;
        Scripted throwing = new Scripted("google", false) {
            @Override
            public AiResult<SpeechSynthesis.Audio> synthesize(String model, String version, SpeechSynthesis.Request request, Duration budget) {
                throw new IllegalStateException("boom");
            }
        };
        AiProperties properties = SpeechTestSupport.properties(List.of(GEMINI), List.of(), Map.of());
        var broken = new RoutedSpeechSynthesis(properties, SpeechSettings.defaults(), Map.of("google", throwing), new BreakerRegistry(clock, properties.breaker()),
                new AiBudget(properties.budget(), (capability, since) -> 0, Clock.systemUTC()), journal, new AiTelemetry(new SimpleMeterRegistry()));
        assertThat(failure(broken.synthesize(SpeechTestSupport.request("x", "en", "male")))).isEqualTo(new AiFailure.Transient("adapter_error"));

        var noJournal = new RoutedSpeechSynthesis(properties, SpeechSettings.defaults(), Map.of("google", google), new BreakerRegistry(clock, properties.breaker()),
                new AiBudget(properties.budget(), (capability, since) -> 0, Clock.systemUTC()), new CallJournal() {
                    @Override public java.util.UUID begin(Intent intent) { throw new IllegalStateException("db down"); }

                    @Override public void finish(java.util.UUID callId, Outcome outcome) { }
                }, new AiTelemetry(new SimpleMeterRegistry()));
        assertThat(failure(noJournal.synthesize(SpeechTestSupport.request("x", "en", "male")))).isEqualTo(new AiFailure.Transient("journal_unavailable"));
        assertThat(google.calls).isEmpty();
    }

    @Test
    void aRouteEntryMustBeAKnownProviderWithAPriceWhenItIsBilledByTokens() {
        for (String bad : List.of("google", "google:", ":m", "elevenlabs:v1", "google:gemini-unpriced")) {
            assertThatThrownBy(() -> router(List.of(bad), List.of())).isInstanceOf(IllegalArgumentException.class);
        }
        // SpeechKit is billed by character and needs no price entry
        assertThat(router(List.of("yandex:speechkit-v1"), List.of()).configured()).isTrue();
        // a blank entry (an empty property) is no entry at all
        assertThat(router(List.of(" "), List.of("")).configured()).isFalse();
    }

    @Test
    void theRequestValidatesItsFieldsAndNeverPrintsTheText() {
        assertThatThrownBy(() -> new SpeechSynthesis.Request(" ", "ja", "male", 0, null, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SpeechSynthesis.Request("x", "ja", "robot", 0, null, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SpeechSynthesis.Request("x", "ja", "male", -1, null, 1)).isInstanceOf(IllegalArgumentException.class);
        SpeechSynthesis.Request request = new SpeechSynthesis.Request("секрет", null, "male", 2, null, 0);
        assertThat(request.lang()).isEqualTo("en");
        assertThat(request.attempt()).isEqualTo(1);
        assertThat(request.toString()).doesNotContain("секрет").contains("take=2");
    }
}
