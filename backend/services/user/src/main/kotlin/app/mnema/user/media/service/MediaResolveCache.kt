package app.mnema.user.media.service

import app.mnema.user.media.client.MediaApiClient
import app.mnema.user.media.client.MediaResolved
import org.slf4j.LoggerFactory
import org.springframework.cache.CacheManager
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

@Service
class MediaResolveCache(
    private val mediaApiClient: MediaApiClient,
    private val cacheManager: CacheManager
) {
    private val log = LoggerFactory.getLogger(MediaResolveCache::class.java)
    private val cacheName = "user-avatar-resolve"

    fun resolveAvatar(mediaId: UUID, bearerToken: String): MediaResolved? {
        val cache = cacheManager.getCache(cacheName)
        val now = Instant.now()

        val cached = safeGet(cache, mediaId)
        if (cached != null && isValid(cached, now)) {
            return cached
        }
        if (cached != null) {
            safeEvict(cache, mediaId)
        }

        return try {
            val fetched = mediaApiClient.resolve(listOf(mediaId), bearerToken)
                .firstOrNull { it.mediaId == mediaId }
            if (fetched != null && isValid(fetched, now)) {
                safePut(cache, mediaId, fetched)
                fetched
            } else {
                null
            }
        } catch (ex: RuntimeException) {
            log.warn("Media resolve failed, returning cached results: {}", ex.message)
            null
        }
    }

    private fun isValid(resolved: MediaResolved, now: Instant): Boolean {
        return resolved.url != null && resolved.expiresAt != null && resolved.expiresAt.isAfter(now)
    }

    private fun safeGet(cache: org.springframework.cache.Cache?, mediaId: UUID): MediaResolved? {
        if (cache == null) return null
        return try {
            cache.get(mediaId, MediaResolved::class.java)
        } catch (ex: RuntimeException) {
            log.warn("Cache get failed for {}: {}", cacheName, ex.message)
            null
        }
    }

    private fun safePut(cache: org.springframework.cache.Cache?, mediaId: UUID, value: MediaResolved) {
        if (cache == null) return
        try {
            cache.put(mediaId, value)
        } catch (ex: RuntimeException) {
            log.warn("Cache put failed for {}: {}", cacheName, ex.message)
        }
    }

    private fun safeEvict(cache: org.springframework.cache.Cache?, mediaId: UUID) {
        if (cache == null) return
        try {
            cache.evict(mediaId)
        } catch (ex: RuntimeException) {
            log.warn("Cache evict failed for {}: {}", cacheName, ex.message)
        }
    }
}
