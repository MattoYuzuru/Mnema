package app.mnema.ai.provider.openai;

import app.mnema.ai.provider.support.ProviderRetrySupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.Base64;
import java.util.Locale;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Component
public class OpenAiClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(OpenAiClient.class);
    private static final int LOCAL_EMPTY_RESPONSE_MAX_ATTEMPTS = 3;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final Duration requestReadTimeout;
    private final boolean localGatewayBaseUrl;

    public OpenAiClient(RestClient.Builder restClientBuilder,
                        OpenAiProps props,
                        ObjectMapper objectMapper) {
        long connectTimeoutMs = positiveOrDefault(props.requestConnectTimeoutMs(), 10_000L);
        long readTimeoutMs = positiveOrDefault(props.requestReadTimeoutMs(), 600_000L);
        // local-ai-gateway runs on uvicorn (HTTP/1.1); forcing h1 avoids h2c upgrade failures
        JdkClientHttpRequestFactory http11Factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                        .version(HttpClient.Version.HTTP_1_1)
                        .build()
        );
        http11Factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        this.restClient = restClientBuilder
                .requestFactory(http11Factory)
                .baseUrl(props.baseUrl())
                .build();
        this.objectMapper = objectMapper;
        this.requestReadTimeout = Duration.ofMillis(readTimeoutMs);
        this.localGatewayBaseUrl = isLocalGatewayBaseUrl(props.baseUrl());
    }

    public OpenAiResponseResult createResponse(String apiKey, OpenAiResponseRequest request) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("model", request.model());
        payload.put("input", request.input());
        if (request.maxOutputTokens() != null && request.maxOutputTokens() > 0) {
            payload.put("max_output_tokens", request.maxOutputTokens());
        }
        if (request.responseFormat() != null && !request.responseFormat().isNull()) {
            ObjectNode textNode = payload.putObject("text");
            textNode.set("format", request.responseFormat());
        }
        JsonNode response = createResponseJson(apiKey, payload, request.model(), request.input(), request.maxOutputTokens());
        return toResponseResult(response);
    }

    public OpenAiResponseResult createResponseWithInput(String apiKey,
                                                        String model,
                                                        JsonNode input,
                                                        Integer maxOutputTokens,
                                                        JsonNode responseFormat) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("model", model);
        payload.set("input", input);
        if (maxOutputTokens != null && maxOutputTokens > 0) {
            payload.put("max_output_tokens", maxOutputTokens);
        }
        if (responseFormat != null && !responseFormat.isNull()) {
            ObjectNode textNode = payload.putObject("text");
            textNode.set("format", responseFormat);
        }
        String compatInput = input == null ? "" : (input.isTextual() ? input.asText() : input.toString());
        JsonNode response = createResponseJson(apiKey, payload, model, compatInput, maxOutputTokens);
        return toResponseResult(response);
    }

    private JsonNode createResponseJson(String apiKey,
                                        ObjectNode payload,
                                        String model,
                                        String compatInput,
                                        Integer maxOutputTokens) {
        int attempts = localGatewayBaseUrl ? LOCAL_EMPTY_RESPONSE_MAX_ATTEMPTS : 1;
        JsonNode lastResponse = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            JsonNode response = executeResponsesRequest(apiKey, payload, model, compatInput, maxOutputTokens);
            if (response == null) {
                throw new IllegalStateException("OpenAI response is empty");
            }
            lastResponse = response;
            String outputText = OpenAiResponseParser.extractText(response);
            if (!outputText.isBlank()) {
                return response;
            }
            if (attempt < attempts) {
                LOGGER.warn("OpenAI-compatible response missing usable text attempt={} model={} summary={}",
                        attempt,
                        model,
                        summarizeResponse(response));
            }
        }
        throw new IllegalStateException("AI response is empty; " + summarizeResponse(lastResponse));
    }

    private JsonNode executeResponsesRequest(String apiKey,
                                             ObjectNode payload,
                                             String model,
                                             String compatInput,
                                             Integer maxOutputTokens) {
        return ProviderRetrySupport.executeTextRequest("OpenAI", LOGGER, () -> {
            RestClient.RequestBodySpec spec = restClient.post()
                    .uri("/v1/responses")
                    .contentType(MediaType.APPLICATION_JSON);
            if (hasApiKey(apiKey)) {
                spec = spec.header(HttpHeaders.AUTHORIZATION, bearer(apiKey));
            }
            try {
                return spec.body(payload)
                        .retrieve()
                        .body(JsonNode.class);
            } catch (HttpClientErrorException ex) {
                if (!shouldFallbackToChatCompat(ex)) {
                    throw ex;
                }
                return createChatCompletionCompat(apiKey, model, compatInput, maxOutputTokens);
            }
        });
    }

    private OpenAiResponseResult toResponseResult(JsonNode response) {
        if (response == null) {
            throw new IllegalStateException("OpenAI response is empty");
        }
        String outputText = OpenAiResponseParser.extractText(response);
        String model = response.path("model").asText(null);
        JsonNode usage = response.path("usage");
        Integer inputTokens = usage.hasNonNull("input_tokens")
                ? usage.get("input_tokens").asInt()
                : (usage.hasNonNull("prompt_tokens") ? usage.get("prompt_tokens").asInt() : null);
        Integer outputTokens = usage.hasNonNull("output_tokens")
                ? usage.get("output_tokens").asInt()
                : (usage.hasNonNull("completion_tokens") ? usage.get("completion_tokens").asInt() : null);
        return new OpenAiResponseResult(outputText, model, inputTokens, outputTokens, response);
    }

    private JsonNode createChatCompletionCompat(String apiKey,
                                                String model,
                                                String input,
                                                Integer maxOutputTokens) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("model", model);
        payload.putArray("messages")
                .addObject()
                .put("role", "user")
                .put("content", input == null ? "" : input);
        if (maxOutputTokens != null && maxOutputTokens > 0) {
            payload.put("max_tokens", maxOutputTokens);
        }

        RestClient.RequestBodySpec spec = restClient.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON);
        if (hasApiKey(apiKey)) {
            spec = spec.header(HttpHeaders.AUTHORIZATION, bearer(apiKey));
        }
        return spec.body(payload)
                .retrieve()
                .body(JsonNode.class);
    }

    static boolean shouldFallbackToChatCompat(int statusCode, String errorText) {
        if (statusCode == 404 || statusCode == 405 || statusCode == 410 || statusCode == 501) {
            return true;
        }
        if (statusCode != 400) {
            return false;
        }
        String normalized = errorText == null ? "" : errorText.toLowerCase(Locale.ROOT);
        boolean mentionsResponses = normalized.contains("/v1/responses") || normalized.contains("responses");
        boolean unsupportedEndpoint = normalized.contains("not found")
                || normalized.contains("unsupported")
                || normalized.contains("unknown")
                || normalized.contains("no route")
                || normalized.contains("unrecognized");
        return mentionsResponses && unsupportedEndpoint;
    }

    private static boolean shouldFallbackToChatCompat(HttpClientErrorException ex) {
        String body = ex.getResponseBodyAsString();
        String message = ex.getMessage();
        String combined = (body == null ? "" : body) + " " + (message == null ? "" : message);
        return shouldFallbackToChatCompat(ex.getStatusCode().value(), combined);
    }

    static boolean isLocalGatewayBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(baseUrl);
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return false;
            }
            String normalized = host.toLowerCase(Locale.ROOT);
            return "localhost".equals(normalized)
                    || "127.0.0.1".equals(normalized)
                    || "::1".equals(normalized)
                    || "local-ai-gateway".equals(normalized)
                    || normalized.endsWith(".local");
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    static String summarizeResponse(JsonNode response) {
        if (response == null || response.isNull()) {
            return "response=null";
        }
        String status = response.path("status").asText("");
        String model = response.path("model").asText("");
        String outputTypes = "";
        JsonNode output = response.path("output");
        if (output.isArray() && !output.isEmpty()) {
            StringBuilder builder = new StringBuilder();
            for (JsonNode item : output) {
                if (!builder.isEmpty()) {
                    builder.append(',');
                }
                builder.append(item.path("type").asText("?"));
            }
            outputTypes = builder.toString();
        }
        int choices = response.path("choices").isArray() ? response.path("choices").size() : 0;
        return "status=" + status + ", model=" + model + ", outputTypes=" + outputTypes + ", choices=" + choices;
    }

    public byte[] createSpeech(String apiKey, OpenAiSpeechRequest request) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("model", request.model());
        payload.put("input", request.input());
        if (request.voice() != null && !request.voice().isBlank()) {
            payload.put("voice", request.voice());
        }
        payload.put("response_format", request.responseFormat());

        RestClient.RequestBodySpec spec = restClient.post()
                .uri("/v1/audio/speech")
                .contentType(MediaType.APPLICATION_JSON);
        if (hasApiKey(apiKey)) {
            spec = spec.header(HttpHeaders.AUTHORIZATION, bearer(apiKey));
        }
        byte[] response = spec.body(payload)
                .retrieve()
                .body(byte[].class);

        if (response == null || response.length == 0) {
            throw new IllegalStateException("OpenAI speech response is empty");
        }
        return response;
    }

    public String createTranscription(String apiKey, OpenAiTranscriptionRequest request) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("model", request.model());
        if (request.language() != null && !request.language().isBlank()) {
            builder.part("language", request.language());
        }
        if (request.responseFormat() != null && !request.responseFormat().isBlank()) {
            builder.part("response_format", request.responseFormat());
        }
        ByteArrayResource resource = new ByteArrayResource(request.audio()) {
            @Override
            public String getFilename() {
                return request.fileName();
            }
        };
        builder.part("file", resource)
                .contentType(MediaType.parseMediaType(request.mimeType()));

        RestClient.RequestBodySpec spec = restClient.post()
                .uri("/v1/audio/transcriptions")
                .contentType(MediaType.MULTIPART_FORM_DATA);
        if (hasApiKey(apiKey)) {
            spec = spec.header(HttpHeaders.AUTHORIZATION, bearer(apiKey));
        }
        JsonNode response = spec.body(builder.build())
                .retrieve()
                .body(JsonNode.class);

        if (response == null) {
            throw new IllegalStateException("OpenAI transcription response is empty");
        }
        String text = response.path("text").asText(null);
        if (text == null || text.isBlank()) {
            throw new IllegalStateException("OpenAI transcription response is empty");
        }
        return text;
    }

    public OpenAiImageResult createImage(String apiKey, OpenAiImageRequest request) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("model", request.model());
        payload.put("prompt", request.prompt());
        if (request.size() != null && !request.size().isBlank()) {
            payload.put("size", request.size());
        }
        if (request.quality() != null && !request.quality().isBlank()) {
            payload.put("quality", request.quality());
        }
        if (request.style() != null && !request.style().isBlank()) {
            payload.put("style", request.style());
        }
        if (request.format() != null && !request.format().isBlank()) {
            payload.put("output_format", request.format());
        }
        // Ollama OpenAI compatibility expects b64_json for image payloads.
        payload.put("response_format", "b64_json");
        RestClient.RequestBodySpec spec = restClient.post()
                .uri("/v1/images/generations")
                .contentType(MediaType.APPLICATION_JSON);
        if (hasApiKey(apiKey)) {
            spec = spec.header(HttpHeaders.AUTHORIZATION, bearer(apiKey));
        }
        JsonNode response = spec.body(payload)
                .retrieve()
                .body(JsonNode.class);

        if (response == null) {
            throw new IllegalStateException("OpenAI image response is empty");
        }
        JsonNode dataNode = response.path("data");
        if (!dataNode.isArray() || dataNode.isEmpty()) {
            throw new IllegalStateException("OpenAI image response missing data");
        }
        JsonNode item = dataNode.get(0);
        String b64 = item.path("b64_json").asText(null);
        byte[] bytes;
        if (b64 != null && !b64.isBlank()) {
            bytes = Base64.getDecoder().decode(b64);
        } else {
            String imageUrl = item.path("url").asText(null);
            if (imageUrl == null || imageUrl.isBlank()) {
                throw new IllegalStateException("OpenAI image response missing b64_json/url");
            }
            bytes = downloadImageFromUrl(imageUrl, apiKey);
        }
        String revisedPrompt = item.path("revised_prompt").asText(null);
        String model = response.path("model").asText(null);
        String outputFormat = response.path("output_format").asText(null);
        String mimeType = resolveImageMimeType(outputFormat, request.format());
        return new OpenAiImageResult(bytes, mimeType, revisedPrompt, model);
    }

    public OpenAiVideoJob createVideoJob(String apiKey, OpenAiVideoRequest request) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("prompt", request.prompt());
        builder.part("model", request.model());
        if (request.seconds() != null && request.seconds() > 0) {
            builder.part("seconds", request.seconds().toString());
        }
        if (request.size() != null && !request.size().isBlank()) {
            builder.part("size", request.size());
        }

        RestClient.RequestBodySpec spec = restClient.post()
                .uri("/v1/videos")
                .contentType(MediaType.MULTIPART_FORM_DATA);
        if (hasApiKey(apiKey)) {
            spec = spec.header(HttpHeaders.AUTHORIZATION, bearer(apiKey));
        }
        JsonNode response = spec.body(builder.build())
                .retrieve()
                .body(JsonNode.class);

        if (response == null) {
            throw new IllegalStateException("OpenAI video response is empty");
        }
        return parseVideoJob(response);
    }

    public OpenAiVideoJob getVideoJob(String apiKey, String videoId) {
        RestClient.RequestHeadersSpec<?> spec = restClient.get()
                .uri("/v1/videos/{videoId}", videoId);
        if (hasApiKey(apiKey)) {
            spec = spec.header(HttpHeaders.AUTHORIZATION, bearer(apiKey));
        }
        JsonNode response = spec.retrieve().body(JsonNode.class);

        if (response == null) {
            throw new IllegalStateException("OpenAI video status is empty");
        }
        return parseVideoJob(response);
    }

    public byte[] downloadVideoContent(String apiKey, String videoId) {
        RestClient.RequestHeadersSpec<?> spec = restClient.get()
                .uri("/v1/videos/{videoId}/content", videoId);
        if (hasApiKey(apiKey)) {
            spec = spec.header(HttpHeaders.AUTHORIZATION, bearer(apiKey));
        }
        byte[] response = spec.retrieve().body(byte[].class);

        if (response == null || response.length == 0) {
            throw new IllegalStateException("OpenAI video content is empty");
        }
        return response;
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private boolean hasApiKey(String token) {
        return token != null && !token.isBlank();
    }

    private OpenAiVideoJob parseVideoJob(JsonNode response) {
        String id = response.path("id").asText(null);
        String status = response.path("status").asText(null);
        Integer progress = response.hasNonNull("progress") ? response.path("progress").asInt() : null;
        String model = response.path("model").asText(null);
        String error = response.path("error").path("message").asText(null);
        return new OpenAiVideoJob(id, status, progress, model, error);
    }

    private String resolveImageMimeType(String outputFormat, String requestedFormat) {
        String format = outputFormat != null && !outputFormat.isBlank()
                ? outputFormat
                : requestedFormat;
        if (format == null || format.isBlank()) {
            return "image/png";
        }
        return switch (format.toLowerCase()) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "webp" -> "image/webp";
            case "gif" -> "image/gif";
            default -> "image/png";
        };
    }

    private byte[] downloadImageFromUrl(String imageUrl, String apiKey) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(imageUrl))
                    .timeout(requestReadTimeout)
                    .GET();
            if (hasApiKey(apiKey)) {
                builder.header(HttpHeaders.AUTHORIZATION, bearer(apiKey));
            }
            HttpResponse<byte[]> response = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .build()
                    .send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() >= 400) {
                throw new IllegalStateException("Image download failed status=" + response.statusCode());
            }
            byte[] body = response.body();
            if (body == null || body.length == 0) {
                throw new IllegalStateException("Image download response is empty");
            }
            return body;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to download image bytes", ex);
        }
    }

    static boolean isRetryableTransportFailure(Throwable throwable) {
        return ProviderRetrySupport.isRetryableTransportFailure(throwable);
    }

    private static long positiveOrDefault(Long value, long fallback) {
        if (value == null || value <= 0) {
            return fallback;
        }
        return value;
    }
}
