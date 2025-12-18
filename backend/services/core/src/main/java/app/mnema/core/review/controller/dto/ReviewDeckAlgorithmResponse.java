package app.mnema.core.review.controller.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;

public record ReviewDeckAlgorithmResponse(
        UUID userDeckId,
        String algorithmId,
        JsonNode algorithmParams,
        JsonNode effectiveAlgorithmParams,
        long activeCards,
        long trackedCards,
        long pendingMigrationCards
) {
}
