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

/** Routing, fallback, breaker, budget, journal, metrics and guards of the transcription router on scripted adapters (no network, no database). */
class RoutedTranscriptionTest {
    /** An adapter whose answers a test scripts. */
    private static class Scripted implements TranscriptionAdapter {
        private final String provider;
        private final Transcription.Region region;
        final List<Transcription.Request> calls = new ArrayList<>();
        final List<Duration> budgets = new ArrayList<>();
        AiResult<Answer> answer;
        boolean configured = true;
        long cost = 777;

        Scripted(String provider, Transcription.Region region) {
            this.provider = provider;
            this.region = region;
        }

        @Override public String provider() { return provider; }

        @Override public boolean configured() { return configured; }

        @Override public AiProperties.EgressMode egress() { return provider.equals("google") ? AiProperties.EgressMode.PROXY : AiProperties.EgressMode.DIRECT; }

        @Override public Transcription.Region region() { return region; }

        @Override
        public AiResult<Answer> transcribe(String model, Transcription.Request request, Duration budget) {
            calls.add(request);
            budgets.add(budget);
            if (answer != null) return answer;
            return AiResult.ok(new Answer(new Transcription.Transcript("текст от " + provider, 3, "ru", false), new Usage(10, 0, 10, 5)));
        }

        @Override public long costMicros(String model, Answer answer) { return cost; }
    }

    private final Scripted google = new Scripted("google", Transcription.Region.ABROAD);
    private final Scripted selfhost = new Scripted("selfhost", Transcription.Region.RU);
    private final SpeechTestSupport.RecordingJournal journal = new SpeechTestSupport.RecordingJournal();
    private final AtomicLong spent = new AtomicLong();
    private final AiTestSupport.MutableClock clock = new AiTestSupport.MutableClock(java.time.Instant.parse("2026-10-05T10:00:00Z"));
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private RoutedTranscription router(List<String> stt, List<String> sttRu) {
        AiProperties properties = TranscriptionTestSupport.properties(stt, sttRu, Map.of());
        Map<String, TranscriptionAdapter> adapters = new LinkedHashMap<>();
        adapters.put("google", google);
        adapters.put("selfhost", selfhost);
        return new RoutedTranscription(properties, SttSettings.defaults(), adapters, new BreakerRegistry(clock, properties.breaker()),
                new AiBudget(properties.budget(), (capability, since) -> spent.get(), Clock.systemUTC()), journal, new AiTelemetry(meters), meters);
    }

    private static final String TRANSCRIBE = "google:gemini-3.5-transcribe";
    private static final String LITE = "google:gemini-3.5-flash-lite";
    private static final String GIGA = "selfhost:gigaam-v3";
    private static final String QWEN = "selfhost:qwen3-asr-0.6b";

    private static AiFailure failure(AiResult<Transcription.Transcript> result) {
        assertThat(result).isInstanceOf(AiResult.Failed.class);
        return ((AiResult.Failed<Transcription.Transcript>) result).failure();
    }

    private static Transcription.Transcript ok(AiResult<Transcription.Transcript> result) {
        assertThat(result).isInstanceOf(AiResult.Ok.class);
        return ((AiResult.Ok<Transcription.Transcript>) result).value();
    }

    @Test
    void theFirstUsableEntryAnswersAndTheCallIsJournaledMeteredAndPricedWithoutTheAudio() {
        RoutedTranscription router = router(List.of(TRANSCRIBE, LITE), List.of());

        Transcription.Transcript transcript = ok(router.transcribe(TranscriptionTestSupport.request("audio/ogg", "en", List.of("Tokyo"))));

        assertThat(transcript.text()).isEqualTo("текст от google");
        assertThat(google.calls).hasSize(1);
        assertThat(journal.intents).hasSize(1);
        CallJournal.Intent intent = journal.intents.getFirst();
        assertThat(intent.capability()).isEqualTo(AiCapability.STT);
        assertThat(intent.provider()).isEqualTo("google");
        assertThat(intent.model()).isEqualTo("gemini-3.5-transcribe");
        assertThat(intent.requestHash()).matches("[0-9a-f]{64}").doesNotContain("Tokyo");
        assertThat(journal.outcomes).singleElement().satisfies(outcome -> {
            assertThat(outcome.outcome()).isEqualTo("OK");
            assertThat(outcome.costMicros()).isEqualTo(777);
            assertThat(outcome.usage().completionTokens()).isEqualTo(5);
        });
        // the call's own series: the generic provider call and the STT latency
        assertThat(meters.get("mnema_ai_calls_total").tag("capability", "stt").tag("outcome", "OK").tag("egress", "proxy").counter().count()).isEqualTo(1);
        assertThat(meters.get("mnema_stt_latency_seconds").tag("provider", "google").tag("outcome", "OK").timer().count()).isEqualTo(1);
        // the whole deadline bounds the call, the call timeout caps it
        assertThat(google.budgets.getFirst()).isLessThanOrEqualTo(SttSettings.defaults().callTimeout());
    }

    @Test
    void aFailingEntryFallsThroughAndARefusalOrASpentBudgetEndsTheCall() {
        RoutedTranscription router = router(List.of(TRANSCRIBE, QWEN), List.of());
        google.answer = AiResult.failed(new AiFailure.RateLimited(Duration.ofSeconds(2)));

        assertThat(ok(router.transcribe(TranscriptionTestSupport.request("en"))).text()).isEqualTo("текст от selfhost");
        assertThat(journal.outcomes.getFirst().outcome()).isEqualTo("RATE_LIMITED");

        google.answer = AiResult.failed(new AiFailure.Refusal("too_long"));
        assertThat(failure(router.transcribe(TranscriptionTestSupport.request("en")))).isEqualTo(new AiFailure.Refusal("too_long"));
        assertThat(selfhost.calls).hasSize(1);

        // the daily budget is read when the router first needs it
        google.answer = null;
        spent.set(5_000_000);
        assertThat(failure(router(List.of(TRANSCRIBE), List.of()).transcribe(TranscriptionTestSupport.request("en")))).isInstanceOf(AiFailure.BudgetExhausted.class);
        assertThat(google.calls).hasSize(2);
    }

    @Test
    void aClipEveryEntryCannotDecodeIsUnsupportedAudioButAnOutageIsNot() {
        RoutedTranscription router = router(List.of(TRANSCRIBE, QWEN), List.of());
        google.answer = AiResult.failed(new AiFailure.InvalidOutput("unsupported_audio"));
        selfhost.answer = AiResult.failed(new AiFailure.InvalidOutput("unsupported_audio"));
        assertThat(failure(router.transcribe(TranscriptionTestSupport.request("en")))).isEqualTo(new AiFailure.InvalidOutput("unsupported_audio"));

        // one entry could not decode it and the other is down: not the learner's clip
        selfhost.answer = AiResult.failed(new AiFailure.Transient("http_503"));
        assertThat(failure(router.transcribe(TranscriptionTestSupport.request("en")))).isEqualTo(new AiFailure.Transient("http_503"));
    }

    @Test
    void theRussianRouteReplacesTheGeneralOneForRussianOnlyWhenItIsNotEmpty() {
        RoutedTranscription router = router(List.of(QWEN, LITE), List.of(GIGA));

        assertThat(ok(router.transcribe(TranscriptionTestSupport.request("audio/ogg", "ru-RU", List.of()))).text()).isEqualTo("текст от selfhost");
        assertThat(selfhost.calls).hasSize(1);
        google.answer = AiResult.failed(new AiFailure.Transient("x"));
        selfhost.answer = AiResult.failed(new AiFailure.Transient("y"));
        // Korean is not Russian: the general route, which falls through to Gemini
        assertThat(failure(router.transcribe(TranscriptionTestSupport.request("ko")))).isEqualTo(new AiFailure.Transient("x"));
        assertThat(selfhost.calls).hasSize(2);
        assertThat(google.calls).hasSize(1);
        // no language: the general route
        router.transcribe(TranscriptionTestSupport.request(null));
        assertThat(selfhost.calls).hasSize(3);

        // an empty Russian route is the general one for everybody
        RoutedTranscription general = router(List.of(LITE), List.of());
        google.answer = null;
        assertThat(ok(general.transcribe(TranscriptionTestSupport.request("ru"))).text()).isEqualTo("текст от google");
    }

    @Test
    void theRegionIsTheFirstUsableEntrysAndMovesWithAnOpenBreaker() {
        RoutedTranscription router = router(List.of(GIGA, TRANSCRIBE), List.of());
        assertThat(router.region("en")).contains(Transcription.Region.RU);
        selfhost.answer = AiResult.failed(new AiFailure.Transient("http_503"));
        for (int index = 0; index < 5; index++) router.transcribe(TranscriptionTestSupport.request("en"));
        // five transport failures opened the breaker of the self-hosted entry: the audio would now leave the country
        assertThat(router.region("en")).contains(Transcription.Region.ABROAD);
        assertThat(router.healthy()).isTrue();

        selfhost.configured = false;
        assertThat(router(List.of(GIGA), List.of()).region("en")).isEmpty();
        assertThat(router(List.of(GIGA), List.of()).configured()).isFalse();
        assertThat(router(List.of(), List.of()).configured()).isFalse();
        // nothing configured is not unhealthy: the capability says PROVIDER_NOT_CONFIGURED instead
        assertThat(router(List.of(GIGA), List.of()).healthy()).isTrue();
    }

    @Test
    void theRouterIsUnhealthyOnlyWhenEveryUsableEntryHasAnOpenBreaker() {
        RoutedTranscription router = router(List.of(TRANSCRIBE, GIGA), List.of());
        google.answer = AiResult.failed(new AiFailure.Transient("http_503"));
        selfhost.answer = AiResult.failed(new AiFailure.NotConfigured("http_401"));
        for (int index = 0; index < 5; index++) router.transcribe(TranscriptionTestSupport.request("en"));

        assertThat(router.healthy()).isFalse();
        assertThat(failure(router.transcribe(TranscriptionTestSupport.request("en")))).isInstanceOf(AiFailure.CircuitOpen.class);
        // the breaker closes after its open period
        clock.advance(Duration.ofMinutes(2));
        assertThat(router.healthy()).isTrue();
    }

    @Test
    void anUnusableAnswerFallsBackButDoesNotOpenTheBreakerAndNothingConfiguredIsNotConfigured() {
        RoutedTranscription router = router(List.of(LITE), List.of());
        google.answer = AiResult.failed(new AiFailure.InvalidOutput("not_json"));
        for (int index = 0; index < 8; index++) assertThat(failure(router.transcribe(TranscriptionTestSupport.request("en")))).isInstanceOf(AiFailure.InvalidOutput.class);
        assertThat(google.calls).hasSize(8);
        assertThat(router.healthy()).isTrue();

        google.configured = false;
        assertThat(failure(router.transcribe(TranscriptionTestSupport.request("en")))).isEqualTo(new AiFailure.NotConfigured("no_route"));
    }

    @Test
    void aCallInsideATransactionIsAProgrammingError() {
        RoutedTranscription router = router(List.of(LITE), List.of());
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> router.transcribe(TranscriptionTestSupport.request("en"))).isInstanceOf(IllegalStateException.class);
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void anAdapterThatThrowsIsTransientAndAJournalThatCannotBeWrittenStopsTheCall() {
        AiProperties properties = TranscriptionTestSupport.properties(List.of(LITE), List.of(), Map.of());
        Scripted throwing = new Scripted("google", Transcription.Region.ABROAD) {
            @Override
            public AiResult<Answer> transcribe(String model, Transcription.Request request, Duration budget) { throw new IllegalStateException("boom"); }
        };
        var broken = new RoutedTranscription(properties, SttSettings.defaults(), Map.of("google", throwing), new BreakerRegistry(clock, properties.breaker()),
                new AiBudget(properties.budget(), (capability, since) -> 0, Clock.systemUTC()), journal, new AiTelemetry(meters), meters);
        assertThat(failure(broken.transcribe(TranscriptionTestSupport.request("en")))).isEqualTo(new AiFailure.Transient("adapter_error"));

        var noJournal = new RoutedTranscription(properties, SttSettings.defaults(), Map.of("google", google), new BreakerRegistry(clock, properties.breaker()),
                new AiBudget(properties.budget(), (capability, since) -> 0, Clock.systemUTC()), new CallJournal() {
                    @Override public java.util.UUID begin(Intent intent) { throw new IllegalStateException("db down"); }

                    @Override public void finish(java.util.UUID callId, Outcome outcome) { }
                }, new AiTelemetry(meters), meters);
        assertThat(failure(noJournal.transcribe(TranscriptionTestSupport.request("en")))).isEqualTo(new AiFailure.Transient("journal_unavailable"));
        assertThat(google.calls).isEmpty();
    }

    @Test
    void aRouteEntryMustBeAKnownProviderAndAGeminiEntryNeedsAPrice() {
        assertThatThrownBy(() -> router(List.of("stub:x"), List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> router(List.of("google:gemini-9-unpriced"), List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> router(List.of("google"), List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> router(List.of("selfhost:"), List.of())).isInstanceOf(IllegalArgumentException.class);
        // a self-hosted model has no price entry
        assertThat(router(List.of("selfhost:anything"), List.of()).configured()).isTrue();
    }
}
