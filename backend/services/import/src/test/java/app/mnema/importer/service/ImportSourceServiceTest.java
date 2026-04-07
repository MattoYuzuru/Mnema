package app.mnema.importer.service;

import app.mnema.importer.client.media.MediaApiClient;
import app.mnema.importer.controller.dto.UploadImportSourceResponse;
import app.mnema.importer.domain.ImportSourceType;
import app.mnema.importer.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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

    @Test
    void usesExplicitTypeAndDefaultContentTypeWhenMissing() {
        MediaApiClient mediaApiClient = mock(MediaApiClient.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        ImportSourceService service = new ImportSourceService(mediaApiClient, currentUserProvider);

        UUID userId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        when(currentUserProvider.requireUserId(any(Jwt.class))).thenReturn(userId);
        when(mediaApiClient.directUpload(eq(userId), eq("import_file"), eq("text/plain"), eq("deck.txt"), anyLong(), any()))
                .thenReturn(mediaId);

        MockMultipartFile file = new MockMultipartFile("file", "deck.txt", null, "front\tback".getBytes());

        UploadImportSourceResponse response = service.uploadSource(mock(Jwt.class), file, ImportSourceType.tsv);

        assertEquals(ImportSourceType.tsv, response.sourceType());
        assertEquals("deck.txt", response.fileName());
        assertEquals(mediaId, response.mediaId());
    }

    @Test
    void detectsByNormalizedContentTypeAndExtensionFallbacks() {
        MediaApiClient mediaApiClient = mock(MediaApiClient.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        ImportSourceService service = new ImportSourceService(mediaApiClient, currentUserProvider);

        UUID userId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        when(currentUserProvider.requireUserId(any(Jwt.class))).thenReturn(userId);
        when(mediaApiClient.directUpload(eq(userId), eq("import_file"), eq("text/plain"), eq("notes.txt"), anyLong(), any()))
                .thenReturn(mediaId);

        MockMultipartFile txt = new MockMultipartFile("file", "notes.txt", "TEXT/PLAIN; charset=UTF-8", "hello".getBytes());
        UploadImportSourceResponse response = service.uploadSource(mock(Jwt.class), txt, null);

        assertEquals(ImportSourceType.txt, response.sourceType());
        assertEquals(5L, response.sizeBytes());
    }

    @Test
    void rejectsMissingFileAndMissingUser() {
        MediaApiClient mediaApiClient = mock(MediaApiClient.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        ImportSourceService service = new ImportSourceService(mediaApiClient, currentUserProvider);

        ResponseStatusException missingFile = assertThrows(ResponseStatusException.class,
                () -> service.uploadSource(mock(Jwt.class), null, null));
        assertEquals(400, missingFile.getStatusCode().value());
        assertEquals("Missing file", missingFile.getReason());

        MockMultipartFile file = new MockMultipartFile("file", "deck.csv", "text/csv", "a,b".getBytes());
        when(currentUserProvider.requireUserId(any(Jwt.class))).thenThrow(new IllegalStateException("user_id claim missing"));

        ResponseStatusException missingUser = assertThrows(ResponseStatusException.class,
                () -> service.uploadSource(mock(Jwt.class), file, null));
        assertEquals(401, missingUser.getStatusCode().value());
        assertEquals("user_id claim missing", missingUser.getReason());
    }

    @Test
    void wrapsUploadReadFailure() throws Exception {
        MediaApiClient mediaApiClient = mock(MediaApiClient.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        ImportSourceService service = new ImportSourceService(mediaApiClient, currentUserProvider);

        UUID userId = UUID.randomUUID();
        when(currentUserProvider.requireUserId(any(Jwt.class))).thenReturn(userId);
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getOriginalFilename()).thenReturn("deck.csv");
        when(file.getSize()).thenReturn(10L);
        when(file.getContentType()).thenReturn("text/csv");
        when(file.getInputStream()).thenThrow(new IOException("broken input"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.uploadSource(mock(Jwt.class), file, null));

        assertEquals(400, ex.getStatusCode().value());
        assertEquals("Failed to read upload", ex.getReason());
    }

    @Test
    void fallsBackToCsvWhenInspectionFindsNoSignals() {
        MediaApiClient mediaApiClient = mock(MediaApiClient.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        ImportSourceService service = new ImportSourceService(mediaApiClient, currentUserProvider);

        UUID userId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        when(currentUserProvider.requireUserId(any(Jwt.class))).thenReturn(userId);
        when(mediaApiClient.directUpload(eq(userId), eq("import_file"), eq("application/octet-stream"), eq("mystery.bin"), anyLong(), any()))
                .thenReturn(mediaId);

        MockMultipartFile file = new MockMultipartFile("file", "mystery.bin", "application/octet-stream", "not-a-zip".getBytes());

        UploadImportSourceResponse response = service.uploadSource(mock(Jwt.class), file, null);

        assertEquals(ImportSourceType.csv, response.sourceType());
        verify(mediaApiClient).directUpload(eq(userId), eq("import_file"), eq("application/octet-stream"), eq("mystery.bin"), anyLong(), any());
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
