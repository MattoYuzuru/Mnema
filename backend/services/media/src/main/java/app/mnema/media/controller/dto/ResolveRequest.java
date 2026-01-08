package app.mnema.media.controller.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.UUID;

public record ResolveRequest(
        @NotEmpty List<UUID> mediaIds
) {
}
