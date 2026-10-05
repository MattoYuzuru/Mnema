package app.mnema.learning.ai;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/** The two speech adapters and the Stub on recorded answers served by a local server: request shape, parsing, failures, secrets. */
class SpeechAdaptersTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration BUDGET = Duration.ofSeconds(5);
    private static final String SECRET_TEXT = "СЕКРЕТНЫЙ-ТЕКСТ-КЛИПА-777";

    private final ChatHttp http = new ChatHttp(new AiProperties.Transport(Duration.ofSeconds(2), Duration.ofSeconds(2), 1 << 20, Duration.ofSeconds(2)));
    private final SpeechSettings settings = SpeechSettings.defaults();
    private ImageServer server;

    @BeforeEach
    void start() { server = new ImageServer(); }

    @AfterEach
    void stop() {
        server.close();
        http.close();
    }

    @Test
    void theStyleSentToGeminiRidesOnTheKeyVersionAndAnotherStyleChangesIt() {
        String tag = gemini().versionTag();
        assertThat(tag).matches("\\.[0-9a-f]{8}");
        var other = new SpeechSettings(settings.cacheTtl(), 600, "v1", "Whisper", "Kore", "Charon", "alena", "filipp", "", BigDecimal.ONE,
                Duration.ofSeconds(45), Duration.ofSeconds(75));
        var provider = new AiProperties.Provider(true, server.origin(), SpeechTestSupport.GOOGLE_KEY, "", "", "", AiProperties.EgressMode.DIRECT);
        Function<String, AiProperties.Model> prices = Map.of(SpeechTestSupport.GEMINI.id(), SpeechTestSupport.GEMINI)::get;
        assertThat(new GeminiSpeechSynthesis(provider, http, other, prices, Clock.systemUTC()).versionTag()).isNotEqualTo(tag);
        assertThat(yandex().versionTag()).isEmpty();
        assertThat(SpeechSettings.defaults().lease()).as("shorter than the PT2M step deadline").isEqualTo(Duration.ofSeconds(75));
    }

    private GeminiSpeechSynthesis gemini() {
        var provider = new AiProperties.Provider(true, server.origin(), SpeechTestSupport.GOOGLE_KEY, "", "", "", AiProperties.EgressMode.DIRECT);
        Function<String, AiProperties.Model> prices = Map.of(SpeechTestSupport.GEMINI.id(), SpeechTestSupport.GEMINI)::get;
        return new GeminiSpeechSynthesis(provider, http, settings, prices, Clock.systemUTC());
    }

    private YandexSpeechSynthesis yandex() {
        var provider = new AiProperties.Provider(true, server.origin(), SpeechTestSupport.YANDEX_KEY, "", "", "", AiProperties.EgressMode.DIRECT);
        var configured = new SpeechSettings(settings.cacheTtl(), 600, "v1", settings.style(), "Kore", "Charon", "alena", "filipp", "b1gFOLDER", BigDecimal.valueOf(1342),
                Duration.ofSeconds(45), Duration.ofMinutes(2));
        return new YandexSpeechSynthesis(provider, http, configured, SpeechTestSupport.RATE, Clock.systemUTC());
    }

    private static SpeechSynthesis.Audio ok(AiResult<SpeechSynthesis.Audio> result) {
        assertThat(result).isInstanceOf(AiResult.Ok.class);
        return ((AiResult.Ok<SpeechSynthesis.Audio>) result).value();
    }

    private static AiFailure failure(AiResult<SpeechSynthesis.Audio> result) {
        assertThat(result).isInstanceOf(AiResult.Failed.class);
        return ((AiResult.Failed<SpeechSynthesis.Audio>) result).failure();
    }

    // ------------------------------------------------------------------ Gemini

    @Test
    void geminiPostsTheDocumentedInteractionAndReadsTheWavOfTheModelOutputStep() {
        server.bytes("/v1beta/interactions", "application/json", SpeechTestSupport.resource("gemini-ok.json"));

        SpeechSynthesis.Audio audio = ok(gemini().synthesize("gemini-3.8-flash-tts", "v1", SpeechTestSupport.request("行く", "ja", "male"), BUDGET));

        ImageServer.Recorded call = server.requests.getFirst();
        assertThat(call.method()).isEqualTo("POST");
        // the key is a header, never a URL parameter; the voice is the configured one of the abstract voice
        assertThat(call.headers().get("X-goog-api-key")).containsExactly(SpeechTestSupport.GOOGLE_KEY);
        assertThat(call.path()).isEqualTo("/v1beta/interactions");
        assertThat(call.query()).isNull();
        JsonNode body = JSON.readTree(call.body());
        assertThat(body.path("model").stringValue(null)).isEqualTo("gemini-3.8-flash-tts");
        JsonNode text = body.path("input").get(0).path("content").get(0);
        assertThat(body.path("input").get(0).path("type").stringValue(null)).isEqualTo("user_input");
        assertThat(text.path("type").stringValue(null)).isEqualTo("text");
        assertThat(text.path("text").stringValue(null)).isEqualTo("行く");
        assertThat(text.path("annotations").get(0).path("type").stringValue(null)).isEqualTo("speech_metadata");
        assertThat(text.path("annotations").get(0).path("style").stringValue(null)).isEqualTo("Read clearly for a language learner");
        assertThat(body.path("response_format").path("type").stringValue(null)).isEqualTo("audio");
        assertThat(body.path("response_format").path("mime_type").stringValue(null)).isEqualTo("audio/wav");
        assertThat(body.path("response_format").path("sample_rate").intValue()).isEqualTo(24_000);
        assertThat(body.path("generation_config").path("speech_config").get(0).path("voice").stringValue(null)).isEqualTo("Charon");
        // the answer: the WAV of the fixture, a quarter of a second, billed by the characters sent
        assertThat(audio.bytes()).isEqualTo(SpeechTestSupport.resource("gemini-tone.wav"));
        assertThat(audio.mimeType()).isEqualTo("audio/wav");
        assertThat(audio.durationMs()).isEqualTo(250);
        assertThat(audio.billedCharacters()).isEqualTo(2);
        assertThat(audio.identity()).isEqualTo(new SpeechSynthesis.Identity("google", "gemini-3.8-flash-tts", "v1", "wav", "Charon"));
        assertThat(audio.toString()).doesNotContain("RIFF");
    }

    @Test
    void geminiMapsTheFemaleVoiceAndPricesTheCallFromTextTokensAndAudioTokens() {
        server.bytes("/v1beta/interactions", "application/json", SpeechTestSupport.resource("gemini-ok.json"));
        GeminiSpeechSynthesis adapter = gemini();

        SpeechSynthesis.Audio audio = ok(adapter.synthesize("gemini-3.8-flash-tts", "v1", SpeechTestSupport.request("Привет, как дела у тебя сегодня", "ru", "female"), BUDGET));

        assertThat(JSON.readTree(server.requests.getFirst().body()).path("generation_config").path("speech_config").get(0).path("voice").stringValue(null))
                .isEqualTo("Kore");
        // the text tokens are the characters over four (rounded up) at $0.50 per million; a quarter of a second of audio is 7 tokens (25 a second, rounded
        // up) at $9.00 per million
        String text = "Привет, как дела у тебя сегодня";
        assertThat(audio.billedCharacters()).isEqualTo(text.length());
        assertThat(GeminiSpeechSynthesis.tokensOfText(31)).isEqualTo(8);
        assertThat(GeminiSpeechSynthesis.outputTokens(250)).isEqualTo(7);
        assertThat(adapter.costMicros(audio)).isEqualTo(Math.ceilDiv(GeminiSpeechSynthesis.tokensOfText(text.length()) * 500_000L + 7 * 9_000_000L, 1_000_000L));
        assertThat(adapter.usage(audio).completionTokens()).isEqualTo(7);
        // an unpriced model costs nothing here (the route validation refuses it before it can run)
        assertThat(new GeminiSpeechSynthesis(new AiProperties.Provider(true, server.origin(), SpeechTestSupport.GOOGLE_KEY, "", "", ""), http, settings, name -> null,
                Clock.systemUTC()).costMicros(audio)).isZero();
    }

    @Test
    void geminiFailuresAreTypedAndAnUnusableAnswerIsInvalidOutput() {
        GeminiSpeechSynthesis adapter = gemini();
        var request = SpeechTestSupport.request("行く", "ja", "female");
        server.on("/v1beta/interactions", exchange -> ImageServer.reply(exchange, 429, "text/plain", new byte[0]));
        assertThat(failure(adapter.synthesize("m", "v1", request, BUDGET))).isInstanceOf(AiFailure.RateLimited.class);
        server.on("/v1beta/interactions", exchange -> ImageServer.reply(exchange, 503, "text/plain", new byte[0]));
        assertThat(failure(adapter.synthesize("m", "v1", request, BUDGET))).isInstanceOf(AiFailure.Transient.class);
        server.on("/v1beta/interactions", exchange -> ImageServer.reply(exchange, 403, "text/plain", new byte[0]));
        assertThat(failure(adapter.synthesize("m", "v1", request, BUDGET))).isInstanceOf(AiFailure.NotConfigured.class);
        server.on("/v1beta/interactions", exchange -> ImageServer.reply(exchange, 400, "text/plain", "blocked by safety".getBytes(StandardCharsets.UTF_8)));
        assertThat(failure(adapter.synthesize("m", "v1", request, BUDGET))).isInstanceOf(AiFailure.Refusal.class);
        server.bytes("/v1beta/interactions", "application/json", SpeechTestSupport.resource("gemini-no-audio.json"));
        assertThat(failure(adapter.synthesize("m", "v1", request, BUDGET))).isEqualTo(new AiFailure.InvalidOutput("no_audio"));
        server.bytes("/v1beta/interactions", "application/json", SpeechTestSupport.resource("gemini-not-wav.json"));
        assertThat(failure(adapter.synthesize("m", "v1", request, BUDGET))).isEqualTo(new AiFailure.InvalidOutput("not_wav"));
        server.bytes("/v1beta/interactions", "application/json", "{not json".getBytes(StandardCharsets.UTF_8));
        assertThat(failure(adapter.synthesize("m", "v1", request, BUDGET))).isEqualTo(new AiFailure.InvalidOutput("no_audio"));
        server.bytes("/v1beta/interactions", "application/json", "{\"steps\":[{\"type\":\"model_output\",\"content\":[{\"type\":\"audio\",\"data\":\"***\"}]}]}".getBytes(StandardCharsets.UTF_8));
        assertThat(failure(adapter.synthesize("m", "v1", request, BUDGET))).isEqualTo(new AiFailure.InvalidOutput("no_audio"));
        server.close();
        assertThat(failure(adapter.synthesize("m", "v1", request, BUDGET))).isInstanceOf(AiFailure.Transient.class);
    }

    @Test
    void geminiIsConfiguredOnlyWithAKeyAnEnabledProviderAndATransport() {
        assertThat(gemini().configured()).isTrue();
        assertThat(new GeminiSpeechSynthesis(new AiProperties.Provider(true, "", "", "", "", ""), http, settings, name -> null, Clock.systemUTC()).configured()).isFalse();
        assertThat(new GeminiSpeechSynthesis(new AiProperties.Provider(false, "", "k", "", "", ""), http, settings, name -> null, Clock.systemUTC()).configured()).isFalse();
        // a proxied provider without an active proxy has no transport
        assertThat(new GeminiSpeechSynthesis(new AiProperties.Provider(true, "", "k", "", "", ""), null, settings, name -> null, Clock.systemUTC()).configured()).isFalse();
        assertThat(gemini().supports("ja")).isTrue();
        assertThat(gemini().format()).isEqualTo("wav");
        assertThat(gemini().toString()).doesNotContain(SpeechTestSupport.GOOGLE_KEY);
    }

    // ------------------------------------------------------------------ Yandex

    @Test
    void yandexPostsTheFormWithTheApiKeyHeaderAndReturnsTheMp3() {
        server.bytes("/speech/v1/tts:synthesize", "audio/mpeg", SpeechTestSupport.resource("yandex-tone.mp3"));
        YandexSpeechSynthesis adapter = yandex();

        SpeechSynthesis.Audio audio = ok(adapter.synthesize("speechkit-v1", "v1", SpeechTestSupport.request("Привет & пока", "ru", "male"), BUDGET));

        ImageServer.Recorded call = server.requests.getFirst();
        assertThat(call.headers().get("Authorization")).containsExactly("Api-Key " + SpeechTestSupport.YANDEX_KEY);
        assertThat(call.headers().get("Content-type")).containsExactly("application/x-www-form-urlencoded");
        assertThat(call.body()).isEqualTo("text=%D0%9F%D1%80%D0%B8%D0%B2%D0%B5%D1%82+%26+%D0%BF%D0%BE%D0%BA%D0%B0&lang=ru-RU&voice=filipp&format=mp3&folderId=b1gFOLDER");
        assertThat(audio.bytes()).isEqualTo(SpeechTestSupport.resource("yandex-tone.mp3"));
        assertThat(audio.mimeType()).isEqualTo("audio/mpeg");
        assertThat(audio.durationMs()).isBetween(400L, 600L);
        assertThat(audio.identity()).isEqualTo(new SpeechSynthesis.Identity("yandex", "speechkit-v1", "v1", "mp3", "filipp"));
        // 13 characters at 1342 roubles per million, in dollars at 85 roubles: 13 * 1342 / 85 micro-dollars, rounded up
        assertThat(adapter.costMicros(audio)).isEqualTo(206);
        assertThat(adapter.voiceName("female")).isEqualTo("alena");
    }

    @Test
    void yandexSpeaksOnlyRussianAndNeedsAFolderAndFailsTypedOtherwise() {
        YandexSpeechSynthesis adapter = yandex();
        assertThat(adapter.supports("ru")).isTrue();
        assertThat(adapter.supports("ru-RU")).isTrue();
        assertThat(adapter.supports("ja")).isFalse();
        assertThat(failure(adapter.synthesize("m", "v1", SpeechTestSupport.request("行く", "ja", "female"), BUDGET))).isEqualTo(new AiFailure.Refusal("lang_not_supported"));
        assertThat(server.requests).isEmpty();
        // no folder, no key
        var provider = new AiProperties.Provider(true, server.origin(), SpeechTestSupport.YANDEX_KEY, "", "", "");
        assertThat(new YandexSpeechSynthesis(provider, http, settings, SpeechTestSupport.RATE, Clock.systemUTC()).configured()).isFalse();
        assertThat(adapter.configured()).isTrue();

        var request = SpeechTestSupport.request("привет", "ru", "female");
        server.on("/speech/v1/tts:synthesize", exchange -> ImageServer.reply(exchange, 429, "text/plain", new byte[0]));
        assertThat(failure(adapter.synthesize("m", "v1", request, BUDGET))).isInstanceOf(AiFailure.RateLimited.class);
        server.on("/speech/v1/tts:synthesize", exchange -> ImageServer.reply(exchange, 401, "text/plain", new byte[0]));
        assertThat(failure(adapter.synthesize("m", "v1", request, BUDGET))).isInstanceOf(AiFailure.NotConfigured.class);
        server.on("/speech/v1/tts:synthesize", exchange -> ImageServer.reply(exchange, 500, "text/plain", new byte[0]));
        assertThat(failure(adapter.synthesize("m", "v1", request, BUDGET))).isInstanceOf(AiFailure.Transient.class);
        server.bytes("/speech/v1/tts:synthesize", "audio/mpeg", "not an mp3 at all".getBytes(StandardCharsets.UTF_8));
        assertThat(failure(adapter.synthesize("m", "v1", request, BUDGET))).isEqualTo(new AiFailure.InvalidOutput("not_mp3"));
    }

    @Test
    void anMp3DurationIsEstimatedFromItsFirstFrameAfterAnOptionalId3Tag() {
        byte[] frame = {(byte) 0xff, (byte) 0xfb, (byte) 0x90, 0x00};
        byte[] payload = new byte[16_000];
        System.arraycopy(frame, 0, payload, 0, 4);
        // 128 kbps: 16 000 bytes are one second
        assertThat(YandexSpeechSynthesis.mp3DurationMs(payload)).isEqualTo(1_000);
        byte[] tagged = new byte[10 + 3 + 16_000];
        tagged[0] = 'I';
        tagged[1] = 'D';
        tagged[2] = '3';
        tagged[9] = 3;
        System.arraycopy(frame, 0, tagged, 13, 4);
        assertThat(YandexSpeechSynthesis.mp3DurationMs(tagged)).isEqualTo(1_000);
        assertThat(YandexSpeechSynthesis.mp3DurationMs(new byte[] {1, 2, 3, 4, 5})).isEqualTo(-1);
        assertThat(YandexSpeechSynthesis.mp3DurationMs(new byte[0])).isEqualTo(-1);
    }

    // -------------------------------------------------------------------- logs

    @Test
    void noOutcomeLogsAKeyOrTheTextOfTheClip() {
        var appender = new ListAppender<ILoggingEvent>();
        var root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        Level previous = root.getLevel();
        root.setLevel(Level.DEBUG);
        appender.start();
        root.addAppender(appender);
        try {
            server.bytes("/v1beta/interactions", "application/json", SpeechTestSupport.resource("gemini-not-wav.json"));
            gemini().synthesize("m", "v1", SpeechTestSupport.request(SECRET_TEXT, "ru", "female"), BUDGET);
            server.on("/v1beta/interactions", exchange -> ImageServer.reply(exchange, 400, "text/plain", SECRET_TEXT.getBytes(StandardCharsets.UTF_8)));
            gemini().synthesize("m", "v1", SpeechTestSupport.request(SECRET_TEXT, "ru", "female"), BUDGET);
            server.on("/speech/v1/tts:synthesize", exchange -> ImageServer.reply(exchange, 500, "text/plain", new byte[0]));
            yandex().synthesize("m", "v1", SpeechTestSupport.request(SECRET_TEXT, "ru", "female"), BUDGET);
        } finally {
            root.detachAppender(appender);
            root.setLevel(previous);
        }
        for (ILoggingEvent event : appender.list) {
            assertThat(event.getFormattedMessage()).doesNotContain(SpeechTestSupport.GOOGLE_KEY, SpeechTestSupport.YANDEX_KEY, SECRET_TEXT);
        }
        assertThat(SpeechTestSupport.request(SECRET_TEXT, "ru", "female").toString()).doesNotContain(SECRET_TEXT);
    }

    // -------------------------------------------------------------------- Stub

    @Test
    void theStubIsDeterministicByTextAndVoiceAndItsMarkersSimulateFailures() {
        StubSpeechSynthesis stub = new StubSpeechSynthesis();
        SpeechSynthesis.Audio first = ok(stub.synthesize(SpeechTestSupport.request("行く", "ja", "female")));
        SpeechSynthesis.Audio again = ok(stub.synthesize(SpeechTestSupport.request("行く", "ja", "female")));
        SpeechSynthesis.Audio male = ok(stub.synthesize(SpeechTestSupport.request("行く", "ja", "male")));
        assertThat(again.bytes()).isEqualTo(first.bytes());
        assertThat(male.bytes()).isNotEqualTo(first.bytes());
        Wav.Info info = Wav.parse(first.bytes());
        assertThat(info).isNotNull();
        assertThat(info.durationMs()).isEqualTo(first.durationMs()).isGreaterThanOrEqualTo(300);
        // the length follows the text, at most six seconds
        assertThat(ok(stub.synthesize(SpeechTestSupport.request("я".repeat(600), "ru", "female"))).durationMs()).isEqualTo(6_000);
        assertThat(failure(stub.synthesize(SpeechTestSupport.request("x [[stub:tts-down]]", "en", "female")))).isInstanceOf(AiFailure.Transient.class);
        SpeechSynthesis.Audio garbage = ok(stub.synthesize(SpeechTestSupport.request("x [[stub:tts-garbage]]", "en", "female")));
        assertThat(Wav.parse(garbage.bytes())).isNull();
        assertThat(stub.identity("ja", "male")).contains(new SpeechSynthesis.Identity("stub", "stub-tts", "1", "wav", "male"));
        assertThat(stub.configured()).isTrue();
    }

    @Test
    void theWavReaderAcceptsOnlyPcmS16AndRefusesTruncatedOrOddFiles() {
        byte[] wav = SpeechTestSupport.resource("gemini-tone.wav");
        assertThat(Wav.parse(wav).sampleRate()).isEqualTo(24_000);
        assertThat(Wav.parse(wav).channels()).isEqualTo(1);
        assertThat(Wav.parse(java.util.Arrays.copyOf(wav, wav.length - 1))).isNull();
        assertThat(Wav.parse(java.util.Arrays.copyOf(wav, 20))).isNull();
        byte[] floating = wav.clone();
        floating[20] = 3;
        assertThat(Wav.parse(floating)).isNull();
        byte[] eight = wav.clone();
        eight[34] = 8;
        assertThat(Wav.parse(eight)).isNull();
        byte[] noRiff = wav.clone();
        noRiff[0] = 'X';
        assertThat(Wav.parse(noRiff)).isNull();
    }

    @Test
    void settingsValidateTheirValuesWithoutPrintingThem() {
        assertThat(SpeechSettings.defaults().maxText()).isEqualTo(600);
        for (Runnable bad : new Runnable[] {
                () -> new SpeechSettings(Duration.ofHours(1), 600, "v1", "s", "Kore", "Charon", "alena", "filipp", "", BigDecimal.ONE, Duration.ofSeconds(45), Duration.ofMinutes(2)),
                () -> new SpeechSettings(Duration.ofDays(1), 601, "v1", "s", "Kore", "Charon", "alena", "filipp", "", BigDecimal.ONE, Duration.ofSeconds(45), Duration.ofMinutes(2)),
                () -> new SpeechSettings(Duration.ofDays(1), 600, "V 1", "s", "Kore", "Charon", "alena", "filipp", "", BigDecimal.ONE, Duration.ofSeconds(45), Duration.ofMinutes(2)),
                () -> new SpeechSettings(Duration.ofDays(1), 600, "v1", " ", "Kore", "Charon", "alena", "filipp", "", BigDecimal.ONE, Duration.ofSeconds(45), Duration.ofMinutes(2)),
                () -> new SpeechSettings(Duration.ofDays(1), 600, "v1", "s", "Ko re", "Charon", "alena", "filipp", "", BigDecimal.ONE, Duration.ofSeconds(45), Duration.ofMinutes(2)),
                () -> new SpeechSettings(Duration.ofDays(1), 600, "v1", "s", "Kore", "Charon", "alena", "filipp", "b 1", BigDecimal.ONE, Duration.ofSeconds(45), Duration.ofMinutes(2)),
                () -> new SpeechSettings(Duration.ofDays(1), 600, "v1", "s", "Kore", "Charon", "alena", "filipp", "", BigDecimal.valueOf(-1), Duration.ofSeconds(45), Duration.ofMinutes(2)),
                () -> new SpeechSettings(Duration.ofDays(1), 600, "v1", "s", "Kore", "Charon", "alena", "filipp", "", BigDecimal.ONE, Duration.ZERO, Duration.ofMinutes(2)),
                () -> new SpeechSettings(Duration.ofDays(1), 600, "v1", "s", "Kore", "Charon", "alena", "filipp", "", BigDecimal.ONE, Duration.ofSeconds(45), Duration.ofSeconds(1))}) {
            org.assertj.core.api.Assertions.assertThatThrownBy(bad::run).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
