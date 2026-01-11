package app.mnema.importer.service;

import app.mnema.importer.client.core.*;
import app.mnema.importer.client.media.MediaApiClient;
import app.mnema.importer.client.media.MediaResolved;
import app.mnema.importer.domain.ImportJobEntity;
import app.mnema.importer.domain.ImportMode;
import app.mnema.importer.repository.ImportJobRepository;
import app.mnema.importer.service.parser.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLConnection;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ImportProcessor {

    private static final Pattern IMAGE_PATTERN = Pattern.compile("<img[^>]+src=[\"']([^\"']+)[\"'][^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern SOUND_PATTERN = Pattern.compile("\\[sound:([^\\]]+)]", Pattern.CASE_INSENSITIVE);
    private static final Pattern AUDIO_TAG_PATTERN = Pattern.compile("<audio[^>]+src=[\"']([^\"']+)[\"'][^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern VIDEO_TAG_PATTERN = Pattern.compile("<video[^>]+src=[\"']([^\"']+)[\"'][^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern SOURCE_TAG_PATTERN = Pattern.compile("<source[^>]+src=[\"']([^\"']+)[\"'][^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern UUID_PATTERN = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern IMAGE_FILE_PATTERN = Pattern.compile("([\\w\\-./]+\\.(?:png|jpg|jpeg|gif|webp))", Pattern.CASE_INSENSITIVE);
    private static final Pattern AUDIO_FILE_PATTERN = Pattern.compile("([\\w\\-./]+\\.(?:mp3|m4a|wav|ogg))", Pattern.CASE_INSENSITIVE);
    private static final Pattern VIDEO_FILE_PATTERN = Pattern.compile("([\\w\\-./]+\\.(?:mp4|webm|m4v|mov|mkv|avi|mpeg|mpg))", Pattern.CASE_INSENSITIVE);
    private static final List<String> VIDEO_EXTENSIONS = List.of("mp4", "webm", "m4v", "mov", "mkv", "avi", "mpeg", "mpg");
    private static final String ANKI_UPDATE_MESSAGE = "please update to the latest anki version";
    private static final List<String> FRONT_FIELD_HINTS = List.of("front", "question", "term", "word", "expression", "original", "prompt");
    private static final List<String> BACK_FIELD_HINTS = List.of("back", "answer", "meaning", "translation", "definition", "example", "comment", "notes", "extra", "hint");
    private static final Set<String> SUPPORTED_LANGUAGES = Set.of("ru", "en", "jp", "sp");

    private final MediaApiClient mediaApiClient;
    private final MediaDownloadService downloadService;
    private final ImportParserFactory parserFactory;
    private final CoreApiClient coreApiClient;
    private final ImportJobRepository jobRepository;
    private final ObjectMapper objectMapper;
    private final int batchSize;
    private final String defaultLanguage;

    public ImportProcessor(MediaApiClient mediaApiClient,
                           MediaDownloadService downloadService,
                           ImportParserFactory parserFactory,
                           CoreApiClient coreApiClient,
                           ImportJobRepository jobRepository,
                           ObjectMapper objectMapper,
                           @Value("${app.import.batch-size:200}") int batchSize,
                           @Value("${app.import.default-language:en}") String defaultLanguage) {
        this.mediaApiClient = mediaApiClient;
        this.downloadService = downloadService;
        this.parserFactory = parserFactory;
        this.coreApiClient = coreApiClient;
        this.jobRepository = jobRepository;
        this.objectMapper = objectMapper;
        this.batchSize = batchSize;
        this.defaultLanguage = defaultLanguage;
    }

    public void process(ImportJobEntity job) {
        if (job.getSourceMediaId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing source media");
        }

        MediaResolved resolved = resolveSource(job.getSourceMediaId());
        ImportParser parser = parserFactory.create(job.getSourceType());

        try (InputStream sourceStream = downloadService.openStream(resolved.url());
             ImportStream stream = parser.openStream(sourceStream)) {

            List<String> sourceFields = stream.fields();
            ImportLayout layout = stream.layout();
            MappingContext mappingContext = resolveMapping(job, sourceFields, layout);

            CoreUserDeckResponse targetDeck = mappingContext.userDeck();
            List<CoreFieldTemplate> targetFields = mappingContext.targetFields();
            Map<String, String> mapping = mappingContext.mapping();

            Integer totalItems = stream.totalItems();
            if (totalItems != null && totalItems > 0) {
                updateTotal(job.getJobId(), totalItems);
            }

            Instant now = Instant.now();
            int processed = 0;
            List<CoreCreateCardRequest> batch = new ArrayList<>(batchSize);
            List<ImportRecordProgress> progressBatch = new ArrayList<>(batchSize);

            while (stream.hasNext()) {
                ImportRecord record = stream.next();
                if (record == null) {
                    continue;
                }
                if (isPlaceholderRecord(record)) {
                    continue;
                }
                ObjectNode content = buildContent(record, targetFields, mapping, stream, job.getUserId());
                if (content.isEmpty()) {
                    continue;
                }
                CoreCreateCardRequest card = new CoreCreateCardRequest(content, null, null, null, null, null);
                batch.add(card);
                progressBatch.add(record.progress());

                if (batch.size() >= batchSize) {
                    List<app.mnema.importer.client.core.CoreUserCardResponse> created =
                            coreApiClient.addCardsBatch(job.getUserAccessToken(), targetDeck.userDeckId(), batch);
                    processed += created == null ? 0 : created.size();
                    seedProgress(job, targetDeck.userDeckId(), created, progressBatch, now);
                    batch.clear();
                    progressBatch.clear();
                    updateProgress(job.getJobId(), processed);
                }
            }

            if (!batch.isEmpty()) {
                List<app.mnema.importer.client.core.CoreUserCardResponse> created =
                        coreApiClient.addCardsBatch(job.getUserAccessToken(), targetDeck.userDeckId(), batch);
                processed += created == null ? 0 : created.size();
                seedProgress(job, targetDeck.userDeckId(), created, progressBatch, now);
                updateProgress(job.getJobId(), processed);
            }

            updateTotals(job.getJobId(), processed);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Import failed", ex);
        }
    }

    private MediaResolved resolveSource(UUID mediaId) {
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

    private MappingContext resolveMapping(ImportJobEntity job, List<String> sourceFields, ImportLayout layout) {
        if (job.getMode() == ImportMode.create_new) {
            String deckName = job.getDeckName();
            if (deckName == null || deckName.isBlank()) {
                deckName = job.getSourceName() == null ? "Imported deck" : job.getSourceName();
            }
            List<CoreFieldTemplate> templateFields = buildTemplateFields(sourceFields, layout);
            CoreCardTemplateResponse template = coreApiClient.createTemplate(
                    job.getUserAccessToken(),
                    new CoreCardTemplateRequest(
                            null,
                            null,
                            deckName,
                            "Imported from " + job.getSourceType(),
                            false,
                            buildTemplateLayout(templateFields),
                            null,
                            null,
                            templateFields
                    )
            );

            CoreUserDeckResponse userDeck = coreApiClient.createDeck(
                    job.getUserAccessToken(),
                    new CorePublicDeckRequest(
                            null,
                            null,
                            null,
                            deckName,
                            "Imported from " + job.getSourceType(),
                            null,
                            template.templateId(),
                            false,
                            false,
                            resolveLanguage(),
                            null,
                            null
                    )
            );

            updateTargetDeck(job.getJobId(), userDeck.userDeckId());
            Map<String, String> mapping = identityMapping(template.fields(), sourceFields);
            return new MappingContext(userDeck, template.fields(), mapping);
        }

        if (job.getTargetDeckId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "targetDeckId is required for merge");
        }

        CoreUserDeckResponse userDeck = coreApiClient.getUserDeck(job.getUserAccessToken(), job.getTargetDeckId());
        if (userDeck.publicDeckId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Deck is missing public template");
        }
        CorePublicDeckResponse publicDeck = coreApiClient.getPublicDeck(job.getUserAccessToken(), userDeck.publicDeckId(), userDeck.currentVersion());
        CoreCardTemplateResponse template = coreApiClient.getTemplate(job.getUserAccessToken(), publicDeck.templateId());

        Map<String, String> mapping = mappingFromJob(job, sourceFields, template.fields());
        return new MappingContext(userDeck, template.fields(), mapping);
    }

    private Map<String, String> mappingFromJob(ImportJobEntity job,
                                               List<String> sourceFields,
                                               List<CoreFieldTemplate> targetFields) {
        Map<String, String> mapping = new HashMap<>();
        JsonNode node = job.getFieldMapping();
        if (node != null && node.isObject()) {
            node.fields().forEachRemaining(entry -> mapping.put(entry.getKey(), entry.getValue().asText()));
        }

        if (!mapping.isEmpty()) {
            return mapping;
        }
        Map<String, String> normalized = new HashMap<>();
        for (String field : sourceFields) {
            normalized.put(normalize(field), field);
        }
        for (CoreFieldTemplate field : targetFields) {
            String match = normalized.get(normalize(field.name()));
            if (match != null) {
                mapping.put(field.name(), match);
            }
        }
        return mapping;
    }

    private Map<String, String> identityMapping(List<CoreFieldTemplate> targetFields, List<String> sourceFields) {
        Map<String, String> mapping = new HashMap<>();
        for (CoreFieldTemplate field : targetFields) {
            if (sourceFields.contains(field.name())) {
                mapping.put(field.name(), field.name());
            }
        }
        return mapping;
    }

    private List<CoreFieldTemplate> buildTemplateFields(List<String> sourceFields, ImportLayout layout) {
        List<String> names = sourceFields == null ? List.of() : sourceFields;
        List<String> ordered = new ArrayList<>();
        Set<String> frontNames = new HashSet<>();

        if (layout != null) {
            List<String> front = layout.front() == null ? List.of() : layout.front();
            List<String> back = layout.back() == null ? List.of() : layout.back();
            frontNames.addAll(front);
            for (String name : front) {
                if (names.contains(name) && !ordered.contains(name)) {
                    ordered.add(name);
                }
            }
            for (String name : back) {
                if (names.contains(name) && !ordered.contains(name)) {
                    ordered.add(name);
                }
            }
        }

        if (ordered.isEmpty()) {
            ordered.addAll(names);
        } else {
            for (String name : names) {
                if (!ordered.contains(name)) {
                    ordered.add(name);
                }
            }
        }

        List<Boolean> frontFlags = new ArrayList<>(ordered.size());
        boolean hasFront = false;
        for (String name : ordered) {
            boolean isFront = frontNames.contains(name) || isFrontFieldName(name);
            frontFlags.add(isFront);
            if (isFront) {
                hasFront = true;
            }
        }
        if (!hasFront && !frontFlags.isEmpty()) {
            frontFlags.set(0, true);
        }

        List<CoreFieldTemplate> fields = new ArrayList<>();
        int index = 0;
        for (String name : ordered) {
            String type = inferFieldType(name);
            fields.add(new CoreFieldTemplate(
                    null,
                    null,
                    name,
                    name,
                    type,
                    false,
                    frontFlags.get(index),
                    index,
                    null,
                    null
            ));
            index++;
        }
        return fields;
    }

    private JsonNode buildTemplateLayout(List<CoreFieldTemplate> fields) {
        ObjectNode layout = objectMapper.createObjectNode();
        var front = layout.putArray("front");
        var back = layout.putArray("back");
        fields.stream()
                .sorted(Comparator.comparingInt(field -> field.orderIndex() == null ? Integer.MAX_VALUE : field.orderIndex()))
                .forEach(field -> {
                    if (field.isOnFront()) {
                        front.add(field.name());
                    } else {
                        back.add(field.name());
                    }
                });
        return layout;
    }

    private ObjectNode buildContent(ImportRecord record,
                                    List<CoreFieldTemplate> targetFields,
                                    Map<String, String> mapping,
                                    ImportStream stream,
                                    UUID userId) {
        ObjectNode content = objectMapper.createObjectNode();
        Map<String, String> sourceValues = record.fields();

        for (CoreFieldTemplate targetField : targetFields) {
            String targetName = targetField.name();
            String sourceName = mapping.get(targetName);
            if (sourceName == null) {
                continue;
            }
            String raw = sourceValues.getOrDefault(sourceName, "");
            if (raw == null || raw.isBlank()) {
                continue;
            }

            String fieldType = targetField.fieldType();
            if (isMediaField(fieldType)) {
                JsonNode mediaNode = buildMediaValue(raw, fieldType, stream, userId);
                if (mediaNode != null) {
                    content.set(targetName, mediaNode);
                }
            } else {
                content.put(targetName, cleanText(raw));
            }
        }

        return content;
    }

    private JsonNode buildMediaValue(String raw, String fieldType, ImportStream stream, UUID userId) {
        String normalizedType = fieldType.toLowerCase(Locale.ROOT);
        if (UUID_PATTERN.matcher(raw.trim()).matches()) {
            ObjectNode obj = objectMapper.createObjectNode();
            obj.put("mediaId", raw.trim());
            obj.put("kind", normalizedType);
            return obj;
        }

        if (stream instanceof ApkgImportParser.ApkgImportStream apkgStream) {
            MediaTokens tokens = extractMediaTokens(raw);
            String mediaName = switch (normalizedType) {
                case "audio" -> tokens.firstAudio();
                case "video" -> tokens.firstVideo();
                default -> tokens.firstImage();
            };
            if (mediaName == null) {
                mediaName = findMediaFileName(raw, normalizedType);
            }
            if (mediaName == null && "video".equals(normalizedType)) {
                mediaName = tokens.firstAudio();
            }
            if (mediaName == null) {
                return null;
            }
            UUID mediaId = uploadMedia(apkgStream, userId, mediaName, normalizedType);
            if (mediaId == null) {
                return null;
            }
            ObjectNode obj = objectMapper.createObjectNode();
            obj.put("mediaId", mediaId.toString());
            obj.put("kind", normalizedType);
            return obj;
        }

        return null;
    }

    private UUID uploadMedia(ApkgImportParser.ApkgImportStream apkgStream, UUID userId, String mediaName, String kind) {
        ApkgImportParser.ApkgMedia media;
        try {
            media = apkgStream.openMedia(mediaName);
        } catch (IOException ex) {
            return null;
        }
        if (media == null) {
            return null;
        }
        try (InputStream mediaStream = media.stream()) {
            String contentType = guessContentType(mediaName);
            return mediaApiClient.directUpload(
                    userId,
                    kindToMediaKind(kind),
                    contentType,
                    mediaName,
                    media.size(),
                    mediaStream
            );
        } catch (IOException ex) {
            return null;
        }
    }

    private String kindToMediaKind(String kind) {
        return switch (kind) {
            case "audio" -> "card_audio";
            case "video" -> "card_video";
            default -> "card_image";
        };
    }

    private boolean isMediaField(String fieldType) {
        if (fieldType == null) {
            return false;
        }
        String normalized = fieldType.toLowerCase(Locale.ROOT);
        return normalized.equals("image") || normalized.equals("audio") || normalized.equals("video");
    }

    private String inferFieldType(String name) {
        String lowered = normalize(name);
        if (lowered.contains("image") || lowered.contains("img") || lowered.contains("picture") || lowered.contains("photo") || lowered.contains("pic")) {
            return "image";
        }
        if (lowered.contains("audio") || lowered.contains("sound")) {
            return "audio";
        }
        if (lowered.contains("video")) {
            return "video";
        }
        return "text";
    }

    private String cleanText(String raw) {
        if (raw == null) {
            return "";
        }
        String withoutMedia = IMAGE_PATTERN.matcher(raw).replaceAll("");
        withoutMedia = SOUND_PATTERN.matcher(withoutMedia).replaceAll("");
        withoutMedia = AUDIO_TAG_PATTERN.matcher(withoutMedia).replaceAll("");
        withoutMedia = VIDEO_TAG_PATTERN.matcher(withoutMedia).replaceAll("");
        withoutMedia = SOURCE_TAG_PATTERN.matcher(withoutMedia).replaceAll("");
        return withoutMedia.trim();
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]", "");
    }

    private boolean isPlaceholderRecord(ImportRecord record) {
        Map<String, String> fields = record.fields();
        if (fields == null || fields.isEmpty()) {
            return true;
        }
        boolean hasValue = false;
        for (String value : fields.values()) {
            if (value == null || value.isBlank()) {
                continue;
            }
            hasValue = true;
            String normalized = value.toLowerCase(Locale.ROOT);
            if (!normalized.contains(ANKI_UPDATE_MESSAGE)) {
                return false;
            }
        }
        return hasValue;
    }

    private boolean isFrontFieldName(String name) {
        String normalized = normalize(name);
        if (normalized.isBlank()) {
            return false;
        }
        if (isBackFieldName(name)) {
            return false;
        }
        for (String hint : FRONT_FIELD_HINTS) {
            if (normalized.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    private boolean isBackFieldName(String name) {
        String normalized = normalize(name);
        if (normalized.isBlank()) {
            return false;
        }
        for (String hint : BACK_FIELD_HINTS) {
            if (normalized.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    private String resolveLanguage() {
        if (defaultLanguage == null) {
            return "en";
        }
        String normalized = defaultLanguage.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return "en";
        }
        if (!SUPPORTED_LANGUAGES.contains(normalized)) {
            return "en";
        }
        return normalized;
    }

    private MediaTokens extractMediaTokens(String raw) {
        List<String> images = new ArrayList<>();
        List<String> audio = new ArrayList<>();
        List<String> video = new ArrayList<>();
        Matcher imgMatcher = IMAGE_PATTERN.matcher(raw);
        while (imgMatcher.find()) {
            images.add(imgMatcher.group(1));
        }
        Matcher soundMatcher = SOUND_PATTERN.matcher(raw);
        while (soundMatcher.find()) {
            classifyMedia(soundMatcher.group(1), audio, video);
        }
        Matcher audioMatcher = AUDIO_TAG_PATTERN.matcher(raw);
        while (audioMatcher.find()) {
            classifyMedia(audioMatcher.group(1), audio, video);
        }
        Matcher videoMatcher = VIDEO_TAG_PATTERN.matcher(raw);
        while (videoMatcher.find()) {
            String name = normalizeMediaName(videoMatcher.group(1));
            if (name != null) {
                video.add(name);
            }
        }
        Matcher sourceMatcher = SOURCE_TAG_PATTERN.matcher(raw);
        while (sourceMatcher.find()) {
            classifyMedia(sourceMatcher.group(1), audio, video);
        }
        return new MediaTokens(images, audio, video);
    }

    private String findMediaFileName(String raw, String type) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String cleaned = raw.trim();
        Pattern pattern = switch (type) {
            case "audio" -> AUDIO_FILE_PATTERN;
            case "video" -> VIDEO_FILE_PATTERN;
            default -> IMAGE_FILE_PATTERN;
        };
        Matcher matcher = pattern.matcher(cleaned);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private String guessContentType(String name) {
        String guessed = URLConnection.guessContentTypeFromName(name);
        if (guessed != null) {
            return guessed;
        }
        String ext = fileExtension(name);
        if (ext == null) {
            return "application/octet-stream";
        }
        return switch (ext) {
            case "mp3" -> "audio/mpeg";
            case "m4a" -> "audio/mp4";
            case "wav" -> "audio/wav";
            case "ogg" -> "audio/ogg";
            case "mp4", "m4v" -> "video/mp4";
            case "webm" -> "video/webm";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            default -> "application/octet-stream";
        };
    }

    private void classifyMedia(String name, List<String> audio, List<String> video) {
        String normalized = normalizeMediaName(name);
        if (normalized == null) {
            return;
        }
        if (isVideoFile(normalized)) {
            video.add(normalized);
        } else {
            audio.add(normalized);
        }
    }

    private String normalizeMediaName(String name) {
        if (name == null) {
            return null;
        }
        String trimmed = name.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private boolean isVideoFile(String name) {
        String ext = fileExtension(name);
        return ext != null && VIDEO_EXTENSIONS.contains(ext);
    }

    private String fileExtension(String name) {
        if (name == null) {
            return null;
        }
        int idx = name.lastIndexOf('.');
        if (idx < 0 || idx == name.length() - 1) {
            return null;
        }
        return name.substring(idx + 1).toLowerCase(Locale.ROOT);
    }

    private void seedProgress(ImportJobEntity job,
                              UUID userDeckId,
                              List<app.mnema.importer.client.core.CoreUserCardResponse> created,
                              List<ImportRecordProgress> progressBatch,
                              Instant now) {
        if (created == null || created.isEmpty() || progressBatch == null || progressBatch.isEmpty()) {
            return;
        }
        int limit = Math.min(created.size(), progressBatch.size());
        List<app.mnema.importer.client.core.CoreCardProgressRequest> requests = new ArrayList<>();
        for (int i = 0; i < limit; i++) {
            ImportRecordProgress progress = progressBatch.get(i);
            if (progress == null) {
                continue;
            }
            var card = created.get(i);
            if (card == null || card.userCardId() == null) {
                continue;
            }
            requests.add(buildProgressRequest(card.userCardId(), progress, now));
        }
        if (!requests.isEmpty()) {
            coreApiClient.seedProgress(job.getUserAccessToken(), userDeckId, requests);
        }
    }

    private app.mnema.importer.client.core.CoreCardProgressRequest buildProgressRequest(UUID userCardId,
                                                                                        ImportRecordProgress progress,
                                                                                        Instant now) {
        double stabilityDays = Math.max(0.1, progress.stabilityDays());
        Instant nextReviewAt = now.plus(Duration.ofSeconds((long) (stabilityDays * 86400)));
        return new app.mnema.importer.client.core.CoreCardProgressRequest(
                userCardId,
                progress.difficulty01(),
                stabilityDays,
                progress.reviewCount(),
                now,
                nextReviewAt,
                progress.suspended()
        );
    }

    @Transactional
    protected void updateProgress(UUID jobId, int processed) {
        jobRepository.findById(jobId).ifPresent(job -> {
            job.setProcessedItems(processed);
            job.setUpdatedAt(Instant.now());
            jobRepository.save(job);
        });
    }

    @Transactional
    protected void updateTotals(UUID jobId, int processed) {
        jobRepository.findById(jobId).ifPresent(job -> {
            job.setProcessedItems(processed);
            if (job.getTotalItems() == null) {
                job.setTotalItems(processed);
            }
            job.setUpdatedAt(Instant.now());
            jobRepository.save(job);
        });
    }

    @Transactional
    protected void updateTotal(UUID jobId, int totalItems) {
        jobRepository.findById(jobId).ifPresent(job -> {
            job.setTotalItems(totalItems);
            job.setUpdatedAt(Instant.now());
            jobRepository.save(job);
        });
    }

    @Transactional
    protected void updateTargetDeck(UUID jobId, UUID userDeckId) {
        jobRepository.findById(jobId).ifPresent(job -> {
            job.setTargetDeckId(userDeckId);
            job.setUpdatedAt(Instant.now());
            jobRepository.save(job);
        });
    }

    private record MappingContext(CoreUserDeckResponse userDeck,
                                  List<CoreFieldTemplate> targetFields,
                                  Map<String, String> mapping) {
    }

    private record MediaTokens(List<String> images, List<String> audio, List<String> video) {
        String firstImage() {
            return images.isEmpty() ? null : images.getFirst();
        }

        String firstAudio() {
            return audio.isEmpty() ? null : audio.getFirst();
        }

        String firstVideo() {
            return video.isEmpty() ? null : video.getFirst();
        }
    }
}
