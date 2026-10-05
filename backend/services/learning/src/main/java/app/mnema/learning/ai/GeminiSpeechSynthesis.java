package app.mnema.learning.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;

/**
 * Google Gemini speech generation (https://ai.google.dev/gemini-api/docs/speech-generation, verified 2026-10-04): the unary
 * {@code POST {base}/v1beta/interactions} with the key in the {@code x-goog-api-key} header, an input of one {@code user_input} text, a
 * {@code response_format} of {@code audio/wav} at 24 kHz, and one prebuilt voice in {@code generation_config.speech_config}. The audio is the base64
 * {@code data} of the {@code audio} content of a {@code model_output} step: a RIFF/WAVE PCM s16le mono file, which the media pipeline accepts as a source
 * (the worker transcodes it to the playback variant). The language is auto-detected by the model, so none is sent; the style instruction is a fixed
 * learner-oriented sentence ({@link SpeechSettings#style()}) and the only text besides the clip's own, which the caller has already de-identified.
 *
 * <p>Pricing (https://ai.google.dev/gemini-api/docs/pricing): input and audio output are token-billed. The response carries {@code usage}
 * ({@code total_input_tokens}, {@code total_output_tokens}; live check 2026-10-05: about 200 input tokens even for one word and 32 audio tokens per
 * second), which prices the call; without it the cost is estimated as text tokens ≈ characters / 4 plus 200 and 32 audio tokens per second. The price entry in {@code learning.ai.models} is the
 * rate in force now ({@code $0.50 / $9.00} per million until 2026-12-31, {@code $1.00 / $18.00} from 2027-01-01: update it then).
 */
final class GeminiSpeechSynthesis implements SpeechAdapter {
    static final String PROVIDER = "google";
    static final int AUDIO_TOKENS_PER_SECOND = 32;
    static final int FIXED_INPUT_TOKENS = 200;
    private static final Logger LOG = LoggerFactory.getLogger(GeminiSpeechSynthesis.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ChatHttp http;
    private final URI endpoint;
    private final String key;
    private final boolean enabled;
    private final SpeechSettings settings;
    private final java.util.function.Function<String, AiProperties.Model> prices;
    private final Clock clock;

    GeminiSpeechSynthesis(AiProperties.Provider provider, ChatHttp http, SpeechSettings settings,
                          java.util.function.Function<String, AiProperties.Model> prices, Clock clock) {
        this.http = http;
        String base = provider.baseUrl().isEmpty() ? "https://generativelanguage.googleapis.com" : provider.baseUrl();
        this.endpoint = URI.create((base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + "/v1beta/interactions");
        this.key = provider.apiKey();
        this.enabled = provider.enabled();
        this.settings = settings;
        this.prices = prices;
        this.clock = clock;
    }

    @Override public String provider() { return PROVIDER; }

    @Override public boolean configured() { return enabled && !key.isEmpty() && http != null; }

    @Override public AiProperties.EgressMode egress() { return http == null ? AiProperties.EgressMode.DIRECT : http.egress(); }

    @Override public boolean supports(String lang) { return true; }

    @Override public String voiceName(String voice) { return "female".equals(voice) ? settings.googleFemale() : settings.googleMale(); }

    @Override public String format() { return "wav"; }

    /** The style instruction is sent to the model and changes the speech: a short hash of it keeps old-style clips out of the key. */
    @Override public String versionTag() {
        try {
            return "." + java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(settings.style().getBytes(java.nio.charset.StandardCharsets.UTF_8)), 0, 4);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @Override
    public AiResult<SpeechSynthesis.Audio> synthesize(String model, String modelVersion, SpeechSynthesis.Request request, Duration budget) {
        ObjectNode body = JSON.createObjectNode().put("model", model);
        ObjectNode text = body.putArray("input").addObject().put("type", "user_input").putArray("content").addObject().put("type", "text")
                .put("text", request.text());
        text.putArray("annotations").addObject().put("type", "speech_metadata").put("style", settings.style());
        body.putObject("response_format").put("type", "audio").put("mime_type", "audio/wav").put("sample_rate", 24_000);
        body.putObject("generation_config").putArray("speech_config").addObject().put("voice", voiceName(request.voice()));
        HttpRequest.Builder post = HttpRequest.newBuilder(endpoint).header("x-goog-api-key", key).header("Content-Type", "application/json")
                .header("Accept", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        ChatHttp.Reply reply;
        try {
            reply = http.send(post, budget, null);
        } catch (ChatHttp.TransportException exception) {
            return AiResult.failed(switch (exception.kind()) {
                case TIMEOUT -> new AiFailure.Timeout();
                case TOO_LARGE -> new AiFailure.InvalidOutput("body_too_large");
                case IO -> new AiFailure.Transient("io_error");
            });
        }
        if (reply.status() / 100 != 2) return AiResult.failed(ImageSource.statusFailure(reply.status(), reply.retryAfter(), clock));
        byte[] audio = audio(reply.body());
        long reported = reportedCost(reply.body(), model);
        if (audio == null) return AiResult.failed(new AiFailure.InvalidOutput("no_audio"));
        Wav.Info info = Wav.parse(audio);
        if (info == null) {
            LOG.warn("speech_invalid_audio provider={} reason=not_pcm_wav", PROVIDER);
            return AiResult.failed(new AiFailure.InvalidOutput("not_wav"));
        }
        return AiResult.ok(new SpeechSynthesis.Audio(audio, "audio/wav", request.text().length(), info.durationMs(),
                new SpeechSynthesis.Identity(PROVIDER, model, modelVersion, format(), voiceName(request.voice())), reported));
    }

    /** The first audio {@code data} of the first {@code model_output} step that has one, decoded; null when there is none or it is not base64. */
    private static byte[] audio(byte[] body) {
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (JacksonException malformed) {
            return null;
        }
        for (JsonNode step : root.path("steps")) {
            if (!"model_output".equals(step.path("type").stringValue(""))) continue;
            for (JsonNode content : step.path("content")) {
                String data = content.path("data").stringValue(null);
                if (!"audio".equals(content.path("type").stringValue("")) || data == null || data.isEmpty()) continue;
                try {
                    return Base64.getDecoder().decode(data);
                } catch (IllegalArgumentException notBase64) {
                    return null;
                }
            }
        }
        return null;
    }

    /** The cost from the response's own token counts, or 0 when it has none (then {@link #costMicros} estimates). */
    private long reportedCost(byte[] body, String model) {
        AiProperties.Model price = prices.apply(model);
        if (price == null) return 0;
        try {
            JsonNode usage = JSON.readTree(body).path("usage");
            long input = usage.path("total_input_tokens").asLong(0);
            long output = usage.path("total_output_tokens").asLong(0);
            if (input <= 0 && output <= 0) return 0;
            return ceilDiv(input * price.missMicrosPerMillion() + output * price.outputMicrosPerMillion(), 1_000_000L);
        } catch (JacksonException malformed) {
            return 0;
        }
    }

    @Override
    public long costMicros(SpeechSynthesis.Audio audio) {
        if (audio.costMicros() > 0) return audio.costMicros();
        AiProperties.Model price = prices.apply(audio.identity().model());
        if (price == null) return 0;
        long inputTokens = tokensOfText(audio.billedCharacters());
        long outputTokens = outputTokens(audio.durationMs());
        return ceilDiv(inputTokens * price.missMicrosPerMillion() + outputTokens * price.outputMicrosPerMillion(), 1_000_000L);
    }

    @Override
    public Usage usage(SpeechSynthesis.Audio audio) {
        int input = (int) Math.min(Integer.MAX_VALUE, tokensOfText(audio.billedCharacters()));
        return new Usage(input, 0, input, (int) Math.min(Integer.MAX_VALUE, outputTokens(audio.durationMs())));
    }

    static long tokensOfText(int characters) { return (characters + 3L) / 4 + FIXED_INPUT_TOKENS; }

    static long outputTokens(long durationMs) { return (durationMs * AUDIO_TOKENS_PER_SECOND + 999) / 1000; }

    private static long ceilDiv(long value, long divisor) { return Math.floorDiv(value + divisor - 1, divisor); }

    @Override
    public String toString() { return "GeminiSpeechSynthesis[" + provider().toUpperCase(Locale.ROOT) + "]"; }
}
