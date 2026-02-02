package app.mnema.ai.provider.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Base64;

@Component
public class OpenAiClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public OpenAiClient(RestClient.Builder restClientBuilder,
                        OpenAiProps props,
                        ObjectMapper objectMapper) {
        this.restClient = restClientBuilder
                .baseUrl(props.baseUrl())
                .build();
        this.objectMapper = objectMapper;
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

        JsonNode response = restClient.post()
                .uri("/v1/responses")
                .header(HttpHeaders.AUTHORIZATION, bearer(apiKey))
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .body(JsonNode.class);

        if (response == null) {
            throw new IllegalStateException("OpenAI response is empty");
        }

        String outputText = OpenAiResponseParser.extractText(response);
        String model = response.path("model").asText(null);
        JsonNode usage = response.path("usage");
        Integer inputTokens = usage.hasNonNull("input_tokens") ? usage.get("input_tokens").asInt() : null;
        Integer outputTokens = usage.hasNonNull("output_tokens") ? usage.get("output_tokens").asInt() : null;
        return new OpenAiResponseResult(outputText, model, inputTokens, outputTokens, response);
    }

    public byte[] createSpeech(String apiKey, OpenAiSpeechRequest request) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("model", request.model());
        payload.put("input", request.input());
        payload.put("voice", request.voice());
        payload.put("response_format", request.responseFormat());

        byte[] response = restClient.post()
                .uri("/v1/audio/speech")
                .header(HttpHeaders.AUTHORIZATION, bearer(apiKey))
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .body(byte[].class);

        if (response == null || response.length == 0) {
            throw new IllegalStateException("OpenAI speech response is empty");
        }
        return response;
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
        payload.put("response_format", "b64_json");

        JsonNode response = restClient.post()
                .uri("/v1/images/generations")
                .header(HttpHeaders.AUTHORIZATION, bearer(apiKey))
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
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
        if (b64 == null || b64.isBlank()) {
            throw new IllegalStateException("OpenAI image response missing b64_json");
        }
        byte[] bytes = Base64.getDecoder().decode(b64);
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
        if (request.durationSeconds() != null && request.durationSeconds() > 0) {
            builder.part("duration", request.durationSeconds().toString());
        }
        if (request.resolution() != null && !request.resolution().isBlank()) {
            builder.part("resolution", request.resolution());
        }

        JsonNode response = restClient.post()
                .uri("/v1/videos")
                .header(HttpHeaders.AUTHORIZATION, bearer(apiKey))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(builder.build())
                .retrieve()
                .body(JsonNode.class);

        if (response == null) {
            throw new IllegalStateException("OpenAI video response is empty");
        }
        return parseVideoJob(response);
    }

    public OpenAiVideoJob getVideoJob(String apiKey, String videoId) {
        JsonNode response = restClient.get()
                .uri("/v1/videos/{videoId}", videoId)
                .header(HttpHeaders.AUTHORIZATION, bearer(apiKey))
                .retrieve()
                .body(JsonNode.class);

        if (response == null) {
            throw new IllegalStateException("OpenAI video status is empty");
        }
        return parseVideoJob(response);
    }

    public byte[] downloadVideoContent(String apiKey, String videoId) {
        byte[] response = restClient.get()
                .uri("/v1/videos/{videoId}/content", videoId)
                .header(HttpHeaders.AUTHORIZATION, bearer(apiKey))
                .retrieve()
                .body(byte[].class);

        if (response == null || response.length == 0) {
            throw new IllegalStateException("OpenAI video content is empty");
        }
        return response;
    }

    private String bearer(String token) {
        return "Bearer " + token;
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
}
