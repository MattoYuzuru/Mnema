package app.mnema.ai.service;

import app.mnema.ai.config.AiRuntimeProps;
import app.mnema.ai.controller.dto.AiRuntimeCapabilitiesResponse;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class AiRuntimeService {

    private final AiRuntimeProps props;
    private final RestClient restClient;

    public AiRuntimeService(AiRuntimeProps props, RestClient.Builder restClientBuilder) {
        this.props = props;
        this.restClient = restClientBuilder.baseUrl(props.ollamaBaseUrl()).build();
    }

    public AiRuntimeCapabilitiesResponse getCapabilities() {
        List<AiRuntimeCapabilitiesResponse.OllamaModelInfo> ollamaModels = List.of();
        boolean ollamaAvailable = false;
        if (props.ollamaEnabled()) {
            try {
                JsonNode response = restClient.get()
                        .uri("/api/tags")
                        .retrieve()
                        .body(JsonNode.class);
                if (response != null && response.has("models") && response.get("models").isArray()) {
                    ollamaAvailable = true;
                    List<AiRuntimeCapabilitiesResponse.OllamaModelInfo> models = new ArrayList<>();
                    for (JsonNode model : response.get("models")) {
                        String name = text(model, "name");
                        if (name == null || name.isBlank()) {
                            continue;
                        }
                        Long sizeBytes = model.hasNonNull("size") ? model.path("size").asLong() : null;
                        String modifiedAt = text(model, "modified_at");
                        models.add(new AiRuntimeCapabilitiesResponse.OllamaModelInfo(
                                name,
                                sizeBytes,
                                modifiedAt,
                                inferModelCapabilities(name)
                        ));
                    }
                    ollamaModels = models;
                }
            } catch (RestClientException ex) {
                ollamaAvailable = false;
            }
        }

        List<AiRuntimeCapabilitiesResponse.AiProviderCapability> providers = List.of(
                new AiRuntimeCapabilitiesResponse.AiProviderCapability(
                        "ollama",
                        "Ollama (local)",
                        false,
                        true,
                        false,
                        false,
                        true,
                        false,
                        false
                ),
                new AiRuntimeCapabilitiesResponse.AiProviderCapability(
                        "openai",
                        "OpenAI-compatible",
                        true,
                        true,
                        true,
                        true,
                        true,
                        true,
                        true
                ),
                new AiRuntimeCapabilitiesResponse.AiProviderCapability(
                        "qwen",
                        "Qwen/DashScope",
                        true,
                        true,
                        true,
                        true,
                        true,
                        true,
                        true
                ),
                new AiRuntimeCapabilitiesResponse.AiProviderCapability(
                        "grok",
                        "Grok",
                        true,
                        true,
                        true,
                        true,
                        true,
                        true,
                        true
                )
        );

        return new AiRuntimeCapabilitiesResponse(
                props.systemManagedProviderEnabled() ? "system_managed" : "user_keys",
                props.systemProviderName(),
                new AiRuntimeCapabilitiesResponse.OllamaRuntimeResponse(
                        props.ollamaEnabled(),
                        ollamaAvailable,
                        props.ollamaBaseUrl(),
                        ollamaModels
                ),
                providers
        );
    }

    private String text(JsonNode node, String key) {
        JsonNode value = node.path(key);
        if (value.isTextual()) {
            String text = value.asText().trim();
            return text.isEmpty() ? null : text;
        }
        return null;
    }

    private List<String> inferModelCapabilities(String modelName) {
        String normalized = modelName.toLowerCase(Locale.ROOT);
        List<String> capabilities = new ArrayList<>();
        capabilities.add("text");

        if (normalized.contains("vision")
                || normalized.contains("vl")
                || normalized.contains("llava")
                || normalized.contains("moondream")) {
            capabilities.add("vision");
        }
        if (normalized.contains("sd")
                || normalized.contains("flux")
                || normalized.contains("image")) {
            capabilities.add("image");
        }
        if (normalized.contains("whisper") || normalized.contains("asr")) {
            capabilities.add("stt");
        }
        if (normalized.contains("tts") || normalized.contains("voice")) {
            capabilities.add("tts");
        }
        if (normalized.contains("video") || normalized.contains("t2v")) {
            capabilities.add("video");
        }
        return capabilities;
    }
}
