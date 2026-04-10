package app.mnema.ai.controller.dto;

import java.math.BigDecimal;

public record AiJobCostResponse(
        Integer estimatedInputTokens,
        Integer estimatedOutputTokens,
        BigDecimal estimatedCost,
        String estimatedCostCurrency,
        Integer actualInputTokens,
        Integer actualOutputTokens,
        BigDecimal actualCost,
        String actualCostCurrency
) {
}
