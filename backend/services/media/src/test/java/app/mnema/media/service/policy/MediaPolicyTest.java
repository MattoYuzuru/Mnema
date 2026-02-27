package app.mnema.media.service.policy;

import app.mnema.media.domain.type.MediaKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class MediaPolicyTest {

    @Test
    void allowsMnpkgForImportFileKind() {
        MediaPolicy policy = new MediaPolicy();

        assertDoesNotThrow(() ->
                policy.validateUpload(MediaKind.import_file, "application/vnd.mnema.package+sqlite", 1024)
        );
    }
}

