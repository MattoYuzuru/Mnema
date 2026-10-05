package app.mnema.learning.ai;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Builders for the speech tests: configuration, recorded fixtures and a recording journal. */
final class SpeechTestSupport {
    static final AiProperties.Model GEMINI = new AiProperties.Model("google", "gemini-3.8-flash-tts", 500_000, 500_000, 9_000_000);
    static final AiProperties.Model GEMINI_LITE = new AiProperties.Model("google", "gemini-3.8-flash-lite-tts", 500_000, 500_000, 6_000_000);
    static final String GOOGLE_KEY = "google-SECRET-KEY-98765";
    static final String YANDEX_KEY = "yandex-SECRET-KEY-43210";
    static final BigDecimal RATE = BigDecimal.valueOf(85);

    private SpeechTestSupport() { }

    static AiProperties properties(List<String> tts, List<String> ttsRu, Map<String, AiProperties.Provider> providers) {
        AiProperties base = AiTestSupport.properties("", AiTestSupport.routes(List.of(), List.of(), List.of()), providers);
        return new AiProperties(base.provider(), new AiProperties.Routes(List.of(), List.of(), List.of(), Duration.ofSeconds(8), List.of(), List.of(), tts, ttsRu),
                providers, List.of(AiTestSupport.FLASH, GEMINI, GEMINI_LITE), base.transport(), base.retry(), base.breaker(), base.permits(), base.budget(), base.userKey(),
                base.prompt());
    }

    static byte[] resource(String name) {
        try (InputStream in = SpeechTestSupport.class.getResourceAsStream("/ai/speech/" + name)) {
            return in.readAllBytes();
        } catch (IOException | NullPointerException failure) {
            throw new IllegalStateException("Missing fixture " + name);
        }
    }

    static SpeechSynthesis.Request request(String text, String lang, String voice) {
        return new SpeechSynthesis.Request(text, lang, voice, 0, UUID.randomUUID(), 1);
    }

    /** A journal that remembers what it was asked and never touches a database. */
    static final class RecordingJournal implements CallJournal {
        final List<Intent> intents = new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<Outcome> outcomes = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public UUID begin(Intent intent) {
            intents.add(intent);
            return UUID.randomUUID();
        }

        @Override
        public void finish(UUID callId, Outcome outcome) { outcomes.add(outcome); }
    }
}
