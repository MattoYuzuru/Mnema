package app.mnema.learning.ai;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in smoke test of the real speech providers; skipped unless {@code MNEMA_AI_LIVE=true}, and each provider is skipped without its credentials:
 * <pre>
 * MNEMA_AI_LIVE=true ./gradlew :services:learning:cleanTest :services:learning:test --tests '*SpeechLive*'
 * </pre>
 * Gemini needs {@code MNEMA_AI_GOOGLE_API_KEY} and the egress proxy ({@code MNEMA_AI_EGRESS_PROXY_URL}, optionally {@code ..._USER} / {@code ..._PASSWORD}); SpeechKit needs
 * {@code MNEMA_AI_TTS_API_KEY} and {@code MNEMA_AI_YANDEX_FOLDER_ID}. Each synthesises one Russian and one Japanese phrase (SpeechKit: Russian only) through
 * the router, asserts shapes (a decodable WAV or an MP3 of a plausible length) and prints the latency of each call, for the spike's latency numbers; it never
 * prints a key, a URL or the audio. Subjective quality (RU, JA, KO) is judged by ear, not here.
 */
@EnabledIfEnvironmentVariable(named = "MNEMA_AI_LIVE", matches = "true")
class SpeechLiveTest {
    private static String env(String name) {
        String value = System.getenv(name);
        return value == null ? "" : value;
    }

    private static void roundTrip(AiProperties properties, SpeechSettings settings, String route, String lang, String text, String format) {
        try (EgressClients clients = EgressClients.create(properties)) {
            var adapters = AiConfiguration.speechAdapters(properties, settings, clients, BigDecimal.valueOf(85), Clock.systemUTC());
            var router = new RoutedSpeechSynthesis(properties, settings, adapters, new BreakerRegistry(Clock.systemUTC(), properties.breaker()),
                    new AiBudget(properties.budget(), (capability, since) -> 0, Clock.systemUTC()), new SpeechTestSupport.RecordingJournal(),
                    new AiTelemetry(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
            Assumptions.assumeTrue(router.configured(), route + " is not configured here");
            long started = System.nanoTime();
            AiResult<SpeechSynthesis.Audio> result = router.synthesize(SpeechTestSupport.request(text, lang, "female"));
            long millis = Duration.ofNanos(System.nanoTime() - started).toMillis();
            assertThat(result).as(route + " " + lang).isInstanceOf(AiResult.Ok.class);
            SpeechSynthesis.Audio audio = ((AiResult.Ok<SpeechSynthesis.Audio>) result).value();
            System.out.println("speech_live route=" + route + " lang=" + lang + " latency_ms=" + millis + " bytes=" + audio.bytes().length
                    + " duration_ms=" + audio.durationMs() + " cost_micros=" + audio.costMicros());
            assertThat(audio.identity().format()).isEqualTo(format);
            assertThat(audio.durationMs()).isBetween(500L, 30_000L);
            if (format.equals("wav")) assertThat(Wav.parse(audio.bytes())).isNotNull();
        }
    }

    /**
     * Through the egress proxy when {@code MNEMA_AI_EGRESS_PROXY_URL} is set; with {@code MNEMA_AI_LIVE_EGRESS=direct} straight from this machine (a
     * developer network where Google answers, as verified 2026-10-05). Both TTS models of the default route are spoken.
     */
    @Test
    void geminiSpeaksARussianAndAJapanesePhrase() {
        String key = env("MNEMA_AI_GOOGLE_API_KEY");
        boolean direct = "direct".equals(env("MNEMA_AI_LIVE_EGRESS"));
        Assumptions.assumeTrue(!key.isBlank() && (direct || !env("MNEMA_AI_EGRESS_PROXY_URL").isBlank()),
                "MNEMA_AI_GOOGLE_API_KEY and the egress proxy (or MNEMA_AI_LIVE_EGRESS=direct) are not set");
        var google = new AiProperties.Provider(true, "https://generativelanguage.googleapis.com", key, "", "", "",
                direct ? AiProperties.EgressMode.DIRECT : AiProperties.EgressMode.PROXY);
        for (String model : List.of("gemini-3.8-flash-lite-tts", "gemini-3.8-flash-tts")) {
            AiProperties base = SpeechTestSupport.properties(List.of("google:" + model), List.of(), Map.of("google", google));
            AiProperties properties = new AiProperties(base.provider(), base.routes(), base.providers(), base.models(), new AiProperties.Transport(Duration.ofSeconds(5),
                    Duration.ofSeconds(60), 8 << 20, Duration.ofSeconds(60)), base.retry(), base.breaker(), base.permits(), base.budget(), base.userKey(), base.prompt(),
                    direct ? new AiProperties.Egress("", "", "", true)
                            : new AiProperties.Egress(env("MNEMA_AI_EGRESS_PROXY_URL"), env("MNEMA_AI_EGRESS_PROXY_USER"), env("MNEMA_AI_EGRESS_PROXY_PASSWORD"), true));
            roundTrip(properties, SpeechSettings.defaults(), "gemini " + model, "ru", "Здравствуйте, как у вас дела сегодня?", "wav");
            roundTrip(properties, SpeechSettings.defaults(), "gemini " + model, "ja", "今日はいい天気ですね。散歩に行きましょう。", "wav");
        }
    }

    @Test
    void speechKitSpeaksARussianPhrase() {
        String key = env("MNEMA_AI_TTS_API_KEY");
        String folder = env("MNEMA_AI_YANDEX_FOLDER_ID");
        Assumptions.assumeTrue(!key.isBlank() && !folder.isBlank(), "MNEMA_AI_TTS_API_KEY and MNEMA_AI_YANDEX_FOLDER_ID are not set");
        var yandex = new AiProperties.Provider(true, "https://tts.api.cloud.yandex.net", key, "", "", "", AiProperties.EgressMode.DIRECT);
        AiProperties base = SpeechTestSupport.properties(List.of(), List.of("yandex:speechkit-v1"), Map.of("yandex", yandex));
        SpeechSettings defaults = SpeechSettings.defaults();
        SpeechSettings settings = new SpeechSettings(defaults.cacheTtl(), 600, "v1", defaults.style(), defaults.googleFemale(), defaults.googleMale(),
                defaults.yandexFemale(), defaults.yandexMale(), folder, defaults.yandexRubPerMillionChars(), defaults.callTimeout(), defaults.lease());
        roundTrip(base, settings, "speechkit", "ru", "Здравствуйте, как у вас дела сегодня?", "mp3");
    }
}
