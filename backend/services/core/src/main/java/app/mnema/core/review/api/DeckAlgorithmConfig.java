package app.mnema.core.review.api;

import com.fasterxml.jackson.databind.JsonNode;

public record DeckAlgorithmConfig(String algorithmId, JsonNode algorithmParams) {
}
