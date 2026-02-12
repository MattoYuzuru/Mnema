package app.mnema.ai.controller.dto;

import app.mnema.ai.domain.type.AiJobType;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.util.UUID;

public record CreateAiJobRequest(
        @NotNull UUID requestId,
        UUID deckId,
        @NotNull AiJobType type,
        JsonNode params,
        String inputHash,
        @PositiveOrZero Integer tokensEstimated,
        @PositiveOrZero BigDecimal costEstimate
) {
}
