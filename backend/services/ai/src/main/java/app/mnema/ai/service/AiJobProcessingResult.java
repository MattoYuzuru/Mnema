package app.mnema.ai.service;

import app.mnema.ai.domain.type.AiJobStatus;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;

public record AiJobProcessingResult(
        JsonNode resultSummary,
        String provider,
        String model,
        Integer tokensIn,
        Integer tokensOut,
        BigDecimal costEstimate,
        String promptHash,
        AiJobStatus finalStatus,
        JsonNode usageDetails
) {
    public AiJobProcessingResult(
            JsonNode resultSummary,
            String provider,
            String model,
            Integer tokensIn,
            Integer tokensOut,
            BigDecimal costEstimate,
            String promptHash
    ) {
        this(resultSummary, provider, model, tokensIn, tokensOut, costEstimate, promptHash, AiJobStatus.completed, null);
    }

    public AiJobProcessingResult(
            JsonNode resultSummary,
            String provider,
            String model,
            Integer tokensIn,
            Integer tokensOut,
            BigDecimal costEstimate,
            String promptHash,
            AiJobStatus finalStatus
    ) {
        this(resultSummary, provider, model, tokensIn, tokensOut, costEstimate, promptHash, finalStatus, null);
    }
}
