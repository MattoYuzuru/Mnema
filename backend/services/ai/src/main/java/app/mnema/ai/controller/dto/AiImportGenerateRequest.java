package app.mnema.ai.controller.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;
import java.util.UUID;

public record AiImportGenerateRequest(
        @NotNull UUID requestId,
        @NotNull UUID deckId,
        @NotNull UUID sourceMediaId,
        @NotEmpty List<String> fields,
        @NotNull @Positive Integer count,
        UUID providerCredentialId,
        String provider,
        String model,
        String sourceType,
        String encoding,
        String language,
        String instructions,
        JsonNode stt,
        JsonNode tts,
        JsonNode image,
        JsonNode video
) {
}
