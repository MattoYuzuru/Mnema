package app.mnema.user.media.service

import app.mnema.user.media.client.MediaApiClient
import app.mnema.user.media.client.MediaResolved
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.cache.concurrent.ConcurrentMapCacheManager

class MediaResolveCacheTest {

    private val mediaApiClient = mock(MediaApiClient::class.java)
    private val cacheManager = ConcurrentMapCacheManager("user-avatar-resolve")
    private val service = MediaResolveCache(mediaApiClient, cacheManager)

    @Test
    fun `returns cached value when not expired`() {
        val mediaId = UUID.randomUUID()
        val cached = MediaResolved(
            mediaId = mediaId,
            url = "https://cdn.example/avatar.png",
            expiresAt = Instant.now().plusSeconds(300)
        )
        cacheManager.getCache("user-avatar-resolve")!!.put(mediaId, cached)

        val result = service.resolveAvatar(mediaId, "token")

        assertEquals(cached, result)
        verify(mediaApiClient, never()).resolve(org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.anyString())
    }

    @Test
    fun `refreshes expired cache entry`() {
        val mediaId = UUID.randomUUID()
        cacheManager.getCache("user-avatar-resolve")!!.put(
            mediaId,
            MediaResolved(mediaId = mediaId, url = "https://expired.example/avatar.png", expiresAt = Instant.now().minusSeconds(5))
        )
        val fresh = MediaResolved(mediaId = mediaId, url = "https://cdn.example/fresh.png", expiresAt = Instant.now().plusSeconds(300))
        `when`(mediaApiClient.resolve(listOf(mediaId), "token")).thenReturn(listOf(fresh))

        val result = service.resolveAvatar(mediaId, "token")

        assertEquals(fresh, result)
    }

    @Test
    fun `returns null when api response is missing valid url`() {
        val mediaId = UUID.randomUUID()
        `when`(mediaApiClient.resolve(listOf(mediaId), "token")).thenReturn(
            listOf(MediaResolved(mediaId = mediaId, expiresAt = Instant.now().plusSeconds(300)))
        )

        assertNull(service.resolveAvatar(mediaId, "token"))
    }
}
