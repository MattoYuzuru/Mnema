package app.mnema.importer.service;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MediaDownloadServiceTest {

    private final MediaDownloadService service = new MediaDownloadService();

    @Test
    void openStreamReadsFromFileUri() throws Exception {
        Path tempFile = Files.createTempFile("mnema-import", ".txt");
        Files.writeString(tempFile, "payload", StandardCharsets.UTF_8);

        try (InputStream inputStream = service.openStream(tempFile.toUri().toString())) {
            assertThat(new String(inputStream.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("payload");
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    void detectContentTypeUsesFileNameHeuristics() throws Exception {
        assertThat(service.detectContentType("deck.csv")).isEqualTo("text/csv");
        assertThat(service.detectContentType("image.png")).isEqualTo("image/png");
    }
}
