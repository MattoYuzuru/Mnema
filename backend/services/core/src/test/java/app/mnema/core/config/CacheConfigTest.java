package app.mnema.core.config;

import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CacheConfigTest {

    @Test
    void cacheManagerBuildsRedisManager() {
        CacheConfig config = new CacheConfig();

        assertThat(config.cacheManager(mock(RedisConnectionFactory.class))).isNotNull();
    }

    @Test
    void cacheErrorHandlerSwallowsFailuresForKnownAndUnknownCaches() {
        CacheConfig config = new CacheConfig();
        Cache cache = mock(Cache.class);
        when(cache.getName()).thenReturn("core-media-resolve");

        var handler = config.cacheErrorHandler();
        RuntimeException ex = new RuntimeException("redis down");
        handler.handleCacheGetError(ex, cache, "key");
        handler.handleCachePutError(ex, cache, "key", "value");
        handler.handleCacheEvictError(ex, cache, "key");
        handler.handleCacheClearError(ex, cache);

        handler.handleCacheGetError(ex, null, "key");
    }
}
