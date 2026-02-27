package app.mnema.importer.service;

import app.mnema.importer.client.media.MediaApiClient;
import app.mnema.importer.controller.dto.UploadImportSourceResponse;
import app.mnema.importer.domain.ImportSourceType;
import app.mnema.importer.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.Jwt;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ImportSourceServiceTest {

    @Test
    void detectsMnpkgBySqliteHeaderWhenFileHasNoExtension() {
        MediaApiClient mediaApiClient = mock(MediaApiClient.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        ImportSourceService service = new ImportSourceService(mediaApiClient, currentUserProvider);

        UUID userId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        when(currentUserProvider.requireUserId(any(Jwt.class))).thenReturn(userId);
        when(mediaApiClient.directUpload(eq(userId), eq("import_file"), anyString(), anyString(), anyLong(), any()))
                .thenReturn(mediaId);

        byte[] sqliteHeader = new byte[] {0x53, 0x51, 0x4c, 0x69, 0x74, 0x65, 0x20, 0x66, 0x6f, 0x72, 0x6d, 0x61, 0x74, 0x20, 0x33, 0x00};
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "1d09b3b1-ec21-4c70-a744-2741d8ec2efa",
                "application/octet-stream",
                sqliteHeader
        );

        UploadImportSourceResponse response = service.uploadSource(mock(Jwt.class), file, null);

        assertEquals(mediaId, response.mediaId());
        assertEquals(ImportSourceType.mnpkg, response.sourceType());
    }

    @Test
    void detectsMnemaByZipEntriesWhenFileHasNoExtension() throws IOException {
        MediaApiClient mediaApiClient = mock(MediaApiClient.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        ImportSourceService service = new ImportSourceService(mediaApiClient, currentUserProvider);

        UUID userId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        when(currentUserProvider.requireUserId(any(Jwt.class))).thenReturn(userId);
        when(mediaApiClient.directUpload(eq(userId), eq("import_file"), anyString(), anyString(), anyLong(), any()))
                .thenReturn(mediaId);

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "deck-without-extension",
                "application/zip",
                zipOf("deck.csv", "deck.json")
        );

        UploadImportSourceResponse response = service.uploadSource(mock(Jwt.class), file, null);

        assertEquals(mediaId, response.mediaId());
        assertEquals(ImportSourceType.mnema, response.sourceType());
    }

    @Test
    void detectsApkgByZipEntriesWhenFileHasNoExtension() throws IOException {
        MediaApiClient mediaApiClient = mock(MediaApiClient.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        ImportSourceService service = new ImportSourceService(mediaApiClient, currentUserProvider);

        UUID userId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        when(currentUserProvider.requireUserId(any(Jwt.class))).thenReturn(userId);
        when(mediaApiClient.directUpload(eq(userId), eq("import_file"), anyString(), anyString(), anyLong(), any()))
                .thenReturn(mediaId);

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "anki-no-extension",
                "application/zip",
                zipOf("collection.anki2", "media")
        );

        UploadImportSourceResponse response = service.uploadSource(mock(Jwt.class), file, null);

        assertEquals(mediaId, response.mediaId());
        assertEquals(ImportSourceType.apkg, response.sourceType());
    }

    private byte[] zipOf(String... entryNames) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(output)) {
            for (String entryName : entryNames) {
                zos.putNextEntry(new ZipEntry(entryName));
                zos.write("x".getBytes());
                zos.closeEntry();
            }
        }
        return output.toByteArray();
    }
}

