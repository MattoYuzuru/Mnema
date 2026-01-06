package app.mnema.media.storage;

import java.util.Map;

public record PresignedPart(
        int partNumber,
        String url,
        Map<String, String> headers
) {
}
