package app.mnema.ai.controller.dto;

import app.mnema.ai.domain.type.AiJobStatus;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;

public record AiJobResultResponse(
        UUID jobId,
        AiJobStatus status,
        JsonNode resultSummary
) {
}
