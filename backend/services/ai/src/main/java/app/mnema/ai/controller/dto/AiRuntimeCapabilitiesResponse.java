package app.mnema.ai.controller.dto;

import java.util.List;

public record AiRuntimeCapabilitiesResponse(
        String mode,
        String systemProvider,
        OllamaRuntimeResponse ollama,
        List<AiProviderCapability> providers
) {
    public record OllamaRuntimeResponse(
            boolean enabled,
            boolean available,
            String baseUrl,
            List<OllamaModelInfo> models
    ) {}

    public record OllamaModelInfo(
            String name,
            Long sizeBytes,
            String modifiedAt,
            List<String> capabilities
    ) {}

    public record AiProviderCapability(
            String key,
            String displayName,
            boolean requiresCredential,
            boolean text,
            boolean stt,
            boolean tts,
            boolean image,
            boolean video,
            boolean gif
    ) {}
}
