package app.mnema.user.config

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.cache.Cache
import org.springframework.data.redis.connection.RedisConnectionFactory

class CacheConfigTest {

    private val config = CacheConfig()

    @Test
    fun `cache manager builds redis cache manager`() {
        val manager = config.cacheManager(mock(RedisConnectionFactory::class.java))

        assertNotNull(manager)
    }

    @Test
    fun `cache error handler logs and swallows cache exceptions`() {
        val cache = mock(Cache::class.java)
        `when`(cache.name).thenReturn("user-avatar-resolve")
        val handler = config.cacheErrorHandler()
        val exception = RuntimeException("redis unavailable")

        handler.handleCacheGetError(exception, cache, "key")
        handler.handleCachePutError(exception, cache, "key", "value")
        handler.handleCacheEvictError(exception, cache, "key")
        handler.handleCacheClearError(exception, cache)
    }
}
