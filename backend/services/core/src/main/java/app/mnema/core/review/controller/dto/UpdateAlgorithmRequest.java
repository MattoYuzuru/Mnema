package app.mnema.core.review.controller.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record UpdateAlgorithmRequest(
        String algorithmId,
        JsonNode algorithmParams,
        ReviewPreferencesDto reviewPreferences
) {
}
