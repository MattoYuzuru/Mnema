package app.mnema.ai.controller.dto;

import app.mnema.ai.domain.type.AiJobType;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AiJobPreflightResponse(
        UUID deckId,
        AiJobType type,
        UUID providerCredentialId,
        String provider,
        String providerAlias,
        String model,
        String mode,
        JsonNode normalizedParams,
        String summary,
        Integer targetCount,
        List<String> fields,
        List<String> plannedStages,
        List<String> warnings,
        List<AiJobPreflightItemResponse> items,
        AiJobCostResponse cost,
        Integer estimatedSecondsRemaining,
        Instant estimatedCompletionAt,
        Integer queueAhead
) {
}
