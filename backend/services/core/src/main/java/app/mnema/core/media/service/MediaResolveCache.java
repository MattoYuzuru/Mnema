package app.mnema.core.media.service;

import app.mnema.core.media.client.MediaApiClient;
import app.mnema.core.media.client.MediaResolved;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class MediaResolveCache {
    private static final Logger log = LoggerFactory.getLogger(MediaResolveCache.class);
    private static final String CACHE_NAME = "core-media-resolve";

    private final MediaApiClient mediaApiClient;
    private final CacheManager cacheManager;

    public MediaResolveCache(MediaApiClient mediaApiClient, CacheManager cacheManager) {
        this.mediaApiClient = mediaApiClient;
        this.cacheManager = cacheManager;
    }

    public Map<UUID, MediaResolved> resolve(List<UUID> mediaIds) {
        if (mediaIds == null || mediaIds.isEmpty()) {
            return Map.of();
        }

        Cache cache = cacheManager.getCache(CACHE_NAME);
        Map<UUID, MediaResolved> resolved = new LinkedHashMap<>();
        Set<UUID> missing = new LinkedHashSet<>();
        Instant now = Instant.now();

        for (UUID mediaId : mediaIds) {
            if (mediaId == null) {
                continue;
            }
            MediaResolved cached = safeGet(cache, mediaId);
            if (isValid(cached, now)) {
                resolved.put(mediaId, cached);
                continue;
            }
            if (cached != null) {
                safeEvict(cache, mediaId);
            }
            missing.add(mediaId);
        }

        if (missing.isEmpty()) {
            return resolved;
        }

        List<UUID> missingList = new ArrayList<>(missing);
        try {
            List<MediaResolved> fetched = mediaApiClient.resolve(missingList);
            for (MediaResolved item : fetched) {
                if (!isValid(item, now)) {
                    continue;
                }
                resolved.put(item.mediaId(), item);
                safePut(cache, item.mediaId(), item);
            }
        } catch (RuntimeException ex) {
            log.warn("Media resolve failed, returning cached results: {}", ex.getMessage());
        }

        return resolved;
    }

    private boolean isValid(MediaResolved resolved, Instant now) {
        return resolved != null
                && resolved.mediaId() != null
                && resolved.url() != null
                && resolved.expiresAt() != null
                && resolved.expiresAt().isAfter(now);
    }

    private MediaResolved safeGet(Cache cache, UUID mediaId) {
        if (cache == null) {
            return null;
        }
        try {
            return cache.get(mediaId, MediaResolved.class);
        } catch (RuntimeException ex) {
            log.warn("Cache get failed for {}: {}", CACHE_NAME, ex.getMessage());
            return null;
        }
    }

    private void safePut(Cache cache, UUID mediaId, MediaResolved value) {
        if (cache == null) {
            return;
        }
        try {
            cache.put(mediaId, value);
        } catch (RuntimeException ex) {
            log.warn("Cache put failed for {}: {}", CACHE_NAME, ex.getMessage());
        }
    }

    private void safeEvict(Cache cache, UUID mediaId) {
        if (cache == null) {
            return;
        }
        try {
            cache.evict(mediaId);
        } catch (RuntimeException ex) {
            log.warn("Cache evict failed for {}: {}", CACHE_NAME, ex.getMessage());
        }
    }
}
