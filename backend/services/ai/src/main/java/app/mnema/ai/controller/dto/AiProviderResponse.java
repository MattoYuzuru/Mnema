package app.mnema.ai.controller.dto;

import app.mnema.ai.domain.type.AiProviderStatus;

import java.time.Instant;
import java.util.UUID;

public record AiProviderResponse(
        UUID id,
        String provider,
        String alias,
        AiProviderStatus status,
        Instant createdAt,
        Instant lastUsedAt,
        Instant updatedAt
) {
}
