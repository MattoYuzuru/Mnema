package app.mnema.ai.controller.dto;

import java.util.List;
import java.util.UUID;

public record AiJobPreflightItemResponse(
        String itemType,
        UUID cardId,
        String preview,
        List<String> fields,
        List<String> plannedStages
) {
}
