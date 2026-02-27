package app.mnema.media.service;

import app.mnema.media.controller.dto.ResolvedMedia;
import app.mnema.media.domain.entity.MediaAssetEntity;
import app.mnema.media.service.policy.MediaPolicy;
import app.mnema.media.storage.ObjectStorage;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class MediaResolveCache {
    private final ObjectStorage storage;
    private final MediaPolicy policy;

    public MediaResolveCache(ObjectStorage storage, MediaPolicy policy) {
        this.storage = storage;
        this.policy = policy;
    }

    @Cacheable(cacheNames = "media-resolve", key = "#asset.mediaId")
    public ResolvedMedia resolve(MediaAssetEntity asset) {
        var presigned = storage.presignGet(asset.getStorageKey(), policy.presignTtl(), asset.getOriginalFileName());
        Instant expiresAt = Instant.now().plus(policy.presignTtl());
        return new ResolvedMedia(
                asset.getMediaId(),
                asset.getKind(),
                presigned.url(),
                asset.getMimeType(),
                asset.getSizeBytes(),
                asset.getDurationSeconds(),
                asset.getWidth(),
                asset.getHeight(),
                expiresAt
        );
    }

    @CacheEvict(cacheNames = "media-resolve", key = "#mediaId")
    public void evict(UUID mediaId) {
        // Intentionally empty: annotation handles eviction.
    }
}
