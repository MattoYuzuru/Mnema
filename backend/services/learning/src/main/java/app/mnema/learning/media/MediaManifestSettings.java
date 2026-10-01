package app.mnema.learning.media;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Bounded offline inventory and blob retention policy. */
@Component
public final class MediaManifestSettings {
    private final Duration retention;
    private final int maxReferences;
    private final int maxAssets;
    private final int maxDocumentBytes;

    public MediaManifestSettings(@Value("${learning.media.manifest.retention:P90D}") Duration retention,
                                 @Value("${learning.media.manifest.max-references:50000}") int maxReferences,
                                 @Value("${learning.media.manifest.max-assets:10000}") int maxAssets,
                                 @Value("${learning.media.manifest.max-document-bytes:8388608}") int maxDocumentBytes) {
        if (retention == null || retention.compareTo(Duration.ofDays(1)) < 0
                || retention.compareTo(Duration.ofDays(365)) > 0
                || maxReferences < 1 || maxReferences > 50_000
                || maxAssets < 1 || maxAssets > maxReferences
                || maxDocumentBytes < 1024 || maxDocumentBytes > 8 * 1024 * 1024) {
            throw new IllegalArgumentException("Invalid media manifest policy");
        }
        this.retention = retention;
        this.maxReferences = maxReferences;
        this.maxAssets = maxAssets;
        this.maxDocumentBytes = maxDocumentBytes;
    }

    public Duration retention() { return retention; }
    public int maxReferences() { return maxReferences; }
    public int maxAssets() { return maxAssets; }
    public int maxDocumentBytes() { return maxDocumentBytes; }
}
