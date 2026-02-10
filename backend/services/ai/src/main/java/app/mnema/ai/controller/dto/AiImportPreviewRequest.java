package app.mnema.ai.controller.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record AiImportPreviewRequest(
        @NotNull UUID requestId,
        @NotNull UUID deckId,
        @NotNull UUID sourceMediaId,
        UUID providerCredentialId,
        String model,
        String sourceType,
        String encoding,
        String language,
        String instructions
) {
}
