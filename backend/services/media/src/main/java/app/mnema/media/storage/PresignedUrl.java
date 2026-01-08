package app.mnema.media.storage;

import java.util.Map;

public record PresignedUrl(
        String url,
        Map<String, String> headers
) {
}
