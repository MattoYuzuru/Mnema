package app.mnema.ai.controller.dto;

import app.mnema.ai.domain.type.AiJobStepStatus;

import java.time.Instant;

public record AiJobStepResponse(
        String stepName,
        AiJobStepStatus status,
        Instant startedAt,
        Instant endedAt,
        String errorSummary
) {
}
