package app.mnema.media.controller;

import app.mnema.media.controller.dto.CompleteUploadResponse;
import app.mnema.media.controller.dto.DirectUploadRequest;
import app.mnema.media.service.MediaService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/internal/uploads")
public class InternalUploadController {
    private final MediaService mediaService;

    public InternalUploadController(MediaService mediaService) {
        this.mediaService = mediaService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CompleteUploadResponse directUpload(@AuthenticationPrincipal Jwt jwt,
                                               @Valid @RequestPart("meta") DirectUploadRequest request,
                                               @RequestPart("file") MultipartFile file) {
        return mediaService.directUpload(jwt, request, file);
    }
}
