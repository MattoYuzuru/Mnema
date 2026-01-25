package app.mnema.ai.service;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;

public record AiJobProcessingResult(
        JsonNode resultSummary,
        String provider,
        String model,
        Integer tokensIn,
        Integer tokensOut,
        BigDecimal costEstimate,
        String promptHash
) {
}
