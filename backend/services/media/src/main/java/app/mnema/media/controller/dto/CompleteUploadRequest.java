package app.mnema.media.controller.dto;

import java.util.List;

public record CompleteUploadRequest(
        List<CompletedPartRequest> parts
) {
}
