package app.mnema.learning.ai;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * A self-hosted speech-to-text container that speaks the OpenAI audio API: {@code POST {base-url}/v1/audio/transcriptions} as
 * {@code multipart/form-data} with {@code file}, {@code model}, {@code language} (the primary subtag of the hint, when there is one), {@code prompt} (the
 * deck terms as a vocabulary hint), {@code temperature=0} and {@code response_format=verbose_json}
 * (https://developers.openai.com/api/reference/resources/audio/subresources/transcriptions/methods/create). The same shape is served by speaches
 * (faster-whisper-server) and by vLLM for Qwen3-ASR, so a GigaAM wrapper only has to implement it. Nothing here is verified against a live container:
 * none exists yet (the owner has not approved one), so the adapter is tested on recorded answers only.
 *
 * <p>The answer's {@code text}, {@code language}, {@code duration} (the measured seconds) and, per segment, {@code avg_logprob} and
 * {@code no_speech_prob} are read; a clip is {@code garbled} when the duration-weighted mean {@code avg_logprob} is below
 * {@link SttSettings#minAvgLogprob()} or the strongest {@code no_speech_prob} of an answer that has text is above {@link SttSettings#maxNoSpeechProb()}
 * (a container that sends no segments never yields {@code garbled}). The audio never leaves the network of the container; there is no price.
 */
final class SelfHostTranscription implements TranscriptionAdapter {
    static final String PROVIDER = AiProperties.SELFHOST;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_PROMPT_CHARS = 600;

    private final ChatHttp http;
    private final URI endpoint;
    private final String key;
    private final boolean enabled;
    private final SttSettings settings;
    private final Clock clock;

    SelfHostTranscription(AiProperties.Provider provider, ChatHttp http, SttSettings settings, Clock clock) {
        this.http = http;
        String base = provider.baseUrl();
        this.endpoint = base.isEmpty() ? null : URI.create((base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + "/v1/audio/transcriptions");
        this.key = provider.apiKey();
        this.enabled = provider.enabled();
        this.settings = settings;
        this.clock = clock;
    }

    @Override public String provider() { return PROVIDER; }

    @Override public boolean configured() { return enabled && endpoint != null && http != null; }

    @Override public AiProperties.EgressMode egress() { return http == null ? AiProperties.EgressMode.DIRECT : http.egress(); }

    @Override public Transcription.Region region() { return Transcription.Region.RU; }

    @Override
    public AiResult<Answer> transcribe(String model, Transcription.Request request, Duration budget) {
        String boundary = "mnema-" + UUID.randomUUID();
        HttpRequest.Builder post = HttpRequest.newBuilder(endpoint).header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Accept", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(multipart(boundary, model, request)));
        if (!key.isEmpty()) post.header("Authorization", "Bearer " + key);
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
        int status = reply.status();
        // the container could not decode the clip: another route entry may
        if (status == 400 || status == 415 || status == 422) return AiResult.failed(new AiFailure.InvalidOutput("unsupported_audio"));
        if (status / 100 != 2) return AiResult.failed(ImageSource.statusFailure(status, reply.retryAfter(), clock));
        JsonNode root;
        try {
            root = JSON.readTree(reply.body());
        } catch (JacksonException malformed) {
            return AiResult.failed(new AiFailure.InvalidOutput("not_json"));
        }
        if (!root.path("text").isString()) return AiResult.failed(new AiFailure.InvalidOutput("no_text"));
        String text = root.path("text").stringValue("").strip();
        double measured = root.path("duration").isNumber() ? root.path("duration").doubleValue() : 0;
        if (measured > Transcription.MAX_SECONDS + 1) return AiResult.failed(new AiFailure.Refusal("too_long"));
        int seconds = measured > 0 ? (int) Math.ceil(measured) : (request.declaredMs() + 999) / 1000;
        String lang = language(root.path("language").stringValue(""), request.lang());
        boolean garbled = !text.isEmpty() && garbled(root.path("segments"));
        return AiResult.ok(new Answer(new Transcription.Transcript(text, seconds, lang, garbled), Usage.ZERO));
    }

    private static final java.util.Map<String, String> NAMES = java.util.Map.ofEntries(java.util.Map.entry("russian", "ru"), java.util.Map.entry("english", "en"),
            java.util.Map.entry("spanish", "es"), java.util.Map.entry("japanese", "ja"), java.util.Map.entry("korean", "ko"), java.util.Map.entry("chinese", "zh"),
            java.util.Map.entry("german", "de"), java.util.Map.entry("french", "fr"), java.util.Map.entry("italian", "it"), java.util.Map.entry("portuguese", "pt"),
            java.util.Map.entry("ukrainian", "uk"), java.util.Map.entry("turkish", "tr"), java.util.Map.entry("polish", "pl"), java.util.Map.entry("arabic", "ar"));

    /** The detected language as a BCP 47 primary tag: OpenAI-compatible servers answer a name ({@code russian}) or an ISO code; anything else is the hint. */
    static String language(String detected, String hint) {
        String value = detected == null ? "" : detected.strip().toLowerCase(Locale.ROOT);
        if (NAMES.containsKey(value)) return NAMES.get(value);
        if (value.matches("[a-z]{2,3}")) return value;
        return hint == null || hint.isBlank() ? null : hint;
    }

    private boolean garbled(JsonNode segments) {
        if (!segments.isArray() || segments.isEmpty()) return false;
        double weighted = 0;
        double total = 0;
        double plain = 0;
        int count = 0;
        double strongestNoSpeech = 0;
        for (JsonNode segment : segments) {
            if (segment.path("no_speech_prob").isNumber()) strongestNoSpeech = Math.max(strongestNoSpeech, segment.path("no_speech_prob").doubleValue());
            if (!segment.path("avg_logprob").isNumber()) continue;
            double logprob = segment.path("avg_logprob").doubleValue();
            double length = segment.path("end").isNumber() && segment.path("start").isNumber()
                    ? Math.max(0, segment.path("end").doubleValue() - segment.path("start").doubleValue()) : 0;
            weighted += logprob * length;
            total += length;
            plain += logprob;
            count++;
        }
        double mean = total > 0 ? weighted / total : count > 0 ? plain / count : 0;
        return (count > 0 && mean < settings.minAvgLogprob()) || strongestNoSpeech > settings.maxNoSpeechProb();
    }

    @Override public long costMicros(String model, Answer answer) { return 0; }

    /** The form: the clip first as {@code file}, then the plain fields; a field value never contains the boundary (it is a random UUID). */
    private byte[] multipart(String boundary, String model, Transcription.Request request) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(request.audio().length + 1_024);
        String extension = switch (request.mimeType()) {
            case "audio/mp4" -> "m4a";
            case "audio/mpeg" -> "mp3";
            case "audio/ogg" -> "ogg";
            default -> "webm";
        };
        write(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"clip." + extension + "\"\r\nContent-Type: "
                + request.mimeType() + "\r\n\r\n");
        out.writeBytes(request.audio());
        write(out, "\r\n");
        field(out, boundary, "model", model);
        field(out, boundary, "response_format", "verbose_json");
        field(out, boundary, "temperature", "0");
        String language = primary(request.lang());
        if (language != null) field(out, boundary, "language", language);
        String prompt = vocabulary(request.hints());
        if (!prompt.isEmpty()) field(out, boundary, "prompt", prompt);
        write(out, "--" + boundary + "--\r\n");
        return out.toByteArray();
    }

    private static void field(ByteArrayOutputStream out, String boundary, String name, String value) {
        write(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n");
    }

    private static void write(ByteArrayOutputStream out, String text) { out.writeBytes(text.getBytes(StandardCharsets.UTF_8)); }

    /** The ISO 639 language of a BCP 47 tag ({@code ru-RU} gives {@code ru}), or null when it has none. */
    static String primary(String tag) {
        if (tag == null) return null;
        String subtag = tag.strip().toLowerCase(Locale.ROOT).split("[-_]", 2)[0];
        return subtag.matches("[a-z]{2,3}") ? subtag : null;
    }

    /** The deck terms as one line, bounded: Whisper-class models read about 224 tokens of prompt. */
    private static String vocabulary(List<String> hints) {
        StringBuilder line = new StringBuilder();
        for (String hint : hints) {
            if (line.length() + hint.length() + 2 > MAX_PROMPT_CHARS) break;
            if (!line.isEmpty()) line.append(", ");
            line.append(hint);
        }
        return line.toString();
    }

    @Override
    public String toString() { return "SelfHostTranscription[" + PROVIDER + "]"; }
}
