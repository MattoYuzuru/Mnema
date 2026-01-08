package app.mnema.media.service.policy;

import java.util.Set;

public record MediaLimit(
        long maxBytes,
        Integer maxDurationSeconds,
        Integer maxWidth,
        Integer maxHeight,
        Set<String> allowedMimeTypes
) {
}
