package app.mnema.ai.controller.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateAiProviderRequest(
        @NotBlank String provider,
        String alias,
        @NotBlank String secret
) {
}
