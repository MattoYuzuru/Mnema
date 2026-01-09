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
import java.util.Locale;
import java.util.UUID;

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
        ImportSourceType sourceType = explicitType != null ? explicitType : detectType(fileName);
        String contentType = normalizeContentType(file.getContentType());
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

    private ImportSourceType detectType(String fileName) {
        if (fileName == null) {
            return ImportSourceType.csv;
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".apkg")) {
            return ImportSourceType.apkg;
        }
        if (lower.endsWith(".tsv")) {
            return ImportSourceType.tsv;
        }
        if (lower.endsWith(".txt")) {
            return ImportSourceType.txt;
        }
        return ImportSourceType.csv;
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
            case csv -> "text/csv";
            case tsv, txt -> "text/plain";
        };
    }
}
