package app.mnema.ai.controller.dto;

import jakarta.validation.constraints.NotNull;

public record UpdateAiProviderStatusRequest(
        @NotNull Boolean active
) {
}
