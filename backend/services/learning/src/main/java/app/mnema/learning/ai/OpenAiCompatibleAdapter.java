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
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * OpenAI-compatible {@code /chat/completions} adapter for DeepSeek, GigaChat and OpenRouter. Differences between the
 * providers are confined to {@link Dialect}: extra body fields, the user-id field name and the usage fields. Streaming
 * parses server-sent events line by line (comment/keep-alive lines ignored, {@code [DONE]} ends the stream).
 *
 * <p>One call, one attempt: the router owns retries, fallback and breakers. Failure details are fixed codes; neither the
 * credential nor any prompt or response text can reach a log through this class.
 */
final class OpenAiCompatibleAdapter implements TextAdapter {
    /** Per-provider request and usage quirks. */
    enum Dialect {
        /**
         * {@code thinking} is disabled explicitly (on by default, it multiplies output cost) except on the planner routes, which enable it
         * (https://api-docs.deepseek.com/guides/thinking_mode); user field is {@code user_id}.
         */
        DEEPSEEK,
        /** Plain OpenAI shape; cached prompt tokens arrive as {@code precached_prompt_tokens}. */
        GIGACHAT,
        /** Zero data collection preference and {@code usage.cost}. */
        OPENROUTER
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** Provider request ids are stored in an octet-limited column and logged: only plain identifiers are kept. */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._:-]{1,200}");

    private final String provider;
    private final Dialect dialect;
    private final URI uri;
    private final BearerSource bearer;
    private final ChatHttp http;
    private final Map<String, AiProperties.Model> prices;
    private final Clock clock;

    OpenAiCompatibleAdapter(String provider, Dialect dialect, URI baseUrl, BearerSource bearer, ChatHttp http,
                            Map<String, AiProperties.Model> prices, Clock clock) {
        this.provider = provider;
        this.dialect = dialect;
        this.uri = URI.create(baseUrl.toString().replaceAll("/+$", "") + "/chat/completions");
        this.bearer = bearer;
        this.http = http;
        this.prices = Map.copyOf(prices);
        this.clock = clock;
    }

    @Override public String provider() { return provider; }

    @Override public boolean configured() { return bearer.configured(); }

    @Override
    public AiResult<TextResponse> attempt(String model, TextRequest request, Duration budget) {
        long started = System.nanoTime();
        AiResult<String> credential = bearer.bearer(budget);
        if (credential instanceof AiResult.Failed<String> failed) return AiResult.failed(failed.failure());
        String token = ((AiResult.Ok<String>) credential).value();
        Duration remaining = budget.minusNanos(System.nanoTime() - started);

        var httpRequest = HttpRequest.newBuilder(uri)
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .header("Accept", request.streaming() ? "text/event-stream" : "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body(model, request).toString()));
        var stream = request.streaming() ? new StreamAccumulator(request.listener()) : null;
        ChatHttp.Reply reply;
        try {
            reply = http.send(httpRequest, remaining, stream);
        } catch (ChatHttp.TransportException exception) {
            return AiResult.failed(switch (exception.kind()) {
                case TIMEOUT -> new AiFailure.Timeout();
                case TOO_LARGE -> new AiFailure.InvalidOutput("body_too_large");
                case IO -> new AiFailure.Transient("io_error");
            });
        }
        if (reply.status() == 401) bearer.invalidate();
        if (reply.status() / 100 != 2) return AiResult.failed(statusFailure(reply.status(), reply.retryAfter()));
        Parsed parsed = stream != null ? stream.parsed() : parseWhole(reply.body());
        if (parsed.failure() != null) return AiResult.failed(parsed.failure());
        return finish(model, request, parsed, reply.requestId());
    }

    // ------------------------------------------------------------------ request

    private ObjectNode body(String model, TextRequest request) {
        ObjectNode body = JSON.createObjectNode();
        body.put("model", model);
        ArrayNode messages = body.putArray("messages");
        TextRequest.Role role = null;
        StringBuilder text = null;
        for (TextRequest.Segment segment : request.segments()) {
            if (segment.role() != role) {
                flush(messages, role, text);
                role = segment.role();
                text = new StringBuilder();
            } else {
                text.append("\n\n");
            }
            text.append(segment.text());
        }
        flush(messages, role, text);
        body.put("max_tokens", request.maxOutputTokens());
        body.put("temperature", request.temperature());
        if (request.output() == OutputContract.JSON) body.putObject("response_format").put("type", "json_object");
        if (request.streaming()) {
            body.put("stream", true);
            body.putObject("stream_options").put("include_usage", true);
        }
        switch (dialect) {
            case DEEPSEEK -> {
                // reasoning is on only for the planner routes (AI-14); the temperature is ignored by the provider in that mode
                body.putObject("thinking").put("type", request.route().thinking() ? "enabled" : "disabled");
                body.put("user_id", request.userKey().value());
            }
            case GIGACHAT -> { }
            case OPENROUTER -> {
                body.put("user", request.userKey().value());
                body.putObject("usage").put("include", true);
                body.putObject("provider").put("data_collection", "deny");
            }
        }
        return body;
    }

    private static void flush(ArrayNode messages, TextRequest.Role role, StringBuilder text) {
        if (role == null) return;
        messages.addObject().put("role", role == TextRequest.Role.SYSTEM ? "system" : "user").put("content", text.toString());
    }

    // ----------------------------------------------------------------- response

    private AiFailure statusFailure(int status, String retryAfter) {
        if (status == 429) return new AiFailure.RateLimited(parseRetryAfter(retryAfter));
        if (status >= 500 || status == 408) return new AiFailure.Transient("http_" + status);
        if (status == 401 || status == 402 || status == 403 || status == 404 || status / 100 == 3) {
            return new AiFailure.NotConfigured("http_" + status);
        }
        return new AiFailure.Refusal("http_" + status);
    }

    private Duration parseRetryAfter(String value) {
        if (value == null || value.isBlank()) return Duration.ZERO;
        String trimmed = value.strip();
        try {
            return Duration.ofSeconds(Math.min(Long.parseLong(trimmed), 3_600));
        } catch (NumberFormatException notSeconds) {
            try {
                Duration wait = Duration.between(clock.instant(), ZonedDateTime.parse(trimmed,
                        DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
                return wait.isNegative() ? Duration.ZERO : wait.compareTo(Duration.ofHours(1)) > 0 ? Duration.ofHours(1) : wait;
            } catch (DateTimeParseException notDate) {
                return Duration.ZERO;
            }
        }
    }

    private static Parsed parseWhole(byte[] body) {
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (JacksonException exception) {
            return Parsed.failed(new AiFailure.InvalidOutput("malformed_json"));
        }
        JsonNode choice = root.path("choices").path(0);
        if (!choice.isObject()) return Parsed.failed(new AiFailure.InvalidOutput("no_choices"));
        JsonNode message = choice.path("message");
        if (message.path("refusal").isString() && !message.path("refusal").stringValue().isEmpty()) {
            return Parsed.failed(new AiFailure.Refusal("refusal"));
        }
        return new Parsed(message.path("content").stringValue(""), choice.path("finish_reason").stringValue(""),
                root.path("usage"), root.path("id").stringValue(null), null);
    }

    private AiResult<TextResponse> finish(String model, TextRequest request, Parsed parsed, String headerId) {
        String reason = parsed.finishReason();
        if ("content_filter".equals(reason)) return AiResult.failed(new AiFailure.Refusal("content_filter"));
        if ("insufficient_system_resource".equals(reason) || "aborted".equals(reason)) {
            return AiResult.failed(new AiFailure.Transient("finish_" + reason));
        }
        // GigaChat finish reasons beyond the OpenAI set. UNVERIFIED against the official reference (developers.sber.ru was not
        // retrievable when this was written): "blacklist" is mapped to a refusal (the answer was withheld by a content stop
        // list) and "error" to a transient failure (a provider-side generation error). Revisit with the live API.
        if ("blacklist".equals(reason)) return AiResult.failed(new AiFailure.Refusal("blacklist"));
        if ("error".equals(reason)) return AiResult.failed(new AiFailure.Transient("finish_error"));
        String text = parsed.text();
        if (text.isBlank()) return AiResult.failed(new AiFailure.InvalidOutput("empty_content"));
        if (request.output() == OutputContract.JSON) {
            try {
                if (!JSON.readTree(text).isObject()) return AiResult.failed(new AiFailure.InvalidOutput("json_not_object"));
            } catch (JacksonException exception) {
                return AiResult.failed(new AiFailure.InvalidOutput("malformed_json_output"));
            }
        }
        var finishReason = switch (reason) {
            case "stop" -> TextResponse.FinishReason.STOP;
            case "length" -> TextResponse.FinishReason.LENGTH;
            default -> TextResponse.FinishReason.OTHER;
        };
        Usage usage = usage(parsed.usage(), request, text);
        long cost = cost(model, usage, parsed.usage());
        String id = safeId(parsed.id());
        if (id == null) id = safeId(headerId);
        return AiResult.ok(new TextResponse(text, finishReason, usage, cost, id, new TextResponse.RouteUsed(provider, model)));
    }

    private static String safeId(String candidate) { return candidate != null && SAFE_ID.matcher(candidate).matches() ? candidate : null; }

    private Usage usage(JsonNode node, TextRequest request, String text) {
        if (!node.isObject() || node.isEmpty()) {
            int prompt = request.segments().stream().mapToInt(segment -> TokenCounter.estimate(segment.text())).sum();
            return new Usage(prompt, 0, prompt, TokenCounter.estimate(text));
        }
        int prompt = node.path("prompt_tokens").intValue(0);
        int hit = switch (dialect) {
            case DEEPSEEK -> node.path("prompt_cache_hit_tokens").intValue(0);
            case GIGACHAT -> node.path("precached_prompt_tokens").intValue(0);
            case OPENROUTER -> node.path("prompt_tokens_details").path("cached_tokens").intValue(0);
        };
        hit = Math.max(0, Math.min(hit, prompt));
        return new Usage(prompt, hit, prompt - hit, Math.max(0, node.path("completion_tokens").intValue(0)));
    }

    private long cost(String model, Usage usage, JsonNode node) {
        if (dialect == Dialect.OPENROUTER && node.path("cost").isNumber()) {
            return Math.max(0, Math.round(node.path("cost").doubleValue() * 1_000_000));
        }
        AiProperties.Model price = prices.get(model);
        if (price == null) return 0;
        long micros = usage.cacheHitTokens() * price.hitMicrosPerMillion()
                + usage.cacheMissTokens() * price.missMicrosPerMillion()
                + usage.completionTokens() * price.outputMicrosPerMillion();
        return Math.ceilDiv(micros, 1_000_000L);
    }

    /** What one reply boils down to; {@code failure} is set when the body itself is a provider error. */
    private record Parsed(String text, String finishReason, JsonNode usage, String id, AiFailure failure) {
        static Parsed failed(AiFailure failure) { return new Parsed("", "", JSON.createObjectNode(), null, failure); }
    }

    /** Line consumer of a server-sent-event stream. */
    private static final class StreamAccumulator implements java.util.function.Predicate<String> {
        private final StreamListener listener;
        private final StringBuilder text = new StringBuilder();
        private String finishReason = "";
        private JsonNode usage = JSON.createObjectNode();
        private String id;
        private AiFailure failure;
        private boolean done;

        StreamAccumulator(StreamListener listener) { this.listener = listener; }

        @Override
        public boolean test(String line) {
            if (line.isEmpty() || line.charAt(0) == ':') return true;
            if (!line.startsWith("data:")) return true;
            String data = line.substring(5).strip();
            if (data.equals("[DONE]")) {
                done = true;
                return false;
            }
            JsonNode chunk;
            try {
                chunk = JSON.readTree(data);
            } catch (JacksonException exception) {
                failure = new AiFailure.InvalidOutput("malformed_chunk");
                return false;
            }
            if (chunk.has("error")) {
                failure = new AiFailure.Transient("provider_error");
                return false;
            }
            if (id == null && chunk.path("id").isString()) id = chunk.path("id").stringValue();
            if (chunk.path("usage").isObject()) usage = chunk.path("usage");
            JsonNode choice = chunk.path("choices").path(0);
            if (choice.path("delta").path("refusal").isString() && !choice.path("delta").path("refusal").stringValue().isEmpty()) {
                failure = new AiFailure.Refusal("refusal");
                return false;
            }
            String delta = choice.path("delta").path("content").stringValue("");
            if (!delta.isEmpty()) {
                text.append(delta);
                listener.onDelta(delta);
            }
            if (choice.path("finish_reason").isString()) finishReason = choice.path("finish_reason").stringValue();
            return true;
        }

        Parsed parsed() {
            if (failure != null) return Parsed.failed(failure);
            if (!done && finishReason.isEmpty()) return Parsed.failed(new AiFailure.Transient("stream_truncated"));
            return new Parsed(text.toString(), finishReason, usage, id, null);
        }
    }
}
