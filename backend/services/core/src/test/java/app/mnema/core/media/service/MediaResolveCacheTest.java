package app.mnema.core.media.service;

import app.mnema.core.media.client.MediaApiClient;
import app.mnema.core.media.client.MediaResolved;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MediaResolveCacheTest {

    @Test
    void resolveReturnsEmptyMapForNullOrEmptyInput() {
        MediaApiClient client = mock(MediaApiClient.class);
        MediaResolveCache cache = new MediaResolveCache(client, new ConcurrentMapCacheManager("core-media-resolve"));

        assertThat(cache.resolve(null)).isEmpty();
        assertThat(cache.resolve(List.of())).isEmpty();
        verifyNoInteractions(client);
    }

    @Test
    void resolveUsesValidCacheAndFetchesOnlyMissingItems() {
        UUID cachedId = UUID.randomUUID();
        UUID missingId = UUID.randomUUID();
        MediaApiClient client = mock(MediaApiClient.class);
        ConcurrentMapCacheManager cacheManager = new ConcurrentMapCacheManager("core-media-resolve");
        Cache springCache = cacheManager.getCache("core-media-resolve");
        MediaResolved cached = resolved(cachedId, Instant.now().plusSeconds(60));
        springCache.put(cachedId, cached);
        MediaResolved fetched = resolved(missingId, Instant.now().plusSeconds(120));
        when(client.resolve(List.of(missingId))).thenReturn(List.of(fetched));

        MediaResolveCache cache = new MediaResolveCache(client, cacheManager);

        Map<UUID, MediaResolved> resolved = cache.resolve(Arrays.asList(cachedId, null, missingId));

        assertThat(resolved).containsEntry(cachedId, cached).containsEntry(missingId, fetched);
        assertThat(springCache.get(missingId, MediaResolved.class)).isEqualTo(fetched);
    }

    @Test
    void resolveEvictsExpiredCacheEntriesAndSwallowsUpstreamFailure() {
        UUID mediaId = UUID.randomUUID();
        MediaApiClient client = mock(MediaApiClient.class);
        Cache cache = mock(Cache.class);
        CacheManager cacheManager = mock(CacheManager.class);
        when(cacheManager.getCache("core-media-resolve")).thenReturn(cache);
        when(cache.get(mediaId, MediaResolved.class)).thenReturn(resolved(mediaId, Instant.now().minusSeconds(5)));
        when(client.resolve(List.of(mediaId))).thenThrow(new RuntimeException("media unavailable"));

        MediaResolveCache subject = new MediaResolveCache(client, cacheManager);

        assertThat(subject.resolve(List.of(mediaId))).isEmpty();
        verify(cache).evict(mediaId);
    }

    @Test
    void resolveIgnoresInvalidFetchedItemsAndCacheErrors() {
        UUID mediaId = UUID.randomUUID();
        MediaApiClient client = mock(MediaApiClient.class);
        Cache cache = mock(Cache.class);
        CacheManager cacheManager = mock(CacheManager.class);
        when(cacheManager.getCache("core-media-resolve")).thenReturn(cache);
        when(cache.get(mediaId, MediaResolved.class)).thenThrow(new RuntimeException("redis down"));
        when(client.resolve(List.of(mediaId))).thenReturn(List.of(
                new MediaResolved(mediaId, "image", null, "image/png", 1L, null, null, null, Instant.now().plusSeconds(60))
        ));

        MediaResolveCache subject = new MediaResolveCache(client, cacheManager);

        assertThat(subject.resolve(List.of(mediaId))).isEmpty();
    }

    private static MediaResolved resolved(UUID mediaId, Instant expiresAt) {
        return new MediaResolved(mediaId, "image", "https://cdn.example/" + mediaId, "image/png", 1L, null, null, null, expiresAt);
    }
}
