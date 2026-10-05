package app.mnema.learning.ai;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in smoke test of the real speech-to-text providers; skipped unless {@code MNEMA_AI_LIVE=true}, and each provider is skipped without its credentials:
 * <pre>
 * MNEMA_AI_LIVE=true MNEMA_STT_LIVE_WAV=/path/to/clip.ogg ./gradlew :services:learning:cleanTest :services:learning:test --tests '*SttLive*'
 * </pre>
 * Gemini needs {@code MNEMA_AI_GOOGLE_API_KEY} (the test reaches it directly: production uses the egress proxy, a workstation outside Russia does not need it);
 * the self-hosted container needs {@code MNEMA_AI_STT_BASE_URL} (and, if it asks, {@code MNEMA_AI_STT_API_KEY}). The clip is {@code MNEMA_STT_LIVE_WAV}
 * (any file of a type the providers take: it is sent as {@code audio/ogg} unless {@code MNEMA_STT_LIVE_MIME} says otherwise) and its words
 * {@code MNEMA_STT_LIVE_TEXT} (optional: when set the transcript must contain its first word). It prints latency, seconds and cost, never the key, the URL or the text.
 */
@EnabledIfEnvironmentVariable(named = "MNEMA_AI_LIVE", matches = "true")
class SttLiveTest {
    private static String env(String name) {
        String value = System.getenv(name);
        return value == null ? "" : value;
    }

    private static byte[] clip() throws java.io.IOException {
        String path = env("MNEMA_STT_LIVE_WAV");
        Assumptions.assumeTrue(!path.isBlank() && Files.isReadable(Path.of(path)), "MNEMA_STT_LIVE_WAV is not set");
        byte[] bytes = Files.readAllBytes(Path.of(path));
        Assumptions.assumeTrue(bytes.length <= 2 << 20, "the clip is over 2 MiB");
        return bytes;
    }

    private static void roundTrip(AiProperties properties, String route, String lang) throws java.io.IOException {
        byte[] audio = clip();
        try (EgressClients clients = EgressClients.create(properties)) {
            var adapters = AiConfiguration.transcriptionAdapters(properties, SttSettings.defaults(), clients, Clock.systemUTC());
            var router = new RoutedTranscription(properties, SttSettings.defaults(), adapters, new BreakerRegistry(Clock.systemUTC(), properties.breaker()),
                    new AiBudget(properties.budget(), (capability, since) -> 0, Clock.systemUTC()), new SpeechTestSupport.RecordingJournal(),
                    new AiTelemetry(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()), new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
            Assumptions.assumeTrue(router.configured(), route + " is not configured here");
            String mime = env("MNEMA_STT_LIVE_MIME").isBlank() ? "audio/ogg" : env("MNEMA_STT_LIVE_MIME");
            var request = new Transcription.Request(audio, mime, lang, List.of(), null, Duration.ofSeconds(30), 10_000, Transcription.Purpose.COMPOSER, null, Transcription.Region.ABROAD);
            long started = System.nanoTime();
            AiResult<Transcription.Transcript> result = router.transcribe(request);
            long millis = Duration.ofNanos(System.nanoTime() - started).toMillis();
            assertThat(result).as(route).isInstanceOf(AiResult.Ok.class);
            Transcription.Transcript transcript = ((AiResult.Ok<Transcription.Transcript>) result).value();
            System.out.println("stt_live route=" + route + " lang=" + lang + " latency_ms=" + millis + " bytes=" + audio.length + " seconds=" + transcript.seconds()
                    + " chars=" + transcript.text().length() + " garbled=" + transcript.garbled());
            assertThat(transcript.text()).isNotBlank();
            String expected = env("MNEMA_STT_LIVE_TEXT");
            if (!expected.isBlank()) assertThat(transcript.text().toLowerCase()).contains(expected.split("\\s+")[0].toLowerCase());
        }
    }

    @Test
    void geminiTranscribesTheClipDirectly() throws Exception {
        String key = env("MNEMA_AI_GOOGLE_API_KEY");
        Assumptions.assumeTrue(!key.isBlank(), "MNEMA_AI_GOOGLE_API_KEY is not set");
        // direct, never in the defaults: the proxy is for production, where Google is not reachable
        var google = new AiProperties.Provider(true, "https://generativelanguage.googleapis.com", key, "", "", "", AiProperties.EgressMode.DIRECT);
        for (String model : new String[] {"gemini-3.5-flash-lite", "gemini-3.5-transcribe"}) {
            roundTrip(TranscriptionTestSupport.properties(List.of("google:" + model), List.of(), Map.of("google", google)), "gemini:" + model, env("MNEMA_STT_LIVE_LANG"));
        }
    }

    @Test
    void theSelfHostedContainerTranscribesTheClip() throws Exception {
        String base = env("MNEMA_AI_STT_BASE_URL");
        Assumptions.assumeTrue(!base.isBlank(), "MNEMA_AI_STT_BASE_URL is not set");
        String model = env("MNEMA_STT_LIVE_MODEL").isBlank() ? "qwen3-asr-0.6b" : env("MNEMA_STT_LIVE_MODEL");
        var selfhost = new AiProperties.Provider(true, base, env("MNEMA_AI_STT_API_KEY"), "", "", "", AiProperties.EgressMode.DIRECT);
        roundTrip(TranscriptionTestSupport.properties(List.of("selfhost:" + model), List.of(), Map.of("selfhost", selfhost)), "selfhost:" + model, env("MNEMA_STT_LIVE_LANG"));
    }
}
