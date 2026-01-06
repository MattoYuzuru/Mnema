package app.mnema.media.controller.dto;

import java.util.Map;

public record UploadPartResponse(
        int partNumber,
        String url,
        Map<String, String> headers
) {
}
