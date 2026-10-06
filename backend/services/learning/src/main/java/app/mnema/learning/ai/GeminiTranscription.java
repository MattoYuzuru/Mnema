package app.mnema.learning.ai;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Google Gemini audio transcription (https://ai.google.dev/gemini-api/docs/transcribe and https://ai.google.dev/gemini-api/docs/audio, verified
 * 2026-10-05): the unary {@code POST {base}/v1beta/interactions} with the key in the {@code x-goog-api-key} header and the clip inline as an {@code audio}
 * input ({@code data} base64, {@code mime_type}); the answer is the text of the {@code model_output} steps, with the token usage beside it.
 * <ul>
 *   <li>The dedicated model {@code gemini-3.5-transcribe} takes the audio alone, and {@code generation_config.transcription_config} carries the language
 *       ({@code language_codes}, a BCP 47 tag of its list, omitted to detect) and the deck terms ({@code custom_vocabulary}, up to 1,000, best up to 100).</li>
 *   <li>Any other model (a Flash or Flash-Lite) gets {@link SttSettings#geminiPrompt()} as a text input before the audio, with the language and the terms
 *       as a plain sentence each.</li>
 * </ul>
 * Gemini gives no confidence, so {@code garbled} is always false. The duration is measured from the audio tokens of the usage
 * ({@value #AUDIO_TOKENS_PER_SECOND} a second, as the live responses and the pricing page of 2026-10-05 show; the audio guide says 32). The audio is the
 * learner's voice: it leaves Russia through the egress gateway ({@code egress=proxy}), after the consent of {@code speech-consent}, and carries no
 * identity: Gemini is sent no user id (the Interactions API has no such field), and the terms are the deck's, never the learner's.
 *
 * <p>Pricing (https://ai.google.dev/gemini-api/docs/pricing): input audio and output (thinking included) tokens, from the usage and the
 * {@code learning.ai.models} entry of the model.
 */
final class GeminiTranscription implements TranscriptionAdapter {
    static final String PROVIDER = "google";
    static final int AUDIO_TOKENS_PER_SECOND = 25;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Map<String, String> TAGS = Map.ofEntries(Map.entry("ru", "ru-RU"), Map.entry("en", "en-US"), Map.entry("es", "es-419"),
            Map.entry("ja", "ja-JP"), Map.entry("ko", "ko-KR"), Map.entry("zh", "cmn-Hans-CN"), Map.entry("de", "de-DE"), Map.entry("fr", "fr-FR"),
            Map.entry("it", "it-IT"), Map.entry("pt", "pt-BR"), Map.entry("tr", "tr-TR"), Map.entry("uk", "uk-UA"), Map.entry("pl", "pl-PL"),
            Map.entry("nl", "nl-NL"), Map.entry("ar", "ar-EG"), Map.entry("hi", "hi-IN"), Map.entry("th", "th-TH"), Map.entry("vi", "vi-VN"),
            Map.entry("id", "id-ID"), Map.entry("sv", "sv-SE"), Map.entry("cs", "cs-CZ"), Map.entry("el", "el-GR"), Map.entry("he", "he-IL"));

    private final ChatHttp http;
    private final URI endpoint;
    private final String key;
    private final boolean enabled;
    private final SttSettings settings;
    private final Function<String, AiProperties.Model> prices;
    private final Clock clock;

    GeminiTranscription(AiProperties.Provider provider, ChatHttp http, SttSettings settings, Function<String, AiProperties.Model> prices, Clock clock) {
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

    @Override public Transcription.Region region() { return Transcription.Region.ABROAD; }

    /** The dedicated transcription model takes its settings in {@code transcription_config}; every other model is told what to do in words. */
    static boolean dedicated(String model) { return model.endsWith("transcribe"); }

    /** The BCP 47 tag the transcription model accepts for a hint (its documented list), or null to let it detect the language. */
    static String tag(String lang) {
        String primary = SelfHostTranscription.primary(lang);
        return primary == null ? null : TAGS.get(primary);
    }

    @Override
    public AiResult<Answer> transcribe(String model, Transcription.Request request, Duration budget) {
        ObjectNode body = JSON.createObjectNode().put("model", model);
        ArrayNode input = body.putArray("input");
        String tag = tag(request.lang());
        if (dedicated(model)) {
            ObjectNode config = JSON.createObjectNode();
            if (tag != null) config.putArray("language_codes").add(tag);
            if (!request.hints().isEmpty()) {
                ArrayNode vocabulary = config.putArray("custom_vocabulary");
                request.hints().forEach(vocabulary::add);
            }
            if (!config.isEmpty()) body.putObject("generation_config").set("transcription_config", config);
        } else {
            input.addObject().put("type", "text").put("text", prompt(request, tag));
        }
        input.addObject().put("type", "audio").put("data", Base64.getEncoder().encodeToString(request.audio())).put("mime_type", request.mimeType());
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
        // only a response that says the audio is the problem blames the learner's clip (415, 422, or a 400 that names the audio); a bad key or a request
        // the provider cannot read is also a 400 (INVALID_ARGUMENT) and is our outage: transient, so that the breaker counts it and the learner is never blamed
        if (reply.status() == 415 || reply.status() == 422 || reply.status() == 400 && audioRejected(reply.body())) {
            return AiResult.failed(new AiFailure.InvalidOutput("unsupported_audio"));
        }
        if (reply.status() == 400) return AiResult.failed(new AiFailure.Transient("http_400"));
        if (reply.status() / 100 != 2) return AiResult.failed(ImageSource.statusFailure(reply.status(), reply.retryAfter(), clock));
        JsonNode root;
        try {
            root = JSON.readTree(reply.body());
        } catch (JacksonException malformed) {
            return AiResult.failed(new AiFailure.InvalidOutput("not_json"));
        }
        // a response without a status, an answer the model blocked, or one with no model output at all is an outage, not a clip without speech
        if (!"completed".equals(root.path("status").stringValue("")) || root.has("error") || root.path("prompt_feedback").has("block_reason")
                || root.path("promptFeedback").has("blockReason")) {
            return AiResult.failed(new AiFailure.InvalidOutput("not_completed"));
        }
        StringBuilder text = new StringBuilder();
        boolean answered = false;
        for (JsonNode step : root.path("steps")) {
            if (!"model_output".equals(step.path("type").stringValue(""))) continue;
            answered = true;
            for (JsonNode content : step.path("content")) {
                if ("text".equals(content.path("type").stringValue(""))) text.append(content.path("text").stringValue(""));
            }
        }
        if (!answered) return AiResult.failed(new AiFailure.InvalidOutput("no_output"));
        JsonNode usage = root.path("usage");
        long audioTokens = 0;
        for (JsonNode modality : usage.path("input_tokens_by_modality")) {
            if ("audio".equals(modality.path("modality").stringValue(""))) audioTokens += modality.path("tokens").longValue(0);
        }
        long measuredMs = audioTokens * 1000 / AUDIO_TOKENS_PER_SECOND;
        if (measuredMs > (Transcription.MAX_SECONDS + 1) * 1000L) return AiResult.failed(new AiFailure.Refusal("too_long"));
        int seconds = audioTokens > 0 ? (int) ((audioTokens + AUDIO_TOKENS_PER_SECOND - 1) / AUDIO_TOKENS_PER_SECOND) : (request.declaredMs() + 999) / 1000;
        int in = (int) Math.min(Integer.MAX_VALUE, usage.path("total_input_tokens").longValue(0));
        int out = (int) Math.min(Integer.MAX_VALUE, outputTokens(usage));
        return AiResult.ok(new Answer(new Transcription.Transcript(text.toString(), seconds, request.lang(), false), new Usage(in, 0, in, out)));
    }

    private static final java.util.regex.Pattern AUDIO_REJECTED = java.util.regex.Pattern.compile(
            "(unsupported|not supported|invalid|cannot|unable|could not|failed).{0,80}(audio|mime)|(audio|mime).{0,80}(unsupported|not supported|invalid|cannot|decode|corrupt)");

    /** Whether the body of a 400 says that the audio itself is the problem; the message is only matched, never kept or logged. */
    static boolean audioRejected(byte[] body) {
        try {
            String message = JSON.readTree(body).path("error").path("message").stringValue("").toLowerCase(Locale.ROOT);
            return !message.contains("api key") && !message.contains("api_key") && AUDIO_REJECTED.matcher(message).find();
        } catch (RuntimeException notJson) {
            return false;
        }
    }

    /**
     * The billed output (thinking included): the totals of the usage, or, when the model reports none (the dedicated transcription model answered
     * {@code total_output_tokens: 0} next to 11 candidate tokens on 2026-10-05), the candidate and thought tokens of its invocations.
     */
    static long outputTokens(JsonNode usage) {
        long totals = usage.path("total_output_tokens").longValue(0) + usage.path("total_thought_tokens").longValue(0);
        if (totals > 0) return totals;
        long counted = 0;
        for (JsonNode invocation : usage.path("model_invocation_token_counts")) {
            for (String details : new String[] {"candidates_tokens_details", "thoughts_tokens_details"}) {
                for (JsonNode part : invocation.path(details)) counted += part.path("tokens").longValue(0);
            }
        }
        return counted;
    }

    private String prompt(Transcription.Request request, String tag) {
        StringBuilder prompt = new StringBuilder(settings.geminiPrompt());
        if (tag != null) prompt.append(" The expected language is ").append(tag).append('.');
        if (!request.hints().isEmpty()) prompt.append(" Words that may occur: ").append(String.join(", ", request.hints())).append('.');
        return prompt.toString();
    }

    @Override
    public long costMicros(String model, Answer answer) {
        AiProperties.Model price = prices.apply(model);
        if (price == null) return 0;
        Usage usage = answer.usage();
        long cost = (long) usage.promptTokens() * price.missMicrosPerMillion() + (long) usage.completionTokens() * price.outputMicrosPerMillion();
        return Math.floorDiv(cost + 999_999L, 1_000_000L);
    }

    @Override
    public String toString() { return "GeminiTranscription[" + PROVIDER.toUpperCase(Locale.ROOT) + "]"; }
}
