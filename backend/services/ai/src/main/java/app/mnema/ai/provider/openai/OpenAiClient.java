package app.mnema.ai.provider.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

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

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
