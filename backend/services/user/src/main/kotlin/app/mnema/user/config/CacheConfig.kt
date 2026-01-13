package app.mnema.user.config

import app.mnema.user.media.client.MediaResolved
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.slf4j.LoggerFactory
import org.springframework.cache.Cache
import org.springframework.cache.annotation.EnableCaching
import org.springframework.cache.interceptor.CacheErrorHandler
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.cache.RedisCacheConfiguration
import org.springframework.data.redis.cache.RedisCacheManager
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer
import org.springframework.data.redis.serializer.RedisSerializationContext
import org.springframework.data.redis.serializer.StringRedisSerializer
import java.time.Duration

@Configuration
@EnableCaching
class CacheConfig {
    private val log = LoggerFactory.getLogger(CacheConfig::class.java)

    @Bean
    fun cacheManager(connectionFactory: RedisConnectionFactory): RedisCacheManager {
        val ttl = Duration.ofMinutes(10).minusSeconds(30)
        val baseMapper = ObjectMapper()
            .registerModule(JavaTimeModule())
            .registerModule(KotlinModule.Builder().build())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        val genericMapper = baseMapper.copy()
        genericMapper.activateDefaultTyping(LaissezFaireSubTypeValidator.instance, ObjectMapper.DefaultTyping.NON_FINAL)
        val config = RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(ttl)
            .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(StringRedisSerializer()))
            .serializeValuesWith(
                RedisSerializationContext.SerializationPair.fromSerializer(GenericJackson2JsonRedisSerializer(genericMapper))
            )

        val resolvedSerializer = Jackson2JsonRedisSerializer(baseMapper, MediaResolved::class.java)
        val resolvedConfig = RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(ttl)
            .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(StringRedisSerializer()))
            .serializeValuesWith(
                RedisSerializationContext.SerializationPair.fromSerializer(resolvedSerializer)
            )

        return RedisCacheManager.builder(connectionFactory)
            .cacheDefaults(config)
            .withCacheConfiguration("user-avatar-resolve", resolvedConfig)
            .build()
    }

    @Bean
    fun cacheErrorHandler(): CacheErrorHandler = object : CacheErrorHandler {
        override fun handleCacheGetError(exception: RuntimeException, cache: Cache, key: Any) {
            log.warn("Cache get failed for {}: {}", cache.name, exception.message)
        }

        override fun handleCachePutError(exception: RuntimeException, cache: Cache, key: Any, value: Any?) {
            log.warn("Cache put failed for {}: {}", cache.name, exception.message)
        }

        override fun handleCacheEvictError(exception: RuntimeException, cache: Cache, key: Any) {
            log.warn("Cache evict failed for {}: {}", cache.name, exception.message)
        }

        override fun handleCacheClearError(exception: RuntimeException, cache: Cache) {
            log.warn("Cache clear failed for {}: {}", cache.name, exception.message)
        }
    }
}
