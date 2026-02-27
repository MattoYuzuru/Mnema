package app.mnema.importer.service;

import app.mnema.importer.client.media.MediaApiClient;
import app.mnema.importer.controller.dto.UploadImportSourceResponse;
import app.mnema.importer.domain.ImportSourceType;
import app.mnema.importer.security.CurrentUserProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class ImportSourceService {

    private final MediaApiClient mediaApiClient;
    private final CurrentUserProvider currentUserProvider;

    public ImportSourceService(MediaApiClient mediaApiClient, CurrentUserProvider currentUserProvider) {
        this.mediaApiClient = mediaApiClient;
        this.currentUserProvider = currentUserProvider;
    }

    public UploadImportSourceResponse uploadSource(Jwt jwt, MultipartFile file, ImportSourceType explicitType) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing file");
        }
        UUID userId;
        try {
            userId = currentUserProvider.requireUserId(jwt);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ex.getMessage());
        }
        String fileName = file.getOriginalFilename();
        long sizeBytes = file.getSize();
        String contentType = normalizeContentType(file.getContentType());
        ImportSourceType sourceType = explicitType != null ? explicitType : detectType(fileName, contentType, file);
        if (contentType == null) {
            contentType = defaultContentType(sourceType);
        }

        UUID mediaId;
        try (var inputStream = file.getInputStream()) {
            mediaId = mediaApiClient.directUpload(
                    userId,
                    "import_file",
                    contentType,
                    fileName,
                    sizeBytes,
                    inputStream
            );
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Failed to read upload", ex);
        }

        return new UploadImportSourceResponse(mediaId, fileName, sizeBytes, sourceType);
    }

    private ImportSourceType detectType(String fileName, String contentType, MultipartFile file) {
        ImportSourceType byName = detectTypeByName(fileName);
        if (byName != null) {
            return byName;
        }
        ImportSourceType byContentType = detectTypeByContentType(contentType);
        if (byContentType != null) {
            return byContentType;
        }
        try {
            if (hasSqliteHeader(file)) {
                return ImportSourceType.mnpkg;
            }
            ImportSourceType zipType = detectZipType(file);
            if (zipType != null) {
                return zipType;
            }
        } catch (IOException ignored) {
            // fall through to CSV default
        }
        return ImportSourceType.csv;
    }

    private ImportSourceType detectTypeByName(String fileName) {
        if (fileName == null) {
            return null;
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".apkg")) {
            return ImportSourceType.apkg;
        }
        if (lower.endsWith(".mnema")) {
            return ImportSourceType.mnema;
        }
        if (lower.endsWith(".mnpkg")) {
            return ImportSourceType.mnpkg;
        }
        if (lower.endsWith(".tsv")) {
            return ImportSourceType.tsv;
        }
        if (lower.endsWith(".txt")) {
            return ImportSourceType.txt;
        }
        return null;
    }

    private ImportSourceType detectTypeByContentType(String contentType) {
        if (contentType == null) {
            return null;
        }
        if (contentType.contains("vnd.mnema.package+sqlite") || contentType.contains("x-sqlite3")) {
            return ImportSourceType.mnpkg;
        }
        if (contentType.contains("x-apkg") || contentType.contains("application/apkg") || contentType.contains("vnd.anki")) {
            return ImportSourceType.apkg;
        }
        if (contentType.contains("text/csv")) {
            return ImportSourceType.csv;
        }
        if (contentType.contains("tab-separated-values")) {
            return ImportSourceType.tsv;
        }
        if (contentType.contains("text/plain")) {
            return ImportSourceType.txt;
        }
        return null;
    }

    private boolean hasSqliteHeader(MultipartFile file) throws IOException {
        byte[] signature = new byte[] {0x53, 0x51, 0x4c, 0x69, 0x74, 0x65, 0x20, 0x66, 0x6f, 0x72, 0x6d, 0x61, 0x74, 0x20, 0x33, 0x00};
        try (InputStream inputStream = file.getInputStream()) {
            byte[] header = inputStream.readNBytes(signature.length);
            if (header.length < signature.length) {
                return false;
            }
            for (int i = 0; i < signature.length; i++) {
                if (header[i] != signature[i]) {
                    return false;
                }
            }
            return true;
        }
    }

    private ImportSourceType detectZipType(MultipartFile file) throws IOException {
        try (ZipInputStream zipInputStream = new ZipInputStream(file.getInputStream())) {
            boolean hasDeckCsv = false;
            boolean hasDeckJson = false;
            boolean hasAnkiCollection = false;
            int scanned = 0;

            ZipEntry entry;
            while ((entry = zipInputStream.getNextEntry()) != null && scanned < 64) {
                String name = entry.getName();
                if (name == null) {
                    continue;
                }
                scanned++;
                if (name.equalsIgnoreCase("deck.csv")) {
                    hasDeckCsv = true;
                } else if (name.equalsIgnoreCase("deck.json")) {
                    hasDeckJson = true;
                } else if (name.equals("collection.anki21b")
                        || name.equals("collection.anki21")
                        || name.equals("collection.anki2")) {
                    hasAnkiCollection = true;
                }
                if (hasDeckCsv && hasDeckJson) {
                    return ImportSourceType.mnema;
                }
                if (hasAnkiCollection) {
                    return ImportSourceType.apkg;
                }
            }
        } catch (IOException ignored) {
            return null;
        }
        return null;
    }

    private String normalizeContentType(String contentType) {
        if (contentType == null) {
            return null;
        }
        String normalized = contentType.trim().toLowerCase(Locale.ROOT);
        int idx = normalized.indexOf(';');
        if (idx > 0) {
            normalized = normalized.substring(0, idx).trim();
        }
        return normalized.isBlank() ? null : normalized;
    }

    private String defaultContentType(ImportSourceType type) {
        return switch (type) {
            case apkg -> "application/zip";
            case mnema -> "application/zip";
            case mnpkg -> "application/vnd.mnema.package+sqlite";
            case csv -> "text/csv";
            case tsv, txt -> "text/plain";
        };
    }
}
