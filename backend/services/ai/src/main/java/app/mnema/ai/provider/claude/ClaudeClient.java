package app.mnema.ai.provider.claude;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class ClaudeClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final ClaudeProps props;

    public ClaudeClient(RestClient.Builder restClientBuilder,
                        ClaudeProps props,
                        ObjectMapper objectMapper) {
        this.restClient = restClientBuilder.baseUrl(props.baseUrl()).build();
        this.objectMapper = objectMapper;
        this.props = props;
    }

    public ClaudeResponseResult createMessage(String apiKey, ClaudeMessageRequest request) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("model", request.model());
        if (request.maxOutputTokens() != null && request.maxOutputTokens() > 0) {
            payload.put("max_tokens", request.maxOutputTokens());
        }

        ArrayNode messages = payload.putArray("messages");
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        ArrayNode content = user.putArray("content");
        ObjectNode text = content.addObject();
        text.put("type", "text");
        text.put("text", request.input());

        JsonNode response = restClient.post()
                .uri("/v1/messages")
                .header("x-api-key", apiKey)
                .header("anthropic-version", props.apiVersion())
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .body(JsonNode.class);

        if (response == null) {
            throw new IllegalStateException("Claude response is empty");
        }

        String outputText = ClaudeResponseParser.extractText(response);
        String model = response.path("model").asText(null);
        JsonNode usage = response.path("usage");
        Integer inputTokens = usage.hasNonNull("input_tokens") ? usage.get("input_tokens").asInt() : null;
        Integer outputTokens = usage.hasNonNull("output_tokens") ? usage.get("output_tokens").asInt() : null;
        return new ClaudeResponseResult(outputText, model, inputTokens, outputTokens, response);
    }
}
