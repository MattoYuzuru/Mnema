package app.mnema.ai.provider.gemini;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class GeminiClient {
    
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public GeminiClient(RestClient.Builder restClientBuilder,
                        GeminiProps props,
                        ObjectMapper objectMapper) {
        this.restClient = restClientBuilder.baseUrl(props.baseUrl()).build();
        this.objectMapper = objectMapper;
    }

    public GeminiResponseResult createResponse(String apiKey, GeminiResponseRequest request) {
        ObjectNode payload = objectMapper.createObjectNode();
        ArrayNode contents = payload.putArray("contents");
        ObjectNode user = contents.addObject();
        user.put("role", "user");
        ArrayNode parts = user.putArray("parts");
        parts.addObject().put("text", request.input());

        ObjectNode generationConfig = payload.putObject("generationConfig");
        if (request.maxOutputTokens() != null && request.maxOutputTokens() > 0) {
            generationConfig.put("maxOutputTokens", request.maxOutputTokens());
        }
        if (request.responseMimeType() != null && !request.responseMimeType().isBlank()) {
            generationConfig.put("responseMimeType", request.responseMimeType());
        }
        if (request.responseSchema() != null && !request.responseSchema().isNull()) {
            generationConfig.set("responseSchema", request.responseSchema());
        }

        JsonNode response = restClient.post()
                .uri("/v1beta/models/{model}:generateContent", request.model())
                .header("x-goog-api-key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .body(JsonNode.class);

        if (response == null) {
            throw new IllegalStateException("Gemini response is empty");
        }

        String outputText = GeminiResponseParser.extractText(response);
        String model = response.path("modelVersion").asText(null);
        if (model == null || model.isBlank()) {
            model = response.path("model").asText(null);
        }
        JsonNode usage = response.path("usageMetadata");
        Integer inputTokens = usage.hasNonNull("promptTokenCount") ? usage.get("promptTokenCount").asInt() : null;
        Integer outputTokens = usage.hasNonNull("candidatesTokenCount") ? usage.get("candidatesTokenCount").asInt() : null;
        return new GeminiResponseResult(outputText, model, inputTokens, outputTokens, response);
    }

    public GeminiResponseParser.AudioResult createSpeech(String apiKey, GeminiSpeechRequest request) {
        ObjectNode payload = objectMapper.createObjectNode();
        ArrayNode contents = payload.putArray("contents");
        ObjectNode user = contents.addObject();
        user.put("role", "user");
        ArrayNode parts = user.putArray("parts");
        parts.addObject().put("text", request.input());

        ObjectNode generationConfig = payload.putObject("generationConfig");
        ArrayNode modalities = generationConfig.putArray("responseModalities");
        modalities.add("AUDIO");
        if (request.voice() != null && !request.voice().isBlank()) {
            ObjectNode speechConfig = generationConfig.putObject("speechConfig");
            ObjectNode voiceConfig = speechConfig.putObject("voiceConfig");
            ObjectNode prebuilt = voiceConfig.putObject("prebuiltVoiceConfig");
            prebuilt.put("voiceName", request.voice());
        }

        JsonNode response = restClient.post()
                .uri("/v1beta/models/{model}:generateContent", request.model())
                .header("x-goog-api-key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .body(JsonNode.class);

        if (response == null) {
            throw new IllegalStateException("Gemini speech response is empty");
        }

        GeminiResponseParser.AudioResult audio = GeminiResponseParser.extractAudio(response);
        if (audio == null || audio.data() == null || audio.data().length == 0) {
            throw new IllegalStateException("Gemini speech response is empty");
        }
        return audio;
    }

    public GeminiImageResult createImage(String apiKey, GeminiImageRequest request) {
        ObjectNode payload = objectMapper.createObjectNode();
        ArrayNode contents = payload.putArray("contents");
        ObjectNode user = contents.addObject();
        user.put("role", "user");
        ArrayNode parts = user.putArray("parts");
        parts.addObject().put("text", request.prompt());

        ObjectNode generationConfig = payload.putObject("generationConfig");
        ArrayNode modalities = generationConfig.putArray("responseModalities");
        modalities.add("IMAGE");

        JsonNode response = restClient.post()
                .uri("/v1beta/models/{model}:generateContent", request.model())
                .header("x-goog-api-key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .body(JsonNode.class);

        if (response == null) {
            throw new IllegalStateException("Gemini image response is empty");
        }

        GeminiResponseParser.InlineDataResult inline = GeminiResponseParser.extractInlineData(response);
        if (inline == null || inline.data() == null || inline.data().length == 0) {
            throw new IllegalStateException("Gemini image response is empty");
        }
        String model = response.path("modelVersion").asText(null);
        if (model == null || model.isBlank()) {
            model = response.path("model").asText(null);
        }
        return new GeminiImageResult(inline.data(), inline.mimeType(), model);
    }

}
