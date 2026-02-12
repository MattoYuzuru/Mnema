package app.mnema.ai.provider.qwen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Base64;

@Component
public class QwenClient {

    private final RestClient compatibleClient;
    private final RestClient dashscopeClient;
    private final ObjectMapper objectMapper;

    public QwenClient(RestClient.Builder restClientBuilder,
                      QwenProps props,
                      ObjectMapper objectMapper) {
        this.compatibleClient = restClientBuilder
                .baseUrl(props.baseUrl())
                .build();
        this.dashscopeClient = restClientBuilder
                .baseUrl(props.dashscopeBaseUrl())
                .build();
        this.objectMapper = objectMapper;
    }

    public QwenChatResult createChatCompletion(String apiKey, QwenChatRequest request) {
        ArrayNode messages = objectMapper.createArrayNode();
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", request.input());
        return createChatCompletionWithMessages(apiKey, request.model(), messages, request.maxOutputTokens(), request.responseFormat());
    }

    public QwenChatResult createChatCompletionWithMessages(String apiKey,
                                                           String model,
                                                           JsonNode messages,
                                                           Integer maxOutputTokens,
                                                           JsonNode responseFormat) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("model", model);
        payload.set("messages", messages);
        if (maxOutputTokens != null && maxOutputTokens > 0) {
            payload.put("max_tokens", maxOutputTokens);
        }
        if (responseFormat != null && !responseFormat.isNull()) {
            payload.set("response_format", normalizeResponseFormat(responseFormat));
        }

        JsonNode response = compatibleClient.post()
                .uri("/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, bearer(apiKey))
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .body(JsonNode.class);

        if (response == null) {
            throw new IllegalStateException("Qwen response is empty");
        }

        String outputText = extractChatText(response);
        String responseModel = response.path("model").asText(null);
        JsonNode usage = response.path("usage");
        Integer inputTokens = usage.hasNonNull("prompt_tokens") ? usage.get("prompt_tokens").asInt() : null;
        Integer outputTokens = usage.hasNonNull("completion_tokens") ? usage.get("completion_tokens").asInt() : null;
        return new QwenChatResult(outputText, responseModel, inputTokens, outputTokens, response);
    }

    public QwenSpeechResult createSpeech(String apiKey, QwenSpeechRequest request) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("model", request.model());
        ObjectNode input = payload.putObject("input");
        input.put("text", request.input());
        if (request.voice() != null && !request.voice().isBlank()) {
            input.put("voice", request.voice());
        }
        if (request.languageType() != null && !request.languageType().isBlank()) {
            input.put("language_type", request.languageType());
        }

        JsonNode response = dashscopeClient.post()
                .uri("/api/v1/services/aigc/multimodal-generation/generation")
                .header(HttpHeaders.AUTHORIZATION, bearer(apiKey))
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .body(JsonNode.class);

        if (response == null) {
            throw new IllegalStateException("Qwen speech response is empty");
        }

        JsonNode audio = response.path("output").path("audio");
        String audioData = audio.path("data").asText(null);
        if (audioData != null && !audioData.isBlank()) {
            byte[] decoded = Base64.getDecoder().decode(audioData);
            return new QwenSpeechResult(decoded, "audio/wav");
        }
        String audioUrl = audio.path("url").asText(null);
        if (audioUrl == null || audioUrl.isBlank()) {
            throw new IllegalStateException("Qwen speech response missing audio");
        }
        byte[] bytes = downloadUrl(audioUrl);
        String mimeType = resolveAudioMimeType(audioUrl);
        return new QwenSpeechResult(bytes, mimeType);
    }

    public QwenImageResult createImage(String apiKey, QwenImageRequest request) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("model", request.model());
        ObjectNode input = payload.putObject("input");
        ArrayNode messages = input.putArray("messages");
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        ArrayNode content = user.putArray("content");
        content.addObject().put("text", request.prompt());

        if (request.size() != null && !request.size().isBlank()) {
            ObjectNode parameters = payload.putObject("parameters");
            parameters.put("size", request.size());
        }

        JsonNode response = dashscopeClient.post()
                .uri("/api/v1/services/aigc/multimodal-generation/generation")
                .header(HttpHeaders.AUTHORIZATION, bearer(apiKey))
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .body(JsonNode.class);

        if (response == null) {
            throw new IllegalStateException("Qwen image response is empty");
        }

        JsonNode imageNode = response.path("output")
                .path("choices")
                .path(0)
                .path("message")
                .path("content")
                .path(0)
                .path("image");
        String imageUrl = imageNode.asText(null);
        if (imageUrl == null || imageUrl.isBlank()) {
            throw new IllegalStateException("Qwen image response missing image");
        }
        byte[] bytes = downloadUrl(imageUrl);
        String mimeType = resolveImageMimeType(imageUrl);
        String model = response.path("model").asText(null);
        return new QwenImageResult(bytes, mimeType, model);
    }

    public QwenVideoJob createVideoJob(String apiKey, QwenVideoRequest request) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("model", request.model());
        ObjectNode input = payload.putObject("input");
        input.put("prompt", request.prompt());
        ObjectNode parameters = payload.putObject("parameters");
        if (request.durationSeconds() != null && request.durationSeconds() > 0) {
            parameters.put("duration", request.durationSeconds());
        }
        if (request.size() != null && !request.size().isBlank()) {
            parameters.put("size", request.size());
        }

        JsonNode response = dashscopeClient.post()
                .uri("/api/v1/services/aigc/video-generation/video-synthesis")
                .header(HttpHeaders.AUTHORIZATION, bearer(apiKey))
                .header("X-DashScope-Async", "enable")
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .body(JsonNode.class);

        if (response == null) {
            throw new IllegalStateException("Qwen video response is empty");
        }
        String taskId = response.path("output").path("task_id").asText(null);
        String status = response.path("output").path("task_status").asText(null);
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalStateException("Qwen video task id is missing");
        }
        return new QwenVideoJob(taskId, status, request.model(), null, null);
    }

    public QwenVideoJob getVideoJob(String apiKey, String taskId) {
        JsonNode response = dashscopeClient.get()
                .uri("/api/v1/tasks/{taskId}", taskId)
                .header(HttpHeaders.AUTHORIZATION, bearer(apiKey))
                .retrieve()
                .body(JsonNode.class);

        if (response == null) {
            throw new IllegalStateException("Qwen video status is empty");
        }
        JsonNode output = response.path("output");
        String status = output.path("task_status").asText(null);
        String videoUrl = output.path("video_url").asText(null);
        return new QwenVideoJob(taskId, status, null, videoUrl, null);
    }

    public byte[] downloadUrl(String url) {
        byte[] response = dashscopeClient.get()
                .uri(url)
                .retrieve()
                .body(byte[].class);
        if (response == null || response.length == 0) {
            throw new IllegalStateException("Qwen media download is empty");
        }
        return response;
    }

    private String extractChatText(JsonNode response) {
        JsonNode choices = response.path("choices");
        if (choices.isArray() && !choices.isEmpty()) {
            JsonNode message = choices.get(0).path("message");
            JsonNode content = message.get("content");
            if (content == null) {
                return "";
            }
            if (content.isTextual()) {
                return content.asText();
            }
            if (content.isArray()) {
                StringBuilder builder = new StringBuilder();
                for (JsonNode part : content) {
                    String text = part.path("text").asText(null);
                    if (text == null || text.isBlank()) {
                        continue;
                    }
                    if (!builder.isEmpty()) {
                        builder.append('\n');
                    }
                    builder.append(text);
                }
                return builder.toString();
            }
        }
        return "";
    }

    private JsonNode normalizeResponseFormat(JsonNode format) {
        if (format == null || format.isNull()) {
            return format;
        }
        if (format.has("json_schema")) {
            return format;
        }
        if (!format.has("type")) {
            return format;
        }
        String type = format.path("type").asText(null);
        if (!"json_schema".equals(type)) {
            return format;
        }
        ObjectNode normalized = objectMapper.createObjectNode();
        normalized.put("type", "json_schema");
        ObjectNode jsonSchema = normalized.putObject("json_schema");
        if (format.hasNonNull("name")) {
            jsonSchema.put("name", format.path("name").asText());
        }
        if (format.has("schema")) {
            jsonSchema.set("schema", format.get("schema"));
        }
        if (format.has("strict")) {
            jsonSchema.set("strict", format.get("strict"));
        }
        return normalized;
    }

    private String resolveImageMimeType(String url) {
        if (url == null) {
            return "image/png";
        }
        String lower = url.toLowerCase();
        if (lower.contains(".jpg") || lower.contains(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.contains(".webp")) {
            return "image/webp";
        }
        return "image/png";
    }

    private String resolveAudioMimeType(String url) {
        if (url == null) {
            return "audio/wav";
        }
        String lower = url.toLowerCase();
        if (lower.contains(".mp3")) {
            return "audio/mpeg";
        }
        if (lower.contains(".ogg")) {
            return "audio/ogg";
        }
        if (lower.contains(".wav")) {
            return "audio/wav";
        }
        return "audio/wav";
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
