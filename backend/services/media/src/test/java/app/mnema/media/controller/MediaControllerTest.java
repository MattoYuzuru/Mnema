package app.mnema.media.controller;

import app.mnema.media.controller.dto.ResolveRequest;
import app.mnema.media.controller.dto.ResolvedMedia;
import app.mnema.media.domain.type.MediaKind;
import app.mnema.media.service.MediaService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MediaControllerTest {

    @Mock
    MediaService mediaService;

    @Mock
    Jwt jwt;

    @Test
    void delegatesResolveAndDelete() {
        MediaController controller = new MediaController(mediaService);
        UUID mediaId = UUID.randomUUID();
        ResolveRequest request = new ResolveRequest(List.of(mediaId));
        List<ResolvedMedia> resolved = List.of(new ResolvedMedia(
                mediaId, MediaKind.card_image, "https://cdn/x", "image/png", 10L, null, null, null, Instant.now()
        ));

        when(mediaService.resolve(jwt, List.of(mediaId))).thenReturn(resolved);

        assertThat(controller.resolve(jwt, request)).isEqualTo(resolved);
        controller.delete(jwt, mediaId);

        verify(mediaService).deleteMedia(jwt, mediaId);
    }
}
