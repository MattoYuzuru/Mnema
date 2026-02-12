package app.mnema.ai.provider.grok;

import app.mnema.ai.provider.openai.OpenAiResponseParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Base64;

@Component
public class GrokClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public GrokClient(RestClient.Builder restClientBuilder,
                      GrokProps props,
                      ObjectMapper objectMapper) {
        this.restClient = restClientBuilder
                .baseUrl(props.baseUrl())
                .build();
        this.objectMapper = objectMapper;
    }

    public GrokResponseResult createResponse(String apiKey, GrokResponseRequest request) {
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
            throw new IllegalStateException("Grok response is empty");
        }

        String outputText = OpenAiResponseParser.extractText(response);
        String model = response.path("model").asText(null);
        JsonNode usage = response.path("usage");
        Integer inputTokens = usage.hasNonNull("input_tokens") ? usage.get("input_tokens").asInt() : null;
        Integer outputTokens = usage.hasNonNull("output_tokens") ? usage.get("output_tokens").asInt() : null;
        return new GrokResponseResult(outputText, model, inputTokens, outputTokens, response);
    }

    public GrokResponseResult createResponseWithInput(String apiKey,
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

        JsonNode response = restClient.post()
                .uri("/v1/responses")
                .header(HttpHeaders.AUTHORIZATION, bearer(apiKey))
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .body(JsonNode.class);

        if (response == null) {
            throw new IllegalStateException("Grok response is empty");
        }

        String outputText = OpenAiResponseParser.extractText(response);
        String responseModel = response.path("model").asText(null);
        JsonNode usage = response.path("usage");
        Integer inputTokens = usage.hasNonNull("input_tokens") ? usage.get("input_tokens").asInt() : null;
        Integer outputTokens = usage.hasNonNull("output_tokens") ? usage.get("output_tokens").asInt() : null;
        return new GrokResponseResult(outputText, responseModel, inputTokens, outputTokens, response);
    }

    public byte[] createSpeech(String apiKey, GrokSpeechRequest request) {
        throw new IllegalStateException("Grok provider does not support TTS");
    }

    public String createTranscription(String apiKey, GrokTranscriptionRequest request) {
        throw new IllegalStateException("Grok provider does not support STT");
    }

    public GrokImageResult createImage(String apiKey, GrokImageRequest request) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("model", request.model());
        payload.put("prompt", request.prompt());
        if (request.aspectRatio() != null && !request.aspectRatio().isBlank()) {
            payload.put("aspect_ratio", request.aspectRatio());
        }
        if (request.resolution() != null && !request.resolution().isBlank()) {
            payload.put("resolution", request.resolution());
        }
        if (request.responseFormat() != null && !request.responseFormat().isBlank()) {
            payload.put("response_format", request.responseFormat());
        }

        JsonNode response = restClient.post()
                .uri("/v1/images/generations")
                .header(HttpHeaders.AUTHORIZATION, bearer(apiKey))
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .body(JsonNode.class);

        if (response == null) {
            throw new IllegalStateException("Grok image response is empty");
        }

        JsonNode dataNode = response.path("data");
        if (!dataNode.isArray() || dataNode.isEmpty()) {
            throw new IllegalStateException("Grok image response missing data");
        }
        JsonNode item = dataNode.get(0);
        String b64 = item.path("b64_json").asText(null);
        byte[] bytes;
        String mimeType = "image/jpeg";
        if (b64 != null && !b64.isBlank()) {
            bytes = Base64.getDecoder().decode(b64);
        } else {
            String url = item.path("url").asText(null);
            if (url == null || url.isBlank()) {
                throw new IllegalStateException("Grok image response missing image data");
            }
            bytes = downloadUrl(url);
            mimeType = resolveImageMimeType(url);
        }
        String revisedPrompt = item.path("revised_prompt").asText(null);
        String model = response.path("model").asText(null);
        return new GrokImageResult(bytes, mimeType, revisedPrompt, model);
    }

    public GrokVideoJob createVideoJob(String apiKey, GrokVideoRequest request) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("model", request.model());
        payload.put("prompt", request.prompt());
        if (request.durationSeconds() != null && request.durationSeconds() > 0) {
            payload.put("duration", request.durationSeconds());
        }
        if (request.size() != null && !request.size().isBlank()) {
            payload.put("size", request.size());
        }

        JsonNode response = restClient.post()
                .uri("/v1/videos/generations")
                .header(HttpHeaders.AUTHORIZATION, bearer(apiKey))
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .body(JsonNode.class);

        if (response == null) {
            throw new IllegalStateException("Grok video response is empty");
        }
        String requestId = response.path("request_id").asText(null);
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalStateException("Grok video request id is missing");
        }
        return new GrokVideoJob(requestId, "pending", request.model(), null, null);
    }

    public GrokVideoJob getVideoJob(String apiKey, String requestId) {
        JsonNode response = restClient.get()
                .uri("/v1/videos/{requestId}", requestId)
                .header(HttpHeaders.AUTHORIZATION, bearer(apiKey))
                .retrieve()
                .body(JsonNode.class);

        if (response == null) {
            throw new IllegalStateException("Grok video status is empty");
        }
        String status = response.path("status").asText(null);
        JsonNode responseNode = response.path("response");
        String model = responseNode.path("model").asText(null);
        String videoUrl = responseNode.path("video").path("url").asText(null);
        return new GrokVideoJob(requestId, status, model, videoUrl, null);
    }

    public byte[] downloadUrl(String url) {
        byte[] response = restClient.get()
                .uri(url)
                .retrieve()
                .body(byte[].class);
        if (response == null || response.length == 0) {
            throw new IllegalStateException("Grok media download is empty");
        }
        return response;
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private String resolveImageMimeType(String url) {
        if (url == null) {
            return "image/jpeg";
        }
        String lower = url.toLowerCase();
        if (lower.contains(".png")) {
            return "image/png";
        }
        if (lower.contains(".webp")) {
            return "image/webp";
        }
        if (lower.contains(".jpg") || lower.contains(".jpeg")) {
            return "image/jpeg";
        }
        return "image/jpeg";
    }
}
