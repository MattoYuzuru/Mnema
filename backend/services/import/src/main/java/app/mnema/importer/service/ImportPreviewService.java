package app.mnema.importer.service;

import app.mnema.importer.client.core.CoreApiClient;
import app.mnema.importer.client.core.CoreCardTemplateResponse;
import app.mnema.importer.client.core.CoreFieldTemplate;
import app.mnema.importer.client.core.CorePublicDeckResponse;
import app.mnema.importer.client.core.CoreUserDeckResponse;
import app.mnema.importer.client.media.MediaApiClient;
import app.mnema.importer.client.media.MediaResolved;
import app.mnema.importer.controller.dto.ImportFieldInfo;
import app.mnema.importer.controller.dto.ImportPreviewRequest;
import app.mnema.importer.controller.dto.ImportPreviewResponse;
import app.mnema.importer.domain.ImportSourceType;
import app.mnema.importer.service.parser.ImportParser;
import app.mnema.importer.service.parser.ImportParserFactory;
import app.mnema.importer.service.parser.ImportPreview;
import app.mnema.importer.service.parser.ImportRecord;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ImportPreviewService {

    private static final int DEFAULT_SAMPLE_SIZE = 3;

    private final MediaApiClient mediaApiClient;
    private final MediaDownloadService downloadService;
    private final ImportParserFactory parserFactory;
    private final CoreApiClient coreApiClient;

    public ImportPreviewService(MediaApiClient mediaApiClient,
                                MediaDownloadService downloadService,
                                ImportParserFactory parserFactory,
                                CoreApiClient coreApiClient) {
        this.mediaApiClient = mediaApiClient;
        this.downloadService = downloadService;
        this.parserFactory = parserFactory;
        this.coreApiClient = coreApiClient;
    }

    public ImportPreviewResponse preview(String accessToken, ImportPreviewRequest request) {
        UUID sourceMediaId = request.sourceMediaId();
        ImportSourceType sourceType = request.sourceType();
        int sampleSize = request.sampleSize() == null ? DEFAULT_SAMPLE_SIZE : request.sampleSize();
        if (sampleSize <= 0) {
            sampleSize = DEFAULT_SAMPLE_SIZE;
        }

        MediaResolved resolved = resolveSingle(sourceMediaId);
        ImportPreview preview = readPreview(resolved.url(), sourceType, sampleSize);

        List<ImportFieldInfo> sourceFields = preview.fields().stream()
                .map(name -> new ImportFieldInfo(name, null))
                .toList();

        List<ImportFieldInfo> targetFields = List.of();
        if (request.targetDeckId() != null) {
            CoreCardTemplateResponse template = loadTargetTemplate(accessToken, request.targetDeckId());
            targetFields = template.fields().stream()
                    .map(field -> new ImportFieldInfo(field.name(), field.fieldType()))
                    .toList();
        }

        Map<String, String> suggested = suggestMapping(sourceFields, targetFields);
        List<Map<String, String>> sample = preview.sample().stream()
                .map(ImportRecord::fields)
                .toList();

        return new ImportPreviewResponse(sourceFields, targetFields, suggested, sample);
    }

    private MediaResolved resolveSingle(UUID mediaId) {
        List<MediaResolved> resolved = mediaApiClient.resolve(List.of(mediaId));
        if (resolved == null || resolved.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Import source not found");
        }
        MediaResolved media = resolved.getFirst();
        if (media.url() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Import source not ready");
        }
        return media;
    }

    private ImportPreview readPreview(String url, ImportSourceType sourceType, int sampleSize) {
        ImportParser parser = parserFactory.create(sourceType);
        try (InputStream inputStream = downloadService.openStream(url)) {
            return parser.preview(inputStream, sampleSize);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Failed to read import source", ex);
        }
    }

    private CoreCardTemplateResponse loadTargetTemplate(String accessToken, UUID userDeckId) {
        if (accessToken == null || accessToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing access token");
        }
        CoreUserDeckResponse userDeck = coreApiClient.getUserDeck(accessToken, userDeckId);
        if (userDeck == null || userDeck.publicDeckId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Deck is not backed by public template");
        }
        CorePublicDeckResponse publicDeck = coreApiClient.getPublicDeck(accessToken, userDeck.publicDeckId(), userDeck.currentVersion());
        if (publicDeck == null || publicDeck.templateId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Deck template not found");
        }
        return coreApiClient.getTemplate(accessToken, publicDeck.templateId());
    }

    private Map<String, String> suggestMapping(List<ImportFieldInfo> sourceFields,
                                               List<ImportFieldInfo> targetFields) {
        Map<String, String> normalizedSource = sourceFields.stream()
                .collect(Collectors.toMap(
                        field -> normalize(field.name()),
                        ImportFieldInfo::name,
                        (a, b) -> a
                ));

        Map<String, String> mapping = new HashMap<>();
        for (ImportFieldInfo target : targetFields) {
            String key = normalize(target.name());
            String match = normalizedSource.get(key);
            if (match != null) {
                mapping.put(target.name(), match);
            }
        }
        return mapping;
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]", "");
    }
}
