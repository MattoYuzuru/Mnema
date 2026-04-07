package app.mnema.media.service.policy;

import app.mnema.media.domain.type.MediaKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MediaPolicyTest {

    private final MediaPolicy policy = new MediaPolicy();

    @Test
    void allowsMnpkgForImportFileKind() {
        assertDoesNotThrow(() ->
                policy.validateUpload(MediaKind.import_file, "application/vnd.mnema.package+sqlite", 1024)
        );
    }

    @Test
    void allowsGifForCardVideoWithDedicatedSizeLimit() {
        assertEquals(20L * MediaPolicy.MB, policy.maxBytesFor(MediaKind.card_video, "image/gif"));
    }

    @Test
    void rejectsUnsupportedMimeType() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> policy.validateUpload(MediaKind.card_audio, "image/png", 1024)
        );

        assertEquals("Unsupported contentType for card_audio: image/png", exception.getMessage());
    }

    @Test
    void normalizesContentTypeWithParameters() {
        assertEquals("image/png", policy.normalizeContentType(" image/png; charset=utf-8 "));
    }
}
