package app.mnema.ai.service;

import app.mnema.ai.config.AiRuntimeProps;
import app.mnema.ai.controller.dto.AiRuntimeCapabilitiesResponse;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class AiRuntimeService {

    private final AiRuntimeProps props;
    private final RestClient ollamaClient;
    private final RestClient openAiClient;

    public AiRuntimeService(AiRuntimeProps props, RestClient.Builder restClientBuilder) {
        this.props = props;
        this.ollamaClient = restClientBuilder.baseUrl(props.ollamaBaseUrl()).build();
        this.openAiClient = restClientBuilder.baseUrl(props.openaiBaseUrl()).build();
    }

    public AiRuntimeCapabilitiesResponse getCapabilities() {
        RuntimeDiscovery discovery = discoverRuntime();

        List<AiRuntimeCapabilitiesResponse.AiProviderCapability> providers = List.of(
                new AiRuntimeCapabilitiesResponse.AiProviderCapability(
                        "ollama",
                        "Ollama (local)",
                        false,
                        true,
                        discovery.hasCapability("stt"),
                        discovery.hasCapability("tts"),
                        discovery.hasCapability("image"),
                        discovery.hasCapability("video"),
                        discovery.hasCapability("gif")
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
                        discovery.available(),
                        props.ollamaBaseUrl(),
                        discovery.models(),
                        discovery.voices()
                ),
                providers
        );
    }

    private RuntimeDiscovery discoverRuntime() {
        Map<String, AiRuntimeCapabilitiesResponse.OllamaModelInfo> merged = new LinkedHashMap<>();
        boolean available = false;

        DiscoveryResult ollamaResult = discoverOllamaModels();
        available = available || ollamaResult.available();
        mergeModels(merged, ollamaResult.models());

        DiscoveryResult openAiResult = discoverOpenAiModels();
        available = available || openAiResult.available();
        mergeModels(merged, openAiResult.models());

        List<String> voices = discoverVoices();

        if (!voices.isEmpty()) {
            available = true;
        }

        List<AiRuntimeCapabilitiesResponse.OllamaModelInfo> models = merged.values().stream()
                .sorted(Comparator.comparing(AiRuntimeCapabilitiesResponse.OllamaModelInfo::name, String.CASE_INSENSITIVE_ORDER))
                .toList();

        return new RuntimeDiscovery(available, models, voices);
    }

    private void mergeModels(Map<String, AiRuntimeCapabilitiesResponse.OllamaModelInfo> merged,
                             List<AiRuntimeCapabilitiesResponse.OllamaModelInfo> models) {
        for (AiRuntimeCapabilitiesResponse.OllamaModelInfo model : models) {
            AiRuntimeCapabilitiesResponse.OllamaModelInfo existing = merged.get(model.name());
            if (existing == null) {
                merged.put(model.name(), model);
                continue;
            }

            Set<String> capabilities = new LinkedHashSet<>(existing.capabilities());
            capabilities.addAll(model.capabilities());

            Long size = existing.sizeBytes() != null ? existing.sizeBytes() : model.sizeBytes();
            String modifiedAt = existing.modifiedAt() != null ? existing.modifiedAt() : model.modifiedAt();

            merged.put(
                    model.name(),
                    new AiRuntimeCapabilitiesResponse.OllamaModelInfo(
                            model.name(),
                            size,
                            modifiedAt,
                            capabilities.stream().sorted().toList()
                    )
            );
        }
    }

    private DiscoveryResult discoverOllamaModels() {
        if (!props.ollamaEnabled()) {
            return DiscoveryResult.empty();
        }

        try {
            JsonNode response = ollamaClient.get()
                    .uri("/api/tags")
                    .retrieve()
                    .body(JsonNode.class);

            if (response == null || !response.has("models") || !response.get("models").isArray()) {
                return DiscoveryResult.empty();
            }

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
                        inferModelCapabilities(name, false)
                ));
            }
            return new DiscoveryResult(true, models);
        } catch (RestClientException ex) {
            return DiscoveryResult.empty();
        }
    }

    private DiscoveryResult discoverOpenAiModels() {
        try {
            JsonNode response = openAiClient.get()
                    .uri("/v1/models")
                    .retrieve()
                    .body(JsonNode.class);

            if (response == null || !response.has("data") || !response.get("data").isArray()) {
                return DiscoveryResult.empty();
            }

            List<AiRuntimeCapabilitiesResponse.OllamaModelInfo> models = new ArrayList<>();
            for (JsonNode model : response.get("data")) {
                String name = text(model, "id");
                if (name == null || name.isBlank()) {
                    continue;
                }

                Set<String> capabilities = new LinkedHashSet<>(inferModelCapabilities(name, true));
                JsonNode metadata = model.path("metadata");
                if (metadata.has("capabilities") && metadata.get("capabilities").isArray()) {
                    for (JsonNode capNode : metadata.get("capabilities")) {
                        String cap = capNode.asText(null);
                        if (cap != null && !cap.isBlank()) {
                            capabilities.add(cap.trim().toLowerCase(Locale.ROOT));
                        }
                    }
                }

                models.add(new AiRuntimeCapabilitiesResponse.OllamaModelInfo(
                        name,
                        null,
                        null,
                        capabilities.stream().sorted().toList()
                ));
            }
            return new DiscoveryResult(true, models);
        } catch (RestClientException ex) {
            return DiscoveryResult.empty();
        }
    }

    private List<String> discoverVoices() {
        try {
            JsonNode response = openAiClient.get()
                    .uri("/v1/audio/voices")
                    .retrieve()
                    .body(JsonNode.class);

            if (response == null || !response.has("data") || !response.get("data").isArray()) {
                return List.of();
            }

            Set<String> voices = new LinkedHashSet<>();
            for (JsonNode item : response.get("data")) {
                String id = text(item, "id");
                if (id == null || id.isBlank()) {
                    continue;
                }
                voices.add(id);
            }
            return voices.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
        } catch (RestClientException ex) {
            return List.of();
        }
    }

    private String text(JsonNode node, String key) {
        JsonNode value = node.path(key);
        if (value.isTextual()) {
            String text = value.asText().trim();
            return text.isEmpty() ? null : text;
        }
        return null;
    }

    private List<String> inferModelCapabilities(String modelName, boolean includeAudioCapabilities) {
        String normalized = modelName.toLowerCase(Locale.ROOT);
        List<String> capabilities = new ArrayList<>();
        capabilities.add("text");

        if (normalized.contains("vision")
                || normalized.contains("vl")
                || normalized.contains("llava")
                || normalized.contains("moondream")
                || normalized.contains("minicpm-v")) {
            capabilities.add("vision");
        }
        if (normalized.contains("sd")
                || normalized.contains("flux")
                || normalized.contains("image")) {
            capabilities.add("image");
        }
        if (includeAudioCapabilities) {
            if (normalized.contains("whisper") || normalized.contains("asr") || normalized.contains("stt")) {
                capabilities.add("stt");
            }
            if (normalized.contains("tts")
                    || normalized.contains("voice")
                    || normalized.contains("kokoro")
                    || normalized.contains("orpheus")
                    || normalized.contains("piper")) {
                capabilities.add("tts");
            }
        }
        if (normalized.contains("video") || normalized.contains("t2v") || normalized.contains("wan") || normalized.contains("sora")) {
            capabilities.add("video");
        }
        if (normalized.contains("gif")) {
            capabilities.add("gif");
        }
        return capabilities;
    }

    private record DiscoveryResult(boolean available, List<AiRuntimeCapabilitiesResponse.OllamaModelInfo> models) {
        static DiscoveryResult empty() {
            return new DiscoveryResult(false, List.of());
        }
    }

    private record RuntimeDiscovery(
            boolean available,
            List<AiRuntimeCapabilitiesResponse.OllamaModelInfo> models,
            List<String> voices
    ) {
        boolean hasCapability(String capability) {
            return models.stream().anyMatch(model -> model.capabilities().contains(capability));
        }
    }
}
