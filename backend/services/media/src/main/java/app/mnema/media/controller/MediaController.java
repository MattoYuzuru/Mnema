package app.mnema.media.controller;

import app.mnema.media.controller.dto.ResolveRequest;
import app.mnema.media.controller.dto.ResolvedMedia;
import app.mnema.media.service.MediaService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
public class MediaController {
    private final MediaService mediaService;

    public MediaController(MediaService mediaService) {
        this.mediaService = mediaService;
    }

    @PostMapping("/resolve")
    public List<ResolvedMedia> resolve(@AuthenticationPrincipal Jwt jwt,
                                       @Valid @RequestBody ResolveRequest request) {
        return mediaService.resolve(jwt, request.mediaIds());
    }

    @DeleteMapping("/assets/{mediaId}")
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID mediaId) {
        mediaService.deleteMedia(jwt, mediaId);
    }
}
