package app.mnema.media.controller;

import app.mnema.media.controller.dto.CompleteUploadRequest;
import app.mnema.media.controller.dto.CompleteUploadResponse;
import app.mnema.media.controller.dto.CreateUploadRequest;
import app.mnema.media.controller.dto.CreateUploadResponse;
import app.mnema.media.service.MediaService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/uploads")
public class UploadController {
    private final MediaService mediaService;

    public UploadController(MediaService mediaService) {
        this.mediaService = mediaService;
    }

    @PostMapping
    public CreateUploadResponse createUpload(@AuthenticationPrincipal Jwt jwt,
                                             @Valid @RequestBody CreateUploadRequest request) {
        return mediaService.createUpload(jwt, request);
    }

    @PostMapping("/{uploadId}/complete")
    public CompleteUploadResponse completeUpload(@AuthenticationPrincipal Jwt jwt,
                                                 @PathVariable UUID uploadId,
                                                 @RequestBody(required = false) CompleteUploadRequest request) {
        return mediaService.completeUpload(jwt, uploadId, request);
    }

    @PostMapping("/{uploadId}/abort")
    public void abortUpload(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID uploadId) {
        mediaService.abortUpload(jwt, uploadId);
    }
}
