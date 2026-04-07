package app.mnema.media.controller;

import app.mnema.media.controller.dto.CompleteUploadResponse;
import app.mnema.media.controller.dto.DirectUploadRequest;
import app.mnema.media.domain.type.MediaKind;
import app.mnema.media.domain.type.MediaStatus;
import app.mnema.media.service.MediaService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InternalUploadControllerTest {

    @Mock
    MediaService mediaService;

    @Mock
    Jwt jwt;

    @Test
    void delegatesDirectUpload() {
        InternalUploadController controller = new InternalUploadController(mediaService);
        DirectUploadRequest request = new DirectUploadRequest(MediaKind.avatar, "image/png", "avatar.png", UUID.randomUUID());
        MockMultipartFile file = new MockMultipartFile("file", "avatar.png", "image/png", new byte[]{1, 2});
        CompleteUploadResponse response = new CompleteUploadResponse(UUID.randomUUID(), MediaStatus.ready);

        when(mediaService.directUpload(jwt, request, file)).thenReturn(response);

        assertThat(controller.directUpload(jwt, request, file)).isEqualTo(response);
    }
}
