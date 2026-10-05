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

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/** The two speech-to-text adapters on recorded answers served by a local server: request shape, parsing, failures, garbled thresholds, secrets. */
class TranscriptionAdaptersTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration BUDGET = Duration.ofSeconds(5);
    private static final String SECRET_HINT = "СЕКРЕТНЫЙ-ТЕРМИН-777";

    private final ChatHttp http = new ChatHttp(new AiProperties.Transport(Duration.ofSeconds(2), Duration.ofSeconds(2), 1 << 20, Duration.ofSeconds(2)));
    private final SttSettings settings = SttSettings.defaults();
    private ImageServer server;

    @BeforeEach
    void start() { server = new ImageServer(); }

    @AfterEach
    void stop() {
        server.close();
        http.close();
    }

    private GeminiTranscription gemini() {
        Function<String, AiProperties.Model> prices = Map.of(TranscriptionTestSupport.TRANSCRIBE.id(), TranscriptionTestSupport.TRANSCRIBE,
                TranscriptionTestSupport.FLASH_LITE.id(), TranscriptionTestSupport.FLASH_LITE)::get;
        return new GeminiTranscription(TranscriptionTestSupport.google(server.origin(), true, TranscriptionTestSupport.GOOGLE_KEY), http, settings, prices, Clock.systemUTC());
    }

    private SelfHostTranscription selfhost(String key) {
        return new SelfHostTranscription(TranscriptionTestSupport.selfhost(server.origin(), key), http, settings, Clock.systemUTC());
    }

    private static TranscriptionAdapter.Answer ok(AiResult<TranscriptionAdapter.Answer> result) {
        assertThat(result).isInstanceOf(AiResult.Ok.class);
        return ((AiResult.Ok<TranscriptionAdapter.Answer>) result).value();
    }

    private static AiFailure failure(AiResult<TranscriptionAdapter.Answer> result) {
        assertThat(result).isInstanceOf(AiResult.Failed.class);
        return ((AiResult.Failed<TranscriptionAdapter.Answer>) result).failure();
    }

    // ------------------------------------------------------------------ Gemini: the dedicated model

    @Test
    void theDedicatedModelGetsTheAudioInlineAndTheLanguageAndTheTermsInItsTranscriptionConfig() {
        server.bytes("/v1beta/interactions", "application/json", SpeechTestSupport.resource("stt-gemini-transcribe.json"));

        TranscriptionAdapter.Answer answer = ok(gemini().transcribe("gemini-3.5-transcribe",
                TranscriptionTestSupport.request("audio/mp4", "ru-RU", List.of("Токио", "рамен")), BUDGET));

        ImageServer.Recorded call = server.requests.getFirst();
        assertThat(call.method()).isEqualTo("POST");
        assertThat(call.path()).isEqualTo("/v1beta/interactions");
        assertThat(call.query()).isNull();
        // the key is a header, never a URL parameter
        assertThat(call.headers().get("X-goog-api-key")).containsExactly(TranscriptionTestSupport.GOOGLE_KEY);
        JsonNode body = JSON.readTree(call.body());
        assertThat(body.path("model").stringValue(null)).isEqualTo("gemini-3.5-transcribe");
        // the audio alone: no prompt
        assertThat(body.path("input")).hasSize(1);
        JsonNode audio = body.path("input").get(0);
        assertThat(audio.path("type").stringValue(null)).isEqualTo("audio");
        assertThat(audio.path("mime_type").stringValue(null)).isEqualTo("audio/mp4");
        assertThat(Base64.getDecoder().decode(audio.path("data").stringValue(null))).isEqualTo(TranscriptionTestSupport.AUDIO);
        JsonNode config = body.path("generation_config").path("transcription_config");
        assertThat(config.path("language_codes").get(0).stringValue(null)).isEqualTo("ru-RU");
        assertThat(config.path("custom_vocabulary").get(0).stringValue(null)).isEqualTo("Токио");
        assertThat(config.path("custom_vocabulary").get(1).stringValue(null)).isEqualTo("рамен");
        // the answer: the model_output text, 76 audio tokens are 3.04 s at 25 a second, rounded up; never garbled (Gemini gives no confidence)
        assertThat(answer.transcript().text()).isEqualTo("Tomorrow I will travel to Tokyo to meet my friends.");
        assertThat(answer.transcript().seconds()).isEqualTo(4);
        assertThat(answer.transcript().garbled()).isFalse();
        // billed: 77 input tokens at $2.00 and the 11 candidate tokens (the total says 0) at $12.00 per million, rounded up
        assertThat(answer.usage()).isEqualTo(new Usage(77, 0, 77, 11));
        assertThat(gemini().costMicros("gemini-3.5-transcribe", answer)).isEqualTo(Math.ceilDiv(77 * 2_000_000L + 11 * 12_000_000L, 1_000_000L));
    }

    @Test
    void withoutAHintOrTermsTheDedicatedModelGetsNoConfigAndAnUnlistedLanguageIsDetected() {
        server.bytes("/v1beta/interactions", "application/json", SpeechTestSupport.resource("stt-gemini-transcribe.json"));
        ok(gemini().transcribe("gemini-3.5-transcribe", TranscriptionTestSupport.request("audio/webm", null, List.of()), BUDGET));
        ok(gemini().transcribe("gemini-3.5-transcribe", TranscriptionTestSupport.request("audio/webm", "xx", List.of()), BUDGET));

        assertThat(JSON.readTree(server.requests.get(0).body()).has("generation_config")).isFalse();
        assertThat(JSON.readTree(server.requests.get(1).body()).has("generation_config")).isFalse();
        assertThat(GeminiTranscription.tag("ru")).isEqualTo("ru-RU");
        assertThat(GeminiTranscription.tag("ja-JP")).isEqualTo("ja-JP");
        assertThat(GeminiTranscription.tag("zh-Hans")).isEqualTo("cmn-Hans-CN");
        assertThat(GeminiTranscription.tag("xx")).isNull();
        assertThat(GeminiTranscription.tag(null)).isNull();
    }

    // ------------------------------------------------------------------ Gemini: a general model

    @Test
    void aGeneralModelIsToldToTranscribeVerbatimBeforeTheAudioAndPaidForItsThinking() {
        server.bytes("/v1beta/interactions", "application/json", SpeechTestSupport.resource("stt-gemini-flash.json"));

        TranscriptionAdapter.Answer answer = ok(gemini().transcribe("gemini-3.5-flash-lite",
                TranscriptionTestSupport.request("audio/ogg", "en", List.of("Tokyo")), BUDGET));

        JsonNode body = JSON.readTree(server.requests.getFirst().body());
        assertThat(body.path("input")).hasSize(2);
        assertThat(body.path("input").get(0).path("type").stringValue(null)).isEqualTo("text");
        String prompt = body.path("input").get(0).path("text").stringValue(null);
        assertThat(prompt).startsWith(settings.geminiPrompt()).contains("en-US").contains("Tokyo");
        assertThat(body.path("input").get(1).path("type").stringValue(null)).isEqualTo("audio");
        assertThat(body.path("input").get(1).path("mime_type").stringValue(null)).isEqualTo("audio/ogg");
        assertThat(body.has("generation_config")).isFalse();
        // the text of the model_output step only; the thought step is not the transcript
        assertThat(answer.transcript().text()).isEqualTo("Tomorrow I will travel to Tokyo to meet my friends.");
        // 12 output and 60 thinking tokens are all output
        assertThat(answer.usage()).isEqualTo(new Usage(81, 0, 81, 72));
        assertThat(gemini().costMicros("gemini-3.5-flash-lite", answer)).isEqualTo(Math.ceilDiv(81 * 300_000L + 72 * 2_500_000L, 1_000_000L));
        assertThat(gemini().costMicros("not-priced", answer)).isZero();
    }

    @Test
    void noSpeechIsAnEmptyTranscriptAndAClipOverASixtySecondsIsRefused() {
        server.bytes("/v1beta/interactions", "application/json", SpeechTestSupport.resource("stt-gemini-silence.json"));
        TranscriptionAdapter.Answer silence = ok(gemini().transcribe("gemini-3.5-transcribe", TranscriptionTestSupport.request("en"), BUDGET));
        assertThat(silence.transcript().text()).isEmpty();
        // 38 audio tokens are 1.52 s
        assertThat(silence.transcript().seconds()).isEqualTo(2);

        // 1700 audio tokens are 68 s: the provider measured more than a clip may be
        String tooLong = "{\"status\":\"completed\",\"usage\":{\"total_input_tokens\":1700,\"input_tokens_by_modality\":[{\"modality\":\"audio\",\"tokens\":1700}]},"
                + "\"steps\":[{\"type\":\"model_output\",\"content\":[{\"type\":\"text\",\"text\":\"x\"}]}]}";
        server.bytes("/v1beta/interactions", "application/json", tooLong.getBytes(StandardCharsets.UTF_8));
        assertThat(failure(gemini().transcribe("gemini-3.5-transcribe", TranscriptionTestSupport.request("en"), BUDGET))).isEqualTo(new AiFailure.Refusal("too_long"));

        // an answer without usage is metered by the recorder's duration
        server.bytes("/v1beta/interactions", "application/json",
                "{\"status\":\"completed\",\"steps\":[{\"type\":\"model_output\",\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]}]}".getBytes(StandardCharsets.UTF_8));
        assertThat(ok(gemini().transcribe("gemini-3.5-transcribe", TranscriptionTestSupport.request("en"), BUDGET)).transcript().seconds()).isEqualTo(4);
    }

    @Test
    void geminiFailuresAreTypedAndAClipItCannotDecodeMovesTheRouteOn() {
        GeminiTranscription adapter = gemini();
        var request = TranscriptionTestSupport.request("ru");
        server.on("/v1beta/interactions", exchange -> ImageServer.reply(exchange, 429, "text/plain", new byte[0]));
        assertThat(failure(adapter.transcribe("m", request, BUDGET))).isInstanceOf(AiFailure.RateLimited.class);
        server.on("/v1beta/interactions", exchange -> ImageServer.reply(exchange, 503, "text/plain", new byte[0]));
        assertThat(failure(adapter.transcribe("m", request, BUDGET))).isInstanceOf(AiFailure.Transient.class);
        server.on("/v1beta/interactions", exchange -> ImageServer.reply(exchange, 403, "text/plain", new byte[0]));
        assertThat(failure(adapter.transcribe("m", request, BUDGET))).isInstanceOf(AiFailure.NotConfigured.class);
        server.on("/v1beta/interactions", exchange -> ImageServer.reply(exchange, 404, "text/plain", new byte[0]));
        assertThat(failure(adapter.transcribe("m", request, BUDGET))).isInstanceOf(AiFailure.Refusal.class);
        server.on("/v1beta/interactions", exchange -> ImageServer.reply(exchange, 400, "text/plain", SECRET_HINT.getBytes(StandardCharsets.UTF_8)));
        assertThat(failure(adapter.transcribe("m", request, BUDGET))).isEqualTo(new AiFailure.InvalidOutput("unsupported_audio"));
        server.bytes("/v1beta/interactions", "application/json", "{not json".getBytes(StandardCharsets.UTF_8));
        assertThat(failure(adapter.transcribe("m", request, BUDGET))).isEqualTo(new AiFailure.InvalidOutput("not_json"));
        server.bytes("/v1beta/interactions", "application/json", "{\"status\":\"failed\"}".getBytes(StandardCharsets.UTF_8));
        assertThat(failure(adapter.transcribe("m", request, BUDGET))).isEqualTo(new AiFailure.InvalidOutput("not_completed"));
        server.close();
        assertThat(failure(adapter.transcribe("m", request, BUDGET))).isInstanceOf(AiFailure.Transient.class);
    }

    @Test
    void geminiIsConfiguredOnlyWithAKeyAnEnabledProviderAndATransportAndItsRegionIsAbroad() {
        assertThat(gemini().configured()).isTrue();
        assertThat(gemini().region()).isEqualTo(Transcription.Region.ABROAD);
        var noKey = new GeminiTranscription(TranscriptionTestSupport.google("", true, ""), http, settings, name -> null, Clock.systemUTC());
        assertThat(noKey.configured()).isFalse();
        var off = new GeminiTranscription(TranscriptionTestSupport.google("", false, "k"), http, settings, name -> null, Clock.systemUTC());
        assertThat(off.configured()).isFalse();
        // a proxied provider without an active proxy has no transport
        var noTransport = new GeminiTranscription(TranscriptionTestSupport.google("", true, "k"), null, settings, name -> null, Clock.systemUTC());
        assertThat(noTransport.configured()).isFalse();
        assertThat(gemini().toString()).doesNotContain(TranscriptionTestSupport.GOOGLE_KEY);
    }

    // ------------------------------------------------------------------ self-hosted, OpenAI-compatible

    @Test
    void theSelfHostedContainerGetsTheDocumentedMultipartFormAndAnAuthorizationHeader() {
        server.bytes("/v1/audio/transcriptions", "application/json", SpeechTestSupport.resource("stt-openai-verbose.json"));

        TranscriptionAdapter.Answer answer = ok(selfhost(TranscriptionTestSupport.SELFHOST_KEY).transcribe("gigaam-v3",
                TranscriptionTestSupport.request("audio/mp4", "ru-RU", List.of("Токио", "рамен")), BUDGET));

        ImageServer.Recorded call = server.requests.getFirst();
        assertThat(call.method()).isEqualTo("POST");
        assertThat(call.path()).isEqualTo("/v1/audio/transcriptions");
        assertThat(call.headers().get("Authorization")).containsExactly("Bearer " + TranscriptionTestSupport.SELFHOST_KEY);
        String type = call.headers().get("Content-type").getFirst();
        assertThat(type).startsWith("multipart/form-data; boundary=");
        String boundary = type.substring(type.indexOf("boundary=") + 9);
        String form = call.body();
        assertThat(form).contains("--" + boundary + "--");
        assertThat(form).contains("name=\"file\"; filename=\"clip.m4a\"\r\nContent-Type: audio/mp4\r\n\r\nRECORDED-AUDIO-BYTES\r\n");
        assertThat(field(form, "model")).isEqualTo("gigaam-v3");
        assertThat(field(form, "response_format")).isEqualTo("verbose_json");
        assertThat(field(form, "temperature")).isEqualTo("0");
        // the primary subtag of the hint, and the terms as one line
        assertThat(field(form, "language")).isEqualTo("ru");
        assertThat(field(form, "prompt")).isEqualTo("Токио, рамен");
        // the answer: the text, the measured duration rounded up, the detected language as a code; not garbled (mean -0.27, no speech probability 0.02)
        assertThat(answer.transcript()).isEqualTo(new Transcription.Transcript("Завтра я поеду в Токио, чтобы встретиться с друзьями.", 7, "ru", false));
        assertThat(answer.usage()).isEqualTo(Usage.ZERO);
        assertThat(selfhost("").costMicros("gigaam-v3", answer)).isZero();
    }

    private static String field(String form, String name) {
        String marker = "name=\"" + name + "\"\r\n\r\n";
        int start = form.indexOf(marker);
        assertThat(start).as("field " + name).isNotNegative();
        return form.substring(start + marker.length(), form.indexOf("\r\n", start + marker.length()));
    }

    @Test
    void withoutAKeyALanguageOrTermsTheFormCarriesNoAuthorizationLanguageOrPrompt() {
        server.bytes("/v1/audio/transcriptions", "application/json", SpeechTestSupport.resource("stt-openai-plain.json"));

        TranscriptionAdapter.Answer answer = ok(selfhost("").transcribe("qwen3-asr-0.6b", TranscriptionTestSupport.request("audio/webm", null, List.of()), BUDGET));

        ImageServer.Recorded call = server.requests.getFirst();
        assertThat(call.headers()).doesNotContainKey("Authorization");
        assertThat(call.body()).doesNotContain("name=\"language\"").doesNotContain("name=\"prompt\"").contains("filename=\"clip.webm\"");
        // no duration: the recorder's, rounded up
        assertThat(answer.transcript()).isEqualTo(new Transcription.Transcript("Hello there", 4, "en", false));
    }

    @Test
    void aLowMeanLogprobOrAStrongNoSpeechProbabilityMakesAnAnswerGarbledButOnlyWhenThereIsText() {
        server.bytes("/v1/audio/transcriptions", "application/json", SpeechTestSupport.resource("stt-openai-unsure.json"));
        // the duration-weighted mean of -0.3 over 1 s and -1.6 over 3 s is -1.275, below the -1.0 threshold
        assertThat(ok(selfhost("").transcribe("m", TranscriptionTestSupport.request("ru"), BUDGET)).transcript().garbled()).isTrue();

        server.bytes("/v1/audio/transcriptions", "application/json", SpeechTestSupport.resource("stt-openai-noise.json"));
        assertThat(ok(selfhost("").transcribe("m", TranscriptionTestSupport.request("en"), BUDGET)).transcript().garbled()).isTrue();

        server.bytes("/v1/audio/transcriptions", "application/json",
                "{\"text\":\"\",\"duration\":2.0,\"segments\":[{\"start\":0,\"end\":2,\"avg_logprob\":-3,\"no_speech_prob\":0.99}]}".getBytes(StandardCharsets.UTF_8));
        TranscriptionAdapter.Answer silence = ok(selfhost("").transcribe("m", TranscriptionTestSupport.request("en"), BUDGET));
        assertThat(silence.transcript().text()).isEmpty();
        assertThat(silence.transcript().garbled()).isFalse();

        // the thresholds are configuration
        var lenient = new SttSettings(Duration.ofSeconds(25), -5.0, 1.0, settings.geminiPrompt());
        server.bytes("/v1/audio/transcriptions", "application/json", SpeechTestSupport.resource("stt-openai-unsure.json"));
        var tolerant = new SelfHostTranscription(TranscriptionTestSupport.selfhost(server.origin(), ""), http, lenient, Clock.systemUTC());
        assertThat(ok(tolerant.transcribe("m", TranscriptionTestSupport.request("ru"), BUDGET)).transcript().garbled()).isFalse();
    }

    @Test
    void theSelfHostedFailuresAreTypedAndAClipItCannotDecodeOrThatIsTooLongIsTold() {
        SelfHostTranscription adapter = selfhost("");
        var request = TranscriptionTestSupport.request("ru");
        for (int status : new int[] {400, 415, 422}) {
            server.on("/v1/audio/transcriptions", exchange -> ImageServer.reply(exchange, status, "text/plain", SECRET_HINT.getBytes(StandardCharsets.UTF_8)));
            assertThat(failure(adapter.transcribe("m", request, BUDGET))).isEqualTo(new AiFailure.InvalidOutput("unsupported_audio"));
        }
        server.on("/v1/audio/transcriptions", exchange -> ImageServer.reply(exchange, 429, "text/plain", new byte[0]));
        assertThat(failure(adapter.transcribe("m", request, BUDGET))).isInstanceOf(AiFailure.RateLimited.class);
        server.on("/v1/audio/transcriptions", exchange -> ImageServer.reply(exchange, 503, "text/plain", new byte[0]));
        assertThat(failure(adapter.transcribe("m", request, BUDGET))).isInstanceOf(AiFailure.Transient.class);
        server.on("/v1/audio/transcriptions", exchange -> ImageServer.reply(exchange, 401, "text/plain", new byte[0]));
        assertThat(failure(adapter.transcribe("m", request, BUDGET))).isInstanceOf(AiFailure.NotConfigured.class);
        server.bytes("/v1/audio/transcriptions", "application/json", "<html>".getBytes(StandardCharsets.UTF_8));
        assertThat(failure(adapter.transcribe("m", request, BUDGET))).isEqualTo(new AiFailure.InvalidOutput("not_json"));
        server.bytes("/v1/audio/transcriptions", "application/json", "{\"language\":\"en\"}".getBytes(StandardCharsets.UTF_8));
        assertThat(failure(adapter.transcribe("m", request, BUDGET))).isEqualTo(new AiFailure.InvalidOutput("no_text"));
        server.bytes("/v1/audio/transcriptions", "application/json", SpeechTestSupport.resource("stt-openai-long.json"));
        assertThat(failure(adapter.transcribe("m", request, BUDGET))).isEqualTo(new AiFailure.Refusal("too_long"));
        server.close();
        assertThat(failure(adapter.transcribe("m", request, BUDGET))).isInstanceOf(AiFailure.Transient.class);
    }

    @Test
    void theSelfHostedProviderIsConfiguredWithABaseUrlAndItsRegionIsRussia() {
        assertThat(selfhost("").configured()).isTrue();
        assertThat(selfhost("").region()).isEqualTo(Transcription.Region.RU);
        var none = new SelfHostTranscription(TranscriptionTestSupport.selfhost("", ""), http, settings, Clock.systemUTC());
        assertThat(none.configured()).isFalse();
        assertThat(SelfHostTranscription.language("Russian", null)).isEqualTo("ru");
        assertThat(SelfHostTranscription.language("kor", "en")).isEqualTo("kor");
        assertThat(SelfHostTranscription.language("", "ru-RU")).isEqualTo("ru-RU");
        assertThat(SelfHostTranscription.language("klingon", null)).isNull();
        assertThat(SelfHostTranscription.primary("ru-RU")).isEqualTo("ru");
        assertThat(SelfHostTranscription.primary("*")).isNull();
        assertThat(selfhost(TranscriptionTestSupport.SELFHOST_KEY).toString()).doesNotContain(TranscriptionTestSupport.SELFHOST_KEY);
    }

    // -------------------------------------------------------------------- logs

    @Test
    void noOutcomeLogsAKeyTheTermsOrTheTextOfTheClip() {
        var appender = new ListAppender<ILoggingEvent>();
        var root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        Level previous = root.getLevel();
        root.setLevel(Level.DEBUG);
        appender.start();
        root.addAppender(appender);
        var request = TranscriptionTestSupport.request("audio/ogg", "ru", List.of(SECRET_HINT));
        try {
            server.on("/v1beta/interactions", exchange -> ImageServer.reply(exchange, 400, "text/plain", SECRET_HINT.getBytes(StandardCharsets.UTF_8)));
            gemini().transcribe("gemini-3.5-transcribe", request, BUDGET);
            server.bytes("/v1/audio/transcriptions", "application/json", SpeechTestSupport.resource("stt-openai-verbose.json"));
            selfhost(TranscriptionTestSupport.SELFHOST_KEY).transcribe("m", request, BUDGET);
        } finally {
            root.detachAppender(appender);
            root.setLevel(previous);
        }
        for (ILoggingEvent event : appender.list) {
            assertThat(event.getFormattedMessage()).doesNotContain(TranscriptionTestSupport.GOOGLE_KEY, TranscriptionTestSupport.SELFHOST_KEY, SECRET_HINT,
                    "RECORDED-AUDIO-BYTES");
        }
        assertThat(request.toString()).doesNotContain(SECRET_HINT, "RECORDED-AUDIO-BYTES");
        assertThat(new Transcription.Transcript(SECRET_HINT, 1, "ru", true).toString()).doesNotContain(SECRET_HINT);
    }

    // -------------------------------------------------------------------- Stub

    @Test
    void theStubAnswersAFixedSentenceAndAHarnessScriptsTheRest() {
        StubTranscription stub = new StubTranscription();
        var request = TranscriptionTestSupport.request("audio/mp4", null, List.of());
        assertThat(((AiResult.Ok<Transcription.Transcript>) stub.transcribe(request)).value())
                .isEqualTo(new Transcription.Transcript(StubTranscription.DICTATION, 4, "ru", false));
        var answer = new Transcription.Request(TranscriptionTestSupport.AUDIO, "audio/ogg", "en", List.of(), null, Duration.ofSeconds(5), 1_000,
                Transcription.Purpose.STUDY_ANSWER, null);
        assertThat(((AiResult.Ok<Transcription.Transcript>) stub.transcribe(answer)).value().text()).isEqualTo(StubTranscription.ANSWER);
        assertThat(stub.scriptable()).isTrue();
        assertThat(stub.region("ja")).contains(Transcription.Region.RU);
        assertThat(stub.configured()).isTrue();

        assertThat(scripted(stub, "Свой текст").text()).isEqualTo("Свой текст");
        assertThat(scripted(stub, "").text()).isEmpty();
        Transcription.Transcript garbled = scripted(stub, "Что-то [[stub:stt-garbled]]");
        assertThat(garbled.text()).isEqualTo("Что-то");
        assertThat(garbled.garbled()).isTrue();
        assertThat(scripted(stub, "[[stub:stt-garbled]]").garbled()).as("no text, nothing to doubt").isFalse();
        assertThat(((AiResult.Failed<Transcription.Transcript>) stub.transcribe(withScript(StubTranscription.DOWN))).failure()).isInstanceOf(AiFailure.Transient.class);
        assertThat(((AiResult.Failed<Transcription.Transcript>) stub.transcribe(withScript(StubTranscription.UNSUPPORTED))).failure())
                .isEqualTo(new AiFailure.InvalidOutput("unsupported_audio"));
        long started = System.nanoTime();
        assertThat(scripted(stub, "медленно " + StubTranscription.SLOW).text()).isEqualTo("медленно");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(Duration.ofMillis(StubTranscription.SLOW_MILLIS));
    }

    private static Transcription.Request withScript(String script) {
        return new Transcription.Request(TranscriptionTestSupport.AUDIO, "audio/ogg", null, List.of(), null, Duration.ofSeconds(5), 1_000, Transcription.Purpose.COMPOSER, script);
    }

    private static Transcription.Transcript scripted(StubTranscription stub, String script) {
        return ((AiResult.Ok<Transcription.Transcript>) stub.transcribe(withScript(script))).value();
    }

    @Test
    void aRequestIsValidatedAndItsTranscriptBounded() {
        for (Runnable bad : new Runnable[] {
                () -> new Transcription.Request(new byte[0], "audio/ogg", null, List.of(), null, Duration.ofSeconds(1), 1_000, null, null),
                () -> new Transcription.Request(TranscriptionTestSupport.AUDIO, "audio/wav", null, List.of(), null, Duration.ofSeconds(1), 1_000, null, null),
                () -> new Transcription.Request(TranscriptionTestSupport.AUDIO, "audio/ogg", null, List.of(), null, Duration.ofSeconds(1), 0, null, null),
                () -> new Transcription.Request(TranscriptionTestSupport.AUDIO, "audio/ogg", null, List.of(), null, Duration.ofSeconds(1), 60_001, null, null),
                () -> new Transcription.Request(TranscriptionTestSupport.AUDIO, "audio/ogg", null, List.of(), null, Duration.ZERO, 1_000, null, null)}) {
            org.assertj.core.api.Assertions.assertThatThrownBy(bad::run).isInstanceOf(IllegalArgumentException.class);
        }
        var request = new Transcription.Request(TranscriptionTestSupport.AUDIO, "audio/ogg", " ", null, null, Duration.ofSeconds(1), 1_000, null, null);
        assertThat(request.lang()).isNull();
        assertThat(request.hints()).isEmpty();
        assertThat(request.purpose()).isEqualTo(Transcription.Purpose.COMPOSER);
        assertThat(new Transcription.Transcript(" hi ", 0, null, false)).isEqualTo(new Transcription.Transcript("hi", 1, null, false));
    }

    @Test
    void settingsValidateTheirValuesWithoutPrintingThem() {
        for (Runnable bad : new Runnable[] {
                () -> new SttSettings(Duration.ZERO, -1, 0.6, "p"),
                () -> new SttSettings(Duration.ofMinutes(3), -1, 0.6, "p"),
                () -> new SttSettings(Duration.ofSeconds(5), 0.5, 0.6, "p"),
                () -> new SttSettings(Duration.ofSeconds(5), -1, 1.5, "p"),
                () -> new SttSettings(Duration.ofSeconds(5), -1, 0.6, " ")}) {
            org.assertj.core.api.Assertions.assertThatThrownBy(bad::run).isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ------------------------------------------------------------ the private-host rule of the self-hosted base URL

    @Test
    void httpIsAllowedForTheSelfHostedContainerOnAPrivateHostAndForNoOtherProvider() {
        var routes = AiTestSupport.routes(List.of(), List.of(), List.of());
        for (String url : new String[] {"http://stt:8000", "http://localhost:8000", "http://127.0.0.1:8000", "http://10.1.2.3:8000", "http://172.20.0.5",
                "http://192.168.1.10:9000", "https://stt.example.org"}) {
            var provider = new AiProperties.Provider(true, url, "", "", "", "");
            assertThat(AiTestSupport.properties("", routes, Map.of("selfhost", provider)).providers()).as(url).containsKey("selfhost");
        }
        for (String url : new String[] {"http://stt.example.org", "http://8.8.8.8", "http://172.32.0.1", "http://192.169.0.1", "http://10.0.0.256", "ftp://stt"}) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> new AiProperties.Provider(true, url, "", "", "", "")).as(url)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        // another provider keeps the strict rule: https, or http on loopback
        var google = new AiProperties.Provider(true, "http://stt:8000", "", "", "", "");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> AiTestSupport.properties("", routes, Map.of("google", google)))
                .isInstanceOf(IllegalArgumentException.class);
        var loopback = new AiProperties.Provider(true, "http://localhost:9", "", "", "", "");
        assertThat(AiTestSupport.properties("", routes, Map.of("google", loopback)).providers()).containsKey("google");
    }
}
