package app.mnema.ai.controller.dto;

import app.mnema.ai.domain.type.AiJobStatus;
import app.mnema.ai.domain.type.AiJobType;

import java.time.Instant;
import java.util.UUID;

public record AiJobResponse(
        UUID jobId,
        UUID requestId,
        UUID deckId,
        AiJobType type,
        AiJobStatus status,
        Integer progress,
        Instant createdAt,
        Instant updatedAt,
        Instant startedAt,
        Instant completedAt,
        String errorMessage,
        UUID providerCredentialId,
        String provider,
        String providerAlias,
        String model
) {
}
