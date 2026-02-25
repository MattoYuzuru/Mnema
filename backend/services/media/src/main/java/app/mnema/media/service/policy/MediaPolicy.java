package app.mnema.media.service.policy;

import app.mnema.media.domain.type.MediaKind;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

@Component
public class MediaPolicy {
    public static final long MB = 1024L * 1024L;

    private static final long GIF_MAX_BYTES = 20 * MB;

    private final Map<MediaKind, MediaLimit> limits;
    private final long multipartThresholdBytes;
    private final long multipartPartSizeBytes;
    private final Duration presignTtl;

    public MediaPolicy() {
        this.limits = defaultLimits();
        this.multipartThresholdBytes = 16 * MB;
        this.multipartPartSizeBytes = 8 * MB;
        this.presignTtl = Duration.ofMinutes(10);
    }

    public MediaLimit limitFor(MediaKind kind) {
        MediaLimit limit = limits.get(kind);
        if (limit == null) {
            throw new IllegalArgumentException("Unsupported media kind: " + kind);
        }
        return limit;
    }

    public long maxBytesFor(MediaKind kind, String contentType) {
        if (kind == MediaKind.card_video && "image/gif".equalsIgnoreCase(contentType)) {
            return GIF_MAX_BYTES;
        }
        return limitFor(kind).maxBytes();
    }

    public void validateUpload(MediaKind kind, String contentType, long sizeBytes) {
        String normalized = normalizeContentType(contentType);
        if (normalized == null || normalized.isBlank()) {
            throw new IllegalArgumentException("contentType is required");
        }
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("sizeBytes must be positive");
        }
        MediaLimit limit = limitFor(kind);
        if (!limit.allowedMimeTypes().contains(normalized)) {
            throw new IllegalArgumentException("Unsupported contentType for " + kind + ": " + normalized);
        }
        long maxBytes = maxBytesFor(kind, normalized);
        if (sizeBytes > maxBytes) {
            throw new IllegalArgumentException("File too large for " + kind + ": " + sizeBytes + " > " + maxBytes);
        }
    }

    public String normalizeContentType(String contentType) {
        if (contentType == null) {
            return null;
        }
        String normalized = contentType.trim().toLowerCase();
        int idx = normalized.indexOf(';');
        if (idx > 0) {
            normalized = normalized.substring(0, idx).trim();
        }
        return normalized;
    }

    public long multipartThresholdBytes() {
        return multipartThresholdBytes;
    }

    public long multipartPartSizeBytes() {
        return multipartPartSizeBytes;
    }

    public Duration presignTtl() {
        return presignTtl;
    }

    private static Map<MediaKind, MediaLimit> defaultLimits() {
        Map<MediaKind, MediaLimit> map = new EnumMap<>(MediaKind.class);

        Set<String> imageTypes = Set.of("image/jpeg", "image/png", "image/webp");
        Set<String> cardImageTypes = Set.of("image/jpeg", "image/png", "image/webp", "image/gif");
        Set<String> importFileTypes = Set.of(
                "application/zip",
                "application/octet-stream",
                "application/x-zip-compressed",
                "application/x-apkg",
                "application/apkg",
                "application/vnd.anki",
                "text/csv",
                "text/plain"
        );
        Set<String> aiImportTypes = Set.of(
                "text/plain",
                "application/pdf",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "image/jpeg",
                "image/png",
                "image/webp",
                "audio/flac",
                "audio/mpeg",
                "audio/mp4",
                "audio/mp3",
                "audio/x-m4a",
                "audio/ogg",
                "audio/wav",
                "audio/x-wav",
                "audio/webm"
        );

        map.put(MediaKind.avatar, new MediaLimit(
                10 * MB,
                null,
                1024,
                1024,
                imageTypes
        ));

        map.put(MediaKind.deck_icon, new MediaLimit(
                10 * MB,
                null,
                2048,
                2048,
                imageTypes
        ));

        map.put(MediaKind.card_image, new MediaLimit(
                20 * MB,
                null,
                4096,
                4096,
                cardImageTypes
        ));

        map.put(MediaKind.card_audio, new MediaLimit(
                20 * MB,
                300,
                null,
                null,
                Set.of("audio/mpeg", "audio/mp4", "audio/ogg", "audio/wav", "audio/x-wav")
        ));

        map.put(MediaKind.card_video, new MediaLimit(
                50 * MB,
                20,
                1920,
                1080,
                Set.of("video/mp4", "video/webm", "image/gif")
        ));

        map.put(MediaKind.import_file, new MediaLimit(
                600 * MB,
                null,
                null,
                null,
                importFileTypes
        ));

        map.put(MediaKind.ai_import, new MediaLimit(
                600 * MB,
                null,
                null,
                null,
                aiImportTypes
        ));

        return map;
    }
}
