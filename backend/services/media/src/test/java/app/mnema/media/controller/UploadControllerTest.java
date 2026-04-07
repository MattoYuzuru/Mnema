package app.mnema.media.controller;

import app.mnema.media.controller.dto.CompleteUploadRequest;
import app.mnema.media.controller.dto.CompleteUploadResponse;
import app.mnema.media.controller.dto.CompletedPartRequest;
import app.mnema.media.controller.dto.CreateUploadRequest;
import app.mnema.media.controller.dto.CreateUploadResponse;
import app.mnema.media.controller.dto.UploadPartResponse;
import app.mnema.media.domain.type.MediaKind;
import app.mnema.media.domain.type.MediaStatus;
import app.mnema.media.service.MediaService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UploadControllerTest {

    @Mock
    MediaService mediaService;

    @Mock
    Jwt jwt;

    @Test
    void delegatesCreateCompleteAndAbort() {
        UploadController controller = new UploadController(mediaService);
        CreateUploadRequest createRequest = new CreateUploadRequest(MediaKind.card_image, "image/png", 10, "f.png");
        CreateUploadResponse createResponse = new CreateUploadResponse(
                UUID.randomUUID(), UUID.randomUUID(), false, "https://upload", Map.of(), List.of(), null, null, Instant.now()
        );
        UUID uploadId = UUID.randomUUID();
        CompleteUploadRequest completeRequest = new CompleteUploadRequest(List.of(new CompletedPartRequest(1, "etag")));
        CompleteUploadResponse completeResponse = new CompleteUploadResponse(UUID.randomUUID(), MediaStatus.ready);

        when(mediaService.createUpload(jwt, createRequest)).thenReturn(createResponse);
        when(mediaService.completeUpload(jwt, uploadId, completeRequest)).thenReturn(completeResponse);

        assertThat(controller.createUpload(jwt, createRequest)).isEqualTo(createResponse);
        assertThat(controller.completeUpload(jwt, uploadId, completeRequest)).isEqualTo(completeResponse);
        controller.abortUpload(jwt, uploadId);

        verify(mediaService).abortUpload(jwt, uploadId);
    }
}
