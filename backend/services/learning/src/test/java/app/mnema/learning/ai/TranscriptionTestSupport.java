package app.mnema.learning.ai;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Builders for the speech-to-text tests: requests, configuration and routes. */
final class TranscriptionTestSupport {
    static final String GOOGLE_KEY = "google-SECRET-KEY-98765";
    static final String SELFHOST_KEY = "selfhost-SECRET-KEY-13579";
    static final AiProperties.Model TRANSCRIBE = new AiProperties.Model("google", "gemini-3.5-transcribe", 2_000_000, 2_000_000, 12_000_000);
    static final AiProperties.Model FLASH_LITE = new AiProperties.Model("google", "gemini-3.5-flash-lite", 300_000, 300_000, 2_500_000);
    static final byte[] AUDIO = "RECORDED-AUDIO-BYTES".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    private TranscriptionTestSupport() { }

    static Transcription.Request request(String mime, String lang, List<String> hints) {
        return new Transcription.Request(AUDIO, mime, lang, hints, null, Duration.ofSeconds(10), 3_040, Transcription.Purpose.COMPOSER, null, Transcription.Region.ABROAD);
    }

    static Transcription.Request request(String lang) { return request("audio/ogg", lang, List.of()); }

    /** Properties with the two speech-to-text routes and the given providers; every other route is empty. */
    static AiProperties properties(List<String> stt, List<String> sttRu, Map<String, AiProperties.Provider> providers) {
        AiProperties base = AiTestSupport.properties("", AiTestSupport.routes(List.of(), List.of(), List.of()), providers);
        return new AiProperties(base.provider(), new AiProperties.Routes(List.of(), List.of(), List.of(), Duration.ofSeconds(8), List.of(), List.of(), List.of(),
                List.of(), stt, sttRu), providers, List.of(AiTestSupport.FLASH, TRANSCRIBE, FLASH_LITE), base.transport(), base.retry(), base.breaker(),
                base.permits(), base.budget(), base.userKey(), base.prompt());
    }

    static AiProperties.Provider google(String origin, boolean enabled, String key) {
        return new AiProperties.Provider(enabled, origin, key, "", "", "", AiProperties.EgressMode.DIRECT);
    }

    static AiProperties.Provider selfhost(String origin, String key) {
        return new AiProperties.Provider(true, origin, key, "", "", "", AiProperties.EgressMode.DIRECT);
    }
}
